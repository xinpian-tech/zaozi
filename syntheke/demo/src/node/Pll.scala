package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.demo.zaoziimpl.{*, given}
import me.jiuyang.syntheke.zaozi.zaozi

given GeneratorDefinition[PllP] = zaozi(PllGen)

final case class PllNodes(
  ref:                 ClockReset.Inward,
  systemClockDomain:   Domain[ClockDomain.type],
  systemResetDomain:   Domain[ResetDomain.type],
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
  ): PllNodes =
    val refDraft =
      given sourcecode.Name = sourcecode.Name("ref")
      inward(ClockReset)()

    val refClock = refDraft.domain(ClockDomain)
    val refReset = refDraft.domain(ResetDomain)

    // The loop starts a new root clock; its reset is the reference reset released on that clock.
    val systemClockDomain = ClockDomain.root(ClockInput(outHz))
    val systemResetDomain =
      ResetDomain.target(activeLow = false, refReset -> None)(Some(ResetProcessing.Async(systemClockDomain, 2)))

    val outputDrafts = taps.map { n =>
      given sourcecode.Name = sourcecode.Name(n)
      outward(ClockReset)(systemClockDomain, systemResetDomain)
    }

    val ref     = refDraft.fixed(())
    val outputs = outputDrafts.map(_.fixed(()))

    parameters { (_, domains) =>
      val refHz = ClockDomain.hz(domains(refClock))
      val gcd   = BigInt(outHz).gcd(BigInt(refHz)).toInt
      val mult  = outHz / gcd
      val div   = refHz / gcd
      if ResetDomain.activeLow(domains(refReset)) then Left(Violation("the PLL macro requires an active-high reference reset"))
      else if mult > PllNodes.maxMult || div > PllNodes.maxDiv then
        Left(
          Violation(
            s"$refHz Hz to $outHz Hz needs a $mult/$div loop, beyond the PLL's ${PllNodes.maxMult}/${PllNodes.maxDiv}"
          )
        )
      else Right(PllP(refHz, outHz, mult, div, taps))
    }
    PllNodes(ref, systemClockDomain, systemResetDomain, outputs)

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
