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

  /** Materialize `pred` into fresh btor2 nodes; return the extended design and the boolean
    * node id carrying the predicate's truth.
    */
  private def emit(pred: Btor2Pred): (Btor2Design, Long) =
    val bool1  = sortForWidth(1)
    val target = pred.state
    val sort   = stateSort(target)
    val width  = sortBits(sort)
    var next   = maxId
    val out    = scala.collection.mutable.ListBuffer.empty[String]
    def fresh(node: String => String): Long =
      next += 1; out += node(next.toString); next

    val boolId = pred match
      case Btor2Pred.Ult(_, v) =>
        val c = fresh(id => s"$id constd $sort $v")
        fresh(id => s"$id ult $bool1 $target $c")
      case Btor2Pred.Uge(_, v) =>
        val c  = fresh(id => s"$id constd $sort $v")
        val lt = fresh(id => s"$id ult $bool1 $target $c")
        fresh(id => s"$id not $bool1 $lt")
      case Btor2Pred.Eq(_, v)  =>
        val c = fresh(id => s"$id constd $sort $v")
        fresh(id => s"$id eq $bool1 $target $c")

    val _ = width
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

/** A predicate over one state node and a constant, for building btor2 comparison nodes. */
enum Btor2Pred:
  case Ult(node: Long, value: BigInt)
  case Uge(node: Long, value: BigInt)
  case Eq(node: Long, value: BigInt)

  def state: Long = this match
    case Ult(s, _) => s
    case Uge(s, _) => s
    case Eq(s, _)  => s

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
