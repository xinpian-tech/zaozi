// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.utlib

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}

import org.llvm.circt.scalalib.capi.dialect.firrtl.{given_DialectApi, DialectApi as FirrtlDialectApi}
import org.llvm.circt.scalalib.capi.dialect.ltl.{given_DialectApi as given_LTLDialectApi, DialectApi as LTLDialectApi}
import org.llvm.circt.scalalib.capi.dialect.verif.{
  given_DialectApi as given_VerifDialectApi,
  DialectApi as VerifDialectApi
}
import org.llvm.mlir.scalalib.capi.ir.{Context, ContextApi, given}

import java.lang.foreign.Arena

/** The one MLIR-context bracket for elaborating a zaozi generator to `.mlirbc` files and linking them with `firld` —
  * shared by every flow that lowers a design ([[UTGenerator]]'s lib model and testbench, [[FormalUT]]'s bounded
  * model).
  */
private[utlib] object Lower:

  /** Elaborate `gen` under a fresh MLIR context, dumping per-module `.mlirbc` files into `outDir`. */
  def elaborate[
    PARAM <: Parameter,
    L <: LayerInterface[PARAM],
    I <: HWInterface[PARAM],
    P <: DVInterface[PARAM, L]
  ](gen:       Generator[PARAM, L, I, P],
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
        gen.dumpMlirbc(parameter)
      }
      summon[Context].destroy()
    finally arena.close()

  /** [[elaborate]] into `moduleDir`, then link every dumped `.mlirbc` into one FIRRTL module rooted at `top`. */
  def elaborateAndLink[
    PARAM <: Parameter,
    L <: LayerInterface[PARAM],
    I <: HWInterface[PARAM],
    P <: DVInterface[PARAM, L]
  ](gen:       Generator[PARAM, L, I, P],
    parameter: PARAM,
    moduleDir: os.Path,
    top:       String
  ): os.Path =
    os.makeDir.all(moduleDir)
    elaborate(gen, parameter, moduleDir)
    val modules = os.list(moduleDir).filter(_.ext == "mlirbc").sortBy(_.last)
    require(modules.nonEmpty, s"elaboration produced no .mlirbc files under $moduleDir")
    val linked  = moduleDir / "linked.mlir"
    os.proc(
      Seq(CirctTools("firld"), s"--base-circuit=$top", "--no-mangle") ++ modules.map(_.toString) ++
        Seq("-o", linked.toString)
    ).call()
    linked
