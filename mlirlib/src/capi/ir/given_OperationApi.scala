// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.ir

import org.llvm.mlir.*
import org.llvm.mlir.CAPI.{
  mlirOperationClone,
  mlirOperationCreate,
  mlirOperationCreateParse,
  mlirOperationDestroy,
  mlirOperationDump,
  mlirOperationGetAttribute,
  mlirOperationGetAttributeByName,
  mlirOperationGetBlock,
  mlirOperationGetContext,
  mlirOperationGetDiscardableAttribute,
  mlirOperationGetDiscardableAttributeByName,
  mlirOperationGetFirstRegion,
  mlirOperationGetInherentAttributeByName,
  mlirOperationGetLocation,
  mlirOperationGetName,
  mlirOperationGetNextInBlock,
  mlirOperationGetNumResults,
  mlirOperationGetOperand,
  mlirOperationGetParentOperation,
  mlirOperationGetRegion,
  mlirOperationGetResult,
  mlirOperationGetSuccessor,
  mlirOperationGetTypeID,
  mlirOperationMoveAfter,
  mlirOperationMoveBefore,
  mlirOperationPrint,
  mlirOperationPrintWithState,
  mlirOperationRemoveFromParent,
  mlirOperationSetAttributeByName,
  mlirOperationSetDiscardableAttributeByName,
  mlirOperationSetInherentAttributeByName,
  mlirOperationSetOperand,
  mlirOperationSetOperands,
  mlirOperationSetSuccessor,
  mlirOperationWalk,
  mlirOperationWriteBytecode,
  mlirOperationWriteBytecodeWithConfig,
  MlirWalkPostOrder,
  MlirWalkPreOrder,
  MlirWalkResultAdvance,
  MlirWalkResultInterrupt,
  MlirWalkResultSkip
}
import org.llvm.mlir.scalalib.capi.support.{*, given}

import java.lang.foreign.{Arena, MemorySegment}

given OperationApi with
  inline def operationCreate(
    state:       OperationState
  )(
    using arena: Arena
  ): Operation = Operation(mlirOperationCreate(arena, state.segment))
  inline def operationCreate(
    name:                     String,
    location:                 Location,
    regionBlockTypeLocations: Seq[Seq[(Seq[Type], Seq[Location])]] = Seq.empty,
    namedAttributes:          Seq[NamedAttribute] = Seq.empty,
    operands:                 Seq[Value] = Seq.empty,
    resultsTypes:             Option[Seq[Type]] = None,
    inferredResultsTypes:     Option[Int] = None
  )(
    using arena:              Arena,
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
    sourceStr:   String,
    sourceName:  String
  )(
    using arena: Arena,
    context:     Context
  ): Operation = Operation(
    mlirOperationCreateParse(arena, context.segment, sourceStr.toStringRef.segment, sourceName.toStringRef.segment)
  )
  inline def operationClone(
    op:          Operation
  )(
    using arena: Arena
  ): Operation = Operation(mlirOperationClone(arena, op.segment))
  extension (operation: Operation)
    inline def getContext(
      using arena: Arena
    ): Context = Context(mlirOperationGetContext(arena, operation.segment))
    inline def getLocation(
      using arena: Arena
    ): Location = Location(mlirOperationGetLocation(arena, operation.segment))
    inline def getTypeID(
      using arena: Arena
    ): TypeID = TypeID(mlirOperationGetTypeID(arena, operation.segment))
    inline def getName(
      using arena: Arena
    ): Identifier = Identifier(mlirOperationGetName(arena, operation.segment))
    inline def getBlock(
      using arena: Arena
    ): Block = Block(mlirOperationGetBlock(arena, operation.segment))
    inline def getParentOperation(
      using arena: Arena
    ): Operation = Operation(mlirOperationGetParentOperation(arena, operation.segment))
    inline def getRegion(
      pos:         Long
    )(
      using arena: Arena
    ): Region = Region(mlirOperationGetRegion(arena, operation.segment, pos))
    inline def getNextInBlock(
      using arena: Arena
    ): Operation = Operation(mlirOperationGetNextInBlock(arena, operation.segment))
    inline def getOperand(
      pos:         Long
    )(
      using arena: Arena
    ): Value = Value(mlirOperationGetOperand(arena, operation.segment, pos))
    inline def getResult(
      pos:         Long
    )(
      using arena: Arena
    ): Value = Value(mlirOperationGetResult(arena, operation.segment, pos))
    inline def getNumResults: Long =
      mlirOperationGetNumResults(operation.segment)
    inline def getSuccessor(
      pos:         Long
    )(
      using arena: Arena
    ): Block = Block(mlirOperationGetSuccessor(arena, operation.segment, pos))
    inline def getInherentAttributeByName(
      name:        String
    )(
      using arena: Arena
    ): Attribute = Attribute(
      mlirOperationGetInherentAttributeByName(arena, operation.segment, name.toStringRef.segment)
    )
    inline def getDiscardableAttribute(
      pos:         Long
    )(
      using arena: Arena
    ): NamedAttribute = NamedAttribute(mlirOperationGetDiscardableAttribute(arena, operation.segment, pos))
    inline def getDiscardableAttributeByName(
      name:        String
    )(
      using arena: Arena
    ): Attribute = Attribute(
      mlirOperationGetDiscardableAttributeByName(arena, operation.segment, name.toStringRef.segment)
    )
    inline def getAttribute(
      pos:         Long
    )(
      using arena: Arena
    ): NamedAttribute = NamedAttribute(mlirOperationGetAttribute(arena, operation.segment, pos))
    inline def getAttributeByName(
      name:        String
    )(
      using arena: Arena
    ): Attribute = Attribute(mlirOperationGetAttributeByName(arena, operation.segment, name.toStringRef.segment))
    inline def writeBytecodeWithConfig(
      config:      BytecodeWriterConfig,
      callback:    Array[Byte] => Unit
    )(
      using arena: Arena
    ): LogicalResult = LogicalResult(
      mlirOperationWriteBytecodeWithConfig(
        arena,
        operation.segment,
        config.segment,
        callback.bytesToStringCallback.segment,
        MemorySegment.NULL
      )
    )
    inline def getFirstRegion(
      using arena: Arena
    ): Region = Region(mlirOperationGetFirstRegion(arena, operation.segment))

    // Scala Only API
    inline def destroy():                              Unit = mlirOperationDestroy(operation.segment)
    inline def removeFromParent():                     Unit = mlirOperationRemoveFromParent(operation.segment)
    inline def setOperand(pos: Long, newValue: Value): Unit =
      mlirOperationSetOperand(operation.segment, pos, newValue.segment)
    inline def setOperands(
      operands: Seq[Value]
    )(
      using Arena
    ): Unit = mlirOperationSetOperands(operation.segment, operands.size, operands.toMlirArray)
    inline def setSuccessor(pos: Long, block: Block):  Unit =
      mlirOperationSetSuccessor(operation.segment, pos, block.segment)
    inline def setInherentAttributeByName(
      name: String,
      attr: Attribute
    )(
      using Arena
    ): Unit = mlirOperationSetInherentAttributeByName(operation.segment, name.toStringRef.segment, attr.segment)
    inline def setDiscardableAttributeByName(
      name: String,
      attr: Attribute
    )(
      using Arena
    ): Unit = mlirOperationSetDiscardableAttributeByName(operation.segment, name.toStringRef.segment, attr.segment)
    inline def setAttributeByName(
      name: String,
      attr: Attribute
    )(
      using Arena
    ): Unit = mlirOperationSetAttributeByName(operation.segment, name.toStringRef.segment, attr.segment)
    inline def print(
      callback: String => Unit
    )(
      using Arena
    ): Unit = mlirOperationPrint(operation.segment, callback.stringToStringCallback.segment, MemorySegment.NULL)
    inline def printWithFlags(
      callback: String => Unit
    )(
      using Arena
    ): Unit = mlirOperationPrint(operation.segment, callback.stringToStringCallback.segment, MemorySegment.NULL)
    inline def printWithState(
      asmState: AsmState,
      callback: String => Unit
    )(
      using Arena
    ): Unit = mlirOperationPrintWithState(
      operation.segment,
      asmState.segment,
      callback.stringToStringCallback.segment,
      MemorySegment.NULL
    )
    inline def writeBytecode(
      callBack:    Array[Byte] => Unit
    )(
      using arena: Arena
    ): Unit = mlirOperationWriteBytecode(operation.segment, callBack.bytesToStringCallback.segment, MemorySegment.NULL)

    inline def dump():                       Unit          = mlirOperationDump(operation.segment)
    inline def moveAfter(other:  Operation): Unit          = mlirOperationMoveAfter(operation.segment, other.segment)
    inline def moveBefore(other: Operation): Unit          = mlirOperationMoveBefore(operation.segment, other.segment)
    inline def walk(
      callback:    Operation => WalkResultEnum,
      walk:        WalkEnum
    )(
      using arena: Arena
    ): Unit =
      mlirOperationWalk(operation.segment, callback.toOperationWalkCallback.segment, MemorySegment.NULL, walk.toNative)
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
    inline def segment:                      MemorySegment = operation._segment
    inline def sizeOf:                       Int           = MlirOperation.sizeof().toInt
end given

given WalkResultEnumApi with
  extension (int: Int)
    inline def fromNative: WalkResultEnum = int match
      case i if i == MlirWalkResultAdvance()   => WalkResultEnum.Advance
      case i if i == MlirWalkResultInterrupt() => WalkResultEnum.Interrupt
      case i if i == MlirWalkResultSkip()      => WalkResultEnum.Skip
  extension (ref: WalkResultEnum)
    inline def toNative: Int = ref match
      case WalkResultEnum.Advance   => MlirWalkResultAdvance()
      case WalkResultEnum.Interrupt => MlirWalkResultInterrupt()
      case WalkResultEnum.Skip      => MlirWalkResultSkip()
    inline def sizeOf:   Int = 4
end given

given WalkEnumApi with
  extension (int: Int)
    inline def fromNative: WalkEnum = int match
      case i if i == MlirWalkPostOrder() => WalkEnum.PostOrder
      case i if i == MlirWalkPreOrder()  => WalkEnum.PreOrder
  extension (ref: WalkEnum)
    inline def toNative: Int = ref match
      case WalkEnum.PostOrder => MlirWalkPostOrder()
      case WalkEnum.PreOrder  => MlirWalkPreOrder()
    inline def sizeOf:   Int = 4
end given

given OperationWalkCallbackApi with
  extension (operationCallBack: Operation => WalkResultEnum)
    inline def toOperationWalkCallback(
      using arena: Arena
    ): OperationWalkCallback =
      OperationWalkCallback(
        MlirOperationWalkCallback.allocate(
          (operation: MemorySegment, userData: MemorySegment) =>
            try operationCallBack(Operation(operation)).toNative
            catch
              case t: Throwable =>
                System.err.println(s"[zaozi:upcall:operation-walk] ${t.getClass.getName}: ${t.getMessage}")
                t.printStackTrace(System.err)
                WalkResultEnum.Interrupt.toNative
          ,
          arena
        )
      )
  extension (operationCallBack: OperationWalkCallback) inline def segment: MemorySegment = operationCallBack._segment
end given
