// SPDX-License-Identifier: Apache-2.0
package me.jiuyang.smtlib

/** Reusable solver configuration. Each invocation creates fresh naming state while sharing the debug callback and
  * process-wide native library configuration.
  */
final class SolverContext(val debug: Option[String => Unit] = None):
  private var anonymousCounter = 0

  private[smtlib] def sharedLibPaths: Seq[String] = SolverContext.sharedLibPaths

  private[smtlib] def nextAnonymousName(): String =
    val index = anonymousCounter
    anonymousCounter += 1
    s"_GEN_${index}"

object SolverContext:
  given default: SolverContext = new SolverContext

  // Resolve once, on the first native execution; constructing IR does not load Z3.
  private lazy val sharedLibPaths: Seq[String] = Seq(sys.env("Z3_LIB"))
