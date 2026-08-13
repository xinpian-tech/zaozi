// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib

/** A btor2 model as a mutable-append text, enough to certify and inject harvested invariants.
  *
  * The parser is line-oriented and keeps every original line verbatim; new nodes are appended
  * with fresh ids. This is not a semantic model of btor2 — it only needs to find state nodes,
  * their sorts, and to build comparison/boolean nodes over them so a predicate can be added
  * either as a `constraint` (inject the invariant) or as a `bad` (validate it, BMC-as-bad).
  */
final class Btor2Design private (
  private val lines: Vector[String]):

  private val maxId: Long =
    lines.iterator.flatMap(_.trim.split("\\s+").headOption).flatMap(_.toLongOption).maxOption.getOrElse(0L)

  /** id -> declared bit width, for `sort bitvec` lines. */
  private val sortBits: Map[Long, Int] =
    lines.iterator.flatMap { line =>
      line.trim.split("\\s+") match
        case Array(id, "sort", "bitvec", w) => id.toLongOption.zip(w.toIntOption)
        case _                              => None
    }.toMap

  /** id -> sort id, for `state` lines. */
  private val stateSort: Map[Long, Long] =
    lines.iterator.flatMap { line =>
      line.trim.split("\\s+") match
        case Array(id, "state", sort, _*) => id.toLongOption.zip(sort.toLongOption)
        case _                            => None
    }.toMap

  private val stateByName: Map[String, Long] =
    lines.iterator.flatMap { line =>
      line.trim.split("\\s+") match
        case Array(id, "state", _, name) => id.toLongOption.map(name -> _)
        case _                           => None
    }.toMap

  def stateNamed(name:  String): Option[Long] = stateByName.get(name)
  def sortWidth(nodeId: Long):   Int          = sortBits(stateSort(nodeId))

  private def sortForWidth(width: Int): Long =
    sortBits.collectFirst { case (id, `width`) => id }.getOrElse(
      throw new IllegalStateException(s"no `sort bitvec $width` in the model to reuse")
    )

  def text: String = lines.mkString("\n") + "\n"

  private def append(newLines: Seq[String]): Btor2Design = new Btor2Design(lines ++ newLines)

  /** Result sort id of any node whose line is `<id> <op> <sortid> ...`. */
  private val resultSort: Map[Long, Long] =
    lines.iterator.flatMap { line =>
      line.trim.split("\\s+") match
        case Array(id, op, sort, _*) if op != "sort" && sort.toLongOption.isDefined =>
          id.toLongOption.zip(sort.toLongOption)
        case _                                                                      => None
    }.toMap

  /** Bit width of any node's result, or -1 if unknown (e.g. it carries no sort). */
  private[utlib] def sortWidthOf(nodeId: Long): Int =
    resultSort.get(nodeId).flatMap(sortBits.get).getOrElse(-1)

  /** Sort id of any node's result — states and computed nodes alike. */
  private def sortOf(nodeId: Long): Long =
    resultSort.getOrElse(nodeId, stateSort(nodeId))

  /** Every `<id> <op> <sort> <a> <b>` line, as (op, result, a, b). Used to locate nodes by
    * the comparisons they feed.
    */
  private[utlib] def binaryOps: Seq[(String, Long, Long, Long)] =
    lines.iterator.flatMap { line =>
      line.trim.split("\\s+") match
        case Array(id, op, _, a, b) =>
          for i <- id.toLongOption; x <- a.toLongOption; y <- b.toLongOption yield (op, i, x, y)
        case _                      => None
    }.toSeq

  private val lineByResult: Map[Long, Array[String]] =
    lines.iterator.flatMap { line =>
      val toks = line.trim.split("\\s+")
      toks.headOption.flatMap(_.toLongOption).map(_ -> toks)
    }.toMap

  /** Value of a constant node, following the zero-extend (`uext`) that btor2 inserts when a
    * narrow enum constant is compared against a wider signal — the enum `eq` dispatch reads a
    * `uext` of the constant, not the constant itself.
    */
  private[utlib] def constOf(nodeId: Long): Option[BigInt] =
    lineByResult.get(nodeId).flatMap {
      case Array(_, "constd", _, v)      => v.toIntOption.map(BigInt(_))
      case Array(_, "const", _, bits)    => scala.util.Try(BigInt(bits, 2)).toOption
      case Array(_, "uext", _, op, _)    => op.toLongOption.flatMap(constOf)
      case _                             => None
    }

  /** Materialize `pred` into fresh btor2 nodes; return the extended design and the boolean
    * node id carrying the predicate's truth.
    */
  private def emit(pred: Btor2Pred): (Btor2Design, Long) =
    val bool1 = sortForWidth(1)
    var next  = maxId
    val out   = scala.collection.mutable.ListBuffer.empty[String]
    def fresh(node: String => String): Long =
      next += 1; out += node(next.toString); next

    def go(p: Btor2Pred): Long = p match
      case Btor2Pred.Cmp(node, op, v) =>
        val sort = sortOf(node)
        op match
          case CmpOp.Ult =>
            val c = fresh(id => s"$id constd $sort $v"); fresh(id => s"$id ult $bool1 $node $c")
          case CmpOp.Uge =>
            val c = fresh(id => s"$id constd $sort $v"); val lt = fresh(id => s"$id ult $bool1 $node $c")
            fresh(id => s"$id not $bool1 $lt")
          case CmpOp.Eq  =>
            val c = fresh(id => s"$id constd $sort $v"); fresh(id => s"$id eq $bool1 $node $c")
      case Btor2Pred.Not(p0)          => val a = go(p0); fresh(id => s"$id not $bool1 $a")
      case Btor2Pred.And(l, r)        => val a = go(l); val b = go(r); fresh(id => s"$id and $bool1 $a $b")
      case Btor2Pred.Or(l, r)         => val a = go(l); val b = go(r); fresh(id => s"$id or $bool1 $a $b")

    val boolId = go(pred)
    (append(out.toSeq), boolId)

  /** Add `pred` as an assumption constraining every reachable state. */
  def withConstraint(pred: Btor2Pred): Btor2Design =
    val (design, boolId) = emit(pred)
    design.append(Seq(s"${design.maxId + 1} constraint $boolId"))

  /** Replace the model's target(s) with a single `bad` asserting `pred` is reachable. */
  def asBad(pred: Btor2Pred): Btor2Design =
    val withoutBad       = new Btor2Design(lines.filterNot(_.trim.split("\\s+").lift(1).contains("bad")))
    val (design, boolId) = withoutBad.emit(pred)
    design.append(Seq(s"${design.maxId + 1} bad $boolId"))

object Btor2Design:
  private[utlib] def fromLines(lines: Vector[String]): Btor2Design = new Btor2Design(lines)

/** Unsigned comparison of a btor2 node against a constant. */
enum CmpOp:
  case Ult, Uge, Eq

/** A small predicate AST over btor2 nodes, lowered to fresh comparison/boolean nodes. */
enum Btor2Pred:
  case Cmp(node: Long, op: CmpOp, value: BigInt)
  case Not(p: Btor2Pred)
  case And(l: Btor2Pred, r: Btor2Pred)
  case Or(l: Btor2Pred, r: Btor2Pred)

object Btor2Pred:
  def ult(node: Long, v: BigInt): Btor2Pred = Cmp(node, CmpOp.Ult, v)
  def uge(node: Long, v: BigInt): Btor2Pred = Cmp(node, CmpOp.Uge, v)
  def eq(node:  Long, v: BigInt): Btor2Pred = Cmp(node, CmpOp.Eq, v)

/** btormc's verdict for a single-bad model. */
enum Btor2Result:
  /** A `bad` was reached at `depth`. */
  case Reachable(depth: Int)

  /** No `bad` was reached within `kmax` frames (bounded, not a proof). */
  case UnreachableWithin(kmax: Int)

  /** k-induction proved the `bad` unreachable. */
  case Proven(k: Int)

object Btor2:
  def parse(text: String): Btor2Design =
    Btor2Design.fromLines(text.linesIterator.filterNot(_.isBlank).toVector)

  /** Run btormc over `design` and read the verdict. Plain BMC by default; `kind` adds
    * k-induction so an `UnreachableWithin` can be upgraded to a `Proven`.
    */
  def check(design: Btor2Design, kmax: Int, kind: Boolean = false): Btor2Result =
    val file = os.temp(design.text, suffix = ".btor2")
    try
      val mcArgs = Seq("btormc", "-kmax", kmax.toString) ++
        (if kind then Seq("--kind", "-v") else Seq.empty) ++ Seq(file.toString)
      val result = os
        .proc(Seq("nix", "shell", "nixpkgs#boolector", "-c") ++ mcArgs)
        .call(check = false, mergeErrIntoOut = true)
      readVerdict(result.out.text(), kmax)
    finally os.remove(file)

  /** Check whether `pred` is reachable, as a fresh `bad`. */
  def checkPred(design: Btor2Design, pred: Btor2Pred, kmax: Int, kind: Boolean = false): Btor2Result =
    check(design.asBad(pred), kmax, kind)

  private val satAt   = raw"(?m)^sat\b".r
  private val frame   = raw"(?m)^@(\d+)".r
  private val unreach = raw"unreachable at bound k = (\d+)".r

  private def readVerdict(output: String, kmax: Int): Btor2Result =
    unreach.findFirstMatchIn(output) match
      case Some(m) => Btor2Result.Proven(m.group(1).toInt)
      case None    =>
        if satAt.findFirstIn(output).isDefined then
          // btormc prints the counterexample as frames @0..@N; the last is the reach depth.
          val depth = frame.findAllMatchIn(output).map(_.group(1).toInt).maxOption.getOrElse(kmax)
          Btor2Result.Reachable(depth)
        else Btor2Result.UnreachableWithin(kmax)
