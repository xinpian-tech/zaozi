// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.ir

import org.llvm.mlir.*
import org.llvm.mlir.CAPI.{
  mlirArrayAttrGet,
  mlirArrayAttrGetElement,
  mlirArrayAttrGetNumElements,
  mlirArrayAttrGetTypeID,
  mlirAttributeGetNull,
  mlirAttributeGetType,
  mlirAttributeIsAArray,
  mlirAttributeIsABool,
  mlirAttributeIsADenseBoolArray,
  mlirAttributeIsADenseF32Array,
  mlirAttributeIsADenseF64Array,
  mlirAttributeIsADenseI16Array,
  mlirAttributeIsADenseI32Array,
  mlirAttributeIsADenseI64Array,
  mlirAttributeIsADenseI8Array,
  mlirAttributeIsADictionary,
  mlirAttributeIsAFlatSymbolRef,
  mlirAttributeIsAFloat,
  mlirAttributeIsAInteger,
  mlirAttributeIsALocation,
  mlirAttributeIsAString,
  mlirAttributeIsASymbolRef,
  mlirAttributeIsAType,
  mlirAttributeIsAUnit,
  mlirBoolAttrGet,
  mlirBoolAttrGetValue,
  mlirDenseArrayAttrGetTypeID,
  mlirDenseArrayGetNumElements,
  mlirDenseBoolArrayGet,
  mlirDenseBoolArrayGetElement,
  mlirDenseF32ArrayGet,
  mlirDenseF32ArrayGetElement,
  mlirDenseF64ArrayGet,
  mlirDenseF64ArrayGetElement,
  mlirDenseI16ArrayGet,
  mlirDenseI16ArrayGetElement,
  mlirDenseI32ArrayGet,
  mlirDenseI32ArrayGetElement,
  mlirDenseI64ArrayGet,
  mlirDenseI64ArrayGetElement,
  mlirDenseI8ArrayGet,
  mlirDenseI8ArrayGetElement,
  mlirDictionaryAttrGet,
  mlirDictionaryAttrGetElement,
  mlirDictionaryAttrGetNumElements,
  mlirDictionaryAttrGetTypeID,
  mlirDistinctAttrCreate,
  mlirFlatSymbolRefAttrGet,
  mlirFlatSymbolRefAttrGetValue,
  mlirFloatAttrDoubleGet,
  mlirFloatAttrDoubleGetChecked,
  mlirFloatAttrGetTypeID,
  mlirFloatAttrGetValueDouble,
  mlirIntegerAttrGet,
  mlirIntegerAttrGetFromWords,
  mlirIntegerAttrGetTypeID,
  mlirIntegerAttrGetValueInt,
  mlirIntegerAttrGetValueSInt,
  mlirIntegerAttrGetValueUInt,
  mlirNamedAttributeGet,
  mlirStringAttrGet,
  mlirStringAttrGetTypeID,
  mlirStringAttrGetValue,
  mlirStringAttrTypedGet,
  mlirSymbolRefAttrGet,
  mlirSymbolRefAttrGetLeafReference,
  mlirSymbolRefAttrGetNestedReference,
  mlirSymbolRefAttrGetNumNestedReferences,
  mlirSymbolRefAttrGetRootReference,
  mlirSymbolRefAttrGetTypeID,
  mlirTypeAttrGet,
  mlirTypeAttrGetTypeID,
  mlirTypeAttrGetValue,
  mlirUnitAttrGet,
  mlirUnitAttrGetTypeID
}
import org.llvm.mlir.scalalib.capi.support.{*, given}

import java.lang.foreign.{Arena, MemorySegment, ValueLayout}

given AttributeApi with
  extension (int:       BigInt)
    def integerAttrGet(
      tpe:         Type
    )(
      using arena: Arena,
      context:     Context
    ): Attribute =
      val width = tpe.integerTypeGetWidth
      require(
        width > 0 && int >= -(BigInt(1) << (width - 1)) && int < (BigInt(1) << width),
        s"integer does not fit in $width bits: $int"
      )
      val count = (width + 63) / 64
      val words = arena.allocate(ValueLayout.JAVA_LONG, count.toLong)
      (0 until count).foreach(index =>
        words.setAtIndex(ValueLayout.JAVA_LONG, index.toLong, (int >> (64 * index)).toLong)
      )
      Attribute(mlirIntegerAttrGetFromWords(arena, tpe.segment, count, words))

  inline def allocateAttribute(
    using arena: Arena
  ): Attribute = Attribute(MlirAttribute.allocate(arena))
  inline def getNull(
    using arena: Arena,
    context:     Context
  ): Attribute = Attribute(mlirAttributeGetNull(arena))
  extension (attribute: Attribute)
    inline def getType(
      using arena: Arena
    ): Type = Type(mlirAttributeGetType(arena, attribute.segment))
  // Location
  extension (attribute: Attribute) inline def isLocation: Boolean = mlirAttributeIsALocation(attribute.segment)
  // Array
  inline def arrayAttrGetTypeID(
    using arena: Arena
  ): TypeID = TypeID(mlirArrayAttrGetTypeID(arena))
  extension (array:     Seq[Attribute])
    inline def arrayAttrGet(
      using arena: Arena,
      context:     Context
    ): Attribute =
      Attribute(mlirArrayAttrGet(arena, context.segment, array.size, array.toMlirArray))
  extension (attribute: Attribute)
    inline def isArray:                 Boolean = mlirAttributeIsAArray(attribute.segment)
    inline def arrayAttrGetNumElements: Int     = mlirArrayAttrGetNumElements(attribute.segment).toInt
    inline def arrayAttrGetElement(
      idx:         Int
    )(
      using arena: Arena
    ): Attribute = Attribute(mlirArrayAttrGetElement(arena, attribute.segment, idx.toLong))
  // Dictionary
  inline def dictionaryAttrGetTypeID(
    using arena: Arena
  ) = TypeID(mlirDictionaryAttrGetTypeID(arena))
  extension (map:       Map[String, Attribute])
    inline def directoryAttrGet(
      using arena: Arena,
      context:     Context
    ): Attribute =
      Attribute(
        mlirDictionaryAttrGet(
          arena,
          context.segment,
          map.size,
          map
            .map:
              case (str, attr) =>
                summon[NamedAttributeApi].namedAttributeGet(str.identifierGet, attr)
            .toSeq
            .toMlirArray
        )
      )
  extension (attribute: Attribute)
    inline def isDictionary:                 Boolean = mlirAttributeIsADictionary(attribute.segment)
    inline def dictionaryAttrGetNumElements: Int     = mlirDictionaryAttrGetNumElements(attribute.segment).toInt
    inline def dictionaryAttrGetElement(
      idx:         Int
    )(
      using arena: Arena
    ): Attribute = Attribute(mlirDictionaryAttrGetElement(arena, attribute.segment, idx.toLong))
  // Floating point
  inline def floatAttrGetTypeID(
    using arena: Arena
  ): TypeID = TypeID(mlirFloatAttrGetTypeID(arena))
  extension (double:    Double)
    inline def floatAttrDoubleGet(
      tpe:         Type
    )(
      using arena: Arena,
      context:     Context
    ): Attribute = Attribute(mlirFloatAttrDoubleGet(arena, context.segment, tpe.segment, double))
    inline def floatAttrDoubleGetChecked(
      tpe:         Type
    )(
      using arena: Arena,
      context:     Context
    ): Attribute = Attribute(mlirFloatAttrDoubleGetChecked(arena, context.segment, tpe.segment, double))

  extension (attribute: Attribute)
    inline def isFloat:                 Boolean = mlirAttributeIsAFloat(attribute.segment)
    inline def floatAttrGetValueDouble: Double  = mlirFloatAttrGetValueDouble(attribute.segment)
  // Integer
  inline def integerAttrGetTypeID(
    using arena: Arena
  ): TypeID = TypeID(mlirIntegerAttrGetTypeID(arena))
  extension (int:       Long)
    inline def integerAttrGet(
      tpe:         Type
    )(
      using arena: Arena,
      context:     Context
    ): Attribute = Attribute(mlirIntegerAttrGet(arena, tpe.segment, int))
  extension (attribute: Attribute)
    inline def isInteger:               Boolean = mlirAttributeIsAInteger(attribute.segment)
    inline def integerAttrGetValueInt:  Long    = mlirIntegerAttrGetValueInt(attribute.segment)
    inline def integerAttrGetValueSInt: Long    = mlirIntegerAttrGetValueSInt(attribute.segment)
    inline def integerAttrGetValueUInt: Long    = mlirIntegerAttrGetValueUInt(attribute.segment)
  // Bool
  extension (bool:      Boolean)
    inline def boolAttrGet(
      using arena: Arena,
      context:     Context
    ): Attribute = Attribute(mlirBoolAttrGet(arena, context.segment, if (bool) 1 else 0))
  extension (attribute: Attribute)
    inline def isBool:           Boolean = mlirAttributeIsABool(attribute.segment)
    inline def boolAttrGetValue: Boolean = mlirBoolAttrGetValue(attribute.segment)
  // String
  def stringAttrGetTypeID(
    using arena: Arena
  ): TypeID = TypeID(mlirStringAttrGetTypeID(arena))
  extension (string:    String)
    inline def stringAttrGet(
      using arena: Arena,
      context:     Context
    ): Attribute = Attribute(mlirStringAttrGet(arena, context.segment, string.toStringRef.segment))
    inline def stringAttrTypedGet(
      tpe:         Type
    )(
      using arena: Arena
    ): Attribute = Attribute(mlirStringAttrTypedGet(arena, tpe.segment, string.toStringRef.segment))
  extension (attribute: Attribute)
    inline def isString: Boolean =
      mlirAttributeIsAString(attribute.segment)
    inline def stringAttrGetValue(
      using arena: Arena
    ): String =
      StringRef(mlirStringAttrGetValue(arena, attribute.segment)).toScalaString
  // SymbolRef
  inline def symbolRefAttrGetTypeID(
    using arena: Arena
  ): TypeID = TypeID(mlirSymbolRefAttrGetTypeID(arena))
  extension (symbol:    String)
    inline def symbolRefAttrGet(
      references:  Seq[Attribute]
    )(
      using arena: Arena,
      context:     Context
    ): Attribute = Attribute(
      mlirSymbolRefAttrGet(arena, context.segment, symbol.toStringRef.segment, references.size, references.toMlirArray)
    )
  extension (attribute: Attribute)
    inline def isSymbolRef = mlirAttributeIsASymbolRef(attribute.segment)
    inline def symbolRefAttrGetRootReference(
      using arena: Arena
    ) = StringRef(mlirSymbolRefAttrGetRootReference(arena, attribute.segment)).toScalaString
    inline def symbolRefAttrGetLeafReference(
      using arena: Arena
    ) = StringRef(mlirSymbolRefAttrGetLeafReference(arena, attribute.segment)).toScalaString
    inline def symbolRefAttrGetNumNestedReferences: Long = mlirSymbolRefAttrGetNumNestedReferences(attribute.segment)
    inline def symbolRefAttrGetNestedReference(
      pos:         Long
    )(
      using arena: Arena
    ) = Attribute(mlirSymbolRefAttrGetNestedReference(arena, attribute.segment, pos))
    inline def distinctAttrCreate(
      using arena: Arena
    ) = Attribute(mlirDistinctAttrCreate(arena, attribute.segment))
    inline def disctinctAttrCreate(
      using arena: Arena
    ) = attribute.distinctAttrCreate
  // Flat SymbolRef
  extension (string:    String)
    inline def flatSymbolRefAttrGet(
      using arena: Arena,
      context:     Context
    ): Attribute = Attribute(mlirFlatSymbolRefAttrGet(arena, context.segment, string.toStringRef.segment))
  extension (attribute: Attribute)
    inline def isFlatSymbolRef: Boolean = mlirAttributeIsAFlatSymbolRef(attribute.segment)
    inline def flatSymbolRefAttrGetValue(
      using arena: Arena
    ): String = StringRef(mlirFlatSymbolRefAttrGetValue(arena, attribute.segment)).toScalaString
  // Type
  inline def typeAttrGetTypeID(
    using arena: Arena
  ) = TypeID(mlirTypeAttrGetTypeID(arena))
  extension (tpe:       Type)
    inline def typeAttrGet(
      using arena: Arena,
      context:     Context
    ): Attribute =
      Attribute(mlirTypeAttrGet(arena, tpe.segment))
  extension (attribute: Attribute)
    inline def isType: Boolean = mlirAttributeIsAType(attribute.segment)
    inline def typeAttrGetValue(
      using arena: Arena
    ): Type = Type(mlirTypeAttrGetValue(arena, attribute.segment))
  // Unit
  inline def unitAttrGetTypeID(
    using arena: Arena
  ) = TypeID(mlirUnitAttrGetTypeID(arena))
  inline def unitAttrGet(
    using arena: Arena,
    context:     Context
  ): Attribute = Attribute(mlirUnitAttrGet(arena, context.segment))
  extension (attribute: Attribute) inline def isUnit: Boolean = mlirAttributeIsAUnit(attribute.segment)

  // Dense array
  inline def denseArrayAttrGetTypeID(
    using arena: Arena
  ): TypeID = TypeID(mlirDenseArrayAttrGetTypeID(arena))
  extension (bools:     Seq[Boolean])
    inline def denseBoolArrayGet(
      using arena: Arena,
      context:     Context
    ): Attribute = Attribute(
      mlirDenseBoolArrayGet(arena, context.segment, bools.size, bools.map(i => if (i) 1 else 0).toMlirArray)
    )
  extension (ints:      Seq[Int])
    inline def denseI8ArrayGet(
      using arena: Arena,
      context:     Context
    ): Attribute = Attribute(mlirDenseI8ArrayGet(arena, context.segment, ints.size, ints.toMlirArray))
    inline def denseI16ArrayGet(
      using arena: Arena,
      context:     Context
    ): Attribute = Attribute(mlirDenseI16ArrayGet(arena, context.segment, ints.size, ints.toMlirArray))
    inline def denseI32ArrayGet(
      using arena: Arena,
      context:     Context
    ): Attribute = Attribute(mlirDenseI32ArrayGet(arena, context.segment, ints.size, ints.toMlirArray))
  extension (longs:     Seq[Long])
    inline def denseI64ArrayGet(
      using arena: Arena,
      context:     Context
    ): Attribute = Attribute(mlirDenseI64ArrayGet(arena, context.segment, longs.size, longs.toMlirArray))
  extension (floats:    Seq[Float])
    inline def denseF32ArrayGet(
      using arena: Arena,
      context:     Context
    ): Attribute = Attribute(mlirDenseF32ArrayGet(arena, context.segment, floats.size, floats.toMlirArray))
  extension (doubles:   Seq[Double])
    inline def denseF64ArrayGet(
      using arena: Arena,
      context:     Context
    ): Attribute = Attribute(mlirDenseF64ArrayGet(arena, context.segment, doubles.size, doubles.toMlirArray))
  extension (attribute: Attribute)
    inline def isDenseBoolArray:                    Boolean = mlirAttributeIsADenseBoolArray(attribute.segment)
    inline def isDenseI8Array:                      Boolean = mlirAttributeIsADenseI8Array(attribute.segment)
    inline def isDenseI16Array:                     Boolean = mlirAttributeIsADenseI16Array(attribute.segment)
    inline def isDenseI32Array:                     Boolean = mlirAttributeIsADenseI32Array(attribute.segment)
    inline def isDenseI64Array:                     Boolean = mlirAttributeIsADenseI64Array(attribute.segment)
    inline def isDenseF32Array:                     Boolean = mlirAttributeIsADenseF32Array(attribute.segment)
    inline def isDenseF64Array:                     Boolean = mlirAttributeIsADenseF64Array(attribute.segment)
    inline def denseArrayGetNumElements:            Long    = mlirDenseArrayGetNumElements(attribute.segment)
    inline def denseBoolArrayGetElement(pos: Long): Boolean = mlirDenseBoolArrayGetElement(attribute.segment, pos)
    inline def denseI8ArrayGetElement(pos:   Long): Int     = mlirDenseI8ArrayGetElement(attribute.segment, pos)
    inline def denseI16ArrayGetElement(pos:  Long): Int     = mlirDenseI16ArrayGetElement(attribute.segment, pos)
    inline def denseI32ArrayGetElement(pos:  Long): Int     = mlirDenseI32ArrayGetElement(attribute.segment, pos)
    inline def denseI64ArrayGetElement(pos:  Long): Long    = mlirDenseI64ArrayGetElement(attribute.segment, pos)
    inline def denseF32ArrayGetElement(pos:  Long): Float   = mlirDenseF32ArrayGetElement(attribute.segment, pos)
    inline def denseF64ArrayGetElement(pos:  Long): Double  = mlirDenseF64ArrayGetElement(attribute.segment, pos)

  extension (attribute: Attribute)
    inline def segment: MemorySegment = attribute._segment
    inline def sizeOf:  Int           = MlirAttribute.sizeof().toInt
end given

given NamedAttributeApi with
  inline def namedAttributeGet(
    identifier:  Identifier,
    attribute:   Attribute
  )(
    using arena: Arena
  ): NamedAttribute = NamedAttribute(mlirNamedAttributeGet(arena, identifier.segment, attribute.segment))

  extension (namedAttribute: NamedAttribute)
    inline def segment: MemorySegment = namedAttribute._segment
    inline def sizeOf:  Int           = MlirNamedAttribute.sizeof().toInt
end given
