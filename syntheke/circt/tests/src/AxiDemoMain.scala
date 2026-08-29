// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.circt.tests

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.circt.*
import me.jiuyang.syntheke.tests.zaoziimpl.{clockGenModel, pllAnalogModel, simConsoleModel}

/** Dump the AXI demo SoC's artifacts: `Top.mlirbc`, `Top.sv`, the behavioral definitions of its external modules —
  * everything a simulator needs — plus the tooling JSON exports.
  */
object AxiDemoMain:
  def main(args: Array[String]): Unit =
    val resolved = Negotiator.negotiate(AxiVerilogSpec.buildSoc())
    val design   = Elaborator.elaborate(resolved, axiBackends)
    val dir      = os.Path(args.headOption.getOrElse(os.pwd.toString), os.pwd)
    os.makeDir.all(dir)
    os.write.over(dir / "Top.mlirbc", design.mlirbc)
    os.write.over(dir / "Top.sv", design.verilog)
    os.write.over(dir / "ClockGen.sv", clockGenModel)
    os.write.over(dir / "SimConsole.sv", simConsoleModel)
    os.write.over(dir / "PllAnalog.sv", pllAnalogModel)
    os.write.over(dir / "topology.json", ujson.write(Export.topology(resolved.spec), indent = 2))
    os.write.over(dir / "edges.json", ujson.write(Export.edges(resolved), indent = 2))
    os.write.over(dir / "plan.json", ujson.write(Export.plan(resolved), indent = 2))
    os.write.over(dir / "params.json", ujson.write(Export.params(resolved), indent = 2))
    println(s"wrote Top.mlirbc / Top.sv / the three behavioral models / four JSON exports to $dir")
