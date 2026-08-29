// SPDX-License-Identifier: Apache-2.0
package me.jiuyang.stdlib

import utest.*

object UtilSpec extends TestSuite:
  val tests = Tests:
    test("log2Ceil"):
      assert(log2Ceil(1) == 0)
      assert(log2Ceil(2) == 1)
      assert(log2Ceil(3) == 2)
      assert(log2Ceil(4) == 2)
      assert(log2Ceil(5) == 3)
      assert(log2Ceil(1024) == 10)
      assert(log2Ceil(BigInt(1) << 100) == 100)
      intercept[IllegalArgumentException](log2Ceil(0))

    test("log2Floor"):
      assert(log2Floor(1) == 0)
      assert(log2Floor(2) == 1)
      assert(log2Floor(3) == 1)
      assert(log2Floor(4) == 2)
      assert(log2Floor(5) == 2)
      assert(log2Floor(1024) == 10)
      intercept[IllegalArgumentException](log2Floor(0))

    test("isPow2"):
      assert(isPow2(1))
      assert(isPow2(2))
      assert(!isPow2(3))
      assert(isPow2(4))
      assert(!isPow2(0))
      assert(!isPow2(-4))
      assert(isPow2(BigInt(1) << 100))
