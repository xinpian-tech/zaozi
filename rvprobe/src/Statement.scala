// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe

/** A statement in an assembly program.
  *
  * An assembly file is an ordered sequence of statements: directives, labels, and instructions. This ADT captures the
  * full structure so the entire program can be represented in the typed DSL without resorting to raw template strings.
  */
enum Statement:
  /** A solved instruction at the given index in the Recipe. */
  case Inst(idx: Int)

  /** A label definition (e.g. `_start:`). */
  case Label(name: String)

  /** `.section` directive (e.g. `.section .text`). */
  case Section(name: String, flags: String*)

  /** `.globl` / `.global` directive. */
  case Global(symbol: String)

  /** `.align` directive. */
  case Align(n: Int)

  /** `.word` directive (e.g. `.word 0xdeadbeef`). */
  case Word(value: Long)

  /** Escape hatch for directives not yet modelled. */
  case Raw(content: String)
