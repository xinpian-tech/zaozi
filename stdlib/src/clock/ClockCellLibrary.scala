// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.clock

import me.jiuyang.stdlib.cell.{CellRow, CellValue, given}

private def identifier(name: String): Boolean = name.matches("[A-Za-z_][A-Za-z0-9_]*")

case class ClockSequentialCell(
  `type`: ClockCellKind,
  clock:  String,
  enable: String,
  test:   Option[String] = None,
  output: String):
  val pins = Seq(clock, enable, output) ++ test
  require(
    `type` == ClockCellKind.GatePositive || `type` == ClockCellKind.GateNegative,
    s"sequential cell type ${`type`} is not GatePositive or GateNegative"
  )
  require(pins.forall(identifier), s"sequential cell pins ${pins.mkString(", ")} are not identifiers")
  require(pins.distinct.size == pins.size, s"sequential cell pins ${pins.mkString(", ")} are not distinct")

given upickle.default.ReadWriter[ClockSequentialCell] = upickle.default.macroRW

/** A library cell: a combinational truth table or a clock gate. */
case class ClockCellDeclaration(
  name:     String,
  inputs:   Seq[String] = Seq.empty,
  output:   String = "",
  function: Seq[CellRow] = Seq.empty,
  tie:      Map[String, Int] = Map.empty,
  sequential: Option[ClockSequentialCell] = None):
  require(identifier(name), s"cell name '$name' is not an identifier")
  require(!name.startsWith("Zaozi"), s"cell $name uses the reserved Zaozi prefix")
  require(
    sequential.isEmpty || (inputs.isEmpty && output.isEmpty && function.isEmpty && tie.isEmpty),
    s"sequential cell $name must not declare a function"
  )

  /** Inputs left free after ties, in declared order. */
  val free: Seq[String] = inputs.filterNot(tie.contains)

  /** Output for one assignment of the free inputs, with ties applied. */
  def evaluate(values: Map[String, Boolean]): Boolean =
    val levels  = values ++ tie.map((pin, value) => pin -> (value == 1))
    val outputs = function
      .filter(_.levels.forall((pin, level) => pin == output || level.forall(_ == levels(pin))))
      .map(_.entries.find(_._1 == output).get._2 match
        case CellValue.Text(text)   => if text.startsWith("!") then !levels(text.tail) else levels(text)
        case CellValue.Level(value) => value.get)
      .distinct
    def render  = inputs.map(pin => s"$pin=${if levels(pin) then 1 else 0}").mkString(" ")
    require(outputs.nonEmpty, s"cell $name output is undefined for $render")
    require(outputs.size == 1, s"cell $name rows disagree for $render")
    outputs.head

  if sequential.isEmpty then
    val pins = inputs :+ output
    require(inputs.nonEmpty, s"cell $name has no inputs")
    require(pins.forall(identifier), s"cell $name pins ${pins.mkString(", ")} are not identifiers")
    require(pins.distinct.size == pins.size, s"cell $name pins ${pins.mkString(", ")} are not distinct")
    tie.foreach:      (pin, value) =>
      require(pin != output, s"cell $name ties its output $pin")
      require(inputs.contains(pin), s"cell $name ties unknown pin $pin")
      require(value == 0 || value == 1, s"cell $name ties $pin to $value, not 0 or 1")
    function.foreach: row =>
      require(row.entries.exists(_._1 == output), s"cell $name function row has no output $output")
      row.entries.foreach:
        case (pin, _) if !pins.contains(pin)                                                        =>
          throw new IllegalArgumentException(s"cell $name function uses unknown pin $pin")
        case (pin, CellValue.Level(Some(_)))                                                        => ()
        case (pin, CellValue.Text(text)) if pin == output && inputs.contains(text.stripPrefix("!")) => ()
        case (pin, value)                                                                           =>
          throw new IllegalArgumentException(s"cell $name pin $pin cannot be $value")
    val table = ClockCellDeclaration.assignments(free)
    free.foreach:     pin =>
      require(
        table.exists(values => evaluate(values) != evaluate(values.updated(pin, !values(pin)))),
        s"cell $name input $pin changes no output and is not tied"
      )

given upickle.default.ReadWriter[ClockCellDeclaration] = upickle.default.macroRW

given mainargs.TokensReader.Simple[ClockCellDeclaration]:
  def shortName = "cell"
  def read(strs: Seq[String]): Right[Nothing, ClockCellDeclaration] =
    Right(upickle.default.read[ClockCellDeclaration](strs.head))

object ClockCellDeclaration:
  def assignments(pins: Seq[String]): Seq[Map[String, Boolean]] =
    pins.foldLeft(Seq(Map.empty[String, Boolean])): (partial, pin) =>
      partial.flatMap(values => Seq(values.updated(pin, false), values.updated(pin, true)))

/** How declared cells implement a clock role. */
enum ClockRole derives upickle.default.ReadWriter:
  /** One cell; `pins` maps role ports to cell pins. */
  case Cell(cell: String, pins: Map[String, String])

  /** The opposite gate polarity between two inverters. */
  case Inverted(gate: ClockCellKind)

  /** A network of declared cells chosen at `config`. */
  case Composed(composition: ClockComposition)

/** Declared cells and the clock roles they implement; without cells the roles stay behavioral. */
case class ClockCellLibrary(
  cells: Seq[ClockCellDeclaration] = Seq.empty,
  roles: Map[ClockCellKind, ClockRole] = Map.empty):
  def implements(kind: ClockCellKind): Boolean = cells.isEmpty || roles.contains(kind)

given upickle.default.ReadWriter[ClockCellLibrary] = upickle.default.macroRW

given mainargs.TokensReader.Simple[ClockCellLibrary]:
  def shortName = "library"
  def read(strs: Seq[String]): Right[Nothing, ClockCellLibrary] =
    Right(upickle.default.read[ClockCellLibrary](strs.head))

object ClockCellLibrary:
  /** The role a cell implements and its role-port to cell-pin map. */
  def bind(cell: ClockCellDeclaration): Option[(ClockCellKind, Map[String, String])] = cell.sequential match
    case Some(gate) => Some(gate.`type` -> Map("a" -> gate.clock, "enable" -> gate.enable, "outClock" -> gate.output))
    case None       =>
      ClockCellKind.values.iterator
        .flatMap(kind => ClockComposer.functions.get(kind).map((ports, function) => (kind, ports, function)))
        .filter(_._2.size == cell.free.size)
        .flatMap: (kind, ports, function) =>
          cell.free.permutations
            .map(ports.zip(_).toMap)
            .find: pins =>
              ClockCellDeclaration
                .assignments(ports)
                .forall(values => cell.evaluate(pins.map((port, pin) => pin -> values(port))) == function(values))
            .map(pins => kind -> (pins + ("outClock" -> cell.output)))
        .nextOption()

  private def opposite(gate: ClockCellKind): ClockCellKind =
    if gate == ClockCellKind.GatePositive then ClockCellKind.GateNegative else ClockCellKind.GatePositive

  /** Binds cells to roles; `composed` networks from `config` are checked against the current cells. */
  def resolve(cells: Seq[ClockCellDeclaration], composed: Seq[ClockComposition] = Seq.empty): ClockCellLibrary =
    require(cells.map(_.name).distinct.size == cells.size, "cell names must be unique")
    val bound = cells.flatMap(cell => bind(cell).map((kind, pins) => (kind, cell.name, pins)))
    bound
      .groupBy(_._1)
      .foreach: (kind, found) =>
        require(found.size == 1, s"cells ${found.map(_._2).mkString(" and ")} both implement clock role $kind")
    val direct   = bound.map((kind, cell, pins) => kind -> ClockRole.Cell(cell, pins)).toMap
    val built    = composed.map: composition =>
      val problem =
        if direct.contains(composition.role) then Left("is now bound to a declared cell")
        else ClockComposer.verify(composition, cells)
      problem.left.foreach: reason =>
        throw new IllegalArgumentException(
          s"composed clock role ${composition.role}: $reason; the cells changed since config, rerun config"
        )
      composition.role -> ClockRole.Composed(composition)
    val roles    = direct ++ built
    val inverted = Seq(ClockCellKind.GatePositive, ClockCellKind.GateNegative).collect:
      case gate if roles.contains(gate) && !roles.contains(opposite(gate)) && roles.contains(ClockCellKind.Inverter) =>
        opposite(gate) -> ClockRole.Inverted(gate)
    ClockCellLibrary(cells, roles ++ inverted)

  /** Composes every used combinational role that no cell binds, including an inverter for a missing gate polarity. */
  def compose(cells: Seq[ClockCellDeclaration], used: Set[ClockCellKind]): Seq[ClockComposition] =
    if cells.isEmpty then Seq.empty
    else
      val bound    = resolve(cells).roles
      // A gate with only its opposite polarity bound is built later from that gate and inverters.
      val inverted = used.filter(gate =>
        Set(ClockCellKind.GatePositive, ClockCellKind.GateNegative)(gate) && !bound.contains(gate) &&
          bound.contains(opposite(gate))
      )
      val needed   = used -- inverted ++ Option.when(inverted.nonEmpty)(ClockCellKind.Inverter)
      ClockCellKind.values.toSeq
        .filter(kind => needed(kind) && !bound.contains(kind))
        .map: role =>
          require(
            ClockComposer.functions.contains(role),
            s"clock role $role is used but no declared cell implements it"
          )
          ClockComposer.compose(role, cells) match
            case ClockComposer.Result.Found(composition) => composition
            case ClockComposer.Result.Impossible(reason) =>
              throw new IllegalArgumentException(s"clock role $role cannot be built from the declared cells: $reason")
