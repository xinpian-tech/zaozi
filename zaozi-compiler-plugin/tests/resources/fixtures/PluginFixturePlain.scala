// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package fixtures

/** No bundles, no dynamic accesses: the plugin must leave this file's SemanticDB byte-identical. */
object PluginFixturePlain:
  val answer: Int = 42

  def twice(n: Int): Int = n + n
