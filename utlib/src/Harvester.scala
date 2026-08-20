// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib

/** Structural facts harvested from a design's HW-dialect MLIR, to be transported to a formal engine as strengthening
  * invariants.
  *
  * The recognizers work at the `hw`/`comb`/`seq` level firtool and `circt-verilog` lower to, where a Verilog
  * `always`/`case` has become a tree of `comb.mux`es over `seq.firreg`s. That loses the source `case` structure but
  * keeps exactly what a counter-bound invariant needs: which registers are FSMs, which are bounded counters, and how
  * each counter updates.
  */

/** A register whose next value is its own value, its value plus a constant, or a constant — the increment/clear/hold
  * shape of a bounded counter.
  */
final case class CounterFact(
  name:          String,
  width:         Int,
  increments:    Boolean,
  clears:        Boolean,
  controlInputs: Set[String])
    extends Construct

/** A register selected against its own value by equality tests and reset to a constant state — a finite state machine.
  * `controlInputs` are the transition conditions (the external signals that gate the next-state), made explicit rather
  * than dropped.
  */
final case class FsmFact(
  name:          String,
  width:         Int,
  resetState:    Option[Int],
  controlInputs: Set[String])
    extends Construct

/** A recognized hardware construct is an *open* interface, not a closed box: besides its own `state` register it names
  * the external `controlInputs` its next-state logic depends on (module inputs and other registers). Invariants over
  * the state alone are intrinsic (sound by the construct's shape); relational ones that cross `controlInputs` are left
  * to certification or generator contracts.
  */
sealed trait Construct:
  def name:          String
  def width:         Int
  def controlInputs: Set[String]

/** Recognizes one construct family on a register from the design's HW-dialect MLIR. Adding a family is adding a
  * `Recognizer`; the certify/inject engine downstream is shared.
  */
trait Recognizer:
  type Out <: Construct
  def recognize(design: MlirDesign, reg: MlirRegister): Option[Out]

final case class Harvest(
  counters: Seq[CounterFact],
  fsms: Seq[FsmFact]):
  def constructs: Seq[Construct] = counters ++ fsms

/** The counter and FSM located in a flattened btor2 by structural role, where register names are gone: the counter is
  * the node an `ult`-against-the-property-bound reads, the FSM is the node the most `eq`-against-distinct-constants
  * read.
  */
final case class Btor2Structure(
  counterNode: Long,
  bound:       BigInt,
  ultNode:     Long,
  fsmNode:     Long,
  fsmStates:   Set[Int],
  eqNodes:     Map[Int, Long])

object Harvester:

  /** The bounded-counter recognizer. */
  object CounterRecognizer extends Recognizer:
    type Out = CounterFact
    def recognize(design: MlirDesign, reg: MlirRegister): Option[CounterFact] = counterFact(design, reg)

  /** The finite-state-machine recognizer. */
  object FsmRecognizer extends Recognizer:
    type Out = FsmFact
    def recognize(design: MlirDesign, reg: MlirRegister): Option[FsmFact] = fsmFact(design, reg)

  /** The recognizer library — extend it to cover more construct families (one-hot, FIFO, …). */
  val recognizers: Seq[Recognizer] = Seq(CounterRecognizer, FsmRecognizer)

  def harvest(mlir: os.Path, module: String): Harvest =
    val design = MlirText.parse(os.read(mlir), module)
    Harvest(
      counters = design.registers.flatMap(CounterRecognizer.recognize(design, _)),
      fsms = design.registers.flatMap(FsmRecognizer.recognize(design, _))
    )

  /** The external signals (module inputs / other registers) in `reg`'s next-state cone — the construct's interface.
    * Walks the driver's fanin, stopping at the register itself and constants; values not defined by a comb/hw node
    * (module inputs, block args) and other registers are the control inputs.
    */
  private def controlInputs(design: MlirDesign, reg: MlirRegister): Set[String] =
    val self   = reg.name
    val seen   = scala.collection.mutable.Set.empty[String]
    val inputs = scala.collection.mutable.Set.empty[String]
    def walk(v: String): Unit =
      if v == self || seen(v) then ()
      else
        seen += v
        design.node(v) match
          case Some(n) if n.op == "hw.constant" => ()
          case Some(n)                          => n.operands.foreach(walk)
          case None                             => inputs += v
    walk(reg.driver)
    inputs.toSet

  /** Locate the counter and FSM in a flattened btor2 by the comparisons they feed: the counter is the 4-bit node an
    * `ult`-against-`bound` reads (the property's own bound), the FSM is the 4-bit node the most
    * `eq`-against-distinct-constants read.
    */
  def locateBtor2(design: Btor2Design, bound: BigInt): Btor2Structure =
    val ops                    = design.binaryOps
    val (ultNode, counterNode) = ops.collectFirst {
      case ("ult", r, a, c) if design.constOf(c).contains(bound) && design.sortWidthOf(a) == 4 => (r, a)
    }.getOrElse(throw new IllegalStateException(s"no `ult _ <const $bound>` locating the counter"))

    // (fsm node, its per-state eq nodes): the node the most eq-against-distinct-constants read.
    val eqByNode             = ops.collect {
      case ("eq", r, a, c) if design.sortWidthOf(a) == 4 => design.constOf(c).map(v => (a, v.toInt, r))
    }.flatten.groupBy(_._1)
    val (fsmNode, eqTriples) = eqByNode.view
      .mapValues(_.distinctBy(_._2))
      .maxByOption(_._2.size)
      .getOrElse(throw new IllegalStateException("no 4-bit node dispatched by eq-against-constants (no FSM)"))
    require(eqTriples.size >= 5, s"strongest eq-dispatch has only ${eqTriples.size} states; not an FSM")

    Btor2Structure(
      counterNode,
      bound,
      ultNode,
      fsmNode,
      eqTriples.map(_._2).toSet,
      eqTriples.map(t => t._2 -> t._3).toMap
    )

  /** Reproduce a k-induction closure of an SVA implication-check by emitting the harvested invariant in the checker's
    * *sampled* frame.
    *
    * Yosys compiles `antecedent |-> consequent` into `concat(antecedent, consequent) == p` sampled through a two-frame
    * latch pipeline; its free latches let bare induction sustain a phantom violation, so the strengthening must be
    * stated one frame delayed, exactly as the checker samples. This locates the antecedent (the concat-partner of the
    * counter's `ult`), the stage-2 latch (the state whose `next` feeds the `bad`), and the tail-state `eq` nodes, then
    * adds one-frame-delayed samples of `counter ≥ bound`, `fsm ∈ tail`, and the antecedent with the three constraints
    * that close it: C2 (high count ⟶ tail), C3 (tail ⟶ no antecedent), C4 (stage-2 latch clear).
    */
  def closeSampled(design: Btor2Design, struct: Btor2Structure, tail: Set[Int]): Btor2Design =
    // Node ids referenced by an op line, dropping its result-sort token (operands are the
    // trailing ids; the leading token of a typed op is the sort). `bad`/`constraint` carry no
    // sort, so keep all trailing ids.
    def refs(op: String, rest: Seq[String]): Seq[Long] =
      val ids = (if op == "bad" || op == "constraint" then rest else rest.drop(1)).flatMap(_.toLongOption)
      ids

    // The antecedent: the first operand of the concat whose other operand is the `ult` node.
    val antecedent = design.opLines.collectFirst {
      case ("concat", _, rest) if refs("concat", rest).lift(1).contains(struct.ultNode) =>
        refs("concat", rest).head
    }.getOrElse(throw new IllegalStateException("no concat pairing the antecedent with the counter `ult`"))

    // The bad condition's direct operands, and the stage-2 latch feeding them.
    val badCond     = design.opLines.collectFirst { case ("bad", _, rest) => refs("bad", rest).head }
      .getOrElse(throw new IllegalStateException("no `bad` node"))
    val badOperands = design.opLines.collectFirst { case (op, `badCond`, rest) => refs(op, rest) }
      .getOrElse(Seq.empty)
      .toSet
    val stage2      = design.opLines.collectFirst {
      case ("next", _, rest)
          if refs("next", rest).lift(1).exists(badOperands.contains)
            && refs("next", rest).headOption.exists(design.isState) =>
        refs("next", rest).head
    }.getOrElse(throw new IllegalStateException("no stage-2 latch whose next feeds the bad"))

    val tailEqs = tail.toSeq.sorted.map(s =>
      struct.eqNodes.getOrElse(s, throw new IllegalStateException(s"no eq node for tail state $s"))
    )

    val sort1 = design.sortForWidth(1)
    var id    = design.topId
    def fresh(): Long = { id += 1; id }
    val out = scala.collection.mutable.ListBuffer.empty[String]
    def emit(line: String): Long = { out += line; id }

    val zero    = { fresh(); emit(s"$id constd $sort1 0") }
    // Combinational: fsm ∈ tail, and counter ≥ bound.
    val inTail  = tailEqs.reduceLeft { (acc, e) =>
      fresh(); emit(s"$id or $sort1 $acc $e")
    }
    val geBound = { fresh(); emit(s"$id not $sort1 ${struct.ultNode}") }
    // One-frame-delayed samples, init 0, matching the checker's latch timing.
    def sample(sig: Long): Long =
      val s = fresh(); emit(s"$s state $sort1")
      fresh(); emit(s"$id init $sort1 $s $zero")
      fresh(); emit(s"$id next $sort1 $s $sig")
      s
    val sTail = sample(inTail)
    val sAnte         = sample(antecedent)
    val sHigh         = sample(geBound)
    // C2: sampled high count implies sampled tail. C3: sampled tail excludes sampled
    // antecedent. C4: the checker's stage-2 latch never sets.
    fresh(); val c2   = emit(s"$id implies $sort1 $sHigh $sTail"); fresh(); emit(s"$id constraint $c2")
    fresh(); val both = emit(s"$id and $sort1 $sTail $sAnte")
    fresh(); val c3   = emit(s"$id not $sort1 $both"); fresh(); emit(s"$id constraint $c3")
    fresh(); val c4   = emit(s"$id not $sort1 $stage2"); fresh(); emit(s"$id constraint $c4")

    design.appended(out.toSeq)

  /** Discover, by bounded reachability, the set of FSM states in which the counter can reach `threshold` — the tail
    * set. A state is included only if `counter >= threshold ∧ fsm == s` is reachable within `kmax`; a bounded miss
    * excludes it. Sound as a discovery step because the resulting invariant is separately certified by
    * [[validateTailSet]].
    */
  def sieveTailSet(
    design:     Btor2Design,
    struct:     Btor2Structure,
    threshold:  BigInt,
    kmax:       Int,
    candidates: Set[Int] = null
  ): Set[Int] =
    Option(candidates).getOrElse(struct.fsmStates).filter { s =>
      val pred = Btor2Pred.And(
        Btor2Pred.uge(struct.counterNode, threshold),
        Btor2Pred.eq(struct.fsmNode, s)
      )
      Btor2.checkPred(design, pred, kmax) match
        case Btor2Result.Reachable(_) => true
        case _                        => false
    }

  /** Certify `counter >= threshold ⟶ fsm ∈ tail` as an invariant by BMC-as-bad: its negation must be unreachable to
    * `kmax`.
    */
  def validateTailSet(
    design:    Btor2Design,
    struct:    Btor2Structure,
    threshold: BigInt,
    tail:      Set[Int],
    kmax:      Int
  ): Btor2Result =
    val inTail   = tail.map(s => Btor2Pred.eq(struct.fsmNode, s)).reduce(Btor2Pred.Or.apply)
    val negation = Btor2Pred.And(Btor2Pred.uge(struct.counterNode, threshold), Btor2Pred.Not(inTail))
    Btor2.checkPred(design, negation, kmax)

  /** A counter's driver cone contains only the register itself, constants, `comb.add` / `comb.sub` of the register with
    * a constant, and the `comb.mux`es that select among them.
    */
  private def counterFact(design: MlirDesign, reg: MlirRegister): Option[CounterFact] =
    val self       = reg.name
    var increments = false
    var clears     = false
    var ok         = true

    def walk(value: String, seen: Set[String]): Unit =
      if value == self || seen(value) then ()
      else
        design.node(value) match
          case Some(MlirNode("hw.constant", _, _))                         =>
            if design.constantValue(value).contains(BigInt(0)) then clears = true
          case Some(MlirNode("comb.mux", operands, _))                     =>
            operands.tail.foreach(walk(_, seen + value))
          case Some(MlirNode(op @ ("comb.add" | "comb.sub"), operands, _)) =>
            // A step by a constant on the register itself.
            val touchesSelf = operands.contains(self)
            val byConstant  = operands.exists(design.node(_).exists(_.op == "hw.constant"))
            if touchesSelf && byConstant then increments = true else ok = false
          case _                                                           =>
            ok = false

    walk(reg.driver, Set.empty)
    Option.when(ok && (increments || clears))(
      CounterFact(self.stripPrefix("%"), reg.width, increments, clears, controlInputs(design, reg))
    )

  /** An FSM holds its own value unless a guard fires, and its own value is compared for equality somewhere in the
    * design (the `case` dispatch, now `comb.icmp eq`).
    */
  private def fsmFact(design: MlirDesign, reg: MlirRegister): Option[FsmFact] =
    val self       = reg.name
    val holds      = design.node(reg.driver) match
      case Some(MlirNode("comb.mux", operands, _)) => operands.lastOption.contains(self)
      case _                                       => false
    val dispatched = design.nodes.exists { node =>
      node.op == "comb.icmp" && node.operands.contains(self) &&
      design.icmpPredicate(node.result).exists(pred => pred == "eq" || pred == "ceq")
    }
    Option.when(holds && dispatched)(
      FsmFact(self.stripPrefix("%"), reg.width, reg.resetValue, controlInputs(design, reg))
    )
