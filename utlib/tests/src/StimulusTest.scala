// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.utlib

import utest.*

/** The Trace → Model B `stimulus.txt` bridge: a [[FormalUT]] witness becomes the per-cycle drive file the generated
  * tick callback replays. Values render as the callback reads them (`%ld`): signed ports in two's-complement decimal,
  * unsigned ports raw.
  */
object StimulusTest extends TestSuite:
  private val outputRoot = os.Path(sys.props("zaozi.utlib.outDir"), os.pwd)

  private def spec(signed: Boolean, width: Int = 8) = AbiSpec(
    dut = "AbsValUT_width8",
    ports = Seq(
      AbiPort("clk", AbiRole.Clock, 1, false),
      AbiPort("A", AbiRole.Drive, width, signed),
      AbiPort("ABSVAL", AbiRole.Probe, width, signed)
    ),
    abiVersion = "1.0"
  )

  val tests: Tests = Tests:
    test("an unsigned drive port renders raw values, one line per cycle"):
      val t = Trace(3, Map("A" -> Vector(BigInt(5), BigInt(0), BigInt(243))))
      assert(Stimulus.fromTrace(t, spec(signed = false)) == "5\n0\n243\n")

    test("a signed drive port renders two's-complement decimal (0xF3 at width 8 is -13)"):
      val t = Trace(2, Map("A" -> Vector(BigInt(5), BigInt(243))))
      assert(Stimulus.fromTrace(t, spec(signed = true)) == "5\n-13\n")

    test("the drive signal is found case-insensitively when the exact name is absent"):
      val t = Trace(1, Map("a" -> Vector(BigInt(7))))
      assert(Stimulus.fromTrace(t, spec(signed = false)) == "7\n")

    test("a delayed drive is read from its <name>__d_next trace signal"):
      val t = Trace(2, Map("A__d_next" -> Vector(BigInt(3), BigInt(5)), "A__d_state" -> Vector(BigInt(0), BigInt(3))))
      assert(Stimulus.fromTrace(t, spec(signed = false)) == "3\n5\n")

    test("a trace missing the drive signal fails with the port name in the message"):
      val t = Trace(1, Map("other" -> Vector(BigInt(1))))
      val e = intercept[IllegalArgumentException](Stimulus.fromTrace(t, spec(signed = false)))
      assert(e.getMessage.contains("A"))

    test("a spec without exactly one drive port is rejected"):
      val s = spec(signed = false).copy(ports = Seq(AbiPort("clk", AbiRole.Clock, 1, false)))
      intercept[IllegalArgumentException](Stimulus.fromTrace(Trace(1, Map.empty), s))

    test("save writes the file and returns its path"):
      val dir = outputRoot / "Stimulus-save"
      os.remove.all(dir)
      val t   = Trace(2, Map("A" -> Vector(BigInt(1), BigInt(2))))
      val p   = Stimulus.save(t, spec(signed = false), dir / "stimulus.txt")
      assert(os.read(p) == "1\n2\n")
