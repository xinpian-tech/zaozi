// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.smtlib

import java.lang.foreign.{Arena, FunctionDescriptor, Linker, MemorySegment, SymbolLookup, ValueLayout}

import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.smtlib.parser.{parseSExpr, parseZ3Output, SExpr, Z3Result, Z3Status}
import org.llvm.mlir.scalalib.capi.dialect.smt.given_TypeApi
import org.llvm.mlir.scalalib.capi.ir.{*, given}
import org.llvm.mlir.scalalib.capi.support.given
import org.llvm.mlir.scalalib.capi.target.exportsmtlib.given

final case class SMTQuery(ir: String, assumptions: Seq[String] = Seq.empty):
  import SMTQuery.*

  def replay: String = withContext:
    val module = parseModule(ir)
    try
      val declarations = namedDeclarations(module)
      assumptionValues(declarations)
      val output       = new StringBuilder
      require(module.exportSMTLIB(output ++= _, false).succeeded, "SMT-LIB export failed")
      val check        =
        if assumptions.isEmpty then "(check-sat)\n"
        else s"(check-sat-assuming (${assumptions.map(name => s"|$name|").mkString(" ")}))\n"
      "(set-option :produce-unsat-cores true)\n" + output.toString + check
    finally module.destroy()

  private def assumptionValues(
    declarations: Seq[(String, Operation)]
  )(
    using Arena
  ): Seq[Value] =
    val values = declarations.toMap
    assumptions.map: name =>
      require(values.contains(name), s"unknown SMT assumption: $name")
      val value = values(name).getResult(0)
      require(value.getType.isBool, s"SMT assumption must be Boolean: $name")
      value

  def check(timeoutMs: Int): Z3Result =
    require(timeoutMs > 0, "solver timeout must be positive")
    val (program, names) = withContext:
      val module = parseModule(ir)
      try
        val declarations = namedDeclarations(module)
        val arguments    = assumptionValues(declarations)
        val names        = declarations.zipWithIndex.map:
          case ((name, declaration), index) =>
            val prefix = s"query_$index"
            declaration.setAttributeByName("namePrefix", prefix.stringAttrGet)
            prefix -> name
        val solver       = children(module.getOperation).head
        val block        = solver.getRegion(0).getFirstBlock
        summon[Context].getOrLoadDialect("func")
        val call         = summon[OperationApi].operationCreate(
          "func.call",
          solver.getLocation,
          namedAttributes = Seq(
            summon[NamedAttributeApi].namedAttributeGet("callee".identifierGet, "query_check".flatSymbolRefAttrGet)
          ),
          operands = arguments,
          resultsTypes = Some(Seq.empty)
        )
        block.insertOwnedOperationBefore(block.getTerminator, call)
        val harness      = parseModule(
          s"module { func.func @entry() { return } func.func private @query_check(${arguments.map(_ => "!smt.bool").mkString(", ")}) }"
        )
        try
          val entry = harness.getBody.getFirstOperation.getRegion(0).getFirstBlock
          solver.removeFromParent()
          entry.insertOwnedOperationBefore(entry.getTerminator, solver)
          require(harness.getOperation.verify, "invalid SMT query harness")
          render(harness.getOperation) -> names.toMap
        finally harness.destroy()
      finally module.destroy()
    val lowered          = os
      .proc("circt-opt", "--canonicalize", "--cse", "--lower-smt-to-z3-llvm", "--canonicalize")
      .call(stdin = program, stderr = os.Pipe, timeout = 30000)
      .out
      .text()
    val executable       = withContext:
      val module = parseModule(lowered)
      try
        val entry    = children(module.getOperation).find(op => symbol(op) == "entry").get
        val entryOps = children(entry)
        def globalFor(callee: String): String =
          val created = entryOps
            .find(op =>
              op.getName.str == "llvm.call" &&
                op.getAttributeByName("callee").flatSymbolRefAttrGetValue == callee
            )
            .get
            .getResult(0)
          val store   = entryOps
            .find(op =>
              op.getName.str == "llvm.store" &&
                org.llvm.mlir.CAPI.mlirValueEqual(op.getOperand(0).segment, created.segment)
            )
            .get
          store.getOperand(1).opResultGetOwner.getAttributeByName("global_name").flatSymbolRefAttrGetValue
        val context = globalFor("Z3_mk_context")
        val solverConstructor = entryOps
          .filter(_.getName.str == "llvm.call")
          .map(_.getAttributeByName("callee").flatSymbolRefAttrGetValue)
          .find(name => name == "Z3_mk_solver" || name == "Z3_mk_solver_for_logic")
          .get
        val solver            = globalFor(solverConstructor)
        val helper            = parseModule(runtime(context, solver, assumptions.size, timeoutMs))
        try
          val declaration = children(module.getOperation).find(op => symbol(op) == "query_check").get
          declaration.removeFromParent()
          declaration.destroy()
          val existing    = children(module.getOperation).map(symbol).toSet
          children(helper.getOperation)
            .filterNot(op => existing(symbol(op)))
            .foreach: op =>
              op.removeFromParent()
              module.getBody.appendOwnedOperation(op)
          require(module.getOperation.verify, "invalid SMT query runtime")
          render(module.getOperation)
        finally helper.destroy()
      finally module.destroy()
    val output           = os
      .proc("mlir-runner", "--O0", "-e", "entry", "-entry-point-result=void", s"--shared-libs=${sys.env("Z3_LIB")}")
      .call(stdin = executable, stderr = os.Pipe, timeout = timeoutMs.toLong + 30000)
      .out
      .text()
    def original(name: String): String =
      val prefix = name.take(name.lastIndexOf('!'))
      require(names.contains(prefix), s"unexpected solver symbol: $name")
      names(prefix)
    val result = parseZ3Output(output)
    result.status match
      case Z3Status.Sat     => result.copy(model = result.model.map((name, value) => original(name) -> value))
      case Z3Status.Unsat   =>
        parseSExpr(output).get.value match
          case Seq(SExpr.Symbol("unsat"), SExpr.List(SExpr.Symbol("ast-vector") +: values)) =>
            result.copy(conflict = values.map:
              case SExpr.Symbol(name) => original(name)
              case other              => throw new IllegalArgumentException(s"unexpected conflict member: $other"))
          case other                                                                        => throw new IllegalArgumentException(s"unexpected conflict result: $other")
      case Z3Status.Unknown => result

object SMTQuery:
  private def withContext[T](body: (Arena, Context) ?=> T): T =
    val arena = Arena.ofConfined()
    try
      given Arena   = arena
      val registry  = summon[DialectRegistryApi].registryCreate()
      org.llvm.mlir.CAPI.mlirRegisterAllDialects(registry.segment)
      val context   = summon[ContextApi].contextCreateWithRegistry(registry, false)
      registry.destroy()
      given Context = context
      try body
      finally context.destroy()
    finally arena.close()

  private def parseModule(
    text: String
  )(
    using Arena,
    Context
  ): Module =
    val module = summon[ModuleApi].moduleCreateParse(text)
    require(org.llvm.mlir.MlirModule.ptr(module.segment).address() != 0, "invalid SMT query IR")
    module

  private def render(
    op: Operation
  )(
    using Arena
  ): String =
    val text = new StringBuilder
    op.print(text ++= _)
    text.toString

  private def children(
    op: Operation
  )(
    using Arena
  ): Vector[Operation] =
    (0L until org.llvm.mlir.CAPI.mlirOperationGetNumRegions(op.segment)).toVector.flatMap: index =>
      Iterator
        .iterate(op.getRegion(index).getFirstBlock)(_.getNextInRegion)
        .takeWhile(b => org.llvm.mlir.MlirBlock.ptr(b.segment).address() != 0)
        .flatMap: block =>
          Iterator
            .iterate(block.getFirstOperation)(_.getNextInBlock)
            .takeWhile(o => org.llvm.mlir.MlirOperation.ptr(o.segment).address() != 0)

  private def symbol(
    op: Operation
  )(
    using Arena
  ): String = op.getAttributeByName("sym_name").stringAttrGetValue

  private def namedDeclarations(
    module: Module
  )(
    using Arena,
    Context
  ): Seq[(String, Operation)] =
    val ops          = children(module.getOperation)
    require(ops.size == 1 && ops.head.getName.str == "smt.solver", "expected one SMT solver")
    val declarations = children(ops.head).filter(_.getName.str == "smt.declare_fun")
    declarations.foldLeft(Vector.empty[(String, Operation)]): (named, op) =>
      val attr   = op.getAttributeByName("namePrefix")
      val prefix = if org.llvm.mlir.MlirAttribute.ptr(attr.segment).address() == 0 then "v" else attr.stringAttrGetValue
      val used   = named.map(_._1).toSet
      val name   = Iterator
        .from(0)
        .map(i => if i == 0 && prefix.nonEmpty then prefix else s"${prefix}_$i")
        .find(name => !used(name))
        .get
      require(!name.exists(c => c == '|' || c == '\\'), "query names must be SMT-LIB symbols")
      op.setAttributeByName("namePrefix", name.stringAttrGet)
      named :+ (name -> op)

  def fromModule(
    module: Module
  )(
    using Arena
  ): SMTQuery =
    require(module.getOperation.verify, "invalid SMT query")
    SMTQuery(render(module.getOperation))

  def build(body: (Arena, Context, Block) ?=> Unit): SMTQuery = withContext:
    val module = summon[ModuleApi].moduleCreateEmpty(summon[LocationApi].locationUnknownGet)
    try
      given Block = module.getBody
      summon[Context].getOrLoadDialect("smt")
      solver { body; smtYield() }
      fromModule(module)
    finally module.destroy()

  lazy val solverVersion: String =
    val arena = Arena.ofConfined()
    try
      val symbol = SymbolLookup.libraryLookup(sys.env("Z3_LIB"), arena).find("Z3_get_full_version").orElseThrow()
      Linker
        .nativeLinker()
        .downcallHandle(symbol, FunctionDescriptor.of(ValueLayout.ADDRESS))
        .invokeWithArguments()
        .asInstanceOf[MemorySegment]
        .reinterpret(Long.MaxValue)
        .getString(0)
    finally arena.close()

  private def runtime(context: String, solver: String, count: Int, timeoutMs: Int): String =
    val resource = getClass.getResourceAsStream("/SMTQuery.mlir")
    val template =
      try new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
      finally resource.close()
    val storage  =
      if count == 0 then "%assumptions = llvm.mlir.zero : !llvm.ptr"
      else
        s"%size = llvm.mlir.constant($count : i32) : i32\n%assumptions = llvm.alloca %size x !llvm.ptr : (i32) -> !llvm.ptr\n" +
          (0 until count)
            .map(i =>
              s"%slot$i = llvm.getelementptr %assumptions[$i] : (!llvm.ptr) -> !llvm.ptr, !llvm.ptr\nllvm.store %arg$i, %slot$i : !llvm.ptr, !llvm.ptr"
            )
            .mkString("\n")
    template
      .replace("QUERY_CONTEXT", context)
      .replace("QUERY_SOLVER", solver)
      .replace("QUERY_ARGUMENTS", (0 until count).map(i => s"%arg$i: !llvm.ptr").mkString(", "))
      .replace("QUERY_TIMEOUT", timeoutMs.toString)
      .replace("QUERY_COUNT", count.toString)
      .replace("QUERY_ASSUMPTIONS", storage)
