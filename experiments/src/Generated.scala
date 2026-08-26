// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>

// The slot the LLM harness overwrites (`ut_harness.py <file>` copies its input here). This default content is the
// working example the prompt shows: a typed-DSL experiment that solves the stdlib AbsVal odd-input constraint.

import me.jiuyang.stdlib.{AbsValOddUT, AbsValParameter}
import me.jiuyang.utlib.{UTCli, UTExperiment}

object Generated extends UTExperiment:
  def run(outDir: os.Path): ujson.Value =
    UTCli.generateReport(AbsValOddUT, AbsValParameter(8), bound = 1, outDir)
