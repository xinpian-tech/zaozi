// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
// RUN: scala-cli run %s --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS | FileCheck %s

import me.jiuyang.zaozi.sv.*
import me.jiuyang.zaozi.sv.valuetpe.{Bits, Struct, Field, TypeAlias, NamedType, PackedArray, UnpackedArray}
import me.jiuyang.zaozi.sv.reftpe.{Const, Ref}
import me.jiuyang.zaozi.sv.default.{*, given}

@generator
object InterfaceExample extends SVGenerator:
  object Parameters extends VerilogParameterInterface:
    val WIDTH: ParameterField = VerilogParameter(8)

  class FIRRTLIO(p: FIRRTLBindings) extends me.jiuyang.zaozi.HWBundle(p)

  object IO extends SVBundle:
    val request = Flipped(Types.Payload)
    val response = Aligned(Types.Payload)
    val header = Aligned(Types.Header)
    val macroData: Port[TypeAlias[Bits]] = Flipped(Types.MacroBits)
    val macroResult: Port[TypeAlias[Bits]] = Aligned(Types.MacroBits)

  object Macros extends VerilogMacroInterface:
    val EXTRA_WIDTH: MacroRef = VerilogMacro()

  object Types:
    private val p: SVParameters = summon[SVParameters]
    val Word: Bits = Bits(p.WIDTH)
    object Header extends Struct with NamedType:
      val tag: Field[Bits] = Field(Bits(8))
    val MacroBits: TypeAlias[Bits] = TypeDef(Bits(Macros.EXTRA_WIDTH.value + ConstExpr(1)))

    // Uses the generator's contextual symbolic parameter environment.
    // The same declaration is shared by ports, architecture and all instances.
    object Payload extends Struct:
      private val p: SVParameters = summon[SVParameters]
      val header = Field(Header)
      val data: Field[Bits] = Field(Bits(p.WIDTH + ConstExpr(1)))

  def architecture =
    val io: SVPorts = summon[SVPorts]
    val request = io.request
    val payload = Variable(Types.Payload)
    payload := request
    io.response.data := payload.data
    io.response.header := payload.header
    io.header := payload.header
    io.macroResult := io.macroData

@generator
object InterfaceHierarchy extends SVGenerator:
  object Macros extends VerilogMacroInterface
  object Parameters extends VerilogParameterInterface:
    val WIDTH: ParameterField = VerilogParameter(8)

  class FIRRTLIO(p: FIRRTLBindings) extends me.jiuyang.zaozi.HWBundle(p)

  // Reuse the child's stable data declarations, with this module's own IO object.
  val Types = InterfaceExample.Types
  object IO extends SVBundle:
    val request = Flipped(Types.Payload)
    val response = Aligned(Types.Payload)
    val header = Aligned(Types.Header)
    val macroData: Port[TypeAlias[Bits]] = Flipped(Types.MacroBits)
    val macroResult: Port[TypeAlias[Bits]] = Aligned(Types.MacroBits)

  def architecture =
    val io: SVPorts = summon[SVPorts]
    val parameters: SVParameters = summon[SVParameters]
    val child = InterfaceExample.instantiateSV(p => Seq(p.WIDTH.bind(parameters.WIDTH)))
    child.io.request := io.request
    child.io.macroData := io.macroData
    io.response := child.io.response
    io.header := child.io.header
    io.macroResult := child.io.macroResult

// Scalar ports exercise concrete bindings of a shared symbolic type description.
@generator
object ScalarExample extends SVGenerator:
  object Macros extends VerilogMacroInterface
  object Parameters extends VerilogParameterInterface:
    val WIDTH: ParameterField = VerilogParameter(8)
    val DEPTH: ParameterField = VerilogParameter(16)

  class FIRRTLIO(p: FIRRTLBindings) extends me.jiuyang.zaozi.HWBundle(p)

  object IO extends SVBundle:
    private val p: SVParameters = summon[SVParameters]
    // These are symbolic Verilog expressions, computed directly in the IO declaration.
    val doubleWidth: ConstExpr = p.WIDTH * ConstExpr(2)
    val addressWidth: ConstExpr = p.DEPTH.clog2
    val data: Port[Bits] = Flipped(InterfaceExample.Types.Word)
    val result: Port[Bits] = Aligned(InterfaceExample.Types.Word)
    val doubleData: Port[Bits] = Flipped(Bits(doubleWidth))
    val doubleResult: Port[Bits] = Aligned(Bits(doubleWidth))
    val address: Port[Bits] = Flipped(Bits(addressWidth))
    val addressResult: Port[Bits] = Aligned(Bits(addressWidth))

  def architecture =
    val io: SVPorts = summon[SVPorts]
    io.result := io.data
    io.doubleResult := io.doubleData
    io.addressResult := io.address

@generator
object ConcreteInstances extends SVGenerator:
  object Macros extends VerilogMacroInterface
  object Parameters extends VerilogParameterInterface:
    val WIDTH: ParameterField = VerilogParameter(8)

  class FIRRTLIO(p: FIRRTLBindings) extends me.jiuyang.zaozi.HWBundle(p)

  object IO extends SVBundle:
    val wide: Port[Bits] = Aligned(Bits(12))
    val narrow: Port[Bits] = Aligned(Bits(8))
    val wideDouble: Port[Bits] = Aligned(Bits(24))
    val narrowDouble: Port[Bits] = Aligned(Bits(16))
    val wideAddress: Port[Bits] = Aligned(Bits(5))
    val narrowAddress: Port[Bits] = Aligned(Bits(4))

  def architecture =
    val io: SVPorts = summon[SVPorts]
    val wide = ScalarExample.instantiateSV(p =>
      Seq(p.WIDTH.bind(ConstExpr(12)), p.DEPTH.bind(ConstExpr(32))))
    val narrow = ScalarExample.instantiateSV()
    assert(wide.io.data.width == 12)
    assert(narrow.io.data.width == 8)
    assert(wide.io.doubleData.width == 24)
    assert(narrow.io.doubleData.width == 16)
    assert(wide.io.getType eq ScalarExample.IO)
    assert(narrow.io.getType eq ScalarExample.IO)
    assert(wide.parameters.getType eq ScalarExample.Parameters)
    assert(narrow.parameters.getType eq ScalarExample.Parameters)
    assert(wide.io.getType.data.dataType eq InterfaceExample.Types.Word)
    assert(narrow.io.getType.data.dataType eq InterfaceExample.Types.Word)
    wide.io.data := BigInt(1).B(12)
    narrow.io.data := BigInt(2).B(8)
    io.wide := wide.io.result
    io.narrow := narrow.io.result
    wide.io.doubleData := BigInt(3).B(24)
    narrow.io.doubleData := BigInt(4).B(16)
    wide.io.address := BigInt(5).B(5)
    narrow.io.address := BigInt(6).B(4)
    io.wideDouble := wide.io.doubleResult
    io.narrowDouble := narrow.io.doubleResult
    io.wideAddress := wide.io.addressResult
    io.narrowAddress := narrow.io.addressResult

// Access declaration objects before there is an Arena, Context, or native module.
// Their contextual dependencies are the generator's symbolic declaration environments.
val declaredIO = InterfaceExample.IO
val declaredParameters = InterfaceExample.Parameters
val scalarIO = ScalarExample.IO
assert(InterfaceExample.svInterface eq declaredIO)
assert(InterfaceExample.verilogParameters eq declaredParameters)
@generator
object SymbolicArrays extends SVGenerator:
  object Macros extends VerilogMacroInterface
  object Parameters extends VerilogParameterInterface:
    val WIDTH: ParameterField = VerilogParameter(8)
    val DEPTH: ParameterField = VerilogParameter(4, width = 64)
  class FIRRTLIO(p: FIRRTLBindings) extends me.jiuyang.zaozi.HWBundle(p)
  object Types:
    private val p: SVParameters = summon[SVParameters]
    val Packed: PackedArray[Bits] = PackedArray(p.DEPTH, Bits(p.WIDTH))
    val Unpacked: UnpackedArray[Bits] = UnpackedArray(p.DEPTH + ConstExpr(1), Bits(p.WIDTH))
  object IO extends SVBundle:
    val packedIn: Port[PackedArray[Bits]] = Flipped(Types.Packed)
    val packedOut: Port[PackedArray[Bits]] = Aligned(Types.Packed)
    val unpackedIn: Port[UnpackedArray[Bits]] = Flipped(Types.Unpacked)
    val unpackedOut: Port[UnpackedArray[Bits]] = Aligned(Types.Unpacked)
  def architecture =
    val io: SVPorts = summon[SVPorts]
    val p: SVParameters = summon[SVParameters]
    val length: Const[Bits] = LocalParam(p.DEPTH + ConstExpr(1))
    assert(length.width == 64)
    io.packedOut := io.packedIn
    io.unpackedOut := io.unpackedIn

@generator
object BoundArrays extends SVGenerator:
  object Macros extends VerilogMacroInterface
  object Parameters extends VerilogParameterInterface
  class FIRRTLIO(p: FIRRTLBindings) extends me.jiuyang.zaozi.HWBundle(p)
  object IO extends SVBundle:
    val first: Port[Bits] = Aligned(Bits(5))
    val second: Port[Bits] = Aligned(Bits(7))
  def architecture =
    val io: SVPorts = summon[SVPorts]
    val first = SymbolicArrays.instantiateSV(p => Seq(p.WIDTH.bind(ConstExpr(5)), p.DEPTH.bind(ConstExpr(3))))
    val second = SymbolicArrays.instantiateSV(p => Seq(p.WIDTH.bind(ConstExpr(7)), p.DEPTH.bind(ConstExpr(2))))
    assert(first.io.packedIn.getType eq SymbolicArrays.Types.Packed)
    assert(second.io.packedIn.getType eq SymbolicArrays.Types.Packed)
    assert(first.io.packedIn.count == 3 && first.io.packedIn.width == 15)
    assert(second.io.packedIn.count == 2 && second.io.packedIn.width == 14)
    first.io.packedIn := PackedArrayValue(PackedArray(3, Bits(5)), Seq.fill(3)(BigInt(1).B(5)))
    first.io.unpackedIn := UnpackedArrayValue(UnpackedArray(4, Bits(5)), Seq.fill(4)(BigInt(2).B(5)))
    second.io.packedIn := PackedArrayValue(PackedArray(2, Bits(7)), Seq.fill(2)(BigInt(3).B(7)))
    second.io.unpackedIn := UnpackedArrayValue(UnpackedArray(3, Bits(7)), Seq.fill(3)(BigInt(4).B(7)))
    io.first := first.io.packedOut(BigInt(0).B(2))
    val returned: Ref[UnpackedArray[Bits]] = Variable(UnpackedArray(3, Bits(7)))
    returned := second.io.unpackedOut
    io.second := returned(BigInt(0).B(32))

print(InterfaceExample.verilogString())
print(InterfaceHierarchy.verilogString())
val concrete = ConcreteInstances.verilogString()
assert(!concrete.contains("module ScalarExample"))
print(concrete)
print(ScalarExample.verilogString())
print(SymbolicArrays.verilogString())
print(BoundArrays.verilogString())
// Re-elaboration must not duplicate or mutate singleton declarations.
assert(InterfaceExample.verilogString() == InterfaceExample.verilogString())
assert(ScalarExample.svInterface eq scalarIO)

// CHECK-DAG: typedef struct packed {{.*}} Header;
// CHECK-DAG: typedef logic [`EXTRA_WIDTH:0] MacroBits;
// CHECK: module InterfaceExample
// CHECK: parameter WIDTH = 8
// CHECK: input  struct packed
// CHECK-SAME: [{{.*}}WIDTH{{.*}}] data;
// CHECK-SAME: request
// CHECK: output struct packed
// CHECK-SAME: [{{.*}}WIDTH{{.*}}] data;
// CHECK-SAME: response
// CHECK: assign response_out.data = payload.data;
// CHECK: assign response_out.header = payload.header;
// CHECK: assign header_out = payload.header;
// CHECK: module InterfaceHierarchy
// CHECK: .WIDTH(WIDTH)
// CHECK-NOT: module InterfaceExample

// CHECK: module ConcreteInstances
// CHECK: [11:0]
// CHECK: [7:0]
// CHECK: [23:0]
// CHECK: [15:0]
// CHECK: [4:0]
// CHECK: [3:0]
// CHECK: .WIDTH(12)
// CHECK: .DEPTH(32)
// CHECK: ScalarExample narrow
// CHECK: module ScalarExample
// CHECK: parameter WIDTH = 8
// CHECK: parameter DEPTH = 16
// CHECK: [WIDTH * 2 - 1:0]
// CHECK-SAME: doubleData
// CHECK: [$clog2($unsigned(DEPTH)) - 1:0]
// CHECK-SAME: address

// CHECK: module SymbolicArrays
// CHECK: parameter {{.*}}DEPTH = 64'd4
// CHECK: [DEPTH - 64'd1:0][WIDTH - 1:0]
// CHECK: unpackedIn[0:{{.*}}DEPTH{{.*}}]
// CHECK: localparam [63:0] {{ *}}length = DEPTH + 64'd1;
// CHECK: assign packedOut_out = packedIn;
// CHECK: assign unpackedOut_out = unpackedIn;
// CHECK: module BoundArrays
// CHECK: .WIDTH(5)
// CHECK: .DEPTH(64'd3)
// CHECK: .WIDTH(7)
// CHECK: .DEPTH(64'd2)
