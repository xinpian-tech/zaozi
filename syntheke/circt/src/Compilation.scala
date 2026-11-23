package me.jiuyang.syntheke.circt

import me.jiuyang.syntheke.*

import scala.collection.immutable.VectorMap

private[circt] object Compilation:
  private def name(design: ResolvedDesign): String = design.spec.wrapper(ModuleId.root).get.moduleName

  def order(roots: Seq[ResolvedDesign]): Vector[ResolvedDesign] =
    def visit(done: Vector[ResolvedDesign], design: ResolvedDesign): Vector[ResolvedDesign] =
      if done.exists(_ eq design) then done
      else design.dependencies.foldLeft(done)((acc, child) => visit(acc, child._2)) :+ design
    roots.foldLeft(Vector.empty[ResolvedDesign])(visit)

  def directories(ordered: Vector[ResolvedDesign], roots: Seq[ResolvedDesign]): VectorMap[String, os.RelPath] =
    val topNames = roots.map(name)
    val parents  = VectorMap.from(
      ordered
        .flatMap(parent => parent.dependencies.map((_, child) => name(child) -> name(parent)))
        .groupedBy(_._1)
        .map((child, edges) => child -> edges.map(_._2))
    )
    ordered.reverse.foldLeft(VectorMap.empty[String, os.RelPath]) { (paths, design) =>
      val module = name(design)
      val prefix =
        if topNames.contains(module) then Vector.empty[String]
        else
          parents(module).map(parent => paths(parent).segments.toVector).reduce { (left, right) =>
            left.zip(right).takeWhile((a, b) => a == b).map(_._1)
          }
      paths.updated(module, os.RelPath((prefix :+ module).mkString("/")))
    }
