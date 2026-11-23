// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>

package org.llvm.circt.scalalib.capi.firtool

import org.llvm.mlir.raw.structs as MlirStructs
import org.llvm.circt.raw.structs as CirctStructs
import org.llvm.circt.raw.enumerations as CEnums
import org.llvm.circt.raw.functions as circtApi
import scala.scalanative.unsafe.*

import org.llvm.mlir.scalalib.capi.support.{*, given}
import org.llvm.mlir.scalalib.capi.pass.{PassManager, given}

given FirtoolApi with
  inline def firtoolOptionsCreateDefault(
    using zone: Zone
  ): FirtoolOptions = FirtoolOptions(
    nativeResult[CirctStructs.CirctFirtoolFirtoolOptions](out => circtApi.circtFirtoolOptionsCreateDefault()(out))
  )
  extension (firtoolOptions: FirtoolOptions)
    inline def destroy(
      using zone: Zone
    ): Unit = circtApi.circtFirtoolOptionsDestroy(firtoolOptions.native)
    inline def setAddMuxPragmas(
      value:      Boolean
    )(
      using zone: Zone
    ): Unit = circtApi.circtFirtoolOptionsSetAddMuxPragmas(firtoolOptions.native, value)
    inline def setAddVivadoRAMAddressConflictSynthesisBugWorkaround(
      value:      Boolean
    )(
      using zone: Zone
    ): Unit =
      circtApi.circtFirtoolOptionsSetAddVivadoRAMAddressConflictSynthesisBugWorkaround(firtoolOptions.native, value)
    inline def setBlackBoxRootPath(
      value:      String
    )(
      using zone: Zone
    ): Unit = circtApi.circtFirtoolOptionsSetBlackBoxRootPath(firtoolOptions.native, value.toStringRef.native)
    inline def setBuildMode(
      value:      CirctFirtoolBuildMode
    )(
      using zone: Zone
    ): Unit = circtApi.circtFirtoolOptionsSetBuildMode(
      firtoolOptions.native,
      CEnums.CirctFirtoolBuildMode.define(value.toNative)
    )
    inline def setCkgEnableName(
      value:      String
    )(
      using zone: Zone
    ): Unit = circtApi.circtFirtoolOptionsSetCkgEnableName(firtoolOptions.native, value.toStringRef.native)
    inline def setCkgInputName(
      value:      String
    )(
      using zone: Zone
    ): Unit = circtApi.circtFirtoolOptionsSetCkgInputName(firtoolOptions.native, value.toStringRef.native)
    inline def setCkgModuleName(
      value:      String
    )(
      using zone: Zone
    ): Unit = circtApi.circtFirtoolOptionsSetCkgModuleName(firtoolOptions.native, value.toStringRef.native)
    inline def setCkgOutputName(
      value:      String
    )(
      using zone: Zone
    ): Unit = circtApi.circtFirtoolOptionsSetCkgOutputName(firtoolOptions.native, value.toStringRef.native)
    inline def setCkgTestEnableName(
      value:      String
    )(
      using zone: Zone
    ): Unit = circtApi.circtFirtoolOptionsSetCkgTestEnableName(firtoolOptions.native, value.toStringRef.native)
    inline def setCompanionMode(
      value:      CirctFirtoolCompanionMode
    )(
      using zone: Zone
    ): Unit = circtApi.circtFirtoolOptionsSetCompanionMode(
      firtoolOptions.native,
      CEnums.CirctFirtoolCompanionMode.define(value.toNative)
    )
    inline def setDisableAggressiveMergeConnections(
      value:      Boolean
    )(
      using zone: Zone
    ): Unit = circtApi.circtFirtoolOptionsSetDisableAggressiveMergeConnections(firtoolOptions.native, value)
    inline def setDisableAnnotationsClassless(
      value:      Boolean
    )(
      using zone: Zone
    ): Unit = circtApi.circtFirtoolOptionsSetDisableAnnotationsClassless(firtoolOptions.native, value)
    inline def setDisableCSEinClasses(
      value:      Boolean
    )(
      using zone: Zone
    ): Unit = circtApi.circtFirtoolOptionsSetDisableCSEinClasses(firtoolOptions.native, value)
    inline def setDisableLayerSink(
      value:      Boolean
    )(
      using zone: Zone
    ): Unit = circtApi.circtFirtoolOptionsSetDisableLayerSink(firtoolOptions.native, value)
    inline def setDisableOptimization(
      value:      Boolean
    )(
      using zone: Zone
    ): Unit = circtApi.circtFirtoolOptionsSetDisableOptimization(firtoolOptions.native, value)
    inline def setDisableRandom(
      value:      CirctFirtoolRandomKind
    )(
      using zone: Zone
    ): Unit = circtApi.circtFirtoolOptionsSetDisableRandom(
      firtoolOptions.native,
      CEnums.CirctFirtoolRandomKind.define(value.toNative)
    )
    inline def setDisableUnknownAnnotations(
      disable:    Boolean
    )(
      using zone: Zone
    ): Unit = circtApi.circtFirtoolOptionsSetDisableUnknownAnnotations(firtoolOptions.native, disable)
    inline def setEmitSeparateAlwaysBlocks(
      value:      Boolean
    )(
      using zone: Zone
    ): Unit = circtApi.circtFirtoolOptionsSetEmitSeparateAlwaysBlocks(firtoolOptions.native, value)
    inline def setEnableAnnotationWarning(
      value:      Boolean
    )(
      using zone: Zone
    ): Unit = circtApi.circtFirtoolOptionsSetEnableAnnotationWarning(firtoolOptions.native, value)
    inline def setEnableDebugInfo(
      value:      Boolean
    )(
      using zone: Zone
    ): Unit = circtApi.circtFirtoolOptionsSetEnableDebugInfo(firtoolOptions.native, value)
    inline def setExportModuleHierarchy(
      value:      Boolean
    )(
      using zone: Zone
    ): Unit = circtApi.circtFirtoolOptionsSetExportModuleHierarchy(firtoolOptions.native, value)
    inline def setIgnoreReadEnableMem(
      value:      Boolean
    )(
      using zone: Zone
    ): Unit = circtApi.circtFirtoolOptionsSetIgnoreReadEnableMem(firtoolOptions.native, value)
    inline def setLowerAnnotationsNoRefTypePorts(
      value:      Boolean
    )(
      using zone: Zone
    ): Unit = circtApi.circtFirtoolOptionsSetLowerAnnotationsNoRefTypePorts(firtoolOptions.native, value)
    inline def setLowerMemories(
      value:      Boolean
    )(
      using zone: Zone
    ): Unit = circtApi.circtFirtoolOptionsSetLowerMemories(firtoolOptions.native, value)
    inline def setNoDedup(
      value:      Boolean
    )(
      using zone: Zone
    ): Unit = circtApi.circtFirtoolOptionsSetNoDedup(firtoolOptions.native, value)
    inline def setOutputAnnotationFilename(
      value:      String
    )(
      using zone: Zone
    ): Unit = circtApi.circtFirtoolOptionsSetOutputAnnotationFilename(firtoolOptions.native, value.toStringRef.native)
    inline def setOutputFilename(
      filename:   String
    )(
      using zone: Zone
    ): Unit = circtApi.circtFirtoolOptionsSetOutputFilename(firtoolOptions.native, filename.toStringRef.native)
    inline def setPreserveAggregate(
      value:      CirctFirtoolPreserveAggregateMode
    )(
      using zone: Zone
    ): Unit = circtApi.circtFirtoolOptionsSetPreserveAggregate(
      firtoolOptions.native,
      CEnums.CirctFirtoolPreserveAggregateMode.define(value.toNative)
    )
    inline def setPreserveValues(
      value:      CirctFirtoolPreserveValuesMode
    )(
      using zone: Zone
    ): Unit = circtApi.circtFirtoolOptionsSetPreserveValues(
      firtoolOptions.native,
      CEnums.CirctFirtoolPreserveValuesMode.define(value.toNative)
    )
    inline def setReplSeqMem(
      value:      Boolean
    )(
      using zone: Zone
    ): Unit = circtApi.circtFirtoolOptionsSetReplSeqMem(firtoolOptions.native, value)
    inline def setReplSeqMemFile(
      value:      String
    )(
      using zone: Zone
    ): Unit = circtApi.circtFirtoolOptionsSetReplSeqMemFile(firtoolOptions.native, value.toStringRef.native)
    inline def setSelectDefaultInstanceChoice(
      value:      Boolean
    )(
      using zone: Zone
    ): Unit = circtApi.circtFirtoolOptionsSetSelectDefaultInstanceChoice(firtoolOptions.native, value)
    inline def setStripDebugInfo(
      value:      Boolean
    )(
      using zone: Zone
    ): Unit = circtApi.circtFirtoolOptionsSetStripDebugInfo(firtoolOptions.native, value)
    inline def setStripFirDebugInfo(
      value:      Boolean
    )(
      using zone: Zone
    ): Unit = circtApi.circtFirtoolOptionsSetStripFirDebugInfo(firtoolOptions.native, value)
    inline def setVbToBv(
      value:      Boolean
    )(
      using zone: Zone
    ): Unit = circtApi.circtFirtoolOptionsSetVbToBv(firtoolOptions.native, value)
    inline def setVerificationFlavor(
      value:      CirctFirtoolVerificationFlavor
    )(
      using zone: Zone
    ): Unit = circtApi.circtFirtoolOptionsSetVerificationFlavor(
      firtoolOptions.native,
      CEnums.CirctFirtoolVerificationFlavor.define(value.toNative)
    )
  extension (pm:             PassManager)
    // See inputFilename usage in https://github.com/llvm/circt/blob/ff847edb042541c44c79b59f1a680f641241b485/lib/Firtool/Firtool.cpp#L254
    def chirrtlToLowFIRRTL(
      firtoolOptions: FirtoolOptions
    )(
      using zone:     Zone
    ): LogicalResult = LogicalResult(
      nativeResult[MlirStructs.MlirLogicalResult](out =>
        circtApi.circtFirtoolPopulateCHIRRTLToLowFIRRTL(pm.native, firtoolOptions.native)(out)
      )
    )
    def exportSplitVerilog(
      firtoolOptions: FirtoolOptions,
      directory:      String
    )(
      using zone:     Zone
    ): LogicalResult = LogicalResult(
      nativeResult[MlirStructs.MlirLogicalResult](out =>
        circtApi.circtFirtoolPopulateExportSplitVerilog(pm.native, firtoolOptions.native, directory.toStringRef.native)(
          out
        )
      )
    )
    def exportVerilog(
      firtoolOptions: FirtoolOptions,
      callback:       String => Unit
    )(
      using zone:     Zone
    ): LogicalResult =
      val data = NativeCallbacks.retain(callback.stringToStringCallback.consume)
      pm._callbackStates.add(data)
      LogicalResult(
        nativeResult[MlirStructs.MlirLogicalResult](out =>
          circtApi.circtFirtoolPopulateExportVerilog(
            pm.native,
            firtoolOptions.native,
            CallbackFunctions.string(),
            data
          )(out)
        )
      )
    def finalizeIR(
      firtoolOptions: FirtoolOptions
    )(
      using zone:     Zone
    ): LogicalResult = LogicalResult(
      nativeResult[MlirStructs.MlirLogicalResult](out =>
        circtApi.circtFirtoolPopulateFinalizeIR(pm.native, firtoolOptions.native)(out)
      )
    )
    def hwToSV(
      firtoolOptions: FirtoolOptions
    )(
      using zone:     Zone
    ): LogicalResult = LogicalResult(
      nativeResult[MlirStructs.MlirLogicalResult](out =>
        circtApi.circtFirtoolPopulateHWToSV(pm.native, firtoolOptions.native)(out)
      )
    )
    def lowFIRRTLToHW(
      firtoolOptions: FirtoolOptions,
      inputFilename:  String
    )(
      using zone:     Zone
    ): LogicalResult = LogicalResult(
      nativeResult[MlirStructs.MlirLogicalResult](out =>
        circtApi.circtFirtoolPopulateLowFIRRTLToHW(pm.native, firtoolOptions.native, inputFilename.toStringRef.native)(
          out
        )
      )
    )
    def preprocessTransforms(
      firtoolOptions: FirtoolOptions
    )(
      using zone:     Zone
    ): LogicalResult = LogicalResult(
      nativeResult[MlirStructs.MlirLogicalResult](out =>
        circtApi.circtFirtoolPopulatePreprocessTransforms(pm.native, firtoolOptions.native)(out)
      )
    )
end given
