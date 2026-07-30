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

  /** The design after `lowFIRRTLToHW`, as MLIR text. */
  def hwString(parameter: this.TPARAM): String = run(parameter, stopAfterHw = true)

  /** The design as SystemVerilog, with SVA-flavored cover properties. */
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

      given PassManager = summon[PassManagerApi].passManagerCreate
      val out           = new StringBuilder
      val options       = summon[FirtoolOptions]

      summon[PassManager].preprocessTransforms(options)
      summon[PassManager].chirrtlToLowFIRRTL(options)
      summon[PassManager].lowFIRRTLToHW(options, "")
      if !stopAfterHw then
        summon[PassManager].getAsOpPassManager.addPipeline(
          "lower-contracts,verif-lower-symbolic-values{mode=yosys},verif-lower-tests",
          err => throw new RuntimeException(s"verif pipeline parse error: $err")
        )
        summon[PassManager].hwToSV(options)
        summon[PassManager].exportVerilog(options, out ++= _)

      given MlirModule = summon[MlirModuleApi].moduleCreateEmpty(summon[LocationApi].locationUnknownGet)
      given Circuit    = summon[CircuitApi].op(self.moduleName(parameter))
      summon[Circuit].appendToModule()
      self.module(parameter).appendToCircuit()
      validateCircuit()
      summon[PassManager].runOnOp(summon[MlirModule].getOperation)

      if stopAfterHw then summon[MlirModule].getOperation.print(out ++= _)

      summon[Context].destroy()
      out.toString
    finally arena.close()
