// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.ir

import org.llvm.mlir.raw.structs as MlirStructs
import org.llvm.mlir.raw.enumerations as MlirEnums
import org.llvm.mlir.raw.enumerations.*
import org.llvm.mlir.raw.functions as mlirApi
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

import org.llvm.mlir.scalalib.capi.support.{*, given}

given OperationApi with
  extension (operation: Operation) inline def isNull: Boolean = (!operation.native).ptr == null

  inline def operationCreate(
    state:      OperationState
  )(
    using zone: Zone
  ): Operation = Operation(
    nativeResult[MlirStructs.MlirOperation](out => mlirApi.mlirOperationCreate(state.native)(out))
  )
  inline def operationCreate(
    name:                     String,
    location:                 Location,
    regionBlockTypeLocations: Seq[Seq[(Seq[Type], Seq[Location])]] = Seq.empty,
    namedAttributes:          Seq[NamedAttribute] = Seq.empty,
    operands:                 Seq[Value] = Seq.empty,
    resultsTypes:             Option[Seq[Type]] = None,
    inferredResultsTypes:     Option[Int] = None
  )(
    using zone:               Zone,
    context:                  Context
  ): Operation =
    val operationState = summon[OperationStateApi].operationStateGet(name, location)
    operationState.addOwnedRegions(
      regionBlockTypeLocations.map(blocks =>
        val region = summon[RegionApi].regionCreate
        blocks.foreach(block =>
          val (tpe: Seq[Type], loc: Seq[Location]) = block
          region.appendOwnedBlock(summon[BlockApi].blockCreate(tpe, loc))
        )
        region
      )
    )
    operationState.addAttributes(namedAttributes)
    operationState.addOperands(operands)
    inferredResultsTypes.foreach(_ => operationState.enableResultTypeInference())
    resultsTypes.foreach(resultsTypes => operationState.addResults(resultsTypes))
    summon[OperationApi].operationCreate(operationState)
  inline def operationCreateParse(
    sourceStr:  String,
    sourceName: String
  )(
    using zone: Zone,
    context:    Context
  ): Operation = Operation(
    nativeResult[MlirStructs.MlirOperation](out =>
      mlirApi.mlirOperationCreateParse(context.native, sourceStr.toStringRef.native, sourceName.toStringRef.native)(out)
    )
  )
  inline def operationClone(
    op:         Operation
  )(
    using zone: Zone
  ): Operation = Operation(nativeResult[MlirStructs.MlirOperation](out => mlirApi.mlirOperationClone(op.native)(out)))
  extension (operation: Operation)
    inline def getContext(
      using zone: Zone
    ): Context = Context(
      nativeResult[MlirStructs.MlirContext](out => mlirApi.mlirOperationGetContext(operation.native)(out))
    )
    inline def getLocation(
      using zone: Zone
    ): Location = Location(
      nativeResult[MlirStructs.MlirLocation](out => mlirApi.mlirOperationGetLocation(operation.native)(out))
    )
    inline def getTypeID(
      using zone: Zone
    ): TypeID = TypeID(
      nativeResult[MlirStructs.MlirTypeID](out => mlirApi.mlirOperationGetTypeID(operation.native)(out))
    )
    inline def getName(
      using zone: Zone
    ): Identifier = Identifier(
      nativeResult[MlirStructs.MlirIdentifier](out => mlirApi.mlirOperationGetName(operation.native)(out))
    )
    inline def getBlock(
      using zone: Zone
    ): Block = Block(nativeResult[MlirStructs.MlirBlock](out => mlirApi.mlirOperationGetBlock(operation.native)(out)))
    inline def getParentOperation(
      using zone: Zone
    ): Operation = Operation(
      nativeResult[MlirStructs.MlirOperation](out => mlirApi.mlirOperationGetParentOperation(operation.native)(out))
    )
    inline def getRegion(
      pos:        Long
    )(
      using zone: Zone
    ): Region = Region(
      nativeResult[MlirStructs.MlirRegion](out => mlirApi.mlirOperationGetRegion(operation.native, pos.toSize)(out))
    )
    inline def getNextInBlock(
      using zone: Zone
    ): Operation = Operation(
      nativeResult[MlirStructs.MlirOperation](out => mlirApi.mlirOperationGetNextInBlock(operation.native)(out))
    )
    inline def getOperand(
      pos:        Long
    )(
      using zone: Zone
    ): Value = Value(
      nativeResult[MlirStructs.MlirValue](out => mlirApi.mlirOperationGetOperand(operation.native, pos.toSize)(out))
    )
    inline def getResult(
      pos:        Long
    )(
      using zone: Zone
    ): Value = Value(
      nativeResult[MlirStructs.MlirValue](out => mlirApi.mlirOperationGetResult(operation.native, pos.toSize)(out))
    )
    inline def getNumResults: Long    =
      mlirApi.mlirOperationGetNumResults(operation.native).toLong
    inline def verify:        Boolean =
      mlirApi.mlirOperationVerify(operation.native)
    inline def getSuccessor(
      pos:        Long
    )(
      using zone: Zone
    ): Block = Block(
      nativeResult[MlirStructs.MlirBlock](out => mlirApi.mlirOperationGetSuccessor(operation.native, pos.toSize)(out))
    )
    inline def getInherentAttributeByName(
      name:       String
    )(
      using zone: Zone
    ): Attribute = Attribute(
      nativeResult[MlirStructs.MlirAttribute](out =>
        mlirApi.mlirOperationGetInherentAttributeByName(operation.native, name.toStringRef.native)(out)
      )
    )
    inline def getDiscardableAttribute(
      pos:        Long
    )(
      using zone: Zone
    ): NamedAttribute = NamedAttribute(
      nativeResult[MlirStructs.MlirNamedAttribute](out =>
        mlirApi.mlirOperationGetDiscardableAttribute(operation.native, pos.toSize)(out)
      )
    )
    inline def getDiscardableAttributeByName(
      name:       String
    )(
      using zone: Zone
    ): Attribute = Attribute(
      nativeResult[MlirStructs.MlirAttribute](out =>
        mlirApi.mlirOperationGetDiscardableAttributeByName(operation.native, name.toStringRef.native)(out)
      )
    )
    inline def getAttribute(
      pos:        Long
    )(
      using zone: Zone
    ): NamedAttribute = NamedAttribute(
      nativeResult[MlirStructs.MlirNamedAttribute](out =>
        mlirApi.mlirOperationGetAttribute(operation.native, pos.toSize)(out)
      )
    )
    inline def getAttributeByName(
      name:       String
    )(
      using zone: Zone
    ): Attribute = Attribute(
      nativeResult[MlirStructs.MlirAttribute](out =>
        mlirApi.mlirOperationGetAttributeByName(operation.native, name.toStringRef.native)(out)
      )
    )
    inline def writeBytecodeWithConfig(
      config:     BytecodeWriterConfig,
      callback:   Array[Byte] => Unit
    )(
      using zone: Zone
    ): LogicalResult = LogicalResult(
      callback.bytesToStringCallback.use { (fn, data) =>
        nativeResult[MlirStructs.MlirLogicalResult](out =>
          mlirApi.mlirOperationWriteBytecodeWithConfig(operation.native, config.native, fn, data)(out)
        )
      }
    )
    inline def getFirstRegion(
      using zone: Zone
    ): Region = Region(
      nativeResult[MlirStructs.MlirRegion](out => mlirApi.mlirOperationGetFirstRegion(operation.native)(out))
    )

    // Scala Only API
    inline def destroy():                              Unit = mlirApi.mlirOperationDestroy(operation.native)
    inline def removeFromParent():                     Unit = mlirApi.mlirOperationRemoveFromParent(operation.native)
    inline def setOperand(pos: Long, newValue: Value): Unit =
      mlirApi.mlirOperationSetOperand(operation.native, pos.toSize, newValue.native)
    inline def setOperands(
      operands: Seq[Value]
    )(
      using Zone
    ): Unit = mlirApi.mlirOperationSetOperands(operation.native, operands.size.toSize, operands.toMlirArray)
    inline def setSuccessor(pos: Long, block: Block):  Unit =
      mlirApi.mlirOperationSetSuccessor(operation.native, pos.toSize, block.native)
    inline def setInherentAttributeByName(
      name: String,
      attr: Attribute
    )(
      using Zone
    ): Unit = mlirApi.mlirOperationSetInherentAttributeByName(operation.native, name.toStringRef.native, attr.native)
    inline def setDiscardableAttributeByName(
      name: String,
      attr: Attribute
    )(
      using Zone
    ): Unit = mlirApi.mlirOperationSetDiscardableAttributeByName(operation.native, name.toStringRef.native, attr.native)
    inline def setAttributeByName(
      name: String,
      attr: Attribute
    )(
      using Zone
    ): Unit = mlirApi.mlirOperationSetAttributeByName(operation.native, name.toStringRef.native, attr.native)
    inline def print(
      callback: String => Unit
    )(
      using Zone
    ): Unit = callback.stringToStringCallback.use { (fn, data) =>
      mlirApi.mlirOperationPrint(operation.native, fn, data)
    }
    inline def printWithFlags(
      callback: String => Unit
    )(
      using Zone
    ): Unit = callback.stringToStringCallback.use { (fn, data) =>
      mlirApi.mlirOperationPrint(operation.native, fn, data)
    }
    inline def printWithState(
      asmState: AsmState,
      callback: String => Unit
    )(
      using Zone
    ): Unit = callback.stringToStringCallback.use { (fn, data) =>
      mlirApi.mlirOperationPrintWithState(operation.native, asmState.native, fn, data)
    }
    inline def writeBytecode(
      callBack:   Array[Byte] => Unit
    )(
      using zone: Zone
    ): Unit = callBack.bytesToStringCallback.use { (fn, data) =>
      mlirApi.mlirOperationWriteBytecode(operation.native, fn, data)
    }

    inline def dump():                       Unit = mlirApi.mlirOperationDump(operation.native)
    inline def moveAfter(other:  Operation): Unit = mlirApi.mlirOperationMoveAfter(operation.native, other.native)
    inline def moveBefore(other: Operation): Unit = mlirApi.mlirOperationMoveBefore(operation.native, other.native)
    inline def walk(
      callback:   Operation => WalkResultEnum,
      walk:       WalkEnum
    )(
      using zone: Zone
    ): Unit =
      // MLIR passes the handle by value. Keep its storage alive when a visitor
      // collects operations and uses them after the C callback returns.
      val visit = (handle: Ptr[MlirStructs.MlirOperation]) => callback(Operation(nativeCopy(!handle)))
      NativeCallbacks.scoped(visit) { data =>
        mlirApi.mlirOperationWalk(operation.native, CallbackFunctions.walk(), data, MlirWalkOrder.define(walk.toNative))
      }
    // Scala Only API
    inline def appendToBlock(
    )(
      using block: Block
    ): Unit = block.appendOwnedOperation(operation)
    inline def insertToBlock(
      pos:         Long
    )(
      using block: Block
    ): Unit = block.insertOwnedOperation(pos, operation)
    inline def insertAfter(
      ref:         Operation
    )(
      using block: Block
    ): Unit = block.insertOwnedOperationAfter(ref, operation)
    inline def insertBefore(
      ref:         Operation
    )(
      using block: Block
    ): Unit = block.insertOwnedOperationBefore(ref, operation)

    inline def sizeOf: Int = sizeof[MlirStructs.MlirOperation].toInt
end given

given WalkResultEnumApi with
  extension (int: Int)
    inline def fromNative: WalkResultEnum = int match
      case i if i == MlirEnums.MlirWalkResult.MlirWalkResultAdvance.int   => WalkResultEnum.Advance
      case i if i == MlirEnums.MlirWalkResult.MlirWalkResultInterrupt.int => WalkResultEnum.Interrupt
      case i if i == MlirEnums.MlirWalkResult.MlirWalkResultSkip.int      => WalkResultEnum.Skip
  extension (ref: WalkResultEnum)
    inline def toNative: Int = ref match
      case WalkResultEnum.Advance   => MlirEnums.MlirWalkResult.MlirWalkResultAdvance.int
      case WalkResultEnum.Interrupt => MlirEnums.MlirWalkResult.MlirWalkResultInterrupt.int
      case WalkResultEnum.Skip      => MlirEnums.MlirWalkResult.MlirWalkResultSkip.int
    inline def sizeOf:   Int = 4
end given

given WalkEnumApi with
  extension (int: Int)
    inline def fromNative: WalkEnum = int match
      case i if i == MlirEnums.MlirWalkOrder.MlirWalkPostOrder.int => WalkEnum.PostOrder
      case i if i == MlirEnums.MlirWalkOrder.MlirWalkPreOrder.int  => WalkEnum.PreOrder
  extension (ref: WalkEnum)
    inline def toNative: Int = ref match
      case WalkEnum.PostOrder => MlirEnums.MlirWalkOrder.MlirWalkPostOrder.int
      case WalkEnum.PreOrder  => MlirEnums.MlirWalkOrder.MlirWalkPreOrder.int
    inline def sizeOf:   Int = 4
end given

given OperationWalkCallbackApi with
  extension (operationCallBack: Operation => WalkResultEnum)
    inline def toOperationWalkCallback(
      using zone: Zone
    ): OperationWalkCallback = OperationWalkCallback(operationCallBack)
end given
