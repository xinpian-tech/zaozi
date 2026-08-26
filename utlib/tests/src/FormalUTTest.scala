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

    test("delayInputs models the DPI drive register: one pinned flop between the port and its uses"):
      val hw = writeModule(
        outputRoot / "FormalUT-delay",
        "top",
        """module {
          |  hw.module @top(in %clock : !seq.clock, in %reset : i1, in %A : i8, out X : i8) {
          |    %c1_i8 = hw.constant 1 : i8
          |    %0 = comb.add %A, %c1_i8 : i8
          |    hw.output %0 : i8
          |  }
          |}
          |""".stripMargin
      )
      val out = os.read(FormalUT.delayInputs(hw, "top", Seq("A")))
      assert(out.contains("%A__d = seq.compreg %A, %clock initial %A__d__init : i8"))
      assert(out.contains("comb.add %A__d, %c1_i8 : i8"))
      // the port declaration itself keeps the original name
      assert(out.contains("in %A : i8"))

    test("pruneForBmc strips the probe/layer artifacts circt-bmc cannot ingest"):
      // The shape firtool -ir-hw emits for a zaozi UT with a layered probe: ref macros, hierpaths, the bound
      // *_Verification module full of sv.xmr, its {doNotPrint} bind instance, emit.file blocks, and namehints.
      val dirty =
        """module {
          |  hw.hierpath private @nla [@Top::@sym]
          |  sv.macro.decl @ref_Top_A
          |  hw.hierpath private @xmrPath [@Top::@verification, @Top_Verification::@sym_0]
          |  emit.file "ref_Top.sv" {
          |    sv.macro.def @ref_Top_A "{{0}}"([@xmrPath])
          |  }
          |  hw.module private @Top_Verification() {
          |    %0 = sv.xmr.ref @xmrPath : !hw.inout<i8>
          |    hw.output
          |  }
          |  om.class @Top_Class(%basepath: !om.basepath) {
          |    %0 = om.basepath_create %basepath @nla
          |    om.class.fields
          |  }
          |  hw.module @Top(in %clk : !seq.clock, in %rst : i1, in %A : i8) {
          |    %true = hw.constant true
          |    %c0_i8 = hw.constant 0 : i8
          |    %r = seq.firreg %A clock %clk sym @sym_5 reset sync %rst, %c0_i8 {clockEdge = 0 : i32, resetPolarity = 0 : i32} : i8
          |    %w_layerCapture = hw.wire %true sym @sym_9  : i1
          |    %0 = comb.extract %A from 0 {sv.namehint = "_GEN_1"} : (i8) -> i1
          |    %1 = comb.xor bin %0, %true {sv.namehint = "_GEN_2"} : i1
          |    verif.assert %1 label "gen_a_odd" : i1
          |    hw.instance "verification" sym @verification @Top_Verification() -> () {doNotPrint}
          |    hw.output
          |  }
          |}
          |""".stripMargin
      val dir     = outputRoot / "FormalUT-prune"
      os.remove.all(dir)
      os.makeDir.all(dir)
      os.write.over(dir / "dirty.mlir", dirty)
      val pruned  = os.read(FormalUT.pruneForBmc(dir / "dirty.mlir"))
      assert(pruned.contains("verif.assert %1 label \"gen_a_odd\""))
      assert(pruned.contains("hw.module @Top(in %clk : !seq.clock, in %rst : i1, in %A : i8)"))
      assert(!pruned.contains("sv."))
      assert(!pruned.contains("emit.file"))
      assert(!pruned.contains("hw.hierpath"))
      assert(!pruned.contains("Top_Verification"))
      assert(!pruned.contains("doNotPrint"))
      assert(!pruned.contains("om.class"))
      // The wire itself stays (its value may feed logic); only the now-danging inner sym goes.
      assert(pruned.contains("%w_layerCapture = hw.wire %true : i1"))
      // A sync-reset firreg becomes a compreg whose initial value is pinned to the reset constant — otherwise
      // circt-bmc leaves the initial state free and a witness can carry fake history.
      assert(!pruned.contains("seq.firreg"))
      assert(pruned.contains("%r = seq.compreg %r__mux, %clk initial %r__init : i8"))
      assert(pruned.contains("%r__mux = comb.mux %rst, %c0_i8, %A : i8"))
      assert(pruned.contains("seq.initial()"))
