// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.circtlib.tests

import org.llvm.circt.scalalib.capi.dialect.llhd.{DialectApi as LLHDDialectApi, given}
import org.llvm.circt.scalalib.dialect.llhd.operation.{*, given}
import org.llvm.mlir.scalalib.capi.ir.{Block, BlockApi, Context, ContextApi, LocationApi, OperationApi, TypeApi, given}
import utest.*

import java.lang.foreign.Arena

object LLHDSmoke extends TestSuite:
  val tests: Tests = Tests:
    test("LLHD operations"):
      given Arena = Arena.ofConfined()
      try
        given Context = summon[ContextApi].contextCreate
        try
          summon[Context].allowUnregisteredDialects(true)
          summon[LLHDDialectApi].loadDialect
          val location = summon[LocationApi].locationUnknownGet
          val i1       = 1.integerTypeGet
          val scope    = summon[OperationApi].operationCreate(
            name = "test.scope",
            location = location,
            regionBlockTypeLocations = Seq(Seq((Seq(i1), Seq(location))))
          )
          given Block  = scope.getFirstRegion.getFirstBlock
          val initial  = summon[Block].getArgument(0)

          val time    = summon[ConstantTimeApi].op("ns", 5, location)
          time.operation.appendToBlock()
          val signal  = summon[SignalApi].op(initial, Some("clock"), location)
          signal.operation.appendToBlock()
          val probe   = summon[ProbeApi].op(signal.result, i1, location)
          probe.operation.appendToBlock()
          val process = summon[ProcessApi].op(location)
          process.operation.appendToBlock()

          val destination = summon[BlockApi].blockCreate(Seq.empty, Seq.empty)
          process.operation.getFirstRegion.appendOwnedBlock(destination)
          {
            given Block = process.body
            val drive   = summon[DriveApi].op(signal.result, probe.result, time.result, None, location)
            drive.operation.appendToBlock()
            val wait    = summon[WaitApi].op(Some(time.result), Seq.empty, destination, location)
            wait.operation.appendToBlock()
          }
          {
            given Block = destination
            val halt    = summon[HaltApi].op(location)
            halt.operation.appendToBlock()
          }

          val out     = StringBuilder()
          scope.print(out ++= _)
          val printed = out.toString

          assert(printed.contains("llhd.constant_time"))
          assert(printed.contains("llhd.sig"))
          assert(printed.contains("llhd.prb"))
          assert(printed.contains("llhd.process"))
          assert(printed.contains("llhd.drv"))
          assert(printed.contains("llhd.wait"))
          assert(printed.contains("llhd.halt"))

        finally summon[Context].destroy()
      finally summon[Arena].close()
