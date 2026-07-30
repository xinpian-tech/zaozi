// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozitest

import me.jiuyang.testlib.*
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.mlir.MlirAttribute
import org.llvm.mlir.scalalib.capi.ir.{WalkEnum, WalkResultEnum, given}
import utest.*

case class DocSpecParameter(width: Int) extends Parameter
given upickle.default.ReadWriter[DocSpecParameter] = upickle.default.macroRW

class DocSpecLayers(parameter: DocSpecParameter) extends LayerInterface(parameter):
  def layers = Seq.empty

class DocSpecIO(parameter: DocSpecParameter) extends HWBundle(parameter):
  val clock = Flipped(Clock())
  val reset = Flipped(Reset())
  val i     = Flipped(UInt(parameter.width))
  val o     = Aligned(UInt(parameter.width))

class DocSpecProbe(parameter: DocSpecParameter) extends DVBundle[DocSpecParameter, DocSpecLayers](parameter)

object DocSpec extends TestSuite:
  val tests = Tests:
    test("declaration documentation reaches IR and Verilog"):
      @generator
      object Child extends Generator[DocSpecParameter, DocSpecLayers, DocSpecIO, DocSpecProbe]:
        def architecture(parameter: DocSpecParameter) =
          val io = summon[Interface[DocSpecIO]]
          io.o := io.i

      @generator
      object Top
          extends Generator[DocSpecParameter, DocSpecLayers, DocSpecIO, DocSpecProbe]
          with HasMlirTest
          with HasVerilogTest:
        override def moduleDoc(parameter: DocSpecParameter): Option[String] =
          Some("top module documentation\r\nsecond module line")

        def architecture(parameter: DocSpecParameter) =
          val io             = summon[Interface[DocSpecIO]]
          val wire           = Wire(UInt(parameter.width)).doc("wire documentation")
          val node           = Node(wire).doc("node documentation")
          given ClockScope   = ClockScope.posedge(io.clock)
          given ResetScope   = ResetScope.syncActiveHigh(io.reset)
          val reg            = Reg(UInt(parameter.width)).doc("register documentation")
          val initializedReg = RegInit(0.U(parameter.width)).doc("initialized register documentation")
          val child          = Child.instantiate(parameter).doc("instance documentation")

          wire           := io.i
          reg            := node
          initializedReg := reg
          child.io.clock := io.clock
          child.io.reset := io.reset
          child.io.i     := initializedReg
          io.o           := child.io.o

      val parameter = DocSpecParameter(8)
      Top.mlirOperationTest(parameter): root =>
        val docs = scala.collection.mutable.Map.empty[(String, String), String]
        root.walk(
          op =>
            val kind                    = op.getName.str
            val identifierAttributeName = kind match
              case "firrtl.module"                                                                      => Some("sym_name")
              case "firrtl.wire" | "firrtl.node" | "firrtl.reg" | "firrtl.regreset" | "firrtl.instance" => Some("name")
              case _                                                                                    => None
            identifierAttributeName.foreach: attributeName =>
              val identifier       = op.getInherentAttributeByName(attributeName)
              val comment          = op.getInherentAttributeByName("comment")
              val identifierExists = MlirAttribute.ptr(identifier.segment).address != 0
              val commentExists    = MlirAttribute.ptr(comment.segment).address != 0
              if identifierExists && commentExists && identifier.isString && comment.isString then
                docs((kind, identifier.stringAttrGetValue)) = comment.stringAttrGetValue
            WalkResultEnum.Advance
          ,
          WalkEnum.PreOrder
        )

        val expected = Map(
          ("firrtl.module", Top.moduleName(parameter)) ->
            "top module documentation\nsecond module line",
          ("firrtl.wire", "wire")                      -> "wire documentation",
          ("firrtl.node", "node")                      -> "node documentation",
          ("firrtl.reg", "reg")                        -> "register documentation",
          ("firrtl.regreset", "initializedReg")        -> "initialized register documentation",
          ("firrtl.instance", "child")                 -> "instance documentation"
        )
        val printed  = new StringBuilder
        root.print(printed ++= _)
        expected.forall((key, value) => docs.get(key).contains(value)) &&
        !printed.toString.contains("DocStringAnnotation")
      Top.verilogTest(parameter)(
        "// top module documentation",
        "// second module line",
        "// wire documentation",
        "// node documentation",
        "// register documentation",
        "// initialized register documentation",
        "// instance documentation"
      )

    test("documentation is unavailable on IO"):
      @generator
      object Child extends Generator[DocSpecParameter, DocSpecLayers, DocSpecIO, DocSpecProbe]:
        def architecture(parameter: DocSpecParameter) =
          val io = summon[Interface[DocSpecIO]]
          io.o := io.i

      @generator
      object IODoc extends Generator[DocSpecParameter, DocSpecLayers, DocSpecIO, DocSpecProbe] with HasCompileErrorTest:
        def architecture(parameter: DocSpecParameter) =
          val io    = summon[Interface[DocSpecIO]]
          val child = Child.instantiate(parameter)
          compileError("""io.doc("interface documentation")""").check(
            "",
            "Field 'doc' does not exist in type me.jiuyang.zaozitest.DocSpecIO."
          )
          compileError("""io.i.doc("port documentation")""").check(
            "",
            "Type parameter T must be a subtype of DynamicSubfield, but got me.jiuyang.zaozi.valuetpe.UInt."
          )
          compileError("""child.io.doc("instance IO documentation")""").check(
            "",
            "Field 'doc' does not exist in type me.jiuyang.zaozitest.DocSpecIO."
          )
          compileError("""child.probe.doc("instance probe documentation")""").check(
            "",
            "Field 'doc' does not exist in type me.jiuyang.zaozitest.DocSpecProbe."
          )
          compileError("""io.asRecord.doc("IO view documentation")""").check(
            "",
            "Type parameter T must be a subtype of DynamicSubfield, but got me.jiuyang.zaozi.valuetpe.Record."
          )
          compileError("""(io.i + io.i).doc("expression documentation")""").check(
            "",
            "Type parameter T must be a subtype of DynamicSubfield, but got me.jiuyang.zaozi.valuetpe.UInt."
          )

      IODoc.compileErrorTest(DocSpecParameter(8))
