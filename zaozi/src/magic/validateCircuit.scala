// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.magic

import scala.util.chaining.*

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*

import org.llvm.circt.scalalib.capi.dialect.firrtl.{
  given_AttributeApi,
  given_DialectApi,
  given_FirrtlDirectionApi,
  FirrtlConvention,
  FirrtlDirection,
  FirrtlLayerConvention
}
import org.llvm.circt.scalalib.dialect.firrtl.operation.{
  given_CircuitApi,
  given_ExtModuleApi,
  given_LayerApi,
  given_ModuleApi,
  given_OptionApi,
  given_OptionCaseApi,
  Circuit,
  CircuitApi,
  ExtModule,
  LayerApi,
  OptionApi,
  OptionCaseApi
}
import org.llvm.mlir.scalalib.capi.ir.{
  given_AttributeApi,
  given_BlockApi,
  given_ContextApi,
  given_IdentifierApi,
  given_LocationApi,
  given_ModuleApi,
  given_NamedAttributeApi,
  given_OperationApi,
  given_RegionApi,
  given_TypeApi,
  given_ValueApi,
  Block,
  Context,
  ContextApi,
  LocationApi,
  Module as MlirModule,
  ModuleApi as MlirModuleApi,
  NamedAttributeApi,
  OperationApi,
  Type,
  WalkEnum,
  WalkResultEnum
}
import org.llvm.mlir.scalalib.capi.pass.{given_PassManagerApi, PassManager}
import org.llvm.mlir.scalalib.capi.support.given

import java.lang.foreign.Arena

// Find all instantiated instance and insert to circuit
def validateCircuit(
)(
  using Arena,
  Context,
  Circuit
): Unit =
  val declaredModules = scala.collection.mutable.Set.empty[String]
  // Cases of each option used by instance choices, in first-use order.
  val options         = scala.collection.mutable.LinkedHashMap.empty[String, Seq[String]]
  summon[Circuit].block.getFirstOperation
    .walk(
      op =>
        op.getName.str match
          // Find all instance and create an extmodule for it, which is a placeholder for linking at circt time.
          case i if i == "firrtl.instance" || i == "firrtl.instance_choice" =>
            val moduleNames: Seq[String] =
              if i == "firrtl.instance" then Seq(op.getInherentAttributeByName("moduleName").flatSymbolRefAttrGetValue)
              else
                val targets = op.getInherentAttributeByName("moduleNames")
                val cases   = op.getInherentAttributeByName("caseNames")
                Seq
                  .tabulate(cases.arrayAttrGetNumElements)(cases.arrayAttrGetElement)
                  .foreach: ref =>
                    val option = ref.symbolRefAttrGetRootReference
                    val name   = ref.symbolRefAttrGetNestedReference(0).flatSymbolRefAttrGetValue
                    options.updateWith(option)(known => Some(known.getOrElse(Seq.empty).appended(name).distinct))
                Seq.tabulate(targets.arrayAttrGetNumElements)(targets.arrayAttrGetElement(_).flatSymbolRefAttrGetValue)
            val verilogName = op.getAttributeByName("zaozi.verilog_defname")
            val isVerilog = org.llvm.mlir.MlirAttribute.ptr(verilogName.segment).address() != 0
            for moduleName <- moduleNames if declaredModules.add(moduleName) do
              val portTypes: Seq[Type] = Seq.tabulate(op.getNumResults.toInt)(i => op.getResult(i).getType)
              val extmoduleOp = ExtModule(
                summon[OperationApi].operationCreate(
                  name = "firrtl.extmodule",
                  location = op.getLocation,
                  namedAttributes =
                    val namedAttributeApi = summon[NamedAttributeApi]
                    Seq(
                      // ::mlir::StringAttr
                      namedAttributeApi.namedAttributeGet(
                        "sym_name".identifierGet,
                        moduleName.stringAttrGet
                      ),
                      // ::mlir::StringAttr
                      namedAttributeApi.namedAttributeGet(
                        "defname".identifierGet,
                        if isVerilog then verilogName else moduleName.stringAttrGet
                      ),
                      // ::mlir::StringAttr
                      namedAttributeApi.namedAttributeGet(
                        "parameters".identifierGet,
                        if isVerilog then op.getAttributeByName("zaozi.verilog_parameters") else Seq.empty.arrayAttrGet
                      ),
                      // ::circt::firrtl::ConventionAttr
                      namedAttributeApi.namedAttributeGet(
                        "convention".identifierGet,
                        FirrtlConvention.Internal.toAttribute
                      ),
                      // ::mlir::DenseBoolArrayAttr
                      namedAttributeApi.namedAttributeGet(
                        "portDirections".identifierGet,
                        op.getInherentAttributeByName("portDirections")
                      ),
                      // ::mlir::ArrayAttr
                      namedAttributeApi.namedAttributeGet(
                        "portLocations".identifierGet,
                        Seq
                          .fill(op.getNumResults.toInt)(summon[LocationApi].locationUnknownGet.getAttribute)
                          .arrayAttrGet
                      ),
                      // ::mlir::ArrayAttr
                      namedAttributeApi.namedAttributeGet(
                        "portAnnotations".identifierGet,
                        Seq.empty.arrayAttrGet
                      ),
                      // ::mlir::ArrayAttr
                      namedAttributeApi.namedAttributeGet(
                        "portSymbols".identifierGet,
                        Seq.empty.arrayAttrGet
                      ),
                      // ::mlir::ArrayAttr
                      namedAttributeApi.namedAttributeGet(
                        "portNames".identifierGet,
                        op.getInherentAttributeByName("portNames")
                      ),
                      // ::mlir::ArrayAttr
                      namedAttributeApi.namedAttributeGet(
                        "portTypes".identifierGet,
                        portTypes.map(_.typeAttrGet).arrayAttrGet
                      ),
                      // ::mlir::ArrayAttr
                      namedAttributeApi.namedAttributeGet(
                        "annotations".identifierGet,
                        Seq.empty.arrayAttrGet
                      ),
                      // ::mlir::ArrayAttr
                      namedAttributeApi.namedAttributeGet(
                        "knownLayers".identifierGet,
                        op.getInherentAttributeByName("layers")
                      ),
                      // ::mlir::ArrayAttr
                      namedAttributeApi.namedAttributeGet(
                        "layers".identifierGet,
                        op.getInherentAttributeByName("layers")
                      )
                      // Zaozi doesn't support XMR and all the probe IO should be connected internally within the module
                      // thus we don't need internalPaths
                    )
                  ,
                  regionBlockTypeLocations = Seq(Seq())
                )
              )
              summon[Circuit].block.appendOwnedOperation(extmoduleOp.operation)
            if isVerilog then
              org.llvm.mlir.CAPI
                .mlirOperationRemoveAttributeByName(op.segment, "zaozi.verilog_defname".toStringRef.segment)
              org.llvm.mlir.CAPI
                .mlirOperationRemoveAttributeByName(op.segment, "zaozi.verilog_parameters".toStringRef.segment)
          // get layers from module, append it to circuit to create symbol table.
          case i if i == "firrtl.module"                                    =>
            val layersAttrs = op.getInherentAttributeByName("layers")
            val layers      = Seq.tabulate(layersAttrs.arrayAttrGetNumElements): idx =>
              val attr = layersAttrs.arrayAttrGetElement(idx)
              val root = attr.symbolRefAttrGetRootReference
              Seq(root) ++ Seq.tabulate(attr.symbolRefAttrGetNumNestedReferences.toInt)(idx =>
                attr.symbolRefAttrGetNestedReference(idx).flatSymbolRefAttrGetValue
              )

            class TreeNode(val value: String, val children: Seq[TreeNode])
            def insert(path: Seq[String], node: TreeNode): TreeNode                        =
              path match
                case Nil          =>
                  node
                case head :: tail =>
                  val maybeChildIndex = node.children.indexWhere(_.value == head)
                  if (maybeChildIndex == -1)
                    val newChild = insert(tail, TreeNode(head, Nil))
                    TreeNode(node.value, node.children :+ newChild)
                  else
                    // Child exists => recursively insert into that child
                    val oldChild     = node.children(maybeChildIndex)
                    val updatedChild = insert(tail, oldChild)
                    // Replace the old child with the updated child
                    TreeNode(node.value, node.children.updated(maybeChildIndex, updatedChild))
            val emptyRoot = TreeNode("ROOT", Nil)
            def dfs(root: TreeNode):                       Seq[TreeNode]                   =
              Seq(root) ++ root.children.flatMap(dfs)
            def buildParentMap(root: TreeNode):            Map[TreeNode, Option[TreeNode]] =
              def helper(
                node:   TreeNode,
                parent: Option[TreeNode],
                acc:    Map[TreeNode, Option[TreeNode]]
              ): Map[TreeNode, Option[TreeNode]] =
                val updatedMap = acc + (node -> parent)
                node.children.foldLeft(updatedMap): (mapSoFar, child) =>
                  helper(child, Some(node), mapSoFar)
              helper(root, None, Map.empty)
            val tree = layers.foldLeft(emptyRoot): (acc, path) =>
              insert(path, acc)
            val parentMap = buildParentMap(tree)
            val layerMap  =
              dfs(tree)
                .map: layer =>
                  layer.value -> summon[LayerApi].op(
                    layer.value,
                    summon[LocationApi].locationUnknownGet,
                    FirrtlLayerConvention.Bind
                  )
                .toMap
            parentMap.foreach:
              case (node, Some(parent)) =>
                if (parentMap(parent).isEmpty)
                  summon[Circuit].block.appendOwnedOperation(layerMap(node.value).operation)
                else
                  layerMap(parent.value).block.appendOwnedOperation(layerMap(node.value).operation)
              case (node, None)         =>
          case _                                                            => // Do Nothing
        WalkResultEnum.Advance
      ,
      WalkEnum.PreOrder
    )
  options.foreach: (name, cases) =>
    val option = summon[OptionApi].op(name, summon[LocationApi].locationUnknownGet)
    cases.foreach(name =>
      option.block.appendOwnedOperation(
        summon[OptionCaseApi].op(name, summon[LocationApi].locationUnknownGet).operation
      )
    )
    summon[Circuit].block.appendOwnedOperation(option.operation)
