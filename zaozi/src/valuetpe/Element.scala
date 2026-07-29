// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.valuetpe

/** Base type for scalar (non-aggregate) hardware values: [[Bits]], [[UInt]], [[SInt]], [[Bool]], [[Clock]], [[Reset]],
  * [[Analog]].
  */
trait Element extends Connectable
