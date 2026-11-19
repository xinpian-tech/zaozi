// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.ir

import org.llvm.mlir.raw.structs as MlirStructs
import org.llvm.mlir.raw.functions as mlirApi
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

import org.llvm.mlir.scalalib.capi.support.{*, given}

given AttributeApi with
  inline def allocateAttribute(
    using zone: Zone
  ): Attribute = Attribute(MlirStructs.MlirAttribute(null))
  inline def getNull(
    using zone: Zone,
    context:    Context
  ): Attribute = Attribute(nativeResult[MlirStructs.MlirAttribute](out => mlirApi.mlirAttributeGetNull()(out)))
  extension (attribute: Attribute)
    inline def getType(
      using zone: Zone
    ): Type = Type(nativeResult[MlirStructs.MlirType](out => mlirApi.mlirAttributeGetType(attribute.native)(out)))
  // Location
  extension (attribute: Attribute) inline def isLocation: Boolean = mlirApi.mlirAttributeIsALocation(attribute.native)
  // Array
  inline def arrayAttrGetTypeID(
    using zone: Zone
  ): TypeID = TypeID(nativeResult[MlirStructs.MlirTypeID](out => mlirApi.mlirArrayAttrGetTypeID()(out)))
  extension (array:     Seq[Attribute])
    inline def arrayAttrGet(
      using zone: Zone,
      context:    Context
    ): Attribute =
      Attribute(
        nativeResult[MlirStructs.MlirAttribute](out =>
          mlirApi.mlirArrayAttrGet(context.native, array.size.toSize, array.toMlirArray)(out)
        )
      )
  extension (attribute: Attribute)
    inline def isArray:                 Boolean = mlirApi.mlirAttributeIsAArray(attribute.native)
    inline def arrayAttrGetNumElements: Int     = mlirApi.mlirArrayAttrGetNumElements(attribute.native).toLong.toInt
    inline def arrayAttrGetElement(
      idx:        Int
    )(
      using zone: Zone
    ): Attribute = Attribute(
      nativeResult[MlirStructs.MlirAttribute](out =>
        mlirApi.mlirArrayAttrGetElement(attribute.native, idx.toLong.toSize)(out)
      )
    )
  // Dictionary
  inline def dictionaryAttrGetTypeID(
    using zone: Zone
  ) = TypeID(nativeResult[MlirStructs.MlirTypeID](out => mlirApi.mlirDictionaryAttrGetTypeID()(out)))
  extension (map:       Map[String, Attribute])
    inline def directoryAttrGet(
      using zone: Zone,
      context:    Context
    ): Attribute =
      Attribute(
        nativeResult[MlirStructs.MlirAttribute](out =>
          mlirApi.mlirDictionaryAttrGet(
            context.native,
            map.size.toSize,
            map
              .map:
                case (str, attr) =>
                  summon[NamedAttributeApi].namedAttributeGet(str.identifierGet, attr)
              .toSeq
              .toMlirArray
          )(out)
        )
      )
  extension (attribute: Attribute)
    inline def isDictionary:                 Boolean = mlirApi.mlirAttributeIsADictionary(attribute.native)
    inline def dictionaryAttrGetNumElements: Int     =
      mlirApi.mlirDictionaryAttrGetNumElements(attribute.native).toLong.toInt
    inline def dictionaryAttrGetElement(
      idx:        Int
    )(
      using zone: Zone
    ): NamedAttribute = NamedAttribute(
      nativeResult[MlirStructs.MlirNamedAttribute](out =>
        mlirApi.mlirDictionaryAttrGetElement(attribute.native, idx.toLong.toSize)(out)
      )
    )
  // Floating point
  inline def floatAttrGetTypeID(
    using zone: Zone
  ): TypeID = TypeID(nativeResult[MlirStructs.MlirTypeID](out => mlirApi.mlirFloatAttrGetTypeID()(out)))
  extension (double:    Double)
    inline def floatAttrDoubleGet(
      tpe:        Type
    )(
      using zone: Zone,
      context:    Context
    ): Attribute = Attribute(
      nativeResult[MlirStructs.MlirAttribute](out =>
        mlirApi.mlirFloatAttrDoubleGet(context.native, tpe.native, double)(out)
      )
    )
    inline def floatAttrDoubleGetChecked(
      tpe:        Type
    )(
      using zone: Zone,
      location:   Location
    ): Attribute = Attribute(
      nativeResult[MlirStructs.MlirAttribute](out =>
        mlirApi.mlirFloatAttrDoubleGetChecked(location.native, tpe.native, double)(out)
      )
    )

  extension (attribute: Attribute)
    inline def isFloat:                 Boolean = mlirApi.mlirAttributeIsAFloat(attribute.native)
    inline def floatAttrGetValueDouble: Double  = mlirApi.mlirFloatAttrGetValueDouble(attribute.native)
  // Integer
  inline def integerAttrGetTypeID(
    using zone: Zone
  ): TypeID = TypeID(nativeResult[MlirStructs.MlirTypeID](out => mlirApi.mlirIntegerAttrGetTypeID()(out)))
  extension (int:       Long)
    inline def integerAttrGet(
      tpe:        Type
    )(
      using zone: Zone,
      context:    Context
    ): Attribute = Attribute(
      nativeResult[MlirStructs.MlirAttribute](out => mlirApi.mlirIntegerAttrGet(tpe.native, int)(out))
    )
  extension (attribute: Attribute)
    inline def isInteger:               Boolean = mlirApi.mlirAttributeIsAInteger(attribute.native)
    inline def integerAttrGetValueInt:  Long    = mlirApi.mlirIntegerAttrGetValueInt(attribute.native).toLong
    inline def integerAttrGetValueSInt: Long    = mlirApi.mlirIntegerAttrGetValueSInt(attribute.native).toLong
    inline def integerAttrGetValueUInt: Long    = mlirApi.mlirIntegerAttrGetValueUInt(attribute.native).toLong
  // Bool
  extension (bool:      Boolean)
    inline def boolAttrGet(
      using zone: Zone,
      context:    Context
    ): Attribute = Attribute(
      nativeResult[MlirStructs.MlirAttribute](out => mlirApi.mlirBoolAttrGet(context.native, if (bool) 1 else 0)(out))
    )
  extension (attribute: Attribute)
    inline def isBool:           Boolean = mlirApi.mlirAttributeIsABool(attribute.native)
    inline def boolAttrGetValue: Boolean = mlirApi.mlirBoolAttrGetValue(attribute.native)
  // String
  def stringAttrGetTypeID(
    using zone: Zone
  ): TypeID = TypeID(nativeResult[MlirStructs.MlirTypeID](out => mlirApi.mlirStringAttrGetTypeID()(out)))
  extension (string:    String)
    inline def stringAttrGet(
      using zone: Zone,
      context:    Context
    ): Attribute = Attribute(
      nativeResult[MlirStructs.MlirAttribute](out =>
        mlirApi.mlirStringAttrGet(context.native, string.toStringRef.native)(out)
      )
    )
    inline def stringAttrTypedGet(
      tpe:        Type
    )(
      using zone: Zone
    ): Attribute = Attribute(
      nativeResult[MlirStructs.MlirAttribute](out =>
        mlirApi.mlirStringAttrTypedGet(tpe.native, string.toStringRef.native)(out)
      )
    )
  extension (attribute: Attribute)
    inline def isString: Boolean =
      mlirApi.mlirAttributeIsAString(attribute.native)
    inline def stringAttrGetValue(
      using zone: Zone
    ): String =
      StringRef(
        nativeResult[MlirStructs.MlirStringRef](out => mlirApi.mlirStringAttrGetValue(attribute.native)(out))
      ).toScalaString
  // SymbolRef
  inline def symbolRefAttrGetTypeID(
    using zone: Zone
  ): TypeID = TypeID(nativeResult[MlirStructs.MlirTypeID](out => mlirApi.mlirSymbolRefAttrGetTypeID()(out)))
  extension (symbol:    String)
    inline def symbolRefAttrGet(
      references: Seq[Attribute]
    )(
      using zone: Zone,
      context:    Context
    ): Attribute = Attribute(
      nativeResult[MlirStructs.MlirAttribute](out =>
        mlirApi.mlirSymbolRefAttrGet(
          context.native,
          symbol.toStringRef.native,
          references.size.toSize,
          references.toMlirArray
        )(out)
      )
    )
  extension (attribute: Attribute)
    inline def isSymbolRef = mlirApi.mlirAttributeIsASymbolRef(attribute.native)
    inline def symbolRefAttrGetRootReference(
      using zone: Zone
    ) = StringRef(
      nativeResult[MlirStructs.MlirStringRef](out => mlirApi.mlirSymbolRefAttrGetRootReference(attribute.native)(out))
    ).toScalaString
    inline def symbolRefAttrGetLeafReference(
      using zone: Zone
    ) = StringRef(
      nativeResult[MlirStructs.MlirStringRef](out => mlirApi.mlirSymbolRefAttrGetLeafReference(attribute.native)(out))
    ).toScalaString
    inline def symbolRefAttrGetNumNestedReferences: Long =
      mlirApi.mlirSymbolRefAttrGetNumNestedReferences(attribute.native).toLong
    inline def symbolRefAttrGetNestedReference(
      pos:        Long
    )(
      using zone: Zone
    ) = Attribute(
      nativeResult[MlirStructs.MlirAttribute](out =>
        mlirApi.mlirSymbolRefAttrGetNestedReference(attribute.native, pos.toSize)(out)
      )
    )
    inline def distinctAttrCreate(
      using zone: Zone
    ) = Attribute(nativeResult[MlirStructs.MlirAttribute](out => mlirApi.mlirDistinctAttrCreate(attribute.native)(out)))
    inline def disctinctAttrCreate(
      using zone: Zone
    ) = attribute.distinctAttrCreate
  // Flat SymbolRef
  extension (string:    String)
    inline def flatSymbolRefAttrGet(
      using zone: Zone,
      context:    Context
    ): Attribute = Attribute(
      nativeResult[MlirStructs.MlirAttribute](out =>
        mlirApi.mlirFlatSymbolRefAttrGet(context.native, string.toStringRef.native)(out)
      )
    )
  extension (attribute: Attribute)
    inline def isFlatSymbolRef: Boolean = mlirApi.mlirAttributeIsAFlatSymbolRef(attribute.native)
    inline def flatSymbolRefAttrGetValue(
      using zone: Zone
    ): String = StringRef(
      nativeResult[MlirStructs.MlirStringRef](out => mlirApi.mlirFlatSymbolRefAttrGetValue(attribute.native)(out))
    ).toScalaString
  // Type
  inline def typeAttrGetTypeID(
    using zone: Zone
  ) = TypeID(nativeResult[MlirStructs.MlirTypeID](out => mlirApi.mlirTypeAttrGetTypeID()(out)))
  extension (tpe:       Type)
    inline def typeAttrGet(
      using zone: Zone,
      context:    Context
    ): Attribute =
      Attribute(nativeResult[MlirStructs.MlirAttribute](out => mlirApi.mlirTypeAttrGet(tpe.native)(out)))
  extension (attribute: Attribute)
    inline def isType: Boolean = mlirApi.mlirAttributeIsAType(attribute.native)
    inline def typeAttrGetValue(
      using zone: Zone
    ): Type = Type(nativeResult[MlirStructs.MlirType](out => mlirApi.mlirTypeAttrGetValue(attribute.native)(out)))
  // Unit
  inline def unitAttrGetTypeID(
    using zone: Zone
  ) = TypeID(nativeResult[MlirStructs.MlirTypeID](out => mlirApi.mlirUnitAttrGetTypeID()(out)))
  inline def unitAttrGet(
    using zone: Zone,
    context:    Context
  ): Attribute = Attribute(nativeResult[MlirStructs.MlirAttribute](out => mlirApi.mlirUnitAttrGet(context.native)(out)))
  extension (attribute: Attribute) inline def isUnit: Boolean = mlirApi.mlirAttributeIsAUnit(attribute.native)

  // Dense array
  inline def denseArrayAttrGetTypeID(
    using zone: Zone
  ): TypeID = TypeID(nativeResult[MlirStructs.MlirTypeID](out => mlirApi.mlirDenseArrayAttrGetTypeID()(out)))
  extension (bools:     Seq[Boolean])
    inline def denseBoolArrayGet(
      using zone: Zone,
      context:    Context
    ): Attribute = Attribute(
      nativeResult[MlirStructs.MlirAttribute](out =>
        mlirApi.mlirDenseBoolArrayGet(
          context.native,
          bools.size.toSize,
          nativeArray(bools.map(i => if i then 1 else 0))
        )(out)
      )
    )
  extension (ints:      Seq[Int])
    inline def denseI8ArrayGet(
      using zone: Zone,
      context:    Context
    ): Attribute = Attribute(
      nativeResult[MlirStructs.MlirAttribute](out =>
        mlirApi.mlirDenseI8ArrayGet(context.native, ints.size.toSize, nativeArray(ints.map(v => v.toByte)))(out)
      )
    )
    inline def denseI16ArrayGet(
      using zone: Zone,
      context:    Context
    ): Attribute = Attribute(
      nativeResult[MlirStructs.MlirAttribute](out =>
        mlirApi.mlirDenseI16ArrayGet(context.native, ints.size.toSize, nativeArray(ints.map(v => v.toShort)))(out)
      )
    )
    inline def denseI32ArrayGet(
      using zone: Zone,
      context:    Context
    ): Attribute = Attribute(
      nativeResult[MlirStructs.MlirAttribute](out =>
        mlirApi.mlirDenseI32ArrayGet(context.native, ints.size.toSize, nativeArray(ints))(out)
      )
    )
  extension (longs:     Seq[Long])
    inline def denseI64ArrayGet(
      using zone: Zone,
      context:    Context
    ): Attribute = Attribute(
      nativeResult[MlirStructs.MlirAttribute](out =>
        mlirApi.mlirDenseI64ArrayGet(context.native, longs.size.toSize, nativeArray(longs))(out)
      )
    )
  extension (floats:    Seq[Float])
    inline def denseF32ArrayGet(
      using zone: Zone,
      context:    Context
    ): Attribute = Attribute(
      nativeResult[MlirStructs.MlirAttribute](out =>
        mlirApi.mlirDenseF32ArrayGet(context.native, floats.size.toSize, nativeArray(floats))(out)
      )
    )
  extension (doubles:   Seq[Double])
    inline def denseF64ArrayGet(
      using zone: Zone,
      context:    Context
    ): Attribute = Attribute(
      nativeResult[MlirStructs.MlirAttribute](out =>
        mlirApi.mlirDenseF64ArrayGet(context.native, doubles.size.toSize, nativeArray(doubles))(out)
      )
    )
  extension (attribute: Attribute)
    inline def isDenseBoolArray:                    Boolean = mlirApi.mlirAttributeIsADenseBoolArray(attribute.native)
    inline def isDenseI8Array:                      Boolean = mlirApi.mlirAttributeIsADenseI8Array(attribute.native)
    inline def isDenseI16Array:                     Boolean = mlirApi.mlirAttributeIsADenseI16Array(attribute.native)
    inline def isDenseI32Array:                     Boolean = mlirApi.mlirAttributeIsADenseI32Array(attribute.native)
    inline def isDenseI64Array:                     Boolean = mlirApi.mlirAttributeIsADenseI64Array(attribute.native)
    inline def isDenseF32Array:                     Boolean = mlirApi.mlirAttributeIsADenseF32Array(attribute.native)
    inline def isDenseF64Array:                     Boolean = mlirApi.mlirAttributeIsADenseF64Array(attribute.native)
    inline def denseArrayGetNumElements:            Long    = mlirApi.mlirDenseArrayGetNumElements(attribute.native).toLong
    inline def denseBoolArrayGetElement(pos: Long): Boolean =
      mlirApi.mlirDenseBoolArrayGetElement(attribute.native, pos.toSize)
    inline def denseI8ArrayGetElement(pos: Long):   Int     = mlirApi.mlirDenseI8ArrayGetElement(attribute.native, pos.toSize)
    inline def denseI16ArrayGetElement(pos: Long):  Int     =
      mlirApi.mlirDenseI16ArrayGetElement(attribute.native, pos.toSize)
    inline def denseI32ArrayGetElement(pos: Long):  Int     =
      mlirApi.mlirDenseI32ArrayGetElement(attribute.native, pos.toSize)
    inline def denseI64ArrayGetElement(pos: Long):  Long    =
      mlirApi.mlirDenseI64ArrayGetElement(attribute.native, pos.toSize).toLong
    inline def denseF32ArrayGetElement(pos: Long):  Float   =
      mlirApi.mlirDenseF32ArrayGetElement(attribute.native, pos.toSize)
    inline def denseF64ArrayGetElement(pos: Long):  Double  =
      mlirApi.mlirDenseF64ArrayGetElement(attribute.native, pos.toSize)

  extension (attribute: Attribute)

    inline def sizeOf: Int = sizeof[MlirStructs.MlirAttribute].toInt
end given

given NamedAttributeApi with
  inline def namedAttributeGet(
    identifier: Identifier,
    attribute:  Attribute
  )(
    using zone: Zone
  ): NamedAttribute = NamedAttribute(
    nativeResult[MlirStructs.MlirNamedAttribute](out =>
      mlirApi.mlirNamedAttributeGet(identifier.native, attribute.native)(out)
    )
  )

  extension (namedAttribute: NamedAttribute)

    inline def sizeOf: Int = sizeof[MlirStructs.MlirNamedAttribute].toInt
end given
