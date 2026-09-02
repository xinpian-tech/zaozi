// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.demo.zaoziimpl.{*, given}
import me.jiuyang.syntheke.demo.axi.{Axi4, Axi4Xbar}

val Xbar = new GeneratorEntry[XbarP]

/** An n×m AXI crossbar: every input reaches every output. The endpoint class declares the nodes, the full dependency
  * matrix, the id-remapping dFns and aggregating uFns, and the port-shape FullParam.
  */
final class AxiXbarNodes(
  name:        String,
  ins:         Vector[String],
  outs:        Vector[String],
  arbitration: Arbitration
)(
  using GeneratorScope[XbarP])
    extends Nodes:
  val clk             = inward(ClockDomain).uFn(_ => Right(()))
  private val inputs  = ins.map { n =>
    given sourcecode.Name = sourcecode.Name(n)
    inward(Axi4)
  }
  private val outputs = outs.map { n =>
    given sourcecode.Name = sourcecode.Name(n)
    outward(Axi4)
  }

  /** Ports are declared by name, so they are looked up by name. */
  def input(n: String): Axi4.Inward =
    require(ins.contains(n), s"xbar has no input '$n' (inputs: ${ins.mkString(", ")})")
    inputs(ins.indexOf(n))

  def output(n: String): Axi4.Outward =
    require(outs.contains(n), s"xbar has no output '$n' (outputs: ${outs.mkString(", ")})")
    outputs(outs.indexOf(n))

  private val grid = outputs.map(out => inputs.map(in => depend(in, out)))
  outputs.zipWithIndex.foreach { (out, oi) =>
    val readers = grid(oi).map(_._1)
    out.dFn(ctx => Right(Axi4Xbar.mapInputs(readers.map(ctx(_)))))
  }
  inputs.zipWithIndex.foreach { (in, ii) =>
    val readers = grid.map(_(ii)._2)
    in.uFn(ctx => Axi4Xbar.aggregate(readers.map(ctx(_)), inputs.size))
  }
  parameters { view =>
    Right(
      XbarP(
        name,
        arbitration,
        ins.zip(inputs).map((n, b) => n -> shapeOf(view, b)),
        outs.zip(outputs).map((n, b) => n -> shapeOf(view, b)),
        outputs.map(b => view.edgeOf(b).slave.slaves.flatMap(_.address).map(a => (a.base, a.mask)))
      )
    )
  }

def axiXbar(
  ins:         Vector[String],
  outs:        Vector[String],
  arbitration: Arbitration
)(
  using
  ws:          WrapperScope,
  name:        sourcecode.Name,
  file:        sourcecode.File,
  line:        sourcecode.Line
): AxiXbarNodes =
  generator(Xbar)(new AxiXbarNodes(name.value, ins, outs, arbitration))
