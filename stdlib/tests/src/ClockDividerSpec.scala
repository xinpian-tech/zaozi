// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.clock

import utest.*

object ClockDividerSpec extends TestSuite:
  val tests = Tests:
    test("zero normalizes to divide by one within the encoded width"):
      assert(ClockDividerParameter(1, 0, false, false).initialDivisor == 1)
      assert(ClockDividerParameter(4, 15, false, false).initialDivisor == 15)
      intercept[IllegalArgumentException](ClockDividerParameter(0, 0, false, false))
      intercept[IllegalArgumentException](ClockDividerParameter(4, -1, false, false))
      intercept[IllegalArgumentException](ClockDividerParameter(4, 16, false, false))

    test("initial divisors retain precision above machine integer widths"):
      val initial = (BigInt(1) << 80) | 3
      val p       = ClockDividerParameter(81, initial, true, false)
      assert(p.initialDivisor == initial)
      assert(upickle.default.read[ClockDividerParameter](upickle.default.write(p)) == p)
      intercept[IllegalArgumentException](p.copy(width = 80))
