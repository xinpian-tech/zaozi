// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.sv.tests

import me.jiuyang.zaozi.sv.valuetpe.{Enum, NamedType}
import me.jiuyang.zaozi.InstanceContext
import me.jiuyang.zaozi.sv.{ConstExpr, ParameterInterface, SVGenerator, VerilogParameterInterface}
import me.jiuyang.zaozi.sv.default.{*, given}
import org.llvm.circt.scalalib.capi.dialect.hw.{DialectApi as HWDialect, given}
import org.llvm.circt.scalalib.capi.dialect.sv.{DialectApi as SVDialect, given}
import org.llvm.circt.scalalib.capi.exportverilog.given
import org.llvm.circt.scalalib.dialect.hw.operation.{ModuleApi as HWModuleApi, OutputApi, given}
import org.llvm.circt.scalalib.dialect.hw.tpe.{ModulePort, PortDirection}
import org.llvm.mlir.scalalib.capi.ir.{*, given}
import org.llvm.mlir.scalalib.capi.support.given
import java.lang.foreign.Arena
import utest.*

object SVDataSpec extends TestSuite:
  object Parameters extends VerilogParameterInterface:
    val WIDTH = VerilogParameter(8)
    val DEPTH = VerilogParameter(16)

  object ArrayPorts extends me.jiuyang.zaozi.sv.SVBundle:
    val packedInput = Flipped(PackedArray(4, Bits(8)))

  val tests = Tests:
    test("symbolic parameters, localparam, enum and val names survive Verilog export"):
      given Arena                                = Arena.ofAuto()
      given Context                              = summon[ContextApi].contextCreate
      summon[HWDialect].loadDialect
      summon[SVDialect].loadDialect
      given InstanceContext                      = new InstanceContext
      val location                               = summon[LocationApi].locationUnknownGet
      val module                                 = summon[ModuleApi].moduleCreateEmpty(location)
      val parameters                             = ParameterInterface(Parameters)
      given me.jiuyang.zaozi.sv.TypeScopeContext = new me.jiuyang.zaozi.sv.TypeScopeContext("types")
      object State extends Enum with NamedType:
        val Idle = Value()
        val Busy = Value()
        val Done = Value()
      val stateType = State
      val width     = Bits(parameters.WIDTH)
      val hw        = summon[HWModuleApi].op(
        "Parameterized",
        Seq(
          ModulePort("data", width.toMlirType, PortDirection.Input),
          ModulePort("result", width.toMlirType, PortDirection.Output),
          ModulePort("state", stateType.toMlirType, PortDirection.Output),
          ModulePort("addressWidth", Bits(32).toMlirType, PortDirection.Output),
          ModulePort("widthValue", Bits(32).toMlirType, PortDirection.Output)
        ),
        parameters.declarations,
        location
      )
      hw.operation.appendToBlock()(
        using module.getBody
      )
      given Block   = hw.block
      val data      = new me.jiuyang.zaozi.sv.reftpe.Node[me.jiuyang.zaozi.sv.valuetpe.Bits]:
        private[zaozi] val _tpe   = width
        private[zaozi] val _refer = hw.block.getArgument(0)
      val temporary = Variable(width)
      temporary := data
      val state = Variable(stateType)
      alwaysComb:
        state := stateType.Busy
      val addressWidth = LocalParam(parameters.DEPTH.clog2 + ConstExpr(1))
      val doubled        = LocalParam(parameters.WIDTH * ConstExpr(2))
      val parameterValue = ParameterValue(parameters.WIDTH)
      summon[OutputApi]
        .op(Seq(temporary.refer, state.refer, addressWidth.refer, parameterValue.refer), location)
        .operation
        .appendToBlock()
      emitTypeDeclarations(summon[me.jiuyang.zaozi.sv.TypeScopeContext], module.getBody)
      assert(module.getOperation.verify)
      val verilog        = StringBuilder()
      assert(module.exportVerilog(verilog ++= _).succeeded)
      val text           = verilog.toString
      assert(text.contains("parameter"), text.contains("WIDTH"), text.contains("DEPTH"))
      assert(text.contains("[WIDTH - 1:0]"), text.contains("temporary"))
      assert(text.contains("localparam"), text.contains("addressWidth"), text.contains("$clog2($unsigned(DEPTH))"))
      assert(text.contains("typedef enum"), text.contains("State"), text.contains("Busy"))
      assert("(?m)^\\s*State\\s+state_0;".r.findFirstIn(text).nonEmpty)
      assert(text.contains("doubled = WIDTH * 2"), text.contains("assign widthValue = WIDTH;"))
      assert(text.contains("always_comb"))

    test("wide bit literals and packed and unpacked arrays"):
      given Arena           = Arena.ofAuto()
      given Context         = summon[ContextApi].contextCreate
      summon[HWDialect].loadDialect
      summon[SVDialect].loadDialect
      given InstanceContext = new InstanceContext
      val location          = summon[LocationApi].locationUnknownGet
      val module            = summon[ModuleApi].moduleCreateEmpty(location)
      val packed            = PackedArray(4, Bits(8))
      val unpacked          = UnpackedArray(4, Bits(8))
      val hw                = summon[HWModuleApi].op(
        "Arrays",
        Seq(
          ModulePort("packedInput", packed.toMlirType, PortDirection.Input),
          ModulePort("unpackedInput", unpacked.toMlirType, PortDirection.Input),
          ModulePort("wide", Bits(65).toMlirType, PortDirection.Output),
          ModulePort("selected", Bits(8).toMlirType, PortDirection.Output)
        ),
        Seq.empty,
        location
      )
      hw.operation.appendToBlock()(
        using module.getBody
      )
      given Block           = hw.block
      val wide              = (BigInt(1) << 64).B(65)
      val io                = new me.jiuyang.zaozi.sv.Interface(ArrayPorts, Map("packedInput" -> hw.block.getArgument(0)))
      val selected          = io.packedInput(BigInt(1).B(2))
      summon[OutputApi].op(Seq(wide.refer, selected.refer), location).operation.appendToBlock()
      assert(module.getOperation.verify)
      val verilog           = StringBuilder()
      assert(module.exportVerilog(verilog ++= _).succeeded)
      val text              = verilog.toString
      assert(text.contains("[3:0][7:0]"), text.contains("unpackedInput[0:3]"), text.contains("65'h10000000000000000"))
      assert(text.contains("assign selected = packedInput[2'h1];"))
