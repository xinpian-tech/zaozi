// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.circtlib.tests

import org.llvm.circt.scalalib.capi.dialect.hw.{DialectApi as HWDialect, given}
import org.llvm.circt.scalalib.capi.dialect.ltl.given
import org.llvm.circt.scalalib.capi.dialect.sv.{AttributeApi as SVAttributeApi, DialectApi as SVDialect, given}
import org.llvm.circt.scalalib.capi.exportverilog.given
import org.llvm.circt.scalalib.dialect.sv.tpe.{TypeApi as SVTypeApi, given}
import org.llvm.circt.scalalib.dialect.sv.attr.{AttributeApi as SVIRAttributeApi, *, given}
import org.llvm.circt.scalalib.dialect.sv.operation.{*, given}
import org.llvm.mlir.scalalib.capi.ir.{*, given}
import org.llvm.mlir.scalalib.capi.support.{*, given}
import java.lang.foreign.{Arena, MemorySegment}
import utest.*

object SVSmoke extends TestSuite:
  // SV operation inventory at the CIRCT revision pinned in flake.lock.
  private val expectedOperations: Set[String] = Set(
    "sv.alias",
    "sv.always",
    "sv.alwayscomb",
    "sv.alwaysff",
    "sv.array_index_inout",
    "sv.assert",
    "sv.assert.concurrent",
    "sv.assert_property",
    "sv.assign",
    "sv.assume",
    "sv.assume.concurrent",
    "sv.assume_property",
    "sv.bind",
    "sv.bind.interface",
    "sv.bpassign",
    "sv.case",
    "sv.concat_str",
    "sv.constantStr",
    "sv.constantX",
    "sv.constantZ",
    "sv.cover",
    "sv.cover.concurrent",
    "sv.cover_property",
    "sv.error",
    "sv.error.procedural",
    "sv.exit",
    "sv.fatal",
    "sv.fatal.procedural",
    "sv.fclose",
    "sv.fflush",
    "sv.finish",
    "sv.for",
    "sv.force",
    "sv.func",
    "sv.func.call",
    "sv.func.call.procedural",
    "sv.func.dpi.import",
    "sv.fwrite",
    "sv.generate",
    "sv.generate.case",
    "sv.generate.for",
    "sv.if",
    "sv.ifdef",
    "sv.ifdef.procedural",
    "sv.include",
    "sv.indexed_part_select",
    "sv.indexed_part_select_inout",
    "sv.info",
    "sv.info.procedural",
    "sv.initial",
    "sv.interface",
    "sv.interface.instance",
    "sv.interface.modport",
    "sv.interface.signal",
    "sv.interface.signal.assign",
    "sv.interface.signal.read",
    "sv.localparam",
    "sv.logic",
    "sv.macro.decl",
    "sv.macro.def",
    "sv.macro.error",
    "sv.macro.ref",
    "sv.macro.ref.expr",
    "sv.macro.ref.expr.se",
    "sv.modport.get",
    "sv.nonstandard.deposit",
    "sv.ordered",
    "sv.package",
    "sv.passign",
    "sv.read_inout",
    "sv.readmem",
    "sv.reg",
    "sv.release",
    "sv.reserve_names",
    "sv.return",
    "sv.sformatf",
    "sv.stop",
    "sv.struct_field_inout",
    "sv.system",
    "sv.system.sampled",
    "sv.system.stime",
    "sv.system.time",
    "sv.unpacked_array_create",
    "sv.unpacked_open_array_cast",
    "sv.verbatim",
    "sv.verbatim.expr",
    "sv.verbatim.expr.se",
    "sv.verbatim.module",
    "sv.verbatim.source",
    "sv.warning",
    "sv.warning.procedural",
    "sv.wire",
    "sv.write",
    "sv.xmr",
    "sv.xmr.ref"
  )

  private class Fixture:
    given Arena   = Arena.ofAuto()
    given Context = summon[ContextApi].contextCreate
    summon[HWDialect].loadDialect
    summon[SVDialect].loadDialect
    val loc       = summon[LocationApi].locationUnknownGet
    val module    = summon[ModuleApi].moduleCreateParse(
      "module { hw.module @Top(in %clock: i1, in %reset: i1) { hw.instance \"child\" sym @child @Child() -> () {doNotPrint} hw.output } hw.module.extern @Child() }"
    )
    val root      = module.getBody
    val hw        = root.getFirstOperation
    val body      = hw.getFirstRegion.getFirstBlock
    val clock     = body.getArgument(0)
    val reset     = body.getArgument(1)
    val i1        = 1.integerTypeGet
    val i8        = 8.integerTypeGet
    val i32       = 32.integerTypeGet
    val i64       = 64.integerTypeGet
    val built     = scala.collection.mutable.Set.empty[String]
    def tpe(text: String):    Type      =
      Type(org.llvm.mlir.CAPI.mlirTypeParseGet(summon[Arena], summon[Context].segment, text.toStringRef.segment))
    def attr(text: String):   Attribute =
      Attribute(
        org.llvm.mlir.CAPI.mlirAttributeParseGet(summon[Arena], summon[Context].segment, text.toStringRef.segment)
      )
    def add[T](
      op:        T,
      block:     Block = body
    )(
      using api: HasOperation[T]
    ): T =
      built += api.operation(op).getName.str
      if block eq body then
        api
          .operation(op)
          .insertBefore(body.getTerminator)(
            using body
          )
      else
        api
          .operation(op)
          .appendToBlock()(
            using block
          )
      op
    def constant(value: Int): Value     =
      val op = summon[OperationApi].operationCreate(
        "hw.constant",
        loc,
        namedAttributes =
          Seq(summon[NamedAttributeApi].namedAttributeGet("value".identifierGet, value.toLong.integerAttrGet(i32))),
        resultsTypes = Some(Seq(i32))
      )
      op.insertBefore(body.getTerminator)(
        using body
      )
      op.getResult(0)
    def checkExport():        String    =
      assert(module.getOperation.verify)
      val text = StringBuilder()
      assert(module.exportVerilog(text ++= _).succeeded)
      text.toString

  val tests: Tests = Tests:
    test("native types and attributes"):
      val f     = new Fixture
      import f.given
      val types = summon[SVTypeApi]
      def printed(tpe: Type): String =
        val out = StringBuilder()
        tpe.print(out ++= _)
        out.toString
      assert(printed(types.getNet(f.i8)) == "!sv.net<i8>")
      assert(printed(types.getVar(f.i8)) == "!sv.var<i8>")
      assert(printed(types.getUnpackedOpenArray(f.i8)) == "!sv.open_uarray<i8>")
      assert(printed(types.getInterface("Bus")) == "!sv.interface<@Bus>")
      assert(printed(types.getModport("Bus", "sink")) == "!sv.modport<@Bus::@sink>")
      val a = summon[SVAttributeApi].getSVAttribute("keep", Some("true"), true)
      assert(
        a.isSVAttribute,
        a.getSVAttributeName == "keep",
        a.getSVAttributeExpression == Some("true"),
        a.getSVAttributeEmitAsComment
      )
      val absent = summon[SVAttributeApi].getSVAttribute("keep")
      val empty  = summon[SVAttributeApi].getSVAttribute("keep", Some(""))
      // The constructor preserves absent versus empty in IR. The native getter
      // returns a null StringRef for either, so it cannot recover that distinction.
      assert(!org.llvm.mlir.CAPI.mlirAttributeEqual(absent.segment, empty.segment))
      assert(absent.getSVAttributeExpression.isEmpty, empty.getSVAttributeExpression.isEmpty)
      assert(summon[SVIRAttributeApi].getCasePattern("01xz").integerAttrGetValueInt == 27)

    test("all SV operations verify and roundtrip"):
      val f               = new Fixture
      import f.*
      import f.given
      val zero            = constant(0)
      val one             = constant(1)
      val four            = constant(4)
      val wire            = add(
        summon[WireApi].op(
          name = "wireValue",
          innerSym = Some(attr("#hw<innerSym@wireValue>")),
          elementType = i32,
          location = loc
        )
      ).result
      val reg             =
        add(
          summon[RegApi].op(name = "state", init = Some(zero), elementType = i32, location = loc)
        ).result
      val logic           =
        add(summon[LogicApi].op(name = "logicValue", elementType = i32, location = loc)).result
      add(summon[AssignApi].op(dest = wire, src = one, location = loc))
      add(summon[AliasApi].op(aliases = Seq(wire, logic), location = loc))
      val read            = add(summon[ReadInOutApi].op(input = wire, location = loc)).result
      add(summon[ConstantXApi].op(resultType = i8, location = loc))
      add(summon[ConstantZApi].op(resultType = i8, location = loc))
      val str             = add(summon[ConstantStrApi].op(str = "value=", location = loc)).result
      val formatted       = add(
        summon[SFormatFApi].op(
          formatString = "%d",
          substitutions = Seq(read),
          location = loc
        )
      ).result
      add(summon[ConcatStrApi].op(inputs = Seq(str, formatted), location = loc))
      add(summon[LocalParamApi].op(value = 4L.integerAttrGet(i32), name = "WIDTH", location = loc))
      add(summon[IndexedPartSelectApi].op(input = read, base = zero, width = 8, location = loc))
      add(summon[IndexedPartSelectInOutApi].op(input = wire, base = zero, width = 8, location = loc))
      val array           = add(
        summon[UnpackedArrayCreateApi].op(
          inputs = Seq(zero, one),
          location = loc
        )
      ).result
      add(
        summon[UnpackedOpenArrayCastApi]
          .op(input = array, resultType = summon[SVTypeApi].getUnpackedOpenArray(i32), location = loc)
      )
      val memory          =
        add(summon[RegApi].op(name = "memory", elementType = tpe("!hw.array<4xi32>"), location = loc)).result
      add(
        summon[ArrayIndexInOutApi].op(input = memory, index = zero, resultType = tpe("!hw.inout<i32>"), location = loc)
      )
      val struct          = add(
        summon[WireApi].op(name = "record", elementType = tpe("!hw.struct<field: i32>"), location = loc)
      ).result
      add(summon[StructFieldInOutApi].op(input = struct, field = "field", location = loc))
      add(
        summon[VerbatimExprApi].op(formatString = "{{0}}", substitutions = Seq(read), resultType = i32, location = loc)
      )
      add(summon[VerbatimExprSEApi].op(formatString = "$random", resultType = i32, location = loc))
      add(summon[SystemFunctionApi].op(fnName = "random", resultType = i32, location = loc))
      add(summon[SampledApi].op(expression = clock, location = loc))
      add(summon[TimeApi].op(location = loc))
      add(summon[STimeApi].op(location = loc))
      add(
        summon[XMRApi].op(path = Seq("child"), terminal = "signal", resultType = tpe("!hw.inout<i32>"), location = loc)
      )
      val ordered         = add(summon[OrderedOutputApi].op(location = loc))
      add(summon[VerbatimApi].op(formatString = "// ordered", location = loc), ordered.block)
      val initial         = add(summon[InitialApi].op(location = loc)).block
      add(summon[BPAssignApi].op(dest = reg, src = zero, location = loc), initial)
      add(summon[ForceApi].op(dest = reg, src = one, location = loc), initial)
      add(summon[ReleaseApi].op(dest = reg, location = loc), initial)
      add(summon[DepositApi].op(dest = reg, src = zero, location = loc), initial)
      add(summon[WriteApi].op(formatString = "value=%d", substitutions = Seq(read), location = loc), initial)
      add(summon[FWriteApi].op(fd = one, formatString = "%d", substitutions = Seq(read), location = loc), initial)
      add(summon[FFlushApi].op(location = loc), initial)
      add(summon[FFlushApi].op(fd = Some(one), location = loc), initial)
      add(summon[FCloseApi].op(fd = one, location = loc), initial)
      add(
        summon[ReadMemApi].op(dest = memory, filename = "init.hex", base = MemoryBase.Hexadecimal, location = loc),
        initial
      )
      add(summon[StopApi].op(verbosity = Verbosity.One, location = loc), initial)
      add(summon[FinishApi].op(verbosity = Verbosity.Two, location = loc), initial)
      add(summon[ExitApi].op(location = loc), initial)
      add(
        summon[FatalProceduralApi]
          .op(verbosity = Verbosity.Zero, message = Some("failure %d"), substitutions = Seq(read), location = loc),
        initial
      )
      add(summon[ErrorProceduralApi].op(message = Some("error"), location = loc), initial)
      add(summon[WarningProceduralApi].op(message = Some("warning"), location = loc), initial)
      add(summon[InfoProceduralApi].op(message = Some("info"), location = loc), initial)
      add(summon[FatalApi].op(verbosity = Verbosity.One, message = Some("failure"), location = loc))
      add(summon[ErrorApi].op(message = Some("error"), location = loc))
      add(summon[WarningApi].op(message = Some("warning"), location = loc))
      add(summon[InfoApi].op(message = Some("info"), location = loc))
      val branch          = add(summon[IfApi].op(cond = clock, hasElse = true, location = loc), initial)
      add(summon[BPAssignApi].op(dest = reg, src = one, location = loc), branch.condBlock)
      add(summon[BPAssignApi].op(dest = reg, src = zero, location = loc), branch.elseBlock)
      val cases           = add(
        summon[CaseApi].op(
          cond = clock,
          casePatterns = Seq(
            summon[SVIRAttributeApi].getCasePattern("0"),
            summon[SVIRAttributeApi].getCaseExprPattern,
            summon[SVIRAttributeApi].getCaseDefault
          ),
          caseValues = Seq(reset),
          validationQualifier = ValidationQualifier.Unique,
          location = loc
        ),
        initial
      )
      add(summon[BPAssignApi].op(dest = reg, src = zero, location = loc), cases.caseBlock(0))
      add(summon[BPAssignApi].op(dest = reg, src = one, location = loc), cases.caseBlock(1))
      add(summon[BPAssignApi].op(dest = reg, src = four, location = loc), cases.caseBlock(2))
      val loop            = add(
        summon[ForApi].op(lowerBound = zero, upperBound = four, step = one, inductionVarName = "i", location = loc),
        initial
      )
      add(summon[BPAssignApi].op(dest = reg, src = loop.inductionVar, location = loc), loop.block)
      val always          = add(
        summon[AlwaysApi]
          .op(events = Seq(EventControl.PosEdge, EventControl.NegEdge), clocks = Seq(clock, reset), location = loc)
      )
      add(summon[PAssignApi].op(dest = reg, src = one, location = loc), always.block)
      val comb            = add(summon[AlwaysCombApi].op(location = loc))
      add(summon[BPAssignApi].op(dest = logic, src = read, location = loc), comb.block)
      val ff              = add(
        summon[AlwaysFFApi].op(
          clockEdge = EventControl.PosEdge,
          clock = clock,
          resetStyle = ResetStyle.AsyncReset,
          resetEdge = EventControl.NegEdge,
          reset = reset,
          location = loc
        )
      )
      add(summon[PAssignApi].op(dest = reg, src = one, location = loc), ff.block)
      add(summon[PAssignApi].op(dest = reg, src = zero, location = loc), ff.resetBlock)
      add(
        summon[AssertApi]
          .op(expression = clock, defer = DeferAssert.Immediate, message = Some("clock"), location = loc),
        initial
      )
      add(summon[AssumeApi].op(expression = reset, defer = DeferAssert.Observed, location = loc), initial)
      add(summon[CoverApi].op(expression = clock, defer = DeferAssert.Final, location = loc), initial)
      add(summon[AssertConcurrentApi].op(event = EventControl.PosEdge, clock = clock, property = reset, location = loc))
      add(summon[AssumeConcurrentApi].op(event = EventControl.NegEdge, clock = clock, property = reset, location = loc))
      add(summon[CoverConcurrentApi].op(event = EventControl.Edge, clock = clock, property = reset, location = loc))
      add(
        summon[AssertPropertyApi].op(
          property = reset,
          event = Some(EventControl.PosEdge),
          clock = Some(clock),
          disable = Some(reset),
          label = Some("safety"),
          location = loc
        )
      )
      add(summon[AssumePropertyApi].op(property = reset, location = loc))
      add(summon[CoverPropertyApi].op(property = reset, disable = Some(clock), location = loc))
      add(summon[MacroDeclApi].op(symName = "FEATURE", location = loc), root)
      add(summon[MacroDeclApi].op(symName = "IDENTITY", args = Some(Seq("x")), location = loc), root)
      add(summon[MacroDefApi].op(macroName = "FEATURE", formatString = "1", location = loc), root)
      add(summon[MacroDefApi].op(macroName = "IDENTITY", formatString = "x", location = loc), root)
      add(summon[MacroRefApi].op(macroName = "FEATURE", location = loc))
      add(summon[MacroRefExprApi].op(macroName = "IDENTITY", inputs = Seq(read), resultType = i32, location = loc))
      add(summon[MacroRefExprSEApi].op(macroName = "IDENTITY", inputs = Seq(read), resultType = i32, location = loc))
      val ifdef           = add(
        summon[IfDefApi].op(cond = summon[SVIRAttributeApi].getMacroIdent("FEATURE"), hasElse = true, location = loc)
      )
      add(summon[MacroErrorApi].op(message = Some("feature required"), location = loc), ifdef.elseBlock)
      val proceduralIfdef = add(
        summon[IfDefProceduralApi].op(cond = summon[SVIRAttributeApi].getMacroIdent("FEATURE"), location = loc),
        initial
      )
      add(summon[WriteApi].op(formatString = "feature", location = loc), proceduralIfdef.condBlock)
      add(summon[IncludeApi].op(style = IncludeStyle.Local, target = "cells.svh", location = loc), root)
      add(summon[IncludeApi].op(style = IncludeStyle.System, target = "system.svh", location = loc), root)
      add(summon[ReserveNamesApi].op(reservedNames = Seq("reserved_cell"), location = loc), root)
      val pack            = add(summon[PackageApi].op(symName = "Constants", location = loc), root)
      val iface           = add(summon[InterfaceApi].op(symName = "Bus", comment = Some("external bus"), location = loc), root)
      add(summon[InterfaceSignalApi].op(symName = "data", tpe = i32, location = loc), iface.block)
      add(
        summon[InterfaceModportApi].op(
          symName = "sink",
          ports = Seq(summon[SVIRAttributeApi].getModportStruct(ModportDirection.Input, "data")),
          location = loc
        ),
        iface.block
      )
      val instance        = add(
        summon[InterfaceInstanceApi].op(
          name = "bus",
          innerSym = Some(attr("#hw<innerSym@bus>")),
          doNotPrint = true,
          resultType = summon[SVTypeApi].getInterface("Bus"),
          location = loc
        )
      ).result
      add(
        summon[GetModportApi].op(
          iface = instance,
          field = "sink",
          resultType = summon[SVTypeApi].getModport("Bus", "sink"),
          location = loc
        )
      )
      add(summon[AssignInterfaceSignalApi].op(iface = instance, signalName = "data", rhs = read, location = loc))
      add(summon[ReadInterfaceSignalApi].op(iface = instance, signalName = "data", resultType = i32, location = loc))
      add(summon[BindInterfaceApi].op(instance = attr("#hw.innerNameRef<@Top::@bus>"), location = loc), root)
      add(summon[BindApi].op(instance = attr("#hw.innerNameRef<@Top::@child>"), location = loc), root)
      val path            = summon[OperationApi].operationCreate(
        "hw.hierpath",
        loc,
        namedAttributes = Seq(
          summon[NamedAttributeApi].namedAttributeGet("sym_name".identifierGet, "wirePath".stringAttrGet),
          summon[NamedAttributeApi]
            .namedAttributeGet("namepath".identifierGet, Seq(attr("#hw.innerNameRef<@Top::@wireValue>")).arrayAttrGet)
        ),
        resultsTypes = Some(Seq.empty)
      )
      path.appendToBlock()(
        using root
      )
      add(summon[XMRRefApi].op(reference = "wirePath", resultType = tpe("!hw.inout<i32>"), location = loc))
      val generate        = add(summon[GenerateApi].op(symName = "gen", location = loc))
      val generateCase    = add(
        summon[GenerateCaseApi].op(
          cond = 1L.integerAttrGet(i32),
          casePatterns = Seq(0L.integerAttrGet(i32), summon[SVIRAttributeApi].getCaseDefault),
          caseNames = Seq("zero", "other"),
          location = loc
        ),
        generate.block
      )
      add(summon[VerbatimApi].op(formatString = "// zero branch", location = loc), generateCase.caseBlock(0))
      add(summon[VerbatimApi].op(formatString = "// selected branch", location = loc), generateCase.caseBlock(1))
      val generateFor     = add(
        summon[GenerateForApi].op(
          lowerBound = 0L.integerAttrGet(i32),
          upperBound = 4L.integerAttrGet(i32),
          step = 1L.integerAttrGet(i32),
          genBlockName = "lane",
          inductionVarName = Some("j"),
          location = loc
        ),
        generate.block
      )
      add(summon[VerbatimApi].op(formatString = "// lane", location = loc), generateFor.block)
      val functionType    = tpe("!hw.modty<input arg: i32, output result: i32>")
      val argumentAttrs   = Some(Seq(attr("{}"), attr("{sv.func.explicitly_returned}")))
      val function        = add(
        summon[FuncApi].op(
          symName = "identity",
          moduleType = functionType,
          perArgumentAttrs = argumentAttrs,
          isDeclaration = false,
          location = loc
        ),
        root
      )
      add(summon[ReturnApi].op(values = Seq(function.block.getArgument(0)), location = loc), function.block)
      add(
        summon[FuncApi].op(
          symName = "foreign",
          symVisibility = Some("private"),
          moduleType = functionType,
          perArgumentAttrs = argumentAttrs,
          inputLocs = Some(Seq(loc)),
          resultLocs = Some(Seq(loc)),
          location = loc
        ),
        root
      )
      add(summon[FuncDPIImportApi].op(callee = "foreign", linkageName = Some("c_identity"), location = loc), root)
      add(summon[FuncCallApi].op(callee = "identity", inputs = Seq(read), resultType = i32, location = loc))
      add(
        summon[FuncCallProceduralApi]
          .op(callee = "foreign", inputs = Seq(read), resultTypes = Seq(i32), location = loc),
        initial
      )
      add(
        summon[SVVerbatimSourceApi].op(
          symName = "cell_source",
          content = "module Cell(input logic [31:0] a, output logic [31:0] y); assign y = a; endmodule",
          outputFile = attr("#hw.output_file<\"Cell.sv\">"),
          parameters = Seq.empty,
          verilogName = "Cell",
          location = loc
        ),
        root
      )
      add(
        summon[SVVerbatimModuleApi]
          .op(
            location = loc,
            symName = "Cell",
            moduleType = tpe("!hw.modty<input a: i32, output y: i32>"),
            perPortAttrs = Some(Seq(attr("{}"), attr("{}"))),
            portLocs = Some(Seq(loc, loc)),
            parameters = Seq.empty,
            source = "cell_source"
          ),
        root
      )
      assert(built.toSet == expectedOperations)
      assert(module.getOperation.verify)
      val ir              = StringBuilder()
      module.getOperation.print(ir ++= _)
      val roundtrip       = summon[ModuleApi].moduleCreateParse(ir.toString)
      assert(org.llvm.mlir.MlirModule.ptr(roundtrip.segment) != MemorySegment.NULL)
      assert(roundtrip.getOperation.verify)
      val printed         = StringBuilder()
      roundtrip.getOperation.print(printed ++= _)
      assert(printed.toString == ir.toString)

    test("sequential logic macros interfaces and functions export"):
      val f        = new Fixture
      import f.*
      import f.given
      val zero     = constant(0)
      val one      = constant(1)
      val reg      = add(summon[RegApi].op(name = "state", elementType = i32, location = loc)).result
      val ff       = add(
        summon[AlwaysFFApi].op(
          clockEdge = EventControl.PosEdge,
          clock = clock,
          resetStyle = ResetStyle.AsyncReset,
          resetEdge = EventControl.NegEdge,
          reset = reset,
          location = loc
        )
      )
      add(summon[PAssignApi].op(dest = reg, src = one, location = loc), ff.block)
      add(summon[PAssignApi].op(dest = reg, src = zero, location = loc), ff.resetBlock)
      add(
        summon[AssertPropertyApi].op(
          property = reset,
          event = Some(EventControl.PosEdge),
          clock = Some(clock),
          disable = Some(reset),
          label = Some("safety"),
          location = loc
        )
      )
      val initial  = add(summon[InitialApi].op(location = loc)).block
      val cases    = add(
        summon[CaseApi].op(
          cond = clock,
          casePatterns = Seq(summon[SVIRAttributeApi].getCasePattern("0"), summon[SVIRAttributeApi].getCaseDefault),
          validationQualifier = ValidationQualifier.Unique,
          location = loc
        ),
        initial
      )
      add(summon[WriteApi].op(formatString = "zero", location = loc), cases.caseBlock(0))
      add(summon[WriteApi].op(formatString = "one", location = loc), cases.caseBlock(1))
      add(summon[MacroDeclApi].op(symName = "FEATURE", location = loc), root)
      add(summon[MacroDefApi].op(macroName = "FEATURE", formatString = "1", location = loc), root)
      val ifdef    = add(summon[IfDefApi].op(cond = summon[SVIRAttributeApi].getMacroIdent("FEATURE"), location = loc))
      add(summon[VerbatimApi].op(formatString = "// feature enabled", location = loc), ifdef.condBlock)
      val iface    = add(summon[InterfaceApi].op(symName = "Bus", location = loc), root)
      add(summon[InterfaceSignalApi].op(symName = "data", tpe = i32, location = loc), iface.block)
      add(
        summon[InterfaceModportApi].op(
          symName = "sink",
          ports = Seq(summon[SVIRAttributeApi].getModportStruct(ModportDirection.Input, "data")),
          location = loc
        ),
        iface.block
      )
      val function = add(
        summon[FuncApi].op(
          symName = "identity",
          moduleType = tpe("!hw.modty<input arg: i32, output result: i32>"),
          perArgumentAttrs = Some(Seq(attr("{}"), attr("{sv.func.explicitly_returned}"))),
          isDeclaration = false,
          location = loc
        ),
        root
      )
      add(summon[ReturnApi].op(values = Seq(function.block.getArgument(0)), location = loc), function.block)
      add(
        summon[FuncCallProceduralApi]
          .op(callee = "identity", inputs = Seq(one), resultTypes = Seq(i32), location = loc),
        initial
      )
      val text     = checkExport()
      assert(
        text.contains("always_ff"),
        text.contains("assert property"),
        text.contains("unique case"),
        text.contains("`ifdef FEATURE"),
        text.contains("modport sink"),
        text.contains("function automatic")
      )

    test("reset regions property segments and implicit result types"):
      val f               = new Fixture
      import f.*
      import f.given
      val zero            = constant(0)
      val one             = constant(1)
      val noReset         = add(summon[AlwaysFFApi].op(EventControl.PosEdge, clock, loc))
      val plainState      = add(summon[RegApi].op(i32, name = "plain_state", location = loc)).result
      add(summon[PAssignApi].op(plainState, one, loc), noReset.block)
      assert(org.llvm.mlir.MlirBlock.ptr(noReset.operation.getRegion(1).getFirstBlock.segment) == MemorySegment.NULL)
      val sync            =
        add(summon[AlwaysFFApi].op(EventControl.PosEdge, clock, ResetStyle.SyncReset, EventControl.PosEdge, reset, loc))
      val syncState       = add(summon[RegApi].op(i32, name = "sync_state", location = loc)).result
      add(summon[PAssignApi].op(syncState, one, loc), sync.block)
      add(summon[PAssignApi].op(syncState, zero, loc), sync.resetBlock)
      val string          = add(summon[ConstantStrApi].op("a\"\\\nλ", loc)).result
      val formatted       = add(summon[SFormatFApi].op("%s", Seq(string), loc)).result
      val array           = add(summon[UnpackedArrayCreateApi].op(Seq(zero, one), loc)).result
      assert(org.llvm.mlir.CAPI.mlirTypeEqual(string.getType.segment, tpe("!hw.string").segment))
      assert(org.llvm.mlir.CAPI.mlirTypeEqual(formatted.getType.segment, string.getType.segment))
      assert(org.llvm.mlir.CAPI.mlirTypeEqual(array.getType.segment, tpe("!hw.uarray<2xi32>").segment))
      val scope           = summon[OperationApi].operationCreate(
        "hw.type_scope",
        loc,
        namedAttributes =
          Seq(summon[NamedAttributeApi].namedAttributeGet("sym_name".identifierGet, "Types".stringAttrGet)),
        regionBlockTypeLocations = Seq(Seq((Seq.empty, Seq.empty)))
      )
      scope.appendToBlock()(
        using root
      )
      val declaration     = summon[OperationApi].operationCreate(
        "hw.typedecl",
        loc,
        namedAttributes = Seq(
          summon[NamedAttributeApi].namedAttributeGet("sym_name".identifierGet, "Words".stringAttrGet),
          summon[NamedAttributeApi].namedAttributeGet("type".identifierGet, array.getType.typeAttrGet)
        )
      )
      declaration.appendToBlock()(
        using scope.getFirstRegion.getFirstBlock
      )
      val namedArrayType  = tpe("!hw.typealias<@Types::@Words, !hw.uarray<2xi32>>")
      val namedArray      = add(summon[UnpackedArrayCreateApi].op(Seq(zero, one), namedArrayType, loc)).result
      assert(org.llvm.mlir.CAPI.mlirTypeEqual(namedArray.getType.segment, namedArrayType.segment))
      summon[org.llvm.circt.scalalib.capi.dialect.ltl.DialectApi].loadDialect
      val property        = summon[OperationApi].operationCreate(
        "ltl.implication",
        loc,
        operands = Seq(clock, reset),
        resultsTypes = Some(Seq(tpe("!ltl.property")))
      )
      property.insertBefore(body.getTerminator)(
        using body
      )
      val checks          = Seq(
        add(summon[AssertPropertyApi].op(property.getResult(0), location = loc)).operation -> Seq(1, 0, 0),
        add(
          summon[AssumePropertyApi].op(property.getResult(0), disable = Some(reset), location = loc)
        ).operation                                                                        -> Seq(1, 0, 1),
        add(
          summon[CoverPropertyApi]
            .op(property.getResult(0), event = Some(EventControl.PosEdge), clock = Some(clock), location = loc)
        ).operation                                                                        -> Seq(1, 1, 0),
        add(
          summon[AssertPropertyApi].op(
            property.getResult(0),
            event = Some(EventControl.NegEdge),
            clock = Some(clock),
            disable = Some(reset),
            location = loc
          )
        ).operation                                                                        -> Seq(1, 1, 1)
      )
      checks.foreach { (op, expected) =>
        val segments = op.getAttributeByName("operandSegmentSizes")
        assert(expected.indices.map(i => segments.denseI32ArrayGetElement(i)) == expected)
      }
      val initial         = add(summon[InitialApi].op(loc)).block
      val stop            = add(summon[StopApi].op(location = loc), initial)
      val finish          = add(summon[FinishApi].op(location = loc), initial)
      val fatal           = add(summon[FatalApi].op(location = loc))
      val proceduralFatal = add(summon[FatalProceduralApi].op(location = loc), initial)
      Seq(stop.operation, finish.operation, fatal.operation, proceduralFatal.operation).foreach { op =>
        assert(op.getAttributeByName("verbosity").integerAttrGetValueInt == 1)
      }
      val text            = checkExport()
      assert(
        text.contains("plain_state"),
        text.contains("sync_state"),
        text.contains("disable iff"),
        text.contains("|->")
      )

    test("function port ordering locations and void calls roundtrip"):
      val f             = new Fixture
      import f.*
      import f.given
      val sourceLoc     = summon[LocationApi].locationFileLineColGet("function.sv", 12, 3)
      val function      = add(
        summon[FuncApi].op(
          symName = "two_results",
          moduleType = tpe("!hw.modty<input a: i32, output b: i32, input c: i8, output result: i8>"),
          perArgumentAttrs = Some(Seq(attr("{}"), attr("{}"), attr("{}"), attr("{sv.func.explicitly_returned}"))),
          inputLocs = Some(Seq(sourceLoc, loc)),
          resultLocs = Some(Seq(loc, sourceLoc)),
          isDeclaration = false,
          location = loc
        ),
        root
      )
      assert(
        org.llvm.mlir.MlirAttribute
          .ptr(function.operation.getAttributeByName("input_locs").segment) == MemorySegment.NULL
      )
      assert(org.llvm.mlir.CAPI.mlirTypeEqual(function.block.getArgument(0).getType.segment, i32.segment))
      assert(org.llvm.mlir.CAPI.mlirTypeEqual(function.block.getArgument(1).getType.segment, i8.segment))
      add(summon[ReturnApi].op(Seq(function.block.getArgument(0), function.block.getArgument(1)), loc), function.block)
      val voidFunction  = add(
        summon[FuncApi].op(
          symName = "no_result",
          moduleType = tpe("!hw.modty<>"),
          isDeclaration = false,
          location = loc
        ),
        root
      )
      add(summon[ReturnApi].op(location = loc), voidFunction.block)
      val inoutFunction = add(
        summon[FuncApi].op(
          symName = "touch",
          moduleType = tpe("!hw.modty<inout data: i32>"),
          isDeclaration = false,
          location = loc
        ),
        root
      )
      assert(
        org.llvm.mlir.CAPI.mlirTypeEqual(inoutFunction.block.getArgument(0).getType.segment, i32.inOutTypeGet().segment)
      )
      add(summon[ReturnApi].op(location = loc), inoutFunction.block)
      val initial       = add(summon[InitialApi].op(loc)).block
      val inoutArgument = add(summon[RegApi].op(i32, name = "inout_arg", location = loc)).result
      add(summon[FuncCallProceduralApi].op("touch", inputs = Seq(inoutArgument), location = loc), initial)
      val arg32         = constant(5)
      val arg8          = add(summon[ConstantXApi].op(i8, loc)).result
      val call          = add(summon[FuncCallProceduralApi].op("two_results", Seq(arg32, arg8), Seq(i32, i8), loc), initial)
      assert(call.results.size == 2)
      add(summon[FuncCallProceduralApi].op("no_result", location = loc), initial)
      val wide          = add(summon[ConstantXApi].op(80.integerTypeGet, loc)).result
      val cases         = add(
        summon[CaseApi].op(
          cond = wide,
          casePatterns =
            Seq(summon[SVIRAttributeApi].getCasePattern("01xz" * 20), summon[SVIRAttributeApi].getCaseDefault),
          caseStyle = CaseStyle.CaseZ,
          location = loc
        ),
        initial
      )
      add(summon[WriteApi].op("wide pattern", location = loc), cases.caseBlock(0))
      add(summon[WriteApi].op("default pattern", location = loc), cases.caseBlock(1))
      add(summon[MacroDeclApi].op("OBJECT", location = loc), root)
      add(summon[MacroDeclApi].op("ZERO_ARGS", args = Some(Seq.empty), location = loc), root)
      add(summon[MacroDefApi].op("OBJECT", "1", location = loc), root)
      add(summon[MacroDefApi].op("ZERO_ARGS", "2", location = loc), root)
      val ir            = StringBuilder()
      assert(module.getOperation.verify)
      module.getOperation.print(ir ++= _)
      val roundtrip     = summon[ModuleApi].moduleCreateParse(ir.toString)
      assert(org.llvm.mlir.MlirModule.ptr(roundtrip.segment) != MemorySegment.NULL)
      assert(roundtrip.getOperation.verify)
      val printed       = StringBuilder()
      roundtrip.getOperation.print(printed ++= _)
      assert(printed.toString == ir.toString)
      val text          = checkExport()
      assert(
        text.contains("output"),
        text.contains("function automatic void no_result"),
        text.contains("ZERO_ARGS()"),
        text.contains("80'b")
      )
