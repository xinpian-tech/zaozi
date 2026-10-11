// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.sv.reftpe

import me.jiuyang.zaozi.sv.valuetpe.Enum

/** A declaration reference, materialized as an enum constant at each use site. */
final class EnumMember[E <: Enum] private[sv] (val enumType: E, val name: String) extends Referable[E]:
  private[zaozi] val _tpe = enumType
