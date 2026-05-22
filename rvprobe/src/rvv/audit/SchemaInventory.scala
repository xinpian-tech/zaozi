// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.rvv.audit

import me.jiuyang.rvprobe.rvv.{Schema, SchemaCategory}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

object SchemaInventory:
  def render(): String =
    val all  = Schema.all
    val cats = List(
      SchemaCategory.Vsetvl    -> "vsetvl*",
      SchemaCategory.Fp        -> "FP",
      SchemaCategory.LoadStore -> "Load/store",
      SchemaCategory.Integer   -> "Integer")

    val sb = new StringBuilder
    sb.append("# Schema Inventory\n\n")
    sb.append("Generated from `me.jiuyang.rvprobe.rvv.Schema`. Do not edit by hand.\n")
    sb.append("To regenerate, run the `SchemaInventory` @main against the current `Schema.scala`.\n\n")
    sb.append("## Summary\n\n")
    sb.append("| Category   | Count |\n")
    sb.append("|---|---|\n")
    for (cat, label) <- cats do
      sb.append(s"| $label | ${Schema.ofCategory(cat).size} |\n")
    sb.append(s"| **Total** | **${all.size}** |\n\n")

    for (cat, label) <- cats do
      sb.append(s"## $label\n\n")
      sb.append("| Schema enum | Format string |\n")
      sb.append("|---|---|\n")
      for s <- Schema.ofCategory(cat) do
        sb.append(s"| `${s.toString}` | `${s.formatString}` |\n")
      sb.append("\n")
    sb.toString

  @main def writeSchemaInventory(outPath: String): Unit =
    val content = render()
    Files.write(Paths.get(outPath), content.getBytes(StandardCharsets.UTF_8))
    println(s"wrote ${content.length} bytes to $outPath")
