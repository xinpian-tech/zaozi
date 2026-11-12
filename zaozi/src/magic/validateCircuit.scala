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
  FirrtlDirection,
  FirrtlLayerConvention
}
import org.llvm.circt.scalalib.dialect.firrtl.operation.{
  given_CircuitApi,
  given_LayerApi,
  given_ModuleApi,
  Circuit,
  CircuitApi,
  LayerApi
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
  WalkEnum,
  WalkResultEnum
}
import org.llvm.mlir.scalalib.capi.pass.{given_PassManagerApi, PassManager}

import java.lang.foreign.Arena

// Declare the layers each module uses, and every module the circuit instantiates.
def validateCircuit(
)(
  using Arena,
  Context,
  Circuit
): Unit =
  summon[Circuit].block.getFirstOperation
    .walk(
      op =>
        op.getName.str match
          // get layers from module, append it to circuit to create symbol table.
          case i if i == "firrtl.module" =>
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
          case _                         => // Do Nothing
        WalkResultEnum.Advance
      ,
      WalkEnum.PreOrder
    )
  summon[Circuit].declareInstantiatedModules()
