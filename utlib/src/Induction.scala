// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}

import org.llvm.circt.scalalib.capi.dialect.firrtl.{
  given_DialectApi as given_FirrtlDialect,
  DialectApi as FirrtlDialectApi
}
import org.llvm.circt.scalalib.capi.dialect.ltl.{given_DialectApi as given_LTLDialect, DialectApi as LTLDialectApi}
import org.llvm.circt.scalalib.capi.dialect.sv.{given_DialectApi as given_SvDialect, DialectApi as SvDialectApi}
import org.llvm.circt.scalalib.capi.dialect.verif.{
  given_DialectApi as given_VerifDialect,
  DialectApi as VerifDialectApi
}
import org.llvm.circt.scalalib.capi.firtool.{given_FirtoolOptionsApi, FirtoolApi, FirtoolOptions, given}
import org.llvm.mlir.scalalib.capi.ir.{
  given_ContextApi,
  given_ModuleApi,
  given_OperationApi,
  Context,
  ContextApi,
  Module as MlirModule,
  ModuleApi as MlirModuleApi
}
import org.llvm.mlir.scalalib.capi.pass.{given_PassManagerApi, PassManagerApi}
import org.llvm.mlir.scalalib.capi.support.given_LogicalResultApi

import java.lang.foreign.Arena

/** Outcome of an induction step check. */
enum StepOutcome:
  /** No state satisfying the assumptions can violate the assertion in one step. */
  case Proven

  /** A counterexample to induction exists — a state satisfying the assumptions whose
    * successor violates the assertion. Not necessarily reachable.
    */
  case Cti(output: String)

  /** The checker did not produce a recognizable verdict. */
  case Undetermined(output: String)

/** Discharge a construct's self-emitted induction step check with `circt-bmc`.
  *
  * The construct elaborates its own step obligation: registers carry no initial value (the
  * BMC then treats the starting state as fully symbolic — an arbitrary state, which is
  * exactly what an induction step quantifies over), assumptions constrain that state, and
  * the assertion speaks about the explicitly computed next-state wires. One frame of BMC
  * over that module *is* the step case.
  */
object Induction:

  def checkStep[PARAM <: Parameter, L <: LayerInterface[PARAM], I <: HWInterface[PARAM], P <: DVInterface[PARAM, L]](
    dut:       Generator[PARAM, L, I, P],
    parameter: PARAM,
    outDir:    os.Path
  ): StepOutcome =
    os.makeDir.all(outDir)
    elaborate(dut, parameter, outDir)
    val modules = os.list(outDir).filter(_.ext == "mlirbc").sortBy(_.last)
    require(modules.nonEmpty, s"elaboration produced no .mlirbc files under $outDir")
    val top    = dut.moduleName(parameter)
    val linked = outDir / "linked.mlir"
    os.proc(
      Seq("firld", s"--base-circuit=$top", "--no-mangle") ++ modules.map(_.toString) ++ Seq("-o", linked.toString)
    ).call()

    val mlirFile = outDir / "top.mlir"
    os.write.over(mlirFile, hwMlirString(os.read.bytes(linked)))

    val result = os
      .proc(
        "circt-bmc",
        "-b",
        "1",
        "--module",
        top,
        "--run",
        s"--shared-libs=${libz3()}",
        mlirFile.toString
      )
      .call(check = false, mergeErrIntoOut = true)
    val output = result.out.text()
    if output.contains("Bound reached with no violations!") then StepOutcome.Proven
    else if output.contains("Assertion can be violated!") then StepOutcome.Cti(output)
    else StepOutcome.Undetermined(output)

  private def elaborate[PARAM <: Parameter, L <: LayerInterface[PARAM], I <: HWInterface[PARAM], P <: DVInterface[
    PARAM,
    L
  ]](
    dut:       Generator[PARAM, L, I, P],
    parameter: PARAM,
    outDir:    os.Path
  ): Unit =
    val arena = Arena.ofConfined()
    try
      given Arena   = arena
      given Context = summon[ContextApi].contextCreate
      summon[FirrtlDialectApi].loadDialect
      summon[LTLDialectApi].loadDialect
      summon[VerifDialectApi].loadDialect
      Elaboration.inOutputDirectory(outDir) {
        dut.dumpMlirbc(parameter)
      }
      summon[Context].destroy()
    finally arena.close()

  /** Lower linked FIRRTL to the HW/Comb/Seq level `circt-bmc` consumes, as MLIR text. */
  private def hwMlirString(linkedFirrtl: Array[Byte]): String =
    val arena = Arena.ofConfined()
    try
      given Arena          = arena
      given Context        = summon[ContextApi].contextCreate
      summon[FirrtlDialectApi].loadDialect
      summon[LTLDialectApi].loadDialect
      summon[VerifDialectApi].loadDialect
      summon[SvDialectApi].loadDialect
      given FirtoolOptions = summon[FirtoolApi].firtoolOptionsCreateDefault
      given MlirModule     = summon[MlirModuleApi].moduleCreateParse(linkedFirrtl)

      val options = summon[FirtoolOptions]
      val toHw    = summon[PassManagerApi].passManagerCreate
      toHw.preprocessTransforms(options)
      toHw.chirrtlToLowFIRRTL(options)
      toHw.lowFIRRTLToHW(options, "")
      if !toHw.runOnOp(summon[MlirModule].getOperation).succeeded then
        throw new RuntimeException("FIRRTL-to-HW lowering failed")

      val out = new StringBuilder
      summon[MlirModule].getOperation.print(out ++= _)
      summon[Context].destroy()
      out.toString
    finally arena.close()

  /** The Z3 shared library `circt-bmc --run` JIT-links against. The development shell
    * exports it as `Z3_LIB`; a dynamically linked `z3` binary is the fallback.
    */
  private def libz3(): os.Path =
    sys.env.get("Z3_LIB").map(os.Path(_)).filter(os.exists).getOrElse {
      val z3  = os.proc("which", "z3").call().out.trim()
      val ldd = os.proc("ldd", z3).call().out.text()
      raw"(/\S+/libz3\.so[^\s(]*)".r
        .findFirstIn(ldd)
        .map(os.Path(_))
        .getOrElse(
          throw new IllegalStateException(s"Z3_LIB is unset and ldd $z3 shows no libz3.so:\n$ldd")
        )
    }
