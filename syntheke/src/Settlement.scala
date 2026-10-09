package me.jiuyang.syntheke

import scala.collection.mutable

/** Settles a design's domains and checks them before any parameter is computed.
  *
  * Every declared domain and every node membership resolves to a settled domain: a declared domain directly, a
  * reference through the node it names, a carrier input through its bind. Then the binds are checked against what
  * their protocols carry and accept, and each kind's plugins run stage by stage. Every stage reports all of its
  * failures; a failed stage stops negotiation before the next.
  */
private[syntheke] object Settlement:
  private final class Unresolved(message: String) extends RuntimeException(message)

  def apply(spec: DesignSpec): DomainGraph =
    val nodes      = spec.nodeModules.flatMap(m => m.nodes.map(n => ModuleNodeId(m.id, n.name) -> n))
    val memberOf   = nodes.flatMap((id, n) => n.memberships.map(m => (id, m.kind) -> m)).toMap
    val sourceOf   = spec.binds.map(b => b.target -> b.source).toMap
    val declaredBy = spec.domains.toSet[Domain[?]]
    val kinds      = (spec.domains.map(_.kind) ++ memberOf.keys.map(_._2) ++ nodes.flatMap(_._2.protocol.carries)).distinct

    report(
      "domain kinds",
      kinds.groupBy(_.name).toVector.sortBy(_._1).collect {
        case (name, objects) if objects.sizeIs > 1 => s"domain kind name '$name' denotes ${objects.size} different objects"
      }
    )

    val table    = new DomainTable
    val settled  = mutable.Map.empty[Domain[?], Settled[?]]
    val members  = mutable.Map.empty[(ModuleNodeId, DomainKind), Settled[?]]
    val visiting = mutable.LinkedHashSet.empty[Any]

    def guarded[A](key: Any, what: => String)(body: => A): A =
      if visiting(key) then throw Unresolved(s"$what depends on itself")
      visiting += key
      try body
      finally visiting -= key

    def resolve(source: DomainSource[?]): Settled[?] = source match
      case domain: Domain[?] => settle(domain)
      case ref: DomainRef[?] => member(ref.node, ref.kind)

    def settle(domain: Domain[?]): Settled[?] = settled.getOrElse(
      domain,
      guarded(domain, s"domain ${domain.id.show}") {
        if !declaredBy(domain) then throw Unresolved(s"domain ${domain.id.show} is not declared in this design")
        val origin = domain.origin match
          case Domain.Origin.Derived(sources, link) => Domain.Origin.Derived(sources.map(resolve), link)
          case Domain.Origin.Root(value)            => Domain.Origin.Root(value)
          case Domain.Origin.Imported(of)           => Domain.Origin.Imported(of)
        val result = new Settled(domain.kind, domain.id, origin, table)
        settled(domain) = result
        result
      }
    )

    def member(node: ModuleNodeId, kind: DomainKind): Settled[?] = members.getOrElse(
      node -> kind,
      guarded(node -> kind, s"the ${kind.name} domain of ${node.show}") {
        val membership = memberOf.getOrElse(node -> kind, throw Unresolved(s"node ${node.show} is in no ${kind.name} domain"))
        val result     = membership.source match
          case Some(source) => resolve(source)
          case None         =>
            member(sourceOf.getOrElse(node, throw Unresolved(s"node ${node.show} has no bind to receive from")), kind)
        members(node -> kind) = result
        result
      }
    )

    def attempt(body: => Unit, loc: SourceLoc): Option[String] =
      try
        body
        None
      catch case e: Unresolved => Some(s"${e.getMessage}, at ${loc.show}")

    report(
      "domain settlement",
      spec.domains.flatMap(d => attempt(settle(d), d.loc)) ++
        memberOf.toVector.sortBy((key, _) => (key._1.show, key._2.name)).flatMap { case ((n, k), m) =>
          attempt(member(n, k), m.loc)
        }
    )

    val graph = new DomainGraph(spec.domains.map(settled), settled.toMap, members.toMap)
    table.seal(graph)

    report(
      "domain crossing",
      spec.binds.flatMap { bind =>
        val protocol = spec.nodeSpec(bind.source).get.protocol
        val crossed  = memberOf.keys.collect { case (n, k) if n == bind.source || n == bind.target => k }.toVector
          .distinct
          .filterNot(protocol.carries)
          .sortBy(_.name)
        crossed.flatMap { kind =>
          (graph.member(bind.source, kind), graph.member(bind.target, kind)) match
            case (Some(a), Some(b)) =>
              protocol.accepts.find(_.kind eq kind) match
                case None         => Some(s"${bind.id.show}: the protocol states nothing about ${kind.name} domains")
                case Some(accept) =>
                  val relation = kind.relate(a.asInstanceOf, b.asInstanceOf)
                  Option.when(!accept.test(relation)) {
                    val shown = ujson.write(upickle.default.writeJs(relation)(using kind.relationWriter))
                    s"${bind.id.show}: the protocol does not accept crossing $a -> $b ($shown)"
                  }
            case _                  => Some(s"${bind.id.show}: only one end is in a ${kind.name} domain")
        }.map(message => s"$message, at ${bind.loc.show}")
      }
    )

    CheckStage.values.foreach { stage =>
      report(
        s"domain checks ($stage)",
        for
          kind  <- kinds.sortBy(_.name)
          check <- kind.checks if check.stage == stage
          error <- check.run(graph)
        yield s"${kind.name}/${check.name}: $error"
      )
    }
    graph
