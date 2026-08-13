// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib

/** Structural facts harvested from a design's HW-dialect MLIR, to be transported to a formal
  * engine as strengthening invariants.
  *
  * The recognizers work at the `hw`/`comb`/`seq` level firtool and `circt-verilog` lower to,
  * where a Verilog `always`/`case` has become a tree of `comb.mux`es over `seq.firreg`s. That
  * loses the source `case` structure but keeps exactly what a counter-bound invariant needs:
  * which registers are FSMs, which are bounded counters, and how each counter updates.
  */

/** A register whose next value is its own value, its value plus a constant, or a constant —
  * the increment/clear/hold shape of a bounded counter.
  */
final case class CounterFact(
  name:       String,
  width:      Int,
  increments: Boolean,
  clears:     Boolean)

/** A register selected against its own value by equality tests and reset to a constant state
  * — a finite state machine.
  */
final case class FsmFact(
  name:       String,
  width:      Int,
  resetState: Option[Int])

final case class Harvest(
  counters: Seq[CounterFact],
  fsms:     Seq[FsmFact])

/** The counter and FSM located in a flattened btor2 by structural role, where register names
  * are gone: the counter is the node an `ult`-against-the-property-bound reads, the FSM is the
  * node the most `eq`-against-distinct-constants read.
  */
final case class Btor2Structure(
  counterNode: Long,
  bound:       BigInt,
  fsmNode:     Long,
  fsmStates:   Set[Int])

object Harvester:

  def harvest(mlir: os.Path, module: String): Harvest =
    val design = MlirText.parse(os.read(mlir), module)
    Harvest(
      counters = design.registers.flatMap(counterFact(design, _)),
      fsms = design.registers.flatMap(fsmFact(design, _))
    )

  /** Locate the counter and FSM in a flattened btor2 by the comparisons they feed: the
    * counter is the 4-bit node an `ult`-against-`bound` reads (the property's own bound), the
    * FSM is the 4-bit node the most `eq`-against-distinct-constants read.
    */
  def locateBtor2(design: Btor2Design, bound: BigInt): Btor2Structure =
    val ops = design.binaryOps
    val counterNode = ops.collectFirst {
      case ("ult", _, a, c) if design.constOf(c).contains(bound) && design.sortWidthOf(a) == 4 => a
    }.getOrElse(throw new IllegalStateException(s"no `ult _ <const $bound>` locating the counter"))

    val eqConstants = ops.collect {
      case ("eq", _, a, c) if design.sortWidthOf(a) == 4 => design.constOf(c).map(a -> _)
    }.flatten
    val (fsmNode, states) = eqConstants
      .groupMap(_._1)(_._2.toInt)
      .view
      .mapValues(_.toSet)
      .maxByOption(_._2.size)
      .getOrElse(throw new IllegalStateException("no 4-bit node dispatched by eq-against-constants (no FSM)"))
    require(states.size >= 5, s"strongest eq-dispatch has only ${states.size} states; not an FSM")

    Btor2Structure(counterNode, bound, fsmNode, states)

  /** Discover, by bounded reachability, the set of FSM states in which the counter can reach
    * `threshold` — the tail set. A state is included only if `counter >= threshold ∧ fsm == s`
    * is reachable within `kmax`; a bounded miss excludes it. Sound as a discovery step because
    * the resulting invariant is separately certified by [[validateTailSet]].
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

  /** Certify `counter >= threshold ⟶ fsm ∈ tail` as an invariant by BMC-as-bad: its negation
    * must be unreachable to `kmax`.
    */
  def validateTailSet(
    design:    Btor2Design,
    struct:    Btor2Structure,
    threshold: BigInt,
    tail:      Set[Int],
    kmax:      Int
  ): Btor2Result =
    val inTail = tail.map(s => Btor2Pred.eq(struct.fsmNode, s)).reduce(Btor2Pred.Or.apply)
    val negation = Btor2Pred.And(Btor2Pred.uge(struct.counterNode, threshold), Btor2Pred.Not(inTail))
    Btor2.checkPred(design, negation, kmax)

  /** A counter's driver cone contains only the register itself, constants, `comb.add` /
    * `comb.sub` of the register with a constant, and the `comb.mux`es that select among them.
    */
  private def counterFact(design: MlirDesign, reg: MlirRegister): Option[CounterFact] =
    val self = reg.name
    var increments = false
    var clears     = false
    var ok         = true

    def walk(value: String, seen: Set[String]): Unit =
      if value == self || seen(value) then ()
      else
        design.node(value) match
          case Some(MlirNode("hw.constant", _, _)) =>
            if design.constantValue(value).contains(BigInt(0)) then clears = true
          case Some(MlirNode("comb.mux", operands, _))                                =>
            operands.tail.foreach(walk(_, seen + value))
          case Some(MlirNode(op @ ("comb.add" | "comb.sub"), operands, _))            =>
            // A step by a constant on the register itself.
            val touchesSelf = operands.contains(self)
            val byConstant  = operands.exists(design.node(_).exists(_.op == "hw.constant"))
            if touchesSelf && byConstant then increments = true else ok = false
          case _                                                                      =>
            ok = false

    walk(reg.driver, Set.empty)
    Option.when(ok && (increments || clears))(CounterFact(self.stripPrefix("%"), reg.width, increments, clears))

  /** An FSM holds its own value unless a guard fires, and its own value is compared for
    * equality somewhere in the design (the `case` dispatch, now `comb.icmp eq`).
    */
  private def fsmFact(design: MlirDesign, reg: MlirRegister): Option[FsmFact] =
    val self = reg.name
    val holds = design.node(reg.driver) match
      case Some(MlirNode("comb.mux", operands, _)) => operands.lastOption.contains(self)
      case _                                       => false
    val dispatched = design.nodes.exists { node =>
      node.op == "comb.icmp" && node.operands.contains(self) &&
        design.icmpPredicate(node.result).exists(pred => pred == "eq" || pred == "ceq")
    }
    Option.when(holds && dispatched)(FsmFact(self.stripPrefix("%"), reg.width, reg.resetValue))
