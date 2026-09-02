// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.circt

import org.llvm.mlir.scalalib.capi.diagnostic.{given_DiagnosticApi, Diagnostic}
import org.llvm.mlir.scalalib.capi.ir.{given_ContextApi, given_LocationApi, Context, Operation}
import org.llvm.mlir.scalalib.capi.pass.{given_PassManagerApi, PassManager}
import org.llvm.mlir.scalalib.capi.support.given_LogicalResultApi

import java.lang.foreign.Arena

/** A failing MLIR pass says why through the context's diagnostic handler, not through its return value: without one
  * attached, `runOnOp` reports nothing but "failed". Catch what it emits and put it in the exception.
  *
  * These are plain MLIR helpers — the same ones zaozi keeps in `me.jiuyang.zaozi.default.Diagnostics`. They are
  * repeated here rather than imported so that the Elaborate phase depends on CIRCT alone; which generator backend
  * enacts a module is the backend's business, not this phase's.
  */
object Diagnostics:

  def withCapturedDiagnostics[A](
    body: => A
  )(
    using Context,
    Arena
  ): (A, String) =
    val sink      = new StringBuilder
    val handlerId = summon[Context].attachDiagnosticHandler { diagnostic =>
      diagnostic.getLocation.print(sink ++= _)
      sink ++= ": "
      diagnostic.print(sink ++= _)
      sink ++= "\n"
      false
    }
    try (body, sink.toString)
    finally summon[Context].detachDiagnosticHandler(handlerId)

  extension (passManager: PassManager)
    /** Run the pass manager, and fail the elaboration with whatever MLIR had to say about it. */
    def runOnOpOrThrow(
      operation: Operation,
      what:      String
    )(
      using Context,
      Arena
    ): Unit =
      val (result, captured) = withCapturedDiagnostics(passManager.runOnOp(operation))
      if result.failed then
        val detail = captured.trim
        throw ElaborationException(
          s"$what failed:\n" +
            (if detail.isEmpty then "(no MLIR diagnostics captured; inspect stderr for the cause)" else detail)
        )
