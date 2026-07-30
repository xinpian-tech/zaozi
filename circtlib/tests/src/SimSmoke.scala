// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.circtlib.tests

import org.llvm.circt.scalalib.capi.dialect.sim.{DialectApi as SimDialect, TypeApi as SimTypeApi, given}
import org.llvm.mlir.scalalib.capi.ir.{Context, ContextApi, given}
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
