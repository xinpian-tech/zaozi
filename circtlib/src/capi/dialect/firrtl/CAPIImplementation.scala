// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.capi.dialect.firrtl

import org.llvm.circt.*
import org.llvm.circt.CAPI.{mlirGetDialectHandle__firrtl__ as _, *}
import org.llvm.mlir.scalalib.capi.support.{*, given}
import org.llvm.mlir.scalalib.capi.ir.{Context, Identifier, Type, given}

import java.lang.foreign.{Arena, MemorySegment}

given FirrtlBundleFieldApi with
  inline def createFirrtlBundleField(
    name:        String,
    isFlip:      Boolean,
    tpe:         Type
  )(
    using arena: Arena,
    context:     Context
  ): FirrtlBundleField =
    val buffer = FIRRTLBundleField.allocate(arena)
    FIRRTLBundleField.name(buffer).copyFrom(name.identifierGet.segment)
    FIRRTLBundleField.isFlip(buffer, isFlip)
    FIRRTLBundleField.`type`(buffer).copyFrom(tpe.segment)
    FirrtlBundleField(buffer)
  extension (firrtlBundleField: FirrtlBundleField)
    inline def getName(
      using arena: Arena
    ): String = Identifier(FIRRTLBundleField.name(firrtlBundleField.segment)).str
    inline def getIsFlip: Boolean = FIRRTLBundleField.isFlip(firrtlBundleField.segment)
    inline def getType:   Type    = Type(FIRRTLBundleField.`type`(firrtlBundleField.segment))

    inline def segment: MemorySegment = firrtlBundleField._segment
    inline def sizeOf:  Int           = FIRRTLBundleField.sizeof().toInt
end given

given FirrtlClassElementApi with
  inline def createFirrtlClassElement(
    name:        String,
    direction:   FirrtlDirection,
    tpe:         Type
  )(
    using arena: Arena,
    context:     Context
  ): FirrtlClassElement =
    val buffer = FIRRTLClassElement.allocate(arena)
    FIRRTLClassElement.name(buffer).copyFrom(name.identifierGet.segment)
    FIRRTLClassElement.direction(buffer, direction.toNative)
    FIRRTLClassElement.`type`(buffer).copyFrom(tpe.segment)
    FirrtlClassElement(buffer)
  extension (firrtlClassElement: FirrtlClassElement)
    inline def segment: MemorySegment = firrtlClassElement._segment
    inline def sizeOf:  Int           = FIRRTLClassElement.sizeof().toInt
end given

given FirrtlEventControlApi with
  extension (int: Int)
    override inline def fromNative: FirrtlEventControl = int match
      case i if i == FIRRTL_EVENT_CONTROL_AT_POS_EDGE() => FirrtlEventControl.AtPosEdge
      case i if i == FIRRTL_EVENT_CONTROL_AT_NEG_EDGE() => FirrtlEventControl.AtNegEdge
      case i if i == FIRRTL_EVENT_CONTROL_AT_EDGE()     => FirrtlEventControl.AtEdge
  extension (ref: FirrtlEventControl)
    inline def toNative: Int =
      ref match
        case FirrtlEventControl.AtPosEdge => FIRRTL_EVENT_CONTROL_AT_POS_EDGE()
        case FirrtlEventControl.AtNegEdge => FIRRTL_EVENT_CONTROL_AT_NEG_EDGE()
        case FirrtlEventControl.AtEdge    => FIRRTL_EVENT_CONTROL_AT_EDGE()
    inline def sizeOf:   Int = 4

given FirrtlConventionApi with
  extension (int: Int)
    override inline def fromNative: FirrtlConvention = int match
      case i if i == FIRRTL_CONVENTION_INTERNAL()   => FirrtlConvention.Internal
      case i if i == FIRRTL_CONVENTION_SCALARIZED() => FirrtlConvention.Scalarized
  extension (ref: FirrtlConvention)
    inline def toNative: Int =
      ref match
        case FirrtlConvention.Internal   => FIRRTL_CONVENTION_INTERNAL()
        case FirrtlConvention.Scalarized => FIRRTL_CONVENTION_SCALARIZED()
    inline def sizeOf:   Int = 4
end given

given FirrtlLayerConventionApi with
  extension (int: Int)
    override inline def fromNative: FirrtlLayerConvention = int match
      case i if i == FIRRTL_LAYER_CONVENTION_BIND()   => FirrtlLayerConvention.Bind
      case i if i == FIRRTL_LAYER_CONVENTION_INLINE() => FirrtlLayerConvention.Inline
  extension (ref: FirrtlLayerConvention)
    inline def toNative: Int =
      ref match
        case FirrtlLayerConvention.Bind   => FIRRTL_LAYER_CONVENTION_BIND()
        case FirrtlLayerConvention.Inline => FIRRTL_LAYER_CONVENTION_INLINE()
    inline def sizeOf:   Int = 4
end given

given FirrtlNameKindApi with
  extension (int: Int)
    override inline def fromNative: FirrtlNameKind = int match
      case i if i == FIRRTL_NAME_KIND_DROPPABLE_NAME()   => FirrtlNameKind.Droppable
      case i if i == FIRRTL_NAME_KIND_INTERESTING_NAME() => FirrtlNameKind.Interesting
  extension (ref: FirrtlNameKind)
    inline def toNative: Int =
      ref match
        case FirrtlNameKind.Droppable   => FIRRTL_NAME_KIND_DROPPABLE_NAME()
        case FirrtlNameKind.Interesting => FIRRTL_NAME_KIND_INTERESTING_NAME()
    inline def sizeOf:   Int = 4
end given

given FirrtlDirectionApi with
  extension (int: Int)
    override inline def fromNative: FirrtlDirection = int match
      case i if i == FIRRTL_DIRECTION_IN()  => FirrtlDirection.In
      case i if i == FIRRTL_DIRECTION_OUT() => FirrtlDirection.Out
  extension (ref: FirrtlDirection)
    inline def toNative: Int =
      ref match
        case FirrtlDirection.In  => FIRRTL_DIRECTION_IN()
        case FirrtlDirection.Out => FIRRTL_DIRECTION_OUT()
    inline def sizeOf:   Int = 4
end given

given FirrtlRUWApi with
  extension (int: Int)
    override inline def fromNative: FirrtlRUW = int match
      case i if i == FIRRTL_RUW_UNDEFINED() => FirrtlRUW.Undefined
      case i if i == FIRRTL_RUW_OLD()       => FirrtlRUW.Old
      case i if i == FIRRTL_RUW_NEW()       => FirrtlRUW.New
  extension (ref: FirrtlRUW)
    inline def toNative: Int = ref match
      case FirrtlRUW.Undefined => FIRRTL_RUW_UNDEFINED()
      case FirrtlRUW.Old       => FIRRTL_RUW_OLD()
      case FirrtlRUW.New       => FIRRTL_RUW_NEW()
    inline def sizeOf:   Int = 4
end given

given FirrtlMemDirApi with
  extension (int: Int)
    override inline def fromNative: FirrtlMemDir = int match
      case i if i == FIRRTL_MEM_DIR_INFER()      => FirrtlMemDir.Infer
      case i if i == FIRRTL_MEM_DIR_READ()       => FirrtlMemDir.Read
      case i if i == FIRRTL_MEM_DIR_WRITE()      => FirrtlMemDir.Write
      case i if i == FIRRTL_MEM_DIR_READ_WRITE() => FirrtlMemDir.ReadWrite
  extension (ref: FirrtlMemDir)
    inline def toNative: Int = ref match
      case FirrtlMemDir.Infer     => FIRRTL_MEM_DIR_INFER()
      case FirrtlMemDir.Read      => FIRRTL_MEM_DIR_READ()
      case FirrtlMemDir.Write     => FIRRTL_MEM_DIR_WRITE()
      case FirrtlMemDir.ReadWrite => FIRRTL_MEM_DIR_READ_WRITE()
    inline def sizeOf:   Int = 4
end given

given FirrtlValueFlowApi with
  extension (int: Int)
    override inline def fromNative: FirrtlValueFlow = int match
      case i if i == FIRRTL_VALUE_FLOW_NONE()   => FirrtlValueFlow.None
      case i if i == FIRRTL_VALUE_FLOW_SOURCE() => FirrtlValueFlow.Source
      case i if i == FIRRTL_VALUE_FLOW_SINK()   => FirrtlValueFlow.Sink
      case i if i == FIRRTL_VALUE_FLOW_DUPLEX() => FirrtlValueFlow.Duplex
  extension (ref: FirrtlValueFlow)
    inline def toNative: Int =
      ref match
        case FirrtlValueFlow.None   => FIRRTL_VALUE_FLOW_NONE()
        case FirrtlValueFlow.Source => FIRRTL_VALUE_FLOW_SOURCE()
        case FirrtlValueFlow.Sink   => FIRRTL_VALUE_FLOW_SINK()
        case FirrtlValueFlow.Duplex => FIRRTL_VALUE_FLOW_DUPLEX()
    inline def sizeOf:   Int = 4
end given
