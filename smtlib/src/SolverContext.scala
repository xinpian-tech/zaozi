// SPDX-License-Identifier: Apache-2.0
package me.jiuyang.smtlib

/** Naming state shared by declarations in one solver body. */
final class SolverContext:
  private var anonymousCounter = 0

  private[smtlib] def nextAnonymousName(): String =
    val index = anonymousCounter
    anonymousCounter += 1
    s"_GEN_${index}"
