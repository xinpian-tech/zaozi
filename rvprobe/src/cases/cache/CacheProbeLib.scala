// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.cases.cache

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.Register.*
import me.jiuyang.rvprobe.constraints.{*, given}

import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import java.lang.foreign.Arena

/** Reusable assembly snippets for bare-metal cache verification probes.
  *
  * All helpers require the standard DSL context parameters (`Arena`, `Context`, `Block`, `Recipe`).
  */
object CacheProbeLib:
  val CacheLineBytes:      Int = 64
  val SameSetStrideBytes:  Int = 4096
  val ConflictRegionBytes: Int = 32768
  private val WordBytes:   Int = java.lang.Integer.BYTES

  def cacheSets(
    extra: (Recipe ?=> SetConstraint)*
  ): Seq[Recipe ?=> SetConstraint] =
    isRV64GC() ++ extra

  def cacheSetsWithCsr(): Seq[Recipe ?=> SetConstraint] =
    cacheSets(isRVZICSR())

  def cacheSetsWithCounters(): Seq[Recipe ?=> SetConstraint] =
    cacheSets(isRVZICSR(), isRVZICNTR())

  def cacheSetsWithFenceI(): Seq[Recipe ?=> SetConstraint] =
    cacheSets(isRVZICSR(), isRVZIFENCEI())

  /** Emit `.text` section header with `_start` entry point. */
  def textStart(
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    section(".text")
    global("_start")
    label("_start")

  /** Emit the exit sequence: write 1 to `tohost` then spin forever.
    *
    * Produces:
    * {{{
    *   exit:
    *       addi x5, x0, 1
    *       la   x6, tohost
    *       sd   x5, 0(x6)
    *   spin:
    *       j    spin
    * }}}
    */
  def exitSeq(
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    label("exit")
    addi(x5, x0, 1)
    la(x6, "tohost")
    sd(x6, x5, 0)
    label("spin")
    j("spin")

  /** Emit the `.tohost` section with `tohost` and `fromhost` symbols. */
  def tohostSection(
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    section(".tohost", "aw", "@progbits")
    align(6)
    global("tohost")
    global("fromhost")
    label("tohost")
    dword(0)
    label("fromhost")
    dword(0)

  /** Emit a `.data` section with one 64-byte-aligned buffer.
    *
    * @param name
    *   label name for the buffer
    * @param size
    *   number of bytes to reserve (`.zero`)
    */
  def dataBuffer(
    name: String,
    size: Int
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    section(".data")
    balign(64)
    label(name)
    zero(size)

  /** Emit a 64-byte-aligned `.data` buffer with the first word initialized and the rest zero-filled. */
  def initializedWordBuffer(
    name:      String,
    value:     Long,
    lineBytes: Int = CacheLineBytes
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    require(lineBytes >= WordBytes, s"lineBytes ($lineBytes) must be >= $WordBytes")
    section(".data")
    balign(lineBytes)
    label(name)
    word(value)
    zero(lineBytes - WordBytes)

  /** Emit a `.data` section with multiple 64-byte-aligned buffers. */
  def dataBuffers(
    bufs: (String, Int)*
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    section(".data")
    for (name, size) <- bufs do
      balign(64)
      label(name)
      zero(size)

  /** Build a chain of same-set addresses using a constant stride: A=base, B=A+stride, C=B+stride, ... */
  def sameSetAddresses(
    base:   Register,
    stride: Register,
    regs:   Register*
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    regs.headOption.foreach(first => add(first, base, x0))
    regs.sliding(2).foreach {
      case Seq(prev, next) => add(next, prev, stride)
      case _               =>
    }

  def repeat(
    count: Int
  )(body:  => Unit
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    for _ <- 0 until count do body

  def nops(
    count: Int
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    repeat(count) { nop() }

  /** Measure the cycle cost of a block: `rdcycle before` / body / `rdcycle after` / `sub delta`. */
  def timed(
    before: Register,
    after:  Register,
    delta:  Register
  )(body:   => Unit
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    rdcycle(before)
    body
    rdcycle(after)
    sub(delta, after, before)
