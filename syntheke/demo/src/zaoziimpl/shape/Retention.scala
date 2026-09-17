package me.jiuyang.syntheke.demo.zaoziimpl

import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.Bundle

class RetentionBundle extends Bundle:
  val save = Aligned(Bool())
  val restore = Aligned(Bool())
  val reset = Aligned(Reset())
  val saved = Flipped(Bool())
  val restored = Flipped(Bool())
