// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
// RUN: scala-cli run %s --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS | FileCheck %s

import me.jiuyang.zaozi.{Parameter, HWBundle, Generator}
import me.jiuyang.zaozi.default.given_GeneratorApi
import me.jiuyang.zaozi.valuetpe.{Bits as FIRRTLBitsType, BundleField}
import me.jiuyang.zaozi.default.{Bits as FIRRTLBits, given_TypeImpl as firrtlTypes}
import me.jiuyang.zaozi.sv.*
import me.jiuyang.zaozi.sv.valuetpe.{Bits, Enum, NamedType, TypeAlias, Struct, Union, Field, PackedArray, UnpackedArray}
import me.jiuyang.zaozi.sv.reftpe.{Const, Ref, Node, FIRRTLInstance}
import me.jiuyang.zaozi.sv.default.{*, given}

@generator
object Example extends SVGenerator:
  object Parameters extends VerilogParameterInterface:
    val WIDTH: ParameterField = VerilogParameter(8)
    val DEPTH: ParameterField = VerilogParameter(16)
    val MODE: ParameterField = VerilogParameter(0)
    val LANES: ParameterField = VerilogParameter(4)

  object Macros extends VerilogMacroInterface:
    val USE_DOUBLE: MacroRef = VerilogMacro()

  class FIRRTLIO(bindings: FIRRTLBindings) extends HWBundle(bindings):
    val data: BundleField[FIRRTLBitsType] = Flipped(FIRRTLBits(bindings.parameters.WIDTH))
    val offset: BundleField[FIRRTLBitsType] = Flipped(FIRRTLBits(32))
    val result: BundleField[FIRRTLBitsType] = Aligned(FIRRTLBits(bindings.parameters.WIDTH))
    val widthValue: BundleField[FIRRTLBitsType] = Aligned(FIRRTLBits(32))
    val addressBits: BundleField[FIRRTLBitsType] = Aligned(FIRRTLBits(32))

  object IO extends SVBundle:
    private val parameters: SVParameters = summon[SVParameters]
    val data: Port[Bits] = Flipped(Bits(parameters.WIDTH))
    val offset: Port[Bits] = Flipped(Bits(32))
    val result: Port[Bits] = Aligned(Bits(parameters.WIDTH))
    val widthValue: Port[Bits] = Aligned(Bits(32))
    val addressBits: Port[Bits] = Aligned(Bits(32))

  object Types:
    object Header extends Struct:
      val tag: Field[Bits] = Field(Bits(8))
      val flags: Field[Bits] = Field(Bits(8))
    object Packet extends Struct with NamedType:
      val header = Field(Header)
      val payload: Field[Bits] = Field(Bits(32))
    object WordViews extends Union with NamedType:
      val word: Field[Bits] = Field(Bits(32))
      val bytes: Field[PackedArray[Bits]] = Field(PackedArray(4, Bits(8)))
    object State extends Enum with NamedType:
      val Idle = Value()
      val Busy = Value()
      val Done = Value()

  def architecture =
    import Example.Types.*
    val io: SVPorts = summon[SVPorts]
    val parameters: SVParameters = summon[SVParameters]
    val width: ParameterRef = parameters.WIDTH
    val capacityExpr: ConstExpr = width * parameters.DEPTH
    val capacity: Const[Bits] = LocalParam(capacityExpr)
    val offset: Ref[Bits] = io.offset
    val adjusted: Node[Bits] = width + offset
    val header = StructValue(Header, Seq(
      Header.tag.bind(BigInt(1).B(8)), Header.flags.bind(BigInt(0).B(8))))
    val initialPacket = StructValue(Packet, Seq(
      Packet.header.bind(header), Packet.payload.bind(adjusted)))
    val packet = Variable(Packet)
    packet := initialPacket
    val tagCopy: Ref[Bits] = Variable(Bits(8))
    tagCopy := initialPacket.header.tag
    val payload: Ref[Bits] = packet.payload
    io.widthValue := payload

    val packedWord = UnionValue(WordViews, WordViews.word, offset)
    val word = Variable(WordViews)
    word := packedWord
    val byte: Node[Bits] = word.bytes(BigInt(1).B(2))
    val selectedByte: Ref[Bits] = Variable(Bits(8))
    selectedByte := byte

    // Each iteration is elaborated by Verilog and owns its own variables.
    // The Scala body is built once, with a native genvar reference.
    val lanes: Unit = generateFor(ConstExpr(0), parameters.LANES): (index: Node[Bits]) =>
      val lanePacket = Variable(Packet)
      lanePacket.header.tag := BigInt(2).B(8)
      lanePacket.header.flags := BigInt(0).B(8)
      lanePacket.payload := offset + index
      val history: Ref[UnpackedArray[Bits]] = Variable(UnpackedArray(2, Bits(32)))
      history(BigInt(0).B(32)) := lanePacket.payload
      history(BigInt(1).B(32)) := index
      val previous: Ref[Bits] = history(BigInt(0).B(32))
      val copy: Ref[Bits] = Variable(Bits(32))
      copy := previous
    val addressWidth: Const[Bits] = LocalParam(parameters.DEPTH.clog2 + ConstExpr(1))
    io.addressBits := addressWidth

    val state = Variable(State)
    val idle = State.Idle
    state := idle

    // Verilog elaboration selects the case; all branches remain in the exported SV.
    val implementation: Unit = generateCase(parameters.MODE):
      val direct: Unit = is(ConstExpr(0)):
        io.result := io.data
      val configured: Unit = defaultCase:
        // Verilog preprocessing selects this branch, independently of MODE.
        ifdef(Macros.USE_DOUBLE):
          io.result := io.data + io.data
        .otherwise:
          io.result := io.data

// Parent-to-child bindings remain symbolic. Example is emitted once with its defaults.
@generator
object Hierarchy extends SVGenerator:
  object Macros extends VerilogMacroInterface
  object Parameters extends VerilogParameterInterface:
    val WIDTH: ParameterField = VerilogParameter(8)
    val DEPTH: ParameterField = VerilogParameter(16)
    val MODE: ParameterField = VerilogParameter(0)
    val LANES: ParameterField = VerilogParameter(4)

  class FIRRTLIO(bindings: FIRRTLBindings) extends HWBundle(bindings):
    val data: BundleField[FIRRTLBitsType] = Flipped(FIRRTLBits(bindings.parameters.WIDTH))
    val offset: BundleField[FIRRTLBitsType] = Flipped(FIRRTLBits(32))
    val result: BundleField[FIRRTLBitsType] = Aligned(FIRRTLBits(bindings.parameters.WIDTH))
    val widthValue: BundleField[FIRRTLBitsType] = Aligned(FIRRTLBits(32))
    val addressBits: BundleField[FIRRTLBitsType] = Aligned(FIRRTLBits(32))

  object IO extends SVBundle:
    private val parameters: SVParameters = summon[SVParameters]
    val data: Port[Bits] = Flipped(Bits(parameters.WIDTH))
    val offset: Port[Bits] = Flipped(Bits(32))
    val result: Port[Bits] = Aligned(Bits(parameters.WIDTH))
    val widthValue: Port[Bits] = Aligned(Bits(32))
    val addressBits: Port[Bits] = Aligned(Bits(32))

  def architecture =
    val io: SVPorts = summon[SVPorts]
    val parameters: SVParameters = summon[SVParameters]
    val child = Example.instantiateSV(p =>
      Seq(p.WIDTH.bind(parameters.WIDTH), p.DEPTH.bind(parameters.DEPTH),
        p.MODE.bind(parameters.MODE), p.LANES.bind(parameters.LANES)))
    child.io.data := io.data
    child.io.offset := io.offset
    io.result := child.io.result
    io.widthValue := child.io.widthValue
    io.addressBits := child.io.addressBits

// Scala Parameter remains part of the FIRRTL generator only.
case class TopParameter(width: Int = 12) extends Parameter
object TopParameter:
  given upickle.default.ReadWriter[TopParameter] = upickle.default.macroRW
class TopIO(parameter: TopParameter) extends HWBundle(parameter):
  val data: BundleField[FIRRTLBitsType] = Flipped(FIRRTLBits(parameter.width))
  val narrowData: BundleField[FIRRTLBitsType] = Flipped(FIRRTLBits(8))
  val offset: BundleField[FIRRTLBitsType] = Flipped(FIRRTLBits(32))
  val result: BundleField[FIRRTLBitsType] = Aligned(FIRRTLBits(parameter.width))
  val narrowResult: BundleField[FIRRTLBitsType] = Aligned(FIRRTLBits(8))
  val widthValue: BundleField[FIRRTLBitsType] = Aligned(FIRRTLBits(32))
  val addressBits: BundleField[FIRRTLBitsType] = Aligned(FIRRTLBits(32))
class TopLayers(parameter: TopParameter) extends me.jiuyang.zaozi.LayerInterface(parameter):
  def layers = Seq.empty
class TopProbe(parameter: TopParameter) extends me.jiuyang.zaozi.DVBundle[TopParameter, TopLayers](parameter)

@generator
object FIRRTLTop extends Generator[TopParameter, TopLayers, TopIO, TopProbe]:
  import me.jiuyang.zaozi.default.{*, given}
  def architecture(parameter: TopParameter) =
    val io: me.jiuyang.zaozi.reftpe.Interface[TopIO] = summon[me.jiuyang.zaozi.reftpe.Interface[TopIO]]
    val child: FIRRTLInstance[Example.FIRRTLIO] = Example.instantiate(p =>
      Seq(p.WIDTH.bind(ConstExpr(parameter.width)), p.MODE.bind(ConstExpr(1))))
    child.io.data := io.data
    child.io.offset := io.offset
    io.result := child.io.result
    io.widthValue := child.io.widthValue
    io.addressBits := child.io.addressBits
    // A second binding reuses the same SV definition, including its macro declaration.
    val narrow: FIRRTLInstance[Example.FIRRTLIO] = Example.instantiate()
    narrow.io.data := io.narrowData
    narrow.io.offset := io.offset
    io.narrowResult := narrow.io.result

val declaredIO = Example.IO
val declaredParameters = Example.Parameters
assert(Example.svInterface eq declaredIO)
assert(Example.verilogParameters eq declaredParameters)
print(Example.verilogString())
val parentSV = Hierarchy.verilogString()
assert(!parentSV.contains("module Example"))
print(parentSV)

// Build separate artifacts explicitly. Instantiating a dependency never emits its body.
val artifacts = os.temp.dir(prefix = "zaozi-sv-artifacts-")
try
  Example.main(Array("design", (artifacts / "Example.mlirbc").toString))
  Hierarchy.main(Array("design", (artifacts / "Hierarchy.mlirbc").toString))
  import java.lang.foreign.Arena
  import org.llvm.mlir.scalalib.capi.ir.{ModuleApi, ContextApi, Context, LocationApi, given}
  given Arena = Arena.ofConfined()
  given Context = summon[ContextApi].contextCreate
  try
    org.llvm.circt.scalalib.capi.dialect.hw.given_DialectApi.loadDialect
    org.llvm.circt.scalalib.capi.dialect.sv.given_DialectApi.loadDialect
    org.llvm.circt.scalalib.capi.dialect.firrtl.given_DialectApi.loadDialect
    org.llvm.circt.scalalib.capi.dialect.ltl.given_DialectApi.loadDialect
    org.llvm.circt.scalalib.capi.dialect.verif.given_DialectApi.loadDialect
    for name <- Seq("Example", "Hierarchy") do
      val module = summon[ModuleApi].moduleCreateParse(os.read.bytes(artifacts / s"$name.mlirbc"))
      try
        assert(module.getOperation.verify)
        val ir = StringBuilder()
        module.getOperation.print(ir.append(_))
        assert(ir.toString.linesIterator.count(_.trim.startsWith("hw.module @")) == 1)
        if name == "Hierarchy" then assert(ir.toString.contains("hw.module.extern @Example"))
      finally module.getOperation.destroy()
    // FIRRTL retains concrete import declarations; external tooling links/lowers later.
    import org.llvm.circt.scalalib.dialect.firrtl.operation.{Circuit, CircuitApi, given}
    val module = summon[ModuleApi].moduleCreateEmpty(summon[LocationApi].locationUnknownGet)
    try
      given Circuit = summon[CircuitApi].op(FIRRTLTop.moduleName(TopParameter()))
      summon[Circuit].appendToModule()(using summon[Arena], module)
      FIRRTLTop.module(TopParameter()).appendToCircuit()
      me.jiuyang.zaozi.magic.validateCircuit()
      assert(module.getOperation.verify)
      val stream = os.write.outputStream(artifacts / "FIRRTLTop.mlirbc")
      try module.getOperation.writeBytecode(stream.write(_))
      finally stream.close()
      val saved = summon[ModuleApi].moduleCreateParse(os.read.bytes(artifacts / "FIRRTLTop.mlirbc"))
      try
        assert(saved.getOperation.verify)
        val ir = StringBuilder()
        saved.getOperation.print(ir.append(_))
        assert(!ir.toString.contains("hw.module"))
        assert(ir.toString.linesIterator.count(_.trim.startsWith("firrtl.extmodule")) == 2)
        print(ir.toString)
      finally saved.getOperation.destroy()
    finally module.getOperation.destroy()
  finally
    summon[Context].destroy()
    summon[Arena].close()
finally os.remove.all(artifacts)
assert(Example.svInterface eq declaredIO)
assert(Example.verilogParameters eq declaredParameters)

// CHECK: struct packed {struct packed
// CHECK: Packet;
// CHECK: typedef union packed
// CHECK-SAME: WordViews;
// CHECK: typedef enum bit [1:0] {State_Idle, State_Busy, State_Done} State;
// CHECK: module Example
// CHECK: parameter WIDTH = 8
// CHECK: parameter DEPTH = 16
// CHECK: parameter MODE = 0
// CHECK: parameter LANES = 4
// CHECK: [WIDTH - 1:0] data
// CHECK-DAG: localparam {{.*}}capacity = {{WIDTH \* DEPTH|DEPTH \* WIDTH}};
// CHECK-DAG: assign widthValue_out = packet.payload;
// CHECK-DAG: assign selectedByte = word.bytes[2'h1];
// CHECK-DAG: for (genvar lanes_index = 0; lanes_index < LANES; lanes_index += 1) begin : lanes
// CHECK-DAG: assign lanePacket.header.tag = 8'h2;
// CHECK-DAG: assign lanePacket.payload = offset + lanes_index;
// CHECK-DAG: history[0:1];
// CHECK-DAG: assign history[32'h1] = lanes_index;
// CHECK-DAG: assign copy = history[32'h0];
// CHECK-DAG: localparam {{.*}}addressWidth = $clog2($unsigned(DEPTH)) + 1;
// CHECK: case (MODE)
// CHECK: direct
// CHECK: configured
// CHECK: `ifdef USE_DOUBLE
// CHECK: assign result_out = data + data;
// CHECK: `else
// CHECK: assign result_out = data;
// CHECK: `endif
// CHECK: endmodule
// CHECK: module Hierarchy
// CHECK: .WIDTH(WIDTH)
// CHECK: .DEPTH(DEPTH)
// CHECK: .MODE(MODE)
// CHECK: .LANES(LANES)
// CHECK-NOT: module Example
// CHECK: firrtl.circuit "FIRRTLTop_
// CHECK: firrtl.module @FIRRTLTop_
// CHECK: firrtl.extmodule @Example_firrtl_c_10_1_4
// CHECK: defname = "Example"
// CHECK: firrtl.extmodule @Example_firrtl_8_10_0_4
// CHECK: defname = "Example"
