// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib.default

import me.jiuyang.utlib.{Testbench, TestbenchIO, TestbenchPort, UT, UTApi}
import me.jiuyang.zaozi.{
  DVInterface,
  DpiArg,
  DpiCallResult,
  DpiFunction,
  Generator,
  HWApi,
  HWInterface,
  LayerInterface,
  Parameter,
  SVApi,
  SeqApi,
  SimApi
}
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.{BundleField, Data}

import org.llvm.circt.scalalib.capi.dialect.firrtl.{DialectApi as FIRRTLDialectApi, given}
import org.llvm.circt.scalalib.capi.dialect.emit.{DialectApi as EmitDialectApi, given}
import org.llvm.circt.scalalib.capi.dialect.hw.{DialectApi as HWDialectApi, given}
import org.llvm.circt.scalalib.capi.dialect.ltl.{DialectApi as LTLDialectApi, given}
import org.llvm.circt.scalalib.capi.dialect.seq.{DialectApi as SeqDialectApi, given}
import org.llvm.circt.scalalib.capi.dialect.sim.{DialectApi as SimDialectApi, given}
import org.llvm.circt.scalalib.capi.dialect.sv.{DialectApi as SVDialectApi, given}
import org.llvm.circt.scalalib.capi.dialect.verif.{DialectApi as VerifDialectApi, given}
import org.llvm.circt.scalalib.capi.firtool.{FirtoolApi, given}
import org.llvm.circt.scalalib.dialect.hw.operation.{Module as HWModule, PortDirection, given}
import org.llvm.mlir.scalalib.capi.ir.{
  Block,
  Context,
  ContextApi,
  ModuleApi,
  Operation,
  TypeApi,
  Value,
  WalkEnum,
  WalkResultEnum,
  given
}
import org.llvm.mlir.scalalib.capi.pass.{PassManagerApi, given}
import org.llvm.mlir.scalalib.capi.support.given_LogicalResultApi
import org.llvm.mlir.MlirOperation

import java.lang.foreign.Arena

given UTApi with
  private def findHWModule(
    operation: Operation,
    symbol:    String
  )(
    using Arena
  ): HWModule =
    var result = Option.empty[HWModule]
    operation.walk(
      candidate =>
        if candidate.getName.str == "hw.module" &&
          candidate.getInherentAttributeByName("sym_name").stringAttrGetValue == symbol
        then
          // Walk callback handles are temporary; retain the opaque handle in the caller's arena.
          val handle = MlirOperation.allocate(summon[Arena])
          handle.copyFrom(candidate.segment)
          result = Some(new HWModule(Operation(handle)))
          WalkResultEnum.Interrupt
        else WalkResultEnum.Advance,
      WalkEnum.PreOrder
    )
    result.getOrElse(throw new IllegalArgumentException(s"lowered HW module not found: $symbol"))

  extension [PARAM <: Parameter, L <: LayerInterface[PARAM], I <: HWInterface[PARAM], P <: DVInterface[PARAM, L]](
    ut: Generator[PARAM, L, I, P] & UT[PARAM, I]
  )
    def emit(parameter: PARAM): Unit =
      val period = ut.clockPeriodNs(parameter)
      require(period > 0 && period % 2 == 0, "testbench clock period must be a positive even number of nanoseconds")

      locally:
        val elaborationArena = Arena.ofConfined()
        given Arena          = elaborationArena
        given Context        = summon[ContextApi].contextCreate
        try
          summon[FIRRTLDialectApi].loadDialect
          summon[LTLDialectApi].loadDialect
          summon[VerifDialectApi].loadDialect
          ut.dumpMlirbc(parameter)
        finally
          summon[Context].destroy()
          elaborationArena.close()

      val outDir        = os.Path(sys.env.getOrElse("ZAOZI_OUTDIR", ""), os.pwd)
      val modules       = os.list(outDir).filter(_.ext == "mlirbc").sortBy(_.last)
      val testbenchName = ut.moduleName(parameter)
      val wrapperName   = s"${testbenchName}Wrapper"
      require(modules.nonEmpty, s"no FIRRTL modules in $outDir")
      val linked        = outDir / "linked.mlir"
      val testbenchHW   = outDir / "testbench.hw.mlir"
      val testbenchSV   = outDir / "testbench.sv"
      os.proc(
        Seq("firld", s"--base-circuit=$testbenchName", "--no-mangle") ++
          modules.map(_.toString) ++ Seq("-o", linked.toString)
      ).call(cwd = outDir)

      val wrapperArena = Arena.ofConfined()
      given Arena      = wrapperArena
      given Context    = summon[ContextApi].contextCreate
      try
        summon[FIRRTLDialectApi].loadDialect
        summon[EmitDialectApi].loadDialect
        summon[HWDialectApi].loadDialect
        summon[LTLDialectApi].loadDialect
        summon[SeqDialectApi].loadDialect
        summon[SimDialectApi].loadDialect
        summon[SVDialectApi].loadDialect
        summon[VerifDialectApi].loadDialect

        val module = summon[ModuleApi].moduleCreateParse(os.read.bytes(linked))
        try
          val firtoolOptions = summon[FirtoolApi].firtoolOptionsCreateDefault
          try
            val passManager = summon[PassManagerApi].passManagerCreate
            try
              passManager.preprocessTransforms(firtoolOptions)
              passManager.chirrtlToLowFIRRTL(firtoolOptions)
              passManager.lowFIRRTLToHW(firtoolOptions, linked.toString)
              passManager.runOnOpOrThrow(
                module.getOperation,
                s"FIRRTL to HW lowering for unit testbench '$testbenchName'"
              )
            finally passManager.destroy()

            require(module.getOperation.verify, "invalid lowered unit under test")
            val dutModule = findHWModule(module.getOperation, testbenchName)
            val hwPorts   = dutModule.ports
            val interface = ut.interface(parameter)
            interface.toMlirType
            val fields    = interface.elements
            require(fields.forall(_.isFlipped), "testbench ports must all be inputs")
            require(
              hwPorts.size == fields.size && hwPorts
                .zip(fields)
                .forall: (port, field) =>
                  port.name == field.name && port.direction == PortDirection.Input,
              "lowered HW ports do not match the testbench interface"
            )
            require(
              hwPorts.headOption.exists(port => port.name == "clock" && port.tpe.isClock),
              "first testbench port must be clock"
            )

            val root    = module.getBody
            given Block = root

            summon[HWApi].module(wrapperName, Seq.empty):
              val clockReg        = summon[SVApi].reg(1.integerTypeGet, "clock")
              val clockRead       = summon[SVApi].readInOut(clockReg)
              val seqClock        = summon[SeqApi].toClock(clockRead)
              val fallingSeqClock = summon[SeqApi].clockInv(seqClock)

              // CIRCT currently has no structured periodic-delay operation, so only the time source remains verbatim.
              val clockSource = s"initial {{0}} = 1'b0;\nalways #${period / 2}ns {{0}} = ~{{0}};"
              summon[SVApi].verbatim(clockSource, Seq(clockReg))

              val stimulusFields = fields.tail
              val stimuli        = hwPorts.tail
              val driven         = Array.fill[Option[Value]](stimuli.size)(None)
              given Testbench[I] = new Testbench[I]:
                lazy val io:                                            TestbenchIO[I]           = new TestbenchIO[I](this)
                def clock:                                              Value                    = seqClock
                def fallingClock:                                       Value                    = fallingSeqClock
                def dpiFunction(
                  symbol:    String,
                  cName:     Option[String],
                  arguments: Seq[DpiArg]
                )(
                  using Arena,
                  Context
                ): DpiFunction = summon[SimApi].dpiFunction(symbol, cName, arguments)(
                  using summon[Arena],
                  summon[Context],
                  root
                )
                def dpiCall(
                  function: DpiFunction,
                  inputs:   Seq[Value],
                  enabled:  Option[Value]
                )(
                  using Arena,
                  Context,
                  Block
                ): DpiCallResult = summon[SimApi].dpiCall(function, fallingClock, enabled, inputs)
                def finish(
                  condition: Value,
                  success:   Boolean
                )(
                  using Arena,
                  Context,
                  Block
                ): Unit = summon[SimApi].clockedTerminate(clock, condition, success)
                private[utlib] def port[T <: Data](name: String):       TestbenchPort[T]         =
                  val index = stimulusFields.indexWhere(_.name == name)
                  require(index >= 0, s"$name is not a driven input of $testbenchName")
                  new TestbenchPort(stimulusFields(index).asInstanceOf[BundleField[T]], this)
                private[utlib] def portOption[T <: Data](name: String): Option[TestbenchPort[T]] =
                  stimulusFields.indexWhere(_.name == name) match
                    case -1    => None
                    case index => Some(new TestbenchPort(stimulusFields(index).asInstanceOf[BundleField[T]], this))
                private[utlib] def bind(
                  field: BundleField[?],
                  value: Value
                )(
                  using Arena
                ): Unit =
                  val index = stimulusFields.indexWhere(candidate => candidate.eq(field))
                  require(
                    index >= 0,
                    s"${field.name} is not a driven input of $testbenchName"
                  )
                  val port  = stimuli(index)
                  require(driven(index).isEmpty, s"testbench input driven twice: ${port.name}")
                  require(value.getType.equal(port.tpe), s"testbench input type mismatch: ${port.name}")
                  driven(index) = Some(value)

              ut.simulation(parameter)
              val inputs = Seq(seqClock) ++ stimuli
                .zip(driven)
                .map((port, value) =>
                  value.getOrElse(
                    throw new IllegalArgumentException(s"testbench input is not driven: ${port.name}")
                  )
                )
              summon[HWApi].instance("testbench", dutModule, inputs)
              summon[HWApi].output(Seq.empty)

            require(module.getOperation.verify, "invalid unit testbench wrapper")
            val json   = new StringBuilder
            if module.exportDPIInterface(json.append(_)).failed then
              throw new IllegalStateException("CIRCT DPI interface export failed")
            val source = new StringBuilder
            module.getOperation.print(source.append(_))
            os.write.over(testbenchHW, source.toString)
            os.write.over(outDir / "interface.json", json.toString)

            val verilog           = new StringBuilder
            val exportPassManager = summon[PassManagerApi].passManagerCreate
            try
              exportPassManager.hwToSV(firtoolOptions)
              exportPassManager.exportVerilog(firtoolOptions, verilog.append(_))
              exportPassManager.runOnOpOrThrow(
                module.getOperation,
                s"SystemVerilog export for unit testbench '$testbenchName'"
              )
            finally exportPassManager.destroy()
            os.write.over(testbenchSV, verilog.toString)
          finally firtoolOptions.destroy
        finally module.destroy()
      finally
        summon[Context].destroy()
        wrapperArena.close()
