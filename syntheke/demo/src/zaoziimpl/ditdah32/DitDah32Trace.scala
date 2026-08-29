// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
// SPDX-License-Identifier: MIT
package com.vowstar.ditdah32

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

// Verification trace surface. Lowered into the layer("DV") bind collateral so
// the production main module carries no trace ports or registers; the formal
// wrapper and cocotb harness resolve these via the generated probe XMRs.
class DitDah32Probe(parameter: DitDah32Parameter) extends DVBundle[DitDah32Parameter, DitDah32Layers](parameter):
  private def dv                = layers("DV")
  val trace_valid               = Option.when(parameter.enableTrace)(ProbeRead(Bool(), dv))
  val trace_pc                  = Option.when(parameter.enableTrace)(ProbeRead(UInt(parameter.xlen), dv))
  val trace_next_pc             = Option.when(parameter.enableTrace)(ProbeRead(UInt(parameter.xlen), dv))
  val trace_instr               = Option.when(parameter.enableTrace)(ProbeRead(UInt(parameter.xlen), dv))
  val trace_len                 = Option.when(parameter.enableTrace)(ProbeRead(UInt(3), dv))
  val trace_rd_we               = Option.when(parameter.enableTrace)(ProbeRead(Bool(), dv))
  val trace_rd                  = Option.when(parameter.enableTrace)(ProbeRead(UInt(parameter.registerIndexBits), dv))
  val trace_rd_wdata            = Option.when(parameter.enableTrace)(ProbeRead(UInt(parameter.xlen), dv))
  val trace_rs1_addr            = Option.when(parameter.enableTrace)(ProbeRead(UInt(5), dv))
  val trace_rs1_rdata           = Option.when(parameter.enableTrace)(ProbeRead(UInt(parameter.xlen), dv))
  val trace_rs2_addr            = Option.when(parameter.enableTrace)(ProbeRead(UInt(5), dv))
  val trace_rs2_rdata           = Option.when(parameter.enableTrace)(ProbeRead(UInt(parameter.xlen), dv))
  val trace_mem_addr            = Option.when(parameter.enableTrace)(ProbeRead(UInt(parameter.xlen), dv))
  val trace_mem_rmask           = Option.when(parameter.enableTrace)(ProbeRead(UInt(4), dv))
  val trace_mem_wmask           = Option.when(parameter.enableTrace)(ProbeRead(UInt(4), dv))
  val trace_mem_rdata           = Option.when(parameter.enableTrace)(ProbeRead(UInt(parameter.xlen), dv))
  val trace_mem_wdata           = Option.when(parameter.enableTrace)(ProbeRead(UInt(parameter.xlen), dv))
  val trace_mem_fault           = Option.when(parameter.enableTrace)(ProbeRead(Bool(), dv))
  val trace_mem_fault_rmask     = Option.when(parameter.enableTrace)(ProbeRead(UInt(4), dv))
  val trace_mem_fault_wmask     = Option.when(parameter.enableTrace)(ProbeRead(UInt(4), dv))
  val trace_csr_addr            = Option.when(parameter.enableTrace)(ProbeRead(UInt(12), dv))
  val trace_csr_rmask           = Option.when(parameter.enableTrace)(ProbeRead(UInt(parameter.xlen), dv))
  val trace_csr_wmask           = Option.when(parameter.enableTrace)(ProbeRead(UInt(parameter.xlen), dv))
  val trace_csr_rdata           = Option.when(parameter.enableTrace)(ProbeRead(UInt(parameter.xlen), dv))
  val trace_csr_wdata           = Option.when(parameter.enableTrace)(ProbeRead(UInt(parameter.xlen), dv))
  val trace_trap                = Option.when(parameter.enableTrace)(ProbeRead(Bool(), dv))
  val trace_trap_cause          = Option.when(parameter.enableTrace)(ProbeRead(UInt(4), dv))
  val trace_mstatus             = Option.when(parameter.enableTrace)(ProbeRead(UInt(parameter.xlen), dv))
  val trace_mstatus_post_commit = Option.when(parameter.enableTrace)(ProbeRead(UInt(parameter.xlen), dv))
  val trace_mstatus_pre_trap    = Option.when(parameter.enableTrace)(ProbeRead(UInt(parameter.xlen), dv))
  val trace_mie                 = Option.when(parameter.enableTrace)(ProbeRead(UInt(parameter.xlen), dv))
  val trace_mtvec               = Option.when(parameter.enableTrace)(ProbeRead(UInt(parameter.xlen), dv))
  val trace_mepc                = Option.when(parameter.enableTrace)(ProbeRead(UInt(parameter.xlen), dv))
  val trace_mtval               = Option.when(parameter.enableTrace)(ProbeRead(UInt(parameter.xlen), dv))
  val trace_mip                 = Option.when(parameter.enableTrace)(ProbeRead(UInt(parameter.xlen), dv))
  val trace_mcause              = Option.when(parameter.enableTrace)(ProbeRead(UInt(parameter.xlen), dv))
  val trace_irq_pending_mask    = Option.when(parameter.enableTrace)(ProbeRead(UInt(parameter.xlen), dv))
