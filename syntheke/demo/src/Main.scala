// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.circt.*

/** Elaborate the demo SoC and write everything that follows from it: the linked circuit as `Top.mlirbc`, the Verilog as
  * firtool's file set, the program the debugger downloads, the target description it reads the chip from, and the four
  * tooling exports.
  *
  * This is where the Scala ends. What is not derived from the design — the behavioral definitions of the external
  * modules, the DRAM's configuration — is in `sim/` as ordinary source, and what runs the result is meson's.
  */
object Main:
  def main(args: Array[String]): Unit =
    require(args.length == 1, "usage: syntheke-demo <output directory>")
    val dir      = os.Path(args.head, os.pwd)
    val resolved = Negotiator.negotiate(Soc.build())
    val design   = Elaborator.elaborate(resolved, axiBackends)

    os.makeDir.all(dir)
    os.write.over(dir / "Top.mlirbc", design.mlirbc)
    design.verilog.foreach((name, content) => os.write.over(dir / name, content))
    os.write.over(dir / "program.bin", Bringup.image)
    os.write.over(dir / "target.yaml", Bringup.probeRsTarget(resolved))
    os.write.over(dir / "topology.json", ujson.write(Export.topology(resolved.spec), indent = 2))
    os.write.over(dir / "edges.json", ujson.write(Export.edges(resolved), indent = 2))
    os.write.over(dir / "plan.json", ujson.write(Export.plan(resolved), indent = 2))
    os.write.over(dir / "params.json", ujson.write(Export.params(resolved), indent = 2))
    // What the bring-up needs to know about this design, for the script that runs it: where the debugger connects,
    // what it loads where, and what the harts should be seen doing. Derived here so nothing downstream restates it.
    os.write.over(dir / "bringup.env", Bringup.env)
    println(s"wrote ${design.verilog.size} Verilog files, Top.mlirbc, program.bin, target.yaml and the exports to $dir")
