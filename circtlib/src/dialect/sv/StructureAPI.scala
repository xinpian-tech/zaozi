// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.dialect.sv.operation

import org.llvm.circt.scalalib.dialect.sv.attr.*
import org.llvm.mlir.scalalib.capi.ir.{Attribute, Block, Context, Location, Operation, Type, Value}
import org.llvm.mlir.scalalib.capi.support.HasOperation
import java.lang.foreign.Arena

/** `sv.generate`. */
class Generate(val _operation: Operation)
trait GenerateApi extends HasOperation[Generate]:
  def op(
    symName:     String,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ):   Generate
  extension (ref: Generate)
    def block(
      using Arena
    ): Block
end GenerateApi

/** `sv.generate.case`. */
class GenerateCase(val _operation: Operation)
trait GenerateCaseApi extends HasOperation[GenerateCase]:
  def op(
    cond:         Attribute,
    casePatterns: Seq[Attribute],
    caseNames:    Seq[String],
    location:     Location
  )(
    using arena:  Arena,
    context:      Context
  ):   GenerateCase
  extension (ref: GenerateCase)
    def caseBlock(
      index: Int
    )(
      using Arena
    ): Block
end GenerateCaseApi

/** `sv.generate.for`. */
class GenerateFor(val _operation: Operation)
trait GenerateForApi extends HasOperation[GenerateFor]:
  def op(
    lowerBound:       Attribute,
    upperBound:       Attribute,
    step:             Attribute,
    genBlockName:     String,
    inductionVarName: Option[String] = None,
    location:         Location
  )(
    using arena:      Arena,
    context:          Context
  ): GenerateFor
  extension (ref: GenerateFor)
    def block(
      using Arena
    ): Block
    def inductionVar(
      using Arena
    ): Value
end GenerateForApi

/** `sv.interface`. */
class Interface(val _operation: Operation)
trait InterfaceApi extends HasOperation[Interface]:
  def op(
    symName:       String,
    symVisibility: Option[String] = None,
    comment:       Option[String] = None,
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ):   Interface
  extension (ref: Interface)
    def block(
      using Arena
    ): Block
end InterfaceApi

/** `sv.interface.instance`. */
class InterfaceInstance(val _operation: Operation)
trait InterfaceInstanceApi extends HasOperation[InterfaceInstance]:
  def op(
    name:        String,
    innerSym:    Option[Attribute] = None,
    doNotPrint:  Boolean = false,
    resultType:  Type,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ):   InterfaceInstance
  extension (ref: InterfaceInstance)
    def result(
      using Arena
    ): Value
end InterfaceInstanceApi

/** `sv.interface.modport`. */
class InterfaceModport(val _operation: Operation)
trait InterfaceModportApi extends HasOperation[InterfaceModport]:
  def op(
    symName:       String,
    symVisibility: Option[String] = None,
    ports:         Seq[Attribute],
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ): InterfaceModport
end InterfaceModportApi

/** `sv.interface.signal`. */
class InterfaceSignal(val _operation: Operation)
trait InterfaceSignalApi extends HasOperation[InterfaceSignal]:
  def op(
    symName:       String,
    symVisibility: Option[String] = None,
    tpe:           Type,
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ): InterfaceSignal
end InterfaceSignalApi

/** `sv.interface.signal.assign`. */
class AssignInterfaceSignal(val _operation: Operation)
trait AssignInterfaceSignalApi extends HasOperation[AssignInterfaceSignal]:
  def op(
    iface:       Value,
    signalName:  String,
    rhs:         Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): AssignInterfaceSignal
end AssignInterfaceSignalApi

/** `sv.interface.signal.read`. */
class ReadInterfaceSignal(val _operation: Operation)

/** Read a declared interface signal. resultType must match the signal declaration in the enclosing symbol table. */
trait ReadInterfaceSignalApi extends HasOperation[ReadInterfaceSignal]:
  def op(
    iface:       Value,
    signalName:  String,
    resultType:  Type,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ):   ReadInterfaceSignal
  extension (ref: ReadInterfaceSignal)
    def result(
      using Arena
    ): Value
end ReadInterfaceSignalApi

/** `sv.modport.get`. */
class GetModport(val _operation: Operation)

/** Select field from an interface. resultType must reference that interface and modport; SV.h exposes no interface-type
  * introspection.
  */
trait GetModportApi extends HasOperation[GetModport]:
  def op(
    iface:       Value,
    field:       String,
    resultType:  Type,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ):   GetModport
  extension (ref: GetModport)
    def result(
      using Arena
    ): Value
end GetModportApi

/** `sv.package`. */
class Package(val _operation: Operation)

/** Top-level package. At the pinned CIRCT revision its body accepts only hw.typedecl operations. */
trait PackageApi extends HasOperation[Package]:
  def op(
    symName:       String,
    symVisibility: Option[String] = None,
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ):   Package
  extension (ref: Package)
    def block(
      using Arena
    ): Block
end PackageApi

/** `sv.verbatim.module`. */
class SVVerbatimModule(val _operation: Operation)

/** Declare a module provided by source, a symbol referencing SVVerbatimSource. Port attributes and locations follow
  * moduleType declaration order.
  */
trait SVVerbatimModuleApi extends HasOperation[SVVerbatimModule]:
  def op(
    symName:       String,
    symVisibility: Option[String] = None,
    moduleType:    Type,
    perPortAttrs:  Option[Seq[Attribute]] = None,
    portLocs:      Option[Seq[Location]] = None,
    parameters:    Seq[Attribute],
    source:        String,
    verilogName:   Option[String] = None,
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ): SVVerbatimModule
end SVVerbatimModuleApi

/** `sv.verbatim.source`. */
class SVVerbatimSource(val _operation: Operation)

/** External Verilog source. outputFile is an HW output-file attribute; additionalFiles contains flat references to
  * emit.file operations.
  */
trait SVVerbatimSourceApi extends HasOperation[SVVerbatimSource]:
  def op(
    symName:         String,
    symVisibility:   Option[String] = None,
    content:         String,
    outputFile:      Attribute,
    parameters:      Seq[Attribute],
    additionalFiles: Option[Seq[Attribute]] = None,
    verilogName:     String,
    location:        Location
  )(
    using arena:     Arena,
    context:         Context
  ): SVVerbatimSource
end SVVerbatimSourceApi
