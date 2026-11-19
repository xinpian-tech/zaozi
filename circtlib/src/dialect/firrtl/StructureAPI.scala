// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.dialect.firrtl.operation

import org.llvm.circt.scalalib.capi.dialect.firrtl.{FirrtlBundleField, FirrtlConvention, FirrtlLayerConvention}
import org.llvm.mlir.scalalib.capi.support.{*, given}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Location, Module as MlirModule, Operation, Type, Value}

import scala.scalanative.unsafe.Zone

class Circuit(val _operation: Operation)
trait CircuitApi extends HasOperation[Circuit]:
  inline def op(
    circuitName: String
  )(
    using zone:  Zone,
    context:     Context
  ): Circuit

  extension (c: Circuit)
    inline def block(
      using Zone
    ): Block
    inline def appendToModule(
    )(
      using zone: Zone,
      module:     MlirModule
    ): Unit

    /** Declares each module an instance in the circuit references but the circuit neither defines nor declares, as an
      * extmodule standing for it until the circuit is linked.
      */
    def declareInstantiatedModules(
    )(
      using Zone,
      Context
    ): Unit
end CircuitApi

class Class(val _operation: Operation)
class Contract(val _operation: Operation)
trait ContractApi extends HasOperation[Contract]:
  def op(
    inputs:      Seq[Value],
    resultTypes: Seq[Type],
    location:    Location
  )(
    using Zone,
    Context
  ): Contract

  extension (ref: Contract)
    def block(
      using Zone
    ): Block
    def result(
      using Zone
    ): Value
end ContractApi

class ExtClass(val _operation: Operation)
class ExtModule(val _operation: Operation)

type VerilogParameterType = String | BigInt | Double | Boolean
trait ExtModuleApi extends HasOperation[ExtModule]:
  inline def op(
    symbolName:       String,
    moduleName:       String,
    location:         Location,
    firrtlConvention: FirrtlConvention,
    interface:        Seq[(FirrtlBundleField, Location)],
    layers:           Seq[Seq[String]],
    verilogParameter: Seq[(String, VerilogParameterType)]
  )(
    using Zone,
    Context
  ): ExtModule

  extension (ref: ExtModule)
    inline def appendToCircuit(
    )(
      using zone: Zone,
      circuit:    Circuit
    ): Unit
end ExtModuleApi

class IntModule(val _operation: Operation)
class MemModule(val _operation: Operation)
class Module(val _operation: Operation)
trait ModuleApi extends HasOperation[Module]:
  inline def op(
    name:             String,
    location:         Location,
    firrtlConvention: FirrtlConvention,
    interface:        Seq[(FirrtlBundleField, Location)],
    layers:           Seq[Seq[String]]
  )(
    using zone:       Zone,
    context:          Context
  ): Module

  extension (m: Module)
    inline def block(
      using Zone
    ): Block
    inline def appendToCircuit(
    )(
      using zone: Zone,
      circuit:    Circuit
    ): Unit
    inline def getIO(
      idx: Long
    )(
      using Zone
    ): Value
end ModuleApi

class Formal(val _operation: Operation)
class Layer(val _operation: Operation)
trait LayerApi      extends HasOperation[Layer]:
  inline def op(
    name:            String,
    location:        Location,
    layerConvention: FirrtlLayerConvention
  )(
    using zone:      Zone,
    context:         Context
  ): Layer
end LayerApi
class OptionCase(val _operation: Operation)
trait OptionCaseApi extends HasOperation[OptionCase]:
  inline def op(
    name:       String,
    location:   Location
  )(
    using zone: Zone,
    context:    Context
  ): OptionCase
end OptionCaseApi
class Option(val _operation: Operation)
trait OptionApi     extends HasOperation[Option]:
  inline def op(
    name:       String,
    location:   Location
  )(
    using zone: Zone,
    context:    Context
  ): Option

  extension (ref: Option)
    inline def block(
      using Zone
    ): Block
end OptionApi
