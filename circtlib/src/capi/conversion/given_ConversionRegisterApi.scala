// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.capi.conversion

import org.llvm.circt.raw.functions as circtApi
import scala.scalanative.unsafe.*

given ConversionRegisterApi with
  def affineToLoopSchedule:     Unit = circtApi.mlirRegisterCIRCTConversionAffineToLoopSchedule()
  def cfToHandshake:            Unit = circtApi.mlirRegisterCIRCTConversionCFToHandshake()
  def calyxNative:              Unit = circtApi.mlirRegisterCIRCTConversionCalyxNative()
  def calyxRemoveGroupsFromFSM: Unit = circtApi.mlirRegisterCIRCTConversionCalyxRemoveGroupsFromFSM()
  def calyxToFSM:               Unit = circtApi.mlirRegisterCIRCTConversionCalyxToFSM()
  def calyxToHW:                Unit = circtApi.mlirRegisterCIRCTConversionCalyxToHW()
  def convertCombToArith:       Unit = circtApi.mlirRegisterCIRCTConversionConvertCombToArith()
  def convertCombToSMT:         Unit = circtApi.mlirRegisterCIRCTConversionConvertCombToSMT()
  def convertFSMToSV:           Unit = circtApi.mlirRegisterCIRCTConversionConvertFSMToSV()
  def convertHWToBTOR2:         Unit = circtApi.mlirRegisterCIRCTConversionConvertHWToBTOR2()
  def convertHWToLLVM:          Unit = circtApi.mlirRegisterCIRCTConversionConvertHWToLLVM()
  def convertHWToSMT:           Unit = circtApi.mlirRegisterCIRCTConversionConvertHWToSMT()
  def convertHWToSystemC:       Unit = circtApi.mlirRegisterCIRCTConversionConvertHWToSystemC()
  def convertMooreToCore:       Unit = circtApi.mlirRegisterCIRCTConversionConvertMooreToCore()
  def convertVerifToSMT:        Unit = circtApi.mlirRegisterCIRCTConversionConvertVerifToSMT()
  def dcToHW:                   Unit = circtApi.mlirRegisterCIRCTConversionDCToHW()
  def exportSplitVerilog:       Unit = circtApi.mlirRegisterCIRCTConversionExportSplitVerilog()
  def exportVerilog:            Unit = circtApi.mlirRegisterCIRCTConversionExportVerilog()
  def hwArithToHW:              Unit = circtApi.mlirRegisterCIRCTConversionHWArithToHW()
  def handshakeRemoveBlock:     Unit = circtApi.mlirRegisterCIRCTConversionHandshakeRemoveBlock()
  def handshakeToDC:            Unit = circtApi.mlirRegisterCIRCTConversionHandshakeToDC()
  def handshakeToHW:            Unit = circtApi.mlirRegisterCIRCTConversionHandshakeToHW()
  def legalizeAnonEnums:        Unit = circtApi.mlirRegisterCIRCTConversionLegalizeAnonEnums()
  def loopScheduleToCalyx:      Unit = circtApi.mlirRegisterCIRCTConversionLoopScheduleToCalyx()
  def lowerArcToLLVM:           Unit = circtApi.mlirRegisterCIRCTConversionLowerArcToLLVM()
  def lowerFIRRTLToHW:          Unit = circtApi.mlirRegisterCIRCTConversionLowerFIRRTLToHW()
  def lowerFirMem:              Unit = circtApi.mlirRegisterCIRCTConversionLowerFirMem()
  def lowerHWToSV:              Unit = circtApi.mlirRegisterCIRCTConversionLowerHWToSV()
  def lowerLTLToCore:           Unit = circtApi.mlirRegisterCIRCTConversionLowerLTLToCore()
  def lowerSMTToZ3LLVM:         Unit = circtApi.mlirRegisterCIRCTConversionLowerSMTToZ3LLVM()
  def lowerSeqToSV:             Unit = circtApi.mlirRegisterCIRCTConversionLowerSeqToSV()
  def lowerSimToSV:             Unit = circtApi.mlirRegisterCIRCTConversionLowerSimToSV()
  def lowerVerifToSV:           Unit = circtApi.mlirRegisterCIRCTConversionLowerVerifToSV()
  def materializeCalyxToFSM:    Unit = circtApi.mlirRegisterCIRCTConversionMaterializeCalyxToFSM()
  def passes:                   Unit = circtApi.mlirRegisterCIRCTConversionPasses()
  def pipelineToHW:             Unit = circtApi.mlirRegisterCIRCTConversionPipelineToHW()
  def prepareForEmission:       Unit = circtApi.mlirRegisterCIRCTConversionPrepareForEmission()
  def scfToCalyx:               Unit = circtApi.mlirRegisterCIRCTConversionSCFToCalyx()
  def testApplyLoweringOption:  Unit = circtApi.mlirRegisterCIRCTConversionTestApplyLoweringOption()
end given
