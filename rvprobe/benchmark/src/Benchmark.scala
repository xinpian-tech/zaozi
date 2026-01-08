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
      instruction(i, isAddi()) { rdRange(1, 5) }
    }

def makeL2(nInst: Int): RVGenerator & HasRVProbeTest = new RVGenerator with HasRVProbeTest:
  val sets          = isRV64GC()
  def constraints() =
    (0 until nInst).foreach { i =>
      instruction(i, isAddi()) {
        rdRange(1, 5) & rs1Range(1, 10) & imm12Range(-100, 100)
      }
    }

def makeL3(nInst: Int): RVGenerator & HasRVProbeTest = new RVGenerator with HasRVProbeTest:
  val sets          = isRV64GC()
  def constraints() =
    (0 until nInst).foreach { i =>
      instruction(i, isAddi()) { rdRange(1, 5) & imm12Range(-100, 100) }
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

    // Stage 1: Solve Opcodes
    val t1a    = System.nanoTime()
    generator.initialize()
    generator.withContext {
      generator.applyOpcodeConstraints()
    }
    val t1b    = System.nanoTime()
    var mlirMs = (t1b - t1a).toDouble / 1e6

    val t2a    = System.nanoTime()
    var smtlib = generator.withContext { generator.mlirToSMTLIB() }.replace("(reset)", "(get-model)")
    val t2b    = System.nanoTime()
    var smtMs  = (t2b - t2a).toDouble / 1e6

    val t3a      = System.nanoTime()
    var z3Output = os.proc("z3", "-in", "-t:5000").call(stdin = smtlib, check = false).out.text()
    val t3b      = System.nanoTime()
    var z3Ms     = (t3b - t3a).toDouble / 1e6

    val t4a     = System.nanoTime()
    import me.jiuyang.smtlib.parser.{parseZ3Output, Z3Status}
    // println(s"Z3 Output: $z3Output") // Debugging
    val result1 =
      try {
        parseZ3Output(z3Output)
      } catch {
        case e: Exception =>
          System.err.println(f"Parse error for complexity=$complexity nInst=$nInst")
          System.err.println(f"Z3 Output length: ${z3Output.length}")
          System.err.println(f"Z3 Output start: ${z3Output.take(100)}")
          System.err.println(f"Z3 Output end: ${z3Output.takeRight(100)}")
          throw e
      }
    val opcodes = result1.model.collect {
      case (k, v: BigInt) if k.startsWith("nameId_") =>
        k.stripPrefix("nameId_").toInt -> v.toInt
    }
    val t4b     = System.nanoTime()
    var parseMs = (t4b - t4a).toDouble / 1e6

    // Stage 2: Solve Args
    val t5a = System.nanoTime()
    generator.withContext {
      generator.applyArgConstraints(opcodes)
    }
    val t5b = System.nanoTime()
    mlirMs += (t5b - t5a).toDouble / 1e6

    val t6a = System.nanoTime()
    smtlib = generator.withContext { generator.mlirToSMTLIB() }.replace("(reset)", "(get-model)")
    val t6b = System.nanoTime()
    smtMs += (t6b - t6a).toDouble / 1e6

    val t7a = System.nanoTime()
    z3Output = os.proc("z3", "-in", "-t:5000").call(stdin = smtlib, check = false).out.text()
    val t7b = System.nanoTime()
    z3Ms += (t7b - t7a).toDouble / 1e6

    val t8a     = System.nanoTime()
    val result2 = parseZ3Output(z3Output)
    val args    = result2.model.collect { case (k, v: BigInt) => k -> v }
    generator.assembleInstructions(opcodes, args)
    val t8b     = System.nanoTime()
    parseMs += (t8b - t8a).toDouble / 1e6

    generator.close()

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
