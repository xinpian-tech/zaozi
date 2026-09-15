package me.jiuyang.syntheke

object Export:

  private def moduleId(id: ModuleId): ujson.Value = ujson.Arr.from(id.path.map(ujson.Str(_)))

  private def nodeId(id: ModuleNodeId): ujson.Value =
    ujson.Obj("module" -> moduleId(id.module), "name" -> ujson.Str(id.name))

  private def domainKey(key: DomainKey): ujson.Value =
    ujson.Obj(
      "namespace" -> ujson.Str(key.namespace),
      "name"      -> ujson.Str(key.name)
    )

  private def domainDeclId(id: DomainDeclId): ujson.Value =
    ujson.Obj("module" -> moduleId(id.module), "name" -> ujson.Str(id.name))

  private def nodeDomainKey(key: NodeDomainKey): ujson.Value =
    ujson.Obj("node" -> nodeId(key.node), "domainKey" -> domainKey(key.domain))

  private def domainReadable(token: DomainReadable[?]): ujson.Value = token match
    case domain: DomainHandle[?] =>
      ujson.Obj("domain" -> domainDeclId(domain.id), "domainKey" -> domainKey(domain.domain.key))
    case nodeDomain: NodeDomain[?] =>
      ujson.Obj("nodeDomain" -> nodeDomainKey(nodeDomain.key), "domainKey" -> domainKey(nodeDomain.domain.key))

  private def domainOrder(key: DomainKey): (String, String) =
    (key.namespace, key.name)

  private def attachmentMethod(method: AttachmentMethod): String = method match
    case AttachmentMethod.Direct         => "direct"
    case AttachmentMethod.Follow     => "follow"
    case AttachmentMethod.Contextual     => "contextual"
    case AttachmentMethod.CarrierOut => "carrierOut"
    case AttachmentMethod.CarrierIn  => "carrierIn"

  private def bindId(id: BindId): ujson.Value =
    ujson.Obj("order" -> ujson.Num(id.order), "source" -> nodeId(id.source), "target" -> nodeId(id.target))

  private def contributor(source: DomainContributor): ujson.Value = source match
    case DomainContributor.Node(node) => ujson.Obj("node" -> nodeId(node))
    case DomainContributor.Declaration(id) => ujson.Obj("domainSource" -> domainDeclId(id))
    case DomainContributor.Module(module) => ujson.Obj("module" -> moduleId(module))
    case DomainContributor.Bind(bind) => ujson.Obj("bind" -> bindId(bind))

  private def loc(l: (sourcecode.File, sourcecode.Line)): ujson.Value =
    ujson.Obj("file" -> ujson.Str(l._1.value.replace('\\', '/')), "line" -> ujson.Num(l._2.value))

  private def interface(tpe: ProtocolInterface): ujson.Value = upickle.default.writeJs(tpe)

  def topology(spec: DesignSpec): ujson.Value =
    ujson.Obj(
      "modules"            -> ujson.Arr.from(spec.moduleOrder.map { id =>
        spec.modules(id) match
          case w: WrapperModuleSpec   =>
            ujson.Obj(
              "id"         -> moduleId(id),
              "kind"       -> ujson.Str("wrapper"),
              "moduleName" -> ujson.Str(w.moduleName),
              "children"   -> ujson.Arr.from(w.children.map(ujson.Str(_))),
              "loc"        -> loc(w.loc)
            )
          case g: GeneratorModuleSpec =>
            ujson.Obj(
              "id"           -> moduleId(id),
              "kind"         -> ujson.Str("generator"),
              "generator"    -> ujson.Str(g.definition.name),
              "nodes"        -> ujson.Arr.from(g.nodes.map { n =>
                ujson.Obj(
                  "id"         -> nodeId(ModuleNodeId(id, n.name)),
                  "direction"  -> ujson.Str(n.direction.toString.toLowerCase),
                  "nodeDomains" -> ujson.Arr.from(n.nodeDomains.map { u =>
                    val selector            = u.selector match
                      case DomainSelectorSpec.Direct(domain)                 => ujson.Obj("direct" -> domainDeclId(domain.id))
                      case DomainSelectorSpec.Contextual(domain, providedAt) =>
                        ujson.Obj(
                          "contextual" -> ujson.Obj("domain" -> domainDeclId(domain.id), "providedAt" -> moduleId(providedAt))
                        )
                      case DomainSelectorSpec.Follow(target)                 => ujson.Obj("follow" -> nodeId(target.key.node))
                      case DomainSelectorSpec.CarrierOut(source)             => ujson.Obj("carrierOut" -> domainReadable(source))
                      case DomainSelectorSpec.CarrierIn                      => ujson.Obj("carrierIn" -> ujson.Bool(true))
                    ujson.Obj(
                      "key"            -> nodeDomainKey(u.key),
                      "selector"       -> selector,
                      "order"          -> ujson.Num(u.order),
                      "loc"            -> loc(u.loc)
                    )
                  }),
                  "order"      -> ujson.Num(n.order),
                  "loc"        -> loc(n.loc)
                )
              }),
              "dependencies" -> ujson.Arr.from(g.dependencies.map { d =>
                ujson.Obj(
                  "inward"  -> nodeId(ModuleNodeId(id, d.from)),
                  "outward" -> nodeId(ModuleNodeId(id, d.to)),
                  "order"   -> ujson.Num(d.order),
                  "loc"     -> loc(d.loc)
                )
              }),
              "loc"          -> loc(g.loc)
            )
      }),
      "binds"              -> ujson.Arr.from(spec.binds.map { b =>
        ujson.Obj("id" -> bindId(b.bindId), "declaredIn" -> moduleId(b.declaredIn), "loc" -> loc(b.loc))
      }),
      "domainDeclarations" -> ujson.Arr.from(spec.domainDecls.zipWithIndex.map { (d, order) =>
        ujson.Obj(
          "id"                -> domainDeclId(d.id),
          "domainKey"              -> domainKey(d.domain.key),
          "predecessorTokens" -> ujson.Num(d.reads.size),
          "order"             -> ujson.Num(order),
          "loc"               -> loc(d.loc)
        )
      }),
      "testbench"          -> spec.testbench.fold[ujson.Value](ujson.Null)(moduleId)
    )

  private def write(writer: upickle.default.Writer[?], value: Any): ujson.Value =
    upickle.default.writeJs(value)(
      using writer.asInstanceOf[upickle.default.Writer[Any]]
    )

  private def provenance(p: AttachmentProvenance): ujson.Value = p match
    case AttachmentProvenance.Direct(declaration)                         => ujson.Obj("direct" -> domainDeclId(declaration))
    case AttachmentProvenance.Contextual(declaration, providedAt)         =>
      ujson.Obj(
        "contextual" -> ujson.Obj(
          "domain"     -> domainDeclId(declaration),
          "providedAt" -> moduleId(providedAt)
        )
      )
    case AttachmentProvenance.Follow(targets, anchor)          =>
      ujson.Obj(
        "follow" -> ujson.Arr.from(targets.map(nodeId)),
        "anchor"     -> provenance(anchor)
      )
    case AttachmentProvenance.CarrierOut(declaration, followed) =>
      ujson.Obj(
        "carrierOut" -> domainDeclId(declaration),
        "followed" -> followed.map(provenance).getOrElse(ujson.Null)
      )
    case AttachmentProvenance.CarrierIn(bind, source, declaration, anchor) =>
      ujson.Obj(
        "carrierIn" -> ujson.Obj(
          "bind"     -> bindId(bind),
          "source"   -> nodeId(source),
          "domain"   -> domainDeclId(declaration),
          "anchor"   -> provenance(anchor)
        )
      )

  def domains(resolved: ResolvedDesign): ujson.Value =
    val spec       = resolved.spec
    val moduleRank = spec.moduleOrder.zipWithIndex.toMap
    val nodes      = spec.generatorModules.flatMap { module =>
      module.nodes.map(node => ModuleNodeId(module.id, node.name) -> node)
    }
    val nodeById   = nodes.toMap

    def nodeOrder(id: ModuleNodeId): (Int, Int, String) =
      (moduleRank(id.module), nodeById(id).order, id.name)

    def nodeDomainOrder(nodeDomain: NodeDomainSpec): (Int, Int, (String, String), Int) =
      (moduleRank(nodeDomain.key.node.module), nodeById(nodeDomain.key.node).order, domainOrder(nodeDomain.domain.key), nodeDomain.order)

    val declarationById  = spec.domainDecls.map(declaration => declaration.id -> declaration).toMap
    val declarations     = spec.domainDecls.zipWithIndex.sortBy((d, _) => (moduleRank(d.id.module), d.order, d.id.name))
    val nodeDomains      = spec.nodeDomains.sortBy(nodeDomainOrder)
    val constraints      = resolved.constraints
    val bindByTarget     = spec.binds.map(bind => bind.target -> bind).toMap

    val protocols = nodes
      .map(_._2.protocol)
      .foldLeft(Vector.empty[Protocol]) { (seen, protocol) =>
        if seen.exists(_ eq protocol) then seen else seen :+ protocol
      }

    def protocolId(protocol: Protocol): ujson.Value = ujson.Num(protocols.indexWhere(_ eq protocol))

    val canonicalDomains = resolved.domains.map(_.domain)
      .distinctBy(_.key)
      .sortBy(domain => domainOrder(domain.key))

    val attachmentByKey         = resolved.domainAttachments.map(a => a.key -> a).toMap
    val attachmentsByNode       = resolved.domainAttachments.groupBy(_.key.node).withDefaultValue(Vector.empty)
    val attachmentsByDeclaration = resolved.domainAttachments
      .groupBy(_.declaration)
      .map((id, attachments) => id -> attachments.sortBy(attachment => nodeOrder(attachment.key.node)))
      .withDefaultValue(Vector.empty)
    val resolvedById            = resolved.domains.map(d => d.id -> d).toMap
    val nodeDomainByKey         = nodeDomains.map(u => u.key -> u).toMap

    def readableDeclaration(token: DomainReadable[?]): DomainDeclId = token match
      case domain: DomainHandle[?]  => domain.id
      case nodeDomain: NodeDomain[?] => attachmentByKey(nodeDomain.key).declaration

    def selectorJson(nodeDomain: NodeDomainSpec): ujson.Value = nodeDomain.selector match
      case DomainSelectorSpec.Direct(domain)                 =>
        ujson.Obj("direct" -> domainDeclId(domain.id))
      case DomainSelectorSpec.Contextual(domain, providedAt) =>
        ujson.Obj(
          "contextual" -> ujson.Obj("domain" -> domainDeclId(domain.id), "providedAt" -> moduleId(providedAt))
        )
      case DomainSelectorSpec.Follow(target)                 =>
        ujson.Obj("follow" -> nodeId(target.key.node))
      case DomainSelectorSpec.CarrierOut(source)             =>
        ujson.Obj(
          "carrierOut" -> ujson.Obj(
            "domain"   -> domainDeclId(attachmentByKey(nodeDomain.key).declaration),
            "source"   -> domainReadable(source),
            "protocol" -> protocolId(nodeById(nodeDomain.key.node).protocol),
            "domainKey"     -> domainKey(nodeDomain.domain.key)
          )
        )
      case DomainSelectorSpec.CarrierIn                      =>
        ujson.Obj(
          "carrierIn" -> ujson.Obj(
            "protocol" -> protocolId(nodeById(nodeDomain.key.node).protocol),
            "domainKey"     -> domainKey(nodeDomain.domain.key)
          )
        )

    def inputJson(token: DomainReadable[?]): ujson.Value =
      val declaration  = readableDeclaration(token)
      ujson.Obj(
        "token"  -> domainReadable(token),
        "domain" -> domainDeclId(declaration),
        "value"  -> resolvedById(declaration).encodedValue
      )

    def constraintJson(record: ConstraintSpec): ujson.Value =
      val result = ujson.Obj(
        "source"     -> contributor(record.source),
        "reads"      -> ujson.Arr.from(record.reads.map(domainReadable)),
        "inputs"     -> ujson.Arr.from(record.reads.map(inputJson)),
        "validation" -> ujson.Str("passed"),
        "order"      -> ujson.Num(record.order),
        "loc"        -> loc(record.loc)
      )
      record.constraint match
        case required: Constraint.Required[?] =>
          result("type") = ujson.Str("requirement")
          result("target") = domainDeclId(readableDeclaration(required.source))
          result("value") = write(required.source.domain.requirementWriter, required.value)
        case _: Constraint.Check => result("type") = ujson.Str("check")
      result

    val domainDependencies = resolved.domains.flatMap { target =>
      declarationById(target.id).reads.zipWithIndex.map { (token, readOrder) =>
        ujson.Obj(
          "predecessor" -> domainDeclId(readableDeclaration(token)),
          "target"      -> domainDeclId(target.id),
          "origin"      -> ujson.Obj("domainSource" -> domainDeclId(target.id)),
          "read"        -> domainReadable(token),
          "readOrder"   -> ujson.Num(readOrder)
        )
      }
    }

    val checksByBindDomain = constraints.flatMap { record =>
      (record.source, record.constraint) match
        case (DomainContributor.Bind(bind), check: Constraint.Check) =>
          check.reads.collect { case token: NodeDomain[?] => token.key }
            .groupMap(_.domain)(_.node)
            .collect { case (key, endpoints) if endpoints.contains(bind.source) && endpoints.contains(bind.target) =>
              (bind -> key) -> record.order
            }
            .toVector
        case _ => Vector.empty
    }.groupMap(_._1)(_._2).withDefaultValue(Vector.empty)

    val constraintNodes = constraints.flatMap(_.reads.collect { case token: NodeDomain[?] => token.key }).toSet
    val followTargets = nodeDomains.collect {
      case NodeDomainSpec(_, _, DomainSelectorSpec.Follow(target), _, _, _) =>
        target.key
      case NodeDomainSpec(_, _, DomainSelectorSpec.CarrierOut(target: NodeDomain[?]), _, _, _) =>
        target.key
    }.toSet
    val carriedNodes = resolved.domainAttachments.collect {
      case attachment if attachment.provenance.method == AttachmentMethod.CarrierOut ||
          attachment.provenance.method == AttachmentMethod.CarrierIn => attachment.key
    }.toSet

    def nodeActivationReasons(key: NodeDomainKey): Vector[String] =
      Vector(
        Some("nodeDomain"),
        Option.when(constraintNodes(key))("constraint"),
        Option.when(followTargets(key))("followTarget"),
        Option.when(carriedNodes(key))("carrier")
      ).flatten

    val identityConstraintClasses = resolved.domainAttachments
      .groupBy { attachment =>
        attachment.provenance match
          case AttachmentProvenance.CarrierIn(_, source, _, _) => NodeDomainKey(source, attachment.key.domain)
          case _ => attachment.key
      }
      .values
      .map(_.map(_.key).sortBy(key => (nodeOrder(key.node), domainOrder(key.domain))))
      .toVector
      .sortBy(members => (domainOrder(members.head.domain), nodeOrder(members.head.node)))

    val carrierRecords = nodeDomains.flatMap { nodeDomain =>
      nodeDomain.selector match
        case DomainSelectorSpec.CarrierOut(source)            =>
          Some(
            ujson.Obj(
              "nodeDomain"  -> nodeDomainKey(nodeDomain.key),
              "role"     -> ujson.Str("carrierOut"),
              "protocol" -> protocolId(nodeById(nodeDomain.key.node).protocol),
              "domainKey"     -> domainKey(nodeDomain.domain.key),
              "domain"   -> domainDeclId(attachmentByKey(nodeDomain.key).declaration),
              "source"   -> domainReadable(source),
              "loc"      -> loc(nodeDomain.loc)
            )
          )
        case DomainSelectorSpec.CarrierIn                   =>
          val attachment = attachmentByKey(nodeDomain.key)
          val bind = bindByTarget(nodeDomain.key.node)
          Some(
            ujson.Obj(
              "nodeDomain"  -> nodeDomainKey(nodeDomain.key),
              "role"     -> ujson.Str("carrierIn"),
              "protocol" -> protocolId(nodeById(nodeDomain.key.node).protocol),
              "domainKey"     -> domainKey(nodeDomain.domain.key),
              "bind"     -> bindId(bind.bindId),
              "source"   -> nodeId(bind.source),
              "domain"   -> domainDeclId(attachment.declaration),
              "loc"      -> loc(nodeDomain.loc)
            )
          )
        case _                                               => None
    }

    ujson.Obj(
      "domainReady"              -> ujson.Bool(true),
      "canonicalDomains"           -> ujson.Arr.from(canonicalDomains.map { domain =>
        ujson.Obj(
          "key"                      -> domainKey(domain.key),
          "allowedAttachmentMethods" -> ujson.Arr.from(
            AttachmentMethod.values.toVector
              .filter(domain.attachmentPolicy.permits)
              .map(method => ujson.Str(attachmentMethod(method)))
          )
        )
      }),
      "domainDeclarations"       -> ujson.Arr.from(declarations.map { (declaration, order) =>
        ujson.Obj(
          "id"           -> domainDeclId(declaration.id),
          "domainKey"         -> domainKey(declaration.domain.key),
          "sourceReads"  -> ujson.Arr.from(declaration.reads.map(domainReadable)),
          "settledValue" -> resolvedById(declaration.id).encodedValue,
          "order"        -> ujson.Num(order),
          "loc"          -> loc(declaration.loc)
        )
      }),
      "domainDependencies"       -> ujson.Arr.from(domainDependencies),
      "nodeDomains"           -> ujson.Arr.from(nodeDomains.map { nodeDomain =>
        ujson.Obj(
          "key"         -> nodeDomainKey(nodeDomain.key),
          "domainKey"        -> domainKey(nodeDomain.domain.key),
          "selector"    -> selectorJson(nodeDomain),
          "order"       -> ujson.Num(nodeDomain.order),
          "loc"         -> loc(nodeDomain.loc)
        )
      }),
      "carriers"                 -> ujson.Arr.from(carrierRecords),
      "constraints"              -> ujson.Arr.from(constraints.map(constraintJson)),
      "protocols"                -> ujson.Arr.from(protocols.zipWithIndex.map { (protocol, ordinal) =>
        val protocolNodes = nodes.collect { case (id, node) if node.protocol eq protocol => id }
        val binds = spec.binds.filter(bind => nodeById(bind.source).protocol eq protocol)
        ujson.Obj(
          "id"    -> ujson.Num(ordinal),
          "nodes" -> ujson.Arr.from(protocolNodes.map(nodeId)),
          "binds" -> ujson.Arr.from(binds.map(bind => bindId(bind.bindId))),
          "carries" -> ujson.Arr.from(protocol.carries.toVector.sortBy(domain => domainOrder(domain.key)).map(domain => domainKey(domain.key)))
        )
      }),
      "activeNodeDomains"          -> ujson.Arr.from(
        resolved.domainAttachments
          .sortBy(attachment => (domainOrder(attachment.key.domain), nodeOrder(attachment.key.node)))
          .map { attachment =>
            ujson.Obj(
              "key"               -> nodeDomainKey(attachment.key),
              "domain"            -> domainDeclId(attachment.declaration),
              "activationReasons" -> ujson.Arr.from(nodeActivationReasons(attachment.key).map(ujson.Str(_))),
              "loc"               -> loc(nodeDomainByKey(attachment.key).loc)
            )
          }
      ),
      "activeBindDomains"          -> ujson.Arr.from(
        spec.binds.flatMap(bind => attachmentsByNode(bind.source).map(bind -> _))
          .sortBy((bind, source) => (domainOrder(source.key.domain), bind.order))
          .map { (bind, source) =>
            val key = source.key.domain
            val target = attachmentByKey(NodeDomainKey(bind.target, key))
            val carried = source.provenance.method == AttachmentMethod.CarrierOut
            val checks = checksByBindDomain(bind.bindId -> key)
            val reasons = Vector(
              Some("endpointNodeDomain"),
              Option.when(carried)("carrier"),
              Option.when(checks.nonEmpty)("protocolCheck"),
              Option.when(carried)("pairedCarrier")
            ).flatten
            ujson.Obj(
              "bind"              -> bindId(bind.bindId),
              "domainKey"              -> domainKey(key),
              "sourceDomain"      -> domainDeclId(source.declaration),
              "targetDomain"      -> domainDeclId(target.declaration),
              "carried"           -> ujson.Bool(carried),
              "checks"            -> ujson.Arr.from(checks.map(ujson.Num(_))),
              "activationReasons" -> ujson.Arr.from(reasons.map(ujson.Str(_))),
              "loc"               -> loc(bind.loc)
            )
          }
      ),
      "identityClasses"          -> ujson.Obj(
        "constraints" -> ujson.Arr.from(identityConstraintClasses.zipWithIndex.map { (members, ordinal) =>
          ujson.Obj(
            "id"             -> ujson.Num(ordinal),
            "domainKey"           -> domainKey(members.head.domain),
            "representative" -> nodeDomainKey(members.head),
            "members"        -> ujson.Arr.from(members.map(nodeDomainKey)),
            "domain"         -> domainDeclId(attachmentByKey(members.head).declaration)
          )
        }),
        "actual"      -> ujson.Arr.from(resolved.domains.zipWithIndex.map { (domain, ordinal) =>
          val members = attachmentsByDeclaration(domain.id)
          ujson.Obj(
            "id"               -> ujson.Num(ordinal),
            "domainKey"             -> domainKey(domain.domain.key),
            "domain"           -> domainDeclId(domain.id),
            "nodeDomains"   -> ujson.Arr.from(members.map(attachment => nodeDomainKey(attachment.key)))
          )
        })
      ),
      "domains"                  -> ujson.Arr.from(resolved.domains.map { d =>
        ujson.Obj(
          "id"           -> domainDeclId(d.id),
          "domainKey"         -> domainKey(d.domain.key),
          "value"        -> d.encodedValue,
          "predecessors" -> ujson.Arr.from(d.predecessors.map(domainDeclId)),
          "requirements" -> ujson.Arr.from(d.requirements.map { r =>
            ujson.Obj("source" -> contributor(r.source), "value" -> r.encoded)
          }),
          "loc"          -> loc(d.loc)
        )
      }),
      "attachments"              -> ujson.Arr.from(resolved.domainAttachments.map { a =>
        ujson.Obj(
          "key"        -> nodeDomainKey(a.key),
          "domain"     -> domainDeclId(a.declaration),
          "provenance" -> provenance(a.provenance)
        )
      }),
      "checks"                -> ujson.Arr.from(resolved.domainChecks.map { r =>
        ujson.Obj("subject" -> ujson.Str(r.subject), "domainKeys" -> ujson.Arr.from(r.domainKeys.map(domainKey)))
      })
    )

  def edges(resolved: ResolvedDesign): ujson.Value =
    ujson.Obj(
      "designEdges" -> ujson.Arr.from(resolved.edges.map { e =>
        val p        = e.protocol
        ujson.Obj(
          "id"        -> bindId(e.bind),
          "down"      -> write(p.downWriter, e.down),
          "up"        -> write(p.upWriter, e.up),
          "edge"      -> write(p.edgeWriter, e.edge),
          "interface" -> interface(e.interface)
        )
      })
    )

  def plan(resolved: ResolvedDesign): ujson.Value =
    def origin(o: PlanOrigin):             ujson.Value = o match
      case PlanOrigin.Design(b)       => ujson.Obj("design" -> bindId(b))
      case PlanOrigin.Verification(s) => ujson.Obj("verification" -> nodeId(s))
    def endpoint(e: LocalEndpoint):        ujson.Value = e match
      case LocalEndpoint.ThisPort(name)        => ujson.Obj("port" -> ujson.Str(name.encoded))
      case LocalEndpoint.ChildPort(inst, port) =>
        ujson.Obj("instance" -> ujson.Str(inst), "port" -> ujson.Str(port.encoded))
    ujson.Obj(
      "probeNodes" -> ujson.Arr.from(resolved.probes.nodes.map(ResolvedProbe.encode)),
      "probes"        -> ujson.Arr.from(resolved.probes.ports.map { probe =>
        ujson.Obj("source" -> nodeId(probe.id), "interface" -> interface(probe.reference))
      }),
      "probeBindings" -> upickle.default.writeJs(resolved.observations),
      "ports"         -> ujson.Arr.from(resolved.portPlans.map { p =>
        ujson.Obj(
          "module"    -> moduleId(p.module),
          "direction" -> ujson.Str(p.direction.toString.toLowerCase),
          "name"      -> ujson.Str(p.name.encoded),
          "interface" -> interface(p.interface),
          "origin"    -> origin(p.origin),
          "loc"       -> loc(p.loc)
        )
      }),
      "wires"         -> ujson.Arr.from(resolved.wirePlans.map { w =>
        ujson.Obj(
          "module" -> moduleId(w.module),
          "from"   -> endpoint(w.from),
          "to"     -> endpoint(w.to),
          "origin" -> origin(w.origin),
          "loc"    -> loc(w.loc)
        )
      }),
      "layers"        -> ujson.Obj.from(
        resolved.layerDecls.toVector
          .filterNot(_._2.isEmpty)
          .sortBy(_._1.path.mkString("/"))
          .map { (m, tree) =>
            m.show -> ujson.Arr.from(tree.paths().map(p => ujson.Arr.from(p.map(ujson.Str(_)))))
          }
      )
    )

  def params(resolved: ResolvedDesign): ujson.Value =
    ujson.Arr.from(resolved.generatorModules.map { g =>
      ujson.Obj(
        "module"    -> moduleId(g.module),
        "generator" -> ujson.Str(g.definition.name),
        "fullParam" -> g.encodedFullParam
      )
    })
