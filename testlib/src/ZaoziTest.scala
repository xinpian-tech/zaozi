// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.testlib

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import org.llvm.circt.scalalib.capi.dialect.firrtl.DialectApi as FirrtlDialectApi
import org.llvm.circt.scalalib.capi.dialect.sv.DialectApi as SvDialectApi
import org.llvm.circt.scalalib.capi.dialect.emit.DialectApi as EmitDialectApi
import org.llvm.circt.scalalib.capi.dialect.ltl.DialectApi as LTLDialectApi
import org.llvm.circt.scalalib.capi.dialect.verif.DialectApi as VerifDialectApi
import org.llvm.circt.scalalib.capi.dialect.firrtl.given_DialectApi
import org.llvm.circt.scalalib.capi.firtool.given_FirtoolOptionsApi
import org.llvm.circt.scalalib.dialect.firrtl.operation.{given_CircuitApi, given_ModuleApi, Circuit, CircuitApi}
import org.llvm.circt.scalalib.capi.dialect.emit.given_DialectApi
import org.llvm.circt.scalalib.capi.dialect.ltl.given_DialectApi
import org.llvm.circt.scalalib.capi.dialect.sv.given_DialectApi
import org.llvm.circt.scalalib.capi.dialect.verif.given_DialectApi
import org.llvm.circt.scalalib.capi.firtool.FirtoolApi
import org.llvm.circt.scalalib.capi.firtool.given
import org.llvm.circt.scalalib.capi.firtool.FirtoolOptions
import org.llvm.circt.scalalib.capi.exportfirrtl.given_ExportFirrtlApi
import org.llvm.mlir.scalalib.Mlir
import org.llvm.mlir.scalalib.capi.ir.{
  given_AttributeApi,
  given_BlockApi,
  given_IdentifierApi,
  given_LocationApi,
  given_ModuleApi,
  given_NamedAttributeApi,
  given_OperationApi,
  given_RegionApi,
  given_TypeApi,
  given_ValueApi,
  Block,
  Context,
  LocationApi,
  Module as MlirModule,
  ModuleApi as MlirModuleApi,
  NamedAttributeApi,
  OperationApi,
  Type
}
import org.llvm.mlir.scalalib.capi.pass.{given_OpPassManagerApi, given_PassManagerApi}
import utest.assert

import scala.scalanative.unsafe.Zone

private def withTestModule[A](
  body: MlirModule ?=> A
)(
  using Zone,
  Context
): A =
  given module: MlirModule = summon[MlirModuleApi].moduleCreateEmpty(summon[LocationApi].locationUnknownGet)
  try body
  finally module.destroy()

trait HasMlirTest:
  this: Generator[?, ?, ?, ?] =>
  private val self = this.asInstanceOf[Generator[this.TPARAM, this.TLAYER, this.TINTF, this.TPROBE]]

  def mlirString(
    parameter: this.TPARAM
  ): String =
    Mlir.withContext {
      summon[FirrtlDialectApi].loadDialect
      summon[LTLDialectApi].loadDialect
      summon[VerifDialectApi].loadDialect
      withTestModule {
        given Circuit = summon[CircuitApi].op(self.moduleName(parameter))
        summon[Circuit].appendToModule()
        self.appendModule(parameter)
        summon[Circuit].declareInstantiatedModules()

        val out = new StringBuilder
        summon[MlirModule].getOperation.print(out ++= _)

        out.toString
      }
    }

  def mlirTest(
    parameter: this.TPARAM
  )(predicate: String => Boolean
  ): Unit = assert(predicate(mlirString(parameter)))

  def mlirTest(
    parameter:  this.TPARAM
  )(checkLines: String*
  ): Unit = mlirTest(parameter)(out => checkLines.forall(l => out.contains(l)))

trait HasFirrtlTest:
  this: Generator[?, ?, ?, ?] =>
  private val self = this.asInstanceOf[Generator[this.TPARAM, this.TLAYER, this.TINTF, this.TPROBE]]

  def firrtlString(
    parameter: this.TPARAM
  ): String =
    Mlir.withContext {
      summon[FirrtlDialectApi].loadDialect
      summon[LTLDialectApi].loadDialect
      summon[VerifDialectApi].loadDialect
      withTestModule {
        given Circuit = summon[CircuitApi].op(self.moduleName(parameter))
        summon[Circuit].appendToModule()
        self.appendModule(parameter)

        summon[Circuit].declareInstantiatedModules()
        val out       = new StringBuilder
        summon[MlirModule].exportFIRRTL(out ++= _)

        out.toString
      }
    }

  def firrtlTest(
    parameter: this.TPARAM
  )(predicate: String => Boolean
  ): Unit = assert(predicate(firrtlString(parameter)))

  def firrtlTest(
    parameter:  this.TPARAM
  )(checkLines: String*
  ): Unit = firrtlTest(parameter)(out => checkLines.forall(l => out.contains(l)))

trait HasVerilogTest:
  this: Generator[?, ?, ?, ?] =>
  private val self = this.asInstanceOf[Generator[this.TPARAM, this.TLAYER, this.TINTF, this.TPROBE]]

  def verilogString(
    parameter: this.TPARAM
  ): String =
    Mlir.withContext {
      summon[FirrtlDialectApi].loadDialect
      summon[LTLDialectApi].loadDialect
      summon[SvDialectApi].loadDialect
      summon[EmitDialectApi].loadDialect
      summon[VerifDialectApi].loadDialect
      summon[VerifDialectApi].registerPasses
      given FirtoolOptions = summon[FirtoolApi].firtoolOptionsCreateDefault

      try
        Mlir.withPassManager { manager =>
          val out            = new StringBuilder
          val firtoolOptions = summon[FirtoolOptions]

          manager.preprocessTransforms(firtoolOptions)
          manager.chirrtlToLowFIRRTL(firtoolOptions)
          manager.lowFIRRTLToHW(firtoolOptions, "")
          manager.getAsOpPassManager.addPipeline(
            "lower-contracts,verif-lower-symbolic-values{mode=yosys},verif-lower-tests",
            err => throw new RuntimeException(s"verif pipeline parse error: $err")
          )
          manager.hwToSV(firtoolOptions)
          // TODO: we need a pass for export verilog on a MLIRModule, not it export empty string.
          manager.exportVerilog(firtoolOptions, out ++= _)

          withTestModule {
            given Circuit = summon[CircuitApi].op(self.moduleName(parameter))
            summon[Circuit].appendToModule()
            self.appendModule(parameter)
            summon[Circuit].declareInstantiatedModules()
            manager.runOnOpOrThrow(
              summon[MlirModule].getOperation,
              s"firtool lowering pipeline for module '${self.moduleName(parameter)}'"
            )

            out.toString
          }
        }
      finally summon[FirtoolOptions].destroy
    }

  def verilogTest(
    parameter: this.TPARAM
  )(predicate: String => Boolean
  ): Unit = assert(predicate(verilogString(parameter)))

  def verilogTest(
    parameter:  this.TPARAM
  )(checkLines: String*
  ): Unit = verilogTest(parameter)(out => checkLines.forall(l => out.contains(l)))

trait HasCompileErrorTest:
  this: Generator[?, ?, ?, ?] =>
  private val self = this.asInstanceOf[Generator[this.TPARAM, this.TLAYER, this.TINTF, this.TPROBE]]

  def compileErrorTest(
    parameter: this.TPARAM
  ) =
    Mlir.withContext {
      summon[FirrtlDialectApi].loadDialect
      summon[LTLDialectApi].loadDialect
      summon[VerifDialectApi].loadDialect
      withTestModule {
        given Circuit = summon[CircuitApi].op(self.moduleName(parameter))
        summon[Circuit].appendToModule()
        self.appendModule(parameter)
        ()
      }
    }

def xfail(reason: String)(body: => Any): Unit =
  val unexpectedlyPassed =
    try
      body
      true
    catch case _: Throwable => false

  if unexpectedlyPassed then
    throw new AssertionError(s"XFAIL unexpectedly passed. Please remove xfail marker. Reason: $reason")
