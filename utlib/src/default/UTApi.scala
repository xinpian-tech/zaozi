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
  SimApi,
  TypeImpl
}
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.{Bits, Bool, BundleField, Clock, Data, Reset, SInt, UInt}

import org.llvm.circt.scalalib.capi.dialect.firrtl.{DialectApi as FIRRTLDialectApi, given}
import org.llvm.circt.scalalib.capi.dialect.emit.{DialectApi as EmitDialectApi, given}
import org.llvm.circt.scalalib.capi.dialect.hw.{DialectApi as HWDialectApi, given}
import org.llvm.circt.scalalib.capi.dialect.ltl.{DialectApi as LTLDialectApi, given}
import org.llvm.circt.scalalib.capi.dialect.seq.{DialectApi as SeqDialectApi, given}
import org.llvm.circt.scalalib.capi.dialect.sim.{DialectApi as SimDialectApi, given}
import org.llvm.circt.scalalib.capi.dialect.sv.{DialectApi as SVDialectApi, given}
import org.llvm.circt.scalalib.capi.dialect.verif.{DialectApi as VerifDialectApi, given}
import org.llvm.circt.scalalib.capi.firtool.{FirtoolApi, FirtoolOptions, given}
import org.llvm.circt.scalalib.dialect.hw.operation.{Port, PortDirection}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, ContextApi, ModuleApi, Type, TypeApi, Value, given}
import org.llvm.mlir.scalalib.capi.pass.{PassManagerApi, given}
import org.llvm.mlir.scalalib.capi.support.given_LogicalResultApi

import java.lang.foreign.Arena

given UTApi with
  private final case class ModulePort(name: String, tpe: Type)

  private def port(
    name: String,
    data: Data
  )(
    using Arena,
    Context
  ): ModulePort =
    val (tpe, width) = data match
      case _: Clock => (summon[SeqApi].clockType, 1)
      case _: Reset | _: Bool => (1.integerTypeGet, 1)
      case _: SInt =>
        val width = data.width(
          using summon[Arena],
          summon[Context],
          summon[TypeImpl]
        )
        (width.integerTypeSignedGet, width)
      case _: UInt | _: Bits =>
        val width = data.width(
          using summon[Arena],
          summon[Context],
          summon[TypeImpl]
        )
        (width.integerTypeGet, width)
      case _ => throw new IllegalArgumentException(s"unsupported testbench port type: $name")
    require(width > 0 && width <= 64, s"unsupported testbench port width for $name: $width")
    ModulePort(name, tpe)

  extension [PARAM <: Parameter, L <: LayerInterface[PARAM], I <: HWInterface[PARAM], P <: DVInterface[PARAM, L]](
    ut: Generator[PARAM, L, I, P] & UT[PARAM, I]
  )
    def write(parameter: PARAM): Unit =
      val period = ut.clockPeriodNs(parameter)
      require(period > 0 && period % 2 == 0, "testbench clock period must be a positive even number of nanoseconds")

      val elaborationArena = Arena.ofConfined()
      try
        given Arena   = elaborationArena
        given Context = summon[ContextApi].contextCreate
        try
          summon[FIRRTLDialectApi].loadDialect
          summon[LTLDialectApi].loadDialect
          summon[VerifDialectApi].loadDialect
          ut.dumpMlirbc(parameter)
        finally summon[Context].destroy()
      finally elaborationArena.close()

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
      try
        given Arena   = wrapperArena
        given Context = summon[ContextApi].contextCreate
        try
          summon[FIRRTLDialectApi].loadDialect
          summon[EmitDialectApi].loadDialect
          summon[HWDialectApi].loadDialect
          summon[LTLDialectApi].loadDialect
          summon[SeqDialectApi].loadDialect
          summon[SimDialectApi].loadDialect
          summon[SVDialectApi].loadDialect
          summon[VerifDialectApi].loadDialect

          val interface = ut.interface(parameter)
          interface.toMlirType
          val fields    = interface.elements
          require(fields.forall(_.isFlipped), "testbench ports must all be inputs")
          val ports     = fields.map(field => port(field.name, field.dataType))
          require(
            ports.headOption.exists(port => port.name == "clock" && port.tpe.isClock),
            "first testbench port must be clock"
          )
          val hwPorts   = ports.map(port => Port(port.name, PortDirection.Input, port.tpe))

          val module = summon[ModuleApi].moduleCreateParse(os.read.bytes(linked))
          try
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
              val stimuli        = ports.tail
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
              summon[HWApi].instance("testbench", testbenchName, hwPorts, inputs)
              summon[HWApi].output(Seq.empty)

            // The HW instance points at the FIRRTL top until this pipeline materializes its hw.module.
            // Disable intermediate verification, then verify the combined HW/SV module once lowering finishes.
            given FirtoolOptions = summon[FirtoolApi].firtoolOptionsCreateDefault
            val passManager      = summon[PassManagerApi].passManagerCreate
            try
              passManager.enableVerifier(false)
              passManager.preprocessTransforms(summon[FirtoolOptions])
              passManager.chirrtlToLowFIRRTL(summon[FirtoolOptions])
              passManager.lowFIRRTLToHW(summon[FirtoolOptions], linked.toString)
              passManager.runOnOpOrThrow(
                module.getOperation,
                s"FIRRTL to HW lowering for unit testbench '$testbenchName'"
              )
            finally passManager.destroy()

            require(module.getOperation.verify, "invalid lowered unit testbench")
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
              exportPassManager.hwToSV(summon[FirtoolOptions])
              exportPassManager.exportVerilog(summon[FirtoolOptions], verilog.append(_))
              exportPassManager.runOnOpOrThrow(
                module.getOperation,
                s"SystemVerilog export for unit testbench '$testbenchName'"
              )
            finally exportPassManager.destroy()
            os.write.over(testbenchSV, verilog.toString)
          finally module.destroy()
        finally summon[Context].destroy()
      finally wrapperArena.close()
