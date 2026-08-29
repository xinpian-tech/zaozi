// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.circt.tests

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.circt.*
import me.jiuyang.syntheke.tests.zaoziimpl.{
  clockGenModel,
  jtagDpiModel,
  jtagDpiSource,
  pllAnalogModel,
  simConsoleModel,
  traceLogModel
}

/** Dump the AXI demo SoC's artifacts: `Top.mlirbc`, `Top.sv`, the behavioral definitions of its external modules —
  * everything a simulator needs — plus what a debugger needs to attach to the result, and the tooling JSON exports.
  */
object AxiDemoMain:
  def main(args: Array[String]): Unit =
    val resolved = Negotiator.negotiate(AxiVerilogSpec.buildSoc())
    val design   = Elaborator.elaborate(resolved, axiBackends)
    val dir      = os.Path(args.headOption.getOrElse(os.pwd.toString), os.pwd)
    os.makeDir.all(dir)
    os.write.over(dir / "Top.mlirbc", design.mlirbc)
    design.verilog.foreach((name, content) => os.write.over(dir / name, content))
    os.write.over(dir / "ClockGen.sv", clockGenModel)
    os.write.over(dir / "SimConsole.sv", simConsoleModel)
    os.write.over(dir / "PllAnalog.sv", pllAnalogModel)
    os.write.over(dir / "TraceLog.sv", traceLogModel)
    os.write.over(dir / "JtagDpi.sv", jtagDpiModel)
    os.write.over(dir / "jtag_dpi.cc", jtagDpiSource)
    // The program the debugger downloads, and the target description it reads the chip from.
    os.write.over(
      dir / "program.bin",
      AxiVerilogSpec.program.flatMap(w => (0 until 4).map(b => ((w >> (b * 8)) & 0xff).toByte)).toArray
    )
    val tap      = resolved.edgeAt(ModuleNodeId(ModuleId.root / "harness", "jtagPins")).edgeAs(Jtag)
    os.write.over(
      dir / "target.yaml",
      AxiVerilogSpec.probeRsTarget(
        tap,
        harts = 2,
        ramBase = AxiVerilogSpec.loadBase,
        ramSize = AxiVerilogSpec.dramBytes
      )
    )
    os.write.over(dir / "topology.json", ujson.write(Export.topology(resolved.spec), indent = 2))
    os.write.over(dir / "edges.json", ujson.write(Export.edges(resolved), indent = 2))
    os.write.over(dir / "plan.json", ujson.write(Export.plan(resolved), indent = 2))
    os.write.over(dir / "params.json", ujson.write(Export.params(resolved), indent = 2))
    println(
      s"wrote Top.mlirbc / Top.sv / the behavioral models / program.bin / target.yaml / four JSON exports to $dir"
    )
