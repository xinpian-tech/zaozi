package me.jiuyang.syntheke.demo.harness

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.demo.{
  ClockDomain,
  ClockReset,
  ClockValue,
  IO,
  PowerDomain,
  PowerValue,
  ResetAssertion,
  ResetDomain,
  ResetRelease,
  ResetValue
}
import me.jiuyang.syntheke.demo.zaoziimpl.{*, given}
import me.jiuyang.syntheke.zaozi.zaozi


given TestbenchDefinition[TestHarnessP] = zaozi.testbench(TestHarnessGen)

private def poweredHarness[A](
  body: TestbenchScope[TestHarnessP] ?=> A
)(
  using TestbenchScope[TestHarnessP]
): A =
  val modelPowerDomain = PowerDomain.declare(PowerValue.ExternalDigitalModel, None)
  modelPowerDomain.provide(body)

final case class TestHarnessNodes(
  crystalClockDomain:     DomainHandle[ClockDomain.type],
  tckClockDomain:         DomainHandle[ClockDomain.type],
  boardResetDomain:       DomainHandle[ResetDomain.type],
  private val outputs:    Vector[ClockReset.Outward],
  private val tckOutputs: Vector[ClockReset.Outward],
  pins:                  Vector[IO.Inward]):
  def tap(n: String): ClockReset.Outward =
    outputs
      .find(_.id.name == n)
      .getOrElse(
        throw new IllegalArgumentException(
          s"harness has no clock tap '$n' (taps: ${outputs.map(_.id.name).mkString(", ")})"
        )
      )

  def tckTap(n: String): ClockReset.Outward =
    tckOutputs
      .find(_.id.name == n)
      .getOrElse(
        throw new IllegalArgumentException(
          s"harness has no tck tap '$n' (taps: ${tckOutputs.map(_.id.name).mkString(", ")})"
        )
      )

private final class TestHarnessBuilder(
  freqHz:          Int,
  taps:            Vector[String],
  tckTaps:         Vector[String],
  baud:            Int,
  pinCount:        Int,
  uartPins:        Vector[Int],
  jtagPins:        Vector[Int],
  jtagPort:        Int,
  tckDiv:          Int
)(
  using TestbenchScope[TestHarnessP]):
  val crystalClockDomain   = ClockDomain.declare(ClockValue(freqHz), None)
  val tckClockDomain       = ClockDomain.declare(ClockValue(freqHz / (2 * tckDiv)), None)
  val boardResetDomain     = ResetDomain.declare(
    ResetValue(
      activeHigh = true,
      assertion = ResetAssertion.Asynchronous,
      release = ResetRelease.Synchronous
    ),
    None
  )

  private val outputDrafts = taps.map { n =>
    given sourcecode.Name = sourcecode.Name(n)
    outward(ClockReset)(
      crystalClockDomain,
      boardResetDomain,
      PowerDomain
    )
  }

  private val tckOutputDrafts = tckTaps.map { n =>
    given sourcecode.Name = sourcecode.Name(n)
    outward(ClockReset)(
      tckClockDomain,
      boardResetDomain,
      PowerDomain
    )
  }

  private val outputs    = outputDrafts.map(_.fixed(()))
  private val tckOutputs = tckOutputDrafts.map(_.fixed(()))
  val pins = Vector.tabulate(pinCount) { i =>
    given sourcecode.Name = sourcecode.Name(s"pin$i")
    inward(IO)(PowerDomain).fixed(())
  }

  observedParameters { (probes, _, domains) =>
    val traces = TraceObservation.select(probes)
    Right(
      TestHarnessP(
          domains.value(crystalClockDomain).hz,
          taps,
          tckTaps,
          baud,
          pinCount,
          uartPins,
          jtagPins,
          jtagPort,
          tckDiv,
          traces
      )
    )
  }

  val result: TestHarnessNodes = TestHarnessNodes(
    crystalClockDomain,
    tckClockDomain,
    boardResetDomain,
    outputs,
    tckOutputs,
    pins
  )

def testHarness(
  freqHz:          Int,
  taps:            Vector[String],
  tckTaps:         Vector[String],
  baud:            Int,
  pinCount:        Int,
  uartPins:        Vector[Int],
  jtagPins:        Vector[Int],
  jtagPort:        Int,
  tckDiv:          Int
)(
  using
  ws:              WrapperScope,
  name:            sourcecode.Name,
  file:            sourcecode.File,
  line:            sourcecode.Line
): TestHarnessNodes =
  testbench[TestHarnessP]:
    poweredHarness:
      val harness = new TestHarnessBuilder(
        freqHz,
        taps,
        tckTaps,
        baud,
        pinCount,
        uartPins,
        jtagPins,
        jtagPort,
        tckDiv
      ).result
      (harness, Vector.empty)
