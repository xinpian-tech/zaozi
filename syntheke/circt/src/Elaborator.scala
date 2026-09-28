package me.jiuyang.syntheke.circt

import scala.collection.mutable

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.circt.Diagnostics.runOnOpOrThrow

import org.llvm.circt.scalalib.capi.dialect.emit.given_DialectApi
import org.llvm.circt.scalalib.capi.dialect.emit.DialectApi as EmitDialectApi
import org.llvm.circt.scalalib.capi.dialect.firrtl.given_DialectApi
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
  Value,
  WalkEnum,
  WalkResultEnum
}
import org.llvm.mlir.scalalib.capi.pass.{given_OpPassManagerApi, given_PassManagerApi, PassManager, PassManagerApi}

import java.lang.foreign.Arena

final class ElaborationException(message: String) extends RuntimeException(message)

final case class ElaboratedDesign(
  circuitName: String,
  mlirbc:      Array[Byte],
  verilog:     Map[String, String],
  moduleNames: Map[ModuleId, String])

object Elaborator:

  private def fail(message: String): Nothing = throw ElaborationException(message)

  def elaborate(resolved: ResolvedDesign): ElaboratedDesign =
    val spec = resolved.spec
    val mlirbcDir = os.Path(sys.env.getOrElse("ZAOZI_OUTDIR", os.pwd.toString), os.pwd)
    val backendOf: Map[GeneratorDefinition[?], GeneratorBackend] = spec.generators.map { definition =>
      val backend = definition match
        case provider: GeneratorBackendProvider => provider.createBackend()
        case _ => fail(s"missing backend for generator ${definition.name}")
      definition -> backend
    }.toMap

    val moduleNames: Map[ModuleId, String] = spec.moduleOrder.map { id =>
      id -> (spec.modules(id) match
        case g: GeneratorModuleSpec =>
          backendOf(g.definition).moduleName(resolved.generatorModule(id).get.fullParam)
        case w: WrapperModuleSpec   => w.moduleName)
    }.toMap

    val generatorNames = spec.generatorModules.map(g => moduleNames(g.id)).toSet
    spec.moduleOrder.flatMap(spec.wrapper).foreach { w =>
      if generatorNames(w.moduleName) then
        fail(s"wrapper ${w.id.show} is named '${w.moduleName}', which a generator module of this design also takes")
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
        ): Map[String, Value] =
          val names  = instOp.getInherentAttributeByName("portNames")
          val byName = Seq
            .tabulate(names.arrayAttrGetNumElements)(i => names.arrayAttrGetElement(i).stringAttrGetValue -> i)
            .toMap
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
          (byName.keySet -- expected.map(_._1)).toVector.sorted.foreach { extra =>
            fail(s"port mismatch at $subject#$extra: generator port has no corresponding declaration, at $at")
          }
          byName.map((n, i) => n -> instOp.getResult(i))

        def leafPaths(t: LayerTree, prefix: Vector[String] = Vector.empty): Vector[Vector[String]] =
          if t.children.isEmpty then (if prefix.isEmpty then Vector.empty else Vector(prefix))
          else t.children.toVector.sortBy(_._1).flatMap((n, sub) => leafPaths(sub, prefix :+ n))

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

        val generatorLayers: Map[ModuleId, LayerTree] =
          spec.generatorModules
            .flatMap(g =>
              backendOf(g.definition)
                .layers(resolved.generatorModule(g.id).get.fullParam)
                .flatMap(path => (0 until g.id.path.length).map(n => ModuleId(g.id.path.take(n)) -> LayerPath(path)))
            )
            .foldLeft(Map.empty[ModuleId, LayerTree]) { case (acc, (m, lp)) =>
              acc.updated(m, acc.getOrElse(m, LayerTree.empty).add(lp))
            }
            .withDefaultValue(LayerTree.empty)

        val wrapperPorts = resolved.portPlans
          .groupBy(_.module)
          .view.mapValues(_.sortBy(_.name.encoded)).toMap
          .withDefaultValue(Vector.empty)
        val wrapperLayers = spec.moduleOrder.flatMap(spec.wrapper).map { w =>
          w.id -> leafPaths(resolved.layerDecls.getOrElse(w.id, LayerTree.empty).merge(generatorLayers(w.id)))
        }.toMap
        val tbInputs = resolved.observations.ports

        spec.moduleOrder.foreach { id =>
          spec.wrapper(id).foreach { w =>
            val name       = moduleNames(id)
            val ports      = wrapperPorts(id)
            val portIndex  = ports.zipWithIndex.map((p, i) => p.name.encoded -> i).toMap
            val module     = summon[ModuleApi].op(
              name,
              unknownLoc,
              FirrtlConvention.Scalarized,
              ports.map(p => (portField(p), unknownLoc)),
              wrapperLayers(id)
            )
            given Block    = module.block

            def emitChild(c: String): Vector[((String, String), Value)] =
              val childId = id / c
              spec.modules(childId) match
                case gm: GeneratorModuleSpec =>
                  val rgm      = resolved.generatorModule(childId).get
                  val instOp   = backendOf(gm.definition).instantiate(rgm.fullParam, c, gm.loc)
                  val expected = rgm.view.nodes.map { nv =>
                    (nv.node.name, nv.direction == NodeDirection.Outward, nv.edge.interface)
                  } ++ rgm.probeDeclaration.ports.map(p => (p.name, true, p.tpe)) ++ (
                    if spec.testbench.contains(childId) then
                      tbInputs.map(p => (p.portName, false, p.reference.inner))
                    else Vector.empty
                  )
                  checkedPorts(instOp, expected, childId.show, gm.loc.show).toVector.map((n, v) => ((c, n), v))
                case _:  WrapperModuleSpec   =>
                  val childPorts = wrapperPorts(childId)
                  val instOp     = summon[InstanceApi].op(
                    moduleNames(childId),
                    c,
                    FirrtlNameKind.Interesting,
                    unknownLoc,
                    childPorts.map(portField),
                    wrapperLayers(childId)
                  )
                  instOp.operation.appendToBlock()
                  childPorts.zipWithIndex.map((p, i) => ((c, p.name.encoded), instOp.operation.getResult(i)))

            val childValues = w.children.flatMap(emitChild).toMap

            def baseOf(e: LocalEndpoint): Value = e match
              case LocalEndpoint.ThisPort(port)        =>
                portIndex
                  .get(port.encoded)
                  .fold(fail(s"wrapper $name: missing port ${port.encoded}"))(i => module.getIO(i))
              case LocalEndpoint.ChildPort(inst, port) =>
                childValues.getOrElse(
                  (inst, port.encoded),
                  fail(s"wrapper $name: missing child port $inst.${port.encoded}")
                )

            resolved.wirePlans.filter(_.module == id).foreach { wp =>
              wp.origin match
                case PlanOrigin.Design(_)            =>
                  summon[ConnectApi].op(baseOf(wp.from), baseOf(wp.to), unknownLoc).operation.appendToBlock()
                case PlanOrigin.Verification(_) =>
                  wp.to match
                    case LocalEndpoint.ChildPort(_, _) =>
                      val reference = baseOf(wp.from)
                      val read      = summon[RefResolveApi].op(reference, unknownLoc)
                      read.operation.appendToBlock()
                      summon[ConnectApi].op(read.result, baseOf(wp.to), unknownLoc).operation.appendToBlock()
                    case LocalEndpoint.ThisPort(_)            =>
                      summon[RefDefineApi].op(baseOf(wp.to), baseOf(wp.from), unknownLoc).operation.appendToBlock()
            }

            module.appendToCircuit()
          }
        }

        def moduleSymbols(root: Operation): (Set[String], Set[String]) =
          val defined = mutable.Set.empty[String]
          val referenced = mutable.Set.empty[String]
          root.walk(
            op =>
              op.getName.str match
                case "firrtl.instance" =>
                  referenced += op.getInherentAttributeByName("moduleName").flatSymbolRefAttrGetValue
                case "firrtl.module" | "firrtl.extmodule" =>
                  defined += op.getInherentAttributeByName("sym_name").stringAttrGetValue
                case _ => ()
              WalkResultEnum.Advance
            ,
            WalkEnum.PreOrder
          )
          (defined.toSet, referenced.toSet)

        def isNullOp(op: Operation): Boolean           =
          op._segment.get(java.lang.foreign.ValueLayout.ADDRESS, 0).address == 0
        def opsIn(first: Operation): Vector[Operation] =
          Iterator.iterate(first)(_.getNextInBlock).takeWhile(op => !isNullOp(op)).toVector

        def layerTreeOf(op: Operation): (String, LayerTree) =
          val name     = op.getInherentAttributeByName("sym_name").stringAttrGetValue
          val children = opsIn(op.getFirstRegion.getFirstBlock.getFirstOperation)
            .filter(_.getName.str == "firrtl.layer")
            .map(layerTreeOf)
          name -> LayerTree(children.toMap)

        @annotation.tailrec
        def link(needed: List[String], defined: Set[String], layers: LayerTree): LayerTree = needed match
          case Nil                         => layers
          case sym :: rest if defined(sym) => link(rest, defined, layers)
          case sym :: rest                 =>
            val file       = mlirbcDir / s"$sym.mlirbc"
            if !os.exists(file) then fail(s"instantiated module '$sym' has no definition ($file not found)")
            val parsed     = summon[MlirModuleApi].moduleCreateParse(os.read.bytes(file))
            if parsed._segment.get(java.lang.foreign.ValueLayout.ADDRESS, 0).address == 0 then
              fail(s"cannot parse $file")
            val circuitOps = opsIn(parsed.getOperation.getFirstRegion.getFirstBlock.getFirstOperation)
              .filter(_.getName.str == "firrtl.circuit")
              .flatMap(c => opsIn(c.getFirstRegion.getFirstBlock.getFirstOperation))
            val moved      = circuitOps.filter(op => Set("firrtl.module", "firrtl.extmodule")(op.getName.str)).filter { op =>
              val s2     = op.getInherentAttributeByName("sym_name").stringAttrGetValue
              val isStub = op.getName.str == "firrtl.extmodule" && s2 != sym && os.exists(mlirbcDir / s"$s2.mlirbc")
              if defined(s2) || isStub then false
              else
                op.removeFromParent()
                summon[Circuit].block.appendOwnedOperation(op)
                true
            }
            val layers2    = circuitOps
              .filter(_.getName.str == "firrtl.layer")
              .map(layerTreeOf)
              .foldLeft(layers)((t, kv) => t.merge(LayerTree(Map(kv))))
            val defined2   = defined ++ moved.map(_.getInherentAttributeByName("sym_name").stringAttrGetValue)
            if !defined2(sym) then fail(s"instantiated module '$sym' has no definition in $file")
            link(rest ++ moved.flatMap(op => moduleSymbols(op)._2 -- defined2), defined2, layers2)

        val (defined0, referenced0) = moduleSymbols(summon[Circuit].operation)
        val linkedLayers = link((referenced0 -- defined0).toList, defined0, LayerTree.empty)
        emitLayers(resolved.layerDecls.values.foldLeft(linkedLayers)(_.merge(_)), None)

        if !summon[MlirModule].getOperation.verify then fail("MLIR verification of the linked circuit failed")

        val bytecode = new java.io.ByteArrayOutputStream
        summon[MlirModule].getOperation.writeBytecode(bc => bytecode.write(bc))

        given FirtoolOptions = summon[FirtoolApi].firtoolOptionsCreateDefault
        given PassManager    = summon[PassManagerApi].passManagerCreate
        val firtoolOptions   = summon[FirtoolOptions]
        val verilogDir       = os.temp.dir(prefix = s"syntheke-$circuitName-verilog", deleteOnExit = false)
        summon[PassManager].preprocessTransforms(firtoolOptions)
        summon[PassManager].chirrtlToLowFIRRTL(firtoolOptions)
        summon[PassManager].lowFIRRTLToHW(firtoolOptions, "")
        summon[PassManager].hwToSV(firtoolOptions)
        summon[PassManager].exportSplitVerilog(firtoolOptions, verilogDir.toString)
        summon[PassManager].runOnOpOrThrow(
          summon[MlirModule].getOperation,
          s"firtool lowering pipeline for circuit '$circuitName'"
        )
        val verilog          =
          try os.walk(verilogDir).filter(os.isFile).map(p => p.relativeTo(verilogDir).toString -> os.read(p)).toMap
          finally os.remove.all(verilogDir)

        ElaboratedDesign(circuitName, bytecode.toByteArray, verilog, moduleNames)
      finally summon[Context].destroy()
    finally arena.close()
