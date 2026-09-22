package me.jiuyang.syntheke.circt

import me.jiuyang.syntheke.*

private[circt] object Compilation:
  private def name(design: ResolvedDesign): String = design.spec.wrapper(ModuleId.root).get.moduleName

  def order(roots: Seq[ResolvedDesign]): Vector[ResolvedDesign] =
    def visit(done: Vector[ResolvedDesign], design: ResolvedDesign): Vector[ResolvedDesign] =
      if done.exists(_ eq design) then done
      else
        require(design.spec.root == ModuleId.root, "elaboration requires a definition-local design")
        val children = design.dependencies.foldLeft(done)((acc, child) => visit(acc, child._2))
        val name = design.spec.wrapper(ModuleId.root).get.moduleName
        require(!children.exists(_.spec.wrapper(ModuleId.root).get.moduleName == name),
          s"distinct boundaries share module name $name")
        children :+ design
    roots.foldLeft(Vector.empty[ResolvedDesign])(visit)

  def directories(ordered: Vector[ResolvedDesign], roots: Seq[ResolvedDesign]): Map[String, os.RelPath] =
    val topNames = roots.map(name).toSet
    val parents = ordered.flatMap(parent =>
      parent.dependencies.map((_, child) => name(child) -> name(parent))).groupMap(_._1)(_._2)
    ordered.reverse.foldLeft(Map.empty[String, os.RelPath]) { (paths, design) =>
      val module = name(design)
      val prefix = if topNames(module) then Vector.empty[String]
      else parents(module).map(parent => paths(parent).segments.toVector).reduce { (left, right) =>
        left.zip(right).takeWhile((a, b) => a == b).map(_._1)
      }
      paths.updated(module, os.RelPath((prefix :+ module).mkString("/")))
    }

  def filelists(units: Vector[ElaboratedDesign]): Vector[ElaboratedDesign] =
    val byName = units.map(u => u.circuitName -> u).toMap
    units.map { unit =>
      val closure = order(Vector(unit.resolved)).map(d => byName(name(d)))
      val headers = unit.resolved.dependencies.map(d => byName(name(d._2))).distinct
        .flatMap(_.verilog.keys.filter(n => n.startsWith("ref_") && n.endsWith(".sv"))).distinct.sorted
      val includes = headers.map(n => s"`include \"$n\"\n").mkString
      val files = unit.verilog.map { (path, text) =>
        val body = if path.endsWith(".sv") then includes + text else text
        val packaged = if path.startsWith("ref_") && path.endsWith(".sv") then
          val guard = s"syntheke_${path.stripSuffix(".sv")}"
          s"`ifndef $guard\n`define $guard\n$body\n`endif\n"
        else body
        path -> packaged
      }
      val sourceFiles = closure.flatMap { dependency =>
        dependency.verilog("filelist.f").linesIterator.filter(_.nonEmpty).map(p => s"${dependency.directory}/$p")
      }
      val filelist = (closure.map(u => s"+incdir+${u.directory}") ++ sourceFiles).mkString("", "\n", "\n")
      val layerLists = files.keys.toVector.sorted.collect {
        case path if path.startsWith(s"layers-${unit.circuitName}-") && path.endsWith(".sv") =>
          val layer = path.stripPrefix(s"layers-${unit.circuitName}-").stripSuffix(".sv")
          s"layers-$layer.f" -> s"${unit.directory}/$path\n"
      }
      unit.copy(verilog = files ++ layerLists + ("filelist.f" -> filelist))
    }
