package me.jiuyang.syntheke

private[syntheke] object DomainChecks:
  /** `instances` are the domains this design holds for the designs it instantiates. */
  def apply(spec: DesignSpec, instances: Vector[Domain[?]]): Unit =
    val nodes = spec.nodeModules.flatMap(m => m.nodes.map(n => ModuleNodeId(m.id, n.name) -> n))
    val known = spec.domains ++ instances
    report(
      "domains",
      spec.domains.flatMap(d =>
        d.references
          .filterNot(r => known.exists(_ eq r))
          .map(r => s"$d names $r, a domain of another design, at ${d.loc.show}")
      ) ++
        nodes.flatMap((id, n) =>
          n.domains
            .filterNot(d => known.exists(_ eq d))
            .map(d => s"node ${id.show} is in $d, a domain of another design, at ${n.loc.show}")
        )
    )

    report(
      "domain crossing",
      crossings(spec).flatMap { crossing =>
        val bind     = crossing.bind
        val protocol = spec.nodeSpec(bind.source).get.protocol
        val kind     = crossing.kind
        val failure  = (crossing.source, crossing.target) match
          case (Some(a), Some(b)) if protocol.carries.contains(kind) =>
            Option.when(!(a eq b))(s"${bind.id.show} carries ${kind.name} domains, yet joins $a and $b")
          case (Some(a), Some(b))                                    =>
            protocol.accepts.find(_.kind eq kind) match
              case None         => Some(s"${bind.id.show}: the protocol states nothing about ${kind.name} domains")
              case Some(accept) =>
                Option.when(!accept.test(a, b))(s"${bind.id.show}: the protocol does not accept crossing $a -> $b")
          case _                                                     => Some(s"${bind.id.show}: only one end is in a ${kind.name} domain")
        failure.map(message => s"$message, at ${bind.loc.show}")
      }
    )

  /** What a bind crosses: for every kind either end is in, the domains of its two ends. */
  private[syntheke] final case class Crossing(
    bind:   BindDecl,
    kind:   DomainKind,
    source: Option[Domain[?]],
    target: Option[Domain[?]])

  private[syntheke] def crossings(spec: DesignSpec): Vector[Crossing] =
    for
      bind <- spec.binds
      kind <-
        Vector(bind.source, bind.target).flatMap(spec.nodeSpec(_).toVector.flatMap(_.domains.map(_.kind))).distinct
    yield Crossing(bind, kind, spec.domainOf(bind.source, kind), spec.domainOf(bind.target, kind))
