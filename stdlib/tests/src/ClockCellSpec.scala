// SPDX-License-Identifier: Apache-2.0
package me.jiuyang.stdlib.clock

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import utest.*

case class ClockCellSpecParameter(
  buffer:   ClockBufferCellParameter = ClockBufferCellParameter(),
  inverter: ClockInverterCellParameter = ClockInverterCellParameter(),
  or:       ClockOrCellParameter = ClockOrCellParameter(),
  mux:      ClockMuxCellParameter = ClockMuxCellParameter(),
  xor:      ClockXorCellParameter = ClockXorCellParameter(),
  gate:     ClockGateTechnologyMapping = ClockGateTechnologyMapping())
    extends Parameter
given upickle.default.ReadWriter[ClockCellSpecParameter] = upickle.default.macroRW
class ClockCellSpecLayers(parameter: ClockCellSpecParameter) extends LayerInterface(parameter):
  def layers = Seq.empty
class ClockCellSpecProbe(parameter: ClockCellSpecParameter)
    extends DVBundle[ClockCellSpecParameter, ClockCellSpecLayers](parameter)

class ClockCellSpecIO(parameter: ClockCellSpecParameter) extends HWBundle(parameter):
  val a       = Flipped(Clock())
  val b       = Flipped(Clock())
  val select  = Flipped(Bool())
  val enable  = Flipped(Bool())
  val outputs = Aligned(Vec(7, Clock()))

@generator
object ClockCellSpecTop
    extends Generator[ClockCellSpecParameter, ClockCellSpecLayers, ClockCellSpecIO, ClockCellSpecProbe]:
  def architecture(parameter: ClockCellSpecParameter) =
    val io     = summon[Interface[ClockCellSpecIO]]
    val buffer = ClockBufferCell.instantiate(parameter.buffer)
    buffer.io.a   := io.a
    io.outputs(0) := buffer.io.outClock
    val inverter = ClockInverterCell.instantiate(parameter.inverter)
    inverter.io.a := io.a
    io.outputs(1) := inverter.io.outClock
    val or = ClockOrCell.instantiate(parameter.or)
    or.io.a       := io.a
    or.io.b       := io.b
    io.outputs(2) := or.io.outClock
    val mux = ClockMuxCell.instantiate(parameter.mux)
    mux.io.a      := io.a
    mux.io.b      := io.b
    mux.io.select := io.select
    io.outputs(3) := mux.io.outClock
    val xor = ClockXorCell.instantiate(parameter.xor)
    xor.io.a      := io.a
    xor.io.b      := io.b
    io.outputs(4) := xor.io.outClock
    val positive = ClockGateCell.instantiate(parameter.gate.cell(true))
    positive.io.a      := io.a
    positive.io.enable := io.enable
    io.outputs(5)      := positive.io.outClock
    val negative = ClockGateCell.instantiate(parameter.gate.cell(false))
    negative.io.a      := io.a
    negative.io.enable := io.enable
    io.outputs(6)      := negative.io.outClock

object ClockCellTest:
  val tools = os.Path(sys.env("CIRCT_INSTALL_PATH")) / "bin"
  def elaborate[P <: Parameter, L <: LayerInterface[P], I <: HWInterface[P], D <: DVInterface[P, L]](
    generator: Generator[P, L, I, D],
    parameter: P
  ): String =
    import java.lang.foreign.Arena
    import org.llvm.mlir.scalalib.capi.ir.{Context, ContextApi, given}
    import org.llvm.circt.scalalib.capi.dialect.firrtl.{DialectApi as FIRRTL, given}
    import org.llvm.circt.scalalib.capi.dialect.ltl.{DialectApi as LTL, given}
    import org.llvm.circt.scalalib.capi.dialect.verif.{DialectApi as VERIF, given}
    val arena = Arena.ofConfined()
    try
      given Arena = arena
      val context = summon[ContextApi].contextCreate
      try
        given Context = context
        summon[FIRRTL].loadDialect
        summon[LTL].loadDialect
        summon[VERIF].loadDialect
        val linked    = Elaborate(generator, parameter)
        try
          assert(linked.getOperation.verify)
          val text = new StringBuilder
          linked.getOperation.print(text ++= _)
          text.toString
        finally linked.destroy()
      finally context.destroy()
    finally arena.close()
  def rtl(linked: String): String =
    os.proc(tools / "firtool", "--format=mlir", "--strip-debug-info", "--verilog")
      .call(stdin = linked, stderr = os.Pipe, timeout = 30000)
      .out
      .text()

object ClockCellSpec extends TestSuite:
  private def rtl(parameter: ClockCellSpecParameter): String =
    ClockCellTest.rtl(ClockCellTest.elaborate(ClockCellSpecTop, parameter))

  val tests = Tests:
    test("distinct generators and both gate phases elaborate together with fixed interfaces"):
      val verilog = rtl(ClockCellSpecParameter())
      for kind <- Seq("Buffer", "Inverter", "Or", "Mux", "Xor", "Gate") do
        assert(verilog.contains(s"targets$$Clock${kind}ChoiceIO$$Behavior"))
      assert(!verilog.contains("ZaoziClock"))
    test("configured technology choices are emitted for every cell type"):
      val library        =
        """
          |module CKBUF(input I, output Z); assign Z = I; endmodule
          |module CKINV(input I, output ZN); assign ZN = ~I; endmodule
          |module CKOR(input I0, I1, output Z); assign Z = I0 | I1; endmodule
          |module CKMUX(input I0, I1, S, output Z); assign Z = S ? I1 : I0; endmodule
          |module CKXOR(input I0, I1, output Z); assign Z = I0 ^ I1; endmodule
          |module ICGP(input CK, E, output Q); reg held; always @* if (!CK) held = E; assign Q = CK & held; endmodule
          |module ICGN(input CK, E, output Q); reg held; always @* if (CK) held = !E; assign Q = CK | held; endmodule
          |""".stripMargin
      val librarySources = Map("fab-cells.sv" -> library)
      val cells          = ClockCellSpecParameter(
        buffer =
          ClockBufferCellParameter(Some(ClockBufferTechnologyParameter("CKBUF", "I", "Z", sources = librarySources))),
        inverter = ClockInverterCellParameter(
          Some(ClockInverterTechnologyParameter("CKINV", "I", "ZN", sources = librarySources))
        ),
        or = ClockOrCellParameter(Some(ClockOrTechnologyParameter("CKOR", "I0", "I1", "Z", sources = librarySources))),
        mux = ClockMuxCellParameter(
          Some(ClockMuxTechnologyParameter("CKMUX", "I0", "I1", "S", "Z", sources = librarySources))
        ),
        xor =
          ClockXorCellParameter(Some(ClockXorTechnologyParameter("CKXOR", "I0", "I1", "Z", sources = librarySources))),
        gate = ClockGateTechnologyMapping(
          positive = Some(ClockGateTechnologyParameter("ICGP", "CK", "E", "Q", sources = librarySources)),
          negative = Some(ClockGateTechnologyParameter("ICGN", "CK", "E", "Q", sources = librarySources))
        )
      )
      val verilog        = rtl(cells)
      for kind <- Seq("Buffer", "Inverter", "Or", "Mux", "Xor", "Gate") do
        assert(verilog.contains(s"targets$$Clock${kind}ChoiceIO$$Technology"))
      val roundTrip      = upickle.default.read[ClockCellSpecParameter](upickle.default.write(cells))
      assert(roundTrip == cells)
      val directory      = os.Path(sys.env.getOrElse("ZAOZI_OUTDIR", ""), os.pwd)
      librarySources.foreach((name, source) => assert(os.read(directory / name) == source))
      val sources        = Seq("Buffer", "Inverter", "Or", "Mux", "Xor", "GatePositive", "GateNegative")
        .map(kind => os.read(directory / s"Clock${kind}Behavior.sv")) :+ os.read(directory / "fab-cells.sv")
      for selection <- Seq("Behavior", "Technology") do
        val defines  = Seq("Buffer", "Inverter", "Or", "Mux", "Xor", "Gate")
          .map(kind => s"-Dtargets$$Clock${kind}ChoiceIO$$$selection")
        val imported = os
          .proc(
            ClockCellTest.tools / "circt-verilog",
            "--format=sv",
            "--ir-hw",
            s"--top=${ClockCellSpecTop.moduleName(cells)}",
            defines,
            "-"
          )
          .call(stdin = verilog + sources.mkString("\n"), stderr = os.Pipe, timeout = 30000)
          .out
          .text()
        assert(imported.contains("hw.module"))
        for name <- Seq("CKBUF", "ICGP", "ICGN") do assert(imported.contains(name) == (selection == "Technology"))
    test("clock tree uses the configured technology cells throughout its hierarchy"):
      val cells     = ClockCellSpecParameter(
        inverter = ClockInverterCellParameter(Some(ClockInverterTechnologyParameter("TREE_INV"))),
        or = ClockOrCellParameter(Some(ClockOrTechnologyParameter("TREE_OR"))),
        mux = ClockMuxCellParameter(Some(ClockMuxTechnologyParameter("TREE_MUX"))),
        xor = ClockXorCellParameter(Some(ClockXorTechnologyParameter("TREE_XOR"))),
        gate = ClockGateTechnologyMapping(positive = Some(ClockGateTechnologyParameter("TREE_ICG")))
      )
      val parameter = ClockTreeParameter(
        Seq("a", "b"),
        Seq(
          ClockTarget(
            "out",
            Seq(ClockLink("a"), ClockLink("b")),
            selection = Some(ClockSelection.GlitchFree(2, false)),
            path = ClockPath(
              gate = Some(ClockPathGate(true, false)),
              divider = Some(ClockPathDivider(3, 3, false, false)),
              invert = true
            )
          )
        ),
        gate = cells.gate,
        mux = cells.mux,
        or = cells.or,
        xor = cells.xor,
        inverter = cells.inverter
      )
      val hardware  = ClockCellTest.rtl(ClockCellTest.elaborate(ClockTree, parameter))
      for prefix <- Seq("Inverter", "Or", "Mux", "Xor", "Gate") do
        assert(hardware.contains(s"targets$$Clock${prefix}ChoiceIO$$Technology"))
