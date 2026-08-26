// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.utlib

/** Resolves a CIRCT tool from `CIRCT_INSTALL_PATH` (the flake-provided install) when set, else falls back to `PATH`.
  * A forked test JVM's `PATH` can lag the devshell, but `CIRCT_INSTALL_PATH` is threaded in explicitly.
  */
private[utlib] object CirctTools:
  def apply(name: String): String =
    sys.env
      .get("CIRCT_INSTALL_PATH")
      .map(p => os.Path(p) / "bin" / name)
      .filter(os.exists)
      .map(_.toString)
      .getOrElse(name)
