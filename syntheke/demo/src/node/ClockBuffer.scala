package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.demo.zaoziimpl.{*, given}
import me.jiuyang.syntheke.zaozi.zaozi

given GeneratorDefinition[ClockBufferP] = zaozi(ClockBufferGen)

final case class ClockBufferNodes(in: ClockReset.Inward, out: ClockReset.Outward)

object ClockBufferNodes:
  private[demo] def build(
    using GeneratorScope[ClockBufferP]
  ): (ClockBufferNodes, Vector[Constraint]) =
    val inDraft =
      given sourcecode.Name = sourcecode.Name("in")
      inward(ClockReset)(ClockDomain, ResetDomain, PowerDomain)
    val outDraft =
      given sourcecode.Name = sourcecode.Name("out")
      outward(ClockReset)(inDraft.domain(ClockDomain), inDraft.domain(ResetDomain), PowerDomain)

    val in = inDraft.seal(ReadPlan())(_ => Right(((), Vector.empty)))
    val out = outDraft.seal(ReadPlan())(_ => Right(((), Vector.empty)))
    parameters((_, _) => Right(ClockBufferP()))
    (ClockBufferNodes(in, out), Vector.empty)

def clockBuffer()(
  using
  ws: WrapperScope,
  name: sourcecode.Name,
  file: sourcecode.File,
  line: sourcecode.Line
): ClockBufferNodes = generator[ClockBufferP](ClockBufferNodes.build)
