// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.tests

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.constraints.*
import me.jiuyang.rvprobe.cases.chaining.ChainingLib.*
import me.jiuyang.rvprobe.cases.coverage.CoverageLib.*

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

  // --- Chaining matrix generators (representative subset from ChainingMatrix.scala) ---
  def makeD1C1 = new RVGenerator:
    val sets          = Seq(isRVV())
    def constraints() = explicitRAW_ALUxALU()

  def makeD1C3 = new RVGenerator:
    val sets          = Seq(isRVV())
    def constraints() = explicitRAW_MaskxALU()

  def makeD1C4 = new RVGenerator:
    val sets          = Seq(isRVV())
    def constraints() = explicitRAW_SlowxFast()

  def makeD2C1 = new RVGenerator:
    val sets          = Seq(isRVV())
    def constraints() = implicitV0RAW_ALUxALU()

  def makeD3C6 = new RVGenerator:
    val sets          = Seq(isRVV())
    def constraints() = war_GatherxALU()

  def makeD4C1 = new RVGenerator:
    val sets          = Seq(isRVV())
    def constraints() = waw_ALUxALU()

  def makeD5C1 = new RVGenerator:
    val sets          = Seq(isRVV())
    def constraints() = implicitV0WAR_ALUxALU()

  // --- Coverage generators (representative subset from RV32I.scala) ---
  def makeCovAdd = new RVGenerator:
    val sets          = isRV64GC()
    def constraints() = rType(35, isAdd())

  def makeCovAddi = new RVGenerator:
    val sets          = isRV64GC()
    def constraints() = iTypeAlu(35, isAddi())

  def makeCovAnd = new RVGenerator:
    val sets          = isRV64GC()
    def constraints() = rTypeLogical(35, isAnd(), and)

  def makeCovSlli = new RVGenerator:
    val sets          = isRV64GC()
    def constraints() = shiftImm(35, isSlli())

  def makeCovLui = new RVGenerator:
    val sets          = isRV64GC()
    def constraints() = uType(35, isLui())

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
    test("exp3b_scalability") {
      val complexities = Seq("L1", "L2", "L3")
      val sizes        = Seq(10, 50, 100, 200, 500, 1000, 2000)

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
      val csvPath = os.pwd / "exp3b_scalability.csv"
      val lines   = "complexity,nInst,avgStage1Ms,avgStage2Ms,avgAssembleMs,avgTotalMs" +:
        results.map(r => f"${r.complexity},${r.nInst},${r.stage1Ms}%.1f,${r.stage2Ms}%.1f,${r.assembleMs}%.1f,${r.totalMs}%.1f")
      os.write.over(csvPath, lines.mkString("\n") + "\n")
      println(s"\nCSV written to: $csvPath")
    }

    test("exp3a_realWorkloads") {
      // Global warmup
      (0 until 3).foreach { _ =>
        val g            = makeL1(50)
        val (opcodes, _) = g.solveOpcodes()
        val args         = g.solveArgs(opcodes)
        g.assembleInstructions(opcodes, args)
      }

      val chainingCases: Seq[(String, RVGenerator)] = Seq(
        ("D1C1_RAW_ALUxALU",       makeD1C1),
        ("D1C3_RAW_MaskxALU",      makeD1C3),
        ("D1C4_RAW_SlowxFast",     makeD1C4),
        ("D2C1_ImplV0RAW_ALUxALU", makeD2C1),
        ("D3C6_WAR_GatherxALU",    makeD3C6),
        ("D4C1_WAW_ALUxALU",       makeD4C1),
        ("D5C1_ImplV0WAR_ALUxALU", makeD5C1),
      )

      val coverageCases: Seq[(String, RVGenerator)] = Seq(
        ("Cov_Add_rType",        makeCovAdd),
        ("Cov_Addi_iType",       makeCovAddi),
        ("Cov_And_rTypeLogical", makeCovAnd),
        ("Cov_Slli_shiftImm",    makeCovSlli),
        ("Cov_Lui_uType",        makeCovLui),
      )

      val allCases   = chainingCases ++ coverageCases
      val iterations = 5

      println("case_name,avg_ms")
      val results = allCases.map { case (name, gen) =>
        // Warmup
        (0 until 2).foreach { _ => gen.toRecipeAsm() }
        // Measure
        val times = (0 until iterations).map { _ =>
          val t0  = System.nanoTime()
          val asm = gen.toRecipeAsm()
          val t1  = System.nanoTime()
          (t1 - t0).toDouble / 1e6
        }
        val avg = times.sum / iterations
        println(f"$name,$avg%.1f")
        (name, avg)
      }

      // Write CSV
      val csvPath = os.pwd / "exp3a_real_workloads.csv"
      val lines   = "case_name,avg_ms" +: results.map { case (n, t) => f"$n,$t%.1f" }
      os.write.over(csvPath, lines.mkString("\n") + "\n")
      println(s"\nCSV written to: $csvPath")
    }

  }
}
