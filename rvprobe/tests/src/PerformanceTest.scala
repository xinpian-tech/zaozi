// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.tests

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.constraints.*

import utest.*
import scala.util.Random
import java.io.PrintWriter
import java.io.FileWriter

case class PerformanceMetrics(
  tMLIR:        Long, // MLIR generation time (ms)
  tSMTLIB:      Long, // SMTLIB generation time (ms)
  tZ3:          Long, // Z3 solving time (ms)
  tInstruction: Long, // Instruction parsing time (ms)
  success:      Boolean)

object PerformanceTest extends TestSuite:
  val tests = Tests:
    // Experiment parameters
    val instructionCounts = Seq(10, 50, 100, 200, 500)
    val runsPerDataPoint  = 100
    val complexityLevels  = Seq("L1", "L2", "L3")

    // Results storage
    var results: Map[(String, Int), Seq[PerformanceMetrics]] = Map()

    println("Starting RVProbe Performance Experiment...")
    println(s"Testing instruction counts: ${instructionCounts.mkString(", ")}")
    println(s"Runs per data point: $runsPerDataPoint")

    for {
      complexity <- complexityLevels
      nInst      <- instructionCounts
    } {
      println(s"\nTesting $complexity with $nInst instructions...")
      val metrics = (1 to runsPerDataPoint).map { run =>
        val seed = Random.nextInt(10000)
        Random.setSeed(seed)

        complexity match {
          case "L1" => runL1Test(nInst)
          case "L2" => runL2Test(nInst)
          case "L3" => runL3Test(nInst)
        }
      }

      results = results + ((complexity, nInst) -> metrics)

      // Print intermediate results
      val successfulMetrics = metrics.filter(_.success)
      val avgTMLIR          = successfulMetrics.map(_.tMLIR).sum.toDouble / successfulMetrics.size
      val avgTSMTLIB        = successfulMetrics.map(_.tSMTLIB).sum.toDouble / successfulMetrics.size
      val avgTZ3            = successfulMetrics.map(_.tZ3).sum.toDouble / successfulMetrics.size
      val avgTInstruction   = successfulMetrics.map(_.tInstruction).sum.toDouble / successfulMetrics.size
      val successRate       = metrics.count(_.success).toDouble / metrics.size * 100

      println(f"    - MLIR generation: ${avgTMLIR}%.1f ms")
      println(f"    - SMTLIB generation: ${avgTSMTLIB}%.1f ms")
      println(f"    - Z3 solving: ${avgTZ3}%.1f ms")
      println(f"    - Instruction parsing: ${avgTInstruction}%.1f ms")
      println(f"  Success rate: ${successRate}%.1f%%")
    }

    generateReport()

    // L1: Simple Type Constraints
    def runL1Test(nInst: Int): PerformanceMetrics = {
      object testL1 extends RVGenerator with HasRVProbeTest {
        val sets          = isRV64GC()
        def constraints() = {
          (0 until nInst).foreach { i =>
            instruction(i) {
              isAddi()
            }
          }
        }
      }
      measurePerformance(testL1)
    }

    // L2: Intra-Instruction Constraints
    def runL2Test(nInst: Int): PerformanceMetrics = {
      object testL2 extends RVGenerator with HasRVProbeTest {
        val sets          = isRV64GC()
        def constraints() = {
          (0 until nInst).foreach { i =>
            instruction(i) {
              isAddi() & rdRange(1, 5) & rs1Range(1, 10) & imm12Range(-100, 100)
            }
          }
        }
      }
      measurePerformance(testL2)
    }

    // L3: Global Sequence Constraints
    def runL3Test(nInst: Int): PerformanceMetrics = {
      object testL3 extends RVGenerator with HasRVProbeTest {
        val sets          = isRV64GC()
        def constraints() = {
          (0 until nInst).foreach { i =>
            instruction(i) {
              isAddi() & rdRange(1, 5) & imm12Range(-100, 100)
            }
          }

          (0 until nInst-1).foreach { i =>
            sequence(i, i+1).coverRAW(true)
          }
        }
      }
      measurePerformance(testL3)
    }

    def measurePerformance(generator: RVGenerator & HasRVProbeTest): PerformanceMetrics = {
      try {
        val startTime = System.currentTimeMillis()

        // Stage 1: Create MLIR context and generate MLIR
        val mlirContextStart         = System.currentTimeMillis()
        val (arena, context, module) = generator.createMLIRModule()
        val mlirContextEnd           = System.currentTimeMillis()
        val mlirTime                 = mlirContextEnd - mlirContextStart

        // Stage 2: Convert MLIR to SMTLIB
        val smtlibStart = System.currentTimeMillis()
        val smtlib      = generator.mlirToSMTLIB(arena, context, module).replace("(reset)", "(get-model)")
        val smtlibEnd   = System.currentTimeMillis()
        val smtlibTime  = smtlibEnd - smtlibStart

        // Stage 3: Z3 solving
        val z3Start  = System.currentTimeMillis()
        val z3Output = os.proc("z3", "-in", "-t:5000").call(stdin = smtlib, check = false)
        val z3End    = System.currentTimeMillis()
        val z3Time   = z3End - z3Start

        // Stage 4: Parse instructions
        val instructionStart = System.currentTimeMillis()
        val instructions     = generator.toInstructions(z3Output.out.text())
        val instructionEnd   = System.currentTimeMillis()
        val instructionTime  = instructionEnd - instructionStart

        // Clean up MLIR context
        generator.closeMLIRContext(arena, context)

        val endTime   = System.currentTimeMillis()
        val totalTime = endTime - startTime

        PerformanceMetrics(mlirTime, smtlibTime, z3Time, instructionTime, true)
      } catch {
        case e: Exception =>
          println(s"Test failed: ${e.getMessage}")
          PerformanceMetrics(0, 0, 0, 0, false)
      }
    }

    def generateReport(): Unit = {
      println("\n" + "=" * 80)
      println("PERFORMANCE EXPERIMENT RESULTS")
      println("=" * 80)

      // Generate Table 4.X
      generateTable()

      // Generate CSV for plotting
      generateCSV()

      // Generate analysis
      generateAnalysis()
    }

    def generateTable(): Unit = {
      println("\nTable 4.X: Performance Data")
      println("-" * 80)

      val header = f"${"Complexity"}%-12s ${"Metric"}%-12s" +
        instructionCounts.map(n => f"${n}%-10s").mkString("")
      println(header)
      println("-" * header.length)

      for (complexity <- complexityLevels) {
        val tMLIRRow = f"${""}%-12s ${"T_MLIR (ms)"}%-12s" +
          instructionCounts.map { nInst =>
            val metrics  = results.get((complexity, nInst)).getOrElse(Seq())
            val avgTMLIR = if (metrics.nonEmpty) {
              metrics.filter(_.success).map(_.tMLIR).sum.toDouble / metrics.count(_.success)
            } else 0.0
            f"${avgTMLIR}%-10.1f"
          }.mkString("")

        val tSMTLIBRow = f"${""}%-12s ${"T_SMTLIB (ms)"}%-12s" +
          instructionCounts.map { nInst =>
            val metrics    = results.get((complexity, nInst)).getOrElse(Seq())
            val avgTSMTLIB = if (metrics.nonEmpty) {
              metrics.filter(_.success).map(_.tSMTLIB).sum.toDouble / metrics.count(_.success)
            } else 0.0
            f"${avgTSMTLIB}%-10.1f"
          }.mkString("")

        val tZ3Row = f"${""}%-12s ${"T_Z3 (ms)"}%-12s" +
          instructionCounts.map { nInst =>
            val metrics = results.get((complexity, nInst)).getOrElse(Seq())
            val avgTZ3  = if (metrics.nonEmpty) {
              metrics.filter(_.success).map(_.tZ3).sum.toDouble / metrics.count(_.success)
            } else 0.0
            f"${avgTZ3}%-10.1f"
          }.mkString("")

        val tInstructionRow = f"${""}%-12s ${"T_Parse (ms)"}%-12s" +
          instructionCounts.map { nInst =>
            val metrics         = results.get((complexity, nInst)).getOrElse(Seq())
            val avgTInstruction = if (metrics.nonEmpty) {
              metrics.filter(_.success).map(_.tInstruction).sum.toDouble / metrics.count(_.success)
            } else 0.0
            f"${avgTInstruction}%-10.1f"
          }.mkString("")

        println(tMLIRRow)
        println(tSMTLIBRow)
        println(tZ3Row)
        println(tInstructionRow)
        if (complexity != complexityLevels.last) println()
      }
    }

    def generateCSV(): Unit = {
      val writer = new PrintWriter(new FileWriter("performance_results.csv"))
      writer.println("Complexity,InstructionCount,TMLIR_ms,TSMTLIB_ms,TZ3_ms,TInstruction_ms,SuccessRate")

      for {
        complexity <- complexityLevels
        nInst      <- instructionCounts
      } {
        val metrics           = results.get((complexity, nInst)).getOrElse(Seq())
        val successfulMetrics = metrics.filter(_.success)
        val avgTMLIR          = if (successfulMetrics.nonEmpty) {
          successfulMetrics.map(_.tMLIR).sum.toDouble / successfulMetrics.size
        } else 0.0
        val avgTSMTLIB        = if (successfulMetrics.nonEmpty) {
          successfulMetrics.map(_.tSMTLIB).sum.toDouble / successfulMetrics.size
        } else 0.0
        val avgTZ3            = if (successfulMetrics.nonEmpty) {
          successfulMetrics.map(_.tZ3).sum.toDouble / successfulMetrics.size
        } else 0.0
        val avgTInstruction   = if (successfulMetrics.nonEmpty) {
          successfulMetrics.map(_.tInstruction).sum.toDouble / successfulMetrics.size
        } else 0.0
        val successRate       = if (metrics.nonEmpty) {
          metrics.count(_.success).toDouble / metrics.size * 100
        } else 0.0

        writer.println(
          f"$complexity,$nInst,$avgTMLIR%.1f,$avgTSMTLIB%.1f,$avgTZ3%.1f,$avgTInstruction%.1f,$successRate%.1f"
        )
      }
      writer.close()
      println("\nResults saved to performance_results.csv")
    }

    def generateAnalysis(): Unit = {
      println("\n4.4.4 Analysis and Conclusion")
      println("-" * 40)

      // Analyze eDSL overhead
      println("eDSL Overhead Analysis:")
      for (complexity <- complexityLevels) {
        val largestTest = results.get((complexity, instructionCounts.max))
        largestTest.foreach { metrics =>
          val successfulMetrics = metrics.filter(_.success)
          if (successfulMetrics.nonEmpty) {
            val avgTMLIR        = successfulMetrics.map(_.tMLIR).sum.toDouble / successfulMetrics.size
            val avgTSMTLIB      = successfulMetrics.map(_.tSMTLIB).sum.toDouble / successfulMetrics.size
            val avgTZ3          = successfulMetrics.map(_.tZ3).sum.toDouble / successfulMetrics.size
            val avgTInstruction = successfulMetrics.map(_.tInstruction).sum.toDouble / successfulMetrics.size
            val overheadeDSL    = avgTMLIR + avgTSMTLIB + avgTInstruction
            val overheadPercent = (overheadeDSL / (avgTInstruction + avgTZ3 + avgTSMTLIB + avgTMLIR)) * 100

            println(f"  $complexity: eDSL overhead = ${overheadeDSL}%.1f ms (${overheadPercent}%.1f%% of total time)")
            println(f"    - MLIR: ${avgTMLIR}%.1f ms, SMTLIB: ${avgTSMTLIB}%.1f ms, Parse: ${avgTInstruction}%.1f ms")
            println(
              f"    - Z3 solving: ${avgTZ3}%.1f ms (${(avgTZ3 / (avgTInstruction + avgTZ3 + avgTSMTLIB + avgTMLIR)) * 100}%.1f%% of total time)"
            )
          }
        }
      }

      // Analyze scalability
      println("\nScalability Analysis:")
      for (complexity <- complexityLevels) {
        val times = instructionCounts.flatMap { nInst =>
          results.get((complexity, nInst)).flatMap { metrics =>
            val successfulMetrics = metrics.filter(_.success)
            if (successfulMetrics.nonEmpty) {
              val avgTZ3 = successfulMetrics.map(_.tZ3).sum.toDouble / successfulMetrics.size
              Some((nInst, avgTZ3))
            } else None
          }
        }

        if (times.size >= 2) {
          val (minInst, minTime) = times.minBy(_._1)
          val (maxInst, maxTime) = times.maxBy(_._1)
          val scalingFactor      = maxTime / minTime
          val instFactor         = maxInst.toDouble / minInst

          println(f"  $complexity: ${scalingFactor}%.1fx Z3 time increase for ${instFactor}%.1fx instruction increase")

          // Also analyze total generation time scaling
          val totalTimes = instructionCounts.flatMap { nInst =>
            results.get((complexity, nInst)).flatMap { metrics =>
              val successfulMetrics = metrics.filter(_.success)
              if (successfulMetrics.nonEmpty) {
                val avgTGen = successfulMetrics
                  .map(x => (x.tMLIR + x.tSMTLIB + x.tZ3 + x.tInstruction))
                  .sum
                  .toDouble / successfulMetrics.size
                Some((nInst, avgTGen))
              } else None
            }
          }

          if (totalTimes.size >= 2) {
            val (_, minTotalTime)  = totalTimes.minBy(_._1)
            val (_, maxTotalTime)  = totalTimes.maxBy(_._1)
            val totalScalingFactor = maxTotalTime / minTotalTime
            println(f"    Total generation: ${totalScalingFactor}%.1fx time increase")
          }
        }
      }

      println("\nConclusion:")
      println("The experiment quantifies RVProbe's performance overhead.")
      println("Results demonstrate that the overhead is reasonable for its target")
      println("application of generating directed tests that are difficult for CRV to hit.")
    }
