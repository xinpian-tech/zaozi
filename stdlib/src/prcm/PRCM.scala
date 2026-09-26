// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Huang Rui <vowstar@gmail.com>
package me.jiuyang.stdlib.prcm

import me.jiuyang.stdlib.*
import me.jiuyang.stdlib.default.{SynchronizedReset, SynchronizedResetParameter}
import me.jiuyang.stdlib.mmio.*
import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.ltltpe.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*
import org.llvm.circt.scalalib.dialect.firrtl.operation.RegResetPolarity

case class PRCMMode(name: String, code: BigInt, target: PRCMTarget, services: Seq[String] = Seq.empty)

given upickle.default.ReadWriter[PRCMMode] = upickle.default.macroRW

case class PRCMService(name: String, provider: String)

given upickle.default.ReadWriter[PRCMService] = upickle.default.macroRW

given mainargs.TokensReader.Simple[PRCMService]:
  def shortName = "service"
  def read(strs: Seq[String]): Right[Nothing, PRCMService] =
    Right(upickle.default.read[PRCMService](strs.head))

case class PRCMManagedDomain(name: String, modes: Seq[PRCMMode], resetMode: String, resetStages: Int):
  require(name.nonEmpty, "domain name must not be empty")
  require(modes.forall(_.name.nonEmpty), s"domain $name has an empty mode name")
  require(modes.map(_.name).distinct.size == modes.size, s"domain $name has duplicate mode names")
  require(modes.forall(_.code >= 0), s"domain $name mode codes must be nonnegative")
  require(modes.map(_.code).distinct.size == modes.size, s"domain $name has duplicate mode codes")
  require(modes.exists(_.target == PRCMTarget.Off), s"domain $name needs an Off mode for recovery")
  val reset        = modes
    .find(_.name == resetMode)
    .getOrElse(
      throw new IllegalArgumentException(s"domain $name reset mode $resetMode is not declared")
    )
  val modeWidth    = modes.map(_.code.bitLength).max.max(1)
  val action       = PRCMDomainParameter(resetStages)
  val serviceNames = modes.find(_.target == PRCMTarget.Run).toSeq.flatMap(_.services).distinct.sorted
  require(
    modes.filter(_.target != PRCMTarget.Run).forall(_.services.isEmpty),
    s"domain $name only uses services in Run"
  )
  require(
    modes.filter(_.target == PRCMTarget.Run).forall(_.services.toSet == serviceNames.toSet),
    s"domain $name Run modes must use the same services"
  )

given upickle.default.ReadWriter[PRCMManagedDomain] = upickle.default.macroRW

given mainargs.TokensReader.Simple[PRCMManagedDomain]:
  def shortName = "domain"
  def read(strs: Seq[String]): Right[Nothing, PRCMManagedDomain] =
    Right(upickle.default.read[PRCMManagedDomain](strs.head))

private[prcm] case class PRCMServiceEdge(consumer: Int, provider: Int, services: Seq[String])

private[prcm] case class PRCMRegisterBank(
  index:           Int,
  domain:          PRCMManagedDomain,
  byteOffset:      BigInt,
  dataWidth:       Int,
  hasChip:         Boolean,
  providesService: Boolean,
  usesService: Boolean):
  val request     = (0 until domain.modeWidth by 8).map: bit =>
    RegField(s"domain_${index}_request_${bit / 8}", (domain.modeWidth - bit).min(8)).readReadyValid.writeReadyValid
  val status      = RegField(s"domain_${index}_status", dataWidth).readReadyValid
  val event       = RegField(s"domain_${index}_event", if usesService then 2 else 1).readReadyValid.writeReadyValid
  val statusFlags = Seq("done", "invalid", "fault") ++
    Option.when(hasChip)("blocked_by_chip") ++ Option.when(providesService)("in_use") ++ Option.when(usesService)(
      "wait_service"
    )
  val registers   = Seq(
    RegMapRegister(byteOffset, request),
    RegMapRegister(byteOffset + dataWidth / 8, Seq(status)),
    RegMapRegister(byteOffset + 2 * (dataWidth / 8), Seq(event))
  )

case class PRCMParameter(
  indexWidth:      Int,
  dataWidth:       Int,
  coldResetStages: Int,
  domains:         Seq[PRCMManagedDomain],
  services:        Seq[PRCMService] = Seq.empty,
  chip:            Option[PRCMChip] = None)
    extends Parameter:
  require(indexWidth > 0, "indexWidth must be positive")
  require(dataWidth > 0 && dataWidth % 8 == 0, "dataWidth must be a positive multiple of 8")
  require(domains.nonEmpty, "PRCM needs at least one domain")
  val domainWordBase              = if chip.isDefined then 2 else 0
  val wordCount                   = BigInt(domains.size) * 3 + domainWordBase
  require((wordCount - 1).bitLength <= indexWidth, "PRCM registers do not fit the word index")
  require(domains.map(_.name).distinct.size == domains.size, "domain names must be unique")
  require(services.forall(_.name.nonEmpty), "service names must not be empty")
  require(services.map(_.name).distinct.size == services.size, "service names must be unique")
  services.foreach: service =>
    require(
      domains.exists(domain => domain.name == service.provider && domain.modes.exists(_.target == PRCMTarget.Run)),
      s"service ${service.name} provider ${service.provider} must have a Run mode"
    )
  private val serviceByName       = services.map(service => service.name -> service).toMap
  val coldReceiver                = SynchronizedResetParameter(coldResetStages, RegResetPolarity.NegReset)
  val orderedDomains              = domains.sortBy(_.name)
  private[prcm] val serviceEdges  = orderedDomains.zipWithIndex.flatMap: (domain, consumer) =>
    val needs = domain.serviceNames.map: name =>
      serviceByName.getOrElse(
        name,
        throw new IllegalArgumentException(s"domain ${domain.name} service $name is not declared")
      )
    needs
      .groupBy(_.provider)
      .toSeq
      .sortBy(_._1)
      .map: (provider, required) =>
        PRCMServiceEdge(consumer, orderedDomains.indexWhere(_.name == provider), required.map(_.name).sorted)
  private val consumers           = serviceEdges.map(_.consumer).toSet
  private val providers           = serviceEdges.map(_.provider).toSet
  require((consumers intersect providers).isEmpty, "a service provider cannot also consume a service")
  private[prcm] val chipTargets   = chip.toSeq.flatMap(_.targets(orderedDomains))
  chip.foreach: config =>
    require(BigInt(config.modeWidth) + 2 <= dataWidth, "chip status does not fit the data word")
  chipTargets.foreach: (mode, targets) =>
    serviceEdges.foreach: edge =>
      require(
        !targets(edge.provider).exists(_ != PRCMTarget.Run) || targets(edge.consumer).exists(_ != PRCMTarget.Run),
        s"chip mode ${mode.name} prevents provider ${orderedDomains(edge.provider).name} from serving ${orderedDomains(edge.consumer).name}"
      )
  private[prcm] val chipWitnesses = chipTargets.map: (mode, targets) =>
    val initial = targets.map(_.getOrElse(PRCMTarget.Off))
    val needed  = serviceEdges.filter(edge => initial(edge.consumer) == PRCMTarget.Run).map(_.provider).toSet
    mode.name -> initial.zipWithIndex.map((target, index) => if needed(index) then PRCMTarget.Run else target)
  orderedDomains.zipWithIndex.foreach: (domain, index) =>
    val flags =
      3 + (if chip.isDefined then 1 else 0) + (if providers(index) then 1 else 0) + (if consumers(index) then 1 else 0)
    require(BigInt(domain.modeWidth) + flags <= dataWidth, s"domain ${domain.name} status does not fit the data word")
  val windowBytes                 = wordCount * (dataWidth / 8)
  val verification                = Layer("Verification")
  private[prcm] val banks         = orderedDomains.zipWithIndex.map: (domain, index) =>
    PRCMRegisterBank(
      index,
      domain,
      (BigInt(index) * 3 + domainWordBase) * (dataWidth / 8),
      dataWidth,
      chip.isDefined,
      providers(index),
      consumers(index)
    )
  private[prcm] val chipBank      = chip.map(PRCMChipRegisterBank(_, dataWidth))
  val regMap                      = RegMapDefinition(
    indexWidth,
    dataWidth,
    1,
    true,
    verification,
    chipBank.toSeq.flatMap(_.registers) ++ banks.flatMap(_.registers)
  )

  def header: String =
    def encode(name: String):                  String      = name.flatMap:
      case c if c <= 127 && c.isLetterOrDigit => c.toString
      case c                                  => f"_${c.toInt}%04x"
    def constant(name: String, value: BigInt): Seq[String] =
      if value.bitLength <= 64 then Seq(s"#define PRCM_$name 0x${value.toString(16)}ULL")
      else
        val words = (value.bitLength - 1) / 32 + 1
        Seq(s"#define PRCM_${name}_WORDS $words") ++ (0 until words).map: index =>
          s"#define PRCM_${name}_WORD_$index 0x${((value >> (index * 32)) & 0xffffffffL).toString(16)}U"
    val common = Seq(
      "DATA_WIDTH"  -> BigInt(dataWidth),
      "INDEX_WIDTH" -> BigInt(indexWidth),
      "APERTURE"    -> windowBytes
    )
    val chipConstants = chip.toSeq.flatMap: config =>
      Seq(
        "CHIP_REQUEST_OFFSET"     -> BigInt(0),
        "CHIP_STATUS_OFFSET"      -> BigInt(dataWidth / 8),
        "CHIP_REQUEST_MASK"       -> ((BigInt(1) << config.modeWidth) - 1),
        "CHIP_REQUEST_RESET"      -> config.reset.code,
        "CHIP_STATUS_DONE_BIT"    -> BigInt(config.modeWidth),
        "CHIP_STATUS_INVALID_BIT" -> BigInt(config.modeWidth + 1)
      ) ++ config.modes.map(mode => s"CHIP_MODE_${encode(mode.name)}" -> mode.code)
    val domainConstants = banks.flatMap: bank =>
      val prefix = s"DOMAIN_${encode(bank.domain.name)}"
      val width  = bank.domain.modeWidth
      Seq(
        s"${prefix}_PORT"           -> BigInt(bank.index),
        s"${prefix}_REQUEST_OFFSET" -> bank.byteOffset,
        s"${prefix}_STATUS_OFFSET"  -> (bank.byteOffset + dataWidth / 8),
        s"${prefix}_EVENT_OFFSET"   -> (bank.byteOffset + 2 * (dataWidth / 8)),
        s"${prefix}_REQUEST_MASK"   -> ((BigInt(1) << width) - 1),
        s"${prefix}_REQUEST_RESET"  -> bank.domain.reset.code
      ) ++ bank.statusFlags.zipWithIndex.map: (flag, index) =>
        s"${prefix}_STATUS_${flag.toUpperCase(java.util.Locale.ROOT)}_BIT" -> BigInt(width + index)
      ++ Seq(s"${prefix}_EVENT_POWER_LOST_BIT" -> BigInt(0))
        ++ Option.when(bank.usesService)(s"${prefix}_EVENT_SERVICE_LOST_BIT" -> BigInt(1))
        ++ bank.domain.modes.map(mode => s"${prefix}_MODE_${encode(mode.name)}" -> mode.code)
    (common ++ chipConstants ++ domainConstants).flatMap(constant).mkString("\n") + "\n"

  def bindings: ujson.Value =
    val common    = Seq(
      "domains"  -> ujson.Arr.from(banks.map(bank => ujson.Obj("name" -> bank.domain.name, "port" -> bank.index))),
      "services" -> ujson.Arr.from(serviceEdges.map: edge =>
        ujson.Obj(
          "consumer" -> orderedDomains(edge.consumer).name,
          "provider" -> orderedDomains(edge.provider).name,
          "names"    -> ujson.Arr.from(edge.services)
        ))
    )
    val witnesses = Option.when(chip.isDefined)(
      "chip" -> ujson.Arr.from(chipWitnesses.map: (mode, targets) =>
        ujson.Obj(
          "mode"    -> mode,
          "witness" -> ujson.Obj.from(
            orderedDomains.zip(targets).map((domain, target) => domain.name -> ujson.Str(target.toString))
          )
        ))
    )
    ujson.Obj.from(common ++ witnesses)

given upickle.default.ReadWriter[PRCMParameter] = upickle.default.macroRW

class PRCMLayers(parameter: PRCMParameter) extends LayerInterface(parameter):
  def layers = Seq(parameter.verification)

class PRCMDomainPort extends Bundle:
  val powerGood        = Flipped(Bool())
  val isolationActive  = Flipped(Bool())
  val idle             = Flipped(Bool())
  val powerRequest     = Aligned(Bool())
  val isolationRequest = Aligned(Bool())
  val quiesceRequest   = Aligned(Bool())
  val clock            = Aligned(Clock())
  val resetN           = Aligned(Reset())

class PRCMIO(parameter: PRCMParameter) extends HWBundle(parameter):
  val clock           = Flipped(Clock())
  val coldResetN      = Flipped(Reset())
  val managementReset = Flipped(Bool())
  val req             = Flipped(Decoupled(new RegMapRequest(parameter.indexWidth, parameter.dataWidth)))
  val rsp             = Aligned(Decoupled(new RegMapResponse(parameter.dataWidth, true)))
  // Domain ports and register banks use ascending domain-name order.
  val domains         = Aligned(Vec(parameter.domains.size, new PRCMDomainPort))

class PRCMProbe(parameter: PRCMParameter) extends DVBundle[PRCMParameter, PRCMLayers](parameter)

@generator
object PRCM extends Generator[PRCMParameter, PRCMLayers, PRCMIO, PRCMProbe]:
  def main(args: Array[String]): Unit = args.toList match
    case "prove" :: config :: design :: directory :: timeout :: Nil        =>
      val parameter = upickle.default.read[PRCMParameter](os.read(os.Path(config, os.pwd)))
      if !PRCMProof.writeReport(parameter, os.Path(design, os.pwd), os.Path(directory, os.pwd), timeout.toInt) then
        sys.exit(1)
    case "prove-domain" :: config :: design :: directory :: timeout :: Nil =>
      val parameter = upickle.default.read[PRCMDomainParameter](os.read(os.Path(config, os.pwd)))
      if !PRCMProof.writeDomainReport(parameter, os.Path(design, os.pwd), os.Path(directory, os.pwd), timeout.toInt)
      then sys.exit(1)
    case "prove-model" :: config :: directory :: timeout :: Nil            =>
      val parameter = upickle.default.read[PRCMParameter](os.read(os.Path(config, os.pwd)))
      if !PRCMProof.writeModelReport(parameter, os.Path(directory, os.pwd), timeout.toInt) then sys.exit(1)
    case "header" :: config :: Nil                                         =>
      print(upickle.default.read[PRCMParameter](os.read(os.Path(config, os.pwd))).header)
    case "bindings" :: config :: Nil                                       =>
      println(upickle.default.read[PRCMParameter](os.read(os.Path(config, os.pwd))).bindings.render())
    case _                                                                 => this.mainImpl(args)

  def architecture(parameter: PRCMParameter) =
    val io = summon[Interface[PRCMIO]]
    def named[T](name: String)(body: sourcecode.Name.Machine ?=> T): T =
      body(
        using sourcecode.Name.Machine(name)
      )
    given ClockScope = ClockScope.posedge(io.clock)
    val cold         = SynchronizedReset.instantiate(parameter.coldReceiver)
    cold.io.clock := io.clock
    cold.io.reset := io.coldResetN
    given ResetScope  = ResetScope.asyncActiveLow(cold.io.synchronizedReset)
    val accessAllowed = cold.io.synchronizedReset.asBool & !io.managementReset

    val controllers       = parameter.orderedDomains.zipWithIndex.map: (config, index) =>
      named(s"domain_$index")(PRCMDomain.instantiate(config.action))
    val wanted            = Seq.fill(controllers.size)(Wire(Bits(2)))
    val waitingForService = Seq.fill(controllers.size)(Wire(Bool()))
    val chipControl       = parameter.chipBank.map: bank =>
      val bytes   = bank.request.zipWithIndex.map: (field, index) =>
        val initial    = (bank.chip.reset.code >> (index * 8)) & ((BigInt(1) << field.width) - 1)
        val value      = named(s"chip_request_$index")(RegInit(initial.B(field.width)))
        val readReady  = Wire(Bool())
        val writeValid = Wire(Bool())
        val writeData  = Wire(Bits(field.width))
        when(io.managementReset) {
          value := initial.B(field.width)
        }.otherwise {
          when(writeValid & accessAllowed) { value := writeData }
        }
        (value, Seq(field.read(readReady, accessAllowed, value), field.write(accessAllowed, writeValid, writeData)))
      val request = bytes.reverse.map(_._1: Referable[Bits]).reduce(_ ## _)
      val legal   = bank.chip.modes.map(mode => request === mode.code.B(bank.chip.modeWidth)).reduce(_ | _)
      val last    = named("chip_target")(RegInit(bank.chip.reset.code.B(bank.chip.modeWidth)))
      when(legal) { last := request }
      val target  = legal ? (request, last)
      (request, target, legal, bytes.flatMap(_._2))
    val links             = parameter.serviceEdges.map: edge =>
      val consumer  = controllers(edge.consumer)
      val provider  = controllers(edge.provider)
      val handshake = named(s"service_${edge.consumer}_${edge.provider}")(
        PRCMServiceHandshake.instantiate(PRCMServiceHandshakeParameter())
      )
      val grant     = named(s"grant_${edge.consumer}_${edge.provider}")(RegInit(false.B))
      grant := handshake.io.request & provider.io.readyRun
      val failure    = handshake.io.request &
        (handshake.io.held ? ((!grant | !provider.io.readyRun), provider.io.fault))
      val permission = handshake.io.held & grant & provider.io.readyRun & !provider.io.fault
      handshake.io.clock      := io.clock
      handshake.io.coldResetN := cold.io.synchronizedReset
      handshake.io.need       := (wanted(edge.consumer) === BigInt(PRCMTarget.Run.ordinal).B(2)) & !consumer.io.fault
      handshake.io.grant      := grant
      handshake.io.fault      := failure
      handshake.io.released   := consumer.io.released
      (edge, handshake.io.request, permission, failure)

    val accesses     = parameter.banks.flatMap: bank =>
      val config         = bank.domain
      val domain         = controllers(bank.index)
      val incoming       = links.filter(_._1.provider == bank.index)
      val outgoing       = links.filter(_._1.consumer == bank.index)
      val inUse          = incoming.map(_._2).foldLeft(false.B: Referable[Bool])(_ | _)
      val serviceFailure = outgoing.map(_._4).foldLeft(false.B: Referable[Bool])(_ | _)
      val missing        = outgoing.map(link => !link._3).foldLeft(false.B: Referable[Bool])(_ | _)
      val waitService    = (wanted(bank.index) === BigInt(PRCMTarget.Run.ordinal).B(2)) & missing
      waitingForService(bank.index) := waitService
      val port = io.domains(bank.index)
      domain.io.clock           := io.clock
      domain.io.coldResetN      := cold.io.synchronizedReset
      domain.io.powerGood       := port.powerGood
      domain.io.isolationActive := port.isolationActive
      domain.io.idle            := port.idle
      domain.io.serviceFault    := serviceFailure
      port.powerRequest         := domain.io.powerRequest
      port.isolationRequest     := domain.io.isolationRequest
      port.quiesceRequest       := domain.io.quiesceRequest
      port.clock                := domain.io.domainClock
      port.resetN               := domain.io.domainResetN

      val requestBytes = bank.request.zipWithIndex.map: (field, index) =>
        val initial    = (config.reset.code >> (index * 8)) & ((BigInt(1) << field.width) - 1)
        val value      = named(s"domain_${bank.index}_request_$index")(RegInit(initial.B(field.width)))
        val readReady  = Wire(Bool())
        val writeValid = Wire(Bool())
        val writeData  = Wire(Bits(field.width))
        when(io.managementReset) {
          value := initial.B(field.width)
        }.otherwise {
          when(writeValid & accessAllowed) { value := writeData }
        }
        (value, Seq(field.read(readReady, accessAllowed, value), field.write(accessAllowed, writeValid, writeData)))
      val request      = requestBytes.reverse.map(_._1: Referable[Bits]).reduce(_ ## _)
      val legal        = config.modes.map(mode => request === mode.code.B(config.modeWidth)).reduce(_ | _)
      val lastTarget   = named(s"domain_${bank.index}_target")(RegInit(BigInt(config.reset.target.ordinal).B(2)))
      val target       = Wire(Bits(2))
      target := lastTarget
      config.modes.foreach: mode =>
        when(request === mode.code.B(config.modeWidth)) { target := BigInt(mode.target.ordinal).B(2) }
      lastTarget := target
      val base = Wire(Bits(2))
      base := target
      chipControl.foreach: (_, chipTarget, _, _) =>
        parameter.chipTargets.foreach: (mode, targets) =>
          targets(bank.index).foreach: fixed =>
            when(chipTarget === mode.code.B(chipTarget.width)) { base := BigInt(fixed.ordinal).B(2) }
      val blockedByChip = base =/= target
      wanted(bank.index) := inUse ? (BigInt(PRCMTarget.Run.ordinal).B(2), base)
      domain.io.target   := (waitService & !domain.io.fault) ? (
        domain.io.powerRequest ? (BigInt(PRCMTarget.Reset.ordinal).B(2), BigInt(PRCMTarget.Off.ordinal).B(2)),
        wanted(bank.index)
      )
      val complete    = config.modes.map: mode =>
        val ready = mode.target match
          case PRCMTarget.Off   => domain.io.readyOff
          case PRCMTarget.Reset => domain.io.readyReset
          case PRCMTarget.Run   => domain.io.readyRun
        (request === mode.code.B(config.modeWidth)) & ready
      val done        = complete.reduce(_ | _) & !blockedByChip
      val flags       = Seq[Referable[Bool]](done, !legal, domain.io.fault) ++
        Option.when(bank.hasChip)(blockedByChip) ++ Option.when(bank.providesService)(inUse) ++ Option.when(
          bank.usesService
        )(waitService)
      val status      =
        (flags.reverse.map(value => value.asBits: Referable[Bits]).reduce(_ ## _) ## request).pad(parameter.dataWidth)
      val statusReady = Wire(Bool())

      val event           = named(s"domain_${bank.index}_event")(RegInit(BigInt(0).B(bank.event.width)))
      val eventReadReady  = Wire(Bool())
      val eventWriteValid = Wire(Bool())
      val eventWriteData  = Wire(Bits(bank.event.width))
      val eventClear      = eventWriteValid & accessAllowed
      val newEvents       =
        if bank.usesService then serviceFailure.asBits ## domain.io.powerLost.asBits
        else domain.io.powerLost.asBits
      event := (event & ~(eventClear ? (eventWriteData, BigInt(0).B(bank.event.width)))) | newEvents

      layer("Verification"):
        given ClockEvent = posedge(io.clock)
        val enabled      = cold.io.synchronizedReset.asBool
        Assert(domain.io.powerLost.S |=> event.bit(0).S, enabled, s"domain_${bank.index}_power_loss_recorded")
        if bank.hasChip then
          Assert(blockedByChip.S |-> (!done).S, enabled, s"domain_${bank.index}_chip_blocks_local_completion")
          Cover(blockedByChip.S, enabled, s"domain_${bank.index}_blocked_by_chip")
        if bank.usesService then
          Assert(serviceFailure.S |=> event.bit(1).S, enabled, s"domain_${bank.index}_service_loss_recorded")
          Cover(waitService.S, enabled, s"domain_${bank.index}_waiting_for_service")
        if bank.providesService then Cover(inUse.S, enabled, s"domain_${bank.index}_providing_service")
        if BigInt(config.modes.size) < (BigInt(1) << config.modeWidth) then
          Cover((!legal).S, enabled, s"domain_${bank.index}_invalid_mode")
        Cover(
          (eventClear & eventWriteData.bit(0) & domain.io.powerLost).S,
          enabled,
          s"domain_${bank.index}_set_wins_clear"
        )

      requestBytes.flatMap(_._2) ++ Seq(
        bank.status.read(statusReady, accessAllowed, status),
        bank.event.read(eventReadReady, accessAllowed, event),
        bank.event.write(accessAllowed, eventWriteValid, eventWriteData)
      )
    val chipAccesses = chipControl.toSeq.flatMap: (request, _, legal, writes) =>
      val complete  = parameter.chipTargets.map: (mode, targets) =>
        val domainsReady = parameter.orderedDomains.zipWithIndex.map: (config, index) =>
          val domain    = controllers(index)
          val readiness = Seq(domain.io.readyOff, domain.io.readyReset, domain.io.readyRun)
          targets(index) match
            case Some(target) =>
              (wanted(index) === BigInt(target.ordinal).B(2)) & !waitingForService(index) & readiness(target.ordinal)
            case None         =>
              config.modes
                .map(_.target)
                .distinct
                .map(target => readiness(target.ordinal): Referable[Bool])
                .reduce(_ | _)
        (request === mode.code.B(request.width)) & domainsReady.reduce(_ & _)
      val done      = complete.reduce(_ | _)
      val status    = ((!legal).asBits ## done.asBits ## request).pad(parameter.dataWidth)
      val readReady = Wire(Bool())
      val bank      = parameter.chipBank.get
      layer("Verification"):
        given ClockEvent = posedge(io.clock)
        val enabled      = cold.io.synchronizedReset.asBool
        if BigInt(bank.chip.modes.size) < (BigInt(1) << bank.chip.modeWidth) then
          Cover((!legal).S, enabled, "chip_invalid_mode")
        Cover(done.S, enabled, "chip_policy_complete")
      writes :+ bank.status.read(readReady, accessAllowed, status)
    parameter.regMap(io.req, io.rsp)((chipAccesses ++ accesses)*)

    layer("Verification"):
      given ClockEvent = posedge(io.clock)
      Cover(io.managementReset.S, cold.io.synchronizedReset.asBool, "management_reset")
