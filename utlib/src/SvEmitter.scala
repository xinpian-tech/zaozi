// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib

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
  Module as MlirModule,
  ModuleApi as MlirModuleApi,
  OperationApi,
  WalkEnum,
  WalkResultEnum
}
import org.llvm.mlir.scalalib.capi.pass.{given_OpPassManagerApi, given_PassManagerApi, PassManagerApi}
import org.llvm.mlir.scalalib.capi.support.given_LogicalResultApi

import java.lang.foreign.Arena

private[utlib] object SvEmitter:
  final case class EmittedVerilog(primary: os.Path, splitFiles: Map[String, os.Path])

  private val fileMarker = raw"""^// -+ 8< -+ FILE "([^"]+)" -+ 8< -+$$""".r

  /** Lower the linked FIRRTL module while preserving implementations inside contracts. */
  def verilogString(linkedFirrtl: Array[Byte]): String =
    val arena = Arena.ofConfined()
    try
      given Arena          = arena
      given Context        = summon[ContextApi].contextCreate
      summon[FirrtlDialectApi].loadDialect
      summon[LTLDialectApi].loadDialect
      summon[VerifDialectApi].loadDialect
      summon[SvDialectApi].loadDialect
      summon[EmitDialectApi].loadDialect
      summon[SimDialectApi].loadDialect
      summon[VerifDialectApi].registerPasses
      given FirtoolOptions = summon[FirtoolApi].firtoolOptionsCreateDefault
      given MlirModule     = summon[MlirModuleApi].moduleCreateParse(linkedFirrtl)

      val options = summon[FirtoolOptions]
      val toHw    = summon[PassManagerApi].passManagerCreate
      toHw.preprocessTransforms(options)
      toHw.chirrtlToLowFIRRTL(options)
      toHw.lowFIRRTLToHW(options, "")
      if !toHw.runOnOp(summon[MlirModule].getOperation).succeeded then
        throw new RuntimeException("linked FIRRTL-to-HW lowering failed")

      proceduralizeUnclockedVerification(summon[MlirModule])

      val out  = new StringBuilder
      val toSv = summon[PassManagerApi].passManagerCreate
      toSv.getAsOpPassManager.addPipeline(
        "strip-contracts,verif-lower-symbolic-values{mode=yosys},verif-lower-tests",
        err => throw new RuntimeException(s"verif pipeline parse error: $err")
      )
      toSv.hwToSV(options)
      toSv.exportVerilog(options, out ++= _)
      if !toSv.runOnOp(summon[MlirModule].getOperation).succeeded then
        throw new RuntimeException("linked HW-to-SV lowering failed")

      summon[Context].destroy()
      out.toString
    finally arena.close()

  /** Direct verification operations in combinational modules have no clock. A procedural region makes CIRCT emit legal
    * immediate SystemVerilog assertions and covers.
    */
  private def proceduralizeUnclockedVerification(
    module: MlirModule
  )(
    using Arena,
    Context
  ): Unit =
    val names = Set("verif.assert", "verif.assume", "verif.cover")
    module.getOperation.walk(
      { operation =>
        if names.contains(operation.getName.str)
          && operation.getParentOperation.getName.str != "sv.alwayscomb"
        then
          val parentBlock = operation.getBlock
          val alwaysComb  = summon[OperationApi].operationCreate(
            name = "sv.alwayscomb",
            location = operation.getLocation,
            regionBlockTypeLocations = Seq(Seq(Seq.empty -> Seq.empty))
          )
          parentBlock.insertOwnedOperationBefore(operation, alwaysComb)
          operation.removeFromParent()
          alwaysComb.getFirstRegion.getFirstBlock.appendOwnedOperation(operation)
        WalkResultEnum.Advance
      },
      WalkEnum.PreOrder
    )

  /** Write firtool's primary output and any split layer/bind/blackbox files. */
  def writeVerilog(verilog: String, outDir: os.Path): EmittedVerilog =
    os.makeDir.all(outDir)
    val main       = StringBuilder()
    val files      = scala.collection.mutable.ListBuffer.empty[(String, StringBuilder)]
    verilog.linesIterator.foreach {
      case fileMarker(name) => files += (name -> StringBuilder())
      case line             =>
        val sink = files.lastOption.map(_._2).getOrElse(main)
        sink ++= line
        sink ++= "\n"
    }
    val generated  = outDir / "generated.sv"
    os.write.over(generated, main.toString)
    val splitFiles = files.toSeq.map { case (name, content) =>
      val path = outDir / name
      os.write.over(path, content.toString)
      name -> path
    }.toMap
    EmittedVerilog(generated, splitFiles)
