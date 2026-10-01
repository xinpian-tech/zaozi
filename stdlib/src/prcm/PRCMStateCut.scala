// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>

package me.jiuyang.stdlib.prcm

import java.lang.foreign.Arena
import me.jiuyang.stdlib.clock.{ClockCell, ClockCellKind, ClockCellParameter}
import org.llvm.mlir.scalalib.capi.dialect.smt.{given_DialectApi, DialectApi as SMT}
import org.llvm.circt.scalalib.capi.dialect.firrtl.{given_DialectApi, DialectApi as FIRRTL}
import org.llvm.circt.scalalib.capi.dialect.hw.{given_DialectApi, DialectApi as HW}
import org.llvm.circt.scalalib.capi.dialect.seq.{given_DialectApi, DialectApi as SEQ}
import org.llvm.circt.scalalib.capi.dialect.sv.{given_DialectApi, DialectApi as SV}
import org.llvm.circt.scalalib.capi.dialect.ltl.{given_DialectApi, DialectApi as LTL}
import org.llvm.circt.scalalib.capi.dialect.verif.{given_DialectApi, DialectApi as VERIF}
import org.llvm.circt.scalalib.capi.dialect.emit.{given_DialectApi, DialectApi as EMIT}
import org.llvm.circt.scalalib.capi.dialect.hw.{HWModulePort, given}
import org.llvm.circt.scalalib.capi.dialect.seq.given
import org.llvm.mlir.scalalib.capi.ir.{*, given}
import org.llvm.mlir.scalalib.capi.support.given
import org.llvm.circt.scalalib.capi.conversion.{given_ConversionRegisterApi, ConversionRegisterApi}
import org.llvm.circt.scalalib.capi.firtool.{FirtoolApi, given}
import org.llvm.mlir.scalalib.capi.pass.{PassManagerApi, given}
import me.jiuyang.zaozi.default.runOnOpOrThrow

private[prcm] object PRCMStateCut:
  private val roles =
    ClockCellKind.values.map(kind => ClockCell.verilogModuleName(ClockCellParameter(kind)) -> kind).toMap

  def children(
    op: Operation
  )(
    using Arena
  ): Vector[Operation] =
    (0L until org.llvm.mlir.CAPI.mlirOperationGetNumRegions(op.segment)).toVector.flatMap: index =>
      Iterator
        .iterate(op.getRegion(index).getFirstBlock)(_.getNextInRegion)
        .takeWhile(b => org.llvm.mlir.MlirBlock.ptr(b.segment).address() != 0)
        .flatMap: block =>
          Iterator
            .iterate(block.getFirstOperation)(_.getNextInBlock)
            .takeWhile(o => org.llvm.mlir.MlirOperation.ptr(o.segment).address() != 0)
  def descendants(
    op: Operation
  )(
    using Arena
  ): Vector[Operation] =
    children(op).flatMap(child => child +: descendants(child))

  def cut(
    top:         Operation
  )(
    using arena: Arena,
    context:     Context
  ): ujson.Value =
    val body = top.getRegion(0).getFirstBlock
    val terminator = body.getTerminator
    val moduleType = top.getAttributeByName("module_type").typeAttrGetValue
    val inputCount = moduleType.moduleTypeGetNumInputs()
    val outputCount = moduleType.moduleTypeGetNumOutputs()
    val oldPorts = (0 until inputCount + outputCount).map(moduleType.moduleTypeGetPort)
    val ops = children(top)
    def identity(op: Operation):                                                                 Long               = org.llvm.mlir.MlirOperation.ptr(op.segment).address()
    // Clock roles stay external through flattening; their semantics are built here.
    val cells = ops
      .filter(_.getName.str == "hw.instance")
      .map: op =>
        val name = op.getAttributeByName("moduleName").flatSymbolRefAttrGetValue
        op -> roles.getOrElse(name, throw new IllegalArgumentException(s"unknown PRCM clock cell: $name"))
    val gates = cells
      .collect:
        case (op, kind) if kind == ClockCellKind.GatePositive || kind == ClockCellKind.GateNegative =>
          identity(op) -> (kind == ClockCellKind.GatePositive)
      .toMap
    val states = ops.filter(op => op.getName.str == "seq.firreg" || gates.contains(identity(op)))
    val casts = ops.filter(o => Set("seq.to_clock", "seq.from_clock")(o.getName.str))
    def bits(tpe:    Type):                                                                      Type               = if tpe.isClock then 1.integerTypeGet else tpe
    (0 until inputCount).foreach: index =>
      val arg = body.getArgument(index)
      arg.setType(bits(arg.getType))
    ops.foreach: op =>
      (0L until op.getNumResults).foreach: index =>
        val value = op.getResult(index)
        value.setType(bits(value.getType))
    casts.foreach: op =>
      op.getResult(0).replaceAllUsesOfWith(op.getOperand(0))
      op.removeFromParent()
      op.destroy()
    val bit = 1.integerTypeGet
    def create(name: String, operands: Seq[Value], attributes: Seq[NamedAttribute] = Seq.empty): Value              =
      val op = summon[OperationApi].operationCreate(
        name,
        top.getLocation,
        namedAttributes = attributes,
        operands = operands,
        resultsTypes = Some(Seq(bit))
      )
      body.insertOwnedOperationBefore(terminator, op)
      op.getResult(0)
    lazy val one =
      create(
        "hw.constant",
        Seq.empty,
        Seq(summon[NamedAttributeApi].namedAttributeGet("value".identifierGet, 1L.integerAttrGet(bit)))
      )
    def not(value:   Value):                                                                     Value              = create("comb.xor", Seq(value, one))
    def pins(op: Operation):                                                                     Map[String, Value] =
      val names = op.getAttributeByName("argNames")
      (0 until names.arrayAttrGetNumElements)
        .map(i => names.arrayAttrGetElement(i).stringAttrGetValue -> op.getOperand(i))
        .toMap
    cells.foreach: (op, kind) =>
      val pin    = pins(op)
      val output = kind match
        case ClockCellKind.Buffer   => Some(pin("a"))
        case ClockCellKind.Inverter => Some(not(pin("a")))
        case ClockCellKind.Or       => Some(create("comb.or", Seq(pin("a"), pin("b"))))
        case ClockCellKind.Xor      => Some(create("comb.xor", Seq(pin("a"), pin("b"))))
        case ClockCellKind.Mux      => Some(create("comb.mux", Seq(pin("select"), pin("b"), pin("a"))))
        case _                      => None
      output.foreach(op.getResult(0).replaceAllUsesOfWith(_))
    val inputs = states.zipWithIndex.map: (op, index) =>
      val tpe   = if op.getName.str == "seq.firreg" then op.getResult(0).getType else bit
      val value =
        Value(org.llvm.mlir.CAPI.mlirBlockAddArgument(arena, body.segment, tpe.segment, op.getLocation.segment))
      if op.getName.str == "seq.firreg" then op.getResult(0).replaceAllUsesOfWith(value)
      else
        // A gate latch holds enable while the clock is low (positive) or high (negative).
        val clock = pins(op)("a")
        op.getResult(0)
          .replaceAllUsesOfWith(
            if gates(identity(op)) then create("comb.and", Seq(clock, value))
            else create("comb.or", Seq(clock, not(value)))
          )
      (s"state_$index", value)
    val outputs = states.zipWithIndex.flatMap: (op, index) =>
      if op.getName.str == "seq.firreg" then
        val fields = org.llvm.mlir.CAPI.mlirOperationGetNumOperands(op.segment) match
          case 2 => Vector("d", "clock")
          case 4 => Vector("d", "clock", "reset", "reset_value")
          case _ => throw new IllegalArgumentException("unsupported PRCM register operands")
        fields.zipWithIndex.map: (name, operand) =>
          (s"state_${index}_$name", op.getOperand(operand))
      else
        val pin      = pins(op)
        Vector(
          s"state_${index}_d"      -> pin("enable"),
          s"state_${index}_enable" -> (if gates(identity(op)) then not(pin("a")) else pin("a"))
        )
    val metadata = states.zipWithIndex.map: (op, index) =>
      val register = op.getName.str == "seq.firreg"
      val common   = Seq(
        "symbol" -> ujson.Str(s"state_$index"),
        "kind"   -> ujson.Str(if register then "seq.firreg" else "latch"),
        "width"  -> ujson.Num(inputs(index)._2.getType.getBitWidth())
      )
      val fields   =
        if register then
          val reset = org.llvm.mlir.CAPI.mlirOperationGetNumOperands(op.segment) == 4
          Seq("name" -> ujson.Str(op.getAttributeByName("name").stringAttrGetValue), "reset" -> ujson.Bool(reset)) ++
            (Seq("clockEdge") ++ Option.when(reset)(Seq("resetPolarity", "resetType")).toSeq.flatten).map(name =>
              name -> ujson.Num(op.getAttributeByName(name).integerAttrGetValueInt.toDouble)
            )
        else Seq("name" -> ujson.Str(op.getAttributeByName("instanceName").stringAttrGetValue))
      ujson.Obj.from(common ++ fields)
    val oldOutputs = (0 until outputCount).map(terminator.getOperand(_))
    terminator.setOperands(oldOutputs ++ outputs.map(_._2))
    def port(name: String, tpe: Type, direction: Int):                                           HWModulePort       =
      val segment = org.llvm.circt.HWModulePort.allocate(arena)
      org.llvm.circt.HWModulePort.name(segment, name.stringAttrGet.segment)
      org.llvm.circt.HWModulePort.`type`(segment, tpe.segment)
      org.llvm.circt.HWModulePort.dir(segment, direction)
      HWModulePort(segment)
    val ports =
      oldPorts.map(p => port(Attribute(p.portName).stringAttrGetValue, bits(Type(p.portType)), p.portDirection)) ++
        inputs.map((name, value) => port(name, value.getType, 0)) ++
        outputs.map((name, value) => port(name, value.getType, 1))
    val updated = summon[org.llvm.circt.scalalib.capi.dialect.hw.TypeApi].moduleTypeGet(ports.size, ports)
    top.setAttributeByName("module_type", updated.typeAttrGet)
    top.setAttributeByName(
      "result_locs",
      Seq.fill(outputCount + outputs.size)(top.getLocation.getAttribute).arrayAttrGet
    )
    (states.filter(_.getName.str == "seq.firreg") ++ cells.map(_._1)).foreach: op =>
      op.removeFromParent()
      op.destroy()
    val logic = children(top).filterNot(_.getName.str == "hw.output")
    val members = logic.map(identity).toSet
    val dependencies = logic.map: op =>
      val values = (0L until org.llvm.mlir.CAPI.mlirOperationGetNumOperands(op.segment)).map(op.getOperand)
      identity(op) -> values.filter(_.isOpResult).map(value => identity(value.opResultGetOwner)).filter(members).toSet
    val needs = dependencies.toMap
    @scala.annotation.tailrec
    def order(pending: Vector[Operation], ready: Set[Long], sorted: Vector[Operation]):          Vector[Operation]  =
      if pending.isEmpty then sorted
      else
        val (next, rest) = pending.partition(op => needs(identity(op)).subsetOf(ready))
        require(next.nonEmpty, "cycle remains after cutting state")
        order(rest, ready ++ next.map(identity), sorted ++ next)
    order(logic, Set.empty, Vector.empty).foreach(_.moveBefore(terminator))
    val names = (0 until inputCount).map(moduleType.moduleTypeGetInputName) ++ inputs.map(_._1) ++
      (0 until outputCount).map(moduleType.moduleTypeGetOutputName) ++ outputs.map(_._1)
    ujson.Obj(
      "states"      -> ujson.Arr.from(metadata),
      "symbols"     -> ujson.Arr.from(names),
      "inputCount"  -> (inputCount + inputs.size),
      "outputCount" -> (outputCount + outputs.size)
    )

  def pipeline(
    module:   Module,
    elements: String
  )(
    using Arena,
    Context
  ): Unit =
    val passes = summon[PassManagerApi].passManagerCreate
    try
      val errors = new StringBuilder
      require(passes.getAsOpPassManager.addPipeline(elements, errors ++= _).succeeded, s"invalid pipeline: $errors")
      passes.runOnOpOrThrow(module.getOperation, s"PRCM lowering ($elements)")
    finally passes.destroy()

  def erase(
    module: Module
  )(keep:   Operation => Boolean
  )(
    using Arena
  ): Unit =
    children(module.getOperation)
      .filterNot(keep)
      .foreach: op =>
        op.removeFromParent()
        op.destroy()

  /** Loads the dialects and registers the passes that [[lower]] uses. */
  def prepare(
    using Arena,
    Context
  ): Unit =
    summon[FIRRTL].loadDialect
    summon[HW].loadDialect
    summon[SEQ].loadDialect
    summon[SV].loadDialect
    summon[LTL].loadDialect
    summon[VERIF].loadDialect
    summon[EMIT].loadDialect
    summon[SMT].loadDialect()
    summon[Context].allowUnregisteredDialects(true)
    registered

  // Pass registration is process-wide and aborts when repeated.
  private lazy val registered: Unit =
    summon[HW].registerPasses
    summon[VERIF].registerPasses
    summon[ConversionRegisterApi].convertHWToSMT
    summon[ConversionRegisterApi].convertCombToSMT
    org.llvm.mlir.CAPI.mlirRegisterAllPasses()

  /** Lowers an elaborated FIRRTL circuit in place to an SMT relation over cut state; returns the state metadata. */
  def lower(
    module:  Module,
    topName: String
  )(
    using Arena,
    Context
  ): ujson.Value =
    descendants(module.getOperation)
      .filter(_.getName.str == "firrtl.layerblock")
      .reverse
      .foreach: op =>
        require(op.getAttributeByName("layerName").symbolRefAttrGetRootReference == "Verification")
        require(op.getNumResults == 0)
        op.removeFromParent()
        op.destroy()
    val options = summon[FirtoolApi].firtoolOptionsCreateDefault
    val passes  = summon[PassManagerApi].passManagerCreate
    try
      options.setStripDebugInfo(true)
      // Clock roles are proved by their role semantics, so every instance choice takes its model.
      options.setSelectDefaultInstanceChoice(true)
      require(passes.preprocessTransforms(options).succeeded)
      require(passes.chirrtlToLowFIRRTL(options).succeeded)
      require(passes.lowFIRRTLToHW(options, "").succeeded)
      passes.runOnOpOrThrow(module.getOperation, "PRCM FIRRTL lowering")
    finally
      passes.destroy()
      options.destroy
    erase(module)(op => !op.getName.str.startsWith("om.") && !op.getName.str.startsWith("emit."))
    val modules = children(module.getOperation).filter(op => Set("hw.module", "hw.module.extern")(op.getName.str))
    require(modules.count(_.getAttributeByName("sym_name").stringAttrGetValue == topName) == 1)
    modules.foreach: op =>
      if op.getAttributeByName("sym_name").stringAttrGetValue != topName then
        op.setAttributeByName("sym_visibility", "private".stringAttrGet)
    pipeline(
      module,
      "hw-flatten-modules{hw-inline-all=true hw-inline-with-state=true},symbol-dce,hw.module(prepare-for-formal),canonicalize"
    )
    val tops     = children(module.getOperation).filter(_.getName.str == "hw.module")
    require(tops.size == 1)
    val metadata = cut(tops.head)
    require(module.getOperation.verify, "invalid PRCM state cut")
    pipeline(
      module,
      "symbol-dce,hw.module(hw-aggregate-to-comb),canonicalize,convert-hw-to-smt{for-smtlib-export=true},convert-comb-to-smt," +
        "reconcile-unrealized-casts"
    )
    erase(module)(_.getName.str != "sv.macro.decl")
    require(module.getOperation.verify, "invalid PRCM relation")
    metadata
