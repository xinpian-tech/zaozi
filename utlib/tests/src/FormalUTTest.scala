// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.utlib

import utest.*

/** The circt-bmc formal-UT engine: `check` proves a module's assertions (Pass / Fail(counterexample)) and `generate`
  * reads the same run as a witness (assert `¬constraint` → the counterexample is the transaction). One clocked DUT
  * exercises the multi-cycle trace.
  */
object FormalUTTest extends TestSuite:
  private val outputRoot = os.Path(sys.props("zaozi.utlib.outDir"), os.pwd)

  private def writeModule(dir: os.Path, name: String, body: String): os.Path =
    os.remove.all(dir)
    os.makeDir.all(dir)
    val f = dir / s"$name.mlir"
    os.write.over(f, body)
    f

  val tests: Tests = Tests:
    test("check: an assertion that always holds proves to Pass"):
      val hw = writeModule(
        outputRoot / "FormalUT-pass",
        "hold",
        """hw.module @hold(in %clk : !seq.clock, in %a : i8) {
          |  %t = hw.constant true
          |  verif.assert %t : i1
          |}
          |""".stripMargin
      )
      assert(FormalUT.check(hw, top = "hold", bound = 1) == CheckOutcome.Pass)

    test("check: a violable assertion yields Fail with a counterexample"):
      val hw = writeModule(
        outputRoot / "FormalUT-fail",
        "gen",
        """hw.module @gen(in %clk : !seq.clock, in %a : i8) {
          |  %c5 = hw.constant 5 : i8
          |  %eq = comb.icmp eq %a, %c5 : i8
          |  %true = hw.constant true
          |  %ne = comb.xor %eq, %true : i1
          |  verif.assert %ne : i1
          |}
          |""".stripMargin
      )
      FormalUT.check(hw, top = "gen", bound = 1) match
        case CheckOutcome.Fail(cex) => assert(cex.values("a").contains(BigInt(5)))
        case other                  => assert(false)

    test("generate: the dual reading — a module asserting ¬(a==5) yields a==5"):
      val hw = writeModule(
        outputRoot / "FormalUT-generate",
        "gen",
        """hw.module @gen(in %clk : !seq.clock, in %a : i8) {
          |  %c5 = hw.constant 5 : i8
          |  %eq = comb.icmp eq %a, %c5 : i8
          |  %true = hw.constant true
          |  %ne = comb.xor %eq, %true : i1
          |  verif.assert %ne : i1
          |}
          |""".stripMargin
      )
      FormalUT.generate(hw, top = "gen", bound = 1) match
        case GenerateOutcome.Generated(txn) => assert(txn.values("a").contains(BigInt(5)))
        case other                          => assert(false)

    test("generate: a clean multi-cycle transaction from a clocked DUT"):
      // reg pgo = prev(go); asserts ¬(pgo && a == 5) — "go was high last cycle and a == 5 now".
      val hw = writeModule(
        outputRoot / "FormalUT-multicycle",
        "gen3",
        """hw.module @gen3(in %clk : !seq.clock, in %a : i8, in %go : i1) {
          |  %pgo = seq.compreg %go, %clk : i1
          |  %c5 = hw.constant 5 : i8
          |  %a5 = comb.icmp eq %a, %c5 : i8
          |  %both = comb.and %pgo, %a5 : i1
          |  %true = hw.constant true
          |  %nb = comb.xor %both, %true : i1
          |  verif.assert %nb : i1
          |}
          |""".stripMargin
      )
      FormalUT.generate(hw, top = "gen3", bound = 3) match
        case GenerateOutcome.Generated(txn) =>
          assert(txn.cycles >= 1)
          assert(txn.values("a").contains(BigInt(5)))
        case other                          => assert(false)

    test("parseTrace takes the deepest counterexample block"):
      val trace =
        """counterexample for gen3:
          |cycle 0:
          |  a = 0x5
          |counterexample for gen3:
          |cycle 0:
          |  a = 0x0
          |cycle 1:
          |  a = 0x5
          |""".stripMargin
      FormalUT.parseTrace(trace) match
        case FormalUT.Bmc.Violated(t) => assert(t.cycles == 2 && t.values("a") == Vector(BigInt(0), BigInt(5)))
        case other                    => assert(false)
