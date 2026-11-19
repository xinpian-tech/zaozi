// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.ir

import org.llvm.mlir.raw.structs as MlirStructs
import scala.scalanative.unsafe.*

import org.llvm.mlir.scalalib.capi.diagnostic.Diagnostic
import org.llvm.mlir.scalalib.capi.support.{*, given}

class Context(handle: Ptr[MlirStructs.MlirContext]) extends NativeHandle(handle)
trait ContextApi                                    extends HasSizeOf[Context]:
  inline def contextCreate(
    using zone: Zone
  ): Context
  inline def contextCreateWithThreading(
    threadingEnabled: Boolean
  )(
    using zone:       Zone
  ): Context
  inline def contextCreateWithRegistry(
    registry:         DialectRegistry,
    threadingEnabled: Boolean
  )(
    using zone:       Zone
  ): Context
  extension (context: Context)
    inline def getOrLoadDialect(
      name:       String
    )(
      using zone: Zone
    ):                                                            Dialect
    inline def destroy():                                         Unit
    inline def allowUnregisteredDialects(allow: Boolean):         Unit
    inline def appendDialectRegistry(registry:  DialectRegistry): Unit
    inline def enableMultithreading(enable:     Boolean):         Unit
    inline def loadAllAvailableDialects():                        Unit
    inline def setThreadPool(threadPool:        LlvmThreadPool):  Unit
    def attachDiagnosticHandler(
      handler:    Diagnostic => Boolean
    )(
      using zone: Zone
    ):                                                            Long
    inline def detachDiagnosticHandler(id:      Long):            Unit
end ContextApi

class Dialect(handle: Ptr[MlirStructs.MlirDialect]) extends NativeHandle(handle)
trait DialectApi                                    extends HasSizeOf[Dialect]

class DialectHandle(handle: Ptr[MlirStructs.MlirDialectHandle]) extends NativeHandle(handle)
trait DialectHandleApi                                          extends HasSizeOf[DialectHandle]:
  extension (dialectHandle: DialectHandle)
    inline def getNamespace(
      using zone: Zone
    ):                                                          String
    inline def loadDialect(
      using zone: Zone,
      context:    Context
    ):                                                          Unit
    inline def insertDialect(dialectRegistry: DialectRegistry): Unit
    inline def insertDialect(
    )(
      using context: Context
    ):                                                          Unit
end DialectHandleApi

class DialectRegistry(handle: Ptr[MlirStructs.MlirDialectRegistry]) extends NativeHandle(handle)
trait DialectRegistryApi                                            extends HasSizeOf[DialectRegistry]:
  inline def registryCreate(
  )(
    using zone: Zone
  ):                                                                 DialectRegistry
  extension (dialectRegistry: DialectRegistry) inline def destroy(): Unit
end DialectRegistryApi

class Location(handle: Ptr[MlirStructs.MlirLocation]) extends NativeHandle(handle)
trait LocationApi                                     extends HasSizeOf[Location]:
  inline def locationFromAttribute(
    attribute:  Attribute
  )(
    using zone: Zone
  ): Location
  inline def locationFileLineColGet(
    filename:   String,
    line:       Int,
    col:        Int
  )(
    using zone: Zone,
    context:    Context
  ): Location
  inline def locationCallSiteGet(
    callee:     Location,
    caller:     Location
  )(
    using zone: Zone,
    context:    Context
  ): Location
  inline def locationFusedGet(
    locations:  Seq[Location],
    metadata:   Attribute
  )(
    using zone: Zone,
    context:    Context
  ): Location
  inline def locationNameGet(
    name:       String,
    childLoc:   Location
  )(
    using zone: Zone,
    context:    Context
  ): Location
  inline def locationUnknownGet(
    using zone: Zone,
    context:    Context
  ): Location
  extension (location: Location)
    inline def getAttribute(
      using zone: Zone
    ): Attribute
    inline def getContext(
      using zone: Zone
    ): Context
    inline def print(
      callback:   String => Unit
    )(
      using zone: Zone
    ): Unit
end LocationApi

class Module(handle: Ptr[MlirStructs.MlirModule]) extends NativeHandle(handle)
trait ModuleApi                                   extends HasSizeOf[Module]:
  extension (module: Module) inline def isNull: Boolean
  inline def moduleCreateEmpty(
    location:   Location
  )(
    using zone: Zone
  ):                                            Module
  inline def moduleCreateParse(
    module:     String | Array[Byte]
  )(
    using zone: Zone,
    context:    Context
  ):                                            Module
  inline def moduleFromOperation(
    op:         Operation
  )(
    using zone: Zone
  ):                                            Module
  extension (module: Module)
    inline def getContext(
      using zone: Zone
    ):                    Context
    inline def getBody(
      using zone: Zone
    ):                    Block
    inline def getOperation(
      using zone: Zone
    ):                    Operation
    inline def destroy(): Unit
end ModuleApi

class OperationState(handle: Ptr[MlirStructs.MlirOperationState]) extends NativeHandle(handle)
trait OperationStateApi                                           extends HasSizeOf[OperationState]:
  inline def operationStateGet(
    name:       String,
    loc:        Location
  )(
    using zone: Zone
  ): OperationState
  extension (operationState: OperationState)
    inline def addResults(
      results:    Seq[Type]
    )(
      using zone: Zone
    ):                                      Unit
    inline def addOperands(
      operands:   Seq[Value]
    )(
      using zone: Zone
    ):                                      Unit
    inline def addOwnedRegions(
      regions:    Seq[Region]
    )(
      using zone: Zone
    ):                                      Unit
    inline def addSuccessors(
      blocks:     Seq[Block]
    )(
      using zone: Zone
    ):                                      Unit
    inline def addAttributes(
      attrs:      Seq[NamedAttribute]
    )(
      using zone: Zone
    ):                                      Unit
    inline def enableResultTypeInference(): Unit
end OperationStateApi

class AsmState(handle: Ptr[MlirStructs.MlirAsmState]) extends NativeHandle(handle)
trait AsmStateApi                                     extends HasSizeOf[AsmState]

class OpPrintingFlags(handle: Ptr[MlirStructs.MlirOpPrintingFlags]) extends NativeHandle(handle)
trait OpPrintingFlagsApi                                            extends HasSizeOf[OpPrintingFlags]:
  inline def opPrintingFlagsCreate(
  )(
    using zone: Zone
  ): OpPrintingFlags

  extension (opPrintingFlags: OpPrintingFlags)
    inline def flagsDestroy():                                                                  Unit
    inline def flagsElideLargeElementsAttrs(largeElementLimit:   Long):                         Unit
    inline def flagsElideLargeResourceString(largeResourceLimit: Long):                         Unit
    inline def flagsEnableDebugInfo(enable:                      Boolean, prettyForm: Boolean): Unit
    inline def flagsPrintGenericOpForm():                                                       Unit
    inline def flagsUseLocalScope():                                                            Unit
    inline def flagsAssumeVerified():                                                           Unit
    inline def flagsSkipRegions():                                                              Unit
end OpPrintingFlagsApi

class BytecodeWriterConfig(handle: Ptr[MlirStructs.MlirBytecodeWriterConfig]) extends NativeHandle(handle)
trait BytecodeWriterConfigApi                                                 extends HasSizeOf[BytecodeWriterConfig]

class Operation(handle: Ptr[MlirStructs.MlirOperation]) extends NativeHandle(handle)
trait OperationApi                                      extends HasSizeOf[Operation]:
  extension (operation: Operation) inline def isNull: Boolean
  inline def operationCreate(
    state:      OperationState
  )(
    using zone: Zone
  ):                                                  Operation
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
  ):                                                  Operation
  inline def operationCreateParse(
    sourceStr:  String,
    sourceName: String
  )(
    using zone: Zone,
    context:    Context
  ):                                                  Operation
  inline def operationClone(
    op:         Operation
  )(
    using zone: Zone
  ):                                                  Operation
  extension (operation: Operation)
    inline def getContext(
      using zone: Zone
    ):                                                   Context
    inline def getLocation(
      using zone: Zone
    ):                                                   Location
    inline def getTypeID(
      using zone: Zone
    ):                                                   TypeID
    inline def getName(
      using zone: Zone
    ):                                                   Identifier
    inline def getBlock(
      using zone: Zone
    ):                                                   Block
    inline def getParentOperation(
      using zone: Zone
    ):                                                   Operation
    inline def getRegion(
      pos:        Long
    )(
      using zone: Zone
    ):                                                   Region
    inline def getNextInBlock(
      using zone: Zone
    ):                                                   Operation
    inline def getOperand(
      pos:        Long
    )(
      using zone: Zone
    ):                                                   Value
    inline def getResult(
      pos:        Long
    )(
      using zone: Zone
    ):                                                   Value
    inline def getNumResults:                            Long
    inline def verify:                                   Boolean
    inline def getSuccessor(
      pos:        Long
    )(
      using zone: Zone
    ):                                                   Block
    inline def getInherentAttributeByName(
      name:       String
    )(
      using zone: Zone
    ):                                                   Attribute
    inline def getDiscardableAttribute(
      pos:        Long
    )(
      using zone: Zone
    ):                                                   NamedAttribute
    inline def getDiscardableAttributeByName(
      name:       String
    )(
      using zone: Zone
    ):                                                   Attribute
    inline def getAttribute(
      pos:        Long
    )(
      using zone: Zone
    ):                                                   NamedAttribute
    inline def getAttributeByName(
      name:       String
    )(
      using zone: Zone
    ):                                                   Attribute
    inline def writeBytecodeWithConfig(
      config:     BytecodeWriterConfig,
      callback:   Array[Byte] => Unit
    )(
      using zone: Zone
    ):                                                   LogicalResult
    inline def getFirstRegion(
      using zone: Zone
    ):                                                   Region
    inline def destroy():                                Unit
    inline def removeFromParent():                       Unit
    inline def setOperand(pos:   Long, newValue: Value): Unit
    inline def setOperands(
      operands: Seq[Value]
    )(
      using Zone
    ):                                                   Unit
    inline def setSuccessor(pos: Long, block:    Block): Unit
    inline def setInherentAttributeByName(
      name: String,
      attr: Attribute
    )(
      using Zone
    ):                                                   Unit
    inline def setDiscardableAttributeByName(
      name: String,
      attr: Attribute
    )(
      using Zone
    ):                                                   Unit
    inline def setAttributeByName(
      name: String,
      attr: Attribute
    )(
      using Zone
    ):                                                   Unit
    inline def print(
      callback: String => Unit
    )(
      using Zone
    ):                                                   Unit
    inline def printWithFlags(
      callback: String => Unit
    )(
      using Zone
    ):                                                   Unit
    inline def printWithState(
      asmState: AsmState,
      callback: String => Unit
    )(
      using Zone
    ):                                                   Unit
    inline def writeBytecode(
      callBack:   Array[Byte] => Unit
    )(
      using zone: Zone
    ):                                                   Unit
    inline def dump():                                   Unit
    inline def moveAfter(other:  Operation):             Unit
    inline def moveBefore(other: Operation):             Unit
    inline def walk(
      callback:   Operation => WalkResultEnum,
      walk:       WalkEnum
    )(
      using zone: Zone
    ):                                                   Unit

    inline def appendToBlock(
    )(
      using block: Block
    ): Unit
    inline def insertToBlock(
      pos:         Long
    )(
      using block: Block
    ): Unit
    inline def insertAfter(
      ref:         Operation
    )(
      using block: Block
    ): Unit
    inline def insertBefore(
      ref:         Operation
    )(
      using block: Block
    ): Unit
end OperationApi

class OperationWalkCallback(val run: Operation => WalkResultEnum)
trait OperationWalkCallbackApi

enum WalkResultEnum:
  case Advance
  case Interrupt
  case Skip
end WalkResultEnum
trait WalkResultEnumApi extends HasSizeOf[WalkResultEnum] with EnumHasToNative[WalkResultEnum]

enum WalkEnum:
  case PostOrder
  case PreOrder
end WalkEnum
trait WalkEnumApi extends HasSizeOf[WalkEnum] with EnumHasToNative[WalkEnum]

class Region(handle: Ptr[MlirStructs.MlirRegion]) extends NativeHandle(handle)
trait RegionApi                                   extends HasSizeOf[Region]:
  inline def regionCreate(
    using zone: Zone
  ): Region
  extension (op: Region)
    inline def getFirstBlock(
      using zone: Zone
    ):                                                                 Block
    inline def getNextInOperation(
      using zone: Zone
    ):                                                                 Region
    inline def destroy(
    )(
      using zone: Zone
    ):                                                                 Unit
    inline def appendOwnedBlock(
      block: Block
    ):                                                                 Unit
    inline def insertOwnedBlock(pos:             Long, block:  Block): Unit
    inline def insertOwnedBlockAfter(reference:  Block, block: Block): Unit
    inline def insertOwnedBlockBefore(reference: Block, block: Block): Unit
end RegionApi

class Block(handle: Ptr[MlirStructs.MlirBlock]) extends NativeHandle(handle)
trait BlockApi                                  extends HasSizeOf[Block]:
  inline def blockCreate(
    args:       Seq[Type],
    locs:       Seq[Location]
  )(
    using zone: Zone
  ): Block
  extension (block: Block)
    inline def getParentOperation(
      using zone: Zone
    ):                                                                                 Operation
    inline def getNextInRegion(
      using zone: Zone
    ):                                                                                 Block
    inline def getFirstOperation(
      using zone: Zone
    ):                                                                                 Operation
    inline def getTerminator(
      using zone: Zone
    ):                                                                                 Operation
    inline def addArgument(
      tpe:        Type,
      loc:        Location
    )(
      using zone: Zone
    ):                                                                                 Value
    inline def insertArgument(
      pos:        Int,
      tpe:        Type,
      loc:        Location
    )(
      using zone: Zone
    ):                                                                                 Value
    inline def getArgument(
      pos:        Long
    )(
      using zone: Zone
    ):                                                                                 Value
    inline def destroy():                                                              Unit
    inline def detach():                                                               Unit
    inline def appendOwnedOperation(operation:       Operation):                       Unit
    inline def insertOwnedOperation(pos:             Long, operation:      Operation): Unit
    inline def insertOwnedOperationAfter(reference:  Operation, operation: Operation): Unit
    inline def insertOwnedOperationBefore(reference: Operation, operation: Operation): Unit
    inline def eraseArgument(index:                  Int):                             Unit
    inline def print(
      callBack:   String => Unit
    )(
      using zone: Zone
    ):                                                                                 Unit
end BlockApi

class Value(handle: Ptr[MlirStructs.MlirValue]) extends NativeHandle(handle)
trait ValueApi                                  extends HasSizeOf[Value]:
  extension (value: Value)
    inline def argumentGetOwner(
      using Zone
    ):                                         Block
    inline def getType(
      using zone: Zone
    ):                                         Type
    inline def getFirstUse(
      using zone: Zone
    ):                                         OpOperand
    inline def setType(tpe:            Type):  Unit
    inline def dump():                         Unit
    inline def print(
      callback:   String => Unit
    )(
      using zone: Zone
    ):                                         Unit
    inline def printAsOperand(
      state:      AsmState,
      callback:   String => Unit
    )(
      using zone: Zone
    ):                                         Unit
    inline def replaceAllUsesOfWith(w: Value): Unit
    inline def isOpResult:                     Boolean
    inline def isBlockArgument:                Boolean
    inline def opResultGetOwner(
      using zone: Zone
    ):                                         Operation
    inline def opResultGetResultNumber:        Long
    inline def blockArgumentGetOwner(
      using zone: Zone
    ):                                         Block
    inline def blockArgumentGetArgNumber:      Long
end ValueApi

class OpOperand(handle: Ptr[MlirStructs.MlirOpOperand]) extends NativeHandle(handle)
trait OpOperandApi                                      extends HasSizeOf[OpOperand]

class Type(handle: Ptr[MlirStructs.MlirType]) extends NativeHandle(handle)
trait TypeApi                                 extends HasSizeOf[Type]:
  inline def f64TypeGet(
    using zone: Zone,
    context:    Context
  ): Type

  inline def noneTypeGet(
    using zone: Zone,
    context:    Context
  ): Type

  extension (width: Int)
    inline def integerTypeSignedGet(
      using zone: Zone,
      context:    Context
    ): Type
    inline def integerTypeUnsignedGet(
      using zone: Zone,
      context:    Context
    ): Type
    inline def integerTypeGet(
      using zone: Zone,
      context:    Context
    ): Type
  extension (tpe:   Type)
    inline def equal(that: Type): Boolean
    inline def print(
      callback:   String => Unit
    )(
      using zone: Zone
    ):                            Unit
end TypeApi

class Attribute(handle: Ptr[MlirStructs.MlirAttribute]) extends NativeHandle(handle)
trait AttributeApi                                      extends HasSizeOf[Attribute]:
  inline def allocateAttribute(
    using zone: Zone
  ):                                                      Attribute
  inline def getNull(
    using zone: Zone,
    context:    Context
  ):                                                      Attribute
  extension (attribute: Attribute)
    inline def getType(
      using zone: Zone
    ):                                                    Type
  // Location
  extension (attribute: Attribute) inline def isLocation: Boolean
  // Array
  inline def arrayAttrGetTypeID(
    using zone: Zone
  ):                                                      TypeID
  extension (array:     Seq[Attribute])
    inline def arrayAttrGet(
      using zone: Zone,
      context:    Context
    ):                                                    Attribute
  extension (attribute: Attribute)
    inline def isArray:                 Boolean
    inline def arrayAttrGetNumElements: Int
    inline def arrayAttrGetElement(
      idx:        Int
    )(
      using zone: Zone
    ):                                  Attribute
  // Dictionary
  inline def dictionaryAttrGetTypeID(
    using zone: Zone
  ):                                                      TypeID
  extension (map:       Map[String, Attribute])
    inline def directoryAttrGet(
      using zone: Zone,
      context:    Context
    ):                                                    Attribute
  extension (attribute: Attribute)
    inline def isDictionary:                 Boolean
    inline def dictionaryAttrGetNumElements: Int
    inline def dictionaryAttrGetElement(
      idx:        Int
    )(
      using zone: Zone
    ):                                       NamedAttribute
  // Floating point
  inline def floatAttrGetTypeID(
    using zone: Zone
  ):                                                      TypeID
  extension (double:    Double)
    inline def floatAttrDoubleGet(
      tpe:        Type
    )(
      using zone: Zone,
      context:    Context
    ): Attribute
    inline def floatAttrDoubleGetChecked(
      tpe:        Type
    )(
      using zone: Zone,
      location:   Location
    ): Attribute
  extension (attribute: Attribute)
    inline def isFloat:                 Boolean
    inline def floatAttrGetValueDouble: Double
  // Integer
  inline def integerAttrGetTypeID(
    using zone: Zone
  ):                                                      TypeID
  extension (int:       Long)
    inline def integerAttrGet(
      tpe:        Type
    )(
      using zone: Zone,
      context:    Context
    ):                                                    Attribute
  extension (attribute: Attribute)
    inline def isInteger:               Boolean
    inline def integerAttrGetValueInt:  Long
    inline def integerAttrGetValueSInt: Long
    inline def integerAttrGetValueUInt: Long
  // Bool
  extension (bool:      Boolean)
    inline def boolAttrGet(
      using zone: Zone,
      context:    Context
    ):                                                    Attribute
  extension (attribute: Attribute)
    inline def isBool:           Boolean
    inline def boolAttrGetValue: Boolean

  // String
  def stringAttrGetTypeID(
    using zone: Zone
  ): TypeID

  extension (string:    String)
    inline def stringAttrGet(
      using zone: Zone,
      context:    Context
    ): Attribute
    inline def stringAttrTypedGet(
      tpe:        Type
    )(
      using zone: Zone
    ): Attribute
  extension (attribute: Attribute)
    inline def isString: Boolean
    inline def stringAttrGetValue(
      using zone: Zone
    ):                   String
  // SymbolRef
  inline def symbolRefAttrGetTypeID(
    using zone: Zone
  ): TypeID

  extension (symbol:    String)
    inline def symbolRefAttrGet(
      references: Seq[Attribute]
    )(
      using zone: Zone,
      context:    Context
    ):    Attribute
  extension (attribute: Attribute)
    inline def isSymbolRef:                         Boolean
    inline def symbolRefAttrGetRootReference(
      using zone: Zone
    ):                                              String
    inline def symbolRefAttrGetLeafReference(
      using zone: Zone
    ):                                              String
    inline def symbolRefAttrGetNumNestedReferences: Long
    inline def symbolRefAttrGetNestedReference(
      pos:        Long
    )(
      using zone: Zone
    ):                                              Attribute
    inline def distinctAttrCreate(
      using zone: Zone
    ):                                              Attribute
    inline def disctinctAttrCreate(
      using zone: Zone
    ):                                              Attribute
  // Flat SymbolRef
  extension (string:    String)
    inline def flatSymbolRefAttrGet(
      using zone: Zone,
      context:    Context
    ):    Attribute
  extension (attribute: Attribute)
    inline def isFlatSymbolRef: Boolean
    inline def flatSymbolRefAttrGetValue(
      using zone: Zone
    ):                          String

  // Type
  inline def typeAttrGetTypeID(
    using zone: Zone
  ): TypeID

  extension (tpe:       Type)
    inline def typeAttrGet(
      using zone: Zone,
      context:    Context
    ):       Attribute
  extension (attribute: Attribute)
    inline def isType: Boolean
    inline def typeAttrGetValue(
      using zone: Zone
    ):                 Type

  // Unit
  inline def unitAttrGetTypeID(
    using zone: Zone
  ): TypeID

  inline def unitAttrGet(
    using zone: Zone,
    context:    Context
  ): Attribute

  extension (attribute: Attribute) inline def isUnit: Boolean

  inline def denseArrayAttrGetTypeID(
    using zone: Zone
  ): TypeID

  extension (bools:     Seq[Boolean])
    inline def denseBoolArrayGet(
      using zone: Zone,
      context:    Context
    ):     Attribute
  extension (ints:      Seq[Int])
    inline def denseI8ArrayGet(
      using zone: Zone,
      context:    Context
    ): Attribute
    inline def denseI16ArrayGet(
      using zone: Zone,
      context:    Context
    ): Attribute
    inline def denseI32ArrayGet(
      using zone: Zone,
      context:    Context
    ): Attribute
  extension (longs:     Seq[Long])
    inline def denseI64ArrayGet(
      using zone: Zone,
      context:    Context
    ):     Attribute
  extension (floats:    Seq[Float])
    inline def denseF32ArrayGet(
      using zone: Zone,
      context:    Context
    ):     Attribute
  extension (doubles:   Seq[Double])
    inline def denseF64ArrayGet(
      using zone: Zone,
      context:    Context
    ):     Attribute
  extension (attribute: Attribute)
    inline def isDenseBoolArray:                    Boolean
    inline def isDenseI8Array:                      Boolean
    inline def isDenseI16Array:                     Boolean
    inline def isDenseI32Array:                     Boolean
    inline def isDenseI64Array:                     Boolean
    inline def isDenseF32Array:                     Boolean
    inline def isDenseF64Array:                     Boolean
    inline def denseArrayGetNumElements:            Long
    inline def denseBoolArrayGetElement(pos: Long): Boolean
    inline def denseI8ArrayGetElement(pos:   Long): Int
    inline def denseI16ArrayGetElement(pos:  Long): Int
    inline def denseI32ArrayGetElement(pos:  Long): Int
    inline def denseI64ArrayGetElement(pos:  Long): Long
    inline def denseF32ArrayGetElement(pos:  Long): Float
    inline def denseF64ArrayGetElement(pos:  Long): Double
end AttributeApi

class NamedAttribute(handle: Ptr[MlirStructs.MlirNamedAttribute]) extends NativeHandle(handle)
trait NamedAttributeApi                                           extends HasSizeOf[NamedAttribute]:
  inline def namedAttributeGet(
    identifier: Identifier,
    attribute:  Attribute
  )(
    using zone: Zone
  ): NamedAttribute
end NamedAttributeApi

class Identifier(handle: Ptr[MlirStructs.MlirIdentifier]) extends NativeHandle(handle)
trait IdentifierApi                                       extends HasSizeOf[Identifier]:
  extension (identifierString: String)
    inline def identifierGet(
      using zone: Zone,
      context:    Context
    ): Identifier
  extension (identifier:       Identifier)
    inline def getContext(
      using zone: Zone
    ): Context
    inline def str(
      using zone: Zone
    ): String
end IdentifierApi

class SymbolTable(handle: Ptr[MlirStructs.MlirSymbolTable]) extends NativeHandle(handle)
trait SymbolTableApi                                        extends HasSizeOf[SymbolTable]
