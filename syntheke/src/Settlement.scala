package me.jiuyang.syntheke

import scala.collection.mutable

/** Settles a design's domains and checks them before any parameter is computed.
  *
  * Every declared domain and every node membership resolves to a settled domain: a declared domain directly, a
  * reference through the node it names, a carrier input through its bind. The framework then checks what it can see
  * itself (kinds, resolution, what each bind crosses), and each kind's plugins run stage by stage. All failures of a
  * stage are reported together; a failed stage stops negotiation before the next.
  */
private[syntheke] object Settlement:
  private final class Unresolved(message: String) extends RuntimeException(message)

  def apply(spec: DesignSpec): DomainGraph =
    val failures = mutable.ArrayBuffer.empty[String]

    val nodes      = spec.nodeModules.flatMap(m => m.nodes.map(n => ModuleNodeId(m.id, n.name) -> n))
    val memberOf   = nodes.flatMap((id, n) => n.memberships.map(m => (id, m.kind) -> m)).toMap
    val sourceOf   = spec.binds.map(b => b.target -> b.source).toMap
    val declaredBy = spec.domains.toSet[Domain[?]]

    val kinds = (spec.domains.map(_.kind) ++ memberOf.keys.map(_._2) ++ nodes.flatMap(_._2.protocol.carries)).distinct
    kinds.groupBy(_.name).collect { case (name, objects) if objects.sizeIs > 1 => name }.toVector.sorted.foreach {
      name => failures += s"domain kind name '$name' denotes ${kinds.count(_.name == name)} different objects"
    }

    val settled  = mutable.Map.empty[Domain[?], Settled[?]]
    val members  = mutable.Map.empty[(ModuleNodeId, DomainKind), Settled[?]]
    val visiting = mutable.LinkedHashSet.empty[Any]

    def guarded[A](key: Any, what: => String)(body: => A): A =
      if visiting(key) then throw Unresolved(s"$what depends on itself through ${visiting.dropWhile(_ != key).mkString(" -> ")}")
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
          case Domain.Origin.Root(value)            => Settled.Origin.Root(value)
          case Domain.Origin.Derived(sources, link) => Settled.Origin.Derived(sources.map(resolve), link)
          case Domain.Origin.Imported(of)           => Settled.Origin.Imported(of)
        val result = new Settled(domain.kind, domain.id, origin, resolve)
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

    def attempt(body: => Unit): Unit =
      try body
      catch case e: Unresolved => failures += e.getMessage

    spec.domains.foreach(d => attempt(settle(d)))
    memberOf.keys.toVector.sortBy((n, k) => (n.show, k.name)).foreach((n, k) => attempt(member(n, k)))

    if failures.isEmpty then
      spec.binds.foreach { bind =>
        val protocol = spec.nodeSpec(bind.source).get.protocol
        val crossed  = memberOf.keys.collect { case (n, k) if n == bind.source || n == bind.target => k }.toVector
          .distinct
          .sortBy(_.name)
        crossed.filterNot(protocol.carries).foreach { kind =>
          (members.get(bind.source -> kind), members.get(bind.target -> kind)) match
            case (Some(a), Some(b)) =>
              protocol.accepts.find(_.kind eq kind) match
                case None         =>
                  failures += s"${bind.bindId.show}: the protocol states nothing about ${kind.name} domains"
                case Some(accept) =>
                  val relation = kind.relate(a.asInstanceOf, b.asInstanceOf)
                  if !accept.test(relation) then
                    val shown = ujson.write(upickle.default.writeJs(relation)(using kind.relationWriter))
                    failures += s"${bind.bindId.show}: the protocol does not accept crossing $a -> $b ($shown)"
            case _                  =>
              failures += s"${bind.bindId.show}: only one end is in a ${kind.name} domain"
        }
      }
    report("structure", failures.toVector)

    val graph = new DomainGraph(spec.domains.map(settled), settled.toMap, members.toMap)
    CheckStage.values.foreach { stage =>
      report(
        stage.toString,
        for
          kind  <- kinds.sortBy(_.name)
          check <- kind.checks if check.stage == stage
          error <- check.run(graph)
        yield s"${kind.name}/${check.name}: $error"
      )
    }
    graph

  private def report(stage: String, failures: Vector[String]): Unit =
    if failures.nonEmpty then
      throw NegotiationException(
        s"domain checks failed at stage $stage:" + failures.map("\n  - " + _).mkString
      )
