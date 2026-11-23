// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozitest

import org.llvm.mlir.scalalib.Mlir
import scala.scalanative.unsafe.Zone
import utest.*

object ContextLifetimeSpec extends TestSuite:
  val tests = Tests:
    test("the context scope closes its zone after normal return"):
      val zone = Mlir.withContext { summon[Zone] }
      assert(zone.isClosed)

    test("the context scope releases allocations when elaboration throws"):
      var zone: Zone = null
      val error  = new RuntimeException("test failure")
      val thrown = intercept[RuntimeException] {
        Mlir.withContext {
          zone = summon[Zone]
          throw error
        }
      }
      assert(thrown eq error, zone.isClosed)
