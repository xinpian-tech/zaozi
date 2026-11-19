// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.capi.conversion

import org.llvm.mlir.scalalib.capi.pass.Pass

import scala.scalanative.unsafe.Zone

trait ConversionCreateApi:
  def affineToLoopSchedule(
    using zone: Zone
  ): Pass
  def cfToHandshake(
    using zone: Zone
  ): Pass
  def calyxNative(
    using zone: Zone
  ): Pass
  def calyxRemoveGroupsFromFSM(
    using zone: Zone
  ): Pass
  def calyxToFSM(
    using zone: Zone
  ): Pass
  def calyxToHW(
    using zone: Zone
  ): Pass
  def convertCombToArith(
    using zone: Zone
  ): Pass
  def convertCombToSMT(
    using zone: Zone
  ): Pass
  def convertFSMToSV(
    using zone: Zone
  ): Pass
  def convertHWToBTOR2(
    using zone: Zone
  ): Pass
  def convertHWToLLVM(
    using zone: Zone
  ): Pass
  def convertHWToSMT(
    using zone: Zone
  ): Pass
  def convertHWToSystemC(
    using zone: Zone
  ): Pass
  def convertMooreToCore(
    using zone: Zone
  ): Pass
  def convertVerifToSMT(
    using zone: Zone
  ): Pass
  def dcToHW(
    using zone: Zone
  ): Pass
  def exportSplitVerilog(
    using zone: Zone
  ): Pass
  def exportVerilog(
    using zone: Zone
  ): Pass
  def hwArithToHW(
    using zone: Zone
  ): Pass
  def handshakeRemoveBlock(
    using zone: Zone
  ): Pass
  def handshakeToDC(
    using zone: Zone
  ): Pass
  def handshakeToHW(
    using zone: Zone
  ): Pass
  def legalizeAnonEnums(
    using zone: Zone
  ): Pass
  def loopScheduleToCalyx(
    using zone: Zone
  ): Pass
  def lowerArcToLLVM(
    using zone: Zone
  ): Pass
  def lowerFIRRTLToHW(
    using zone: Zone
  ): Pass
  def lowerFirMem(
    using zone: Zone
  ): Pass
  def lowerHWToSV(
    using zone: Zone
  ): Pass
  def lowerLTLToCore(
    using zone: Zone
  ): Pass
  def lowerSMTToZ3LLVM(
    using zone: Zone
  ): Pass
  def lowerSeqToSV(
    using zone: Zone
  ): Pass
  def lowerSimToSV(
    using zone: Zone
  ): Pass
  def lowerVerifToSV(
    using zone: Zone
  ): Pass
  def materializeCalyxToFSM(
    using zone: Zone
  ): Pass
  def pipelineToHW(
    using zone: Zone
  ): Pass
  def prepareForEmission(
    using zone: Zone
  ): Pass
  def scfToCalyx(
    using zone: Zone
  ): Pass
  def testApplyLoweringOption(
    using zone: Zone
  ): Pass
end ConversionCreateApi

trait ConversionRegisterApi:
  def affineToLoopSchedule:     Unit
  def cfToHandshake:            Unit
  def calyxNative:              Unit
  def calyxRemoveGroupsFromFSM: Unit
  def calyxToFSM:               Unit
  def calyxToHW:                Unit
  def convertCombToArith:       Unit
  def convertCombToSMT:         Unit
  def convertFSMToSV:           Unit
  def convertHWToBTOR2:         Unit
  def convertHWToLLVM:          Unit
  def convertHWToSMT:           Unit
  def convertHWToSystemC:       Unit
  def convertMooreToCore:       Unit
  def convertVerifToSMT:        Unit
  def dcToHW:                   Unit
  def exportSplitVerilog:       Unit
  def exportVerilog:            Unit
  def hwArithToHW:              Unit
  def handshakeRemoveBlock:     Unit
  def handshakeToDC:            Unit
  def handshakeToHW:            Unit
  def legalizeAnonEnums:        Unit
  def loopScheduleToCalyx:      Unit
  def lowerArcToLLVM:           Unit
  def lowerFIRRTLToHW:          Unit
  def lowerFirMem:              Unit
  def lowerHWToSV:              Unit
  def lowerLTLToCore:           Unit
  def lowerSMTToZ3LLVM:         Unit
  def lowerSeqToSV:             Unit
  def lowerSimToSV:             Unit
  def lowerVerifToSV:           Unit
  def materializeCalyxToFSM:    Unit
  def passes:                   Unit
  def pipelineToHW:             Unit
  def prepareForEmission:       Unit
  def scfToCalyx:               Unit
  def testApplyLoweringOption:  Unit
end ConversionRegisterApi
