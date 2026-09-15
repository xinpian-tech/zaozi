package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.demo.zaoziimpl.{*, given}
import me.jiuyang.syntheke.zaozi.zaozi

given GeneratorDefinition[XbarP] = zaozi(XbarGen)

final case class AxiXbarNodes(
  clk:                 ClockReset.Inward,
  private val inputs:  Vector[Axi4.Inward],
  private val outputs: Vector[Axi4.Outward]):
  def input(n: String): Axi4.Inward =
    inputs
      .find(_.id.name == n)
      .getOrElse(
        throw new IllegalArgumentException(s"xbar has no input '$n' (inputs: ${inputs.map(_.id.name).mkString(", ")})")
      )

  def output(n: String): Axi4.Outward =
    outputs
      .find(_.id.name == n)
      .getOrElse(
        throw new IllegalArgumentException(
          s"xbar has no output '$n' (outputs: ${outputs.map(_.id.name).mkString(", ")})"
        )
      )

object AxiXbarNodes:
  private[demo] def build(
    name:        String,
    ins:         Vector[String],
    outs:        Vector[String],
    arbitration: Arbitration
  )(
    using GeneratorScope[XbarP]
  ): (AxiXbarNodes, Vector[Constraint]) =
    val clkDraft     =
      given sourcecode.Name = sourcecode.Name("clk")
      inward(ClockReset)(
        ClockDomain,
        ResetDomain,
        PowerDomain
      )
    val inputDrafts  = ins.map { n =>
      given sourcecode.Name = sourcecode.Name(n)
      inward(Axi4)(
        clkDraft.domain(ClockDomain),
        clkDraft.domain(ResetDomain),
        PowerDomain
      )
    }
    val outputDrafts = outs.map { n =>
      given sourcecode.Name = sourcecode.Name(n)
      outward(Axi4)(
        clkDraft.domain(ClockDomain),
        clkDraft.domain(ResetDomain),
        PowerDomain
      )
    }

    val grid    = outputDrafts.map(out => inputDrafts.map(in => depend(in, out)))
    val outputs = outputDrafts.zipWithIndex.map { (out, oi) =>
      val readers = grid(oi).map(_._1)
      out.seal(ReadPlan(readers*))(ctx => Right((Axi4Xbar.mapInputs(readers.map(ctx(_))), Vector.empty)))
    }
    val inputs  = inputDrafts.zipWithIndex.map { (in, ii) =>
      val readers = grid.map(_(ii)._2)
      in.seal(ReadPlan(readers*))(ctx =>
        Axi4Xbar.aggregate(readers.map(ctx(_)), inputDrafts.size).map(value => (value, Vector.empty))
      )
    }
    val clk     = clkDraft.seal(ReadPlan())(_ => Right(((), Vector.empty)))

    parameters { (view, _) =>
      val inShapes = ins.zip(inputs).map((n, b) => n -> shapeOf(view.edgeOf(b)))
      val outEdges = outs.zip(outputs).map((n, b) => n -> view.edgeOf(b))
      Right(
        XbarP(
          name,
          arbitration,
          inShapes,
          outEdges.map((n, e) => n -> shapeOf(e)),
          outEdges.map((_, e) => e.slave.slaves.flatMap(_.address).map(a => (a.base, a.mask)))
        )
      )
    }
    (AxiXbarNodes(clk, inputs, outputs), Vector.empty)

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
  generator[XbarP](AxiXbarNodes.build(name.value, ins, outs, arbitration))
