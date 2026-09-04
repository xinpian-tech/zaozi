// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.stdlib.mmio

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.valuetpe.*

class RegMapRequest(
  val indexWidth: Int,
  val dataWidth:  Int
)(
  using TypeImpl,
  ConstructorApi)
    extends Bundle:
  require(indexWidth > 0, s"indexWidth must be positive, not $indexWidth")
  require(dataWidth > 0 && dataWidth % 8 == 0, s"dataWidth must be a positive multiple of 8, not $dataWidth")

  val read:  BundleField[Bool] = Aligned(summon[ConstructorApi].Bool())
  val index: BundleField[UInt] = Aligned(summon[ConstructorApi].UInt(indexWidth))
  val data:  BundleField[Bits] = Aligned(summon[ConstructorApi].Bits(dataWidth))
  val mask:  BundleField[Bits] = Aligned(summon[ConstructorApi].Bits(dataWidth / 8))

class RegMapResponse(
  val dataWidth:   Int,
  val reportError: Boolean
)(
  using TypeImpl,
  ConstructorApi)
    extends Bundle:
  require(dataWidth > 0 && dataWidth % 8 == 0, s"dataWidth must be a positive multiple of 8, not $dataWidth")

  val read:  BundleField[Bool]         = Aligned(summon[ConstructorApi].Bool())
  val data:  BundleField[Bits]         = Aligned(summon[ConstructorApi].Bits(dataWidth))
  val error: Option[BundleField[Bool]] = Option.when(reportError)(Aligned(summon[ConstructorApi].Bool()))
