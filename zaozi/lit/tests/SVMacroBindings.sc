// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
// RUN: scala-cli run %s --server=false --java-home=%JAVAHOME --extra-jars=%RUNCLASSPATH --scala-version=%SCALAVERSION -O="-experimental" %JAVAOPTS | FileCheck %s

import me.jiuyang.zaozi.{Parameter, HWBundle, Generator}
import me.jiuyang.zaozi.default.given_GeneratorApi
import me.jiuyang.zaozi.default.{Bits as FIRRTLBits, given_TypeImpl as firrtlTypes}
import me.jiuyang.zaozi.sv.*
import me.jiuyang.zaozi.sv.default.{*, given}

@generator
object MacroCell extends SVGenerator:
  object Parameters extends VerilogParameterInterface:
    val WIDTH = VerilogParameter(8)
  object Macros extends VerilogMacroInterface:
    val EXTRA = VerilogMacro()
    val BASE = VerilogMacro()
    val DOUBLE = VerilogMacro()
  class FIRRTLIO(bindings: FIRRTLBindings) extends HWBundle(bindings):
    val data = Flipped(FIRRTLBits(bindings.parameters.WIDTH + bindings.macros.EXTRA.value))
    val result = Aligned(FIRRTLBits(bindings.parameters.WIDTH + bindings.macros.EXTRA.value))
    // A defined macro with replacement 0 is still defined for `ifdef.
    val doubles = bindings.macros.DOUBLE.isDefined
  object IO extends SVBundle:
    private val p = summon[SVParameters]
    val data = Flipped(Bits(p.WIDTH + Macros.EXTRA.value))
    val result = Aligned(Bits(p.WIDTH + Macros.EXTRA.value))
  def architecture =
    val io = summon[SVPorts]
    ifdef(Macros.DOUBLE):
      io.result := io.data + io.data
    .otherwise:
      io.result := io.data

case class TopParameter(extra: Int, doubled: Boolean) extends Parameter
object TopParameter:
  given upickle.default.ReadWriter[TopParameter] = upickle.default.macroRW
class TopIO(p: TopParameter) extends HWBundle(p):
  val data = Flipped(FIRRTLBits(9 + p.extra))
  val result = Aligned(FIRRTLBits(9 + p.extra))
class TopLayers(p: TopParameter) extends me.jiuyang.zaozi.LayerInterface(p):
  def layers = Seq.empty
class TopProbe(p: TopParameter) extends me.jiuyang.zaozi.DVBundle[TopParameter, TopLayers](p)

@generator
object MacroTop extends Generator[TopParameter, TopLayers, TopIO, TopProbe]:
  import me.jiuyang.zaozi.default.{*, given}
  def architecture(p: TopParameter) =
    val io = summon[me.jiuyang.zaozi.reftpe.Interface[TopIO]]
    // A snapshot for this compilation unit, not an argument on each instance.
    given MacroEnvironment = MacroEnvironment(
      MacroCell.Macros.EXTRA.bind(p.extra),
      MacroCell.Macros.BASE.bind(8),
      MacroCell.Macros.DOUBLE.bind(if p.doubled then MacroDefinition.Integer(0) else MacroDefinition.Undefined)
    )
    val first = MacroCell.instantiate(parameters =>
      Seq(parameters.WIDTH.bind(MacroCell.Macros.BASE.value + ConstExpr(1))))
    val second = MacroCell.instantiate(parameters => Seq(parameters.WIDTH.bind(ConstExpr(9))))
    assert(first.io.getType.doubles == p.doubled)
    first.io.data := io.data
    second.io.data := first.io.result
    io.result := second.io.result

print(MacroCell.verilogString())

import java.lang.foreign.Arena
import org.llvm.mlir.scalalib.capi.ir.{ModuleApi, ContextApi, Context, LocationApi, given}
import org.llvm.circt.scalalib.dialect.firrtl.operation.{Circuit, CircuitApi, given}
val arena = Arena.ofConfined()
given Arena = arena
given Context = summon[ContextApi].contextCreate
try
  org.llvm.circt.scalalib.capi.dialect.firrtl.given_DialectApi.loadDialect
  org.llvm.circt.scalalib.capi.dialect.hw.given_DialectApi.loadDialect
  org.llvm.circt.scalalib.capi.dialect.ltl.given_DialectApi.loadDialect
  org.llvm.circt.scalalib.capi.dialect.verif.given_DialectApi.loadDialect
  val imports = Seq(TopParameter(3, true), TopParameter(7, false)).map: p =>
    val module = summon[ModuleApi].moduleCreateEmpty(summon[LocationApi].locationUnknownGet)
    try
      given Circuit = summon[CircuitApi].op(MacroTop.moduleName(p))
      summon[Circuit].appendToModule()(using summon[Arena], module)
      MacroTop.module(p).appendToCircuit()
      me.jiuyang.zaozi.magic.validateCircuit()
      assert(module.getOperation.verify)
      val bytes = new java.io.ByteArrayOutputStream()
      module.getOperation.writeBytecode(bytes.write(_))
      val saved = summon[ModuleApi].moduleCreateParse(bytes.toByteArray)
      try
        assert(saved.getOperation.verify)
        val ir = StringBuilder()
        saved.getOperation.print(ir.append(_))
        val text = ir.toString
        assert(text.linesIterator.count(_.trim.startsWith("firrtl.extmodule")) == 1)
        assert(text.contains(s"!firrtl.uint<${9 + p.extra}>"))
        val imported = text.linesIterator.find(_.trim.startsWith("firrtl.extmodule")).get
        assert(imported.contains("zaozi.sv.macros"))
        assert(imported.contains(if p.doubled then "integer" else "undefined"))
        print(text)
        imported
      finally saved.getOperation.destroy()
    finally module.getOperation.destroy()
  assert(imports.head.takeWhile(_ != '(') != imports.last.takeWhile(_ != '('))
finally
  summon[Context].destroy()
  arena.close()

// CHECK: module MacroCell
// CHECK: parameter WIDTH = 8
// CHECK: `EXTRA
// CHECK: `ifdef DOUBLE
// CHECK: `else
// CHECK: `endif
// CHECK: firrtl.circuit
// CHECK: firrtl.extmodule @MacroCell_firrtl_9_macros_
// CHECK-SAME: zaozi.sv.macros
// CHECK-SAME: name = "DOUBLE", state = "integer", value = 0 : i32
// CHECK-SAME: name = "EXTRA", state = "integer", value = 3 : i32
// CHECK: firrtl.circuit
// CHECK: firrtl.extmodule @MacroCell_firrtl_9_macros_
// CHECK-SAME: name = "DOUBLE", state = "undefined"
// CHECK-SAME: name = "EXTRA", state = "integer", value = 7 : i32
