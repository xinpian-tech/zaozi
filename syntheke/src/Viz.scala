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
          val rendered = e.protocol.asInstanceOf[Protocol { type Edge = Any }].render(e.edge)
          rendered.label
        }
    )

  private def render(spec: DesignSpec, edgeLabel: BindId => Option[String]): String =
    val out = new StringBuilder
    out ++= "digraph design {\n  rankdir=LR;\n  node [shape=circle, fontsize=9];\n"

    var clusterId = 0
    def emitModule(id: ModuleId, indent: String): Unit =
      spec.modules(id) match
        case w: WrapperModuleSpec   =>
          out ++= s"${indent}subgraph cluster_$clusterId {\n"
          clusterId += 1
          out ++= s"$indent  label=${q(if id.path.isEmpty then "<root>" else id.path.last)};\n"
          w.children.foreach(c => emitModule(id / c, indent + "  "))
          out ++= s"$indent}\n"
        case g: GeneratorModuleSpec =>
          out ++= s"${indent}subgraph cluster_$clusterId {\n"
          clusterId += 1
          out ++= s"$indent  label=${q(id.path.last)};\n  $indent style=filled; fillcolor=\"#f4f6f8\";\n"
          g.nodes.foreach { n =>
            val shape = n.direction match
              case NodeDirection.Inward  => "circle"
              case NodeDirection.Outward => "doublecircle"
            out ++= s"$indent  ${vertexId(ModuleNodeId(id, n.name))} [label=${q(n.name)}, shape=$shape];\n"
          }
          g.dvSources.foreach(s =>
            out ++= s"$indent  ${dvSourceId(DVSourceId(id, s.name))} [label=${q(s.name)}, shape=diamond, color=purple];\n"
          )
          g.dvSinks.foreach(s =>
            out ++= s"$indent  ${dvSinkId(DVSinkId(id, s.name))} [label=${q(s.name)}, shape=Mdiamond, color=purple];\n"
          )
          out ++= s"$indent}\n"
    emitModule(ModuleId.root, "  ")

    // Module-internal parameter dependencies: dashed, inside the generator cluster.
    spec.generatorModules.foreach { g =>
      g.dependencies.foreach { dep =>
        out ++= s"  ${vertexId(ModuleNodeId(g.id, dep.from))} -> ${vertexId(ModuleNodeId(g.id, dep.to))} [style=dashed, color=gray];\n"
      }
    }
    // Design binds: solid, optionally labeled with the rendered edge.
    spec.binds.foreach { b =>
      val label = edgeLabel(b.bindId).map(l => s" [label=${q(l)}]").getOrElse("")
      out ++= s"  ${vertexId(b.source)} -> ${vertexId(b.target)}$label;\n"
    }
    // Verification binds: dotted purple.
    spec.dvBinds.foreach { b =>
      out ++= s"  ${dvSourceId(b.source)} -> ${dvSinkId(b.sink)} [style=dotted, color=purple];\n"
    }
    out ++= "}\n"
    out.result()
