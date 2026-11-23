// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.capi.firtool

import org.llvm.circt.raw.structs as CirctStructs
import scala.scalanative.unsafe.*

import org.llvm.mlir.scalalib.*
import org.llvm.mlir.scalalib.capi.support.{*, given}
import org.llvm.mlir.scalalib.capi.pass.PassManager

enum CirctFirtoolPreserveAggregateMode:
  case None
  case OneDimVec
  case Vec
  case All
end CirctFirtoolPreserveAggregateMode

trait CirctFirtoolPreserveAggregateModeApi
    extends HasSizeOf[CirctFirtoolPreserveAggregateMode]
    with EnumHasToNative[CirctFirtoolPreserveAggregateMode]

enum CirctFirtoolPreserveValuesMode:
  case Strip
  case None
  case Named
  case All
end CirctFirtoolPreserveValuesMode
trait CirctFirtoolPreserveValuesModeApi
    extends HasSizeOf[CirctFirtoolPreserveValuesMode]
    with EnumHasToNative[CirctFirtoolPreserveValuesMode]

enum CirctFirtoolBuildMode:
  case Default
  case Debug
  case Release
end CirctFirtoolBuildMode
trait CirctFirtoolBuildModeApi extends HasSizeOf[CirctFirtoolBuildMode] with EnumHasToNative[CirctFirtoolBuildMode]

enum CirctFirtoolRandomKind:
  case None
  case Mem
  case Reg
  case All
end CirctFirtoolRandomKind
trait CirctFirtoolRandomKindApi extends HasSizeOf[CirctFirtoolRandomKind] with EnumHasToNative[CirctFirtoolRandomKind]

enum CirctFirtoolCompanionMode:
  case Bind
  case Instantiate
  case Drop
end CirctFirtoolCompanionMode
trait CirctFirtoolCompanionModeApi
    extends HasSizeOf[CirctFirtoolCompanionMode]
    with EnumHasToNative[CirctFirtoolCompanionMode]

enum CirctFirtoolVerificationFlavor:
  case None
  case IfElseFatal
  case Immediate
  case Sva
end CirctFirtoolVerificationFlavor
trait CirctFirtoolVerificationFlavorApi
    extends HasSizeOf[CirctFirtoolVerificationFlavor]
    with EnumHasToNative[CirctFirtoolVerificationFlavor]

class FirtoolOptions(handle: Ptr[CirctStructs.CirctFirtoolFirtoolOptions]) extends NativeHandle(handle)
trait FirtoolOptionsApi                                                    extends HasSizeOf[FirtoolOptions]

trait FirtoolApi:
  inline def firtoolOptionsCreateDefault(
    using zone: Zone
  ): FirtoolOptions
  extension (firtoolOptions: FirtoolOptions)
    inline def destroy(
      using zone: Zone
    ): Unit
    inline def setAddMuxPragmas(
      value:      Boolean
    )(
      using zone: Zone
    ): Unit
    inline def setAddVivadoRAMAddressConflictSynthesisBugWorkaround(
      value:      Boolean
    )(
      using zone: Zone
    ): Unit
    inline def setBlackBoxRootPath(
      value:      String
    )(
      using zone: Zone
    ): Unit
    inline def setBuildMode(
      value:      CirctFirtoolBuildMode
    )(
      using zone: Zone
    ): Unit
    inline def setCkgEnableName(
      value:      String
    )(
      using zone: Zone
    ): Unit
    inline def setCkgInputName(
      value:      String
    )(
      using zone: Zone
    ): Unit
    inline def setCkgModuleName(
      value:      String
    )(
      using zone: Zone
    ): Unit
    inline def setCkgOutputName(
      value:      String
    )(
      using zone: Zone
    ): Unit
    inline def setCkgTestEnableName(
      value:      String
    )(
      using zone: Zone
    ): Unit
    inline def setCompanionMode(
      value:      CirctFirtoolCompanionMode
    )(
      using zone: Zone
    ): Unit
    inline def setDisableAggressiveMergeConnections(
      value:      Boolean
    )(
      using zone: Zone
    ): Unit
    inline def setDisableAnnotationsClassless(
      value:      Boolean
    )(
      using zone: Zone
    ): Unit
    inline def setDisableCSEinClasses(
      value:      Boolean
    )(
      using zone: Zone
    ): Unit
    inline def setDisableLayerSink(
      value:      Boolean
    )(
      using zone: Zone
    ): Unit
    inline def setDisableOptimization(
      value:      Boolean
    )(
      using zone: Zone
    ): Unit
    inline def setDisableRandom(
      value:      CirctFirtoolRandomKind
    )(
      using zone: Zone
    ): Unit
    inline def setDisableUnknownAnnotations(
      disable:    Boolean
    )(
      using zone: Zone
    ): Unit
    inline def setEmitSeparateAlwaysBlocks(
      value:      Boolean
    )(
      using zone: Zone
    ): Unit
    inline def setEnableAnnotationWarning(
      value:      Boolean
    )(
      using zone: Zone
    ): Unit
    inline def setEnableDebugInfo(
      value:      Boolean
    )(
      using zone: Zone
    ): Unit
    inline def setExportModuleHierarchy(
      value:      Boolean
    )(
      using zone: Zone
    ): Unit
    inline def setIgnoreReadEnableMem(
      value:      Boolean
    )(
      using zone: Zone
    ): Unit
    inline def setLowerAnnotationsNoRefTypePorts(
      value:      Boolean
    )(
      using zone: Zone
    ): Unit
    inline def setLowerMemories(
      value:      Boolean
    )(
      using zone: Zone
    ): Unit
    inline def setNoDedup(
      value:      Boolean
    )(
      using zone: Zone
    ): Unit
    inline def setOutputAnnotationFilename(
      value:      String
    )(
      using zone: Zone
    ): Unit
    inline def setOutputFilename(
      filename:   String
    )(
      using zone: Zone
    ): Unit
    inline def setPreserveAggregate(
      value:      CirctFirtoolPreserveAggregateMode
    )(
      using zone: Zone
    ): Unit
    inline def setPreserveValues(
      value:      CirctFirtoolPreserveValuesMode
    )(
      using zone: Zone
    ): Unit
    inline def setReplSeqMem(
      value:      Boolean
    )(
      using zone: Zone
    ): Unit
    inline def setReplSeqMemFile(
      value:      String
    )(
      using zone: Zone
    ): Unit
    inline def setSelectDefaultInstanceChoice(
      value:      Boolean
    )(
      using zone: Zone
    ): Unit
    inline def setStripDebugInfo(
      value:      Boolean
    )(
      using zone: Zone
    ): Unit
    inline def setStripFirDebugInfo(
      value:      Boolean
    )(
      using zone: Zone
    ): Unit
    inline def setVbToBv(
      value:      Boolean
    )(
      using zone: Zone
    ): Unit
    inline def setVerificationFlavor(
      value:      CirctFirtoolVerificationFlavor
    )(
      using zone: Zone
    ): Unit
  extension (pm:             PassManager)
    // See inputFilename usage in https://github.com/llvm/circt/blob/ff847edb042541c44c79b59f1a680f641241b485/lib/Firtool/Firtool.cpp#L254
    def chirrtlToLowFIRRTL(
      firtoolOptions: FirtoolOptions
    )(
      using zone:     Zone
    ): LogicalResult
    def exportSplitVerilog(
      firtoolOptions: FirtoolOptions,
      directory:      String
    )(
      using zone:     Zone
    ): LogicalResult
    def exportVerilog(
      firtoolOptions: FirtoolOptions,
      callback:       String => Unit
    )(
      using zone:     Zone
    ): LogicalResult
    def finalizeIR(
      firtoolOptions: FirtoolOptions
    )(
      using zone:     Zone
    ): LogicalResult
    def hwToSV(
      firtoolOptions: FirtoolOptions
    )(
      using zone:     Zone
    ): LogicalResult
    def lowFIRRTLToHW(
      firtoolOptions: FirtoolOptions,
      inputFilename:  String
    )(
      using zone:     Zone
    ): LogicalResult
    def preprocessTransforms(
      firtoolOptions: FirtoolOptions
    )(
      using zone:     Zone
    ): LogicalResult
end FirtoolApi
