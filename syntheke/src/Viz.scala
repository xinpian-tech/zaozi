// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke

/** Graph exports (doc @sec-visualization): the hierarchy tree maps to nested clusters; inward and outward nodes, binds
  * and module-internal parameter dependencies are always shown. The pre-negotiation view is available from a
  * [[DesignSpec]]; the post-negotiation view adds each edge's `Down` / `Up` / `Edge` summary from the protocol's render
  * metadata.
  */
object Viz:

  private def q(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  private def vertexId(n:   ModuleNodeId): String = q(n.show)
  private def dvSourceId(n: DVSourceId):   String = q("dv:" + n.show)
  private def dvSinkId(n:   DVSinkId):     String = q("dv:" + n.show)

  /** The pre-negotiation view. */
  def dot(spec: DesignSpec): String = render(spec, edgeLabel = _ => None)

  /** The post-negotiation view: bind edges labeled with the protocol's rendered edge summary. */
  def dot(resolved: ResolvedDesign): String =
    val byBind = resolved.edges.map(e => e.bind -> e).toMap
    render(
      resolved.spec,
      edgeLabel = bind =>
        byBind.get(bind).map { e =>
          e.protocol.asInstanceOf[Protocol { type Edge = Any }].render(e.edge).label
        }
    )

  /** GraphML export: nodes carry `module`, `name`, `direction` keys; edges carry `kind` (bind / dependency /
    * verification) and, post-negotiation, the rendered `label`.
    */
  def graphml(resolved: ResolvedDesign): String = graphmlImpl(resolved.spec, resolved.edges.map(e => e.bind -> e).toMap)
  def graphml(spec:     DesignSpec):     String = graphmlImpl(spec, Map.empty)

  private def graphmlImpl(spec: DesignSpec, byBind: Map[BindId, ResolvedEdge]): String =
    def esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    val header    = Vector(
      """<?xml version="1.0" encoding="UTF-8"?>""",
      """<graphml xmlns="http://graphml.graphdrawing.org/xmlns">""",
      """  <key id="module" for="node" attr.name="module" attr.type="string"/>""",
      """  <key id="name" for="node" attr.name="name" attr.type="string"/>""",
      """  <key id="direction" for="node" attr.name="direction" attr.type="string"/>""",
      """  <key id="kind" for="edge" attr.name="kind" attr.type="string"/>""",
      """  <key id="label" for="edge" attr.name="label" attr.type="string"/>""",
      """  <graph id="design" edgedefault="directed">"""
    )
    val nodes     = spec.generatorModules.flatMap { g =>
      g.nodes.flatMap { n =>
        Vector(
          s"""    <node id="${esc(ModuleNodeId(g.id, n.name).show)}">""",
          s"""      <data key="module">${esc(g.id.show)}</data>""",
          s"""      <data key="name">${esc(n.name)}</data>""",
          s"""      <data key="direction">${n.direction.toString.toLowerCase}</data>""",
          "    </node>"
        )
      } ++ (g.dvSources.map(_.name) ++ g.dvSinks.map(_.name)).flatMap { name =>
        Vector(
          s"""    <node id="dv:${esc(DVSourceId(g.id, name).show)}">""",
          s"""      <data key="module">${esc(g.id.show)}</data>""",
          s"""      <data key="name">${esc(name)}</data>""",
          "    </node>"
        )
      } ++ g.dependencies.map { dep =>
        s"""    <edge source="${esc(ModuleNodeId(g.id, dep.from).show)}" target="${esc(
            ModuleNodeId(g.id, dep.to).show
          )}"><data key="kind">dependency</data></edge>"""
      }
    }
    val bindEdges = spec.binds.map { b =>
      val label = byBind
        .get(b.bindId)
        .map { e =>
          val rendered = e.protocol.asInstanceOf[Protocol { type Edge = Any }].render(e.edge)
          s"""<data key="label">${esc(rendered.label)}</data>"""
        }
        .getOrElse("")
      s"""    <edge source="${esc(b.source.show)}" target="${esc(
          b.target.show
        )}"><data key="kind">bind</data>$label</edge>"""
    }
    val dvEdges   = spec.dvBinds.map { b =>
      s"""    <edge source="dv:${esc(b.source.show)}" target="dv:${esc(
          b.sink.show
        )}"><data key="kind">verification</data></edge>"""
    }
    (header ++ nodes ++ bindEdges ++ dvEdges ++ Vector("  </graph>", "</graphml>", "")).mkString("\n")

  private def render(spec: DesignSpec, edgeLabel: BindId => Option[String]): String =
    val clusterOf = spec.moduleOrder.zipWithIndex.toMap

    def moduleLines(id: ModuleId, indent: String): Vector[String] =
      spec.modules(id) match
        case w: WrapperModuleSpec   =>
          Vector(
            s"${indent}subgraph cluster_${clusterOf(id)} {",
            s"$indent  label=${q(if id.path.isEmpty then "<root>" else id.path.last)};"
          ) ++ w.children.flatMap(c => moduleLines(id / c, indent + "  ")) :+ s"$indent}"
        case g: GeneratorModuleSpec =>
          Vector(
            s"${indent}subgraph cluster_${clusterOf(id)} {",
            s"$indent  label=${q(id.path.last)};",
            s"$indent  style=filled; fillcolor=\"#f4f6f8\";"
          ) ++ g.nodes.map { n =>
            val shape = n.direction match
              case NodeDirection.Inward  => "circle"
              case NodeDirection.Outward => "doublecircle"
            s"$indent  ${vertexId(ModuleNodeId(id, n.name))} [label=${q(n.name)}, shape=$shape];"
          } ++ g.dvSources.map(s =>
            s"$indent  ${dvSourceId(DVSourceId(id, s.name))} [label=${q(s.name)}, shape=diamond, color=purple];"
          ) ++ g.dvSinks.map(s =>
            s"$indent  ${dvSinkId(DVSinkId(id, s.name))} [label=${q(s.name)}, shape=Mdiamond, color=purple];"
          ) :+ s"$indent}"

    val deps  = spec.generatorModules.flatMap { g =>
      g.dependencies.map { dep =>
        s"  ${vertexId(ModuleNodeId(g.id, dep.from))} -> ${vertexId(ModuleNodeId(g.id, dep.to))} [style=dashed, color=gray];"
      }
    }
    val binds = spec.binds.map { b =>
      val label = edgeLabel(b.bindId).map(l => s" [label=${q(l)}]").getOrElse("")
      s"  ${vertexId(b.source)} -> ${vertexId(b.target)}$label;"
    }
    val dv    = spec.dvBinds.map { b =>
      s"  ${dvSourceId(b.source)} -> ${dvSinkId(b.sink)} [style=dotted, color=purple];"
    }

    (Vector("digraph design {", "  rankdir=LR;", "  node [shape=circle, fontsize=9];") ++
      moduleLines(ModuleId.root, "  ") ++ deps ++ binds ++ dv :+ "}" :+ "").mkString("\n")
