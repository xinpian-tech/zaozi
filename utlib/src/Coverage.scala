// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib

/** Cover labels and hit counts reported by the simulator. Cover operations themselves belong in the DUT architecture.
  */
final case class CoverageReport(hits: Map[String, Int]):
  def hit(name: String): Boolean = hits.getOrElse(name, 0) > 0
