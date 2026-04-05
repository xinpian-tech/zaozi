// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.tests

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.constraints.*

import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit
import java.io.{File, FileWriter, PrintWriter}

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

@State(Scope.Thread)
class RVProbeBenchmark {
  @Param(scala.Array("L1", "L2", "L3"))
  var complexity: String = ""

  @Param(scala.Array("10", "50", "100", "200", "500"))
  var nInst: Int = 0

  // accumulators for per-stage timing
  private var runs:          Long   = 0L
  private var sumStage1:     Double = 0.0
  private var sumStage2:     Double = 0.0
  private var sumAssemble:   Double = 0.0
  private var sumTotal:      Double = 0.0

  @Benchmark
  @Warmup(iterations = 3)
  @Measurement(iterations = 3)
  @Fork(value = 1)
  @Threads(value = 1)
  def fullPipeline(bh: Blackhole): Unit = {
    val generator: RVGenerator =
      complexity match
        case "L1"  => makeL1(nInst)
        case "L2"  => makeL2(nInst)
        case "L3"  => makeL3(nInst)
        case other => throw IllegalArgumentException(s"Unknown $other")

    // Stage 1: Solve Opcodes
    val t1a          = System.nanoTime()
    val (opcodes, _) = generator.solveOpcodes()
    val t1b          = System.nanoTime()
    val stage1Ms     = (t1b - t1a).toDouble / 1e6

    // Stage 2: Solve Args
    val t2a      = System.nanoTime()
    val args     = generator.solveArgs(opcodes)
    val t2b      = System.nanoTime()
    val stage2Ms = (t2b - t2a).toDouble / 1e6

    // Stage 3: Assemble Instructions
    val t3a        = System.nanoTime()
    val instructions = generator.assembleInstructions(opcodes, args)
    val t3b        = System.nanoTime()
    val assembleMs = (t3b - t3a).toDouble / 1e6

    val totalMs = stage1Ms + stage2Ms + assembleMs

    // accumulate
    runs       += 1
    sumStage1  += stage1Ms
    sumStage2  += stage2Ms
    sumAssemble += assembleMs
    sumTotal   += totalMs

    // consume something so JMH doesn't optimize away
    bh.consume(instructions)
    bh.consume(totalMs)
  }

  @TearDown(Level.Trial)
  def writeCsv(): Unit = {
    val file   = File("jmh_stage_results.csv")
    val writer = PrintWriter(FileWriter(file, true))
    try {
      if (file.length == 0) writer.println("complexity,nInst,avgStage1Ms,avgStage2Ms,avgAssembleMs,avgTotalMs")
      val avgStage1   = if (runs > 0) sumStage1 / runs else 0.0
      val avgStage2   = if (runs > 0) sumStage2 / runs else 0.0
      val avgAssemble = if (runs > 0) sumAssemble / runs else 0.0
      val avgTotal    = if (runs > 0) sumTotal / runs else 0.0
      writer.println(f"$complexity,$nInst,$avgStage1%.3f,$avgStage2%.3f,$avgAssemble%.3f,$avgTotal%.3f")
    } finally writer.close()
  }
}
