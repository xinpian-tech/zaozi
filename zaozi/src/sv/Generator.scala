// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.sv

import me.jiuyang.zaozi.{HWInterface, InstanceContext}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Module}
import java.lang.foreign.Arena

final class SVElaborationContext private[sv] (private[sv] val module: Module):
  private[sv] val modules = collection.mutable.HashSet.empty[String]

/** One Verilog module definition. Configuration belongs to Verilog parameters and macros. */
trait SVGenerator:
  type FIRRTLIO <: HWInterface[?]
  val IO:         SVInterface
  val Parameters: VerilogParameterInterface
  val Macros:     VerilogMacroInterface

  /** Concrete instance bindings for the FIRRTL adapter. */
  type FIRRTLBindings = ResolvedBindings[Parameters.type, Macros.type]

  /** Symbolic references to this module's Verilog parameter declarations. */
  type SVParameters = ParameterInterface[Parameters.type]

  /** References to this module's SV ports. */
  type SVPorts = Interface[IO.type]

  /** Lexical contexts for nested declaration objects. They contain only symbolic descriptions, never a native IR
    * context or a particular instance's bindings.
    */
  protected given declarationParameters:   SVParameters     = symbolicParameters
  protected given declarationTypes:        TypeScopeContext = symbolicTypes
  private[sv] lazy val symbolicParameters: SVParameters     = ParameterInterface(verilogParameters)
  private[sv] lazy val symbolicTypes:      TypeScopeContext = new TypeScopeContext(moduleName + "_types")
  def moduleName:                          String           = getClass.getSimpleName.stripSuffix("$")

  /** Optional external Verilog implementation. Its typed declaration still comes from IO and Parameters. */
  def source:                                Option[VerilogSource] = None
  def architecture:                          (
    Arena,
    Context,
    Block,
    SVPorts,
    SVParameters,
    InstanceContext,
    TypeScopeContext,
    SVElaborationContext
  ) ?=> Unit
  def interface(parameters: FIRRTLBindings): FIRRTLIO
  final def svInterface:                     IO.type               = IO
  final def verilogParameters:               Parameters.type       = Parameters
  def main(args:            Array[String]):  Unit

  def instantiate(
    bindings: SVParameters => Seq[ParameterBinding] = (_: SVParameters) => Seq.empty
  )(
    using SVGeneratorApi,
    MacroEnvironment,
    Arena,
    Context,
    Block,
    InstanceContext,
    sourcecode.Name.Machine,
    sourcecode.File,
    sourcecode.Line
  ): me.jiuyang.zaozi.sv.reftpe.FIRRTLInstance[FIRRTLIO] =
    summon[SVGeneratorApi].instantiateImpl(this)(bindings)
  def instantiateSV(
    bindings:    SVParameters => Seq[ParameterBinding] = (_: SVParameters) => Seq.empty,
    connections: Seq[PortBinding] = Seq.empty
  )(
    using SVGeneratorApi,
    Arena,
    Context,
    Block,
    SVElaborationContext,
    InstanceContext,
    sourcecode.Name.Machine,
    sourcecode.File,
    sourcecode.Line
  ): me.jiuyang.zaozi.sv.reftpe.Instance[IO.type, Parameters.type] =
    summon[SVGeneratorApi].instantiateSVImpl(this)(bindings, connections)

trait SVGeneratorApi:
  extension [G <: SVGenerator & Singleton](generator: G)
    /** Elaborate only this definition. Dependencies are extern declarations, never module bodies. */
    def elaborate(
    )(
      using Arena,
      Context
    ): Module

    /** Export this module only; the caller supplies dependency sources to the external build. */
    def verilogString():               String
    def instantiateImpl(
      bindings: generator.SVParameters => Seq[ParameterBinding] = (_: generator.SVParameters) => Seq.empty
    )(
      using MacroEnvironment,
      Arena,
      Context,
      Block,
      InstanceContext,
      sourcecode.Name.Machine,
      sourcecode.File,
      sourcecode.Line
    ):                                 me.jiuyang.zaozi.sv.reftpe.FIRRTLInstance[generator.FIRRTLIO]
    def instantiateSVImpl(
      bindings:    generator.SVParameters => Seq[ParameterBinding] = (_: generator.SVParameters) => Seq.empty,
      connections: Seq[PortBinding] = Seq.empty
    )(
      using Arena,
      Context,
      Block,
      SVElaborationContext,
      InstanceContext,
      sourcecode.Name.Machine,
      sourcecode.File,
      sourcecode.Line
    ):                                 me.jiuyang.zaozi.sv.reftpe.Instance[generator.IO.type, generator.Parameters.type]
    def mainImpl(args: Array[String]): Unit
