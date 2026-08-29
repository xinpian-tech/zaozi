// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.utlib

import utest.*

/** State promotion: an imported design's *internal* signal becomes an output port, so a typed intent can name design
  * state the way it names any other port. A name that does not exist fails loudly, listing candidates — never a
  * silent no-op in the solver.
  */
object SvImportTest extends TestSuite:
  private val outputRoot = os.Path(sys.props("zaozi.utlib.outDir"), os.pwd)

  private val imported =
    """module {
      |  hw.module @dut(in %clk : !seq.clock, in %a : i8, out result : i8) {
      |    %c1_i3 = hw.constant 1 : i3
      |    %counter = seq.firreg %next clock %clk : i3
      |    %next = comb.add %counter, %c1_i3 : i3
      |    %acc = seq.firreg %a clock %clk : i8
      |    hw.output %acc : i8
      |  }
      |}
      |""".stripMargin

  private def write(name: String): os.Path =
    val dir = outputRoot / s"SvImport-$name"
    os.remove.all(dir)
    os.makeDir.all(dir)
    val f = dir / "imported.mlir"
    os.write.over(f, imported)
    f

  val tests: Tests = Tests:
    test("an internal register is promoted to an output port of the right width"):
      val out = os.read(SvImport.promoteState(write("promote"), Seq(StateSignal("counter", 3))))
      assert(out.contains("out counter : i3"))
      assert(out.contains("hw.output %acc, %counter : i8, i3"))

    test("several signals promote in the order given"):
      val out = os.read(SvImport.promoteState(write("multi"), Seq(StateSignal("counter", 3), StateSignal("acc", 8))))
      assert(out.contains("out counter : i3, out acc : i8"))
      assert(out.contains("hw.output %acc, %counter, %acc : i8, i3, i8"))

    test("a signal that does not exist fails loudly, naming candidates"):
      val e = intercept[IllegalArgumentException](SvImport.promoteState(write("missing"), Seq(StateSignal("nope", 3))))
      assert(e.getMessage.contains("nope"))
      // the message must help the caller (and the repair loop) pick a real one
      assert(e.getMessage.contains("counter") && e.getMessage.contains("acc"))

    test("a width mismatch is rejected rather than silently promoted"):
      val e = intercept[IllegalArgumentException](SvImport.promoteState(write("width"), Seq(StateSignal("counter", 8))))
      assert(e.getMessage.contains("counter"))
      assert(e.getMessage.contains("i3"))

    test("promoting nothing leaves the module untouched"):
      val f = write("empty")
      assert(SvImport.promoteState(f, Seq.empty) == f)
