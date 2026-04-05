// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.tests

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.constraints.*

import utest.*

object PerfBenchmark extends TestSuite {
  def makeL1(nInst: Int): RVGenerator = new RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until nInst).foreach { i =>
        instruction(i, isAddi()) { rdRange(1, 5) }
      }

  def makeL2(nInst: Int): RVGenerator = new RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until nInst).foreach { i =>
        instruction(i, isAddi()) {
          rdRange(1, 5) & rs1Range(1, 10) & imm12Range(-100, 100)
        }
      }

  def makeL3(nInst: Int): RVGenerator = new RVGenerator:
    val sets          = isRV64GC()
    def constraints() =
      (0 until nInst).foreach { i =>
        instruction(i, isAddi()) { rdRange(1, 5) & imm12Range(-100, 100) }
      }

      (0 until (nInst - 1)).foreach { i =>
        sequence(i, i + 1).coverRAW()
      }

  case class StageResult(complexity: String, nInst: Int, stage1Ms: Double, stage2Ms: Double, assembleMs: Double) {
    def totalMs: Double = stage1Ms + stage2Ms + assembleMs
  }

  def benchmark(complexity: String, nInst: Int, warmup: Int = 3, iterations: Int = 10): StageResult = {
    def makeGen(): RVGenerator = complexity match
      case "L1" => makeL1(nInst)
      case "L2" => makeL2(nInst)
      case "L3" => makeL3(nInst)

    // Warmup
    (0 until warmup).foreach { _ =>
      val g            = makeGen()
      val (opcodes, _) = g.solveOpcodes()
      val args         = g.solveArgs(opcodes)
      g.assembleInstructions(opcodes, args)
    }

    // Measurement
    var sumStage1   = 0.0
    var sumStage2   = 0.0
    var sumAssemble = 0.0

    (0 until iterations).foreach { _ =>
      val g = makeGen()

      val t1a          = System.nanoTime()
      val (opcodes, _) = g.solveOpcodes()
      val t1b          = System.nanoTime()

      val t2a  = System.nanoTime()
      val args = g.solveArgs(opcodes)
      val t2b  = System.nanoTime()

      val t3a          = System.nanoTime()
      val instructions = g.assembleInstructions(opcodes, args)
      val t3b          = System.nanoTime()

      sumStage1   += (t1b - t1a).toDouble / 1e6
      sumStage2   += (t2b - t2a).toDouble / 1e6
      sumAssemble += (t3b - t3a).toDouble / 1e6
    }

    StageResult(
      complexity,
      nInst,
      sumStage1 / iterations,
      sumStage2 / iterations,
      sumAssemble / iterations
    )
  }

  val tests = Tests {
    test("perfBenchmark") {
      val complexities = Seq("L1", "L2", "L3")
      val sizes        = Seq(10, 50, 100, 200, 500)

      // Global warmup to eliminate JVM cold-start bias
      println("Warming up JVM...")
      (0 until 5).foreach { _ =>
        val g            = makeL1(100)
        val (opcodes, _) = g.solveOpcodes()
        val args         = g.solveArgs(opcodes)
        g.assembleInstructions(opcodes, args)
      }
      println("Warmup complete.")

      println("complexity,nInst,avgStage1Ms,avgStage2Ms,avgAssembleMs,avgTotalMs")
      val results = for {
        c <- complexities
        n <- sizes
      } yield {
        val r = benchmark(c, n)
        println(f"${r.complexity},${r.nInst},${r.stage1Ms}%.1f,${r.stage2Ms}%.1f,${r.assembleMs}%.1f,${r.totalMs}%.1f")
        r
      }

      // Write CSV
      val csvPath = os.pwd / "jmh_stage_results.csv"
      val lines   = "complexity,nInst,avgStage1Ms,avgStage2Ms,avgAssembleMs,avgTotalMs" +:
        results.map(r => f"${r.complexity},${r.nInst},${r.stage1Ms}%.1f,${r.stage2Ms}%.1f,${r.assembleMs}%.1f,${r.totalMs}%.1f")
      os.write.over(csvPath, lines.mkString("\n") + "\n")
      println(s"\nCSV written to: $csvPath")
    }
  }
}
