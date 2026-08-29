// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.stdlib

import me.jiuyang.utlib.*

import utest.*

/** rvprobe stimulus dropped into someone else's testbench.
  *
  * A template-generated UVM testbench is fixed apart from its sequences, so a solved witness only has to replace the
  * sequence layer: one intent per ALU opcode, each solved through the imported RTL, concatenated into a single UVM
  * sequence of concrete transactions whose fields are named to match HAVEN's own `seq_item`. The emitted file is a
  * drop-in for the `sequence_*.sv` of the HAVEN ALU testbench.
  */
object HavenAluUvmSeqTest extends TestSuite:
  import FormalGenHarness.*

  val tests: Tests = Tests:
    test("one intent per opcode, concatenated into a drop-in UVM sequence"):
      val dir    = freshDir("HavenAlu-uvmseq")
      val svFile = resources / "haven" / "alu_top.v"
      val ip     = SvImport.toHw(Seq(svFile), dir / "imported")

      // Solve each opcode separately; the parameter names the target, so each is its own elaboration.
      val opcodes = Seq(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
      val solved  = opcodes.flatMap { op =>
        val param  = HavenAluOpParameter(op)
        val model  = FormalUT.lowerGenerator(HavenAluOpUT, param, dir / s"op$op")
        val merged = SvImport.mergeForBmc(model.hw, ip)
        FormalUT.generate(model.copy(hw = merged), bound = 2) match
          case GenerateOutcome.Generated(t) =>
            val spec = UTGenerator(HavenAluOpUT, param, dir / s"op$op").abi.spec
            Some(AbstractStimulus.fromTrace(t, spec))
          case other                        =>
            throw java.lang.AssertionError(s"opcode $op: expected Generated, got $other")
      }
      assert(solved.size == opcodes.size)

      // Concatenate into one stimulus stream and emit the sequence.
      val spec  = UTGenerator(HavenAluOpUT, HavenAluOpParameter(0), dir).abi.spec
      val all   = UvmSequence.concat(spec, solved)
      val codec = UvmSequence("rvprobe_directed_seq", "alu_top_seq_item")
      val sv    = codec.write(all, dir / "rvprobe_directed_seq.sv")
      // The same intents at volume: pin only what the intent constrained (op, start) and let the free data
      // fields randomize, so one witness per opcode expands into 1000 transactions that all still satisfy it.
      UvmSequence("rvprobe_bulk_seq", "alu_top_seq_item", pinned = Some(Set("op", "start")), repeatPerBeat = 1000)
        .write(all, dir / "rvprobe_bulk_seq.sv")
      val text  = os.read(sv)

      // Every opcode the intents targeted must appear as a concrete assignment.
      for op <- opcodes do assert(text.contains(s"txn.op = 4'h${op.toHexString};"))
      assert(text.contains("class rvprobe_directed_seq extends uvm_sequence #(alu_top_seq_item)"))
      assert(text.contains("txn.start = 1'h1;"))
      // Concrete values, not randomization — that is the whole point.
      assert(!text.contains(".randomize()"))
      assert(all.cycles >= opcodes.size)
