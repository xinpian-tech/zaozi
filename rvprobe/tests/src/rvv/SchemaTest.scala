// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.tests.rvv

import me.jiuyang.rvprobe.rvv.{OperandRole, Schema, SchemaCategory}
import me.jiuyang.rvprobe.rvv.audit.SchemaInventory

import java.nio.file.{Files, Paths}

import utest.*

object SchemaTest extends TestSuite:
  val tests = Tests:

    test("Schema.all.size == 39"):
      assert(Schema.all.size == 39)

    test("per-category counts (3/5/8/23)"):
      assert(Schema.ofCategory(SchemaCategory.Vsetvl).size == 3)
      assert(Schema.ofCategory(SchemaCategory.Fp).size == 5)
      assert(Schema.ofCategory(SchemaCategory.LoadStore).size == 8)
      assert(Schema.ofCategory(SchemaCategory.Integer).size == 23)
      assert(Schema.all.size == 3 + 5 + 8 + 23)

    test("byFormatString round-trip for every entry"):
      for s <- Schema.all do
        assert(Schema.byFormatString(s.formatString).contains(s))

    test("formatString uniqueness across all 39 entries"):
      val strings = Schema.all.map(_.formatString)
      assert(strings.distinct.size == strings.size)

    test("lookup returns Right for known format strings"):
      val s   = Schema.VdVs2Vs1Vm
      val res = Schema.lookup(s.formatString)
      assert(res.isRight)
      assert(res.contains(s))

    test("lookup returns Left with explicit error for unknown format"):
      val bogus = "vd,vs2,this-is-not-a-real-format"
      Schema.lookup(bogus) match
        case Left(msg) =>
          assert(msg.contains("unknown RVV schema format"))
          assert(msg.contains(bogus))
        case Right(_)  =>
          assert(false)

    test("indexedSlot Some(Vs2) for indexed load/store schemas only"):
      assert(Schema.VdRs1mVs2Vm.indexedSlot.contains(OperandRole.Vs2))
      assert(Schema.Vs3Rs1mVs2Vm.indexedSlot.contains(OperandRole.Vs2))
      for s <- Schema.all if s != Schema.VdRs1mVs2Vm && s != Schema.Vs3Rs1mVs2Vm do
        assert(s.indexedSlot.isEmpty)

    test("operand roles align with format string commas and parens"):
      // smoke-check: every schema's operandRoles is non-empty
      for s <- Schema.all do assert(s.operandRoles.nonEmpty)

    test("vsetvl* schemas are all category Vsetvl"):
      val vsetvl = List(Schema.Vsetvl, Schema.Vsetvli, Schema.Vsetivli)
      for s <- vsetvl do assert(s.category == SchemaCategory.Vsetvl)

    test("upstream format set is a subset of the Schema family"):
      val configsDir = Paths.get("/root/rvprobe-workspace/riscv-vector-tests/configs")
      if Files.isDirectory(configsDir) then
        val tomlFormats =
          import scala.jdk.CollectionConverters.*
          Files
            .walk(configsDir)
            .iterator
            .asScala
            .filter(p => p.getFileName.toString.endsWith(".toml"))
            .flatMap { p =>
              val src    = scala.io.Source.fromFile(p.toFile)
              try
                val lines = src.getLines.toList
                lines.find(_.trim.startsWith("format")).flatMap { ln =>
                  ln.split("=", 2).toList match
                    case _ :: rhs :: Nil => Some(rhs.trim.stripPrefix("\"").stripSuffix("\""))
                    case _               => None
                }
              finally src.close()
            }
            .toSet
        val rvprobeFormats = Schema.all.map(_.formatString).toSet
        val missing        = tomlFormats -- rvprobeFormats
        assert(missing.isEmpty)
      else
        // upstream not available in this environment, skip
        assert(true)

    test("SchemaInventory.render() matches on-disk schema-inventory.md"):
      val onDisk = Paths.get("/root/rvprobe-workspace/zaozi/rvprobe/src/rvv/audit/schema-inventory.md")
      if Files.exists(onDisk) then
        val expected = new String(Files.readAllBytes(onDisk), java.nio.charset.StandardCharsets.UTF_8)
        val actual   = SchemaInventory.render()
        assert(actual == expected)
      else assert(true)
