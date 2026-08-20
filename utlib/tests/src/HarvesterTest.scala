// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib.tests

import me.jiuyang.utlib.*

import utest.*

/** The structure harvester on real third-party RTL: the CV32E40X Zc sequencer.
  *
  * Stage 1 (this suite): with no hints, recognize the module's FSM register and its bounded counter register, and
  * classify the counter's update as the increment/clear/hold shape a counter-bound invariant relies on. This is the
  * structural half of harvesting the fact that closes HWMCC's p530.
  */
object HarvesterTest extends TestSuite:
  private val outputRoot = os.Path(sys.props("zaozi.utlib.outDir"), os.pwd)
  private val resources  = os.Path(sys.props("zaozi.utlib.resourceDir")) / "cv32e40x"

  private lazy val sequencerMlir: os.Path =
    val outDir = outputRoot / "harvester"
    os.makeDir.all(outDir)
    val out    = outDir / "sequencer.mlir"
    os.proc(
      "circt-verilog",
      "--ir-hw",
      (resources / "cv32e40x_pkg.sv").toString,
      (resources / "cv32e40x_sequencer.sv").toString,
      "-o",
      out.toString
    ).call()
    out

  val tests: Tests = Tests:
    test("the harvester recognizes the sequencer's FSM and bounded counter"):
      val harvest = Harvester.harvest(sequencerMlir, "cv32e40x_sequencer")

      val counter = harvest.counters
        .find(_.name == "instr_cnt_q")
        .getOrElse(
          throw new java.lang.AssertionError(s"instr_cnt_q not recognized; found ${harvest.counters.map(_.name)}")
        )
      // Its update is exactly the increment/clear/hold shape.
      assert(counter.width == 4)
      assert(counter.increments)
      assert(counter.clears)

      val fsm = harvest.fsms
        .find(_.name == "seq_state_q")
        .getOrElse(
          throw new java.lang.AssertionError(s"seq_state_q not recognized; found ${harvest.fsms.map(_.name)}")
        )
      // Its next value is selected against its own value by equality tests, and it resets to
      // a constant state (S_IDLE = 0).
      assert(fsm.width == 4)
      assert(fsm.resetState.contains(0))
      // The FSM is an open interface: its next-state depends on external signals, now made
      // explicit as controlInputs rather than dropped.
      assert(fsm.controlInputs.nonEmpty)

    test("on the p530 btor2 the harvester locates the nodes and discovers the tail set"):
      val design = Btor2.parse(os.read(resources / "p530.btor2"))
      // The property's own bound is 14 (a_max_seq_len_pop: instr_cnt_q < 14).
      val struct = Harvester.locateBtor2(design, bound = 14)

      // The FSM is dispatched against the eight seq_state_e values; the counter feeds the
      // `< 14` comparison. Register names are gone from the flattened btor2 — these are found
      // purely by the comparisons they feed.
      assert(struct.fsmStates == Set(1, 2, 3, 4, 5, 6, 7))
      assert(struct.counterNode != struct.fsmNode)

      // Discover, with no hints, which states admit a counter of 14: the popret(z) tail
      // states S_A0 = 6 and S_RET = 7, but not the plain-POP state S_POP = 2. The counter
      // first reaches 14 at depth 35, so the sieve bound must clear that. Probing a
      // representative subset keeps the test tractable; the exhaustive eight-state sieve is
      // in docs/superpowers/research/2026-08-13-cv32e40x-hard-case.md.
      val tail = Harvester.sieveTailSet(design, struct, threshold = 14, kmax = 40, candidates = Set(2, 6, 7))
      assert(tail == Set(6, 7))

      // The discovered invariant `counter >= 14 -> fsm in {6,7}` is certified by BMC-as-bad:
      // its negation is unreachable through depth 35.
      Harvester.validateTailSet(design, struct, threshold = 14, tail = tail, kmax = 35) match
        case _: Btor2Result.UnreachableWithin | _: Btor2Result.Proven => ()
        case other                                                    => throw new java.lang.AssertionError(s"invariant not certified: $other")

    test("emitting the harvested invariant in the checker's sampled frame closes p530"):
      val design = Btor2.parse(os.read(resources / "p530.btor2"))
      val struct = Harvester.locateBtor2(design, bound = 14)

      // The full automatic chain: the discovered tail set, emitted one frame delayed as the
      // SVA checker samples it, closes the property no HWMCC solver cracked in two years.
      val closed = Harvester.closeSampled(design, struct, tail = Set(6, 7))
      Btor2.check(closed, kmax = 20, kind = true) match
        case Btor2Result.Proven(k) => assert(k <= 2)
        case other                 => throw new java.lang.AssertionError(s"p530 did not close: $other")
