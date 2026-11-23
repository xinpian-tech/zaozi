// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.capi.conversion

import org.llvm.mlir.raw.structs as MlirStructs
import org.llvm.circt.raw.functions as circtApi
import org.llvm.mlir.scalalib.capi.support.nativeResult
import scala.scalanative.unsafe.*

import org.llvm.mlir.scalalib.capi.pass.Pass

given ConversionCreateApi with
  def affineToLoopSchedule(
    using zone: Zone
  ): Pass = Pass(
    nativeResult[MlirStructs.MlirPass](out => circtApi.mlirCreateCIRCTConversionAffineToLoopSchedule()(out))
  )
  def cfToHandshake(
    using zone: Zone
  ): Pass = Pass(nativeResult[MlirStructs.MlirPass](out => circtApi.mlirCreateCIRCTConversionCFToHandshake()(out)))
  def calyxNative(
    using zone: Zone
  ): Pass = Pass(nativeResult[MlirStructs.MlirPass](out => circtApi.mlirCreateCIRCTConversionCalyxNative()(out)))
  def calyxRemoveGroupsFromFSM(
    using zone: Zone
  ): Pass = Pass(
    nativeResult[MlirStructs.MlirPass](out => circtApi.mlirCreateCIRCTConversionCalyxRemoveGroupsFromFSM()(out))
  )
  def calyxToFSM(
    using zone: Zone
  ): Pass = Pass(nativeResult[MlirStructs.MlirPass](out => circtApi.mlirCreateCIRCTConversionCalyxToFSM()(out)))
  def calyxToHW(
    using zone: Zone
  ): Pass = Pass(nativeResult[MlirStructs.MlirPass](out => circtApi.mlirCreateCIRCTConversionCalyxToHW()(out)))
  def convertCombToArith(
    using zone: Zone
  ): Pass = Pass(nativeResult[MlirStructs.MlirPass](out => circtApi.mlirCreateCIRCTConversionConvertCombToArith()(out)))
  def convertCombToSMT(
    using zone: Zone
  ): Pass = Pass(nativeResult[MlirStructs.MlirPass](out => circtApi.mlirCreateCIRCTConversionConvertCombToSMT()(out)))
  def convertFSMToSV(
    using zone: Zone
  ): Pass = Pass(nativeResult[MlirStructs.MlirPass](out => circtApi.mlirCreateCIRCTConversionConvertFSMToSV()(out)))
  def convertHWToBTOR2(
    using zone: Zone
  ): Pass = Pass(nativeResult[MlirStructs.MlirPass](out => circtApi.mlirCreateCIRCTConversionConvertHWToBTOR2()(out)))
  def convertHWToLLVM(
    using zone: Zone
  ): Pass = Pass(nativeResult[MlirStructs.MlirPass](out => circtApi.mlirCreateCIRCTConversionConvertHWToLLVM()(out)))
  def convertHWToSMT(
    using zone: Zone
  ): Pass = Pass(nativeResult[MlirStructs.MlirPass](out => circtApi.mlirCreateCIRCTConversionConvertHWToSMT()(out)))
  def convertHWToSystemC(
    using zone: Zone
  ): Pass = Pass(nativeResult[MlirStructs.MlirPass](out => circtApi.mlirCreateCIRCTConversionConvertHWToSystemC()(out)))
  def convertMooreToCore(
    using zone: Zone
  ): Pass = Pass(nativeResult[MlirStructs.MlirPass](out => circtApi.mlirCreateCIRCTConversionConvertMooreToCore()(out)))
  def convertVerifToSMT(
    using zone: Zone
  ): Pass = Pass(nativeResult[MlirStructs.MlirPass](out => circtApi.mlirCreateCIRCTConversionConvertVerifToSMT()(out)))
  def dcToHW(
    using zone: Zone
  ): Pass = Pass(nativeResult[MlirStructs.MlirPass](out => circtApi.mlirCreateCIRCTConversionDCToHW()(out)))
  def exportSplitVerilog(
    using zone: Zone
  ): Pass = Pass(nativeResult[MlirStructs.MlirPass](out => circtApi.mlirCreateCIRCTConversionExportSplitVerilog()(out)))
  def exportVerilog(
    using zone: Zone
  ): Pass = Pass(nativeResult[MlirStructs.MlirPass](out => circtApi.mlirCreateCIRCTConversionExportVerilog()(out)))
  def hwArithToHW(
    using zone: Zone
  ): Pass = Pass(nativeResult[MlirStructs.MlirPass](out => circtApi.mlirCreateCIRCTConversionHWArithToHW()(out)))
  def handshakeRemoveBlock(
    using zone: Zone
  ): Pass = Pass(
    nativeResult[MlirStructs.MlirPass](out => circtApi.mlirCreateCIRCTConversionHandshakeRemoveBlock()(out))
  )
  def handshakeToDC(
    using zone: Zone
  ): Pass = Pass(nativeResult[MlirStructs.MlirPass](out => circtApi.mlirCreateCIRCTConversionHandshakeToDC()(out)))
  def handshakeToHW(
    using zone: Zone
  ): Pass = Pass(nativeResult[MlirStructs.MlirPass](out => circtApi.mlirCreateCIRCTConversionHandshakeToHW()(out)))
  def legalizeAnonEnums(
    using zone: Zone
  ): Pass = Pass(nativeResult[MlirStructs.MlirPass](out => circtApi.mlirCreateCIRCTConversionLegalizeAnonEnums()(out)))
  def loopScheduleToCalyx(
    using zone: Zone
  ): Pass = Pass(
    nativeResult[MlirStructs.MlirPass](out => circtApi.mlirCreateCIRCTConversionLoopScheduleToCalyx()(out))
  )
  def lowerArcToLLVM(
    using zone: Zone
  ): Pass = Pass(nativeResult[MlirStructs.MlirPass](out => circtApi.mlirCreateCIRCTConversionLowerArcToLLVM()(out)))
  def lowerFIRRTLToHW(
    using zone: Zone
  ): Pass = Pass(nativeResult[MlirStructs.MlirPass](out => circtApi.mlirCreateCIRCTConversionLowerFIRRTLToHW()(out)))
  def lowerFirMem(
    using zone: Zone
  ): Pass = Pass(nativeResult[MlirStructs.MlirPass](out => circtApi.mlirCreateCIRCTConversionLowerFirMem()(out)))
  def lowerHWToSV(
    using zone: Zone
  ): Pass = Pass(nativeResult[MlirStructs.MlirPass](out => circtApi.mlirCreateCIRCTConversionLowerHWToSV()(out)))
  def lowerLTLToCore(
    using zone: Zone
  ): Pass = Pass(nativeResult[MlirStructs.MlirPass](out => circtApi.mlirCreateCIRCTConversionLowerLTLToCore()(out)))
  def lowerSMTToZ3LLVM(
    using zone: Zone
  ): Pass = Pass(nativeResult[MlirStructs.MlirPass](out => circtApi.mlirCreateCIRCTConversionLowerSMTToZ3LLVM()(out)))
  def lowerSeqToSV(
    using zone: Zone
  ): Pass = Pass(nativeResult[MlirStructs.MlirPass](out => circtApi.mlirCreateCIRCTConversionLowerSeqToSV()(out)))
  def lowerSimToSV(
    using zone: Zone
  ): Pass = Pass(nativeResult[MlirStructs.MlirPass](out => circtApi.mlirCreateCIRCTConversionLowerSimToSV()(out)))
  def lowerVerifToSV(
    using zone: Zone
  ): Pass = Pass(nativeResult[MlirStructs.MlirPass](out => circtApi.mlirCreateCIRCTConversionLowerVerifToSV()(out)))
  def materializeCalyxToFSM(
    using zone: Zone
  ): Pass = Pass(
    nativeResult[MlirStructs.MlirPass](out => circtApi.mlirCreateCIRCTConversionMaterializeCalyxToFSM()(out))
  )
  def pipelineToHW(
    using zone: Zone
  ): Pass = Pass(nativeResult[MlirStructs.MlirPass](out => circtApi.mlirCreateCIRCTConversionPipelineToHW()(out)))
  def prepareForEmission(
    using zone: Zone
  ): Pass = Pass(nativeResult[MlirStructs.MlirPass](out => circtApi.mlirCreateCIRCTConversionPrepareForEmission()(out)))
  def scfToCalyx(
    using zone: Zone
  ): Pass = Pass(nativeResult[MlirStructs.MlirPass](out => circtApi.mlirCreateCIRCTConversionSCFToCalyx()(out)))
  def testApplyLoweringOption(
    using zone: Zone
  ): Pass = Pass(
    nativeResult[MlirStructs.MlirPass](out => circtApi.mlirCreateCIRCTConversionTestApplyLoweringOption()(out))
  )
end given
