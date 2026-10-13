// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
// RUN: scala-cli run %s --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS | FileCheck %s

import me.jiuyang.zaozi.sv.*
import me.jiuyang.zaozi.sv.valuetpe.{Bits, SVString, PackedArray, UnpackedArray, UnpackedOpenArray, TypeAlias, Enum, NamedType}
import me.jiuyang.zaozi.sv.reftpe.{Node, Ref}
import me.jiuyang.zaozi.sv.default.{*, given}

@generator
object LanguageExample extends SVGenerator:
  object Parameters extends VerilogParameterInterface
  class FIRRTLIO(p: FIRRTLBindings) extends me.jiuyang.zaozi.HWBundle(p)
  object Bus extends VerilogInterface:
    val data: InterfaceSignal[Bits] = Signal(Bits(8))
    val valid: InterfaceSignal[Bits] = Signal(Bits(1))
    val Producer = Modport(data.output, valid.output)
    val Consumer = Modport(data.input, valid.input)
  object Types:
    object State extends Enum with NamedType:
      val Idle = Value()
      val Busy = Value()
  object Macros extends VerilogMacroInterface:
    val FEATURE: MacroRef = VerilogMacro()
    val IDENTITY: MacroRef = VerilogFunctionMacro("x")
    val ZERO: MacroRef = VerilogFunctionMacro()
    val MARK: MacroRef = VerilogMacro()
  object IO extends SVBundle:
    val clock: Port[Bits] = Flipped(Bits(1))
    val reset: Port[Bits] = Flipped(Bits(1))
    val data: Port[Bits] = Flipped(Bits(8))
    val select: Port[Bits] = Flipped(Bits(2))
    val result: Port[Bits] = Aligned(Bits(8))
    val busResult: Port[Bits] = Aligned(Bits(8))
  def architecture =
    val io: SVPorts = summon[SVPorts]
    val state: Ref[Bits] = Variable(Bits(8))
    val eventState: Ref[Bits] = Variable(Bits(8))
    val selectedState: Ref[Bits] = Variable(Bits(8))
    val registered: Ref[Bits] = Variable(Bits(8))
    val unknown: Node[Bits] = X(Bits(8))
    val floating: Node[Bits] = Z(Bits(8))
    val low: Ref[Bits] = state.ref(3, 0)
    val high: Node[Bits] = io.data.bits(BigInt(7).B(32), 4, decrement = true)
    val packed: Ref[PackedArray[Bits]] = Variable(PackedArray(2, Bits(8)))
    packed := PackedArrayValue(packed.getType, Seq(BigInt(17).B(8), BigInt(34).B(8)))
    val unpacked: Ref[UnpackedArray[Bits]] = Variable(UnpackedArray(2, Bits(8)))
    val arrayValue: Node[UnpackedArray[Bits]] = UnpackedArrayValue(unpacked.getType, Seq(BigInt(51).B(8), BigInt(68).B(8)))
    val open: Node[UnpackedOpenArray[Bits]] = unpacked.asOpenArray
    verbatim("// open array: {{0}}", Seq(open))

    Macros.IDENTITY.define("(x)")
    Macros.ZERO.define("8'h00")
    Macros.MARK.define("/* macro statement */")
    Macros.MARK.call()
    val identity: Node[Bits] = Macros.IDENTITY.expr(Bits(8))(io.data)
    val zero: Node[Bits] = Macros.ZERO.expr(Bits(8))()
    val raw: Node[Bits] = verbatimExpr(Bits(8), "({{0}} ^ {{1}})", Seq(identity, zero))
    io.result := raw
    // Source interpolation preserves native value and symbol references.
    verbatim("// module symbol: {{0}}", symbols = Seq(VerbatimSymbol("LanguageExample")))
    ifdef(Macros.FEATURE):
      include("feature.svh")
    .otherwise:
      macroError("FEATURE must be supplied")

    initial:
      val prefix = concatStrings("value=".S, "0x".S)
      val message = formatString("%s%h", prefix, io.data)
      verbatim("string message = {{0}};", Seq(message))
      unpacked := arrayValue
      state := zero
      // Inside a procedural region this emits sv.ifdef.procedural.
      ifdef(Macros.FEATURE):
        low := high
      .otherwise:
        state := unknown
      val evaluated: Node[Bits] = Macros.IDENTITY.expr(Bits(8), sideEffects = true)(io.data)
      val evaluatedRaw: Node[Bits] = verbatimExpr(Bits(8), "{{0}}", Seq(evaluated), sideEffects = true)
      state := evaluatedRaw
      val index: Unit = forLoop(BigInt(0).B(32), BigInt(2).B(32), BigInt(1).B(32)): i =>
        unpacked(i) := BigInt(0).B(8)
    always(Event(io.clock, EventControl.PosEdge), Event(io.reset, EventControl.NegEdge)):
      svIf(io.reset):
        eventState :== io.data
      .otherwise:
        eventState :== floating
    alwaysComb:
      selectedState := zero
      svCase(io.select, CaseStyle.CaseZ, ValidationQualifier.Unique):
        caseOf(CasePattern.Bits("0z")):
          selectedState.ref(3, 0) := high
        caseOf(CasePattern.Expression(BigInt(2).B(2))):
          selectedState := io.data
        caseOf(CasePattern.Default):
          selectedState := zero
    val enumState = Variable(Types.State)
    enumState := Types.State.Idle
    initial:
      svCase(enumState):
        caseOf(CasePattern.EnumMember(Types.State.Idle)):
          verbatim("// idle state")
        caseOf(CasePattern.Default):
          verbatim("// other state")
    alwaysFF(Event(io.clock), Event(io.reset, EventControl.NegEdge), ResetStyle.AsyncReset) {
      registered :== io.data
    } {
      registered :== zero
    }
    val bus = InterfaceInstance(Bus)
    bus.data := io.data
    val observed: Ref[Bits] = Variable(Bits(8))
    observed := bus.data
    val producer = bus.Producer
    // Modport members use the same typed signal declarations.
    // Producer access on an instance unwraps the modport selection.
    verbatim("// producer member: {{0}}", Seq(producer.valid))
    val writer = InterfaceWriter.instantiateSV(connections = Seq(InterfaceWriter.IO.bus.bind(producer), InterfaceWriter.IO.valid.bind(io.reset)))
    val consumer = bus.Consumer
    verbatim("// modport: {{0}}", Seq(consumer))
    val child = InterfaceReader.instantiateSV(connections = Seq(InterfaceReader.IO.bus.bind(consumer)))
    io.busResult := child.io.data

@generator
object InterfaceReader extends SVGenerator:
  object Macros extends VerilogMacroInterface
  object Parameters extends VerilogParameterInterface
  class FIRRTLIO(p: FIRRTLBindings) extends me.jiuyang.zaozi.HWBundle(p)
  object IO extends SVBundle:
    val bus = Flipped(LanguageExample.Bus.Consumer)
    val data: Port[Bits] = Aligned(Bits(8))
  def architecture =
    val io: SVPorts = summon[SVPorts]
    io.data := io.bus.data

@generator
object InterfaceWriter extends SVGenerator:
  object Macros extends VerilogMacroInterface
  object Parameters extends VerilogParameterInterface
  class FIRRTLIO(p: FIRRTLBindings) extends me.jiuyang.zaozi.HWBundle(p)
  object IO extends SVBundle:
    val bus = Flipped(LanguageExample.Bus.Producer)
    val valid: Port[Bits] = Flipped(Bits(1))
  def architecture =
    val io: SVPorts = summon[SVPorts]
    io.bus.valid := io.valid

@generator
object RawCell extends SVGenerator:
  object Macros extends VerilogMacroInterface
  object Parameters extends VerilogParameterInterface:
    val WIDTH: ParameterField = VerilogParameter(8)
  class FIRRTLIO(p: FIRRTLBindings) extends me.jiuyang.zaozi.HWBundle(p)
  object IO extends SVBundle:
    private val p = summon[SVParameters]
    val data: Port[Bits] = Flipped(Bits(p.WIDTH))
    val result: Port[Bits] = Aligned(Bits(p.WIDTH))
  override def source = Some(VerilogSource(
    "module RawCell #(parameter WIDTH = 8)(input [WIDTH-1:0] data, output [WIDTH-1:0] result); assign result = data; endmodule",
    "RawCell.sv"))
  def architecture = ()

print(LanguageExample.verilogString())
print(InterfaceReader.verilogString())
print(InterfaceWriter.verilogString())
print(RawCell.verilogString())

// Each definition remains a separate bytecode artifact, including original source text.
val artifacts = os.temp.dir(prefix = "zaozi-sv-language-")
try
  LanguageExample.main(Array("design", (artifacts / "LanguageExample.mlirbc").toString))
  InterfaceReader.main(Array("design", (artifacts / "InterfaceReader.mlirbc").toString))
  InterfaceWriter.main(Array("design", (artifacts / "InterfaceWriter.mlirbc").toString))
  RawCell.main(Array("design", (artifacts / "RawCell.mlirbc").toString))
  import java.lang.foreign.Arena
  import org.llvm.mlir.scalalib.capi.ir.{ModuleApi, ContextApi, Context, given}
  import org.llvm.circt.scalalib.capi.exportverilog.given
  import org.llvm.mlir.scalalib.capi.support.given
  given Arena = Arena.ofConfined()
  given Context = summon[ContextApi].contextCreate
  try
    org.llvm.circt.scalalib.capi.dialect.hw.given_DialectApi.loadDialect
    org.llvm.circt.scalalib.capi.dialect.sv.given_DialectApi.loadDialect
    for name <- Seq("LanguageExample", "InterfaceReader", "InterfaceWriter", "RawCell") do
      val module = summon[ModuleApi].moduleCreateParse(os.read.bytes(artifacts / s"$name.mlirbc"))
      try
        assert(module.getOperation.verify)
        val ir = StringBuilder()
        module.getOperation.print(ir.append(_))
        if name == "RawCell" then
          assert(ir.toString.contains("sv.verbatim.module"))
          assert(ir.toString.contains("sv.verbatim.source"))
        else assert(ir.toString.linesIterator.count(_.trim.startsWith("hw.module @")) == 1)
        val sv = StringBuilder()
        assert(module.exportVerilog(sv.append(_)).succeeded)
        assert(sv.toString.contains(s"module $name"))
      finally module.getOperation.destroy()
  finally
    summon[Context].destroy()
    summon[Arena].close()
finally os.remove.all(artifacts)

// CHECK: typedef enum bit [0:0] {State_Idle, State_Busy} State;
// CHECK: module LanguageExample
// CHECK: data[32'h7 -: 4]
// CHECK: assign packed_0 = {{.*}}8'h22{{.*}}8'h11
// CHECK: // open array: unpacked
// CHECK: `define IDENTITY(x) (x)
// CHECK: `define ZERO() 8'h00
// CHECK: `define MARK
// CHECK: `MARK
// CHECK: assign result_out = (`IDENTITY(data) ^ `ZERO());
// CHECK: // module symbol: LanguageExample
// CHECK: `ifdef FEATURE
// CHECK: `include "feature.svh"
// CHECK: `_ERROR_FEATURE_must_be_supplied
// CHECK: initial begin
// CHECK: string message = $sformatf("%s%h", {"value=", "0x"}, data);
// CHECK: unpacked = '{8'h33, 8'h44};
// CHECK: `ifdef FEATURE
// CHECK: state[32'h0 +: 4] =
// CHECK: state = 8'bx;
// CHECK: state = `IDENTITY(data);
// CHECK: for (logic [31:0] index = 32'h0; index < 32'h2; index += 32'h1)
// CHECK: unpacked[index] = 8'h0;
// CHECK: always @(posedge clock or negedge reset)
// CHECK: if (reset)
// CHECK: eventState <= data;
// CHECK: else
// CHECK: eventState <= 8'bz;
// CHECK: always_comb
// CHECK: unique casez (select)
// CHECK: 2'b0z:
// CHECK: 2'h2:
// CHECK: default:
// CHECK: case (enumState)
// CHECK: State_Idle:
// CHECK: always_ff @(posedge clock or negedge reset)
// CHECK: if (!reset)
// CHECK: registered <= `ZERO();
// CHECK: registered <= data;
// CHECK: Bus bus();
// CHECK: assign bus.data = data;
// CHECK: assign observed = bus.data;
// CHECK: // producer member: bus.valid
// CHECK: InterfaceWriter writer
// CHECK: .bus {{ *}}(bus.Producer)
// CHECK: .valid {{ *}}(reset)
// CHECK: InterfaceReader child
// CHECK: .bus {{ *}}(bus.Consumer)
// CHECK: interface Bus;
// CHECK: modport Producer(output data, output valid);
// CHECK: modport Consumer(input data, input valid);
// CHECK: module InterfaceReader
// CHECK: Bus.Consumer {{ *}}bus
// CHECK: assign data_out = bus.data;
// CHECK: module InterfaceWriter
// CHECK: Bus.Producer {{ *}}bus
// CHECK: assign bus.valid = valid;
// CHECK: FILE "RawCell.sv"
// CHECK: module RawCell #(parameter WIDTH = 8)
