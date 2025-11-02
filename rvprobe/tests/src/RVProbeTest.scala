// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.tests

import me.jiuyang.rvprobe.RVGenerator

import utest.*

trait HasRVProbeTest:
  self: RVGenerator =>

  def rvprobeTest(str: String)(predicate: String => Boolean): Unit = assert(predicate(str))

  def rvprobeTestSMTLIB(checkLines: String*): Unit =
    rvprobeTest(toSMTLIB())(out => checkLines.forall(l => out.contains(l)))

  def rvprobeTestMLIR(checkLines: String*): Unit = rvprobeTest(toMLIR())(out => checkLines.forall(l => out.contains(l)))

  def rvprobeTestZ3Output(checkLines: String*): Unit =
    rvprobeTest(toZ3Output())(out => checkLines.forall(l => out.contains(l)))

  def rvprobeTestInstructions(expected: String*): Unit =
    val instructions = toInstructions().map(_._2).mkString("\n")
    rvprobeTest(instructions)(out => expected.forall(l => out.contains(l)))
