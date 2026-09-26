// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.clock

import utest.*

object ClockMuxSpec extends TestSuite:
  val tests = Tests:
    test("selection encodes the actual input count and retains a one-bit single-source selector"):
      assert(Seq(1, 2, 3, 4, 5).map(ClockMuxParameter(_, 2, false).selectWidth) == Seq(1, 1, 2, 2, 3))
      intercept[IllegalArgumentException](ClockMuxParameter(0, 2, false))
      intercept[IllegalArgumentException](ClockMuxParameter(-1, 2, false))

    test("synchronization rejects non-positive stages"):
      Seq(-1, 0).foreach: stages =>
        intercept[IllegalArgumentException](ClockMuxParameter(2, stages, false))
