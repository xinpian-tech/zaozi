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

def makeL1(nInst: Int): RVGenerator & HasRVProbeTest = new RVGenerator with HasRVProbeTest:
  val sets          = isRV64GC()
  def constraints() =
    (0 until nInst).foreach { i =>
      instruction(i) { isAddi() }
    }

def makeL2(nInst: Int): RVGenerator & HasRVProbeTest = new RVGenerator with HasRVProbeTest:
  val sets          = isRV64GC()
  def constraints() =
    (0 until nInst).foreach { i =>
      instruction(i) {
        isAddi() & rdRange(1, 5) & rs1Range(1, 10) & imm12Range(-100, 100)
      }
    }

def makeL3(nInst: Int): RVGenerator & HasRVProbeTest = new RVGenerator with HasRVProbeTest:
  val sets          = isRV64GC()
  def constraints() =
    (0 until nInst).foreach { i =>
      instruction(i) { isAddi() & rdRange(1, 5) & imm12Range(-100, 100) }
    }

    (0 until (nInst - 1)).foreach { i =>
      sequence(i, i + 1).coverRAW(true)
    }

@State(Scope.Thread)
class RVProbeBenchmark {
  @Param(scala.Array("L1", "L2", "L3"))
  var complexity: String = ""

  @Param(scala.Array("10", "50", "100", "200", "500"))
  var nInst: Int = 0

  // accumulators
  private var runs:     Long   = 0L
  private var sumMlir:  Double = 0.0
  private var sumSmt:   Double = 0.0
  private var sumZ3:    Double = 0.0
  private var sumParse: Double = 0.0

  @Benchmark
  @Warmup(iterations = 3)
  @Measurement(iterations = 3)
  @Fork(value = 1)
  @Threads(value = 3)
  def fullPipeline(bh: Blackhole): Unit = {
    val t0 = System.nanoTime()
    // create generator (reuse the same factory you already have)
    val generator: RVGenerator & HasRVProbeTest =
      complexity match
        case "L1"  => makeL1(nInst)
        case "L2"  => makeL2(nInst)
        case "L3"  => makeL3(nInst)
        case other => throw IllegalArgumentException(s"Unknown $other")

    val t1a                      = System.nanoTime()
    val (arena, context, module) = generator.createMLIRModule()
    val t1b                      = System.nanoTime()
    val mlirMs                   = (t1b - t1a).toDouble / 1e6

    val t2a    = System.nanoTime()
    val smtlib = generator.mlirToSMTLIB(arena, context, module).replace("(reset)", "(get-model)")
    val t2b    = System.nanoTime()
    val smtMs  = (t2b - t2a).toDouble / 1e6

    val t3a      = System.nanoTime()
    val z3Output = os.proc("z3", "-in", "-t:5000").call(stdin = smtlib, check = false)
    val t3b      = System.nanoTime()
    val z3Ms     = (t3b - t3a).toDouble / 1e6

    val t4a     = System.nanoTime()
    generator.toInstructions(z3Output.out.text())
    val t4b     = System.nanoTime()
    val parseMs = (t4b - t4a).toDouble / 1e6

    generator.closeMLIRContext(arena, context)

    // accumulate
    runs += 1
    sumMlir += mlirMs
    sumSmt += smtMs
    sumZ3 += z3Ms
    sumParse += parseMs

    // consume something so JMH doesn't optimize away
    bh.consume(mlirMs + smtMs + z3Ms + parseMs)
  }

  @TearDown(Level.Trial)
  def writeCsv(): Unit = {
    import java.io.{File, FileWriter, PrintWriter}
    // out/rvprobe/benchmark/runJmh.dest/jmh_stage_results.csv
    val file   = File("jmh_stage_results.csv")
    val writer = PrintWriter(FileWriter(file, true))
    try {
      if (file.length == 0) writer.println("complexity,nInst,avgMlirMs,avgSmtMs,avgZ3Ms,avgParseMs")
      val avgMlir  = if (runs > 0) sumMlir / runs else 0.0
      val avgSmt   = if (runs > 0) sumSmt / runs else 0.0
      val avgZ3    = if (runs > 0) sumZ3 / runs else 0.0
      val avgParse = if (runs > 0) sumParse / runs else 0.0
      writer.println(f"$complexity,$nInst,$avgMlir%.3f,$avgSmt%.3f,$avgZ3%.3f,$avgParse%.3f")
    } finally writer.close()
  }
}
