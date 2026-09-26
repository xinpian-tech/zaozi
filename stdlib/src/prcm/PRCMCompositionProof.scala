// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.prcm

import me.jiuyang.smtlib.SMTQuery
import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.smtlib.parser.Z3Status

private[prcm] object PRCMCompositionProof:
  def queries: Seq[(String, Z3Status, SMTQuery)] =
    val model = new PRCMDomainModel(PRCMTarget.values.toSeq)
    Seq(
      ("service.persistence.feasible", Z3Status.Sat, persistence(false)),
      ("service.persistence", Z3Status.Unsat, persistence(true))
    ) ++ PRCMTarget.values.toSeq.flatMap: target =>
      Seq(
        (s"domain.$target.stable.feasible", Z3Status.Sat, stable(model, target, false)),
        (s"domain.$target.stable", Z3Status.Unsat, stable(model, target, true))
      )

  private def persistence(violation: Boolean): SMTQuery = SMTQuery.build:
    val phase       = smtValue(SInt, "phase")
    val next        = smtValue(SInt, "next_phase")
    val bits        = smtValue(SInt, "inputs")
    val need        = smtValue(Bool, "need")
    val grant       = smtValue(Bool, "grant")
    val fault       = smtValue(Bool, "fault")
    val released    = smtValue(Bool, "released")
    val request     = smtValue(Bool, "request")
    val nextRequest = smtValue(Bool, "next_request")
    smtAssert((phase >= 0.S) & (phase < 4.S) & (bits >= 0.S) & (bits < 16.S))
    (0 until 16).foreach: input =>
      val assignment = Seq(need, grant, fault, released).zipWithIndex
        .map((value, index) => if (input & (1 << index)) != 0 then value else !value)
        .reduce(_ & _)
      smtAssert((bits === input.S) ==> assignment)
      PRCMServicePhase.values.foreach: current =>
        val after         =
          PRCMServiceSequence.step(current, (input & 1) != 0, (input & 2) != 0, (input & 4) != 0, (input & 8) != 0)
        val beforeRequest = if PRCMServiceSequence.request(current) then request else !request
        val afterRequest  = if PRCMServiceSequence.request(after) then nextRequest else !nextRequest
        smtAssert(
          ((phase === current.ordinal.S) & (bits === input.S)) ==>
            ((next === after.ordinal.S) & beforeRequest & afterRequest)
        )
    val idle = phase === PRCMServicePhase.Idle.ordinal.S
    val waiting   = phase === PRCMServicePhase.Wait.ordinal.S
    val holding   = phase === PRCMServicePhase.Hold.ordinal.S
    val returning = phase === PRCMServicePhase.Return.ordinal.S
    val rules     = Seq(
      (idle & need) ==> ((next === PRCMServicePhase.Wait.ordinal.S) & nextRequest),
      (waiting & !fault & !grant) ==> (request & nextRequest),
      (waiting & !fault & grant) ==> ((next === PRCMServicePhase.Hold.ordinal.S) & nextRequest),
      (holding & need & !fault) ==> ((next === PRCMServicePhase.Hold.ordinal.S) & request & nextRequest),
      (holding & (!need | fault) & released) ==> ((next === PRCMServicePhase.Return.ordinal.S) & !nextRequest),
      (returning & !grant) ==> (next === PRCMServicePhase.Idle.ordinal.S)
    )
    if violation then smtAssert(!rules.reduce(_ & _))

  private def stable(model: PRCMDomainModel, target: PRCMTarget, violation: Boolean): SMTQuery = SMTQuery.build:
    val rows   = model.reachable.flatMap(before => before.advance(Some(target)).responses.map(after => before -> after))
    val row    = smtValue(SInt, "transition")
    val before = smtValue(Bool, "complete_before")
    val after  = smtValue(Bool, "complete_after")
    smtAssert((row >= 0.S) & (row < rows.size.S))
    rows.zipWithIndex.foreach: (entry, index) =>
      val (source, destination) = entry
      val input                 = if source.complete(Some(target)) then before else !before
      val output                = if destination.complete(Some(target)) then after else !after
      smtAssert((row === index.S) ==> (input & output))
    smtAssert(before)
    if violation then smtAssert(!after)
