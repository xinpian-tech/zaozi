// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.utlib

import utest.*

/** The model→stimulus interface: [[TraceBinding]] translates solver names back to ABI names, [[AbstractStimulus]]
  * carries the witness purely in ABI terms, and [[ModelBStimulus]] is one codec over it — signed ports render as
  * two's-complement decimal (the tick callback reads `%ld`), unsigned raw.
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

    test("TraceBinding.rebind translates a delayed drive's register signal back to its ABI name"):
      val binding = TraceBinding.delayedDrives(Seq("A"))
      val raw     = Trace(2, Map("A__d_next" -> Vector(BigInt(3), BigInt(5)), "A__d_state" -> Vector(BigInt(0), BigInt(3))))
      val rebound = binding.rebind(raw)
      assert(rebound.values("A") == Vector(BigInt(3), BigInt(5)))
      assert(Stimulus.fromTrace(rebound, spec(signed = false)) == "3\n5\n")

    test("AbstractStimulus carries every drive port per beat — the interface is not single-drive"):
      val two = AbiSpec(
        "multi",
        Seq(AbiPort("a", AbiRole.Drive, 8, false), AbiPort("b", AbiRole.Drive, 8, false)),
        "1.0"
      )
      val t   = Trace(2, Map("a" -> Vector(BigInt(1), BigInt(2)), "b" -> Vector(BigInt(7), BigInt(9))))
      val s   = AbstractStimulus.fromTrace(t, two)
      assert(s.cycles == 2)
      assert(s.beats(1) == Beat(Map("a" -> BigInt(2), "b" -> BigInt(9))))
      // ...but the Model B codec enforces its own single-drive limit.
      intercept[IllegalArgumentException](ModelBStimulus.render(s))

    test("trimAfterStrobe drops the unconstrained tail but keeps the pipeline's drain cycles"):
      // A witness runs to the full bound, so the beats after the property is satisfied are whatever the
      // solver found cheapest.  Replaying them is padding at best; on i2c the solver used them to
      // re-assert reset one cycle after starting a transfer.
      val s      = spec(signed = false)
      val beats  = Vector(1, 1, 0, 0, 0, 0, 0, 0).map(v => Beat(Map("A" -> BigInt(v))))
      val full   = AbstractStimulus(s, beats)
      assert(full.trimAfterStrobe(drain = 0)("A").cycles == 2)
      // The drain must survive the trim: cutting at the strobe would launch an operation the replay
      // never gives the DUT time to finish.
      assert(full.trimAfterStrobe(drain = 3)("A").cycles == 5)
      // A drain past the end clamps rather than throwing.
      assert(full.trimAfterStrobe(drain = 99)("A").cycles == 8)

    test("trimAfterStrobe keeps an all-quiet witness whole rather than truncating it to nothing"):
      val s = spec(signed = false)
      val quiet = AbstractStimulus(s, Vector.fill(4)(Beat(Map("A" -> BigInt(0)))))
      assert(quiet.trimAfterStrobe(drain = 0)("A").cycles == 4)

    test("trimAfterStrobe names a port that is not in the ABI"):
      val s = spec(signed = false)
      val one = AbstractStimulus(s, Vector(Beat(Map("A" -> BigInt(1)))))
      val err = intercept[IllegalArgumentException](one.trimAfterStrobe(drain = 0)("nope"))
      assert(err.getMessage.contains("nope"))

    test("a trace missing the drive signal fails with the port name in the message"):
      val t = Trace(1, Map("other" -> Vector(BigInt(1))))
      val e = intercept[IllegalArgumentException](Stimulus.fromTrace(t, spec(signed = false)))
      assert(e.getMessage.contains("A"))

    test("save writes the file and returns its path"):
      val dir = outputRoot / "Stimulus-save"
      os.remove.all(dir)
      val t   = Trace(2, Map("A" -> Vector(BigInt(1), BigInt(2))))
      val p   = Stimulus.save(t, spec(signed = false), dir / "stimulus.txt")
      assert(os.read(p) == "1\n2\n")
