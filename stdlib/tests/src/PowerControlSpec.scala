// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.power

import utest.*

object PowerControlSpec extends TestSuite:
  val tests = Tests:
    test("delay counter holds the largest loaded value"):
      assert(PowerControlParameter(true, 0, 0, 0).counterWidth == 1)
      assert(PowerControlParameter(true, 1, 2, 1).counterWidth == 1)
      assert(PowerControlParameter(true, 2, 3, 8).counterWidth == 3)
      assert(PowerControlParameter(false, BigInt(1) << 80, 0, 0).counterWidth == 80)
    test("negative delays are rejected"):
      intercept[IllegalArgumentException](PowerControlParameter(true, -1, 0, 0))
      intercept[IllegalArgumentException](PowerControlParameter(true, 0, -1, 0))
      intercept[IllegalArgumentException](PowerControlParameter(true, 0, 0, -1))
