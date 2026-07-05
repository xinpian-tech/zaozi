// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.valuetpe

/** Marks a [[Data]] as a FIRRTL value type the connect operators accept. Probe reference types ([[RProbe]],
  * [[RWProbe]], [[ProbeBundle]], [[ProbeRecord]]) deliberately do not mix it in and connect via `<==`; excluding a new
  * reference type is a matter of not extending this trait.
  */
trait Connectable extends Data
