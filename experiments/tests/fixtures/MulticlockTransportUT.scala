// Synthetic transport regression only; never included in benchmark prompts/RAG.
import me.jiuyang.utlib.*
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

@generator
object MulticlockTransportUT extends Generator[RunParameter, RunLayers, RunIO, RunProbe] with UT[RunParameter, RunIO]:
  override def moduleName(parameter: RunParameter): String = "MulticlockTransportUT"
  def architecture(parameter: RunParameter) =
    val io = summon[Interface[RunIO]]
    val dut = ImportedDut.instantiate(parameter)
    dut.io.clock := io.clock
    dut.io.reset := io.reset.asBool
    dut.io.c2 := io.c2
    dut.io.x := io.x
    io.a := dut.io.a
    io.b := dut.io.b
    given ClockEvent = posedge(io.clock)
    given ClockScope = ClockScope.posedge(io.clock)
    given ResetScope = ResetScope.syncActiveHigh(io.reset)
    Gen((io.a === BigInt(5).B(8)) & (io.b.asUInt >= BigInt(5).U(8)), "counts")
