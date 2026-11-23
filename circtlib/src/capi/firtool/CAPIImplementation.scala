// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.capi.firtool

import org.llvm.circt.raw.structs as CirctStructs
import org.llvm.circt.raw.enumerations as CirctEnums
import scala.scalanative.unsafe.*

given CirctFirtoolPreserveAggregateModeApi with
  extension (int: Int)
    override inline def fromNative: CirctFirtoolPreserveAggregateMode = int match
      case i if i == CirctEnums.CirctFirtoolPreserveAggregateMode.CIRCT_FIRTOOL_PRESERVE_AGGREGATE_MODE_NONE.int =>
        CirctFirtoolPreserveAggregateMode.None
      case i
          if i == CirctEnums.CirctFirtoolPreserveAggregateMode.CIRCT_FIRTOOL_PRESERVE_AGGREGATE_MODE_ONE_DIM_VEC.int =>
        CirctFirtoolPreserveAggregateMode.OneDimVec
      case i if i == CirctEnums.CirctFirtoolPreserveAggregateMode.CIRCT_FIRTOOL_PRESERVE_AGGREGATE_MODE_VEC.int  =>
        CirctFirtoolPreserveAggregateMode.Vec
      case i if i == CirctEnums.CirctFirtoolPreserveAggregateMode.CIRCT_FIRTOOL_PRESERVE_AGGREGATE_MODE_ALL.int  =>
        CirctFirtoolPreserveAggregateMode.All
  extension (ref: CirctFirtoolPreserveAggregateMode)
    inline def toNative: Int = ref match
      case CirctFirtoolPreserveAggregateMode.None      =>
        CirctEnums.CirctFirtoolPreserveAggregateMode.CIRCT_FIRTOOL_PRESERVE_AGGREGATE_MODE_NONE.int
      case CirctFirtoolPreserveAggregateMode.OneDimVec =>
        CirctEnums.CirctFirtoolPreserveAggregateMode.CIRCT_FIRTOOL_PRESERVE_AGGREGATE_MODE_ONE_DIM_VEC.int
      case CirctFirtoolPreserveAggregateMode.Vec       =>
        CirctEnums.CirctFirtoolPreserveAggregateMode.CIRCT_FIRTOOL_PRESERVE_AGGREGATE_MODE_VEC.int
      case CirctFirtoolPreserveAggregateMode.All       =>
        CirctEnums.CirctFirtoolPreserveAggregateMode.CIRCT_FIRTOOL_PRESERVE_AGGREGATE_MODE_ALL.int
    inline def sizeOf:   Int = 4
end given

given CirctFirtoolPreserveValuesModeApi with
  extension (int: Int)
    override inline def fromNative: CirctFirtoolPreserveValuesMode = int match
      case i if i == CirctEnums.CirctFirtoolPreserveValuesMode.CIRCT_FIRTOOL_PRESERVE_VALUES_MODE_STRIP.int =>
        CirctFirtoolPreserveValuesMode.Strip
      case i if i == CirctEnums.CirctFirtoolPreserveValuesMode.CIRCT_FIRTOOL_PRESERVE_VALUES_MODE_NONE.int  =>
        CirctFirtoolPreserveValuesMode.None
      case i if i == CirctEnums.CirctFirtoolPreserveValuesMode.CIRCT_FIRTOOL_PRESERVE_VALUES_MODE_NAMED.int =>
        CirctFirtoolPreserveValuesMode.Named
      case i if i == CirctEnums.CirctFirtoolPreserveValuesMode.CIRCT_FIRTOOL_PRESERVE_VALUES_MODE_ALL.int   =>
        CirctFirtoolPreserveValuesMode.All
  extension (ref: CirctFirtoolPreserveValuesMode)
    inline def toNative: Int = ref match
      case CirctFirtoolPreserveValuesMode.Strip =>
        CirctEnums.CirctFirtoolPreserveValuesMode.CIRCT_FIRTOOL_PRESERVE_VALUES_MODE_STRIP.int
      case CirctFirtoolPreserveValuesMode.None  =>
        CirctEnums.CirctFirtoolPreserveValuesMode.CIRCT_FIRTOOL_PRESERVE_VALUES_MODE_NONE.int
      case CirctFirtoolPreserveValuesMode.Named =>
        CirctEnums.CirctFirtoolPreserveValuesMode.CIRCT_FIRTOOL_PRESERVE_VALUES_MODE_NAMED.int
      case CirctFirtoolPreserveValuesMode.All   =>
        CirctEnums.CirctFirtoolPreserveValuesMode.CIRCT_FIRTOOL_PRESERVE_VALUES_MODE_ALL.int
    inline def sizeOf:   Int = 4
end given

given CirctFirtoolBuildModeApi with
  extension (int: Int)
    override inline def fromNative: CirctFirtoolBuildMode = int match
      case i if i == CirctEnums.CirctFirtoolBuildMode.CIRCT_FIRTOOL_BUILD_MODE_DEFAULT.int =>
        CirctFirtoolBuildMode.Default
      case i if i == CirctEnums.CirctFirtoolBuildMode.CIRCT_FIRTOOL_BUILD_MODE_DEBUG.int   => CirctFirtoolBuildMode.Debug
      case i if i == CirctEnums.CirctFirtoolBuildMode.CIRCT_FIRTOOL_BUILD_MODE_RELEASE.int =>
        CirctFirtoolBuildMode.Release
  extension (ref: CirctFirtoolBuildMode)
    inline def toNative: Int = ref match
      case CirctFirtoolBuildMode.Default => CirctEnums.CirctFirtoolBuildMode.CIRCT_FIRTOOL_BUILD_MODE_DEFAULT.int
      case CirctFirtoolBuildMode.Debug   => CirctEnums.CirctFirtoolBuildMode.CIRCT_FIRTOOL_BUILD_MODE_DEBUG.int
      case CirctFirtoolBuildMode.Release => CirctEnums.CirctFirtoolBuildMode.CIRCT_FIRTOOL_BUILD_MODE_RELEASE.int
    inline def sizeOf:   Int = 4
end given

given CirctFirtoolRandomKindApi with
  extension (int: Int)
    override inline def fromNative: CirctFirtoolRandomKind = int match
      case i if i == CirctEnums.CirctFirtoolRandomKind.CIRCT_FIRTOOL_RANDOM_KIND_NONE.int => CirctFirtoolRandomKind.None
      case i if i == CirctEnums.CirctFirtoolRandomKind.CIRCT_FIRTOOL_RANDOM_KIND_MEM.int  => CirctFirtoolRandomKind.Mem
      case i if i == CirctEnums.CirctFirtoolRandomKind.CIRCT_FIRTOOL_RANDOM_KIND_REG.int  => CirctFirtoolRandomKind.Reg
      case i if i == CirctEnums.CirctFirtoolRandomKind.CIRCT_FIRTOOL_RANDOM_KIND_ALL.int  => CirctFirtoolRandomKind.All
  extension (ref: CirctFirtoolRandomKind)
    inline def toNative: Int = ref match
      case CirctFirtoolRandomKind.None => CirctEnums.CirctFirtoolRandomKind.CIRCT_FIRTOOL_RANDOM_KIND_NONE.int
      case CirctFirtoolRandomKind.Mem  => CirctEnums.CirctFirtoolRandomKind.CIRCT_FIRTOOL_RANDOM_KIND_MEM.int
      case CirctFirtoolRandomKind.Reg  => CirctEnums.CirctFirtoolRandomKind.CIRCT_FIRTOOL_RANDOM_KIND_REG.int
      case CirctFirtoolRandomKind.All  => CirctEnums.CirctFirtoolRandomKind.CIRCT_FIRTOOL_RANDOM_KIND_ALL.int
    inline def sizeOf:   Int = 4
end given

given CirctFirtoolCompanionModeApi with
  extension (int: Int)
    override inline def fromNative: CirctFirtoolCompanionMode = int match
      case i if i == CirctEnums.CirctFirtoolCompanionMode.CIRCT_FIRTOOL_COMPANION_MODE_BIND.int        =>
        CirctFirtoolCompanionMode.Bind
      case i if i == CirctEnums.CirctFirtoolCompanionMode.CIRCT_FIRTOOL_COMPANION_MODE_INSTANTIATE.int =>
        CirctFirtoolCompanionMode.Instantiate
      case i if i == CirctEnums.CirctFirtoolCompanionMode.CIRCT_FIRTOOL_COMPANION_MODE_DROP.int        =>
        CirctFirtoolCompanionMode.Drop
  extension (ref: CirctFirtoolCompanionMode)
    inline def toNative: Int = ref match
      case CirctFirtoolCompanionMode.Bind        => CirctEnums.CirctFirtoolCompanionMode.CIRCT_FIRTOOL_COMPANION_MODE_BIND.int
      case CirctFirtoolCompanionMode.Instantiate =>
        CirctEnums.CirctFirtoolCompanionMode.CIRCT_FIRTOOL_COMPANION_MODE_INSTANTIATE.int
      case CirctFirtoolCompanionMode.Drop        => CirctEnums.CirctFirtoolCompanionMode.CIRCT_FIRTOOL_COMPANION_MODE_DROP.int
    inline def sizeOf:   Int = 4
end given

given CirctFirtoolVerificationFlavorApi with
  extension (int: Int)
    override inline def fromNative: CirctFirtoolVerificationFlavor = int match
      case i if i == CirctEnums.CirctFirtoolVerificationFlavor.CIRCT_FIRTOOL_VERIFICATION_FLAVOR_NONE.int          =>
        CirctFirtoolVerificationFlavor.None
      case i if i == CirctEnums.CirctFirtoolVerificationFlavor.CIRCT_FIRTOOL_VERIFICATION_FLAVOR_IF_ELSE_FATAL.int =>
        CirctFirtoolVerificationFlavor.IfElseFatal
      case i if i == CirctEnums.CirctFirtoolVerificationFlavor.CIRCT_FIRTOOL_VERIFICATION_FLAVOR_IMMEDIATE.int     =>
        CirctFirtoolVerificationFlavor.Immediate
      case i if i == CirctEnums.CirctFirtoolVerificationFlavor.CIRCT_FIRTOOL_VERIFICATION_FLAVOR_SVA.int           =>
        CirctFirtoolVerificationFlavor.Sva
  extension (ref: CirctFirtoolVerificationFlavor)
    inline def toNative: Int = ref match
      case CirctFirtoolVerificationFlavor.None        =>
        CirctEnums.CirctFirtoolVerificationFlavor.CIRCT_FIRTOOL_VERIFICATION_FLAVOR_NONE.int
      case CirctFirtoolVerificationFlavor.IfElseFatal =>
        CirctEnums.CirctFirtoolVerificationFlavor.CIRCT_FIRTOOL_VERIFICATION_FLAVOR_IF_ELSE_FATAL.int
      case CirctFirtoolVerificationFlavor.Immediate   =>
        CirctEnums.CirctFirtoolVerificationFlavor.CIRCT_FIRTOOL_VERIFICATION_FLAVOR_IMMEDIATE.int
      case CirctFirtoolVerificationFlavor.Sva         =>
        CirctEnums.CirctFirtoolVerificationFlavor.CIRCT_FIRTOOL_VERIFICATION_FLAVOR_SVA.int
    inline def sizeOf:   Int = 4
end given

given FirtoolOptionsApi with
  extension (ref: FirtoolOptions)

    inline def sizeOf: Int = sizeof[CirctStructs.CirctFirtoolFirtoolOptions].toInt
end given
