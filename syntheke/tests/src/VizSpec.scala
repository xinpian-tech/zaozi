// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.tests

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.tests.axi.AxiSocSpec
import utest.*

object VizSpec extends TestSuite:
  val tests = Tests {
    test("both DOT views render clusters, binds, dependencies and edge labels") {
      val spec = AxiSocSpec.buildSoc()
      val pre  = Viz.dot(spec)
      assert(pre.contains("digraph design"))
      assert(pre.contains("label=\"sysXbar\""))
      assert(pre.contains("label=\"mem\""))                     // the wrapper cluster
      assert(pre.contains("style=dashed"))                      // internal parameter dependencies
      assert(pre.contains("\"sysXbar#mem\" -> \"mem.l2#in\";")) // a cross-hierarchy bind

      val resolved = Negotiator.negotiate(spec)
      val post     = Viz.dot(resolved)
      assert(post.contains("[label=\"AXI4 128b\"]")) // rendered edge summary
      assert(post.contains("[label=\"AXI4 32b\"]"))
    }
  }
