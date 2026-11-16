// SPDX-License-Identifier: Apache-2.0
package me.jiuyang.tblib.runner

import java.lang.foreign.Arena
import me.jiuyang.tblib.Testbench
import me.jiuyang.tblib.default.given
import org.llvm.mlir.scalalib.capi.ir.{Context, ContextApi, Module as MlirModule, ModuleApi, given}
import org.llvm.mlir.MlirModule as NativeModule

import org.llvm.circt.scalalib.capi.dialect.firrtl.{DialectApi as FIRRTLDialect, given}
import org.llvm.circt.scalalib.capi.dialect.hw.given
import org.llvm.circt.scalalib.capi.dialect.comb.given
import org.llvm.circt.scalalib.capi.dialect.seq.given
import org.llvm.circt.scalalib.capi.dialect.sv.given
import org.llvm.circt.scalalib.capi.dialect.sim.given
import org.llvm.circt.scalalib.capi.dialect.ltl.given
import org.llvm.circt.scalalib.capi.dialect.verif.given
import org.llvm.circt.scalalib.capi.dialect.emit.given

/** Process adapter used by the Python harness. */
object TestbenchMain:
  // Internal process boundary. Python resolves and validates all command-line options.
  def main(args: Array[String]): Unit =
    require(args.length == 2, "expected DUT and clock IR paths")
    val dut   = os.Path(args(0), os.pwd)
    val clock = os.Path(args(1), os.pwd)
    val out   = os.Path(sys.env("ZAOZI_OUTDIR"), os.pwd)
    withContext:
      val dutModule = read(dut)
      try
        val clockModule = read(clock)
        try
          val result = Testbench(dutModule, clockModule)
          val top = dutModule.getBody.getFirstOperation.getInherentAttributeByName("name").stringAttrGetValue + "TestBench"
          try writeArtifacts(result, top, out)
          finally result.destroy()
        finally clockModule.destroy()
      finally dutModule.destroy()

  private def read(path: os.Path)(using Arena, Context): MlirModule =
    val module = summon[ModuleApi].moduleCreateParse(os.read.bytes(path))
    require(NativeModule.ptr(module.segment).address != 0, s"cannot parse IR: $path")
    module

  /** Borrows the module; the caller remains responsible for destroying it. */
  private def writeArtifacts(
    module: MlirModule,
    name: String,
    out:    os.Path
  )(
    using Arena,
    Context
  ): Unit =
    val bytecode = module.toMlirBytecode
    // Finish conversions before publishing any output.
    val dpi      = module.toDpiJson
    val verilog  = module.toVerilog
    os.makeDir.all(out)
    os.write.over(out / s"$name.mlirbc", bytecode)
    os.write.over(out / s"$name.json", ujson.write(dpi, indent = 2))
    os.write.over(out / s"$name.sv", verilog)

  private def withContext[T](body: (Arena, Context) ?=> T): T =
    val arena = Arena.ofConfined()
    try
      given Arena   = arena
      given Context = summon[ContextApi].contextCreate
      try
        summon[FIRRTLDialect].loadDialect
        summon[org.llvm.circt.scalalib.capi.dialect.hw.DialectApi].loadDialect
        summon[org.llvm.circt.scalalib.capi.dialect.comb.DialectApi].loadDialect
        summon[org.llvm.circt.scalalib.capi.dialect.seq.DialectApi].loadDialect
        summon[org.llvm.circt.scalalib.capi.dialect.sv.DialectApi].loadDialect
        summon[org.llvm.circt.scalalib.capi.dialect.sim.DialectApi].loadDialect
        summon[org.llvm.circt.scalalib.capi.dialect.ltl.DialectApi].loadDialect
        summon[org.llvm.circt.scalalib.capi.dialect.verif.DialectApi].loadDialect
        summon[org.llvm.circt.scalalib.capi.dialect.emit.DialectApi].loadDialect
        body
      finally summon[Context].destroy()
    finally arena.close()
