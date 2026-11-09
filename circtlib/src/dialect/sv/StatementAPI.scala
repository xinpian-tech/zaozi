// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.dialect.sv.operation

import org.llvm.circt.scalalib.dialect.sv.attr.*
import org.llvm.mlir.scalalib.capi.ir.{Attribute, Block, Context, Location, Operation, Type, Value}
import org.llvm.mlir.scalalib.capi.support.HasOperation
import java.lang.foreign.Arena

/** `sv.alias`. */
class Alias(val _operation: Operation)
trait AliasApi extends HasOperation[Alias]:
  def op(
    aliases:     Seq[Value],
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Alias
end AliasApi

/** `sv.always`. */
class Always(val _operation: Operation)
trait AlwaysApi extends HasOperation[Always]:
  def op(
    events:      Seq[EventControl],
    clocks:      Seq[Value],
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ):   Always
  extension (ref: Always)
    def block(
      using Arena
    ): Block
end AlwaysApi

/** `sv.alwayscomb`. */
class AlwaysComb(val _operation: Operation)
trait AlwaysCombApi extends HasOperation[AlwaysComb]:
  def op(
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ):   AlwaysComb
  extension (ref: AlwaysComb)
    def block(
      using Arena
    ): Block
end AlwaysCombApi

/** `sv.alwaysff`. */
class AlwaysFF(val _operation: Operation)

/** Clocked procedural region. The clock-only builder leaves the reset region empty; the reset builder creates both
  * blocks. Use SyncReset or AsyncReset with the reset builder.
  */
trait AlwaysFFApi extends HasOperation[AlwaysFF]:
  def op(
    clockEdge:   EventControl,
    clock:       Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): AlwaysFF
  def op(
    clockEdge:   EventControl,
    clock:       Value,
    resetStyle:  ResetStyle,
    resetEdge:   EventControl,
    reset:       Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): AlwaysFF
  extension (ref: AlwaysFF)
    def block(
      using Arena
    ): Block
    def resetBlock(
      using Arena
    ): Block
end AlwaysFFApi

/** `sv.assign`. */
class Assign(val _operation: Operation)
trait AssignApi extends HasOperation[Assign]:
  def op(
    dest:        Value,
    src:         Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Assign
end AssignApi

/** `sv.bind`. */
class Bind(val _operation: Operation)
trait BindApi extends HasOperation[Bind]:
  def op(
    instance:    Attribute,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Bind
end BindApi

/** `sv.bind.interface`. */
class BindInterface(val _operation: Operation)
trait BindInterfaceApi extends HasOperation[BindInterface]:
  def op(
    instance:    Attribute,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): BindInterface
end BindInterfaceApi

/** `sv.bpassign`. */
class BPAssign(val _operation: Operation)
trait BPAssignApi extends HasOperation[BPAssign]:
  def op(
    dest:        Value,
    src:         Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): BPAssign
end BPAssignApi

/** `sv.case`. */
class Case(val _operation: Operation)

/** Procedural case. Patterns are encoded bit patterns, HW enum fields, expression markers, or a unit default.
  * caseValues contains one value per expression marker, in pattern order.
  */
trait CaseApi extends HasOperation[Case]:
  def op(
    caseStyle:           CaseStyle = CaseStyle.Case,
    cond:                Value,
    casePatterns:        Seq[Attribute],
    caseValues:          Seq[Value] = Seq.empty,
    validationQualifier: ValidationQualifier = ValidationQualifier.Plain,
    location:            Location
  )(
    using arena:         Arena,
    context:             Context
  ):   Case
  extension (ref: Case)
    def caseBlock(
      index: Int
    )(
      using Arena
    ): Block
end CaseApi

/** `sv.error`. */
class Error(val _operation: Operation)
trait ErrorApi extends HasOperation[Error]:
  def op(
    message:       Option[String] = None,
    substitutions: Seq[Value] = Seq.empty,
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ): Error
end ErrorApi

/** `sv.error.procedural`. */
class ErrorProcedural(val _operation: Operation)
trait ErrorProceduralApi extends HasOperation[ErrorProcedural]:
  def op(
    message:       Option[String] = None,
    substitutions: Seq[Value] = Seq.empty,
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ): ErrorProcedural
end ErrorProceduralApi

/** `sv.exit`. */
class Exit(val _operation: Operation)
trait ExitApi extends HasOperation[Exit]:
  def op(
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Exit
end ExitApi

/** `sv.fatal`. */
class Fatal(val _operation: Operation)
trait FatalApi extends HasOperation[Fatal]:
  def op(
    verbosity:     Verbosity = Verbosity.One,
    message:       Option[String] = None,
    substitutions: Seq[Value] = Seq.empty,
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ): Fatal
end FatalApi

/** `sv.fatal.procedural`. */
class FatalProcedural(val _operation: Operation)
trait FatalProceduralApi extends HasOperation[FatalProcedural]:
  def op(
    verbosity:     Verbosity = Verbosity.One,
    message:       Option[String] = None,
    substitutions: Seq[Value] = Seq.empty,
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ): FatalProcedural
end FatalProceduralApi

/** `sv.fclose`. */
class FClose(val _operation: Operation)
trait FCloseApi extends HasOperation[FClose]:
  def op(
    fd:          Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): FClose
end FCloseApi

/** `sv.fflush`. */
class FFlush(val _operation: Operation)
trait FFlushApi extends HasOperation[FFlush]:
  def op(
    fd:          Option[Value] = None,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): FFlush
end FFlushApi

/** `sv.finish`. */
class Finish(val _operation: Operation)
trait FinishApi extends HasOperation[Finish]:
  def op(
    verbosity:   Verbosity = Verbosity.One,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Finish
end FinishApi

/** `sv.for`. */
class For(val _operation: Operation)
trait ForApi extends HasOperation[For]:
  def op(
    lowerBound:       Value,
    upperBound:       Value,
    step:             Value,
    inductionVarName: String = "",
    location:         Location
  )(
    using arena:      Arena,
    context:          Context
  ): For
  extension (ref: For)
    def block(
      using Arena
    ): Block
    def inductionVar(
      using Arena
    ): Value
end ForApi

/** `sv.force`. */
class Force(val _operation: Operation)
trait ForceApi extends HasOperation[Force]:
  def op(
    dest:        Value,
    src:         Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Force
end ForceApi

/** `sv.func`. */
class Func(val _operation: Operation)

/** Declare or define an SV function. perArgumentAttrs follows moduleType port order; inputLocs and resultLocs follow
  * input/output order separately. An explicit return is the final output port marked sv.func.explicitly_returned.
  * Definitions require a Return terminator.
  */
trait FuncApi extends HasOperation[Func]:
  def op(
    symName:          String,
    symVisibility:    Option[String] = None,
    moduleType:       Type,
    perArgumentAttrs: Option[Seq[Attribute]] = None,
    inputLocs:        Option[Seq[Location]] = None,
    resultLocs:       Option[Seq[Location]] = None,
    verilogName:      Option[String] = None,
    isDeclaration:    Boolean = true,
    location:         Location
  )(
    using arena:      Arena,
    context:          Context
  ):   Func
  extension (ref: Func)
    def block(
      using Arena
    ): Block
end FuncApi

/** `sv.func.call`. */
class FuncCall(val _operation: Operation)

/** Nonprocedural call: the referenced function must have one explicitly returned value and no output arguments. Input
  * and result types must match its declaration.
  */
trait FuncCallApi extends HasOperation[FuncCall]:
  def op(
    callee:      String,
    inputs:      Seq[Value] = Seq.empty,
    resultType:  Type,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ):   FuncCall
  extension (ref: FuncCall)
    def result(
      using Arena
    ): Value

end FuncCallApi

/** `sv.func.call.procedural`. */
class FuncCallProcedural(val _operation: Operation)

/** Procedural call: inputs and resultTypes follow the referenced function signature, including output arguments and the
  * optional final explicit return. Empty results represent a void function.
  */
trait FuncCallProceduralApi extends HasOperation[FuncCallProcedural]:
  def op(
    callee:      String,
    inputs:      Seq[Value] = Seq.empty,
    resultTypes: Seq[Type] = Seq.empty,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): FuncCallProcedural
  extension (ref: FuncCallProcedural)
    def result(
      index: Int
    )(
      using Arena
    ): Value
    def results(
      using Arena
    ): Seq[Value]
end FuncCallProceduralApi

/** `sv.func.dpi.import`. */
class FuncDPIImport(val _operation: Operation)
trait FuncDPIImportApi extends HasOperation[FuncDPIImport]:
  def op(
    callee:      String,
    linkageName: Option[String] = None,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): FuncDPIImport
end FuncDPIImportApi

/** `sv.fwrite`. */
class FWrite(val _operation: Operation)
trait FWriteApi extends HasOperation[FWrite]:
  def op(
    fd:            Value,
    formatString:  String,
    substitutions: Seq[Value] = Seq.empty,
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ): FWrite
end FWriteApi

/** `sv.if`. */
class If(val _operation: Operation)
trait IfApi extends HasOperation[If]:
  def op(
    cond:        Value,
    hasElse:     Boolean = false,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): If
  extension (ref: If)
    def condBlock(
      using Arena
    ): Block
    def elseBlock(
      using Arena
    ): Block
end IfApi

/** `sv.ifdef`. */
class IfDef(val _operation: Operation)
trait IfDefApi extends HasOperation[IfDef]:
  def op(
    cond:        Attribute,
    hasElse:     Boolean = false,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): IfDef
  extension (ref: IfDef)
    def condBlock(
      using Arena
    ): Block
    def elseBlock(
      using Arena
    ): Block
end IfDefApi

/** `sv.ifdef.procedural`. */
class IfDefProcedural(val _operation: Operation)
trait IfDefProceduralApi extends HasOperation[IfDefProcedural]:
  def op(
    cond:        Attribute,
    hasElse:     Boolean = false,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): IfDefProcedural
  extension (ref: IfDefProcedural)
    def condBlock(
      using Arena
    ): Block
    def elseBlock(
      using Arena
    ): Block
end IfDefProceduralApi

/** `sv.include`. */
class Include(val _operation: Operation)
trait IncludeApi extends HasOperation[Include]:
  def op(
    style:       IncludeStyle,
    target:      String,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Include
end IncludeApi

/** `sv.info`. */
class Info(val _operation: Operation)
trait InfoApi extends HasOperation[Info]:
  def op(
    message:       Option[String] = None,
    substitutions: Seq[Value] = Seq.empty,
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ): Info
end InfoApi

/** `sv.info.procedural`. */
class InfoProcedural(val _operation: Operation)
trait InfoProceduralApi extends HasOperation[InfoProcedural]:
  def op(
    message:       Option[String] = None,
    substitutions: Seq[Value] = Seq.empty,
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ): InfoProcedural
end InfoProceduralApi

/** `sv.initial`. */
class Initial(val _operation: Operation)
trait InitialApi extends HasOperation[Initial]:
  def op(
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ):   Initial
  extension (ref: Initial)
    def block(
      using Arena
    ): Block
end InitialApi

/** `sv.macro.decl`. */
class MacroDecl(val _operation: Operation)

/** Declare a macro symbol. args=None is object-like; Some(Seq.empty) is a function-like macro with zero arguments. */
trait MacroDeclApi extends HasOperation[MacroDecl]:
  def op(
    symName:       String,
    symVisibility: Option[String] = None,
    args:          Option[Seq[String]] = None,
    verilogName:   Option[String] = None,
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ): MacroDecl
end MacroDeclApi

/** `sv.macro.def`. */
class MacroDef(val _operation: Operation)
trait MacroDefApi extends HasOperation[MacroDef]:
  def op(
    macroName:    String,
    formatString: String,
    symbols:      Seq[Attribute] = Seq.empty,
    location:     Location
  )(
    using arena:  Arena,
    context:      Context
  ): MacroDef
end MacroDefApi

/** `sv.macro.error`. */
class MacroError(val _operation: Operation)
trait MacroErrorApi extends HasOperation[MacroError]:
  def op(
    message:     Option[String] = None,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): MacroError
end MacroErrorApi

/** `sv.macro.ref`. */
class MacroRef(val _operation: Operation)
trait MacroRefApi extends HasOperation[MacroRef]:
  def op(
    macroName:   String,
    inputs:      Seq[Value] = Seq.empty,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): MacroRef
end MacroRefApi

/** `sv.nonstandard.deposit`. */
class Deposit(val _operation: Operation)
trait DepositApi extends HasOperation[Deposit]:
  def op(
    dest:        Value,
    src:         Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Deposit
end DepositApi

/** `sv.ordered`. */
class OrderedOutput(val _operation: Operation)
trait OrderedOutputApi extends HasOperation[OrderedOutput]:
  def op(
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ):   OrderedOutput
  extension (ref: OrderedOutput)
    def block(
      using Arena
    ): Block
end OrderedOutputApi

/** `sv.passign`. */
class PAssign(val _operation: Operation)
trait PAssignApi extends HasOperation[PAssign]:
  def op(
    dest:        Value,
    src:         Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): PAssign
end PAssignApi

/** `sv.readmem`. */
class ReadMem(val _operation: Operation)
trait ReadMemApi extends HasOperation[ReadMem]:
  def op(
    dest:        Value,
    filename:    String,
    base:        MemoryBase,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): ReadMem
end ReadMemApi

/** `sv.release`. */
class Release(val _operation: Operation)
trait ReleaseApi extends HasOperation[Release]:
  def op(
    dest:        Value,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Release
end ReleaseApi

/** `sv.reserve_names`. */
class ReserveNames(val _operation: Operation)
trait ReserveNamesApi extends HasOperation[ReserveNames]:
  def op(
    reservedNames: Seq[String],
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ): ReserveNames
end ReserveNamesApi

/** `sv.return`. */
class Return(val _operation: Operation)
trait ReturnApi extends HasOperation[Return]:
  def op(
    values:      Seq[Value] = Seq.empty,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Return
end ReturnApi

/** `sv.stop`. */
class Stop(val _operation: Operation)
trait StopApi extends HasOperation[Stop]:
  def op(
    verbosity:   Verbosity = Verbosity.One,
    location:    Location
  )(
    using arena: Arena,
    context:     Context
  ): Stop
end StopApi

/** `sv.verbatim`. */
class Verbatim(val _operation: Operation)
trait VerbatimApi extends HasOperation[Verbatim]:
  def op(
    formatString:  String,
    substitutions: Seq[Value] = Seq.empty,
    symbols:       Seq[Attribute] = Seq.empty,
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ): Verbatim
end VerbatimApi

/** `sv.warning`. */
class Warning(val _operation: Operation)
trait WarningApi extends HasOperation[Warning]:
  def op(
    message:       Option[String] = None,
    substitutions: Seq[Value] = Seq.empty,
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ): Warning
end WarningApi

/** `sv.warning.procedural`. */
class WarningProcedural(val _operation: Operation)
trait WarningProceduralApi extends HasOperation[WarningProcedural]:
  def op(
    message:       Option[String] = None,
    substitutions: Seq[Value] = Seq.empty,
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ): WarningProcedural
end WarningProceduralApi

/** `sv.write`. */
class Write(val _operation: Operation)
trait WriteApi extends HasOperation[Write]:
  def op(
    formatString:  String,
    substitutions: Seq[Value] = Seq.empty,
    location:      Location
  )(
    using arena:   Arena,
    context:       Context
  ): Write
end WriteApi
