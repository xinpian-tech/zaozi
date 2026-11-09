// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.sv
import scala.language.dynamics

/** Preprocessing results supplied by the external build at a module's source location. Defined distinguishes a feature
  * flag or non-integer expansion from an integer constant.
  */
enum MacroDefinition:
  case Undefined, Defined
  case Integer(value: Int)

final case class MacroBinding private[sv] (name: String, definition: MacroDefinition)

/** An immutable preprocessing snapshot, shared by instances in the same compilation environment. An absent entry is
  * unresolved, not implicitly undefined. This is not a Verilog preprocessor.
  */
final class MacroEnvironment private (private[sv] val definitions: Map[String, MacroDefinition]):
  private[sv] def resolve(name: String): ResolvedMacro =
    new ResolvedMacro(
      name,
      definitions.getOrElse(
        name,
        throw new IllegalArgumentException(s"No preprocessing result supplied for macro $name")
      )
    )

object MacroEnvironment:
  given empty:                        MacroEnvironment = new MacroEnvironment(Map.empty)
  def apply(bindings: MacroBinding*): MacroEnvironment =
    require(bindings.map(_.name).distinct.size == bindings.size, "Duplicate macro binding")
    new MacroEnvironment(bindings.map(b => b.name -> b.definition).toMap)

final class ResolvedMacro private[sv] (val name: String, val definition: MacroDefinition):
  def isDefined: Boolean = definition != MacroDefinition.Undefined
  def value:     Int     = definition match
    case MacroDefinition.Integer(value) => value
    case _                              => throw new IllegalArgumentException(s"Macro $name has no resolved integer value")

/** Typed view of macro declarations in a concrete preprocessing environment. */
final class FIRRTLMacros[M <: VerilogMacroInterface] private[sv] (
  val getType:                 M,
  private[sv] val environment: MacroEnvironment)
    extends scala.Dynamic:
  def field(name: String):                                   ResolvedMacro = environment.resolve(name)
  transparent inline def selectDynamic(inline name: String): ResolvedMacro =
    ${ me.jiuyang.zaozi.sv.magic.firrtlMacroSelect('this, 'name) }

/** The concrete inputs needed to construct a FIRRTL view of an SV module. */
final case class ResolvedBindings[V <: VerilogParameterInterface, M <: VerilogMacroInterface] private[sv] (
  parameters: FIRRTLParameters[V],
  macros:     FIRRTLMacros[M])
    extends me.jiuyang.zaozi.Parameter
