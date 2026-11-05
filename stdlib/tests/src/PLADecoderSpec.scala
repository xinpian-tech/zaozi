// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.stdlib

import me.jiuyang.decoder.*
import me.jiuyang.stdlib.default.{*, given}
import utest.*

object PLADecoderSpec extends TestSuite:
  private def decoder(input: String): DecoderParameter =
    DecoderParameter("D", Seq(TruthTable(s"[t][n][y:1,n:0][$input:y]")))

  val tests = Tests:
    test("equal tables name the same module"):
      assert(PLADecoder.moduleName(decoder("000|111")) == PLADecoder.moduleName(decoder("000|111")))

    test("tables matching different values name different modules"):
      assert(PLADecoder.moduleName(decoder("000|111")) != PLADecoder.moduleName(decoder("000|001|111")))
