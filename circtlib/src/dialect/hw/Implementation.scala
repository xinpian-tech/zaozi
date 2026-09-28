// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.dialect.hw.operation

import org.llvm.circt.{CAPI, HWModulePort as NativeHWModulePort}
import org.llvm.circt.scalalib.capi.dialect.hw.{HWModulePort, TypeApi as HWTypeApi, given}
import org.llvm.mlir.scalalib.capi.ir.{
  Attribute,
  Block,
  Context,
  Location,
  NamedAttribute,
  NamedAttributeApi,
  Operation,
  OperationApi,
  Type,
  Value,
  given
}

import java.lang.foreign.Arena

private inline def named(
  name:  String,
  value: org.llvm.mlir.scalalib.capi.ir.Attribute
)(
  using Arena,
  Context
): NamedAttribute =
  summon[NamedAttributeApi].namedAttributeGet(name.identifierGet, value)

private def nativePort(
  port:        Port
)(
  using arena: Arena,
  context:     Context
): HWModulePort =
  val segment = NativeHWModulePort.allocate(arena)
  NativeHWModulePort.name(segment, port.name.stringAttrGet.segment)
  NativeHWModulePort.`type`(segment, port.tpe.segment)
  NativeHWModulePort.dir(
    segment,
    port.direction match
      case PortDirection.Input  => CAPI.Input()
      case PortDirection.Output => CAPI.Output()
      case PortDirection.InOut  => CAPI.InOut()
  )
  HWModulePort(segment)

private def moduleType(
  ports: Seq[Port]
)(
  using Arena,
  Context
): Type =
  summon[HWTypeApi].moduleTypeGet(ports.size, ports.map(nativePort))

private def commonModuleAttributes(
  symbol: String,
  ports:  Seq[Port]
)(
  using Arena,
  Context
): Seq[NamedAttribute] =
  Seq(
    named("sym_name", symbol.stringAttrGet),
    named("module_type", moduleType(ports).typeAttrGet),
    named("parameters", Seq.empty[Attribute].arrayAttrGet)
  )

given ModuleApi with
  def op(
    symbol:   String,
    ports:    Seq[Port],
    location: Location
  )(
    using Arena,
    Context
  ): Module =
    val blockPorts = ports.filter(_.direction != PortDirection.Output)
    val blockTypes = blockPorts.map: port =>
      if port.direction == PortDirection.InOut then port.tpe.inOutTypeGet() else port.tpe
    new Module(
      summon[OperationApi].operationCreate(
        name = "hw.module",
        location = location,
        regionBlockTypeLocations = Seq(Seq((blockTypes, Seq.fill(blockTypes.size)(location)))),
        namedAttributes = commonModuleAttributes(symbol, ports),
        resultsTypes = Some(Seq.empty)
      )
    )

  extension (ref: Module)
    def operation: Operation = ref._operation
    def symbol(
      using Arena
    ): String = ref.operation.getInherentAttributeByName("sym_name").stringAttrGetValue
    def ports(
      using Arena
    ): Seq[Port] =
      val moduleType = ref.operation.getInherentAttributeByName("module_type").typeAttrGetValue
      val portCount  = moduleType.moduleTypeGetNumInputs() + moduleType.moduleTypeGetNumOutputs()
      Vector.tabulate(portCount): index =>
        val nativePort = moduleType.moduleTypeGetPort(index)
        val direction  = nativePort.portDirection match
          case value if value == CAPI.Input()  => PortDirection.Input
          case value if value == CAPI.Output() => PortDirection.Output
          case value if value == CAPI.InOut()  => PortDirection.InOut
          case value                           => throw new IllegalArgumentException(s"unknown HW port direction: $value")
        Port(
          Attribute(nativePort.portName).stringAttrGetValue,
          direction,
          Type(nativePort.portType)
        )
    def block(
      using Arena
    ): Block = ref.operation.getFirstRegion.getFirstBlock
end given

given ModuleExternApi with
  def op(
    symbol:      String,
    ports:       Seq[Port],
    verilogName: Option[String],
    location:    Location
  )(
    using Arena,
    Context
  ): ModuleExtern =
    ModuleExtern(
      summon[OperationApi].operationCreate(
        name = "hw.module.extern",
        location = location,
        regionBlockTypeLocations = Seq(Seq.empty),
        namedAttributes = commonModuleAttributes(symbol, ports) ++
          verilogName.toSeq.map(name => named("verilogName", name.stringAttrGet)),
        resultsTypes = Some(Seq.empty)
      )
    )

  extension (ref: ModuleExtern) def operation: Operation = ref._operation
end given

given InstanceApi with
  def op(
    instanceName: String,
    moduleName:   String,
    ports:        Seq[Port],
    inputs:       Seq[Value],
    location:     Location
  )(
    using Arena,
    Context
  ): Instance =
    val inputPorts  = ports.filter(_.direction != PortDirection.Output)
    val outputPorts = ports.filter(_.direction == PortDirection.Output)
    require(inputs.size == inputPorts.size, s"$moduleName instance input count does not match its ports")
    inputPorts
      .zip(inputs)
      .foreach: (port, input) =>
        val expected = if port.direction == PortDirection.InOut then port.tpe.inOutTypeGet() else port.tpe
        require(input.getType.equal(expected), s"$moduleName instance input type does not match port ${port.name}")
    Instance(
      summon[OperationApi].operationCreate(
        name = "hw.instance",
        location = location,
        namedAttributes = Seq(
          named("instanceName", instanceName.stringAttrGet),
          named("moduleName", moduleName.flatSymbolRefAttrGet),
          named("argNames", inputPorts.map(_.name.stringAttrGet).arrayAttrGet),
          named("resultNames", outputPorts.map(_.name.stringAttrGet).arrayAttrGet),
          named("parameters", Seq.empty[Attribute].arrayAttrGet)
        ),
        operands = inputs,
        resultsTypes = Some(outputPorts.map(_.tpe))
      )
    )

  extension (ref: Instance)
    def operation: Operation = ref._operation
    def results(
      using Arena
    ): Seq[Value] = Vector.tabulate(ref.operation.getNumResults.toInt)(index => ref.operation.getResult(index))
    def result(
      index: Int
    )(
      using Arena
    ): Value = ref.operation.getResult(index)
end given

given OutputApi with
  def op(
    outputs:  Seq[Value],
    location: Location
  )(
    using Arena,
    Context
  ): Output =
    Output(
      summon[OperationApi].operationCreate(
        name = "hw.output",
        location = location,
        operands = outputs,
        resultsTypes = Some(Seq.empty)
      )
    )

  extension (ref: Output) def operation: Operation = ref._operation
end given
