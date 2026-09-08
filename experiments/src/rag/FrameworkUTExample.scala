// SPDX-License-Identifier: Apache-2.0
// Generic wiring example only: the external module has no implementation here.
// In a task, the framework supplies this binding; use its Run* types and ImportedDut instead.
import me.jiuyang.utlib.*
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

case class ExampleParameter() extends Parameter
given upickle.default.ReadWriter[ExampleParameter] = upickle.default.macroRW
class ExampleLayers(parameter: ExampleParameter) extends LayerInterface(parameter):
  def layers = Seq.empty
class ExampleProbe(parameter: ExampleParameter) extends DVBundle[ExampleParameter, ExampleLayers](parameter)
class ExamplePorts(parameter: ExampleParameter) extends HWBundle(parameter):
  val clock = Flipped(Clock())
  val reset = Flipped(Reset())
  val request = Flipped(Bool())
  val ready = Aligned(Bool())
case class ExampleExternalParameters() extends VerilogParameter

@generator
object ExampleImported extends VerilogWrapper[ExampleParameter, ExampleLayers, ExamplePorts, ExampleProbe, ExampleExternalParameters]:
  def verilogModuleName(parameter: ExampleParameter) = "example_external"
  def verilogParameter(parameter: ExampleParameter) = ExampleExternalParameters()
  override def moduleName(parameter: ExampleParameter): String = verilogModuleName(parameter)

// A complete UT object, not an expression fragment. The goal is generic observed readiness,
// not a scenario or coverage answer for any benchmark. All behavioral state remains in external RTL.
@generator
object FrameworkUTExample extends Generator[ExampleParameter, ExampleLayers, ExamplePorts, ExampleProbe]
    with UT[ExampleParameter, ExamplePorts]:
  def architecture(parameter: ExampleParameter) =
    val io = summon[Interface[ExamplePorts]]
    val dut = ExampleImported.instantiate(parameter)
    dut.io.clock := io.clock
    dut.io.reset := io.reset
    dut.io.request := io.request
    io.ready := dut.io.ready
    given ClockEvent = posedge(io.clock)
    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.syncActiveHigh(io.reset)
    Gen(io.ready, "ready_observed")
    // An independent goal in the same UT, not a global environment constraint.
    Gen(io.request.S ### io.ready.S, "request_then_ready")
