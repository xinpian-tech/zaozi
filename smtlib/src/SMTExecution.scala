// SPDX-License-Identifier: Apache-2.0
package me.jiuyang.smtlib

import java.lang.foreign.{Arena, ValueLayout}
import me.jiuyang.smtlib.parser.Z3Status
import org.llvm.circt.scalalib.capi.conversion.{ConversionCreateApi, given}
import org.llvm.mlir.CAPI
import org.llvm.mlir.scalalib.capi.ir.{*, given}
import org.llvm.mlir.scalalib.capi.dialect.func.{FuncApi, given}
import org.llvm.mlir.scalalib.capi.pass.{*, given}
import org.llvm.mlir.scalalib.capi.executionengine.{*, given}
import org.llvm.mlir.scalalib.capi.support.given
import org.llvm.mlir.scalalib.capi.target.exportsmtlib.given
import org.llvm.mlir.scalalib.dialect.smt.operation.{CheckApi, SolverApi, YieldApi, given}

private[smtlib] def executeSolver(
  body:        (Arena, Context, Block, SolverContext) ?=> Unit,
  config:      SolverContext,
  location:    Location
)(
  using arena: Arena,
  context:     Context
): Seq[Z3Status] =
  val registry = summon[DialectRegistryApi].registryCreate()
  try
    CAPI.mlirRegisterAllDialects(registry.segment)
    context.appendDialectRegistry(registry)
  finally registry.destroy()
  Seq("smt", "func", "arith", "llvm").foreach(context.getOrLoadDialect)
  val module   = summon[ModuleApi].moduleCreateEmpty(location)
  try
    val solverContext = new SolverContext(config.debug)
    val source        = summon[SolverApi].op(location)
    module.getBody.appendOwnedOperation(source.operation)
    body(
      using arena,
      context,
      source.bodyBlock,
      solverContext
    )
    source.bodyBlock.appendOwnedOperation(summon[YieldApi].op(Seq.empty, location).operation)
    require(module.getOperation.verify, "invalid SMT solver")
    solverContext.debug.foreach: output =>
      val text = new StringBuilder
      require(module.exportSMTLIB(text ++= _, false).succeeded, "SMT-LIB export failed")
      output(text.toString)

    val operations = Iterator
      .iterate(source.bodyBlock.getFirstOperation)(_.getNextInBlock)
      .takeWhile(op => org.llvm.mlir.MlirOperation.ptr(op.segment).address() != 0)
      .toVector
    val checks     = operations.filter(_.getName.str == "smt.check")
    if checks.isEmpty then Vector.empty
    else
      val i32        = 32.integerTypeGet
      val ptr        = Type(CAPI.mlirLLVMPointerTypeGet(arena, context.segment, 0))
      val function   = summon[FuncApi].op("solver", Seq(ptr), Seq.empty, true)
      module.getBody.appendOwnedOperation(function.operation)
      // Pass the output buffer through the solver's native block argument.
      val executable = summon[SolverApi].op(location, Seq(function.block.getArgument(0)))
      function.block.appendOwnedOperation(executable.operation)
      val block      = executable.bodyBlock
      operations.foreach: op =>
        op.removeFromParent()
        block.appendOwnedOperation(op)
      source.operation.removeFromParent()
      source.operation.destroy()

      def named(name: String, value: Attribute): NamedAttribute =
        summon[NamedAttributeApi].namedAttributeGet(name.identifierGet, value)
      checks.zipWithIndex.foreach: (oldCheck, index) =>
        val check = summon[CheckApi].op(oldCheck.getLocation, Seq(i32))
        block.insertOwnedOperationBefore(oldCheck, check.operation)
        Seq(check.satBlock -> 1, check.unknownBlock -> 0, check.unsatBlock -> -1).foreach: (branch, status) =>
          val constant = summon[OperationApi].operationCreate(
            "arith.constant",
            oldCheck.getLocation,
            namedAttributes = Seq(named("value", status.toLong.integerAttrGet(i32))),
            resultsTypes = Some(Seq(i32))
          )
          branch.appendOwnedOperation(constant)
          branch.appendOwnedOperation(summon[YieldApi].op(Seq(constant.getResult(0)), oldCheck.getLocation).operation)
        val address = summon[OperationApi].operationCreate(
          "llvm.getelementptr",
          oldCheck.getLocation,
          operands = Seq(block.getArgument(0)),
          namedAttributes =
            Seq(named("elem_type", i32.typeAttrGet), named("rawConstantIndices", Seq(index).denseI32ArrayGet)),
          resultsTypes = Some(Seq(ptr))
        )
        block.insertOwnedOperationBefore(oldCheck, address)
        block.insertOwnedOperationBefore(
          oldCheck,
          summon[OperationApi].operationCreate(
            "llvm.store",
            oldCheck.getLocation,
            operands = Seq(check.operation.getResult(0), address.getResult(0)),
            resultsTypes = Some(Seq.empty)
          )
        )
        oldCheck.removeFromParent()
        oldCheck.destroy()
      function.block.appendOwnedOperation(
        summon[OperationApi].operationCreate(
          "func.return",
          location,
          resultsTypes = Some(Seq.empty)
        )
      )
      require(module.getOperation.verify, "invalid executable SMT solver")
      val passes = summon[PassManagerApi].passManagerCreate
      try
        passes.addOwnedPass(summon[ConversionCreateApi].lowerSMTToZ3LLVM)
        passes.addOwnedPass(Pass(CAPI.mlirCreateConversionReconcileUnrealizedCastsPass(arena)))
        require(passes.runOnOp(module.getOperation).succeeded, "SMT to Z3 LLVM lowering failed")
      finally passes.destroy()
      val engine =
        summon[ExecutionEngineApi].executionEngineCreate(module, sharedLibPaths = solverContext.sharedLibPaths)
      require(!engine.isNull, "could not create SMT execution engine")
      try
        engine.initialize()
        val results  = arena.allocate(ValueLayout.JAVA_INT, checks.size)
        val argument = arena.allocate(ValueLayout.ADDRESS)
        argument.set(ValueLayout.ADDRESS, 0, results)
        require(engine.invokePacked("solver", Seq(argument)).succeeded, "SMT invocation failed")
        checks.indices.map: index =>
          results.getAtIndex(ValueLayout.JAVA_INT, index) match
            case 1     => Z3Status.Sat
            case -1    => Z3Status.Unsat
            case 0     => Z3Status.Unknown
            case value => throw new IllegalStateException(s"invalid SMT status: $value")
      finally engine.destroy()
  finally module.destroy()
