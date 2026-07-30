// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.circtlib.tests

import org.llvm.circt.scalalib.capi.dialect.seq.{DialectApi as SeqDialect, TypeApi as SeqTypeApi, given}
import org.llvm.circt.scalalib.capi.dialect.sim.{DialectApi as SimDialect, TypeApi as SimTypeApi, given}
import org.llvm.circt.scalalib.dialect.sim.operation.{*, given}
import org.llvm.mlir.scalalib.capi.ir.{
  Block,
  Context,
  ContextApi,
  LocationApi,
  OperationApi,
  TypeApi as MlirTypeApi,
  given
}
import utest.*

import java.lang.foreign.Arena

object SimSmoke extends TestSuite:
  private var currentArena:   Arena   = null
  private var currentContext: Context = null

  override def utestBeforeEach(path: Seq[String]): Unit =
    currentArena = Arena.ofConfined()
    currentContext = null

  override def utestAfterEach(path: Seq[String]): Unit =
    val c = currentContext
    val a = currentArena
    currentContext = null
    currentArena = null
    try if c != null then c.destroy()
    finally if a != null then a.close()

  val tests: Tests = Tests:
    test("sim dialect registration and types"):
      given Arena   = currentArena
      val context   = summon[ContextApi].contextCreate
      currentContext = context
      given Context = context

      // Before loading, the dialect's ops are not registered.
      assert(!context.isRegisteredOperation("sim.print"))

      summon[SimDialect].loadDialect

      // After loading, they are, and the dialect's types parse.
      assert(context.isRegisteredOperation("sim.print"))
      assert(context.isRegisteredOperation("sim.triggered"))

      val fstring    = summon[SimTypeApi].formatStringTypeGet
      val fstringOut = StringBuilder()
      fstring.print(fstringOut ++= _)
      assert(fstringOut.toString == "!sim.fstring")

      val stream    = summon[SimTypeApi].outputStreamTypeGet
      val streamOut = StringBuilder()
      stream.print(streamOut ++= _)
      assert(streamOut.toString == "!sim.output_stream")

    test("sim format string ops"):
      given Arena         = currentArena
      val context         = summon[ContextApi].contextCreate
      currentContext = context
      context.allowUnregisteredDialects(true)
      given Context       = context
      summon[SimDialect].loadDialect
      val unknownLocation = summon[LocationApi].locationUnknownGet

      val i32     = 32.integerTypeGet
      val scope   = summon[OperationApi].operationCreate(
        name = "test.scope",
        location = unknownLocation,
        regionBlockTypeLocations = Seq(Seq((Seq(i32), Seq(unknownLocation))))
      )
      given Block = scope.getFirstRegion.getFirstBlock
      val value   = summon[Block].getArgument(0)

      val literal = summon[FormatLiteralApi].op("cycle=", unknownLocation)
      literal.operation.appendToBlock()
      val dec     = summon[FormatDecApi].op(value, true, unknownLocation)
      dec.operation.appendToBlock()
      val hex     = summon[FormatHexApi].op(value, false, unknownLocation)
      hex.operation.appendToBlock()
      val char    = summon[FormatCharApi].op(value, unknownLocation)
      char.operation.appendToBlock()
      val time    = summon[FormatCurrentTimeApi].op(unknownLocation)
      time.operation.appendToBlock()
      val concat  = summon[FormatConcatApi].op(
        Seq(literal.result, dec.result, hex.result, char.result, time.result),
        unknownLocation
      )
      concat.operation.appendToBlock()

      val out         = StringBuilder()
      scope.print(out ++= _)
      val scopeString = out.toString()

      assert(scopeString.contains("sim.fmt.literal \"cycle=\""))
      assert(scopeString.contains("sim.fmt.dec"))
      assert(scopeString.contains("signed"))
      assert(scopeString.contains("sim.fmt.hex"))
      assert(scopeString.contains("isUpper false"))
      assert(scopeString.contains("sim.fmt.char"))
      assert(scopeString.contains("sim.fmt.current_time"))
      assert(scopeString.contains("sim.fmt.concat"))

    test("sim print and stream ops"):
      given Arena         = currentArena
      val context         = summon[ContextApi].contextCreate
      currentContext = context
      context.allowUnregisteredDialects(true)
      given Context       = context
      summon[SimDialect].loadDialect
      summon[SeqDialect].loadDialect
      val unknownLocation = summon[LocationApi].locationUnknownGet

      val clockType = summon[SeqTypeApi].clockTypeGet
      val i1        = 1.integerTypeGet
      val scope     = summon[OperationApi].operationCreate(
        name = "test.scope",
        location = unknownLocation,
        regionBlockTypeLocations = Seq(
          Seq((Seq(clockType, i1), Seq(unknownLocation, unknownLocation)))
        )
      )
      given Block   = scope.getFirstRegion.getFirstBlock
      val clock     = summon[Block].getArgument(0)
      val enable    = summon[Block].getArgument(1)

      val literal = summon[FormatLiteralApi].op("hello\n", unknownLocation)
      literal.operation.appendToBlock()
      val stdout  = summon[StdoutStreamApi].op(unknownLocation)
      stdout.operation.appendToBlock()
      val stderr  = summon[StderrStreamApi].op(unknownLocation)
      stderr.operation.appendToBlock()

      val plainPrint  = summon[PrintFormattedApi]
        .op(literal.result, clock, enable, None, unknownLocation)
      plainPrint.operation.appendToBlock()
      val streamPrint = summon[PrintFormattedApi]
        .op(literal.result, clock, enable, Some(stderr.result), unknownLocation)
      streamPrint.operation.appendToBlock()
      val procPrint   = summon[PrintFormattedProcApi]
        .op(literal.result, Some(stdout.result), unknownLocation)
      procPrint.operation.appendToBlock()

      val out         = StringBuilder()
      scope.print(out ++= _)
      val scopeString = out.toString()

      assert(scopeString.contains("sim.stdout_stream"))
      assert(scopeString.contains("sim.stderr_stream"))
      assert(scopeString.contains("sim.print"))
      assert(scopeString.contains("sim.proc.print"))
      // The stream-less print must not carry a `to` operand.
      assert(scopeString.split('\n').exists(l => l.contains("sim.print") && !l.contains(" to ")))
      assert(scopeString.split('\n').exists(l => l.contains("sim.print") && l.contains(" to ")))

    test("sim simulation control ops"):
      given Arena         = currentArena
      val context         = summon[ContextApi].contextCreate
      currentContext = context
      context.allowUnregisteredDialects(true)
      given Context       = context
      summon[SimDialect].loadDialect
      summon[SeqDialect].loadDialect
      val unknownLocation = summon[LocationApi].locationUnknownGet

      val clockType  = summon[SeqTypeApi].clockTypeGet
      val i1         = 1.integerTypeGet
      val scope      = summon[OperationApi].operationCreate(
        name = "test.scope",
        location = unknownLocation,
        regionBlockTypeLocations = Seq(
          Seq((Seq(clockType, i1), Seq(unknownLocation, unknownLocation)))
        )
      )
      val scopeBlock = scope.getFirstRegion.getFirstBlock
      val clock      = scopeBlock.getArgument(0)
      val done       = scopeBlock.getArgument(1)

      val triggered = {
        given Block = scopeBlock
        val t       = summon[TriggeredApi].op(clock, Some(done), unknownLocation)
        t.operation.appendToBlock()
        t
      }

      // Body of the triggered region: print a literal, then terminate.
      {
        given Block = triggered.block
        val literal = summon[FormatLiteralApi].op("done\n", unknownLocation)
        literal.operation.appendToBlock()
        val print   = summon[PrintFormattedProcApi].op(literal.result, None, unknownLocation)
        print.operation.appendToBlock()
        val term    = summon[TerminateApi].op(true, false, unknownLocation)
        term.operation.appendToBlock()
      }

      {
        given Block      = scopeBlock
        val clockedTerm  = summon[ClockedTerminateApi]
          .op(clock, done, false, true, unknownLocation)
        clockedTerm.operation.appendToBlock()
        val pause        = summon[PauseApi].op(true, unknownLocation)
        pause.operation.appendToBlock()
        val clockedPause = summon[ClockedPauseApi].op(clock, done, false, unknownLocation)
        clockedPause.operation.appendToBlock()
      }

      val out         = StringBuilder()
      scope.print(out ++= _)
      val scopeString = out.toString()

      assert(scopeString.contains("sim.triggered"))
      assert(scopeString.contains("sim.proc.print"))
      assert(scopeString.contains("sim.terminate success, quiet"))
      assert(scopeString.contains("sim.clocked_terminate"))
      assert(scopeString.contains("failure, verbose"))
      assert(scopeString.contains("sim.pause verbose"))
      assert(scopeString.contains("sim.clocked_pause"))
