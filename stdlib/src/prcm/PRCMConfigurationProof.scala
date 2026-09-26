// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.prcm

import me.jiuyang.smtlib.SMTQuery
import me.jiuyang.smtlib.default.{*, given}
import me.jiuyang.smtlib.tpe.{Bool as SMTBool, Referable as SMTValue}

private[prcm] object PRCMConfigurationProof:
  def queries(parameter: PRCMParameter): Seq[(String, SMTQuery, Map[String, String])] =
    val domains = parameter.orderedDomains
    val local   = domains.zipWithIndex.flatMap: (domain, index) =>
      domain.modes.zipWithIndex.map: (mode, selected) =>
        (s"domain.${domain.name}.mode.${mode.name}", Some(index -> selected), None)
    val chip    = parameter.chip.toSeq
      .flatMap(_.modes)
      .map: mode =>
        (s"chip.mode.${mode.name}", None, Some(mode))
    (local ++ chip).map: (name, selected, chipMode) =>
      val domainSources    = domains.zipWithIndex.map: (domain, index) =>
        s"domain_$index" -> s"domain.${domain.name}.modes"
      val serviceSources   = parameter.serviceEdges.zipWithIndex.map: (edge, index) =>
        s"service_$index" -> s"domain.${domains(edge.consumer).name}.services.${edge.services.mkString(",")}"
      val selectionSources = selected.toSeq.map(_ => "selection" -> name) ++ chipMode.toSeq.flatMap: mode =>
        domains.zipWithIndex.collect:
          case (domain, index) if mode.domains(domain.name).isInstanceOf[PRCMPolicy.Fixed] =>
            s"policy_$index" -> s"chip.mode.${mode.name}.domain.${domain.name}"
      val sources          = (domainSources ++ serviceSources ++ selectionSources).toMap
      val query            = SMTQuery.build:
        val choices = domains.indices.map(index => smtValue(SInt, s"mode_$index"))
        def requireRule(id: String, condition: SMTValue[SMTBool]): Unit              =
          val enabled = smtValue(Bool, id)
          smtAssert(enabled ==> condition)
        domains.zipWithIndex.foreach: (domain, index) =>
          requireRule(s"domain_$index", (choices(index) >= 0.S) & (choices(index) < domain.modes.size.S))
        def running(index: Int):                                   SMTValue[SMTBool] =
          domains(index).modes.zipWithIndex
            .filter(_._1.target == PRCMTarget.Run)
            .map((_, mode) => choices(index) === mode.S)
            .foldLeft(false.B: SMTValue[SMTBool])(_ | _)
        parameter.serviceEdges.zipWithIndex.foreach: (edge, index) =>
          requireRule(s"service_$index", running(edge.consumer) ==> running(edge.provider))
        selected.foreach: (domain, mode) =>
          requireRule("selection", choices(domain) === mode.S)
        chipMode.foreach: mode =>
          domains.zipWithIndex.foreach: (domain, index) =>
            mode.domains(domain.name) match
              case PRCMPolicy.Local         => ()
              case PRCMPolicy.Fixed(target) =>
                requireRule(s"policy_$index", choices(index) === domain.modes.indexWhere(_.name == target).S)
      (name, query.copy(assumptions = sources.keys.toSeq.sorted), sources)
