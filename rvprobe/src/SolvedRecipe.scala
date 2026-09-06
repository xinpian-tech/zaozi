// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe

/** Solved instruction choices and arguments, with the assembly statement layout needed to render them. */
final case class SolvedRecipe(
  opcodes:    Map[Int, Int],
  args:       Map[String, BigInt],
  statements: Seq[Statement])
