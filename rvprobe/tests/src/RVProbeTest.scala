// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.tests

import me.jiuyang.rvprobe.RVGenerator

import utest.*

trait HasRVProbeTest:
  self: RVGenerator =>

  def rvprobeTest(str: String)(predicate: String => Boolean): Unit = assert(predicate(str))

  def rvprobeTestOpcodeSMTLIB(checkLines: String*): Unit =
    rvprobeTest(toOpcodeSMTLIB())(out => checkLines.forall(l => out.contains(l)))

  def rvprobeTestOpcodeMLIR(checkLines: String*): Unit =
    rvprobeTest(toOpcodeMLIR())(out => checkLines.forall(l => out.contains(l)))

  def rvprobeTestOpcodeZ3Output(checkLines: String*): Unit =
    rvprobeTest(toOpcodeZ3Output())(out => checkLines.forall(l => out.contains(l)))

  def rvprobeTestArgSMTLIB(checkLines: String*): Unit =
    rvprobeTest(toArgSMTLIB())(out => checkLines.forall(l => out.contains(l)))

  def rvprobeTestArgMLIR(checkLines: String*): Unit =
    rvprobeTest(toArgMLIR())(out => checkLines.forall(l => out.contains(l)))

  def rvprobeTestArgZ3Output(checkLines: String*): Unit =
    rvprobeTest(toArgZ3Output())(out => checkLines.forall(l => out.contains(l)))

  def rvprobeTestInstructions(expected: String*): Unit =
    val instructions = toInstructions().map(_._2).mkString("\n")
    rvprobeTest(instructions)(out => expected.forall(l => out.contains(l)))
