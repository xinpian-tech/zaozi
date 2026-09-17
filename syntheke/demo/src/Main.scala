package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.circt.*

object Main:
  def main(args: Array[String]): Unit =
    require(args.nonEmpty && args.length <= 2, "usage: syntheke-demo <output directory> [config.json]")
    val dir      = os.Path(args.head, os.pwd)
    val config   = SocConfig.load(args.lift(1).map(os.Path(_, os.pwd)))
    val resolved = Negotiator.negotiate(Soc.build(config))
    val design   = Elaborator.elaborate(resolved)

    os.makeDir.all(dir)
    os.write.over(dir / "Top.mlirbc", design.mlirbc)
    design.verilog.foreach((name, content) => os.write.over(dir / name, content))
    os.write.over(dir / "target.yaml", Bringup.probeRsTarget(resolved))
    os.write.over(dir / "topology.json", ujson.write(Export.topology(resolved.spec), indent = 2))
    os.write.over(dir / "domains.json", ujson.write(Export.domains(resolved), indent = 2))
    os.write.over(dir / "edges.json", ujson.write(Export.edges(resolved), indent = 2))
    os.write.over(dir / "plan.json", ujson.write(Export.plan(resolved), indent = 2))
    os.write.over(dir / "params.json", ujson.write(Export.params(resolved), indent = 2))
    os.write.over(dir / "design.env", Bringup.designEnv(config))
    os.write.over(dir / "config.json", upickle.default.write(config, indent = 2))
    println(s"wrote ${design.verilog.size} Verilog files, Top.mlirbc, target.yaml, design.env and five exports to $dir")
