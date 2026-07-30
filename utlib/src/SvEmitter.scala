// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib

import me.jiuyang.zaozi.Generator
import me.jiuyang.zaozi.default.given_GeneratorApi
import me.jiuyang.zaozi.magic.validateCircuit

import org.llvm.circt.scalalib.capi.dialect.emit.{given_DialectApi as given_EmitDialect, DialectApi as EmitDialectApi}
import org.llvm.circt.scalalib.capi.dialect.firrtl.{
  given_DialectApi as given_FirrtlDialect,
  DialectApi as FirrtlDialectApi
}
import org.llvm.circt.scalalib.capi.dialect.ltl.{given_DialectApi as given_LTLDialect, DialectApi as LTLDialectApi}
import org.llvm.circt.scalalib.capi.dialect.sim.{given_DialectApi as given_SimDialect, DialectApi as SimDialectApi}
import org.llvm.circt.scalalib.capi.dialect.sv.{given_DialectApi as given_SvDialect, DialectApi as SvDialectApi}
import org.llvm.circt.scalalib.capi.dialect.verif.{
  given_DialectApi as given_VerifDialect,
  DialectApi as VerifDialectApi
}
import org.llvm.circt.scalalib.capi.firtool.{given_FirtoolOptionsApi, FirtoolApi, FirtoolOptions, given}
import org.llvm.circt.scalalib.capi.exportfirrtl.given_ExportFirrtlApi
import org.llvm.circt.scalalib.dialect.firrtl.operation.{given_CircuitApi, given_ModuleApi, Circuit, CircuitApi}
import org.llvm.mlir.scalalib.capi.ir.{
  given_AttributeApi,
  given_BlockApi,
  given_ContextApi,
  given_IdentifierApi,
  given_LocationApi,
  given_ModuleApi,
  given_NamedAttributeApi,
  given_OperationApi,
  given_RegionApi,
  given_TypeApi,
  given_ValueApi,
  Context,
  ContextApi,
  LocationApi,
  Module as MlirModule,
  ModuleApi as MlirModuleApi
}
import org.llvm.mlir.scalalib.capi.support.given_LogicalResultApi
import org.llvm.mlir.scalalib.capi.pass.{given_OpPassManagerApi, given_PassManagerApi, PassManager, PassManagerApi}

import java.lang.foreign.Arena

/** Lowers a Zaozi generator to SystemVerilog for simulation.
  *
  * This mirrors `me.jiuyang.testlib.HasVerilogTest` — same self-type trait shape, same firtool staging — with two
  * differences that matter to the framework:
  *
  *   1. The `sim` dialect is loaded, so sim-dialect ops survive to the SV lowering.
  *   2. [[hwString]] stops after `lowFIRRTLToHW`, exposing the HW-level IR that sim instrumentation operates on.
  *
  * The harness's `Cover(…)` ops reach SystemVerilog as `cover property` statements under firtool's default verification
  * flavor — see the note at the options-construction site below. That is the only coverage collection path this
  * framework uses.
  */
trait HasSvEmit:
  this: Generator[?, ?, ?, ?] =>
  private val self = this.asInstanceOf[Generator[this.TPARAM, this.TLAYER, this.TINTF, this.TPROBE]]

  /** Append the modules of any sub-generators this design instantiates.
    *
    * Zaozi's own flow elaborates each generator to a separate `.mlirbc` and links them afterwards with `firld` (that is
    * what `Generator.instantiate` does). This emitter builds one circuit in-process instead, so every instantiated
    * sub-module must be appended to that same circuit here — otherwise the instance references a module Verilator
    * cannot find.
    */
  def appendSubmodules(
    parameter: this.TPARAM
  )(
    using Arena,
    Context,
    Circuit
  ): Unit = ()

  /** Inject sim-dialect ops into the HW-level design, between `lowFIRRTLToHW` and `hwToSV`.
    *
    * Returns whether anything was injected, purely so callers can assert on it. The default does nothing.
    */
  def instrument(
    parameter: this.TPARAM,
    module:    MlirModule
  )(
    using Arena,
    Context
  ): Boolean = false

  /** The design after `lowFIRRTLToHW`, as MLIR text. `instrument` has already run. */
  def hwString(parameter: this.TPARAM): String = run(parameter, stopAfterHw = true)

  /** The design as SystemVerilog, with SVA cover properties. */
  def verilogString(parameter: this.TPARAM): String = run(parameter, stopAfterHw = false)

  private def run(parameter: this.TPARAM, stopAfterHw: Boolean): String =
    val arena = Arena.ofConfined()
    try
      given Arena   = arena
      given Context = summon[ContextApi].contextCreate
      summon[FirrtlDialectApi].loadDialect
      summon[LTLDialectApi].loadDialect
      summon[VerifDialectApi].loadDialect
      summon[SvDialectApi].loadDialect
      summon[EmitDialectApi].loadDialect
      summon[SimDialectApi].loadDialect
      summon[VerifDialectApi].registerPasses

      // No `setVerificationFlavor(Sva)` call: `circtFirtoolOptionsSetVerificationFlavor`
      // does not exist in CIRCT 1.147.0 (circtlib's Scala binding for it is
      // stale and throws at first use), and it is not needed — the default
      // flavor already lowers `verif.cover` to SystemVerilog `cover property`.
      // zaozi's own SVASpec pins that behaviour, asserting
      // `cover_0: cover property (ib0);` without setting any flavor.
      given FirtoolOptions = summon[FirtoolApi].firtoolOptionsCreateDefault

      val out     = new StringBuilder
      val options = summon[FirtoolOptions]

      // Stage 1: FIRRTL down to HW.
      val toHw = summon[PassManagerApi].passManagerCreate
      toHw.preprocessTransforms(options)
      toHw.chirrtlToLowFIRRTL(options)
      toHw.lowFIRRTLToHW(options, "")

      given MlirModule = summon[MlirModuleApi].moduleCreateEmpty(summon[LocationApi].locationUnknownGet)
      given Circuit    = summon[CircuitApi].op(self.moduleName(parameter))
      summon[Circuit].appendToModule()
      appendSubmodules(parameter)
      self.module(parameter).appendToCircuit()
      validateCircuit()
      if !toHw.runOnOp(summon[MlirModule].getOperation).succeeded then
        throw new RuntimeException("FIRRTL-to-HW lowering failed")

      // The design is HW-level here — the only point where sim ops are legal
      // but the design is not yet SystemVerilog.
      instrument(parameter, summon[MlirModule])

      if stopAfterHw then summon[MlirModule].getOperation.print(out ++= _)
      else
        // Stage 2: HW down to SV, then export.
        val toSv = summon[PassManagerApi].passManagerCreate
        toSv.getAsOpPassManager.addPipeline(
          "lower-contracts,verif-lower-symbolic-values{mode=yosys},verif-lower-tests",
          err => throw new RuntimeException(s"verif pipeline parse error: $err")
        )
        toSv.hwToSV(options)
        toSv.exportVerilog(options, out ++= _)
        if !toSv.runOnOp(summon[MlirModule].getOperation).succeeded then
          throw new RuntimeException("HW-to-SV lowering failed")

      summon[Context].destroy()
      out.toString
    finally arena.close()
