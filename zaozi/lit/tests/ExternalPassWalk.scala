// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>

import org.llvm.mlir.raw.structs as MlirStructs
import org.llvm.mlir.raw.functions as mlirApi
import scala.scalanative.unsafe.*

// DEFINE: %{test} = scala-cli --server=false --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %NATIVEOPTS --main-class ExternalPassWalkMain %s
// RUN: %{test} -- %REPOROOT/circtlib/tests/resources/gcd.mlir 2>&1 | FileCheck %s

import org.llvm.circt.scalalib.capi.dialect.comb.{given_DialectApi, DialectApi as CombDialectApi}
import org.llvm.circt.scalalib.capi.dialect.firrtl.{given_DialectApi, DialectApi as FirrtlDialectApi}
import org.llvm.circt.scalalib.capi.dialect.hw.{*, given}
import org.llvm.circt.scalalib.capi.dialect.hw.{given_DialectApi, DialectApi as HWDialectApi}
import org.llvm.circt.scalalib.capi.dialect.om.{given_DialectApi, DialectApi as OMDialectApi}
import org.llvm.circt.scalalib.capi.dialect.seq.{given_DialectApi, DialectApi as SeqDialectApi}
import org.llvm.circt.scalalib.capi.dialect.sv.{given_DialectApi, DialectApi as SVDialectApi}
import org.llvm.mlir.scalalib.capi.ir.{*, given}
import org.llvm.mlir.scalalib.capi.pass.{*, given}
import org.llvm.mlir.scalalib.capi.support.{*, given}

import java.nio.file.{Files, Path}

// Drives the planned end-to-end ExternalPass path against the actual GCD
// MLIR resource at circtlib/tests/resources/gcd.mlir (the firtool-produced
// design used by circtlib/tests/src/OmSmoke.scala). The path is passed as
// args(0) via the lit %REPOROOT substitution. The pass body walks the
// parsed module, finds the hw.module op, decodes each port via the
// HWModuleType API in declaration order, and prints one stable ASCII line
// per port. FileCheck below asserts the exact lines.
object ExternalPassWalkMain:

  private def directionName(dir: Int): String = dir match
    case 0     => "input"
    case 1     => "output"
    case 2     => "inout"
    case other => s"unknown($other)"

  def main(args: Array[String]): Unit =
    require(args.length >= 1, "ExternalPassWalkMain expects a path to gcd.mlir as args(0)")
    val gcdMlirBytes = Files.readAllBytes(Path.of(args(0)))
    val outer        = Zone.open()
    try
      given Zone  = outer
      val context = summon[ContextApi].contextCreate
      try
        given Context = context
        summon[CombDialectApi].loadDialect
        summon[HWDialectApi].loadDialect
        summon[SeqDialectApi].loadDialect
        summon[OMDialectApi].loadDialect
        summon[SVDialectApi].loadDialect
        summon[FirrtlDialectApi].loadDialect
        val module    = summon[ModuleApi].moduleCreateParse(gcdMlirBytes)
        val moduleOp  = module.getOperation
        try
          val pm = summon[PassManagerApi].passManagerCreate
          try
            val transient = Zone.open()
            try
              given Zone    = transient
              val uniquePtr = alloc[Long]().asInstanceOf[Ptr[Byte]]
              val passId    = TypeID(nativeResult[MlirStructs.MlirTypeID](out => mlirApi.mlirTypeIDCreate(uniquePtr)(out)))
              val pass      = summon[PassApi].createExternalPass(
                passId = passId,
                name = "ExternalPassPortWalk",
                argument = "external-pass-port-walk",
                description = "Walks hw.module ports and prints name/width/direction",
                opName = "",
                dependentDialects = Seq.empty,
                constructCallback = () => (),
                destructCallback = () => (),
                initializeCallback = None,
                cloneCallback = () => (),
                runCallback = (op, _) =>
                  // Inner zone scopes the port-decoding allocations; closes
                  // before the upcall returns so the run callback does not
                  // hold memory across pass-manager iterations.
                  val a = Zone.open()
                  try
                    given Zone = a
                    op.walk(
                      o =>
                        if o.getName.str == "hw.module" then
                          val moduleTypeAttr = o.getInherentAttributeByName("module_type")
                          val moduleType     = moduleTypeAttr.typeAttrGetValue
                          val numInputs      = moduleType.moduleTypeGetNumInputs()
                          val numOutputs     = moduleType.moduleTypeGetNumOutputs()
                          val numPorts       = numInputs + numOutputs
                          var i              = 0
                          while i < numPorts do
                            val port      = moduleType.moduleTypeGetPort(i)
                            val nameAttr  = port.portName
                            val portType  = port.portType
                            val name      = nameAttr.stringAttrGetValue
                            // hwGetBitWidth returns -1 for non-HW types such
                            // as !seq.clock; the conventional clock signal
                            // width is 1, matching the i1 electrical signal.
                            val rawWidth  = portType.getBitWidth()
                            val width     = if rawWidth <= 0 then 1 else rawWidth
                            val direction = directionName(port.portDirection)
                            println(s"PORT: name=$name width=$width direction=$direction")
                            i += 1
                        WalkResultEnum.Advance
                      ,
                      WalkEnum.PreOrder
                    )
                  finally a.close()
              )
              pm.addOwnedPass(pass)
            finally transient.close()
            val result    = pm.runOnOp(moduleOp)
            println(s"PASS: status=${if result.succeeded then "success" else "failure"}")
          finally pm.destroy()
        finally module.destroy()
      finally context.destroy()
    finally outer.close()
end ExternalPassWalkMain

// CHECK: PORT: name=clock width=1 direction=input
// CHECK-NEXT: PORT: name=reset width=1 direction=input
// CHECK-NEXT: PORT: name=input_ready width=1 direction=output
// CHECK-NEXT: PORT: name=input_valid width=1 direction=input
// CHECK-NEXT: PORT: name=input_bits_x width=16 direction=input
// CHECK-NEXT: PORT: name=input_bits_y width=16 direction=input
// CHECK-NEXT: PORT: name=output_valid width=1 direction=output
// CHECK-NEXT: PORT: name=output_bits width=16 direction=output
// CHECK-NEXT: PASS: status=success
