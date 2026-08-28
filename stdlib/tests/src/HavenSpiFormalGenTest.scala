// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.stdlib

import me.jiuyang.utlib.*

import utest.*

/** The HAVEN simple_spi benchmark through the loop: the solver discovers a legal Wishbone write handshake (cyc∧stb∧we
  * held with address 2 and data 0xA5 until ack), and the witness replays with ACK observed on the same RTL.
  */
object HavenSpiFormalGenTest extends TestSuite:
  import FormalGenHarness.*

  val tests: Tests = Tests:
    test("Wishbone write constraint → witness → replay: ack with a held 0xA5 write to adr 2"):
      val dir = freshDir("HavenSpi-formalgen")
      val param   = HavenSpiParameter()
      val svFiles = Seq(resources / "haven" / "simple_spi_top.v", resources / "haven" / "fifo4.v")

      val model  = FormalUT.lowerGenerator(HavenSpiUT, param, dir, delayedDrives = Seq("A"))
      val ip     = SvImport.toHw(svFiles, dir / "imported", include = Some(resources / "haven"))
      val merged = SvImport.mergeForBmc(model.hw, ip)
      val txn    = solved(FormalUT.generate(model.copy(hw = merged), bound = 5))

      // The witness must hold a write request (cyc, stb, we, adr=2, dat=0xA5) somewhere.
      val gen  = UTGenerator(HavenSpiUT, param, outputDirectory = dir)
      val stim = Stimulus.save(txn, gen.abi.spec, dir / "stimulus.txt")
      val a    = os.read(stim).linesIterator.map(_.trim.toLong).toVector
      def isWriteA5(v: Long) =
        (v & 1) == 1 && ((v >> 1) & 1) == 1 && ((v >> 2) & 3) == 2 && ((v >> 4) & 1) == 1 && ((v >> 5) & 0xff) == 0xa5
      assert(a.exists(isWriteA5))

      val tb  = gen.emitTestbench(dir, runCycles = txn.cycles + 2)
      val out = buildAndReplay(tb, dir, extraSources = svFiles, extraIncludes = Seq(resources / "haven"))
      assert(out.contains("ACK=1"))
