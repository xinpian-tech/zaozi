// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
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

/** The emitted lib-model artifact: the flat SystemVerilog whose ports are the ABI contract (each drive port and
  * clock/reset an input, each probe point a `probe_<name>` output), plus the layer bind / probe-ref split files. It is
  * ready for an external tool to drive; `topModule` is the top.
  */
final case class LibModel(topModule: String, sources: Seq[os.Path])

/** Turns one Zaozi UT module into artifacts, and stops there.
  *
  * The framework's job is to reduce a DUT-plus-verification-intent to data and IR — the ABI contract ([[saveAbi]]) and
  * the flat lib-model SystemVerilog ([[emitLib]]) — both derived from the module's `(IO, Probe)`. Generating stimulus
  * (from the module's SVA assertions) and driving a simulator with these artifacts are deliberately external concerns:
  * nothing here solves, runs a simulator, or emits a testbench.
  */
final class UTGenerator[
  PARAM <: Parameter,
  L <: LayerInterface[PARAM],
  I <: HWInterface[PARAM],
  P <: DVInterface[PARAM, L]
] private (
  val dut:       Generator[PARAM, L, I, P] & UT[PARAM, I],
  val parameter: PARAM,
  val outputDirectory: os.Path):

  /** The ABI contract as a dependent type on this DUT's `(I, P)`: `abi.drive.<port>` and `abi.probe.<point>` are
    * checked at compile time against the DUT's IO and Probe, and `abi.spec` is the serializable specification derived
    * from those interfaces.
    */
  def abi: Abi[I, P] =
    val arena = Arena.ofConfined()
    try
      given Arena   = arena
      given Context = summon[ContextApi].contextCreate
      summon[FirrtlDialectApi].loadDialect
      new Abi[I, P](AbiSpec.derive(dut.moduleName(parameter), dut.interface(parameter), dut.probe(parameter)))
    finally arena.close()

  /** Materialize the DPI spec and write it as JSON. */
  def saveAbi(path: os.Path = outputDirectory / "abi.json"): AbiSpec =
    val spec = abi.spec
    os.makeDir.all(path / os.up)
    os.write.over(path, spec.toJson)
    spec

  /** Elaborate and lower the lib model: a flat SystemVerilog module whose ports *are* the ABI contract (drive ports and
    * clock/reset as inputs, each probe point as a `probe_<name>` output). It has no internal loop — an external tool
    * drives it, poking the drive ports and peeking the probe ports. The split files (layer binds and probe-ref
    * exposers) are emitted alongside because reading the DUT's probe lowers to a hierarchical reference into the
    * verification layer's bind. Returns the top module and every SystemVerilog source written under `outDir`.
    */
  def emitLib(outDir: os.Path = outputDirectory): LibModel =
    os.makeDir.all(outDir)
    val harnessParameter = LibHarnessParameter(abi.spec)
    val harness          = new LibHarnessGenerator(dut, parameter)
    val topModule        = harness.moduleName(harnessParameter)
    val moduleDir        = outDir / s"lib_mlir_${harnessParameter.hashCode.toHexString}"
    os.makeDir.all(moduleDir)

    elaborate(harness, harnessParameter, moduleDir)
    val modules = os.list(moduleDir).filter(_.ext == "mlirbc").sortBy(_.last)
    require(modules.nonEmpty, s"elaboration produced no .mlirbc files under $moduleDir")
    val linked  = moduleDir / "linked.mlir"
    os.proc(
      Seq("firld", s"--base-circuit=$topModule", "--no-mangle") ++ modules.map(_.toString) ++ Seq("-o", linked.toString)
    ).call()

    val emitted = SvEmitter.writeVerilog(SvEmitter.verilogString(os.read.bytes(linked)), outDir)
    LibModel(topModule, Seq(emitted.primary) ++ emitted.splitFiles.values.toSeq)

  /** Generate the DPI-export wrapper for the lib model: an SV module exposing `export "DPI-C"` poke/peek per contract
    * port, plus a C lifecycle shim. Together with [[emitLib]]'s sources these build (`verilator --lib-create`) into a
    * `.so` an external driver loads and calls. `libTop` is the lib model's top module (from [[emitLib]]).
    */
  def emitDpiWrapper(libTop: String, outDir: os.Path = outputDirectory): DpiWrapper =
    os.makeDir.all(outDir)
    val spec       = abi.spec
    val wrapperTop = DpiWrapper.top(spec)
    val svPath     = outDir / s"$wrapperTop.sv"
    val capiPath   = outDir / s"${wrapperTop}_capi.cpp"
    os.write.over(svPath, DpiWrapper.svString(libTop, spec))
    os.write.over(capiPath, DpiWrapper.capiString(spec))
    DpiWrapper(wrapperTop, Seq(svPath, capiPath))

  private def elaborate[
    HP <: Parameter,
    HL <: LayerInterface[HP],
    HI <: HWInterface[HP],
    HProbe <: DVInterface[
      HP,
      HL
    ]
  ](harness:   Generator[HP, HL, HI, HProbe],
    parameter: HP,
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
        harness.dumpMlirbc(parameter)
      }
      summon[Context].destroy()
    finally arena.close()

object UTGenerator:
  def apply[PARAM <: Parameter, L <: LayerInterface[PARAM], I <: HWInterface[PARAM], P <: DVInterface[PARAM, L]](
    dut:             Generator[PARAM, L, I, P] & UT[PARAM, I],
    parameter:       PARAM,
    outputDirectory: os.Path
  ): UTGenerator[PARAM, L, I, P] =
    new UTGenerator(dut, parameter, outputDirectory)
