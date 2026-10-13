// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.sv.valuetpe

import me.jiuyang.zaozi.sv.TypeScopeContext

/** Mix into a singleton declaration to emit a typedef with that object's name. */
trait NamedType(
  using scope: TypeScopeContext,
  name:        sourcecode.Name)
    extends Data:
  private[sv] val typeScope: String = scope.name
  private[sv] val typeName:  String = name.value
  // Record identity only: fields and enum members are initialized after this constructor.
  scope.declarations += this
