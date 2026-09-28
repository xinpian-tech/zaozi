package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.demo.zaoziimpl.{*, given}
import me.jiuyang.syntheke.zaozi.zaozi

given GeneratorDefinition[PllP] = zaozi(PllGen)

final case class PllNodes(
  ref:                 ClockReset.Inward,
  systemClockDomain:   DomainHandle[ClockDomain.type],
  systemResetDomain:   DomainHandle[ResetDomain.type],
  private val outputs: Vector[ClockReset.Outward]):
  def tap(n: String): ClockReset.Outward =
    outputs
      .find(_.id.name == n)
      .getOrElse(
        throw new IllegalArgumentException(
          s"pll '${ref.id.module.show}' has no clock tap '$n' (taps: ${outputs.map(_.id.name).mkString(", ")})"
        )
      )

object PllNodes:
  val maxMult: Int = 64
  val maxDiv:  Int = 8

  private[demo] def build(
    outHz: Int,
    taps:  Vector[String]
  )(
    using GeneratorScope[PllP]
  ): (PllNodes, Vector[Constraint]) =
    val refDraft =
      given sourcecode.Name = sourcecode.Name("ref")
      inward(ClockReset)(
        ClockDomain,
        ResetDomain,
        PowerDomain
      )

    val refClock = refDraft.domain(ClockDomain)
    val refReset = refDraft.domain(ResetDomain)

    val systemClockDomain = refClock.derive(ClockDomain) { domains =>
      val refHz = domains.value(refClock).hz.toLong
      Right((
        ClockValue(outHz),
        Some(ClockRequirement(
          minHz = Some(((refHz + PllNodes.maxDiv - 1) / PllNodes.maxDiv).toInt),
          maxHz = Some((refHz * PllNodes.maxMult).min(Int.MaxValue.toLong).toInt)
        ))
      ))
    }
    val clocks = Seq(refClock, systemClockDomain)
    val systemResetDomain = (Seq(refReset) ++ clocks).derive(ResetDomain) { domains =>
      val incoming    = domains.value(refReset)
      val frequencies = clocks.map(clock => domains.value(clock).hz)
      if !incoming.activeHigh then Left(Violation("the PLL macro requires an active-high reference reset"))
      else if frequencies.exists(_ <= 0) then Left(Violation("the PLL reference and output clocks must be positive"))
      else
        Right((
          ResetValue(
            activeHigh = true,
            assertion = ResetAssertion.Asynchronous,
            release = ResetRelease.Synchronous
          ),
          None
        ))
    }

    val outputDrafts = taps.map { n =>
      given sourcecode.Name = sourcecode.Name(n)
      outward(ClockReset)(
        systemClockDomain,
        systemResetDomain,
        PowerDomain
      )
    }

    val ratio = clocks.check { domains =>
      val refHz = domains.value(refClock).hz
      val out   = domains.value(systemClockDomain).hz
      val gcd   = BigInt(out).gcd(BigInt(refHz)).toInt
      val mult  = out / gcd
      val div   = refHz / gcd
      if mult > PllNodes.maxMult || div > PllNodes.maxDiv then
        Left(
          Violation(
            s"$refHz Hz to $out Hz needs a $mult/$div loop, beyond the PLL's ${PllNodes.maxMult}/${PllNodes.maxDiv}"
          )
        )
      else Right(())
    }

    val ref     = refDraft.seal(ReadPlan())(_ => Right(((), Vector.empty)))
    val outputs = outputDrafts.map(_.seal(ReadPlan())(_ => Right(((), Vector.empty))))

    parameters { (_, domains) =>
      val refHz       = domains.value(refClock).hz
      val actualOutHz = domains.value(systemClockDomain).hz
      val ratio       = BigInt(actualOutHz).gcd(BigInt(refHz)).toInt
      val mult        = actualOutHz / ratio
      val div         = refHz / ratio
      Right(PllP(refHz, actualOutHz, mult, div, taps))
    }
    (PllNodes(ref, systemClockDomain, systemResetDomain, outputs), Vector(ratio))

def pll(
  outHz: Int,
  taps:  Vector[String]
)(
  using
  ws:    WrapperScope,
  name:  sourcecode.Name,
  file:  sourcecode.File,
  line:  sourcecode.Line
): PllNodes =
  generator[PllP](PllNodes.build(outHz, taps))
