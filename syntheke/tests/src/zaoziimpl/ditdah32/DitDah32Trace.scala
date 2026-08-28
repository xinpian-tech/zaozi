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
class DitDah32Probe(parameter: DitDah32Parameter)
    extends DVBundle[DitDah32Parameter, DitDah32Layers](parameter):
  private def dv = layers("DV")
  val trace_valid           = ProbeRead(Bool(), dv)
  val trace_pc              = ProbeRead(UInt(parameter.xlen), dv)
  val trace_next_pc         = ProbeRead(UInt(parameter.xlen), dv)
  val trace_instr           = ProbeRead(UInt(parameter.xlen), dv)
  val trace_len             = ProbeRead(UInt(3), dv)
  val trace_rd_we           = ProbeRead(Bool(), dv)
  val trace_rd              = ProbeRead(UInt(parameter.registerIndexBits), dv)
  val trace_rd_wdata        = ProbeRead(UInt(parameter.xlen), dv)
  val trace_rs1_addr        = ProbeRead(UInt(5), dv)
  val trace_rs1_rdata       = ProbeRead(UInt(parameter.xlen), dv)
  val trace_rs2_addr        = ProbeRead(UInt(5), dv)
  val trace_rs2_rdata       = ProbeRead(UInt(parameter.xlen), dv)
  val trace_mem_addr        = ProbeRead(UInt(parameter.xlen), dv)
  val trace_mem_rmask       = ProbeRead(UInt(4), dv)
  val trace_mem_wmask       = ProbeRead(UInt(4), dv)
  val trace_mem_rdata       = ProbeRead(UInt(parameter.xlen), dv)
  val trace_mem_wdata       = ProbeRead(UInt(parameter.xlen), dv)
  val trace_mem_fault       = ProbeRead(Bool(), dv)
  val trace_mem_fault_rmask = ProbeRead(UInt(4), dv)
  val trace_mem_fault_wmask = ProbeRead(UInt(4), dv)
  val trace_csr_addr        = ProbeRead(UInt(12), dv)
  val trace_csr_rmask       = ProbeRead(UInt(parameter.xlen), dv)
  val trace_csr_wmask       = ProbeRead(UInt(parameter.xlen), dv)
  val trace_csr_rdata       = ProbeRead(UInt(parameter.xlen), dv)
  val trace_csr_wdata       = ProbeRead(UInt(parameter.xlen), dv)
  val trace_trap            = ProbeRead(Bool(), dv)
  val trace_trap_cause      = ProbeRead(UInt(4), dv)
  val trace_mstatus         = ProbeRead(UInt(parameter.xlen), dv)
  val trace_mstatus_post_commit = ProbeRead(UInt(parameter.xlen), dv)
  val trace_mstatus_pre_trap = ProbeRead(UInt(parameter.xlen), dv)
  val trace_mie             = ProbeRead(UInt(parameter.xlen), dv)
  val trace_mtvec           = ProbeRead(UInt(parameter.xlen), dv)
  val trace_mepc            = ProbeRead(UInt(parameter.xlen), dv)
  val trace_mtval           = ProbeRead(UInt(parameter.xlen), dv)
  val trace_mip             = ProbeRead(UInt(parameter.xlen), dv)
  val trace_mcause          = ProbeRead(UInt(parameter.xlen), dv)
  val trace_irq_pending_mask = ProbeRead(UInt(parameter.xlen), dv)
