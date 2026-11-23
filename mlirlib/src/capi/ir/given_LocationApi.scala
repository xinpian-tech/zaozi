// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.mlir.scalalib.capi.ir

import org.llvm.mlir.raw.structs as MlirStructs
import org.llvm.mlir.raw.functions as mlirApi
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

import org.llvm.mlir.scalalib.capi.support.{*, given}

given LocationApi with
  inline def locationFromAttribute(
    attribute:  Attribute
  )(
    using zone: Zone
  ): Location = Location(
    nativeResult[MlirStructs.MlirLocation](out => mlirApi.mlirLocationFromAttribute(attribute.native)(out))
  )
  inline def locationFileLineColGet(
    filename:   String,
    line:       Int,
    col:        Int
  )(
    using zone: Zone,
    context:    Context
  ): Location =
    Location(
      nativeResult[MlirStructs.MlirLocation](out =>
        mlirApi.mlirLocationFileLineColGet(context.native, filename.toStringRef.native, line.toUInt, col.toUInt)(out)
      )
    )
  inline def locationCallSiteGet(
    callee:     Location,
    caller:     Location
  )(
    using zone: Zone,
    context:    Context
  ): Location = Location(
    nativeResult[MlirStructs.MlirLocation](out => mlirApi.mlirLocationCallSiteGet(callee.native, caller.native)(out))
  )
  inline def locationFusedGet(
    locations:  Seq[Location],
    metadata:   Attribute
  )(
    using zone: Zone,
    context:    Context
  ): Location =
    Location(
      nativeResult[MlirStructs.MlirLocation](out =>
        mlirApi.mlirLocationFusedGet(context.native, locations.size.toSize, locations.toMlirArray, metadata.native)(out)
      )
    )
  inline def locationNameGet(
    name:       String,
    childLoc:   Location
  )(
    using zone: Zone,
    context:    Context
  ): Location = Location(
    nativeResult[MlirStructs.MlirLocation](out =>
      mlirApi.mlirLocationNameGet(context.native, name.toStringRef.native, childLoc.native)(out)
    )
  )
  inline def locationUnknownGet(
    using zone: Zone,
    context:    Context
  ): Location = Location(
    nativeResult[MlirStructs.MlirLocation](out => mlirApi.mlirLocationUnknownGet(context.native)(out))
  )
  extension (location: Location)
    inline def getAttribute(
      using zone: Zone
    ): Attribute =
      Attribute(nativeResult[MlirStructs.MlirAttribute](out => mlirApi.mlirLocationGetAttribute(location.native)(out)))
    inline def getContext(
      using zone: Zone
    ): Context = Context(
      nativeResult[MlirStructs.MlirContext](out => mlirApi.mlirLocationGetContext(location.native)(out))
    )
    inline def print(
      callback:   String => Unit
    )(
      using zone: Zone
    ): Unit =
      // The opaque data is meaningless here since Scala supports function currying
      callback.stringToStringCallback.use { (fn, data) => mlirApi.mlirLocationPrint(location.native, fn, data) }

    inline def sizeOf: Int = sizeof[MlirStructs.MlirLocation].toInt
end given
