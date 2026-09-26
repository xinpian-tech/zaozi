// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.prcm

import me.jiuyang.smtlib.SMTQuery
import org.llvm.mlir.scalalib.capi.ir.Value

private[prcm] object PRCMAssemblyBindings:
  def queries(parameter: PRCMParameter, ir: String, metadata: ujson.Value): (SMTQuery, SMTQuery, SMTQuery) =
    PRCMRelation.use(ir, metadata): relation =>
      import relation.*
      val width   = parameter.dataWidth
      val lanes   = width / 8
      val cold    = registers("cold/synchronizationStages")
      require(cold.size == parameter.coldResetStages)
      val resetN  = value(cold.last)
      val allowed = and(asBool(resetN), not(bit("managementReset")))
      val gates   = states.filter(_("kind").str == "llhd.sig")
      require(gates.size == parameter.domains.size)
      def mismatch(name: String, actual: Value, expected: Value):      Value                        =
        violation(name, not(equal(actual, expected)))
      def flagMismatch(name: String, actual: String, expected: Value): Value                        =
        mismatch(name, values(actual), asBits(expected))
      def management(register: ujson.Value, initial: BigInt):          Vector[Value]                =
        require(
          register("clockEdge").num == 0 && register("reset").bool &&
            register("resetPolarity").num == 1 && register("resetType").num == 1
        )
        val name = symbol(register)
        Vector(
          mismatch(s"${name}_clock", port(register, "clock"), values("clock")),
          mismatch(s"${name}_reset", port(register, "reset"), resetN),
          mismatch(s"${name}_initial", port(register, "reset_value"), const(initial, register("width").num.toInt))
        )
      def padded(value: Value, used: Int):                             Value                        =
        if used == width then value else concat(const(0, width - used), value)
      def packed(flags: Seq[Value], low: Value):                       Value                        = flags.reverse.map(asBits).foldRight(low)(concat)
      def request(prefix: String, bits: Int):                          (Value, Vector[ujson.Value]) =
        val bytes = (0 until bits by 8).map(i => register(s"${prefix}_${i / 8}")).toVector
        (bytes.reverse.map(value).reduce(concat), bytes)
      def hits(input:   Value, code:     BigInt, bits: Int):           Value                        = equal(input, const(code, bits))
      case class Domain(index: Int, config: PRCMManagedDomain):
        val phase = register(s"domain_$index/phase")
        val receivers = registers(s"domain_$index/receiver/synchronizationStages")
        val feedbacks = registers(s"domain_$index/resetFeedback")
        require(receivers.size == config.resetStages && feedbacks.size == 2)
        val power = bit(s"domains_${index}_powerGood")
        val isolation = bit(s"domains_${index}_isolationActive")
        val idle = bit(s"domains_${index}_idle")
        val reset = asBool(value(feedbacks.last))
        def at(p:   PRCMPhase):            Value = hits(value(phase), p.ordinal, 4)
        def flag(f: PRCMPhase => Boolean): Value = or(PRCMPhase.values.filter(f).map(at).toSeq)
        val powerRequest = flag(p => PRCMSequence.control(p).power)
        val clockRequest = flag(p => PRCMSequence.control(p).clock)
        val resetRequest = flag(p => PRCMSequence.control(p).reset)
        val isolationRequest = flag(p => PRCMSequence.control(p).isolation)
        val quiesceRequest = flag(p => PRCMSequence.control(p).quiesce)
        val fault = flag(PRCMSequence.fault)
        val lost = and(flag(p => PRCMSequence.powerLost(p, false)), not(power))
        val ready = Vector(
          and(at(PRCMPhase.Off), not(power), reset, isolation, idle),
          and(at(PRCMPhase.Reset), power, reset, isolation, idle),
          and(at(PRCMPhase.Run), power, not(reset), not(isolation), not(idle))
        )
        val released = or(ready.take(2) :+ and(fault, not(powerRequest), not(power), reset, isolation, idle))
        val (requested, bytes) = request(s"domain_${index}_request", config.modeWidth)
        val storedTargets = registers(s"domain_${index}_target")
        val declaredTargets = config.modes.map(_.target).distinct
        require(
          storedTargets.size <= 1 && (storedTargets.nonEmpty || declaredTargets.size == 1),
          s"domain $index requires retained target state"
        )
        val last = storedTargets.headOption
        last.foreach(s => assume(or(declaredTargets.map(t => hits(value(s), t.ordinal, 2)))))
        val retained = last.fold(const(config.reset.target.ordinal, 2))(value)
        val target = config.modes.foldLeft(retained): (fallback, mode) =>
          ite(hits(requested, mode.code, config.modeWidth), const(mode.target.ordinal, 2), fallback)
        val legal = or(config.modes.map(mode => hits(requested, mode.code, config.modeWidth)))
      val domains = parameter.orderedDomains.zipWithIndex.map((config, index) => Domain(index, config))
      val chip = parameter.chip.map: config =>
        val (requested, bytes) = request("chip_request", config.modeWidth)
        val storedTargets      = registers("chip_target")
        val uniformPolicy      = parameter.chipTargets.map(_._2).distinct.size == 1
        require(
          storedTargets.size <= 1 && (storedTargets.nonEmpty || uniformPolicy),
          "chip requires retained policy state"
        )
        val last               = storedTargets.headOption
        val legal              = or(config.modes.map(mode => hits(requested, mode.code, config.modeWidth)))
        last.foreach(s => assume(or(config.modes.map(mode => hits(value(s), mode.code, config.modeWidth)))))
        val retained           = last.fold(const(config.reset.code, config.modeWidth))(value)
        (config, requested, bytes, last, legal, ite(legal, requested, retained))
      val bases = domains.map: domain =>
        chip.fold(domain.target): (_, _, _, _, _, selected) =>
          parameter.chipTargets.foldLeft(domain.target): (fallback, entry) =>
            val (mode, targets) = entry
            targets(domain.index).fold(fallback)(target =>
              ite(hits(selected, mode.code, parameter.chip.get.modeWidth), const(target.ordinal, 2), fallback)
            )
      case class Link(edge: PRCMServiceEdge):
        val phase      = register(s"service_${edge.consumer}_${edge.provider}/phase")
        val grant      = register(s"grant_${edge.consumer}_${edge.provider}")
        val held       = hits(value(phase), PRCMServicePhase.Hold.ordinal, 2)
        val requested  = or(Seq(hits(value(phase), PRCMServicePhase.Wait.ordinal, 2), held))
        val provider   = domains(edge.provider)
        val consumer   = domains(edge.consumer)
        val failed     =
          and(requested, ite(held, or(Seq(not(asBool(value(grant))), not(provider.ready(2)))), provider.fault))
        val permission = and(held, asBool(value(grant)), provider.ready(2), not(provider.fault))
      val links = parameter.serviceEdges.map(Link(_))
      val inUse = domains.indices.map(i => or(links.filter(_.edge.provider == i).map(_.requested)))
      val wanted = domains.indices.map(i => ite(inUse(i), const(PRCMTarget.Run.ordinal, 2), bases(i)))
      val waiting = domains.indices.map: i =>
        and(
          hits(wanted(i), PRCMTarget.Run.ordinal, 2),
          or(links.filter(_.edge.consumer == i).map(l => not(l.permission)))
        )
      val failures = domains.indices.map(i => or(links.filter(_.edge.consumer == i).map(_.failed)))
      val targets = domains.indices.map: i =>
        ite(
          and(waiting(i), not(domains(i).fault)),
          ite(domains(i).powerRequest, const(PRCMTarget.Reset.ordinal, 2), const(PRCMTarget.Off.ordinal, 2)),
          wanted(i)
        )

      val coldChecks    = cold.zipWithIndex.flatMap: (register, index) =>
        require(register("clockEdge").num == 0 && register("resetPolarity").num == 1 && register("resetType").num == 1)
        val name = symbol(register)
        Vector(
          mismatch(s"${name}_clock", port(register, "clock"), values("clock")),
          mismatch(s"${name}_reset", port(register, "reset"), values("coldResetN")),
          mismatch(s"${name}_initial", port(register, "reset_value"), const(0)),
          mismatch(s"${name}_data", port(register, "d"), if index == 0 then const(1) else value(cold(index - 1)))
        )
      val domainChecks  = domains.flatMap: d =>
        val gate     = gates(d.index)
        val clock    = asBits(and(bit("clock"), asBool(value(gate))))
        val pins     = Vector(
          flagMismatch(s"domain_${d.index}_power", s"domains_${d.index}_powerRequest", d.powerRequest),
          flagMismatch(s"domain_${d.index}_isolation", s"domains_${d.index}_isolationRequest", d.isolationRequest),
          flagMismatch(s"domain_${d.index}_quiesce", s"domains_${d.index}_quiesceRequest", d.quiesceRequest),
          mismatch(s"domain_${d.index}_clock", values(s"domains_${d.index}_clock"), clock),
          mismatch(s"domain_${d.index}_reset", values(s"domains_${d.index}_resetN"), value(d.receivers.last)),
          mismatch(s"domain_${d.index}_gate_enable", port(gate, "enable"), asBits(not(bit("clock")))),
          violation(
            s"domain_${d.index}_gate_data",
            and(asBool(port(gate, "enable")), not(equal(port(gate, "d"), asBits(d.clockRequest))))
          )
        )
        val receiver = d.receivers.zipWithIndex.flatMap: (register, index) =>
          require(
            register("clockEdge").num == 0 && register("resetPolarity").num == 1 && register("resetType").num == 1
          )
          val name = symbol(register)
          Vector(
            mismatch(s"${name}_clock", port(register, "clock"), clock),
            mismatch(s"${name}_reset", port(register, "reset"), asBits(and(asBool(resetN), not(d.resetRequest)))),
            mismatch(s"${name}_initial", port(register, "reset_value"), const(0)),
            mismatch(
              s"${name}_data",
              port(register, "d"),
              if index == 0 then const(1) else value(d.receivers(index - 1))
            )
          )
        val feedback = d.feedbacks.zipWithIndex.flatMap: (register, index) =>
          management(register, 1) :+ mismatch(
            s"${symbol(register)}_data",
            port(register, "d"),
            if index == 0 then asBits(not(asBool(value(d.receivers.last)))) else value(d.feedbacks.head)
          )
        val next     = for
          phase    <- PRCMPhase.values.toVector
          target   <- PRCMTarget.values.toVector
          feedback <- (0 until 16).toVector
          fault    <- Vector(false, true)
        yield
          val f        = PRCMFeedback.fromBits(feedback)
          val selected = and(
            d.at(phase),
            hits(targets(d.index), target.ordinal, 2),
            equal(d.power, bool(f.power)),
            equal(d.reset, bool(f.reset)),
            equal(d.isolation, bool(f.isolation)),
            equal(d.idle, bool(f.idle)),
            equal(failures(d.index), bool(fault))
          )
          and(selected, not(hits(port(d.phase, "d"), PRCMSequence.step(phase, target, f, fault).ordinal, 4)))
        val retained = d.last.toVector.flatMap(s =>
          management(s, d.config.reset.target.ordinal) :+
            mismatch(s"domain_${d.index}_retained_target", port(s, "d"), d.target)
        )
        pins ++ receiver ++ feedback ++ management(d.phase, PRCMPhase.Init.ordinal) ++ retained :+
          violation(s"domain_${d.index}_next", or(next))
      val serviceChecks = links.flatMap: link =>
        val need = and(hits(wanted(link.edge.consumer), PRCMTarget.Run.ordinal, 2), not(link.consumer.fault))
        val next = for
          phase <- PRCMServicePhase.values.toVector
          bits  <- (0 until 16).toVector
        yield
          val inputs   = Vector(need, asBool(value(link.grant)), link.failed, link.consumer.released)
          val selected = and(
            (Vector(hits(value(link.phase), phase.ordinal, 2)) ++
              inputs.zipWithIndex.map((input, index) => equal(input, bool((bits & (1 << index)) != 0))))*
          )
          val expected =
            PRCMServiceSequence.step(phase, (bits & 1) != 0, (bits & 2) != 0, (bits & 4) != 0, (bits & 8) != 0)
          and(selected, not(hits(port(link.phase, "d"), expected.ordinal, 2)))
        management(link.phase, PRCMServicePhase.Idle.ordinal) ++ management(link.grant, 0) ++ Vector(
          mismatch(
            s"${symbol(link.grant)}_grant",
            port(link.grant, "d"),
            asBits(and(link.requested, link.provider.ready(2)))
          ),
          violation(s"${symbol(link.phase)}_next", or(next))
        )
      val chipChecks    = chip.toVector.flatMap: (config, _, _, last, _, selected) =>
        last.toVector.flatMap(s =>
          management(s, config.reset.code) :+ mismatch("chip_retained_target", port(s, "d"), selected)
        )

      val dataRegisters = states.filter(s => s("kind").str == "seq.firreg" && !s("reset").bool)
      val occupancy =
        states.filter(s => s("kind").str == "seq.firreg" && s("reset").bool && s("resetPolarity").num == 0)
      require(dataRegisters.size == 1 && occupancy.size == 1, "expected one MMIO response slot")
      val payload = dataRegisters.head
      val full = occupancy.head
      val payloadWidth = 1 + parameter.indexWidth + width + lanes
      require(payload("width").num == payloadWidth && full("width").num == 1)
      require(payload("clockEdge").num == 0 && full("clockEdge").num == 0 && full("resetType").num == 1)
      val expectedStates = cold.size + gates.size + domains
        .map(d => 1 + d.receivers.size + d.feedbacks.size + d.bytes.size + 1 + d.last.size)
        .sum +
        links.size * 2 + chip.map(c => c._3.size + c._4.size).getOrElse(0) + 2
      require(states.size == expectedStates, "unaccounted PRCM state")
      val events = domains.indices.map(i => register(s"domain_${i}_event"))
      case class Word(index: BigInt, data: Value, used: Int, readMask: BigInt, writeMask: BigInt)
      def bytesMask(bits: Int):                                                   BigInt        = (BigInt(1) << ((bits + 7) / 8)) - 1
      val words = parameter.banks.flatMap: bank =>
        val d       = domains(bank.index)
        val blocked = not(equal(bases(d.index), d.target))
        val done    = and(
          not(blocked),
          or(
            d.config.modes.map(mode =>
              and(hits(d.requested, mode.code, d.config.modeWidth), d.ready(mode.target.ordinal))
            )
          )
        )
        val flags   = Seq(done, not(d.legal), d.fault) ++ Option.when(bank.hasChip)(blocked) ++
          Option.when(bank.providesService)(inUse(d.index)) ++ Option.when(bank.usesService)(waiting(d.index))
        val status  = packed(flags, d.requested)
        val index   = bank.byteOffset / lanes
        Vector(
          Word(
            index,
            padded(d.requested, d.config.modeWidth),
            d.config.modeWidth,
            bytesMask(d.config.modeWidth),
            bytesMask(d.config.modeWidth)
          ),
          Word(index + 1, padded(status, d.config.modeWidth + flags.size), width, bytesMask(width), 0),
          Word(index + 2, padded(value(events(d.index)), bank.event.width), bank.event.width, 1, 1)
        )
      val chipWords = chip.toSeq.flatMap: (config, requested, _, _, legal, _) =>
        val done   = or(parameter.chipTargets.map: (mode, policies) =>
          val ready = domains.map: d =>
            policies(d.index) match
              case Some(target) =>
                and(hits(wanted(d.index), target.ordinal, 2), not(waiting(d.index)), d.ready(target.ordinal))
              case None         => or(d.config.modes.map(_.target).distinct.map(target => d.ready(target.ordinal)))
          and((Seq(hits(requested, mode.code, config.modeWidth)) ++ ready)*))
        val status = padded(packed(Seq(done, not(legal)), requested), config.modeWidth + 2)
        Vector(
          Word(
            0,
            padded(requested, config.modeWidth),
            config.modeWidth,
            bytesMask(config.modeWidth),
            bytesMask(config.modeWidth)
          ),
          Word(1, status, width, bytesMask(width), 0)
        )
      val allWords = chipWords ++ words
      val reqIndex = values("req_bits_index")
      val reqMask = values("req_bits_mask")
      val reqData = values("req_bits_data")
      val blocked = and(
        not(allowed),
        or(allWords.map: word =>
          val mask = ite(bit("req_bits_read"), const(word.readMask, lanes), const(word.writeMask, lanes))
          and(hits(reqIndex, word.index, parameter.indexWidth), not(equal(bvAnd(reqMask, mask), const(0, lanes)))))
      )
      val ready = and(not(asBool(value(full))), not(blocked))
      val accepted = and(bit("req_valid"), ready)
      val write = and(accepted, not(bit("req_bits_read")), allowed)
      val readWord = allWords.foldLeft(const(0, width))((fallback, word) =>
        ite(hits(reqIndex, word.index, parameter.indexWidth), word.data, fallback)
      )
      val backIndex = extract(value(payload), lanes + width, parameter.indexWidth)
      val backData = extract(value(payload), lanes, width)
      val backRead = extract(value(payload), payloadWidth - 1, 1)
      val backMapped = or(allWords.map(word => hits(backIndex, word.index, parameter.indexWidth)))
      val responseData = allWords.foldLeft(const(0, width)): (fallback, word) =>
        val mask = const((BigInt(1) << word.used) - 1, width)
        ite(hits(backIndex, word.index, parameter.indexWidth), bvAnd(backData, mask), fallback)
      val stored = Vector(values("req_bits_read"), reqIndex, readWord, reqMask).reduce(concat)
      val queueChecks = Vector(
        flagMismatch("mmio_ready", "req_ready", ready),
        flagMismatch("mmio_valid", "rsp_valid", asBool(value(full))),
        mismatch("mmio_read", values("rsp_bits_read"), backRead),
        mismatch("mmio_data", values("rsp_bits_data"), responseData),
        flagMismatch("mmio_error", "rsp_bits_error", not(backMapped)),
        mismatch(
          "mmio_occupancy",
          port(full, "d"),
          asBits(or(Seq(accepted, and(asBool(value(full)), not(bit("rsp_ready"))))))
        ),
        mismatch("mmio_snapshot", port(payload, "d"), ite(accepted, stored, value(payload))),
        mismatch("mmio_data_clock", port(payload, "clock"), values("clock")),
        mismatch("mmio_control_clock", port(full, "clock"), values("clock")),
        mismatch("mmio_reset", port(full, "reset"), asBits(not(asBool(resetN)))),
        mismatch("mmio_initial", port(full, "reset_value"), const(0))
      )
      def writeBytes(bytes: Vector[ujson.Value], index: BigInt, initial: BigInt): Vector[Value] =
        bytes.zipWithIndex.flatMap: (register, lane) =>
          val size      = register("width").num.toInt
          val resetByte = (initial >> (lane * 8)) & ((BigInt(1) << size) - 1)
          val selected  = and(write, hits(reqIndex, index, parameter.indexWidth), asBool(extract(reqMask, lane, 1)))
          val next      = ite(
            bit("managementReset"),
            const(resetByte, size),
            ite(selected, extract(reqData, lane * 8, size), value(register))
          )
          management(register, resetByte) :+ mismatch(s"${symbol(register)}_write", port(register, "d"), next)
      val requestChecks = parameter.banks.flatMap(bank =>
        writeBytes(domains(bank.index).bytes, bank.byteOffset / lanes, bank.domain.reset.code)
      ) ++
        chip.toVector.flatMap((config, _, bytes, _, _, _) => writeBytes(bytes, 0, config.reset.code))
      val eventChecks = parameter.banks.flatMap: bank =>
        val register = events(bank.index)
        val selected =
          and(write, hits(reqIndex, bank.byteOffset / lanes + 2, parameter.indexWidth), asBool(extract(reqMask, 0, 1)))
        val clear    = ite(selected, extract(reqData, 0, bank.event.width), const(0, bank.event.width))
        val set      =
          if bank.usesService then concat(asBits(failures(bank.index)), asBits(domains(bank.index).lost))
          else asBits(domains(bank.index).lost)
        management(register, 0) :+ mismatch(
          s"${symbol(register)}_event",
          port(register, "d"),
          bvOr(bvAnd(value(register), bvNot(clear)), set)
        )
      val feasible = query()
      val bindings = query(
        Some(
          or(coldChecks ++ domainChecks ++ serviceChecks ++ chipChecks ++ queueChecks ++ requestChecks ++ eventChecks)
        )
      )
      val resetState = states
        .filter(s => s("kind").str == "seq.firreg" && s("reset").bool)
        .map(register => equal(value(register), port(register, "reset_value")))
      val unsafeReset = or(Seq(bit("rsp_valid")) ++ domains.flatMap: d =>
        Seq(
          bit(s"domains_${d.index}_powerRequest"),
          not(bit(s"domains_${d.index}_isolationRequest")),
          not(bit(s"domains_${d.index}_quiesceRequest")),
          bit(s"domains_${d.index}_resetN")
        ))
      val coldReset = query(Some(and((Seq(not(bit("coldResetN")), unsafeReset) ++ resetState)*)))
      (feasible, bindings, coldReset)
