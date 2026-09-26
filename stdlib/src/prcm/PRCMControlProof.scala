// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.prcm

import java.lang.foreign.Arena

import me.jiuyang.smtlib.SMTQuery
import me.jiuyang.stdlib.default.{DecoderParameter, PLADecoder}
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.magic.validateCircuit
import org.llvm.circt.scalalib.capi.dialect.firrtl.{given_DialectApi, DialectApi as FirrtlDialect}
import org.llvm.circt.scalalib.capi.dialect.ltl.{given_DialectApi, DialectApi as LtlDialect}
import org.llvm.circt.scalalib.capi.dialect.verif.{given_DialectApi, DialectApi as VerifDialect}
import org.llvm.circt.scalalib.capi.firtool.{FirtoolApi, given}
import org.llvm.circt.scalalib.dialect.firrtl.operation.{Circuit, CircuitApi, given}
import org.llvm.mlir.scalalib.capi.dialect.smt.{given_DialectApi, DialectApi as SmtDialect}
import org.llvm.mlir.scalalib.capi.ir.{
  Block,
  Context,
  ContextApi,
  LocationApi,
  Module,
  ModuleApi,
  Operation,
  Value,
  given
}
import org.llvm.mlir.scalalib.capi.pass.{PassManagerApi, given}
import org.llvm.mlir.scalalib.capi.support.given
import org.llvm.mlir.scalalib.dialect.smt.operation.{AndApi, AssertApi, BVConstantApi, EqApi, NotApi, OrApi, given}

private[prcm] object PRCMControlProof:
  def queries(parameter: DecoderParameter): (SMTQuery, SMTQuery) =
    val arena = Arena.ofConfined()
    try
      given Arena   = arena
      given Context = summon[ContextApi].contextCreate
      try
        summon[FirrtlDialect].loadDialect
        summon[LtlDialect].loadDialect
        summon[VerifDialect].loadDialect
        summon[SmtDialect].loadDialect()
        given Module = summon[ModuleApi].moduleCreateEmpty(summon[LocationApi].locationUnknownGet)
        val hardware =
          try
            given Circuit = summon[CircuitApi].op(PLADecoder.moduleName(parameter))
            summon[Circuit].appendToModule()
            PLADecoder.module(parameter).appendToCircuit()
            validateCircuit()
            val options   = summon[FirtoolApi].firtoolOptionsCreateDefault
            val passes    = summon[PassManagerApi].passManagerCreate
            try
              require(passes.preprocessTransforms(options).succeeded)
              require(passes.chirrtlToLowFIRRTL(options).succeeded)
              require(passes.lowFIRRTLToHW(options, "").succeeded)
              passes.runOnOpOrThrow(summon[Module].getOperation, "PRCM control lowering")
              val output = new StringBuilder
              summon[Module].getOperation.print(output ++= _)
              output.toString
            finally
              passes.destroy()
              options.destroy
          finally summon[Module].destroy()

        val lowered = os
          .proc(
            "circt-opt",
            "--strip-om",
            "--convert-hw-to-smt=for-smtlib-export",
            "--convert-comb-to-smt",
            "--reconcile-unrealized-casts"
          )
          .call(stdin = hardware, stderr = os.Pipe, timeout = 30000)
          .out
          .text()
        val module  = summon[ModuleApi].moduleCreateParse(lowered)
        try
          require(module.getOperation.verify, "invalid lowered control relation")
          val solver       = module.getBody.getFirstOperation
          require(solver.getName.str == "smt.solver", "expected one control relation")
          val block        = solver.getRegion(0).getFirstBlock
          val operations   = Iterator
            .iterate(block.getFirstOperation)(_.getNextInBlock)
            .takeWhile(_.getName.str != "smt.yield")
            .toVector
          val declarations = operations.filter(_.getName.str == "smt.declare_fun")
          require(declarations.size == 2, "expected one decoder input and one output")
          val input        = declarations.head.getResult(0)
          val output       = declarations.last.getResult(0)
          def emitQuery(): SMTQuery = SMTQuery.fromModule(module)
          val feasible   = emitQuery()
          val terminator = block.getTerminator
          val location   = solver.getLocation
          def insert(operation: Operation): Value =
            block.insertOwnedOperationBefore(terminator, operation)
            operation.getResult(0)
          val table      = parameter.tables.head
          val mismatches = table.table.map: (pattern, expected) =>
            require(!pattern.hasDontCares && !expected.hasDontCares, "control proof needs concrete action rows")
            val in        = insert(summon[BVConstantApi].op(pattern.value.toInt, pattern.width, location).operation)
            val out       = insert(summon[BVConstantApi].op(expected.value.toInt, expected.width, location).operation)
            val selected  = insert(summon[EqApi].op(Seq(input, in), location).operation)
            val equal     = insert(summon[EqApi].op(Seq(output, out), location).operation)
            val different = insert(summon[NotApi].op(equal, location).operation)
            insert(summon[AndApi].op(Seq(selected, different), location).operation)
          val mismatch   = insert(summon[OrApi].op(mismatches, location).operation)
          block.insertOwnedOperationBefore(terminator, summon[AssertApi].op(mismatch, location).operation)
          require(module.getOperation.verify, "invalid control equivalence query")
          feasible -> emitQuery()
        finally module.destroy()
      finally summon[Context].destroy()
    finally arena.close()
