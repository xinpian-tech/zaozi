// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib

import me.jiuyang.zaozi.{DVInterface, Generator, HWInterface, LayerInterface, Parameter, TypeImpl}
import me.jiuyang.zaozi.default.given
import me.jiuyang.zaozi.valuetpe.{Bits, Bool, Clock, Data, Reset, SInt, UInt}

import org.llvm.circt.HWModulePort
import org.llvm.mlir.MlirOperation
import org.llvm.circt.scalalib.capi.dialect.hw.{HWModulePort as HWPort, TypeApi as HWTypeApi, given}
import org.llvm.mlir.scalalib.capi.ir.{
  Attribute,
  AttributeApi,
  Block,
  Context,
  LocationApi,
  NamedAttributeApi,
  Operation,
  OperationApi,
  Type,
  TypeApi,
  Value,
  given
}

import java.lang.foreign.Arena

/** Declares and instantiates a generated DUT in an HW testbench module. */
object UTDut:
  private case class Port(name: String, input: Boolean, tpe: Type)

  private def moduleType(ports: Seq[Port])(using Arena, Context): Type =
    val hwPorts = ports.map { port =>
      val raw = HWModulePort.allocate(summon[Arena])
      HWModulePort.name(raw, port.name.stringAttrGet.segment)
      HWModulePort.`type`(raw, port.tpe.segment)
      HWModulePort.dir(raw, if port.input then 0 else 1)
      HWPort(raw)
    }
    summon[HWTypeApi].moduleTypeGet(hwPorts.size, hwPorts)

  private def port(name: String, input: Boolean, data: Data)(using Arena, Context): Port =
    val (width, signed) = data match
      case _: Clock | _: Reset | _: Bool => (1, false)
      case _: SInt                      => (data.width(using summon[Arena], summon[Context], summon[TypeImpl]), true)
      case _: UInt | _: Bits            => (data.width(using summon[Arena], summon[Context], summon[TypeImpl]), false)
      case _ => throw new IllegalArgumentException(s"unsupported DUT port type for $name: ${data.getClass.getName}")
    require(width > 0, s"invalid testbench port width: $width")
    Port(name, input, if signed then width.integerTypeSignedGet else width.integerTypeGet)

  private def declareDut(moduleName: String, ports: Seq[Port], module: Operation)(using Arena, Context, Block): Unit =
    require(moduleName.matches("[A-Za-z_][A-Za-z_0-9]*"), s"invalid DUT module name: $moduleName")
    require(ports.map(_.name).distinct.size == ports.size, "duplicate DUT port name")
    val loc = summon[LocationApi].locationUnknownGet
    val declaration = summon[OperationApi].operationCreate(
      name = "hw.module.extern",
      location = loc,
      regionBlockTypeLocations = Seq(Seq.empty),
      namedAttributes = Seq(
        summon[NamedAttributeApi].namedAttributeGet("sym_name".identifierGet, moduleName.stringAttrGet),
        summon[NamedAttributeApi].namedAttributeGet("module_type".identifierGet, moduleType(ports).typeAttrGet),
        summon[NamedAttributeApi].namedAttributeGet("parameters".identifierGet, Seq.empty[Attribute].arrayAttrGet),
        summon[NamedAttributeApi].namedAttributeGet("port_locs".identifierGet, Seq.fill(ports.size)(loc.getAttribute).arrayAttrGet)
      ),
      resultsTypes = Some(Seq.empty)
    )
    module.getBlock.insertOwnedOperationBefore(module, declaration)

  def instantiate[PARAM <: Parameter, L <: LayerInterface[PARAM], I <: HWInterface[PARAM], P <: DVInterface[PARAM, L]](
    generator: Generator[PARAM, L, I, P],
    parameter: PARAM,
    name: String,
    inputs: Map[String, Value]
  )(using Arena, Context, Block): Unit =
    val loc = summon[LocationApi].locationUnknownGet
    val module = summon[Block].getParentOperation
    require(module.getName.str == "hw.module", "DUT instance must be inside a testbench module")
    val moduleName = generator.moduleName(parameter)
    val interface = generator.interface(parameter)
    interface.toMlirType
    val ports = interface.elements.map(field => port(field.name, field.isFlipped, field.dataType))
    var current = module.getBlock.getFirstOperation
    var declared = false
    while MlirOperation.ptr(current.segment).address != 0 do
      if current.getName.str == "hw.module.extern" &&
          current.getInherentAttributeByName("sym_name").stringAttrGetValue == moduleName then declared = true
      current = current.getNextInBlock
    if !declared then declareDut(moduleName, ports, module)
    val inputPorts = ports.filter(_.input)
    val outputPorts = ports.filterNot(_.input)
    require(inputs.keySet == inputPorts.map(_.name).toSet, "DUT inputs do not match the declared interface")
    val orderedInputs = inputPorts.map(port => inputs(port.name))
    inputPorts.zip(orderedInputs).foreach: (port, value) =>
      require(value.getType.equal(port.tpe), s"DUT input type does not match port ${port.name}")
    summon[OperationApi].operationCreate(
      "hw.instance", loc,
      namedAttributes = Seq(
        summon[NamedAttributeApi].namedAttributeGet("instanceName".identifierGet, name.stringAttrGet),
        summon[NamedAttributeApi].namedAttributeGet("moduleName".identifierGet, moduleName.flatSymbolRefAttrGet),
        summon[NamedAttributeApi].namedAttributeGet("parameters".identifierGet, Seq.empty[Attribute].arrayAttrGet),
        summon[NamedAttributeApi].namedAttributeGet("argNames".identifierGet, inputPorts.map(_.name.stringAttrGet).arrayAttrGet),
        summon[NamedAttributeApi].namedAttributeGet("resultNames".identifierGet, outputPorts.map(_.name.stringAttrGet).arrayAttrGet)
      ),
      operands = orderedInputs,
      resultsTypes = Some(outputPorts.map(_.tpe))
    ).appendToBlock()
