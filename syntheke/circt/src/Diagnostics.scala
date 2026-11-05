package me.jiuyang.syntheke.circt

import org.llvm.mlir.scalalib.capi.diagnostic.given_DiagnosticApi
import org.llvm.mlir.scalalib.capi.ir.{given_ContextApi, given_LocationApi, Context, Operation}
import org.llvm.mlir.scalalib.capi.pass.{given_PassManagerApi, PassManager}
import org.llvm.mlir.scalalib.capi.support.given_LogicalResultApi

import java.lang.foreign.Arena

private[circt] object Diagnostics:

  private def withCapturedDiagnostics[A](
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
