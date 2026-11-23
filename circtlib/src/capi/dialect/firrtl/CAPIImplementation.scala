// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.capi.dialect.firrtl

import org.llvm.circt.raw.structs as CirctStructs
import org.llvm.circt.raw.enumerations as CirctEnums
import scala.scalanative.unsafe.*

import org.llvm.mlir.scalalib.capi.support.{*, given}
import org.llvm.mlir.scalalib.capi.ir.{Context, Identifier, Type, given}

import org.llvm.circt.raw.structs.{FIRRTLBundleField, FIRRTLClassElement}
import org.llvm.circt.raw.enumerations.FIRRTLDirection
import org.llvm.mlir.scalalib.capi.support.nativeCopy

given FirrtlBundleFieldApi with
  inline def createFirrtlBundleField(
    name:       String,
    isFlip:     Boolean,
    tpe:        Type
  )(
    using zone: Zone,
    context:    Context
  ): FirrtlBundleField =
    FirrtlBundleField(FIRRTLBundleField(!name.identifierGet.native, isFlip, !tpe.native))
  extension (firrtlBundleField: FirrtlBundleField)
    inline def getName(
      using zone: Zone
    ): String = Identifier(nativeCopy((!firrtlBundleField.native).name)).str
    inline def getIsFlip: Boolean = (!firrtlBundleField.native).isFlip
    inline def getType(
      using Zone
    ): Type = Type(nativeCopy((!firrtlBundleField.native).`type`))

    inline def sizeOf: Int = sizeof[CirctStructs.FIRRTLBundleField].toInt
end given

given FirrtlClassElementApi with
  inline def createFirrtlClassElement(
    name:       String,
    direction:  FirrtlDirection,
    tpe:        Type
  )(
    using zone: Zone,
    context:    Context
  ): FirrtlClassElement =
    FirrtlClassElement(
      FIRRTLClassElement(!name.identifierGet.native, !tpe.native, FIRRTLDirection.define(direction.toNative))
    )
  extension (firrtlClassElement: FirrtlClassElement)

    inline def sizeOf: Int = sizeof[CirctStructs.FIRRTLClassElement].toInt
end given

given FirrtlEventControlApi with
  extension (int: Int)
    override inline def fromNative: FirrtlEventControl = int match
      case i if i == CirctEnums.FIRRTLEventControl.FIRRTL_EVENT_CONTROL_AT_POS_EDGE.int => FirrtlEventControl.AtPosEdge
      case i if i == CirctEnums.FIRRTLEventControl.FIRRTL_EVENT_CONTROL_AT_NEG_EDGE.int => FirrtlEventControl.AtNegEdge
  extension (ref: FirrtlEventControl)
    inline def toNative: Int =
      ref match
        case FirrtlEventControl.AtPosEdge => CirctEnums.FIRRTLEventControl.FIRRTL_EVENT_CONTROL_AT_POS_EDGE.int
        case FirrtlEventControl.AtNegEdge => CirctEnums.FIRRTLEventControl.FIRRTL_EVENT_CONTROL_AT_NEG_EDGE.int
    inline def sizeOf:   Int = 4

given FirrtlConventionApi with
  extension (int: Int)
    override inline def fromNative: FirrtlConvention = int match
      case i if i == CirctEnums.FIRRTLConvention.FIRRTL_CONVENTION_INTERNAL.int   => FirrtlConvention.Internal
      case i if i == CirctEnums.FIRRTLConvention.FIRRTL_CONVENTION_SCALARIZED.int => FirrtlConvention.Scalarized
  extension (ref: FirrtlConvention)
    inline def toNative: Int =
      ref match
        case FirrtlConvention.Internal   => CirctEnums.FIRRTLConvention.FIRRTL_CONVENTION_INTERNAL.int
        case FirrtlConvention.Scalarized => CirctEnums.FIRRTLConvention.FIRRTL_CONVENTION_SCALARIZED.int
    inline def sizeOf:   Int = 4
end given

given FirrtlLayerConventionApi with
  extension (int: Int)
    override inline def fromNative: FirrtlLayerConvention = int match
      case i if i == CirctEnums.FIRRTLLayerConvention.FIRRTL_LAYER_CONVENTION_BIND.int   => FirrtlLayerConvention.Bind
      case i if i == CirctEnums.FIRRTLLayerConvention.FIRRTL_LAYER_CONVENTION_INLINE.int => FirrtlLayerConvention.Inline
  extension (ref: FirrtlLayerConvention)
    inline def toNative: Int =
      ref match
        case FirrtlLayerConvention.Bind   => CirctEnums.FIRRTLLayerConvention.FIRRTL_LAYER_CONVENTION_BIND.int
        case FirrtlLayerConvention.Inline => CirctEnums.FIRRTLLayerConvention.FIRRTL_LAYER_CONVENTION_INLINE.int
    inline def sizeOf:   Int = 4
end given

given FirrtlNameKindApi with
  extension (int: Int)
    override inline def fromNative: FirrtlNameKind = int match
      case i if i == CirctEnums.FIRRTLNameKind.FIRRTL_NAME_KIND_DROPPABLE_NAME.int   => FirrtlNameKind.Droppable
      case i if i == CirctEnums.FIRRTLNameKind.FIRRTL_NAME_KIND_INTERESTING_NAME.int => FirrtlNameKind.Interesting
  extension (ref: FirrtlNameKind)
    inline def toNative: Int =
      ref match
        case FirrtlNameKind.Droppable   => CirctEnums.FIRRTLNameKind.FIRRTL_NAME_KIND_DROPPABLE_NAME.int
        case FirrtlNameKind.Interesting => CirctEnums.FIRRTLNameKind.FIRRTL_NAME_KIND_INTERESTING_NAME.int
    inline def sizeOf:   Int = 4
end given

given FirrtlDirectionApi with
  extension (int: Int)
    override inline def fromNative: FirrtlDirection = int match
      case i if i == CirctEnums.FIRRTLDirection.FIRRTL_DIRECTION_IN.int  => FirrtlDirection.In
      case i if i == CirctEnums.FIRRTLDirection.FIRRTL_DIRECTION_OUT.int => FirrtlDirection.Out
  extension (ref: FirrtlDirection)
    inline def toNative: Int =
      ref match
        case FirrtlDirection.In  => CirctEnums.FIRRTLDirection.FIRRTL_DIRECTION_IN.int
        case FirrtlDirection.Out => CirctEnums.FIRRTLDirection.FIRRTL_DIRECTION_OUT.int
    inline def sizeOf:   Int = 4
end given

given FirrtlRUWApi with
  extension (int: Int)
    override inline def fromNative: FirrtlRUW = int match
      case i if i == CirctEnums.FIRRTLRUW.FIRRTL_RUW_UNDEFINED.int => FirrtlRUW.Undefined
      case i if i == CirctEnums.FIRRTLRUW.FIRRTL_RUW_OLD.int       => FirrtlRUW.Old
      case i if i == CirctEnums.FIRRTLRUW.FIRRTL_RUW_NEW.int       => FirrtlRUW.New
  extension (ref: FirrtlRUW)
    inline def toNative: Int = ref match
      case FirrtlRUW.Undefined => CirctEnums.FIRRTLRUW.FIRRTL_RUW_UNDEFINED.int
      case FirrtlRUW.Old       => CirctEnums.FIRRTLRUW.FIRRTL_RUW_OLD.int
      case FirrtlRUW.New       => CirctEnums.FIRRTLRUW.FIRRTL_RUW_NEW.int
    inline def sizeOf:   Int = 4
end given

given FirrtlMemDirApi with
  extension (int: Int)
    override inline def fromNative: FirrtlMemDir = int match
      case i if i == CirctEnums.FIRRTLMemDir.FIRRTL_MEM_DIR_INFER.int      => FirrtlMemDir.Infer
      case i if i == CirctEnums.FIRRTLMemDir.FIRRTL_MEM_DIR_READ.int       => FirrtlMemDir.Read
      case i if i == CirctEnums.FIRRTLMemDir.FIRRTL_MEM_DIR_WRITE.int      => FirrtlMemDir.Write
      case i if i == CirctEnums.FIRRTLMemDir.FIRRTL_MEM_DIR_READ_WRITE.int => FirrtlMemDir.ReadWrite
  extension (ref: FirrtlMemDir)
    inline def toNative: Int = ref match
      case FirrtlMemDir.Infer     => CirctEnums.FIRRTLMemDir.FIRRTL_MEM_DIR_INFER.int
      case FirrtlMemDir.Read      => CirctEnums.FIRRTLMemDir.FIRRTL_MEM_DIR_READ.int
      case FirrtlMemDir.Write     => CirctEnums.FIRRTLMemDir.FIRRTL_MEM_DIR_WRITE.int
      case FirrtlMemDir.ReadWrite => CirctEnums.FIRRTLMemDir.FIRRTL_MEM_DIR_READ_WRITE.int
    inline def sizeOf:   Int = 4
end given

given FirrtlValueFlowApi with
  extension (int: Int)
    override inline def fromNative: FirrtlValueFlow = int match
      case i if i == CirctEnums.FIRRTLValueFlow.FIRRTL_VALUE_FLOW_NONE.int   => FirrtlValueFlow.None
      case i if i == CirctEnums.FIRRTLValueFlow.FIRRTL_VALUE_FLOW_SOURCE.int => FirrtlValueFlow.Source
      case i if i == CirctEnums.FIRRTLValueFlow.FIRRTL_VALUE_FLOW_SINK.int   => FirrtlValueFlow.Sink
      case i if i == CirctEnums.FIRRTLValueFlow.FIRRTL_VALUE_FLOW_DUPLEX.int => FirrtlValueFlow.Duplex
  extension (ref: FirrtlValueFlow)
    inline def toNative: Int =
      ref match
        case FirrtlValueFlow.None   => CirctEnums.FIRRTLValueFlow.FIRRTL_VALUE_FLOW_NONE.int
        case FirrtlValueFlow.Source => CirctEnums.FIRRTLValueFlow.FIRRTL_VALUE_FLOW_SOURCE.int
        case FirrtlValueFlow.Sink   => CirctEnums.FIRRTLValueFlow.FIRRTL_VALUE_FLOW_SINK.int
        case FirrtlValueFlow.Duplex => CirctEnums.FIRRTLValueFlow.FIRRTL_VALUE_FLOW_DUPLEX.int
    inline def sizeOf:   Int = 4
end given
