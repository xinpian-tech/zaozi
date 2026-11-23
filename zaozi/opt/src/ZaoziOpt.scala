// SPDX-License-Identifier: Apache-2.0
package me.jiuyang.zaozi.tools

import org.llvm.circt.raw.functions as circt
import org.llvm.mlir.raw.functions as mlir
import org.llvm.mlir.scalalib.{Mlir, ModuleTransform}
import org.llvm.mlir.scalalib.capi.ir.{*, given}
import org.llvm.mlir.scalalib.capi.pass.{*, given}
import org.llvm.mlir.scalalib.capi.support.{*, given}
import scala.scalanative.unsafe.Zone

/** Read one bytecode module, optionally run an MLIR pass pipeline, and write bytecode. */
object ZaoziOpt:
  private def register(
  )(
    using Zone,
    Context
  ): Unit =
    val registry = summon[DialectRegistryApi].registryCreate()
    try
      mlir.mlirRegisterAllDialects(registry.native)
      summon[Context].appendDialectRegistry(registry)
    finally registry.destroy()
    val dialects = Seq(
      circt.mlirGetDialectHandle__arc__(),
      circt.mlirGetDialectHandle__chirrtl__(),
      circt.mlirGetDialectHandle__comb__(),
      circt.mlirGetDialectHandle__dc__(),
      circt.mlirGetDialectHandle__debug__(),
      circt.mlirGetDialectHandle__emit__(),
      circt.mlirGetDialectHandle__esi__(),
      circt.mlirGetDialectHandle__firrtl__(),
      circt.mlirGetDialectHandle__fsm__(),
      circt.mlirGetDialectHandle__handshake__(),
      circt.mlirGetDialectHandle__hw__(),
      circt.mlirGetDialectHandle__hwarith__(),
      circt.mlirGetDialectHandle__llhd__(),
      circt.mlirGetDialectHandle__ltl__(),
      circt.mlirGetDialectHandle__moore__(),
      circt.mlirGetDialectHandle__msft__(),
      circt.mlirGetDialectHandle__om__(),
      circt.mlirGetDialectHandle__rtg__(),
      circt.mlirGetDialectHandle__rtgtest__(),
      circt.mlirGetDialectHandle__seq__(),
      circt.mlirGetDialectHandle__sv__(),
      circt.mlirGetDialectHandle__verif__()
    )
    dialects.foreach(h => DialectHandle(nativeCopy(h)).loadDialect)
    // CIRCT supplies its own convert-to-llvm pass. Register upstream transforms
    // and CIRCT conversions without registering that name twice.
    mlir.mlirRegisterTransformsPasses()
    circt.mlirRegisterCIRCTConversionPasses()
    circt.mlirRegisterRTGPasses()
    circt.registerArcPasses()
    circt.registerCombPasses()
    circt.registerDCPasses()
    circt.registerESIPasses()
    circt.registerFSMPasses()
    circt.registerHWArithPasses()
    circt.registerHWPasses()
    circt.registerHandshakePasses()
    circt.registerSVPasses()
    circt.registerSeqPasses()
    circt.registerVerifPasses()

  def main(args: Array[String]): Unit =
    if args.length > 1 then
      System.err.println("usage: zaozi-opt ['builtin.module(...)']")
      sys.exit(2)
    val plugin = ModuleTransform(register()) { module =>
      args.headOption.foreach(pipeline => Mlir.runPipeline(module, pipeline))
    }
    plugin.main(Array.empty)
