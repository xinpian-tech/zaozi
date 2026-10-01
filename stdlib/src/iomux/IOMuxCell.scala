// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.iomux

case class IOMuxCellRow(name: String, value: BigInt)

given upickle.default.ReadWriter[IOMuxCellRow] = upickle.default.macroRW

case class IOMuxCellTable(width: Int, rows: Seq[IOMuxCellRow], default: Int = 0):
  require(width > 0, "cell table width must be positive")
  require(rows.nonEmpty && rows.size <= 16, "cell table needs 1 to 16 rows")
  require(rows.map(_.name).distinct.size == rows.size, "cell row names must be unique")
  require(rows.forall(r => r.value >= 0 && r.value.bitLength <= width), "cell row value exceeds its width")
  require(default >= 0 && default < rows.size, "cell default must name a table row")

  val selectWidth: Int = BigInt(rows.size - 1).bitLength

  def code(name: String): Int =
    val index = if name.isEmpty then default else rows.indexWhere(_.name == name)
    require(index >= 0, s"unknown cell row $name")
    index

given upickle.default.ReadWriter[IOMuxCellTable] = upickle.default.macroRW

case class IOMuxCellPull(width: Int, modes: Map[String, Seq[IOMuxCellRow]], isDriver: Boolean = false):
  require(modes.contains("none"), "cell pull table needs a none row")
  val tables: Map[String, IOMuxCellTable] = modes.map((name, rows) => name -> IOMuxCellTable(width, rows))
  require(
    modes.forall((name, rows) => name == "up" || name == "down" || rows.size == 1),
    "only up and down pull modes select strength rows"
  )

given upickle.default.ReadWriter[IOMuxCellPull] = upickle.default.macroRW

case class IOMuxCellControl(name: String, table: IOMuxCellTable)

given upickle.default.ReadWriter[IOMuxCellControl] = upickle.default.macroRW

case class IOMuxPullRequest(mode: String = "none", strength: String = "")

given upickle.default.ReadWriter[IOMuxPullRequest] = upickle.default.macroRW

case class IOMuxCellSelect[T](off: T, on: Option[T] = None, invert: Boolean = false)

given [T: upickle.default.ReadWriter]: upickle.default.ReadWriter[IOMuxCellSelect[T]] = upickle.default.macroRW

case class IOMuxCellRoute(
  pull:    IOMuxCellSelect[IOMuxPullRequest] = IOMuxCellSelect(IOMuxPullRequest()),
  control: Map[String, IOMuxCellSelect[String]] = Map.empty)

given upickle.default.ReadWriter[IOMuxCellRoute] = upickle.default.macroRW

case class IOMuxCellSafe(
  inputEnable:  Boolean = false,
  outputValue:  Boolean = false,
  outputEnable: Boolean = false,
  pull:         IOMuxPullRequest = IOMuxPullRequest(),
  control:      Map[String, String] = Map.empty)

given upickle.default.ReadWriter[IOMuxCellSafe] = upickle.default.macroRW

case class IOMuxCell(
  name:        String,
  pull:        Option[IOMuxCellPull] = None,
  control:     Seq[IOMuxCellControl] = Seq.empty,
  hasReceiver: Boolean = true,
  safe: Option[IOMuxCellSafe] = None):
  require(control.map(_.name).distinct.size == control.size, s"cell $name control names must be unique")

given upickle.default.ReadWriter[IOMuxCell] = upickle.default.macroRW

given mainargs.TokensReader.Simple[IOMuxCell]:
  def shortName = "cell"
  def read(strs: Seq[String]): Right[Nothing, IOMuxCell] = Right(upickle.default.read[IOMuxCell](strs.head))

case class IOMuxCellModel(
  cells:     Seq[IOMuxCell],
  pinCell:   Seq[String],
  modeOrder: Seq[String],
  controlOrder: Seq[String]):
  require(cells.map(_.name).distinct.size == cells.size, "cell names must be unique")
  pinCell.foreach(name => require(cells.exists(_.name == name), s"pinCell names unknown cell $name"))
  require(modeOrder.distinct.size == modeOrder.size, "cell mode order must not repeat names")
  require(controlOrder.distinct.size == controlOrder.size, "cell control order must not repeat names")

  val pinCellIndex: Seq[Int] = pinCell.map(name => cells.indexWhere(_.name == name))

  private val fixedModes = Seq("none", "up", "down", "keeper", "oscillator")
  require(!modeOrder.exists(fixedModes.contains), "fixed pull modes must not appear in modeOrder")

  val modeNames:    Seq[String] = fixedModes ++ modeOrder ++ cells
    .flatMap(_.pull.toSeq.flatMap(_.modes.keys))
    .distinct
    .filterNot(name => fixedModes.contains(name) || modeOrder.contains(name))
    .sorted
  val controlNames: Seq[String] =
    controlOrder ++ cells.flatMap(_.control.map(_.name)).distinct.filterNot(controlOrder.contains)
  val hasPull:      Boolean     = cells.exists(_.pull.nonEmpty)
  val hasSafe:      Boolean     = cells.exists(_.safe.nonEmpty)
  require(!hasSafe || cells.forall(_.safe.nonEmpty), "every cell must declare safe when force is present")
  require(modeNames.size <= 16, "cell modes must fit the four-bit lane")

  val modeWidth:     Int      = if hasPull then BigInt(modeNames.size - 1).bitLength else 0
  val upWidth:       Int      = cells.flatMap(_.pull.toSeq.flatMap(_.tables.get("up"))).map(_.selectWidth).maxOption.getOrElse(0)
  val downWidth:     Int      =
    cells.flatMap(_.pull.toSeq.flatMap(_.tables.get("down"))).map(_.selectWidth).maxOption.getOrElse(0)
  val controlWidths: Seq[Int] = controlNames.map(name =>
    cells.flatMap(_.control.filter(_.name == name)).map(_.table.selectWidth).maxOption.getOrElse(0)
  )

  def controlTable(cellIndex: Int, name: String): Option[IOMuxCellTable] =
    cells(cellIndex).control.find(_.name == name).map(_.table)

  def weaves(cellIndex: Int): Boolean = cells(cellIndex).pull.exists: pull =>
    !pull.modes.contains("keeper") && !pull.modes.contains("oscillator") && pull.modes.contains("up") &&
      pull.modes.contains("down") && !pull.isDriver && cells(cellIndex).hasReceiver

  def pullCodes(cellIndex: Int, request: IOMuxPullRequest): (Int, Int, Int) =
    val mode   = modeNames.indexOf(request.mode)
    val pull   = cells(cellIndex).pull
    val woven  = weaves(cellIndex) && (request.mode == "keeper" || request.mode == "oscillator")
    require(mode >= 0, s"unknown pull mode ${request.mode}")
    require(
      request.mode == "none" || woven || pull.exists(_.modes.contains(request.mode)),
      s"cell has no pull mode ${request.mode}"
    )
    val up     = pull.flatMap(_.tables.get("up"))
    val down   = pull.flatMap(_.tables.get("down"))
    val graded = (request.mode == "up" && up.exists(_.selectWidth > 0)) ||
      (request.mode == "down" && down.exists(_.selectWidth > 0)) ||
      (woven && (up.exists(_.selectWidth > 0) || down.exists(_.selectWidth > 0)))
    require(graded || request.strength.isEmpty, s"pull mode ${request.mode} has no strength selector")
    require(
      !(graded && (request.mode == "up" || request.mode == "down")) || request.strength.nonEmpty,
      s"pull mode ${request.mode} needs a strength row"
    )
    def strength(direction: String): Int =
      if request.strength.isEmpty || !(request.mode == direction || woven) then 0
      else
        val table = pull.get.tables(direction)
        if table.selectWidth == 0 then 0 else table.code(request.strength)
    (mode, strength("up"), strength("down"))

  def controlCode(cellIndex: Int, name: String, row: String = ""): Int =
    require(controlNames.contains(name), s"unknown cell control $name")
    controlTable(cellIndex, name) match
      case Some(table) => table.code(row)
      case None        =>
        require(row.isEmpty, s"cell has no control $name")
        0

  def validateRoute(pin: Int, route: IOMuxCellRoute): Unit =
    val cellIndex = pinCellIndex(pin)
    require(route.pull.on.isEmpty || cells(cellIndex).pull.nonEmpty, "cell has no linked pull control")
    (route.pull.off +: route.pull.on.toSeq).foreach(request => pullCodes(cellIndex, request))
    route.control.foreach: (name, select) =>
      require(controlTable(cellIndex, name).nonEmpty, s"cell has no control $name")
      require(
        select.on.isEmpty || controlTable(cellIndex, name).get.selectWidth > 0,
        s"linked cell control $name needs multiple rows"
      )
      (select.off +: select.on.toSeq).foreach(row => controlCode(cellIndex, name, row))

  cells.zipWithIndex.foreach: (cell, index) =>
    cell.safe.foreach: safe =>
      pullCodes(index, safe.pull)
      safe.control.foreach: (name, row) =>
        require(controlTable(index, name).nonEmpty, s"cell has no control $name")
        controlCode(index, name, row)
