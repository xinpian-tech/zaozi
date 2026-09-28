package me.jiuyang.syntheke.demo.zaoziimpl

import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.Bundle

class PowerControlBundle extends Bundle:
  val requestOn = Aligned(Bool())
  val on = Flipped(Bool())
  val busy = Flipped(Bool())
  val powerGood = Flipped(Bool())
  val isolated = Flipped(Bool())
