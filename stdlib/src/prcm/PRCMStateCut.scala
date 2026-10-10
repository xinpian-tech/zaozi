// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>

package me.jiuyang.stdlib.prcm

import java.lang.foreign.Arena
import org.llvm.mlir.scalalib.capi.dialect.smt.{given_DialectApi, DialectApi as SMT}
import org.llvm.circt.scalalib.capi.dialect.firrtl.{given_DialectApi, DialectApi as FIRRTL}
import org.llvm.circt.scalalib.capi.dialect.hw.{given_DialectApi, DialectApi as HW}
import org.llvm.circt.scalalib.capi.dialect.seq.{given_DialectApi, DialectApi as SEQ}
import org.llvm.circt.scalalib.capi.dialect.sv.{given_DialectApi, DialectApi as SV}
import org.llvm.circt.scalalib.capi.dialect.ltl.{given_DialectApi, DialectApi as LTL}
import org.llvm.circt.scalalib.capi.dialect.verif.{given_DialectApi, DialectApi as VERIF}
import org.llvm.circt.scalalib.capi.dialect.emit.{given_DialectApi, DialectApi as EMIT}
import org.llvm.circt.scalalib.capi.dialect.llhd.{given_DialectApi, DialectApi as LLHD}
import org.llvm.circt.scalalib.capi.dialect.llhd.given_AttributeApi
import org.llvm.circt.scalalib.capi.dialect.hw.{HWModulePort, given}
import org.llvm.circt.scalalib.capi.dialect.seq.given
import org.llvm.mlir.scalalib.capi.ir.{*, given}
import org.llvm.mlir.scalalib.capi.support.given

private[prcm] object PRCMStateCut:
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
    val moduleType = top.getAttributeByName("module_type").typeAttrGetValue
    val inputCount = moduleType.moduleTypeGetNumInputs()
    val outputCount = moduleType.moduleTypeGetNumOutputs()
    val oldPorts = (0 until inputCount + outputCount).map(moduleType.moduleTypeGetPort)
    val ops = children(top)
    val states = ops.filter(o => Set("seq.firreg", "llhd.sig")(o.getName.str))
    def same(a:      Value, b: Value):                                                  Boolean           = org.llvm.mlir.CAPI.mlirValueEqual(a.segment, b.segment)
    val latches = states.filter(_.getName.str == "llhd.sig")
    val times = ops.filter(_.getName.str == "llhd.constant_time")
    val probes = ops.filter(_.getName.str == "llhd.prb")
    val latchProbes =
      latches.map(signal => signal -> probes.filter(p => same(p.getOperand(0), signal.getResult(0)))).toMap
    val drives = latches.map: signal =>
      val uses    = ops.filter(op =>
        (0L until org.llvm.mlir.CAPI.mlirOperationGetNumOperands(op.segment)).exists(i =>
          same(op.getOperand(i), signal.getResult(0))
        )
      )
      require(uses.forall(op => Set("llhd.prb", "llhd.drv")(op.getName.str)), "unsupported clock latch use")
      val drivers = uses.filter(_.getName.str == "llhd.drv")
      require(
        drivers.size == 1 && org.llvm.mlir.CAPI.mlirOperationGetNumOperands(drivers.head.segment) == 4,
        "expected one conditional clock latch driver"
      )
      val time    = drivers.head.getOperand(2).opResultGetOwner
      require(time.getName.str == "llhd.constant_time")
      val delay   = time.getAttributeByName("value")
      require(
        delay.TimeAttrGetSeconds() == 0 && delay.TimeAttrGetDelta() == 0 && delay.TimeAttrGetEpsilon() == 1,
        "clock latch must have no physical delay"
      )
      signal -> drivers.head
    val latchDrivers = drives.toMap
    val casts = ops.filter(o => Set("seq.to_clock", "seq.from_clock")(o.getName.str))
    def bits(tpe:    Type):                                                             Type              = if tpe.isClock then 1.integerTypeGet else tpe
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
    val inputs = states.zipWithIndex.map: (op, index) =>
      val tpe   = if op.getName.str == "llhd.sig" then op.getOperand(0).getType else op.getResult(0).getType
      val value =
        Value(org.llvm.mlir.CAPI.mlirBlockAddArgument(arena, body.segment, tpe.segment, op.getLocation.segment))
      if op.getName.str == "seq.firreg" then op.getResult(0).replaceAllUsesOfWith(value)
      else
        latchProbes(op).foreach: probe =>
          probe.getResult(0).replaceAllUsesOfWith(value)
          probe.removeFromParent()
          probe.destroy()
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
        val driver = latchDrivers(op)
        Vector(s"state_${index}_d" -> driver.getOperand(1), s"state_${index}_enable" -> driver.getOperand(3))
    val metadata = states.zipWithIndex.map: (op, index) =>
      val common = Seq(
        "symbol" -> ujson.Str(s"state_$index"),
        "kind"   -> ujson.Str(op.getName.str),
        "width"  -> ujson.Num(inputs(index)._2.getType.getBitWidth())
      )
      val fields =
        if op.getName.str == "seq.firreg" then
          val reset = org.llvm.mlir.CAPI.mlirOperationGetNumOperands(op.segment) == 4
          Seq("name" -> ujson.Str(op.getAttributeByName("name").stringAttrGetValue), "reset" -> ujson.Bool(reset)) ++
            (Seq("clockEdge") ++ Option.when(reset)(Seq("resetPolarity", "resetType")).toSeq.flatten).map(name =>
              name -> ujson.Num(op.getAttributeByName(name).integerAttrGetValueInt.toDouble)
            )
        else Seq("name" -> ujson.Str(op.getAttributeByName("name").stringAttrGetValue))
      ujson.Obj.from(common ++ fields)
    val terminator = body.getTerminator
    val oldOutputs = (0 until outputCount).map(terminator.getOperand(_))
    terminator.setOperands(oldOutputs ++ outputs.map(_._2))
    def port(name: String, tpe: Type, direction: Int):                                  HWModulePort      =
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
    (drives.map(_._2) ++ states ++ times).foreach: op =>
      op.removeFromParent()
      op.destroy()
    val logic = children(top).filterNot(_.getName.str == "hw.output")
    def identity(op: Operation):                                                        Long              = org.llvm.mlir.MlirOperation.ptr(op.segment).address()
    val members = logic.map(identity).toSet
    val dependencies = logic.map: op =>
      val values = (0L until org.llvm.mlir.CAPI.mlirOperationGetNumOperands(op.segment)).map(op.getOperand)
      identity(op) -> values.filter(_.isOpResult).map(value => identity(value.opResultGetOwner)).filter(members).toSet
    val needs = dependencies.toMap
    @scala.annotation.tailrec
    def order(pending: Vector[Operation], ready: Set[Long], sorted: Vector[Operation]): Vector[Operation] =
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

  def lower(firrtl: String, topName: String, cells: String): (String, ujson.Value) =
    val arena = Arena.ofConfined()
    try
      given Arena   = arena
      given Context = summon[ContextApi].contextCreate
      try
        summon[FIRRTL].loadDialect
        summon[HW].loadDialect
        summon[SEQ].loadDialect
        summon[SV].loadDialect
        summon[LTL].loadDialect
        summon[VERIF].loadDialect
        summon[EMIT].loadDialect
        summon[LLHD].loadDialect
        summon[SMT].loadDialect()
        summon[Context].allowUnregisteredDialects(true)
        def transform(input: String)(change: Module => Unit): String =
          val module = summon[ModuleApi].moduleCreateParse(input)
          require(org.llvm.mlir.MlirModule.ptr(module.segment).address() != 0, "PRCM IR parsing failed")
          try
            change(module)
            require(module.getOperation.verify, "invalid PRCM relation")
            val output = new StringBuilder
            module.getOperation.print(output ++= _)
            output.toString
          finally module.destroy()
        def run(input: String, command: String*):             String =
          os.proc(command).call(stdin = input, stderr = os.Pipe, timeout = 30000).out.text()
        val functional           = transform(firrtl): module =>
          descendants(module.getOperation)
            .filter(_.getName.str == "firrtl.layerblock")
            .reverse
            .foreach: op =>
              require(op.getAttributeByName("layerName").symbolRefAttrGetRootReference == "Verification")
              require(op.getNumResults == 0)
              op.removeFromParent()
              op.destroy()
        val hardware             = run(functional, "firtool", "--format=mlir", "--ir-hw", "--strip-debug-info")
        val generic              = run(hardware, "circt-opt", "--strip-om", "--mlir-print-op-generic")
        val hidden               = transform(generic): module =>
          PRCMClockCells.link(module, cells)
          val modules = descendants(module.getOperation).filter(_.getName.str == "hw.module")
          require(modules.count(_.getAttributeByName("sym_name").stringAttrGetValue == topName) == 1)
          modules.foreach: op =>
            if op.getAttributeByName("sym_name").stringAttrGetValue != topName then
              op.setAttributeByName("sym_visibility", "private".stringAttrGet)
        val flat                 = run(
          hidden,
          "circt-opt",
          "--hw-flatten-modules=hw-inline-all=true hw-inline-with-state=true",
          "--symbol-dce",
          "--prepare-for-formal",
          "--canonicalize",
          "--mlir-print-op-generic"
        )
        val module               = summon[ModuleApi].moduleCreateParse(flat)
        require(org.llvm.mlir.MlirModule.ptr(module.segment).address() != 0, "PRCM state IR parsing failed")
        val (relation, metadata) =
          try
            val modules  = descendants(module.getOperation).filter(_.getName.str == "hw.module")
            require(modules.size == 1)
            val metadata = cut(modules.head)
            require(module.getOperation.verify, "invalid PRCM state cut")
            val output   = new StringBuilder
            module.getOperation.print(output ++= _)
            output.toString -> metadata
          finally module.destroy()
        val smt                  = run(
          relation,
          "circt-opt",
          "--strip-emit",
          "--symbol-dce",
          "--hw-aggregate-to-comb",
          "--canonicalize",
          "--convert-hw-to-smt=for-smtlib-export",
          "--convert-comb-to-smt",
          "--reconcile-unrealized-casts"
        )
        val cleaned              = transform(smt): module =>
          children(module.getOperation)
            .filter(_.getName.str == "sv.macro.decl")
            .foreach: op =>
              op.removeFromParent()
              op.destroy()
        cleaned -> metadata
      finally summon[Context].destroy()
    finally arena.close()
