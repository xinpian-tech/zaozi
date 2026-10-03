// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.clock

/** One cell of a composed network; `pins` name a role port, an earlier gate `g<i>`, or a constant `0`/`1`. */
case class ClockComposedGate(cell: String, pins: Map[String, String])

given upickle.default.ReadWriter[ClockComposedGate] = upickle.default.macroRW

/** A combinational role built from declared cells; the last gate drives the role output. */
case class ClockComposition(role: ClockCellKind, depth: Int, gates: Seq[ClockComposedGate])

given upickle.default.ReadWriter[ClockComposition] = upickle.default.macroRW

given mainargs.TokensReader.Simple[ClockComposition]:
  def shortName = "composition"
  def read(strs: Seq[String]): Right[Nothing, ClockComposition] =
    Right(upickle.default.read[ClockComposition](strs.head))

object ClockComposer:
  private[clock] val functions: Map[ClockCellKind, (Seq[String], Map[String, Boolean] => Boolean)] = Map(
    ClockCellKind.Buffer   -> (Seq("a"), v => v("a")),
    ClockCellKind.Inverter -> (Seq("a"), v => !v("a")),
    ClockCellKind.Or       -> (Seq("a", "b"), v => v("a") || v("b")),
    ClockCellKind.Mux      -> (Seq("a", "b", "select"), v => if v("select") then v("b") else v("a")),
    ClockCellKind.Xor      -> (Seq("a", "b"), v => v("a") != v("b"))
  )

  /** Input changes a role must follow without a glitch: one input at a time, or mux data while select holds. */
  private def changes(role: ClockCellKind): Seq[Set[String]] = role match
    case ClockCellKind.Mux => Seq(Set("a"), Set("b"), Set("a", "b"))
    case _                 => functions(role)._1.map(Set(_))

  private def index(values: Seq[Boolean]): Int = values.zipWithIndex.map((v, i) => if v then 1 << i else 0).sum

  private case class Kind(cell: ClockCellDeclaration):
    val pins:                        Seq[String]     = cell.free
    val table:                       Vector[Boolean] = (0 until 1 << pins.size).toVector.map(row =>
      cell.evaluate(pins.zipWithIndex.map((pin, i) => pin -> (((row >> i) & 1) == 1)).toMap)
    )
    def apply(inputs: Seq[Boolean]): Boolean         = table(index(inputs))
    // Pin pairs whose swap keeps the function; their sources stay in ascending order.
    val symmetric:                   Seq[(Int, Int)] =
      for
        p <- pins.indices
        q <- pins.indices
        if p < q && table.indices.forall: row =>
          val swapped = (row & ~((1 << p) | (1 << q))) | (((row >> q) & 1) << p) | (((row >> p) & 1) << q)
          table(row) == table(swapped)
      yield (p, q)

  /** Sources index 0, 1, the role inputs, then earlier gates. */
  private case class Gate(kind: Int, sources: Vector[Int])

  enum Result:
    case Found(composition: ClockComposition)
    case Impossible(reason: String)

  private class Search(role: ClockCellKind, cells: Seq[ClockCellDeclaration]):
    val (inputs, function) = functions(role)
    val kinds = cells
      .filter(cell => cell.sequential.isEmpty && (1 to 3).contains(cell.free.size))
      .map(Kind(_))
      .toVector
    val rows = 1 << inputs.size
    val all = (1 << rows) - 1
    def tabulate(row:   Int => Boolean):         Int                  = (0 until rows).filter(row).map(1 << _).sum
    def bit(table:      Int, row:     Int):      Boolean              = ((table >> row) & 1) == 1
    val leaves = Vector(0, all) ++ inputs.indices.map(i => tabulate(row => bit(row, i)))
    def assignment(row: Int):                    Map[String, Boolean] = inputs.zipWithIndex.map((name, i) => name -> bit(row, i)).toMap
    val target = tabulate(row => function(assignment(row)))
    def evaluate(kind:  Kind, tables: Seq[Int]): Int                  = tabulate(row => kind(tables.map(bit(_, row))))
    def name(source: Int):                       String               =
      if source < 2 then source.toString
      else if source < leaves.size then inputs(source - 2)
      else s"g${source - leaves.size}"

    /** The least depth at which the target is reachable, ignoring hazards. */
    /** The least gate depth that reaches the target, ignoring hazards. */
    lazy val minimum: Option[Int] =
      def products(known: Seq[Int], size: Int): Seq[Seq[Int]] =
        (0 until size).foldLeft(Seq(Seq.empty[Int]))((acc, _) => acc.flatMap(prefix => known.map(prefix :+ _)))
      def outputs(known: Set[Int]):             Set[Int]      =
        kinds.flatMap(kind => products(known.toSeq, kind.pins.size).map(evaluate(kind, _))).toSet
      LazyList
        .iterate((leaves.toSet, outputs(leaves.toSet)))((known, produced) =>
          val next = known ++ produced
          (next, outputs(next))
        )
        .zip(LazyList.from(1))
        .sliding(2)
        .collectFirst:
          case Seq(((_, produced), depth), _) if produced.contains(target) => Some(depth)
          case Seq(((known, produced), _), _) if produced.subsetOf(known)  => None
        .flatten

    enum Tree:
      case Leaf(source: Int, id: Int)
      case Node(kind: Kind, children: Seq[Tree])

    def unfold(gates: Vector[Gate], source: Int, next: Int): (Tree, Int) =
      if source < leaves.size then (Tree.Leaf(source, next), next + 1)
      else
        val (children, after) = gates(source - leaves.size).sources.foldLeft((Seq.empty[Tree], next)): (acc, child) =>
          val (tree, n) = unfold(gates, child, acc._2)
          (acc._1 :+ tree, n)
        (Tree.Node(kinds(gates(source - leaves.size).kind), children), after)

    /** Every leaf occurrence switches at its own time; each order must give exactly the role's output change. */
    def hazardFree(gates: Vector[Gate]): Boolean =
      val tree = unfold(gates, leaves.size + gates.size - 1, 0)._1
      def occurrences(t: Tree):                                        Seq[Tree.Leaf] = t match
        case leaf: Tree.Leaf => Seq(leaf)
        case Tree.Node(_, children) => children.flatMap(occurrences)
      def value(t: Tree, before: Int, after: Int, switched: Set[Int]): Boolean        = t match
        case Tree.Leaf(source, id)     =>
          if source < 2 then source == 1 else bit(if switched(id) then after else before, source - 2)
        case Tree.Node(kind, children) => kind(children.map(value(_, before, after, switched)))
      val leafList = occurrences(tree)
      changes(role).forall: changed =>
        val mask = changed.map(pin => 1 << inputs.indexOf(pin)).sum
        (0 until rows).forall: before =>
          val after     = before ^ mask
          val switching = leafList.filter(l => l.source >= 2 && bit(mask, l.source - 2)).map(_.id).toVector
          val outputs   = (0 until 1 << switching.size).map: subset =>
            value(tree, before, after, switching.indices.filter(bit(subset, _)).map(switching).toSet)
          val required  = if function(assignment(before)) != function(assignment(after)) then 1 else 0
          val bounds    = (1 until 1 << switching.size).foldLeft(Vector((0, 0))): (acc, subset) =>
            val steps = switching.indices
              .filter(bit(subset, _))
              .map: b =>
                val previous = subset & ~(1 << b)
                val step     = if outputs(previous) != outputs(subset) then 1 else 0
                (acc(previous)._1 + step, acc(previous)._2 + step)
            acc :+ (steps.map(_._1).min, steps.map(_._2).max)
          bounds.last == (required, required)

    /** Networks of exactly `count` gates and depth `depth` computing the target, or None past the step budget. */
    def networks(count: Int, depth: Int, budget: Long): Option[(Seq[Vector[Gate]], Long)] =
      def extend(gates: Vector[Gate], tables: Vector[Int], level: Vector[Int], spent: Long)
        : Option[(Seq[Vector[Gate]], Long)] =
        val position  = gates.size
        val available = leaves.size + position
        val unused    = (leaves.size until available).filterNot(s => gates.exists(_.sources.contains(s))).toSet
        val last      = position == count - 1
        def choose(kind: Kind, pin: Int, chosen: Vector[Int]): Seq[Vector[Int]] =
          if pin == kind.pins.size then Seq(chosen)
          else
            (0 until available)
              .filter(s => s < 2 || !chosen.contains(s))
              .filter(s => kind.symmetric.forall((p, q) => q != pin || chosen(p) <= s))
              .flatMap(s => choose(kind, pin + 1, chosen :+ s))
        val candidates = kinds.indices.flatMap: k =>
          choose(kinds(k), 0, Vector.empty).flatMap: sources =>
            val gate      = Gate(k, sources)
            val table     = evaluate(kinds(k), sources.map(tables))
            val lvl       = 1 + sources.map(level).max
            val remaining = unused -- sources
            // Gates that do not use their predecessor follow it in key order.
            val ordered   = gates.lastOption.forall: previous =>
              sources.contains(available - 1) || previous.kind < k ||
                (previous.kind == k && previous.sources.zip(sources).find(_ != _).forall(_ < _))
            val feeds     =
              if last then remaining.isEmpty && lvl == depth && table == target
              else remaining.size + 1 <= (count - position - 1) * 3
            Option.when(
              table != all && table != 0 && (!leaves.contains(table) || last) && !gates.contains(
                gate
              ) && lvl <= depth &&
                ordered && feeds
            )((gate, table, lvl))
        candidates.foldLeft(Option((Seq.empty[Vector[Gate]], spent + 1))): (acc, candidate) =>
          acc.flatMap: (found, used) =>
            val (gate, table, lvl) = candidate
            if used > budget then None
            else if last then Some((found :+ (gates :+ gate), used + 1))
            else extend(gates :+ gate, tables :+ table, level :+ lvl, used).map((more, after) => (found ++ more, after))
      extend(Vector.empty, leaves, Vector.fill(leaves.size)(0), 0)

    def key(gates: Vector[Gate]): (Int, Int, String) =
      (
        gates.map(_.sources.count(_ < 2)).sum,
        gates.map(_.sources.size).sum,
        gates.zipWithIndex
          .map((g, i) => s"${kinds(g.kind).cell.name}(${g.sources.map(name).mkString(",")})")
          .mkString(";")
      )

  /** Checks a recorded network against the current cells: function, depth and hazards. */
  def verify(composition: ClockComposition, cells: Seq[ClockCellDeclaration]): Either[String, Unit] =
    val search = Search(composition.role, cells)
    val gates  = composition.gates.zipWithIndex.foldLeft[Either[String, Vector[Gate]]](Right(Vector.empty)):
      case (Right(done), (gate, position)) =>
        val kind    = search.kinds.indexWhere(_.cell.name == gate.cell)
        val sources = Map("0" -> 0, "1" -> 1) ++ search.inputs.zipWithIndex.map((name, i) => name -> (i + 2)) ++
          (0 until position).map(i => s"g$i" -> (search.leaves.size + i))
        if kind < 0 then Left(s"cell ${gate.cell} is not a declared combinational cell")
        else if gate.pins.keySet != search.kinds(kind).pins.toSet then Left(s"cell ${gate.cell} pins changed")
        else
          search
            .kinds(kind)
            .pins
            .map(pin => sources.get(gate.pins(pin)))
            .foldLeft[Option[Vector[Int]]](Some(Vector.empty))((acc, source) =>
              acc.flatMap(found => source.map(found :+ _))
            ) match
            case Some(found) => Right(done :+ Gate(kind, found))
            case None        => Left(s"gate g$position uses an unknown source")
      case (failed, _)                     => failed
    gates.flatMap: network =>
      val (tables, levels) = network.foldLeft((search.leaves, Vector.fill(search.leaves.size)(0))):
        case ((tables, levels), gate) =>
          (
            tables :+ search.evaluate(search.kinds(gate.kind), gate.sources.map(tables)),
            levels :+ (1 + gate.sources.map(levels).max)
          )
      if network.isEmpty || tables.last != search.target then Left("it does not compute the role function")
      else if levels.last != composition.depth then Left(s"its depth is ${levels.last}, not ${composition.depth}")
      else if !search.hazardFree(network) then Left("it can glitch when a role input changes")
      else Right(())

  /** Searches by depth, then by gate count; `budget` bounds the enumerated gate placements. */
  def compose(role: ClockCellKind, cells: Seq[ClockCellDeclaration], budget: Long = 20_000_000L): Result =
    val search = Search(role, cells)
    search.minimum match
      case None          => Result.Impossible("the declared cells cannot express its function")
      case Some(minimum) =>
        val attempts = for
          depth <- (minimum to minimum + 2).iterator
          count <- (depth to depth + 8).iterator
        yield (depth, count)
        attempts
          .foldLeft[Either[Result, Long]](Right(0L)):
            case (done @ Left(_), _)            => done
            case (Right(spent), (depth, count)) =>
              search.networks(count, depth, budget - spent) match
                case None                    => Left(Result.Impossible(s"the search budget of $budget steps ran out"))
                case Some((networks, steps)) =>
                  networks.filter(search.hazardFree).minByOption(search.key) match
                    case Some(best) =>
                      Left(
                        Result.Found(
                          ClockComposition(
                            role,
                            depth,
                            best.map(gate =>
                              ClockComposedGate(
                                search.kinds(gate.kind).cell.name,
                                search.kinds(gate.kind).pins.zip(gate.sources.map(search.name)).toMap
                              )
                            )
                          )
                        )
                      )
                    case None       => Right(spent + steps)
          .left
          .getOrElse(Result.Impossible("no hazard-free network exists within the search bound"))
