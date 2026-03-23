// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.cases

import me.jiuyang.rvprobe.*
import me.jiuyang.rvprobe.Register.*
import me.jiuyang.rvprobe.constraints.Recipe

import org.llvm.mlir.scalalib.capi.ir.{Block, Context}

import java.lang.foreign.Arena

/** Shared Chipyard-compatible HTIF helpers for all bare-metal cases.
  *
  * Protocol:
  *   - pass code is `0`
  *   - fail code is non-zero
  *   - value written to `tohost` is `(code << 1) | 1`
  */
object HTIFLib:
  val PassCode: Long       = 0L
  val DefaultFailCode: Long = 1L

  /** Emit `.section ".text"` + `_start:` entry label. */
  def textStart(
    entryLabel: String = "_start"
  )(
    using Recipe
  ): Unit =
    section("\".text\"")
    global(entryLabel)
    label(entryLabel)

  /** Encode an HTIF code as `(code << 1) | 1`. */
  def encodeHtifCode(
    codeReg:    Register,
    encodedReg: Register = x5
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    if encodedReg != codeReg then add(encodedReg, codeReg, x0)
    slli(encodedReg, encodedReg, 1)
    ori(encodedReg, encodedReg, 1)

  /** Write encoded HTIF value to `tohost`. */
  def writeTohost(
    encodedReg:   Register = x5,
    tohostAddrReg: Register = x6,
    tohostSymbol: String = "tohost"
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    la(tohostAddrReg, tohostSymbol)
    sd(tohostAddrReg, encodedReg, 0)

  /** Write a raw code to HTIF after encoding and then spin forever. */
  def signalCodeAndSpin(
    code:         Long,
    codeReg:      Register = x5,
    encodedReg:   Register = x5,
    tohostAddrReg: Register = x6,
    spinLabel:    String = "spin"
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    li(codeReg, code)
    encodeHtifCode(codeReg, encodedReg)
    writeTohost(encodedReg, tohostAddrReg)
    label(spinLabel)
    j(spinLabel)

  /** Pass (code=0) and spin forever. */
  def passAndSpin(
    codeReg:      Register = x5,
    encodedReg:   Register = x5,
    tohostAddrReg: Register = x6,
    spinLabel:    String = "spin"
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    signalCodeAndSpin(PassCode, codeReg, encodedReg, tohostAddrReg, spinLabel)

  /** Fail (non-zero code) and spin forever. */
  def failAndSpin(
    failCode:     Long = DefaultFailCode,
    codeReg:      Register = x5,
    encodedReg:   Register = x5,
    tohostAddrReg: Register = x6,
    spinLabel:    String = "spin_fail"
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    require(failCode != 0L, "HTIF fail code must be non-zero")
    signalCodeAndSpin(failCode, codeReg, encodedReg, tohostAddrReg, spinLabel)

  /** Emit pass-exit sequence. */
  def exit(
    exitLabel:    String = "exit",
    codeReg:      Register = x5,
    encodedReg:   Register = x5,
    tohostAddrReg: Register = x6,
    spinLabel:    String = "spin"
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    label(exitLabel)
    passAndSpin(codeReg, encodedReg, tohostAddrReg, spinLabel)

  /** Emit fail sequence with `fail:` label. */
  def fail(
    failLabel:    String = "fail",
    failCode:     Long = DefaultFailCode,
    codeReg:      Register = x5,
    encodedReg:   Register = x5,
    tohostAddrReg: Register = x6,
    spinLabel:    String = "spin_fail"
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    label(failLabel)
    failAndSpin(failCode, codeReg, encodedReg, tohostAddrReg, spinLabel)

  /** Emit standard `.tohost` and `fromhost` symbols. */
  def tohostSection(
  )(
    using Recipe
  ): Unit =
    section("\".tohost\"", "aw", "@progbits")
    align(6)
    global("tohost")
    label("tohost")
    dword(0)
    align(6)
    global("fromhost")
    label("fromhost")
    dword(0)

  /** Convenience helper for simple tests: emit `exit` and `.tohost` section together. */
  def finish(
    exitLabel:    String = "exit",
    codeReg:      Register = x5,
    encodedReg:   Register = x5,
    tohostAddrReg: Register = x6,
    spinLabel:    String = "spin"
  )(
    using Arena,
    Context,
    Block,
    Recipe
  ): Unit =
    exit(exitLabel, codeReg, encodedReg, tohostAddrReg, spinLabel)
    tohostSection()

  /** String template for `.text` + `_start` (for template-based emitters). */
  def asmTextStart(
    entryLabel: String = "_start"
  ): String =
    s"""    .section ".text"
       |    .globl $entryLabel
       |$entryLabel:""".stripMargin

  /** String template for pass-exit sequence using HTIF encoding. */
  def asmExit(
    exitLabel: String = "exit",
    codeReg:   String = "t0",
    tohostReg: String = "t1",
    spinLabel: String = "spin"
  ): String =
    s"""$exitLabel:
       |    li   $codeReg, 0
       |    slli $codeReg, $codeReg, 1
       |    ori  $codeReg, $codeReg, 1
       |
       |    la   $tohostReg, tohost
       |    sd   $codeReg, 0($tohostReg)
       |$spinLabel:
       |    j    $spinLabel""".stripMargin

  /** String template for `.tohost` section. */
  def asmTohostSection(): String =
    """    .section ".tohost","aw",@progbits
      |    .align 6
      |    .globl tohost
      |tohost:
      |    .dword 0
      |    .align 6
      |    .globl fromhost
      |fromhost:
      |    .dword 0""".stripMargin
