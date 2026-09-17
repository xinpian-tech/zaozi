package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.demo.zaoziimpl.InstructionTrace
import me.jiuyang.syntheke.zaozi.ProbeBindingFor

given retirementBinding: ProbeBindingFor[InstructionRetirement, InstructionTrace] =
  ProbeBindingFor()
