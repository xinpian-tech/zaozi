// SPDX-License-Identifier: Apache-2.0
package me.jiuyang.tblib.default

import java.lang.foreign.Arena
import me.jiuyang.zaozi.default.{runOnOpOrThrow, given}
import org.llvm.circt.scalalib.capi.dialect.firrtl.{*, given}
import org.llvm.circt.scalalib.dialect.firrtl.operation.{ModuleApi as FModuleApi, Option as _, *, given}
import org.llvm.mlir.scalalib.capi.ir.{Module as MlirModule, ModuleApi as MlirModuleApi, *, given}
import org.llvm.mlir.scalalib.capi.pass.{PassManagerApi, given}
import org.llvm.mlir.MlirOperation

/** Imports DUT circuits and constructs the connected FIRRTL wrapper. Returned modules belong to the caller. */
private[tblib] object FirrtlTestbench:
  private def operations(
    block: Block
  )(
    using Arena
  ): Seq[Operation] =
    Iterator
      .iterate(block.getFirstOperation)(_.getNextInBlock)
      .takeWhile(op => MlirOperation.ptr(op.segment).address != 0)
      .toVector

  /** Each input is one complete FIRRTL circuit, whose name identifies its main module. */
  private def validateCircuit(
    module: MlirModule
  )(
    using Arena
  ): Unit =
    val circuits = operations(module.getBody)
    require(
      circuits.size == 1 && circuits.head.getName.str == "firrtl.circuit",
      "IR must contain exactly one linked FIRRTL circuit"
    )
    require(module.getOperation.verify, "invalid FIRRTL IR")
    val name     = circuitName(module)
    val members  = operations(circuits.head.getFirstRegion.getFirstBlock)
    require(!members.exists(_.getName.str == "firrtl.extmodule"), s"circuit $name has unresolved external modules")
    namedModule(circuits.head, name)

  private def namedModule(
    circuit: Operation,
    name:    String
  )(
    using Arena
  ): Operation =
    operations(circuit.getFirstRegion.getFirstBlock)
      .find(op =>
        op.getName.str == "firrtl.module" && op.getInherentAttributeByName("sym_name").stringAttrGetValue == name
      )
      .getOrElse(throw new IllegalArgumentException(s"FIRRTL main module not found: $name"))

  private def fields(
    op: Operation
  )(
    using Arena,
    Context
  ): Seq[FirrtlBundleField] =
    val names      = op.getInherentAttributeByName("portNames")
    val types      = op.getInherentAttributeByName("portTypes")
    val directions = op.getInherentAttributeByName("portDirections")
    (0 until names.arrayAttrGetNumElements).map: i =>
      summon[FirrtlBundleFieldApi].createFirrtlBundleField(
        names.arrayAttrGetElement(i).stringAttrGetValue,
        !directions.denseBoolArrayGetElement(i),
        types.arrayAttrGetElement(i).typeAttrGetValue
      )

  private def layers(
    op: Operation
  )(
    using Arena
  ): Seq[Seq[String]] =
    val attr = op.getInherentAttributeByName("layers")
    (0 until attr.arrayAttrGetNumElements).map: i =>
      val ref = attr.arrayAttrGetElement(i)
      Seq(ref.symbolRefAttrGetRootReference) ++
        (0 until ref.symbolRefAttrGetNumNestedReferences.toInt).map(j =>
          ref.symbolRefAttrGetNestedReference(j).flatSymbolRefAttrGetValue
        )

  /** Borrows inputs and clones their circuits into the returned wrapper. */
  def build(
    dut:   MlirModule,
    clock: MlirModule
  )(
    using Arena,
    Context
  ): MlirModule =
    validateCircuit(dut)
    validateCircuit(clock)
    val dutName   = circuitName(dut)
    val clockName = circuitName(clock)
    require(dutName != clockName, s"DUT and clock circuit names collide: $dutName")
    val top       = dutName + "TestBench"
    val module    = summon[MlirModuleApi].moduleCreateEmpty(summon[LocationApi].locationUnknownGet)
    try
      Seq(dut, clock).foreach: input =>
        val circuit = summon[OperationApi].operationClone(input.getBody.getFirstOperation)
        module.getBody.appendOwnedOperation(circuit)
      val pm = summon[PassManagerApi].passManagerCreate
      try
        pm.addOwnedPass(summon[PassApi].linkCircuitsPass(dutName, noMangle = false))
        pm.runOnOpOrThrow(module.getOperation, "link DUT and clock circuits")
      finally pm.destroy()
      addWrapper(module, dutName, top, clockName)
      require(module.getOperation.verify, "invalid imported DUT wrapper")
      module
    catch
      case scala.util.control.NonFatal(error) =>
        module.destroy()
        throw error

  /** Adds DUT/clock instances to a borrowed linked circuit. */
  private def addWrapper(
    module:    MlirModule,
    selected:  String,
    top:       String,
    clockName: String
  )(
    using Arena,
    Context
  ): Unit =
    val circuit     = module.getBody.getFirstOperation
    val body        = circuit.getFirstRegion.getFirstBlock
    val members     = operations(body)
    val dut         = namedModule(circuit, selected)
    val dutFields   = fields(dut)
    val clockFields = fields(namedModule(circuit, clockName))
    val exposed     = TestbenchConnections.exposed(dutFields).map(dutFields(_))
    require(
      !members.exists(op =>
        Set("firrtl.module", "firrtl.extmodule")
          .contains(op.getName.str) && op.getInherentAttributeByName("sym_name").stringAttrGetValue == top
      ),
      s"testbench symbol already exists: $top"
    )
    val loc         = summon[LocationApi].locationUnknownGet
    val wrapper     =
      summon[FModuleApi].op(top, loc, FirrtlConvention.Scalarized, exposed.map(f => (f, loc)), layers(dut))
    body.appendOwnedOperation(wrapper.operation)
    locally:
      given Block       = wrapper.block
      val clockInstance = summon[InstanceApi].op(
        clockName,
        "clock",
        FirrtlNameKind.Interesting,
        loc,
        clockFields,
        Seq.empty
      )
      clockInstance.operation.appendToBlock()
      val instance      = summon[InstanceApi].op(selected, "dut", FirrtlNameKind.Interesting, loc, dutFields, layers(dut))
      instance.operation.appendToBlock()
      TestbenchConnections.connect(
        dutFields.zipWithIndex.map((field, i) => (field, instance.operation.getResult(i))),
        clockFields.zipWithIndex.map((field, i) => (field, clockInstance.operation.getResult(i))),
        exposed.zipWithIndex.map((field, i) => field.getName -> wrapper.getIO(i)).toMap
      )
    circuit.setInherentAttributeByName("name", top.stringAttrGet)

  def circuitName(
    module: MlirModule
  )(
    using Arena
  ): String =
    module.getBody.getFirstOperation.getInherentAttributeByName("name").stringAttrGetValue
