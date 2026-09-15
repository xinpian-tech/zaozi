package me.jiuyang.syntheke

import scala.collection.immutable.SortedSet
import scala.collection.mutable

final class NegotiationException(message: String) extends RuntimeException(message)

object Negotiator:

  private final case class DomainReadyDesign(
    spec:            DesignSpec,
    active:          Vector[(BindDecl, DomainKey)],
    domains:         Vector[ResolvedDomain],
    attachments:     Vector[ResolvedDomainAttachment],
    constraints:     Vector[ConstraintSpec]):
    val domainById: Map[DomainDeclId, ResolvedDomain] = domains.map(d => d.id -> d).toMap
    val attachmentByKey: Map[NodeDomainKey, ResolvedDomainAttachment] = attachments.map(a => a.key -> a).toMap

  private final case class Propagated(
    down:        Map[ModuleNodeId, Any],
    up:          Map[ModuleNodeId, Any],
    constraints: Vector[ConstraintSpec])

  private final case class SettledEdge(bind: BindDecl, protocol: Protocol, down: Any, up: Any, edge: Any)

  private final case class Settled(edges: Vector[SettledEdge], constraints: Vector[ConstraintSpec])

  def negotiate(spec: DesignSpec): ResolvedDesign =
    val canonical                          = beforeDomainReady("Freeze/canonical domains")(canonicalDomains(spec))
    beforeDomainReady("Freeze/structure")(structuralCheck(spec))
    beforeDomainReady("Freeze/domain declarations")(frozenDomainCheck(spec, canonical))
    val domainReady                        = negotiateDomains(spec)
    val order                              = parameterTopology(spec)
    val propagated                         = propagate(domainReady, order)
    val settled                            = settle(domainReady, propagated)
    val constraints                        = domainReady.constraints ++ spec.constraints ++ propagated.constraints ++ settled.constraints
    val (domains, checks)                  = validateConstraints(domainReady, canonical, constraints)
    val edges                              = interfaces(settled.edges)
    val (generators, probes, observations) = assembleViews(domainReady, edges)
    val (ports, wires, layers)             = Planner.plan(spec, edges, probes, observations)
    ResolvedDesign(
      spec = spec,
      domains = domains,
      domainAttachments = domainReady.attachments,
      domainChecks = checks,
      constraints = constraints,
      edges = edges,
      generatorModules = generators,
      portPlans = ports,
      wirePlans = wires,
      layerDecls = layers,
      probes = probes,
      observations = observations
    )

  private def fail(message: String): Nothing = throw NegotiationException(message)

  private def beforeDomainReady[A](stage: String)(body: => A): A =
    try body
    catch
      case error: NegotiationException =>
        fail(
          s"$stage failed before DomainReadyDesign; dFn, uFn, Protocol.negotiate, design Protocol.interface, " +
            s"FullParam and public Probe interface callbacks were not invoked: ${error.getMessage}"
        )

  private def at(locs: (sourcecode.File, sourcecode.Line)*): String = locs.map(_.show).mkString(", ")

  private def at(locs: Vector[(sourcecode.File, sourcecode.Line)]): String = locs.map(_.show).mkString(", ")

  private def domainOrder(key: DomainKey): (String, String) =
    (key.namespace, key.name)


  private def canonicalDomains(spec: DesignSpec): Map[DomainKey, Domain] =
    val table = mutable.LinkedHashMap.empty[DomainKey, (Domain, String)]

    def register(domain: Domain, where: String): Unit =
      table.get(domain.key) match
        case Some((first, firstWhere)) if !(first eq domain) =>
          fail(s"domain key ${domain.key.show} denotes different objects at $firstWhere and $where")
        case Some(_)                                       => ()
        case None                                          => table(domain.key) = domain -> where

    def readable(token: DomainReadable[?], where: String): Unit = register(token.domain, where)

    spec.domainDecls.foreach { d =>
      register(d.domain, s"domain ${d.id.show}")
      d.reads.foreach(readable(_, s"domain ${d.id.show} source read set"))
    }
    spec.constraints.foreach { c =>
      c.reads.foreach(readable(_, s"constraint from ${c.source}, at ${at(c.loc)}"))
    }
    spec.generatorModules.foreach { g =>
      g.nodes.foreach { n =>
        val nodeId = ModuleNodeId(g.id, n.name)
        n.nodeDomains.foreach { u =>
          register(u.domain, s"domain use ${u.key.show}")
        }
        n.computation.readPlan.tokens.collect { case u: NodeDomain[?] => u }.foreach { u =>
          register(u.domain, s"read plan of ${nodeId.show}")
        }
        n.protocol.carries.foreach(domain => register(domain, s"carried domain of ${nodeId.show}"))
      }
    }

    table.view.mapValues(_._1).toMap

  private def requireCanonical(canonical: Map[DomainKey, Domain], domain: Domain, where: String): Unit =
    canonical.get(domain.key) match
      case Some(found) if found eq domain => ()
      case Some(_)                      => fail(s"$where uses a non-canonical object for ${domain.key.show}")
      case None                         => fail(s"$where uses unregistered domain ${domain.key.show}")

  private def structuralCheck(spec: DesignSpec): Unit =
    spec.generatorModules.foreach { generator =>
      val names = generator.nodes.map(_.name) ++ generator.probes.map(_.node.id.name)
      if names.distinct.size != names.size then
        fail(s"generator ${generator.id.show} has duplicate local node names")
      generator.probes.foreach { declared =>
        if !(declared.node.owner eq spec.owner) || declared.node.id.module != generator.id then
          fail(s"local Probe ${declared.node.id.show} does not belong to generator ${generator.id.show}, at ${at(declared.loc)}")
      }
    }
    spec.generatorModules.filterNot(g => spec.testbench.contains(g.id)).foreach { generator =>
      generator.definition match
        case _: TestbenchDefinition[?] =>
          fail(s"${generator.id.show} uses a TestbenchDefinition and must be declared with testbench")
        case _ => ()
      generator.parameters match
        case ParameterComputation.Ordinary(_) => ()
        case ParameterComputation.Observed(_) =>
          fail(s"observedParameters is only available to the testbench, not ${generator.id.show}")
    }
    spec.testbench.flatMap(spec.generatorModule).foreach { testbench =>
      testbench.definition match
        case _: TestbenchDefinition[?] => ()
        case _ => fail(s"testbench ${testbench.id.show} requires a TestbenchDefinition")
      if testbench.probes.nonEmpty then
        fail(s"testbench ${testbench.id.show} consumes the DUT Probe catalog and cannot declare local Probe sources")
      if testbench.dependencies.nonEmpty then
        fail(
          s"testbench ${testbench.id.show} has fixed boundaries and cannot declare functional parameter dependencies"
        )
      testbench.nodes.foreach { node =>
        node.computation match
          case NodeComputation.Constant(_)   => ()
          case NodeComputation.Derived(_, _) =>
            fail(s"testbench boundary ${testbench.id.show}#${node.name} must use fixed(value), at ${at(node.loc)}")
      }
    }
    spec.generators.foldLeft(Set.empty[String]) { (seen, e) =>
      if seen(e.name) then
        fail(s"generator name '${e.name}' used by ${spec.generators.count(_.name == e.name)} distinct definitions")
      seen + e.name
    }

    spec.moduleOrder.flatMap(spec.wrapper).foldLeft(Map.empty[String, ModuleId]) { (seen, w) =>
      seen.get(w.moduleName) match
        case Some(first) =>
          fail(
            s"wrapper module name '${w.moduleName}' declared by both ${first.show} and ${w.id.show}, at ${at(w.loc)}"
          )
        case None        => seen + (w.moduleName -> w.id)
    }

    spec.binds.foreach { b =>
      val source = spec
        .nodeSpec(b.source)
        .getOrElse(fail(s"bind source ${b.source.show} is not a node of this design, at ${at(b.loc)}"))
      val target = spec
        .nodeSpec(b.target)
        .getOrElse(fail(s"bind target ${b.target.show} is not a node of this design, at ${at(b.loc)}"))
      if source.direction != NodeDirection.Outward then
        fail(s"bind source ${b.source.show} is ${source.direction}, expected outward, at ${at(b.loc, source.loc)}")
      if target.direction != NodeDirection.Inward then
        fail(s"bind target ${b.target.show} is ${target.direction}, expected inward, at ${at(b.loc, target.loc)}")
      if !(source.protocol eq target.protocol) then
        fail(
          s"bind ${b.bindId.show} uses different protocol objects at its endpoints, at ${at(b.loc, source.loc, target.loc)}"
        )
      if !(b.declaredIn.isAncestorOf(b.source.module) && b.declaredIn.isAncestorOf(b.target.module)) then
        fail(
          s"bind ${b.source.show} -> ${b.target.show} declared in ${b.declaredIn.show}, " +
            s"which is not an ancestor of both endpoints, at ${at(b.loc)}"
        )
    }

    val allNodes = spec.generatorModules.flatMap(g => g.nodes.map(n => ModuleNodeId(g.id, n.name) -> n))
    val asSource = spec.binds.groupBy(_.source)
    val asTarget = spec.binds.groupBy(_.target)
    allNodes.foreach { (id, n) =>
      val (role, binds) = n.direction match
        case NodeDirection.Outward => "source" -> asSource.getOrElse(id, Vector.empty)
        case NodeDirection.Inward  => "target" -> asTarget.getOrElse(id, Vector.empty)
      if binds.size != 1 then
        fail(
          s"${n.direction.toString.toLowerCase} node ${id.show} is the $role of ${binds.size} binds, " +
            s"expected exactly 1, at ${at(binds.map(_.loc) :+ n.loc)}"
        )
    }

    spec.generatorModules.foreach { g =>
      g.dependencies
        .groupBy(d => d.from -> d.to)
        .collectFirst { case (pair, ds) if ds.sizeIs > 1 => pair -> ds }
        .foreach { case ((from, to), ds) =>
          fail(s"duplicate parameter dependency ${g.id.show}#$from -> ${g.id.show}#$to, at ${at(ds.map(_.loc))}")
        }
      g.dependencies.foreach { d =>
        val from = g
          .node(d.from)
          .getOrElse(fail(s"parameter dependency source ${g.id.show}#${d.from} does not exist, at ${at(d.loc)}"))
        val to   = g
          .node(d.to)
          .getOrElse(fail(s"parameter dependency target ${g.id.show}#${d.to} does not exist, at ${at(d.loc)}"))
        if from.direction != NodeDirection.Inward || to.direction != NodeDirection.Outward then
          fail(
            s"parameter dependency ${g.id.show}#${d.from} -> ${g.id.show}#${d.to} has invalid directions, at ${at(d.loc)}"
          )
      }
    }

  private def frozenDomainCheck(spec: DesignSpec, canonical: Map[DomainKey, Domain]): Unit =
    val useByKey = spec.nodeDomains.map(u => u.key -> u).toMap

    def requireModule(module: ModuleId, where: String): Unit =
      if !spec.modules.contains(module) then fail(s"$where belongs to missing module ${module.show}")

    def requireDomain(domain: DomainHandle[?], expected: Domain, where: String): Unit =
      if !(domain.owner eq spec.owner) then fail(s"$where uses a domain from another Design build")
      val declaration = spec.domainDecl(domain.id).getOrElse(fail(s"$where refers to missing domain ${domain.id.show}"))
      if !(domain.domain eq expected) || !(declaration.domain eq expected) then
        fail(s"$where and ${domain.id.show} use different objects for ${expected.key.show}")
      if !(declaration eq domain) then fail(s"$where uses a stale incarnation of domain ${domain.id.show}")

    def requireReadable(token: DomainReadable[?], local: ModuleId, where: String): Unit =
      if !(token.owner eq spec.owner) then fail(s"$where contains a domain token from another Design build")
      requireCanonical(canonical, token.domain, where)
      token match
        case domain: DomainHandle[?] => requireDomain(domain, domain.domain, where)
        case use: NodeDomain[?] =>
          if use.module != local then fail(s"$where contains a token from ${use.module.show}, expected ${local.show}")
          val found = useByKey
            .get(use.key)
            .getOrElse(fail(s"$where refers to missing domain use ${use.key.show}"))
          if !(found.domain eq use.domain) then fail(s"$where uses a mismatched token for ${use.key.show}")
          if !(found.capability eq use) then fail(s"$where uses a stale incarnation of domain use ${use.key.show}")

    def requireFollow(use: NodeDomainSpec, target: NodeDomain[?]): Unit =
      if !(target.domain eq use.domain) then
        fail(s"domain use ${use.key.show} follows ${target.key.show} of a different domain, at ${at(use.loc)}")
      if target.key.node == use.key.node then fail(s"domain use ${use.key.show} follows itself, at ${at(use.loc)}")
      requireReadable(target, target.module, s"follow target of ${use.key.show}")

    spec.domainDecls.groupBy(_.id).collectFirst { case (id, ds) if ds.sizeIs > 1 => id -> ds }.foreach { (id, ds) =>
      fail(s"domain ${id.show} is declared ${ds.size} times, at ${at(ds.map(_.loc))}")
    }
    spec.domainDecls.foreach { d =>
      requireModule(d.id.module, s"domain ${d.id.show}")
      requireCanonical(canonical, d.domain, s"domain ${d.id.show}")
      requireDomain(d, d.domain, s"domain ${d.id.show}")
      d.reads.foreach(requireReadable(_, d.id.module, s"source of domain ${d.id.show}"))
    }

    val allUses = spec.generatorModules.flatMap { g =>
      g.nodes.flatMap { n =>
        val id = ModuleNodeId(g.id, n.name)
        n.nodeDomains.foreach { u =>
          if u.key.node != id then fail(s"domain use ${u.key.show} is stored on node ${id.show}, at ${at(u.loc)}")
        }
        n.nodeDomains
      }
    }
    allUses.groupBy(_.key).collectFirst { case (key, us) if us.sizeIs > 1 => key -> us }.foreach { (key, us) =>
      fail(s"domain use ${key.show} is declared ${us.size} times, at ${at(us.map(_.loc))}")
    }
    allUses.foreach { u =>
      val node = spec
        .nodeSpec(u.key.node)
        .getOrElse(fail(s"domain use ${u.key.show} belongs to a missing node, at ${at(u.loc)}"))
      requireCanonical(canonical, u.domain, s"domain use ${u.key.show}")
      if u.key.domain != u.domain.key then fail(s"domain use ${u.key.show} has a key/object mismatch, at ${at(u.loc)}")
      if !(u.capability.owner eq spec.owner) || u.capability.key != u.key || !(u.capability.domain eq u.domain) then
        fail(s"domain use ${u.key.show} has a mismatched declaration capability, at ${at(u.loc)}")
      val carried = node.protocol.carries.exists(_ eq u.domain)
      val carrierSelector = u.selector match
        case _: DomainSelectorSpec.CarrierOut | DomainSelectorSpec.CarrierIn => true
        case _                                                               => false
      if carried != carrierSelector then
        fail(s"domain use ${u.key.show} has a carrier role inconsistent with its protocol declaration, at ${at(u.loc)}")
      u.selector match
        case DomainSelectorSpec.Direct(domain) =>
          requireDomain(domain, u.domain, s"domain use ${u.key.show}")
        case DomainSelectorSpec.Contextual(domain, providedAt) =>
          requireModule(providedAt, s"context provider of domain use ${u.key.show}")
          if !providedAt.isAncestorOf(u.key.node.module) then
            fail(
              s"context provider ${providedAt.show} is not an ancestor of domain use ${u.key.show}, at ${at(u.loc)}"
            )
          requireDomain(domain, u.domain, s"contextual domain use ${u.key.show}")
        case DomainSelectorSpec.Follow(target) => requireFollow(u, target)
        case DomainSelectorSpec.CarrierOut(source) =>
          source match
            case domain: DomainHandle[?] => requireDomain(domain, u.domain, s"carrier source ${u.key.show}")
            case target: NodeDomain[?] => requireFollow(u, target)
          if node.direction != NodeDirection.Outward then
            fail(s"carrier source ${u.key.show} is not outward, at ${at(u.loc)}")
        case DomainSelectorSpec.CarrierIn                              =>
          if node.direction != NodeDirection.Inward then
            fail(s"carrier sink ${u.key.show} is not inward, at ${at(u.loc)}")
    }

    spec.constraints.foreach { c =>
      val where = s"constraint from ${c.source}, at ${at(c.loc)}"
      c.source match
        case DomainContributor.Module(module) =>
          requireModule(module, where)
          c.reads.foreach(token => requireReadable(token, token.module, where))
        case _ => fail(s"$where must be contributed by a module declaration")
    }

    spec.generatorModules.foreach { g =>
      g.nodes.foreach { n =>
        val id = ModuleNodeId(g.id, n.name)
        n.protocol.carries.foreach { domain =>
          requireCanonical(canonical, domain, s"carried domain of ${id.show}")
          if !n.nodeDomains.exists(_.domain eq domain) then
            fail(s"node ${id.show} has no binding for carried domain ${domain.key.show}, at ${at(n.loc)}")
        }
        n.computation.readPlan.tokens.foreach { token =>
          if !(token.tokenOwner eq spec.owner) then
            fail(s"read plan of ${id.show} contains a token from another Design build")
          token match
            case r: DownReader[?]    =>
              val readNode = spec
                .nodeSpec(r.node)
                .getOrElse(fail(s"read plan of ${id.show} contains missing DownReader node ${r.node.show}"))
              if r.node.module != g.id || !(readNode.capability eq r.nodeCapability) then
                fail(s"read plan of ${id.show} contains a stale incarnation of DownReader node ${r.node.show}")
              if n.direction != NodeDirection.Outward || !g.dependencies.exists(d =>
                  d.from == r.node.name && d.to == n.name
                )
              then fail(s"read plan of ${id.show} contains undeclared Down read of ${r.node.show}")
            case r: UpReader[?]      =>
              val readNode = spec
                .nodeSpec(r.node)
                .getOrElse(fail(s"read plan of ${id.show} contains missing UpReader node ${r.node.show}"))
              if r.node.module != g.id || !(readNode.capability eq r.nodeCapability) then
                fail(s"read plan of ${id.show} contains a stale incarnation of UpReader node ${r.node.show}")
              if n.direction != NodeDirection.Inward || !g.dependencies.exists(d =>
                  d.from == n.name && d.to == r.node.name
                )
              then fail(s"read plan of ${id.show} contains undeclared Up read of ${r.node.show}")
            case u: NodeDomain[?] =>
              if u.key.node != id then
                fail(s"read plan of ${id.show} contains domain token ${u.key.show} of another node")
              requireReadable(u, g.id, s"read plan of ${id.show}")
        }
      }
    }


  private def activeDomainBinds(spec: DesignSpec): Vector[(BindDecl, DomainKey)] =
    spec.binds.flatMap { bind =>
      val source = spec.nodeSpec(bind.source).get
      val target = spec.nodeSpec(bind.target).get
      val keys = source.protocol.carries.map(_.key) ++
        source.nodeDomains.map(_.key.domain) ++ target.nodeDomains.map(_.key.domain)
      keys.toVector.map(bind -> _)
    }.sortBy((bind, key) => (bind.order, domainOrder(key)))

  private def resolveAttachments(
    spec:      DesignSpec,
    active:    Vector[(BindDecl, DomainKey)]
  ): Vector[ResolvedDomainAttachment] =
    val preorder   = spec.moduleOrder.zipWithIndex.toMap
    val nodeOrder  = spec.generatorModules.flatMap(g => g.nodes.map(n => ModuleNodeId(g.id, n.name) -> n.order)).toMap
    val useByKey   = spec.nodeDomains.map(u => u.key -> u).toMap
    val bindByNode = spec.binds.flatMap(b => Vector(b.source -> b, b.target -> b)).toMap

    def keyOrder(key: NodeDomainKey): (Int, Int, (String, String), String) =
      (
        preorder.getOrElse(key.node.module, Int.MaxValue),
        nodeOrder.getOrElse(key.node, Int.MaxValue),
        domainOrder(key.domain),
        key.node.name
      )

    val resolved = mutable.Map.empty[NodeDomainKey, ResolvedDomainAttachment]
    val visiting = mutable.ArrayBuffer.empty[NodeDomainKey]

    def validatePolicy(
      domain: Domain,
      subject: String,
      loc: (sourcecode.File, sourcecode.Line),
      provenance: AttachmentProvenance
    ): Unit =
      val policy = domain.attachmentPolicy
      def check(current: AttachmentProvenance): Unit =
        if !policy.permits(current.method) then
          fail(s"${domain.key.show} rejects ${current.method} at $subject, at ${at(loc)}")
        policy.validate(current) match
          case Left(v)  => fail(s"attachment policy rejected $subject: ${v.message}, at ${at(loc)}")
          case Right(_) => ()
        current match
          case AttachmentProvenance.Follow(_, anchor) => check(anchor)
          case AttachmentProvenance.CarrierOut(_, followed) => followed.foreach(check)
          case AttachmentProvenance.CarrierIn(_, _, _, anchor) => check(anchor)
          case _                                         => ()
      check(provenance)

    def follow(target: NodeDomain[?]): (DomainDeclId, AttachmentProvenance.Follow) =
      val followed = resolve(target.key)
      val provenance = followed.provenance match
        case AttachmentProvenance.Follow(targets, anchor) =>
          AttachmentProvenance.Follow(target.key.node +: targets, anchor)
        case anchor => AttachmentProvenance.Follow(Vector(target.key.node), anchor)
      followed.declaration -> provenance

    def resolve(key: NodeDomainKey): ResolvedDomainAttachment =
      resolved.getOrElseUpdate(
        key, {
          val cycleAt                   = visiting.indexOf(key)
          if cycleAt >= 0 then
            val cycle = (visiting.drop(cycleAt) :+ key).map(_.show).mkString(" -> ")
            fail(s"domain attachment graph has a cycle: $cycle")
          val use                       = useByKey.getOrElse(key, fail(s"active node ${key.node.show} has no ${key.domain.show} NodeDomain"))
          visiting += key
          val (declaration, provenance) = use.selector match
            case DomainSelectorSpec.Direct(domain) =>
              domain.id -> AttachmentProvenance.Direct(domain.id)
            case DomainSelectorSpec.Contextual(domain, providedAt) =>
              domain.id -> AttachmentProvenance.Contextual(domain.id, providedAt)
            case DomainSelectorSpec.Follow(target) => follow(target)
            case DomainSelectorSpec.CarrierOut(source) => source match
              case domain: DomainHandle[?] =>
                domain.id -> AttachmentProvenance.CarrierOut(domain.id, None)
              case target: NodeDomain[?] =>
                val (declaration, provenance) = follow(target)
                declaration -> AttachmentProvenance.CarrierOut(declaration, Some(provenance))
            case DomainSelectorSpec.CarrierIn                      =>
              val bind = bindByNode(key.node)
              val source = resolve(NodeDomainKey(bind.source, key.domain))
              source.declaration -> AttachmentProvenance.CarrierIn(
                bind.bindId, bind.source, source.declaration, source.provenance
              )
          visiting.dropRightInPlace(1)
          validatePolicy(use.domain, use.key.show, use.loc, provenance)
          ResolvedDomainAttachment(key, declaration, provenance)
        }
      )

    active.flatMap { (bind, key) =>
      Vector(NodeDomainKey(bind.source, key), NodeDomainKey(bind.target, key))
    }.distinct.sortBy(keyOrder).map(resolve)


  private def validateDomainEdges(
    spec:        DesignSpec,
    active:      Vector[(BindDecl, DomainKey)],
    constraints: Vector[ConstraintSpec]
  ): Vector[ResolvedDomainCheck] =
    active.flatMap { (bind, key) =>
      val p = spec.nodeSpec(bind.source).get.protocol
      if p.carries.exists(_.key == key) then
        Vector(ResolvedDomainCheck(s"${bind.bindId.show}:carrier", Vector(key)))
      else
        val covered = constraints.exists { constraint =>
          constraint.source == DomainContributor.Bind(bind.bindId) && (constraint.constraint match
            case check: Constraint.Check =>
              val reads = check.reads.collect { case use: NodeDomain[?] => use.key }.toSet
              reads(NodeDomainKey(bind.source, key)) && reads(NodeDomainKey(bind.target, key))
            case _: Constraint.Required[?] => false
          )
        }
        if !covered then
          fail(
            s"active ${bind.bindId.show} has no protocol check covering both endpoints " +
              s"for ${key.show}, at ${at(bind.loc)}"
          )
        Vector.empty
    }


  private def readableDeclaration(
    spec:        DesignSpec,
    attachments: Map[NodeDomainKey, ResolvedDomainAttachment],
    token:       DomainReadable[?],
    where:       String
  ): DomainDeclId =
    if !(token.owner eq spec.owner) then fail(s"$where reads a domain token from another Design build")
    token match
      case domain: DomainHandle[?] =>
        val declaration = spec.domainDecl(domain.id).getOrElse(fail(s"$where reads missing domain ${domain.id.show}"))
        if !(declaration.domain eq domain.domain) then fail(s"$where reads ${domain.id.show} with a mismatched domain object")
        if !(declaration eq domain) then fail(s"$where reads a stale incarnation of domain ${domain.id.show}")
        domain.id
      case use: NodeDomain[?] =>
        val attachment = attachments.getOrElse(use.key, fail(s"$where reads unresolved domain use ${use.key.show}"))
        val u          =
          spec.nodeDomains.find(_.key == use.key).getOrElse(fail(s"$where reads missing domain use ${use.key.show}"))
        if !(u.domain eq use.domain) then fail(s"$where reads ${use.key.show} with a mismatched domain object")
        if !(u.capability eq use) then fail(s"$where reads a stale incarnation of domain use ${use.key.show}")
        attachment.declaration

  private def encodeDomainValue(domain: Domain, value: Any): ujson.Value =
    upickle.default.writeJs(value.asInstanceOf[domain.Value])(
      using domain.valueWriter
    )

  private def encodeRequirement(domain: Domain, value: Any): ujson.Value =
    upickle.default.writeJs(value.asInstanceOf[domain.Requirement])(
      using domain.requirementWriter
    )

  private def encodeWitness(domain: Domain, value: Any): String =
    ujson.write(
      upickle.default.writeJs(value.asInstanceOf[domain.Witness])(
        using domain.witnessWriter
      )
    )

  private def topologicalOrder[A: Ordering](
    nodes:      Vector[A],
    successors: Map[A, Vector[A]],
    indegree:   Map[A, Int]
  ): Either[Vector[A], Vector[A]] =
    @annotation.tailrec
    def kahn(
      ready:   SortedSet[A],
      degrees: Map[A, Int],
      result:  Vector[A]
    ): Vector[A] =
      ready.headOption match
        case None       => result
        case Some(head) =>
          val (nextDegrees, newlyReady) = successors(head).foldLeft(degrees -> Vector.empty[A]) {
            case ((current, opened), successor) =>
              val degree = current(successor) - 1
              current.updated(successor, degree) -> (if degree == 0 then opened :+ successor else opened)
          }
          kahn(ready - head ++ newlyReady, nextDegrees, result :+ head)

    val sorted = kahn(SortedSet.from(nodes.filter(indegree(_) == 0)), indegree, Vector.empty)
    if sorted.size == nodes.size then Right(sorted)
    else
      val remainder = nodes.toSet -- sorted
      def cyclic(id: A): Boolean =
        @annotation.tailrec
        def visit(frontier: Vector[A], seen: Set[A]): Boolean = frontier match
          case head +: tail =>
            if head == id then true
            else if seen(head) then visit(tail, seen)
            else visit(tail ++ successors(head).filter(remainder), seen + head)
          case _            => false
        visit(successors(id).filter(remainder), Set.empty)
      Left(remainder.toVector.filter(cyclic).sorted)

  private def settleDomains(
    spec:        DesignSpec,
    attachments: Vector[ResolvedDomainAttachment]
  ): (Vector[ResolvedDomain], Vector[ConstraintSpec]) =
    val attachmentByKey = attachments.map(a => a.key -> a).toMap
    val preorder        = spec.moduleOrder.zipWithIndex.toMap
    val declById        = spec.domainDecls.map(d => d.id -> d).toMap

    val predecessors = spec.domainDecls.map { declaration =>
      val where = s"source of ${declaration.id.show}"
      val sources = declaration.reads.map { token =>
        readableDeclaration(spec, attachmentByKey, token, where)
      }.toSet
      declaration.id -> sources
    }.toMap

    def declOrder(id: DomainDeclId): (Int, Int, String) =
      val d = declById(id)
      (preorder.getOrElse(id.module, Int.MaxValue), d.order, id.name)

    val successors               = predecessors.toVector.flatMap { case (target, preds) => preds.toVector.map(_ -> target) }
      .groupMap(_._1)(_._2)
      .withDefaultValue(Vector.empty)
    val indegree                 = predecessors.view.mapValues(_.size).toMap
    given Ordering[DomainDeclId] = Ordering.by(declOrder)

    val domainIds = spec.domainDecls.map(_.id)
    val sorted = topologicalOrder(domainIds, successors, indegree) match
      case Right(sorted) => sorted
      case Left(members) =>
        fail(
          s"domain dependency graph has a cycle through ${members.map(_.show).mkString(", ")}, " +
            s"at ${at(members.map(declById(_).loc))}"
        )

    val constraints = Vector.newBuilder[ConstraintSpec]
    val settled = sorted.foldLeft(Map.empty[DomainDeclId, ResolvedDomain]) { (domains, id) =>
      val declaration = declById(id)
      val sourceView = domainViewForReads(
        spec,
        id.module,
        declaration.reads,
        domains,
        attachmentByKey,
        s"source of ${id.show}"
      )
      val (value, ownRequirement) = declaration.run(sourceView) match
        case Left(v)           => fail(s"domain source ${id.show} failed: ${v.message}, at ${at(declaration.loc)}")
        case Right(definition) => definition

      ownRequirement.foreach { requirement =>
        constraints += ConstraintSpec(
          DomainContributor.Declaration(id),
          new Constraint.Required(declaration)(requirement, declaration.loc),
          0
        )
      }
      domains.updated(id, ResolvedDomain(
        id = id,
        domain = declaration.domain,
        value = value,
        encodedValue = encodeDomainValue(declaration.domain, value),
        predecessors = predecessors(id).toVector.sortBy(declOrder),
        requirements = Vector.empty,
        loc = declaration.loc
      ))
    }
    sorted.map(settled) -> constraints.result()

  private def domainViewFor(
    spec:        DesignSpec,
    module:      ModuleId,
    domains:     Map[DomainDeclId, ResolvedDomain],
    attachments: Map[NodeDomainKey, ResolvedDomainAttachment]
  ): DomainView =
    val declarations = spec.domainDecls.map { declaration =>
      val resolved = domains.getOrElse(
        declaration.id,
        fail(s"domain ${declaration.id.show} is unresolved")
      )
      declaration -> resolved
    }
    val uses = spec.generatorModule(module).toVector.flatMap { g =>
      g.nodes.sortBy(_.order).flatMap(_.nodeDomains.sortBy(u => domainOrder(u.domain.key))).map { use =>
        val attachment = attachments.getOrElse(use.key, fail(s"domain use ${use.key.show} is unresolved"))
        val resolved   = domains(attachment.declaration)
        use.capability -> resolved
      }
    }
    new DomainView(module, spec.owner, declarations ++ uses)

  private def domainViewForReads(
    spec:        DesignSpec,
    module:      ModuleId,
    reads:       Vector[DomainReadable[?]],
    domains:     Map[DomainDeclId, ResolvedDomain],
    attachments: Map[NodeDomainKey, ResolvedDomainAttachment],
    where:       String
  ): DomainView =
    val entries = reads.map { token =>
      val declaration = readableDeclaration(spec, attachments, token, where)
      val resolved    = domains.getOrElse(declaration, fail(s"$where reads unresolved domain ${declaration.show}"))
      token -> resolved
    }
    new DomainView(module, spec.owner, entries)


  private def validateConstraints(
    ready:       DomainReadyDesign,
    canonical:   Map[DomainKey, Domain],
    constraints: Vector[ConstraintSpec]
  ): (Vector[ResolvedDomain], Vector[ResolvedDomainCheck]) =
    val spec = ready.spec
    val contributions = constraints.map { contribution =>
      val where = s"constraint from ${contribution.source}, at ${at(contribution.loc)}"
      val module = contribution.source match
        case DomainContributor.Module(module) =>
          if !spec.modules.contains(module) then fail(s"$where belongs to missing module ${module.show}")
          module
        case DomainContributor.Node(node) =>
          if spec.nodeSpec(node).isEmpty then fail(s"$where belongs to missing node ${node.show}")
          contribution.reads.foreach {
            case use: NodeDomain[?] if use.key.node == node => ()
            case _ => fail(s"$where may only read domains declared on ${node.show}")
          }
          node.module
        case DomainContributor.Bind(id) =>
          val bind = spec.binds.find(_.bindId == id).getOrElse(fail(s"$where belongs to missing bind ${id.show}"))
          contribution.reads.foreach {
            case use: NodeDomain[?] if use.key.node == bind.source || use.key.node == bind.target => ()
            case _ => fail(s"$where may only read domains declared on the endpoints of ${id.show}")
          }
          bind.declaredIn
        case DomainContributor.Declaration(id) =>
          val declaration = spec.domainDecl(id).getOrElse(fail(s"$where belongs to missing domain ${id.show}"))
          contribution.constraint match
            case required: Constraint.Required[?] if required.source eq declaration => ()
            case _ => fail(s"$where must require its own domain declaration")
          id.module
      contribution.reads.foreach { token =>
        requireCanonical(canonical, token.domain, where)
        readableDeclaration(spec, ready.attachmentByKey, token, where)
      }
      contribution -> module
    }

    val requirements = constraints.flatMap { contribution =>
      contribution.constraint match
        case required: Constraint.Required[?] =>
          val id = readableDeclaration(spec, ready.attachmentByKey, required.source, s"requirement from ${contribution.source}")
          if !(ready.domainById(id).domain eq required.source.domain) then
            fail(s"requirement from ${contribution.source} does not match domain ${id.show}")
          Vector(id -> (contribution, required))
        case _: Constraint.Check => Vector.empty
    }.groupMap(_._1)(_._2)

    val domains = ready.domains.map { resolved =>
      val domain = resolved.domain
      val work = requirements.getOrElse(resolved.id, Vector.empty)
      val evaluated = work.zipWithIndex.map { case ((contribution, required), ordinal) =>
        (contribution, new DomainRequirementOrigin(ordinal), required.value)
      }
      val typedRequirements = evaluated.map { case (_, origin, requirement) =>
        DomainRequirement(origin, requirement.asInstanceOf[domain.Requirement])
      }
      domain.validate(resolved.value.asInstanceOf[domain.Value], typedRequirements) match
        case Left(violation) =>
          val originMap = evaluated.map { case (contribution, origin, _) => origin -> contribution.source }.toMap
          if violation.sources.exists(origin => !originMap.contains(origin)) then
            fail(s"domain validator ${domain.key.show} returned origins not present in ${resolved.id.show}")
          val contributors = violation.sources.distinct.sortBy(_.ordinal).map(originMap).mkString(", ")
          fail(
            s"domain ${resolved.id.show} rejected requirements [$contributors]: ${violation.message}; " +
              s"witness ${encodeWitness(domain, violation.witness)}, at ${at(resolved.loc +: work.map(_._1.loc))}"
          )
        case Right(_) => ()
      resolved.copy(requirements = evaluated.map { case (contribution, _, requirement) =>
        ResolvedDomainRequirement(contribution.source, requirement, encodeRequirement(domain, requirement))
      })
    }

    val checks = Vector.newBuilder[ResolvedDomainCheck]
    checks ++= validateDomainEdges(spec, ready.active, constraints)
    contributions.foreach { (contribution, module) =>
      contribution.constraint match
        case check: Constraint.Check =>
          val where = s"constraint from ${contribution.source}, at ${at(contribution.loc)}"
          val view = domainViewForReads(
            spec,
            module,
            check.reads,
            ready.domainById,
            ready.attachmentByKey,
            where
          )
          check.run(view) match
            case Left(v) => fail(s"$where failed: ${v.message}")
            case Right(_) => checks += ResolvedDomainCheck(where, check.reads.map(_.domain.key).distinct)
        case _: Constraint.Required[?] => ()
    }
    domains -> checks.result()

  private def negotiateDomains(spec: DesignSpec): DomainReadyDesign =
    val active      = beforeDomainReady("active-domain closure")(activeDomainBinds(spec))
    val attachments = beforeDomainReady("effective-domain attachment resolution")(
      resolveAttachments(spec, active)
    )
    val (domains, constraints) = beforeDomainReady("domain dependency/settlement")(settleDomains(spec, attachments))
    DomainReadyDesign(
      spec,
      active,
      domains,
      attachments,
      constraints
    )


  private def parameterTopology(spec: DesignSpec): Vector[ModuleNodeId] =
    val allNodes                 = spec.generatorModules.flatMap(g => g.nodes.map(g.id -> _))
    val preorder                 = spec.moduleOrder.zipWithIndex.toMap
    val nodeKey                  = allNodes.map((m, n) => ModuleNodeId(m, n.name) -> (preorder(m), n.order)).toMap
    val nodeIds                  = nodeKey.keys.toVector
    val edges                    = spec.binds.map(b => b.source -> b.target) ++ (
      for g <- spec.generatorModules; d <- g.dependencies
      yield ModuleNodeId(g.id, d.from) -> ModuleNodeId(g.id, d.to)
    )
    val successors               = edges.groupMap(_._1)(_._2).withDefaultValue(Vector.empty)
    val predecessors             = edges.groupMap(_._2)(_._1).withDefaultValue(Vector.empty)
    val indegree                 = nodeIds.map(id => id -> predecessors(id).size).toMap
    given Ordering[ModuleNodeId] = Ordering.by(nodeKey)

    topologicalOrder(nodeIds, successors, indegree) match
      case Right(sorted) => sorted
      case Left(members) =>
        val onCycle = members.toSet
        val locs    = members.flatMap(id => spec.nodeSpec(id).map(_.loc)) ++
          spec.binds.filter(b => onCycle(b.source) && onCycle(b.target)).map(_.loc) ++
          spec.generatorModules.flatMap(g =>
            g.dependencies
              .filter(d => onCycle(ModuleNodeId(g.id, d.from)) && onCycle(ModuleNodeId(g.id, d.to)))
              .map(_.loc)
          )
        fail(s"parameter dependency graph has a cycle through ${members.map(_.show).mkString(", ")}, at ${at(locs)}")

  private def propagate(ready: DomainReadyDesign, order: Vector[ModuleNodeId]): Propagated =
    val spec         = ready.spec
    val bindOfSource = spec.binds.map(b => b.source -> b).toMap
    val bindOfTarget = spec.binds.map(b => b.target -> b).toMap
    val constraints  = Vector.newBuilder[ConstraintSpec]

    def specOf(id: ModuleNodeId): NodeSpec = spec.nodeSpec(id).get

    def domainValue(use: NodeDomain[?]): Any =
      val attachment =
        ready.attachmentByKey.getOrElse(use.key, fail(s"read plan refers to unresolved domain use ${use.key.show}"))
      ready.domainById
        .getOrElse(attachment.declaration, fail(s"domain ${attachment.declaration.show} is not settled"))
        .value

    def encodeRead(token: ReadToken, value: Any): String = token match
      case r:   DownReader[?]    =>
        val p = specOf(r.node).protocol
        s"${r.node.show}=${ujson.write(
            upickle.default.writeJs(value)(
              using p.downWriter.asInstanceOf[upickle.default.Writer[Any]]
            )
          )}"
      case r:   UpReader[?]      =>
        val p = specOf(r.node).protocol
        s"${r.node.show}=${ujson.write(
            upickle.default.writeJs(value)(
              using p.upWriter.asInstanceOf[upickle.default.Writer[Any]]
            )
          )}"
      case use: NodeDomain[?] =>
        s"${use.key.show}=${ujson.write(encodeDomainValue(use.domain, value))}"

    def evaluate(values: Map[ModuleNodeId, Any], id: ModuleNodeId): Any =
      val node   = specOf(id)
      val reads = node.computation.readPlan.tokens
      val inputs = reads.map { token =>
        val value = token match
          case r:   DownReader[?]    =>
            values.getOrElse(r.node, fail(s"${id.show} reads unavailable Down of ${r.node.show}"))
          case r:   UpReader[?]      => values.getOrElse(r.node, fail(s"${id.show} reads unavailable Up of ${r.node.show}"))
          case use: NodeDomain[?] => domainValue(use)
        token -> value
      }.toMap
      node.computation(new ReadValues(inputs)) match
        case Right((value, contributed)) =>
          constraints ++= contributed.zipWithIndex.map { (constraint, index) =>
            ConstraintSpec(DomainContributor.Node(id), constraint, index)
          }
          value
        case Left(v)      =>
          val snapshot = reads.map(t => encodeRead(t, inputs(t)))
          fail(
            s"propagation failed at ${id.show} (${node.direction}): ${v.message}; inputs [${snapshot.mkString(", ")}], at ${at(node.loc)}"
          )

    val down = order.foldLeft(Map.empty[ModuleNodeId, Any]) { (values, id) =>
      values.updated(
        id,
        specOf(id).direction match
          case NodeDirection.Outward => evaluate(values, id)
          case NodeDirection.Inward  => values(bindOfTarget(id).source)
      )
    }
    val up   = order.reverse.foldLeft(Map.empty[ModuleNodeId, Any]) { (values, id) =>
      values.updated(
        id,
        specOf(id).direction match
          case NodeDirection.Inward  => evaluate(values, id)
          case NodeDirection.Outward => values(bindOfSource(id).target)
      )
    }
    Propagated(down, up, constraints.result())

  private def settle(ready: DomainReadyDesign, propagated: Propagated): Settled =
    val spec = ready.spec
    val constraints = Vector.newBuilder[ConstraintSpec]
    val edges = spec.binds.map { bind =>
      val source = spec.nodeSpec(bind.source).get
      val target = spec.nodeSpec(bind.target).get
      val p    = source.protocol
      val down = propagated.down(bind.source)
      val up   = propagated.up(bind.target)
      val outward = source.nodeDomains.map(_.capability)
      val inward = target.nodeDomains.map(_.capability)
      val values = domainViewForReads(
        spec,
        bind.declaredIn,
        outward ++ inward,
        ready.domainById,
        ready.attachmentByKey,
        s"negotiation of ${bind.bindId.show}"
      )
      val domains = new EdgeDomains(outward, inward, values)
      p.asInstanceOf[Protocol { type Down = Any; type Up = Any }].negotiate(down, up, domains) match
        case Left(v)     => fail(s"settle failed at ${bind.bindId.show}: ${v.message}, at ${at(bind.loc)}")
        case Right((edge, contributed)) =>
          constraints ++= contributed.zipWithIndex.map { (constraint, index) =>
            ConstraintSpec(DomainContributor.Bind(bind.bindId), constraint, index)
          }
          SettledEdge(bind, p, down, up, edge)
    }
    Settled(edges, constraints.result())

  private def interfaces(edges: Vector[SettledEdge]): Vector[ResolvedEdge] =
    edges.map { settled =>
      val bind = settled.bind
      val bundle = settled.protocol.asInstanceOf[Protocol { type Edge = Any }].interface(settled.edge)
      ProtocolInterface.leaves(bundle).collectFirst { case (path, _: ProtocolInterface.Probe) => path }.foreach {
        path =>
          fail(
            s"design interface of ${bind.bindId.show} contains a Probe at ${path.show}; " +
              s"probes belong to verification protocols, at ${at(bind.loc)}"
          )
      }
      ResolvedEdge(bind.bindId, settled.protocol, settled.down, settled.up, settled.edge, bundle)
    }

  private def assembleViews(
    ready: DomainReadyDesign,
    edges: Vector[ResolvedEdge]
  ): (Vector[ResolvedGeneratorModule], ProbeCatalog, ProbeBindings) =
    val spec         = ready.spec
    val edgeOfSource = edges.map(e => e.bind.source -> e).toMap
    val edgeOfTarget = edges.map(e => e.bind.target -> e).toMap
    def views(g: GeneratorModuleSpec): (EdgeView, DomainView) =
      val nodeViews  = g.nodes.map { n =>
        val id   = ModuleNodeId(g.id, n.name)
        val edge = n.direction match
          case NodeDirection.Outward => edgeOfSource(id)
          case NodeDirection.Inward  => edgeOfTarget(id)
        NodeView(id, n.direction, edge, n.capability)
      }
      val edgeView   = EdgeView(g.id, spec.owner, nodeViews)
      val domainView = domainViewFor(spec, g.id, ready.domainById, ready.attachmentByKey)
      (edgeView, domainView)

    def parameter(g: GeneratorModuleSpec, result: Either[Violation, Any]): Any = result match
      case Left(v)          => fail(s"capability exceeded at ${g.id.show}: ${v.message}, at ${at(g.loc)}")
      case Right(fullParam) => fullParam

    def resolved(g: GeneratorModuleSpec, edgeView: EdgeView, domainView: DomainView, fullParam: Any)
      : ResolvedGeneratorModule =
      val definition      = g.definition.asInstanceOf[GeneratorDefinition[Any]]
      val encoded         = upickle.default.writeJs(fullParam)(
        using definition.fullParamWriter
      )
      val declaration     = definition.probes(fullParam)
      val publicProbes    = declaration.ports
      if publicProbes.map(_.name).distinct.size != publicProbes.size then
        fail(s"generator ${g.id.show} has duplicate public Probe names, at ${at(g.loc)}")
      val functionalNames = g.nodes.map(_.name).toSet
      publicProbes.foreach { port =>
        if functionalNames(port.name) then
          fail(s"public Probe ${g.id.show}#${port.name} conflicts with a functional node, at ${at(g.loc)}")
      }
      ResolvedGeneratorModule(g.id, definition, edgeView, domainView, fullParam, encoded, declaration)

    val dutParameters             = spec.generatorModules.filterNot(g => spec.testbench.contains(g.id)).map { g =>
      val (edgeView, domainView) = views(g)
      val result                 = g.parameters match
        case ParameterComputation.Ordinary(compute) => compute(edgeView, domainView)
        case ParameterComputation.Observed(_)       => fail(s"${g.id.show} is not the testbench")
      (g, edgeView, domainView, parameter(g, result))
    }
    val dut                       = dutParameters.map { (g, edgeView, domainView, fullParam) => resolved(g, edgeView, domainView, fullParam) }
    val catalog                   = ProbeCatalog.resolve(dut.map(g => g.module -> g.probeDeclaration)) { ports =>
      dut.flatMap { generator =>
        spec.generatorModule(generator.module).get.probes.flatMap { declared =>
          declared.resolve(generator.fullParam, generator.probeDeclaration) match
            case Left(violation) =>
              fail(s"Probe selector failed at ${declared.node.id.show}: ${violation.message}, at ${at(declared.loc)}")
            case Right(None) => Vector.empty
            case Right(Some(selected)) =>
              val portId = ModuleNodeId(generator.module, selected.portName)
              val port = ports.find(_.id == portId).getOrElse(
                fail(s"Probe ${declared.node.id.show} selects missing public port ${portId.show}, at ${at(declared.loc)}")
              )
              selected.implementation.validate(generator.probeDeclaration, selected.portName) match
                case Left(violation) =>
                  fail(s"Probe binding failed at ${declared.node.id.show}: ${violation.message}, at ${at(declared.loc)}")
                case Right(_) => ()
              Vector(new ResolvedProbe(declared.node, selected.parameters, port, selected.implementation))
        }
      }
    }
    val (testbench, observations) = spec.testbench.flatMap(spec.generatorModule) match
      case None    => (Vector.empty[ResolvedGeneratorModule], ProbeBindings.empty)
      case Some(g) =>
        val (edgeView, domainView) = views(g)
        val result                 = g.parameters match
          case ParameterComputation.Ordinary(compute) => compute(edgeView, domainView)
          case ParameterComputation.Observed(compute) => compute(catalog, edgeView, domainView)
        val fullParam              = parameter(g, result)
        val observations           = g.definition.asInstanceOf[TestbenchDefinition[Any]].observations(fullParam)
        catalog.validate(observations)
        val targetNames            = observations.ports.map(_.portName)
        val functionalNames        = g.nodes.map(_.name).toSet
        targetNames.foreach { name =>
          if functionalNames(name) then
            fail(s"testbench Probe port '$name' conflicts with a functional node in ${g.id.show}")
        }
        val module                 = resolved(g, edgeView, domainView, fullParam)
        val publicNames            = module.probeDeclaration.ports.map(_.name).toSet
        targetNames.foreach { name =>
          if publicNames(name) then fail(s"testbench Probe port '$name' conflicts with a public Probe in ${g.id.show}")
        }
        (Vector(module), observations)
    val byId                      = (dut ++ testbench).map(g => g.module -> g).toMap
    (spec.generatorModules.map(g => byId(g.id)), catalog, observations)
