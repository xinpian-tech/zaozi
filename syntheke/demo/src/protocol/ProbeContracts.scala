package me.jiuyang.syntheke.demo

import upickle.default.Writer

final case class InstructionRetirement(xlen: Int, regIndexBits: Int) derives Writer
