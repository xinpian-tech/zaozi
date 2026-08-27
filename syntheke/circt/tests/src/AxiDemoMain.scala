// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.circt.tests

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.circt.*

/** Dump the AXI demo SoC's artifacts: `Top.fir`, `Top.sv`, plus the tooling JSON exports and DOT graph. */
object AxiDemoMain:
  def main(args: Array[String]): Unit =
    val resolved = Negotiator.negotiate(AxiVerilogSpec.buildSoc())
    val design   = Elaborator.elaborate(resolved, axiBackends)
    val dir      = os.Path(args.headOption.getOrElse(os.pwd.toString), os.pwd)
    os.write.over(dir / "Top.fir", design.firrtl)
    os.write.over(dir / "Top.sv", design.verilog)
    os.write.over(dir / "topology.json", ujson.write(Export.topology(resolved.spec), indent = 2))
    os.write.over(dir / "edges.json", ujson.write(Export.edges(resolved), indent = 2))
    os.write.over(dir / "plan.json", ujson.write(Export.plan(resolved), indent = 2))
    os.write.over(dir / "params.json", ujson.write(Export.params(resolved), indent = 2))
    println(s"wrote Top.fir / Top.sv / four JSON exports to $dir")
