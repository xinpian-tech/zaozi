package me.jiuyang.syntheke.demo.zaoziimpl

import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.*

class InstructionTrace(val xlen: Int, val regIndexBits: Int) extends Bundle with CanProbe:
  val clock = Aligned(Clock())
  val inReset = Aligned(Bool())
  val valid = Aligned(Bool())
  val pc = Aligned(UInt(xlen))
  val instr = Aligned(UInt(xlen))
  val rdWe = Aligned(Bool())
  val rd = Aligned(UInt(regIndexBits))
  val rdWdata = Aligned(UInt(xlen))
