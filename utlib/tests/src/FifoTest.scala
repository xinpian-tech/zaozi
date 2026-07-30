// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib.tests

import me.jiuyang.utlib.*
import utest.*

object FifoTest extends TestSuite:
  val tests: Tests = Tests:
    test("the FIFO elaborates to synthesizable Verilog"):
      val verilog = Fifo.verilogString(FifoParameter(8))
      // Real RTL, not a vendor blackbox instantiation.
      assert(verilog.contains("module"))
      assert(verilog.contains("always"))
      assert(!verilog.contains("DW_fifo"))
      assert(verilog.contains("enq_ready"))
      assert(verilog.contains("deq_valid"))
      assert(verilog.contains("empty"))
      assert(verilog.contains("full"))
