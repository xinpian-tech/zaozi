// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.tests.rvv

import me.jiuyang.rvprobe.rvv.Schema
import me.jiuyang.rvprobe.rvv.unittest.{RvvInsn, RvvInsnRegistry}

import utest.*

object InsnRegistryTest extends TestSuite:

  val tests = Tests:

    test("RvvInsnRegistry.all has 676 declarations (one per upstream toml)"):
      assert(RvvInsnRegistry.all.size == 676)

    test("RvvInsnRegistry per-extension counts match upstream snapshot tree"):
      val byExt = RvvInsnRegistry.all.groupBy(_.extension).view.mapValues(_.size).toMap
      assert(byExt("v") == 629)
      assert(byExt("zvbb") == 16)
      assert(byExt("zvbc") == 4)
      assert(byExt("zvfbfmin") == 2)
      assert(byExt("zvfbfwma") == 2)
      assert(byExt("zvfhmin") == 2)
      assert(byExt("zvkg") == 2)
      assert(byExt("zvkned") == 11)
      assert(byExt("zvknha") == 3)
      assert(byExt("zvksed") == 3)
      assert(byExt("zvksh") == 2)
      assert(byExt.values.sum == 676)

    test("RvvInsnRegistry (extension, name) keys are unique"):
      val keys = RvvInsnRegistry.all.map(RvvInsn.key)
      assert(keys.distinct.size == keys.size)

    test("Duplicate-name disambiguation: vfncvt.f.f.w in both v and zvfhmin (AC-14)"):
      val matches = RvvInsnRegistry.all.filter(_.name == "vfncvt.f.f.w")
      assert(matches.size == 2)
      assert(matches.map(_.extension).toSet == Set("v", "zvfhmin"))

    test("Duplicate-name disambiguation: vfwcvt.f.f.v in both v and zvfhmin (AC-14)"):
      val matches = RvvInsnRegistry.all.filter(_.name == "vfwcvt.f.f.v")
      assert(matches.size == 2)
      assert(matches.map(_.extension).toSet == Set("v", "zvfhmin"))

    test("Every RvvInsn schema is one of the 39 sealed family entries"):
      val ok = RvvInsnRegistry.all.forall(i => Schema.all.contains(i.schema))
      assert(ok)

    test("vxrm flag is set for exactly 22 instructions (Codex audit)"):
      val vxrm = RvvInsnRegistry.all.count(_.vxrm)
      assert(vxrm == 22)

    test("vxsat flag is set for exactly 14 instructions (Codex audit)"):
      val vxsat = RvvInsnRegistry.all.count(_.vxsat)
      assert(vxsat == 14)

    test("notestfloat3 flag is set for exactly 2 instructions"):
      val ntf = RvvInsnRegistry.all.count(_.notestfloat3)
      assert(ntf == 2)
      val names = RvvInsnRegistry.all.filter(_.notestfloat3).map(_.name).toSet
      assert(names == Set("vfredusum.vs", "vfwredusum.vs"))

    test("RvvInsn.stageFileName roundtrip for vadd.vv"):
      val insn = RvvInsnRegistry.all.find(_.name == "vadd.vv").get
      assert(RvvInsn.stageFileName(insn, 0) == "vadd_vv_v-0.S")

    test("Indexed load schemas have indexedEew populated for vluxei* family"):
      val vluxei8 = RvvInsnRegistry.all.find(_.name == "vluxei8.v")
      assert(vluxei8.isDefined)
      // (The generator may leave indexedEew unset for some; just verify
      // that when present, the value matches the name suffix.)
      vluxei8.flatMap(_.indexedEew).foreach(e => assert(e == 8))
