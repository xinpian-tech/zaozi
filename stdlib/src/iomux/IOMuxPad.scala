// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.iomux

case class IOMuxPadRow(name: String, value: BigInt)

given upickle.default.ReadWriter[IOMuxPadRow] = upickle.default.macroRW

case class IOMuxPadTable(width: Int, rows: Seq[IOMuxPadRow], default: Int = 0):
  require(width > 0, "pad table width must be positive")
  require(rows.nonEmpty && rows.size <= 16, "pad table needs 1 to 16 rows")
  require(rows.map(_.name).distinct.size == rows.size, "pad row names must be unique")
  require(rows.forall(r => r.value >= 0 && r.value.bitLength <= width), "pad row value exceeds its width")
  require(default >= 0 && default < rows.size, "pad default must name a table row")

  val selectWidth: Int = BigInt(rows.size - 1).bitLength

  def code(name: String): Int =
    val index = if name.isEmpty then default else rows.indexWhere(_.name == name)
    require(index >= 0, s"unknown pad row $name")
    index

given upickle.default.ReadWriter[IOMuxPadTable] = upickle.default.macroRW

case class IOMuxPadPull(width: Int, modes: Map[String, Seq[IOMuxPadRow]], isDriver: Boolean = false):
  require(modes.contains("none"), "pad pull table needs a none row")
  val tables: Map[String, IOMuxPadTable] = modes.map((name, rows) => name -> IOMuxPadTable(width, rows))
  require(
    modes.forall((name, rows) => name == "up" || name == "down" || rows.size == 1),
    "only up and down pull modes select strength rows"
  )

given upickle.default.ReadWriter[IOMuxPadPull] = upickle.default.macroRW

case class IOMuxPadControl(name: String, table: IOMuxPadTable)

given upickle.default.ReadWriter[IOMuxPadControl] = upickle.default.macroRW

case class IOMuxPullRequest(mode: String = "none", strength: String = "")

given upickle.default.ReadWriter[IOMuxPullRequest] = upickle.default.macroRW

case class IOMuxPadSelect[T](off: T, on: Option[T] = None, invert: Boolean = false)

given [T: upickle.default.ReadWriter]: upickle.default.ReadWriter[IOMuxPadSelect[T]] = upickle.default.macroRW

case class IOMuxPadRoute(
  pull:    IOMuxPadSelect[IOMuxPullRequest] = IOMuxPadSelect(IOMuxPullRequest()),
  control: Map[String, IOMuxPadSelect[String]] = Map.empty)

given upickle.default.ReadWriter[IOMuxPadRoute] = upickle.default.macroRW

case class IOMuxPadSafe(
  inputEnable:  Boolean = false,
  outputValue:  Boolean = false,
  outputEnable: Boolean = false,
  pull:         IOMuxPullRequest = IOMuxPullRequest(),
  control:      Map[String, String] = Map.empty)

given upickle.default.ReadWriter[IOMuxPadSafe] = upickle.default.macroRW

case class IOMuxPadClass(
  pull:        Option[IOMuxPadPull] = None,
  control:     Seq[IOMuxPadControl] = Seq.empty,
  hasReceiver: Boolean = true,
  safe: Option[IOMuxPadSafe] = None):
  require(control.size <= 16, "a pad class supports at most 16 controls")
  require(control.map(_.name).distinct.size == control.size, "pad control names must be unique")

given upickle.default.ReadWriter[IOMuxPadClass] = upickle.default.macroRW

case class IOMuxPadParameter(
  classes:   Seq[IOMuxPadClass],
  pinClass:  Seq[Int],
  modeOrder: Seq[String] = Seq.empty,
  controlOrder: Seq[String] = Seq.empty):
  require(classes.nonEmpty, "pad configuration needs a class")
  require(pinClass.forall(i => i >= 0 && i < classes.size), "pad class index is out of range")
  require(modeOrder.distinct.size == modeOrder.size, "pad mode order must not repeat names")
  require(controlOrder.distinct.size == controlOrder.size, "pad control order must not repeat names")

  private val fixedModes = Seq("none", "up", "down", "keeper", "oscillator")
  require(!modeOrder.exists(fixedModes.contains), "fixed pull modes must not appear in modeOrder")

  val modeNames:    Seq[String] = fixedModes ++ modeOrder ++ classes
    .flatMap(_.pull.toSeq.flatMap(_.modes.keys))
    .distinct
    .filterNot(name => fixedModes.contains(name) || modeOrder.contains(name))
    .sorted
  val controlNames: Seq[String] =
    controlOrder ++ classes.flatMap(_.control.map(_.name)).distinct.filterNot(controlOrder.contains)
  val hasPull:      Boolean     = classes.exists(_.pull.nonEmpty)
  val hasSafe:      Boolean     = classes.exists(_.safe.nonEmpty)
  require(!hasSafe || classes.forall(_.safe.nonEmpty), "every pad class must declare safe when force is present")
  require(modeNames.size <= 16, "pad modes must fit the four-bit lane")

  val modeWidth:     Int      = if hasPull then BigInt(modeNames.size - 1).bitLength else 0
  val upWidth:       Int      = classes.flatMap(_.pull.toSeq.flatMap(_.tables.get("up"))).map(_.selectWidth).maxOption.getOrElse(0)
  val downWidth:     Int      =
    classes.flatMap(_.pull.toSeq.flatMap(_.tables.get("down"))).map(_.selectWidth).maxOption.getOrElse(0)
  val controlWidths: Seq[Int] = controlNames.map(name =>
    classes.flatMap(_.control.filter(_.name == name)).map(_.table.selectWidth).maxOption.getOrElse(0)
  )

  def controlTable(classIndex: Int, name: String): Option[IOMuxPadTable] =
    classes(classIndex).control.find(_.name == name).map(_.table)

  def weaves(classIndex: Int): Boolean = classes(classIndex).pull.exists: pull =>
    !pull.modes.contains("keeper") && !pull.modes.contains("oscillator") && pull.modes.contains("up") &&
      pull.modes.contains("down") && !pull.isDriver && classes(classIndex).hasReceiver

  def pullCodes(classIndex: Int, request: IOMuxPullRequest): (Int, Int, Int) =
    val mode   = modeNames.indexOf(request.mode)
    val pull   = classes(classIndex).pull
    val woven  = weaves(classIndex) && (request.mode == "keeper" || request.mode == "oscillator")
    require(mode >= 0, s"unknown pull mode ${request.mode}")
    require(
      request.mode == "none" || woven || pull.exists(_.modes.contains(request.mode)),
      s"pad class has no pull mode ${request.mode}"
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

  def controlCode(classIndex: Int, name: String, row: String = ""): Int =
    require(controlNames.contains(name), s"unknown pad control $name")
    controlTable(classIndex, name) match
      case Some(table) => table.code(row)
      case None        =>
        require(row.isEmpty, s"pad class has no control $name")
        0

  def validateRoute(pin: Int, route: IOMuxPadRoute): Unit =
    val classIndex = pinClass(pin)
    require(route.pull.on.isEmpty || classes(classIndex).pull.nonEmpty, "pad class has no linked pull control")
    (route.pull.off +: route.pull.on.toSeq).foreach(request => pullCodes(classIndex, request))
    route.control.foreach: (name, select) =>
      require(controlTable(classIndex, name).nonEmpty, s"pad class has no control $name")
      require(
        select.on.isEmpty || controlTable(classIndex, name).get.selectWidth > 0,
        s"linked pad control $name needs multiple rows"
      )
      (select.off +: select.on.toSeq).foreach(row => controlCode(classIndex, name, row))

  classes.zipWithIndex.foreach: (pad, index) =>
    pad.safe.foreach: safe =>
      pullCodes(index, safe.pull)
      safe.control.foreach: (name, row) =>
        require(controlTable(index, name).nonEmpty, s"pad class has no control $name")
        controlCode(index, name, row)

given upickle.default.ReadWriter[IOMuxPadParameter] = upickle.default.macroRW

given mainargs.TokensReader.Simple[IOMuxPadParameter]:
  def shortName = "pad"
  def read(strs: Seq[String]): Right[Nothing, IOMuxPadParameter] = Right(
    upickle.default.read[IOMuxPadParameter](strs.head)
  )
