package me.jiuyang.syntheke.demo.zaoziimpl

import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.Bundle

/** What a domain's retention flops need from the always-on side: `sleep` holds their always-on slave latches, and
  * `reset`, the always-on reset, is the only reset that clears them.
  */
class RetentionBundle extends Bundle:
  val sleep = Aligned(Bool())
  val reset = Aligned(Reset())
