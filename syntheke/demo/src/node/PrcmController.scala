package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.demo.zaoziimpl.{*, given}
import me.jiuyang.syntheke.zaozi.zaozi

given GeneratorDefinition[PrcmControllerP] = zaozi(PrcmControllerGen)

final case class PrcmControllerNodes(clk: ClockReset.Inward, in: Axi4.Inward, private val ports: Vector[PRCMPort.Outward]):
  /** The port that drives `domain`. */
  def port(domain: Domain[PRCMDomain.type]): PRCMPort.Outward =
    ports
      .find(_.id.name == domain.id.name)
      .getOrElse(throw IllegalArgumentException(s"the PRCM '${clk.id.module.show}' does not manage ${domain.id.show}"))

object PrcmControllerNodes:
  private[demo] def build(
    name:           String,
    base:           Long,
    size:           Long,
    idCapacityBits: Int,
    controller:     Domain[PRCMDomain.type],
    managed:        Vector[Domain[PRCMDomain.type]]
  )(
    using GeneratorScope[PrcmControllerP]
  ): PrcmControllerNodes =
    require(base >= 0 && base % 4 == 0 && size >= 4 && (size & (size - 1)) == 0, "the PRCM needs an aligned power-of-two window")
    val clkDraft =
      given sourcecode.Name = sourcecode.Name("clk")
      inward(ClockReset)()
    val clock    = clkDraft.domain(ClockDomain)
    val reset    = clkDraft.domain(ResetDomain)
    val inDraft  =
      given sourcecode.Name = sourcecode.Name("in")
      inward(Axi4)(clock, reset)

    // Each managed domain runs on the management clock behind a gate, out of the reset the PRCM releases on it.
    val portDrafts = managed.map { domain =>
      val n           = domain.id.name
      val domainClock =
        given sourcecode.Name = sourcecode.Name(s"${n}Clock")
        ClockDomain.target(clock -> ClockPath(gate = Some(ClockGate(positive = true, clockDuringReset = false))))()
      val domainReset =
        given sourcecode.Name = sourcecode.Name(s"${n}Reset")
        ResetDomain.root(ResetRoot.Managed(domain, domainClock))
      given sourcecode.Name = sourcecode.Name(n)
      outward(PRCMPort)(domainClock, domainReset, domain)
    }

    val clk   = clkDraft.fixed(())
    val ports = portDrafts.map(_.fixed(()))
    val in    = inDraft.fixed(
      AxiSlavePort(
        slaves = Vector(
          AxiSlaveParams(
            name,
            AddressSet.misaligned(base, size),
            RegionType.PutEffects,
            executable = false,
            supportsWrite = TransferSizes(4, 4),
            supportsRead = TransferSizes(4, 4)
          )
        ),
        beatBytes = 4,
        idCapacityBits = idCapacityBits,
        minLatency = 1
      )
    )

    parameters { (view, graph) =>
      val shape  = shapeOf(view.edgeOf(in))
      val owner  = graph(controller)
      val drives = managed.map(graph(_))
      val ports  = PRCMDomain.ports(graph, owner)
      if !PowerDomain.tree(graph(clk.domain(PowerDomain))).alwaysOn then
        Left(Violation("the PRCM must be always on"))
      else if drives.exists(d => !(PRCMDomain.controller(d) eq owner.underlying)) || drives.size != ports.size then
        Left(Violation(s"the PRCM drives ${drives.mkString(", ")} but $owner manages ${ports.mkString(", ")}"))
      else
        val indexWidth = java.lang.Long.numberOfTrailingZeros(size) - 2
        Right(
          PrcmControllerP(
            base,
            size,
            shape,
            ResetDomain.activeLow(graph(reset)),
            PRCMDomain.parameter(graph, owner, indexWidth, shape.dataBits)
          )
        )
    }
    PrcmControllerNodes(clk, in, ports)

/** zaozi PR #159's PRCM for `controller` and the domains it manages, behind an AXI register window. */
def prcmController(
  base:           Long,
  size:           Long,
  idCapacityBits: Int,
  controller:     Domain[PRCMDomain.type],
  managed:        Vector[Domain[PRCMDomain.type]]
)(
  using
  ws:             WrapperScope,
  name:           sourcecode.Name,
  file:           sourcecode.File,
  line:           sourcecode.Line
): PrcmControllerNodes =
  generator[PrcmControllerP](PrcmControllerNodes.build(name.value, base, size, idCapacityBits, controller, managed))
