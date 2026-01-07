// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.tests

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.constraints.*

import utest.*

object PaperTest extends TestSuite:
  val tests = Tests:
    object test extends RVGenerator with HasRVProbeTest:
      val sets          = Seq(isRV64I(), isRVI())
      def constraints() =
        instruction(0, isAddw()) {
          rdRange(1, 5)
        }

    // test.printMLIROpcodes()
    // test.printSMTLIBOpcodes()
    val solvedOpcodes = test.solveOpcodes()
    // println(solvedOpcodes)
    test.initialize()
    // val args = test.printSMTLIBArgs(solvedOpcodes)
    // println(args)
    val solvedArgs = test.solveArgs(solvedOpcodes)
    // println(solvedArgs)
    val insts = test.assembleInstructions(solvedOpcodes, solvedArgs)
    println(insts)
    // test.printSMTLIB()
    // test.printInstructions()
