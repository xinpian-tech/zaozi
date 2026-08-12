// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.utlib

/** Direction of a trace-valued soft-constraint bias on the stimulus solve.
  *
  * Reference stimuli enter the SMT query as MaxSMT soft assertions over the same per-cycle
  * input symbols the hard constraints use — one per (port, cycle, reference). [[Toward]]
  * prefers models that agree with the references (warm-starting the search around an
  * observed trace); [[Away]] prefers models that differ (diversifying away from what
  * simulation has already exercised). Soft assertions never override the hard constraints:
  * an infeasible preference costs weight, it does not change satisfiability.
  */
enum TraceBias:
  case Toward, Away
