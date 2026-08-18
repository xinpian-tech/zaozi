// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib

import me.jiuyang.zaozi.{HWInterface, Parameter}

/** Marks a Zaozi generator as a unit-test module.
  *
  * The framework derives the DPI contract and the flat lib model from the module's `(IO, Probe)`. The verification
  * intent — SVA assertions and assumptions — lives in the module's own architecture under the verification layer, not
  * in a separate method.
  */
trait HasUT[PARAM <: Parameter, I <: HWInterface[PARAM]]
