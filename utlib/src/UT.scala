// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib

import me.jiuyang.zaozi.{HWInterface, Parameter}
import me.jiuyang.zaozi.default.{runOnOpOrThrow, withCapturedDiagnostics}

import org.llvm.circt.scalalib.capi.conversion.{ConversionRegisterApi, given}
import org.llvm.circt.scalalib.capi.dialect.comb.{DialectApi as CombDialectApi, given}
import org.llvm.circt.scalalib.capi.dialect.firrtl.{DialectApi as FirrtlDialectApi, given}
import org.llvm.circt.scalalib.capi.dialect.hw.{DialectApi as HWDialectApi, given}
import org.llvm.circt.scalalib.capi.dialect.llhd.{DialectApi as LLHDDialectApi, given}
import org.llvm.circt.scalalib.capi.dialect.seq.{DialectApi as SeqDialectApi, given}
import org.llvm.circt.scalalib.capi.dialect.sim.{DialectApi as SimDialectApi, given}
import org.llvm.circt.scalalib.capi.dialect.sv.{DialectApi as SVDialectApi, given}
import org.llvm.circt.scalalib.capi.exportverilog.given_ExportVerilogApi
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, ContextApi, LocationApi, ModuleApi, Operation, WalkEnum, WalkResultEnum, given}
import org.llvm.mlir.scalalib.capi.pass.{PassManager, PassManagerApi, given}
import org.llvm.mlir.scalalib.capi.support.given_LogicalResultApi

import java.lang.foreign.Arena
import java.io.ByteArrayOutputStream
import java.nio.file.StandardOpenOption.*

object UT:
  final case class Files(mlirbc: os.Path, sv: os.Path, interfaceJson: os.Path)

  /** Contents remain usable after the native MLIR context has been destroyed. */
  final case class Lowered(mlirbc: Vector[Byte], svString: String, dpiSchemaJson: String):
    def writeTo(outDir: os.Path): Files =
      os.makeDir.all(outDir)
      val bytecode = outDir / "testbench.mlirbc"
      val stream   = os.write.outputStream(bytecode, openOptions = Seq(WRITE, CREATE, TRUNCATE_EXISTING))
      try stream.write(mlirbc.toArray)
      finally stream.close()
      val sv   = outDir / "testbench.sv"
      val json = outDir / "interface.json"
      os.write.over(sv, svString)
      os.write.over(json, dpiSchemaJson)
      Files(bytecode, sv, json)

/** A typed testbench that shares Zaozi's parameter and interface model with a DUT generator.
  *
  * The testbench body owns all HW, LLHD, sim and SV operations. This trait
  * elaborates and lowers in memory; the caller chooses whether to print or save.
  */
trait UT[PARAM <: Parameter, I <: HWInterface[PARAM]]:
  type TPARAM = PARAM
  type TINTF  = I

  /** Override only when the testbench contains more than one external DUT module. */
  def dutModuleName(parameter: PARAM): String = ""
  def testbench(parameter: PARAM)(using Arena, Context, Block): Unit

  /** Elaborate and lower in-process, just as GeneratorApi elaborates a design. */
  final def lower(parameter: PARAM): UT.Lowered =
    val arena = Arena.ofConfined()
    try
      given Arena   = arena
      given Context = summon[ContextApi].contextCreate
      try
        summon[HWDialectApi].loadDialect
        summon[CombDialectApi].loadDialect
        summon[FirrtlDialectApi].loadDialect
        summon[LLHDDialectApi].loadDialect
        summon[SeqDialectApi].loadDialect
        summon[SimDialectApi].loadDialect
        summon[SVDialectApi].loadDialect
        val module = summon[ModuleApi].moduleCreateEmpty(summon[LocationApi].locationUnknownGet)
        try
          given Block = module.getBody
          testbench(parameter)
          require(module.getOperation.verify, "invalid UT MLIR module")
          val dutName =
            val configured = dutModuleName(parameter)
            if configured.nonEmpty then configured else inferDutModule(module.getOperation)
          val bytecode = new ByteArrayOutputStream
          module.getOperation.writeBytecode(bytes => bytecode.write(bytes))

          val json = new StringBuilder
          val (schemaResult, diagnostics) = withCapturedDiagnostics:
            module.exportDPIInterface(dutName, json.append(_))
          if schemaResult.failed then
            throw new IllegalStateException(s"CIRCT DPI interface export failed: ${diagnostics.trim}")

          summon[ConversionRegisterApi].passes
          runPipeline(
            module.getOperation,
            "lower-llhd-clock-to-sv,lower-sim-to-sv,lower-seq-to-sv",
            "CIRCT UT SystemVerilog lowering"
          )
          val source = new StringBuilder
          if module.exportVerilog(source.append(_)).failed then
            throw new IllegalStateException("CIRCT failed to export the UT SystemVerilog")
          UT.Lowered(bytecode.toByteArray.toVector, source.toString, json.toString)
        finally module.destroy()
      finally summon[Context].destroy()
    finally arena.close()

  private def inferDutModule(operation: Operation)(using Arena): String =
    val names = scala.collection.mutable.ArrayBuffer.empty[String]
    operation.walk(
      op =>
        if op.getName.str == "hw.module.extern" then
          names += op.getInherentAttributeByName("sym_name").stringAttrGetValue
        WalkResultEnum.Advance,
      WalkEnum.PreOrder
    )
    require(names.size == 1, s"expected one external DUT module, found ${names.size}; override dutModuleName")
    names.head

  private def runPipeline(operation: org.llvm.mlir.scalalib.capi.ir.Operation, pipeline: String, what: String)(
    using Arena,
    Context
  ): Unit =
    given PassManager = summon[PassManagerApi].passManagerCreate
    try
      val errors = new StringBuilder
      if summon[PassManager].getAsOpPassManager.addPipeline(pipeline, errors.append(_)).failed then
        throw new IllegalArgumentException(s"invalid $what pipeline: $errors")
      summon[PassManager].runOnOpOrThrow(operation, what)
    finally summon[PassManager].destroy()
