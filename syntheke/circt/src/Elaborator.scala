package me.jiuyang.syntheke.circt

import scala.collection.immutable.{SortedMap, VectorMap}
import scala.collection.mutable

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.circt.Diagnostics.runOnOpOrThrow

import org.llvm.circt.scalalib.capi.dialect.emit.given_DialectApi
import org.llvm.circt.scalalib.capi.dialect.emit.DialectApi as EmitDialectApi
import org.llvm.circt.scalalib.capi.dialect.firrtl.given_DialectApi
import org.llvm.circt.scalalib.capi.dialect.firrtl.{given_PassApi, PassApi}
import org.llvm.circt.scalalib.capi.dialect.firrtl.{
  given_FirrtlBundleFieldApi,
  given_FirrtlDirectionApi,
  given_FirrtlNameKindApi,
  given_TypeApi,
  DialectApi as FirrtlDialectApi,
  FirrtlBundleField,
  FirrtlBundleFieldApi,
  FirrtlConvention,
  FirrtlLayerConvention,
  FirrtlNameKind,
  TypeApi as FirrtlTypeApi
}
import org.llvm.mlir.scalalib.capi.support.{*, given}
import org.llvm.circt.scalalib.capi.dialect.ltl.given_DialectApi
import org.llvm.circt.scalalib.capi.dialect.ltl.DialectApi as LTLDialectApi
import org.llvm.circt.scalalib.capi.dialect.sv.given_DialectApi
import org.llvm.circt.scalalib.capi.dialect.sv.DialectApi as SvDialectApi
import org.llvm.circt.scalalib.capi.dialect.verif.given_DialectApi
import org.llvm.circt.scalalib.capi.dialect.verif.DialectApi as VerifDialectApi
import org.llvm.circt.scalalib.capi.exportfirrtl.given_ExportFirrtlApi
import org.llvm.circt.scalalib.capi.firtool.{given_FirtoolApi, given_FirtoolOptionsApi, FirtoolApi, FirtoolOptions}
import org.llvm.circt.scalalib.dialect.firrtl.operation.given
import org.llvm.circt.scalalib.dialect.firrtl.operation.{
  Circuit,
  CircuitApi,
  ConnectApi,
  ExtModuleApi,
  InstanceApi,
  Layer as CirctLayer,
  LayerApi,
  ModuleApi,
  RefDefineApi,
  RefResolveApi
}
import org.llvm.mlir.scalalib.capi.ir.{
  given_AttributeApi,
  given_BlockApi,
  given_ContextApi,
  given_IdentifierApi,
  given_LocationApi,
  given_ModuleApi,
  given_NamedAttributeApi,
  given_OperationApi,
  given_RegionApi,
  given_TypeApi,
  given_ValueApi,
  Block,
  Context,
  ContextApi,
  LocationApi,
  Module as MlirModule,
  ModuleApi as MlirModuleApi,
  Operation,
  Type as MlirType,
  Value
}
import org.llvm.mlir.scalalib.capi.pass.{given_OpPassManagerApi, given_PassManagerApi, PassManager, PassManagerApi}

import java.lang.foreign.Arena

final class ElaborationException(message: String) extends RuntimeException(message)

final case class ElaboratedDesign(
  circuitName:                       String,
  directory:                         os.RelPath,
  mlirbc:                            Array[Byte],
  verilog:                           VectorMap[String, String],
  resolved:                          ResolvedDesign,
  private[circt] val layers:         LayerTree,
  private[circt] val definedModules: Vector[String])

object Elaborator:

  private def fail(message: String): Nothing = throw ElaborationException(message)

  def elaborate(roots: Seq[ResolvedDesign]): Vector[ElaboratedDesign] =
    val ordered     = Compilation.order(roots)
    val directories = Compilation.directories(ordered, roots)
    val compiled    = ordered.foldLeft(Vector.empty[ElaboratedDesign]) { (done, resolved) =>
      val dependencies = VectorMap.from(resolved.dependencies.map { (id, definition) =>
        id -> done.find(_.resolved eq definition).get
      })
      val directory    = directories(resolved.spec.wrapper(ModuleId.root).get.moduleName)
      val unit         = elaborateUnit(resolved, dependencies, directory)
      done.foreach { previous =>
        val overlap = previous.definedModules.intersect(unit.definedModules)
        if overlap.nonEmpty then
          fail(
            s"duplicate modules in boundaries ${previous.circuitName} and ${unit.circuitName}: ${overlap.toVector.sorted.mkString(", ")}"
          )
      }
      done :+ unit
    }
    Compilation.filelists(compiled)

  private def elaborateUnit(
    resolved:     ResolvedDesign,
    dependencies: VectorMap[ModuleId, ElaboratedDesign],
    directory:    os.RelPath
  ): ElaboratedDesign =
    val spec       = resolved.spec
    val generators = spec.generatorModules
    def implementation(definition: GeneratorDefinition[?]): CirctGenerator[Any] = definition match
      case generator: CirctGenerator[?] => generator.asInstanceOf[CirctGenerator[Any]]
      case _ => fail(s"generator ${definition.name} has no FIRRTL implementation")
    def fullParam(id: ModuleId):                            Any                 = resolved.generatorModule(id).get.fullParam

    val moduleNames: VectorMap[ModuleId, String] = VectorMap.from(spec.moduleOrder.collect {
      case id if spec.generatorModule(id).isDefined =>
        id -> implementation(spec.generatorModule(id).get.definition).moduleName(fullParam(id))
      case id if spec.wrapper(id).isDefined         => id -> spec.wrapper(id).get.moduleName
    }) ++ dependencies.map((id, compiled) => id -> compiled.circuitName)

    val generatorNames = generators.map(g => moduleNames(g.id))
    (spec.moduleOrder.filter(id => spec.wrapper(id).isDefined) ++ dependencies.keys).foreach { id =>
      if generatorNames.contains(moduleNames(id)) then
        fail(s"${id.show} is named '${moduleNames(id)}', which a generator module of this design also takes")
    }

    val arena = Arena.ofConfined()
    try
      given Arena   = arena
      given Context = summon[ContextApi].contextCreate
      try
        summon[FirrtlDialectApi].loadDialect
        summon[LTLDialectApi].loadDialect
        summon[SvDialectApi].loadDialect
        summon[EmitDialectApi].loadDialect
        summon[VerifDialectApi].loadDialect
        summon[VerifDialectApi].registerPasses

        val unknownLoc = summon[LocationApi].locationUnknownGet

        def portField(port: PortPlan): FirrtlBundleField =
          summon[FirrtlBundleFieldApi].createFirrtlBundleField(
            port.name.encoded,
            port.direction == PortDirection.Input,
            Translate.tpe(port.interface)
          )

        def typeText(t: MlirType): String =
          val sb = new StringBuilder
          t.print(sb ++= _)
          sb.result()

        def firstDiff(exp: MlirType, act: MlirType, path: String): Option[String] =
          if exp.isEquivalentTo(act, true) then None
          else if exp.isBundle && act.isBundle then
            val (ne, na) = (exp.getBundleNumFields.toInt, act.getBundleNumFields.toInt)
            if ne != na then Some(s"$path: $ne fields expected, generator has $na")
            else
              (0 until ne).view.flatMap { i =>
                val (fe, fa) = (exp.getBundleFieldByIndex(i), act.getBundleFieldByIndex(i))
                if fe.getName != fa.getName then Some(s"$path: field $i is '${fa.getName}', expected '${fe.getName}'")
                else if fe.getIsFlip != fa.getIsFlip then
                  Some(s"$path.${fe.getName}: flip is ${fa.getIsFlip}, expected ${fe.getIsFlip}")
                else firstDiff(fe.getType, fa.getType, s"$path.${fe.getName}")
              }.headOption
                .orElse(Some(s"$path: ${typeText(exp)} vs ${typeText(act)}"))
          else if exp.isVector && act.isVector then
            if exp.getVectorElementNum != act.getVectorElementNum then
              Some(
                s"$path: Vec[${exp.getVectorElementNum}] expected, generator has Vec[${act.getVectorElementNum}]"
              )
            else firstDiff(exp.getVectorElementType, act.getVectorElementType, s"$path[]")
          else Some(s"$path: expected ${typeText(exp)}, generator has ${typeText(act)}")

        def checkedPorts(
          instOp:   Operation,
          expected: Vector[(String, Boolean, ProtocolInterface)],
          subject:  String,
          at:       String
        ): VectorMap[String, Value] =
          val names  = instOp.getInherentAttributeByName("portNames")
          val byName = VectorMap.from(
            Vector.tabulate(names.arrayAttrGetNumElements)(i => names.arrayAttrGetElement(i).stringAttrGetValue -> i)
          )
          val dirs   = instOp.getInherentAttributeByName("portDirections")
          expected.foreach { (name, expectOutput, interface) =>
            byName.get(name) match
              case None    =>
                fail(s"port mismatch at $subject#$name: declaration has no matching generator port, at $at")
              case Some(i) =>
                val actualOutput = dirs.denseBoolArrayGetElement(i)
                if actualOutput != expectOutput then
                  fail(
                    s"port mismatch at $subject#$name: port direction is ${
                        if actualOutput then "output" else "input"
                      }, expected ${if expectOutput then "output" else "input"}, at $at"
                  )
                firstDiff(Translate.tpe(interface), instOp.getResult(i).getType, name).foreach { diff =>
                  fail(
                    s"port mismatch at $subject#$name: port type differs from the settled interface at $diff, at $at"
                  )
                }
          }
          byName.keys.toVector.filterNot(expected.map(_._1).contains).sorted.foreach { extra =>
            fail(s"port mismatch at $subject#$extra: generator port has no corresponding declaration, at $at")
          }
          byName.map((n, i) => n -> instOp.getResult(i))

        val definitions  = mutable.LinkedHashMap.empty[String, MlirModule]
        given MlirModule = summon[MlirModuleApi].moduleCreateEmpty(unknownLoc)
        val circuitName  = moduleNames(ModuleId.root)
        given Circuit    = summon[CircuitApi].op(circuitName)
        summon[Circuit].appendToModule()

        def emitLayers(tree: LayerTree, parent: Option[CirctLayer]): Unit =
          tree.children.toVector.sortBy(_._1).foreach { (name, sub) =>
            val op = summon[LayerApi].op(name, unknownLoc, FirrtlLayerConvention.Bind)
            parent match
              case None    => summon[Circuit].block.appendOwnedOperation(op.operation)
              case Some(p) => p.block.appendOwnedOperation(op.operation)
            emitLayers(sub, Some(op))
          }

        // A module declares its planned probe layers and every layer of the modules below it.
        val enabled: VectorMap[ModuleId, LayerTree] =
          def throughout(id: ModuleId, tree: LayerTree) =
            (0 to id.path.length).map(n => ModuleId(id.path.take(n)) -> tree)
          (resolved.layerDecls.toVector ++
            generators.flatMap(g => throughout(g.id, implementation(g.definition).layers(fullParam(g.id)))) ++
            dependencies.toVector.flatMap((id, compiled) => throughout(id, compiled.layers)))
            .groupedBy(_._1)
            .map((module, trees) => module -> trees.map(_._2).reduce(_.merge(_)))
            .to(VectorMap)

        // The ports of each wrapper of this design and of each instance, which has the ports of its design's root.
        val wrapperPorts = (resolved.portPlans.groupedBy(_.module) ++
          dependencies.toVector.map((id, compiled) =>
            id -> compiled.resolved.portPlans.filter(_.module == ModuleId.root)
          ))
          .map((module, ports) => module -> ports.sortBy(_.name.encoded))
          .to(VectorMap)

        dependencies.toVector.sortBy(_._1.show).distinctBy(_._2.circuitName).foreach { (id, compiled) =>
          summon[ExtModuleApi]
            .op(
              compiled.circuitName,
              compiled.circuitName,
              unknownLoc,
              FirrtlConvention.Scalarized,
              wrapperPorts(id).map(p => (portField(p), unknownLoc)),
              compiled.layers.leaves,
              Seq.empty
            )
            .appendToCircuit()
        }

        spec.moduleOrder.filter(id => spec.wrapper(id).isDefined).distinctBy(moduleNames).foreach { id =>
          spec.wrapper(id).foreach { w =>
            val name      = moduleNames(id)
            val ports     = wrapperPorts.getOrElse(id, Vector.empty)
            val portIndex = VectorMap.from(ports.zipWithIndex.map((p, i) => p.name.encoded -> i))
            val module    = summon[ModuleApi].op(
              name,
              unknownLoc,
              FirrtlConvention.Scalarized,
              ports.map(p => (portField(p), unknownLoc)),
              enabled.getOrElse(id, LayerTree.empty).leaves
            )
            given Block   = module.block

            def emitChild(c: String): Vector[((String, String), Value)] =
              val childId = id / c
              spec.modules.get(childId) match
                case Some(gm: GeneratorModuleSpec) =>
                  val rgm      = resolved.generatorModule(childId).get
                  val placed   = implementation(gm.definition).instantiate(rgm.fullParam, c, gm.loc)
                  placed.definitions.foreach((name, definition) => definitions.getOrElseUpdate(name, definition))
                  val instOp   = placed.instance
                  val expected = rgm.view.nodes.map { nv =>
                    (nv.node.name, nv.edge.bind.source == nv.node, nv.edge.interface)
                  } ++ rgm.probeDeclaration.ports.map(p => (p.name, true, p.tpe)) ++ (
                    resolved.observations
                      .get(childId)
                      .toVector
                      .flatMap(_.sources)
                      .map(p => (p.portName, false, p.reference.inner))
                  )
                  checkedPorts(instOp, expected, childId.show, gm.loc.show).toVector.map((n, v) => ((c, n), v))
                case _                             =>
                  val childPorts = wrapperPorts.getOrElse(childId, Vector.empty)
                  val instOp     = summon[InstanceApi].op(
                    moduleNames(childId),
                    c,
                    FirrtlNameKind.Interesting,
                    unknownLoc,
                    childPorts.map(portField),
                    enabled.getOrElse(childId, LayerTree.empty).leaves
                  )
                  instOp.operation.appendToBlock()
                  childPorts.zipWithIndex.map((p, i) => ((c, p.name.encoded), instOp.operation.getResult(i)))

            val childValues = VectorMap.from(w.children.flatMap(emitChild))

            def baseOf(e: LocalEndpoint): Value = e match
              case LocalEndpoint.ThisPort(port)        => module.getIO(portIndex(port.encoded))
              case LocalEndpoint.ChildPort(inst, port) => childValues((inst, port.encoded))

            resolved.wirePlans.filter(_.module == id).foreach { wp =>
              wp.kind match
                case WireKind.Connect         =>
                  summon[ConnectApi].op(baseOf(wp.from), baseOf(wp.to), unknownLoc).operation.appendToBlock()
                case WireKind.DefineReference =>
                  summon[RefDefineApi].op(baseOf(wp.to), baseOf(wp.from), unknownLoc).operation.appendToBlock()
                case WireKind.ReadReference   =>
                  val read = summon[RefResolveApi].op(baseOf(wp.from), unknownLoc)
                  read.operation.appendToBlock()
                  summon[ConnectApi].op(read.result, baseOf(wp.to), unknownLoc).operation.appendToBlock()
            }

            module.appendToCircuit()
          }
        }

        // The circuits the generators elaborated join this one; an extmodule standing for a module resolves to it.
        emitLayers(enabled.values.foldLeft(LayerTree.empty)(_.merge(_)), None)
        definitions.values.foreach { definition =>
          val circuit = definition.getBody.getFirstOperation
          circuit.removeFromParent()
          summon[MlirModule].getBody.appendOwnedOperation(circuit)
        }
        val linker = summon[PassManagerApi].passManagerCreate
        linker.addOwnedPass(summon[PassApi].linkCircuitsPass(circuitName, noMangle = true))
        linker.runOnOpOrThrow(summon[MlirModule].getOperation, s"linking circuit '$circuitName'")

        def isNullOp(op: Operation): Boolean           =
          op._segment.get(java.lang.foreign.ValueLayout.ADDRESS, 0).address == 0
        def opsIn(first: Operation): Vector[Operation] =
          Iterator.iterate(first)(_.getNextInBlock).takeWhile(op => !isNullOp(op)).toVector
        def layersIn(op: Operation): LayerTree         =
          LayerTree(SortedMap.from(opsIn(op.getFirstRegion.getFirstBlock.getFirstOperation).collect {
            case layer if layer.getName.str == "firrtl.layer" =>
              layer.getInherentAttributeByName("sym_name").stringAttrGetValue -> layersIn(layer)
          }))
        val layers = layersIn(summon[MlirModule].getBody.getFirstOperation)

        if !summon[MlirModule].getOperation.verify then fail("MLIR verification of the linked circuit failed")

        val bytecode = new java.io.ByteArrayOutputStream
        summon[MlirModule].getOperation.writeBytecode(bc => bytecode.write(bc))

        given FirtoolOptions = summon[FirtoolApi].firtoolOptionsCreateDefault
        val firtoolOptions   = summon[FirtoolOptions]
        val verilogDir       = os.temp.dir(prefix = s"syntheke-$circuitName-verilog", deleteOnExit = false)
        given PassManager    = summon[PassManagerApi].passManagerCreate
        summon[PassManager].preprocessTransforms(firtoolOptions)
        summon[PassManager].chirrtlToLowFIRRTL(firtoolOptions)
        summon[PassManager].lowFIRRTLToHW(firtoolOptions, "")
        summon[PassManager].hwToSV(firtoolOptions)
        summon[PassManager].exportSplitVerilog(firtoolOptions, verilogDir.toString)
        summon[PassManager].runOnOpOrThrow(
          summon[MlirModule].getOperation,
          s"firtool lowering pipeline for circuit '$circuitName'"
        )
        val definedModules   = opsIn(summon[MlirModule].getOperation.getFirstRegion.getFirstBlock.getFirstOperation)
          .filter(_.getName.str == "hw.module")
          .map(_.getInherentAttributeByName("sym_name").stringAttrGetValue)
          .distinct
        val verilog          =
          try
            VectorMap.from(
              os.walk(verilogDir)
                .filter(os.isFile)
                .map(p => p.relativeTo(verilogDir).toString -> os.read(p))
                .sortBy(_._1)
            )
          finally os.remove.all(verilogDir)

        ElaboratedDesign(circuitName, directory, bytecode.toByteArray, verilog, resolved, layers, definedModules)
      finally summon[Context].destroy()
    finally arena.close()
