// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke

/** Module identity and deduplication by structural key (doc @sec-dedup).
  *
  * Instances with the same structural key share one emitted FIRRTL module definition:
  *   - generator module key = canonical serialization of (`GeneratorId`, canonical FullParam);
  *   - wrapper module key = canonical serialization of (sorted ports, child instances in declaration order with their
  *     structural keys, sorted wires as local endpoint references, sorted FIRRTL layers).
  *
  * Global `ModuleId` / `ModuleNodeId` / `BindId` and declaration locations never enter a key. Wrapper keys are computed
  * bottom-up over the hierarchy tree.
  */
object Dedup:

  /** One module definition to emit: its name, structural key, and every instance sharing it (hierarchy preorder). */
  final case class ModuleDefinition(name: String, key: String, instances: Vector[ModuleId])

  final case class DedupResult(
    definitions: Vector[ModuleDefinition], // ordered by first preorder occurrence
    keyOf:       Map[ModuleId, String],
    nameOf:      Map[ModuleId, String])

  /** Canonical JSON: object keys sorted recursively; arrays keep order. */
  private def canonical(v: ujson.Value): ujson.Value = v match
    case obj: ujson.Obj => ujson.Obj.from(obj.value.toVector.sortBy(_._1).map((k, w) => k -> canonical(w)))
    case arr: ujson.Arr => ujson.Arr.from(arr.value.map(canonical))
    case other => other

  private def interfaceKey(tpe: ProtocolInterface): ujson.Value = tpe match
    case ProtocolInterface.Bundle(fields) =>
      // Bundle fields keep the protocol-defined order: they are part of the interface structure.
      ujson.Arr.from(fields.map { f =>
        ujson.Arr(ujson.Str(f.name), ujson.Bool(f.flip), interfaceKey(f.tpe))
      })
    case ProtocolInterface.Vec(n, e)      => ujson.Arr(ujson.Str("vec"), ujson.Num(n), interfaceKey(e))
    case ProtocolInterface.UInt(w)        => ujson.Arr(ujson.Str("uint"), ujson.Num(w))
    case ProtocolInterface.SInt(w)        => ujson.Arr(ujson.Str("sint"), ujson.Num(w))
    case ProtocolInterface.Bool           => ujson.Str("bool")
    case ProtocolInterface.Clock          => ujson.Str("clock")
    case ProtocolInterface.Reset          => ujson.Str("reset")
    case ProtocolInterface.Probe(i, l)    =>
      ujson.Arr(ujson.Str("probe"), interfaceKey(i), ujson.Arr.from(l.segments.map(ujson.Str(_))))

  private def endpointKey(e: LocalEndpoint): ujson.Value = e match
    case LocalEndpoint.ThisPort(name)             => ujson.Arr(ujson.Str("this"), ujson.Str(name.encoded))
    case LocalEndpoint.ChildPort(inst, port, sub) =>
      ujson.Arr(ujson.Str("child"), ujson.Str(inst), ujson.Str(port.encoded), ujson.Str(sub.show))

  /** Compute structural keys, deduplicated definitions and module names for a resolved design.
    *
    * @param candidateName
    *   candidate module name per instance (doc: the build-time type name or an explicit name); defaults to the instance
    *   name, `"Top"` for the root.
    */
  def dedup(
    resolved:      ResolvedDesign,
    candidateName: ModuleId => String = _.path.lastOption.getOrElse("Top")
  ): DedupResult =
    val spec  = resolved.spec
    val keyOf = scala.collection.mutable.Map.empty[ModuleId, String]

    // Bottom-up: reverse preorder guarantees children before parents.
    spec.moduleOrder.reverseIterator.foreach { id =>
      val key = spec.modules(id) match
        case g: GeneratorModuleSpec =>
          val gm = resolved.generatorModule(id).get
          ujson.write(
            ujson.Arr(
              ujson.Str("generator"),
              ujson.Str(g.entry.id.qualifiedName),
              ujson.Str(g.entry.id.version),
              canonical(gm.encodedFullParam)
            )
          )
        case w: WrapperModuleSpec   =>
          val ports    = resolved.portPlans
            .filter(_.module == id)
            .sortBy(_.name.encoded)
            .map(p => ujson.Arr(ujson.Str(p.name.encoded), ujson.Str(p.direction.toString), interfaceKey(p.interface)))
          val children = w.children.map(c => ujson.Arr(ujson.Str(c), ujson.Str(keyOf(id / c))))
          val wires    = resolved.wirePlans
            .filter(_.module == id)
            .map(wp => (ujson.write(endpointKey(wp.from)), ujson.write(endpointKey(wp.to))))
            .sorted
            .map((f, t) => ujson.Arr(ujson.read(f), ujson.read(t)))
          val layers   = resolved.layerDecls
            .getOrElse(id, LayerTree.empty)
            .paths()
            .map(p => ujson.Arr.from(p.map(ujson.Str(_))))
          ujson.write(
            ujson.Arr(
              ujson.Str("wrapper"),
              ujson.Arr.from(ports),
              ujson.Arr.from(children),
              ujson.Arr.from(wires),
              ujson.Arr.from(layers)
            )
          )
      keyOf(id) = key
    }

    // One definition per key; candidate name of the first preorder instance; same-name different-key gets a
    // suffix by the key's first preorder occurrence.
    val byKeyFirstSeen = scala.collection.mutable.LinkedHashMap.empty[String, Vector[ModuleId]]
    spec.moduleOrder.foreach(id => byKeyFirstSeen(keyOf(id)) = byKeyFirstSeen.getOrElse(keyOf(id), Vector.empty) :+ id)

    val nameCount   = scala.collection.mutable.Map.empty[String, Int]
    val definitions = byKeyFirstSeen.toVector.map { (key, instances) =>
      val candidate = candidateName(instances.head)
      val n         = nameCount.getOrElse(candidate, 0)
      nameCount(candidate) = n + 1
      val name      = if n == 0 then candidate else s"${candidate}_$n"
      ModuleDefinition(name, key, instances)
    }
    val nameOf      = definitions.flatMap(d => d.instances.map(_ -> d.name)).toMap

    DedupResult(definitions, keyOf.toMap, nameOf)
