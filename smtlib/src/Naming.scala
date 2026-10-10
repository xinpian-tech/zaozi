// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.smtlib.default

import java.lang.foreign.Arena

import me.jiuyang.smtlib.SolverContext
import org.llvm.mlir.scalalib.capi.ir.{given_LocationApi, Context, Location, LocationApi}

private inline def locate(
  using Arena,
  Context,
  sourcecode.File,
  sourcecode.Line
): Location =
  summon[LocationApi].locationFileLineColGet(
    summon[sourcecode.File].value,
    summon[sourcecode.Line].value,
    0
  )

private inline def valName(
  using sourcecode.Name.Machine,
  SolverContext
): String = summon[sourcecode.Name.Machine].value match
  case actualName if !sourcecode.Util.isSyntheticName(actualName) => actualName
  case _                                                          => summon[SolverContext].nextAnonymousName()
