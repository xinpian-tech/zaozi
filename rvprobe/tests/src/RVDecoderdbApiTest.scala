// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.tests

import me.jiuyang.rvprobe.*

import org.chipsalliance.rvdecoderdb.*
import os.Path

import utest.*

import java.lang.foreign.Arena

// set the instruction set
object RVDecoderdbApiTest extends TestSuite:
  val tests = Tests:
    test("import rvdecoderdb success"):
      val riscvOpcodesPath: Path                  = Path(
        sys.env.getOrElse(
          "RISCV_OPCODES_INSTALL_PATH",
          throw new RuntimeException("Environment variable RISCV_OPCODES_INSTALL_PATH not set")
        )
      )
      val instTable:        Iterable[Instruction] = instructions(riscvOpcodesPath)
      // instTable.foreach(println)
      instTable.nonEmpty ==> true
    test("getInstructions() returns non-empty list"):
      val instructions = getInstructions()
      instructions.nonEmpty ==> true
      // println(instructions.map(_.name).mkString(", "))
    test("getArgLut() returns non-empty list"):
      val argLut = getArgLut()
      argLut.nonEmpty ==> true
      // println(argLut.map(_._1).mkString(", "))
    test("getExtensions() returns non-empty list"):
      val extensions = getExtensions()
      extensions.nonEmpty ==> true
      // println(extensions.mkString(", "))
