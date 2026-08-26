// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.circt

import scala.collection.mutable

import me.jiuyang.syntheke.*
import me.jiuyang.zaozi.default.runOnOpOrThrow

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
  LayerBlockApi,
  ModuleApi,
  OpenSubfieldApi,
  RefDefineApi,
  RefResolveApi,
  SubfieldApi
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

/** Elaboration errors, reported separately from [[NegotiationError]] (doc @dec-binding-check). */
enum ElaborationError:
  /** No backend was registered for a generator entry. */
  case MissingBackend(module: ModuleId, generator: GeneratorId)

  /** A generator port is missing or structurally different from the settled ProtocolBundle. */
  case PortMismatch(module: ModuleId, endpoint: String, detail: String, loc: SourceLocation)

  /** An instantiated module has no definition after linking every dumped circuit. */
  case MissingModule(moduleName: String)

  /** The linked circuit failed MLIR verification or an internal consistency check. */
  case InvalidCircuit(detail: String)

  /** A feature the CIRCT elaborator does not support yet. */
  case Unsupported(detail: String)

  def show: String = this match
    case MissingBackend(m, g)       => s"missing backend for generator ${g.show} at ${m.show}"
    case PortMismatch(m, e, d, loc) => s"port mismatch at ${m.show}#$e: $d (${loc.show})"
    case MissingModule(name)        => s"instantiated module '$name' has no definition"
    case InvalidCircuit(d)          => s"invalid circuit: $d"
    case Unsupported(d)             => s"unsupported: $d"

/** The enacted design: FIRRTL and Verilog text plus the module-name assignment. */
final case class ElaboratedDesign(
  circuitName: String,
  firrtl:      String,
  verilog:     String,
  moduleNames: Map[ModuleId, String])

/** The Elaborate phase, CIRCT backend (doc @ch-hardware, @sec-wrapper-emission, @sec-elaboration-flow).
  *
  * Wrapper modules are emitted directly through the CIRCT C-API from the negotiated plans: dangle ports, one instance
  * per child, bundle-level connects, and layer declarations. Generator modules are enacted by their
  * [[GeneratorBackend]] (zaozi), which dumps per-module `.mlirbc` circuits; the elaborator links those into the design
  * circuit, verifies it, and runs the firtool pipeline to Verilog — no textual FIRRTL is ever constructed by hand.
  */
object Elaborator:

  def elaborate(
    resolved:  ResolvedDesign,
    backends:  Seq[GeneratorBackend],
    mlirbcDir: os.Path = os.Path(sys.env.getOrElse("ZAOZI_OUTDIR", os.pwd.toString), os.pwd)
  ): Either[Vector[ElaborationError], ElaboratedDesign] =
    val errors    = mutable.ArrayBuffer.empty[ElaborationError]
    val backendOf = backends.map(b => b.id -> b).toMap
    val spec      = resolved.spec
    val dd        = Dedup.dedup(resolved)

    // Module names: generator modules are named by their backend (stable per canonical FullParam, matching the
    // structural key); wrapper modules by the dedup naming rules.
    val moduleNames = mutable.Map.empty[ModuleId, String]
    spec.moduleOrder.foreach { id =>
      spec.modules(id) match
        case g: GeneratorModuleSpec =>
          backendOf.get(g.entry.id) match
            case Some(b) => moduleNames(id) = b.moduleName(resolved.generatorModule(id).get.fullParam)
            case None    => errors += ElaborationError.MissingBackend(id, g.entry.id)
        case _: WrapperModuleSpec   => moduleNames(id) = dd.nameOf(id)
    }
    if errors.nonEmpty then return Left(errors.toVector)

    val arena = Arena.ofConfined()
    var context: Context = null
    try
      given Arena   = arena
      given Context = summon[ContextApi].contextCreate
      context = summon[Context]
      summon[FirrtlDialectApi].loadDialect
      summon[LTLDialectApi].loadDialect
      summon[SvDialectApi].loadDialect
      summon[EmitDialectApi].loadDialect
      summon[VerifDialectApi].loadDialect
      summon[VerifDialectApi].registerPasses

      val unknownLoc = summon[LocationApi].locationUnknownGet

      def translate(t: ProtocolInterface): MlirType = Translate.tpe(t)

      def leafPaths(t: LayerTree, prefix: Vector[String] = Vector.empty): Vector[Vector[String]] =
        if t.children.isEmpty then (if prefix.isEmpty then Vector.empty else Vector(prefix))
        else t.children.toVector.sortBy(_._1).flatMap((n, sub) => leafPaths(sub, prefix :+ n))

      given MlirModule = summon[MlirModuleApi].moduleCreateEmpty(unknownLoc)
      val circuitName  = dd.nameOf(ModuleId.root)
      given Circuit    = summon[CircuitApi].op(circuitName)
      summon[Circuit].appendToModule()

      // Layer symbol definitions, once, from the union of every wrapper's layer tree.
      def emitLayers(tree: LayerTree, parent: Option[CirctLayer]): Unit =
        tree.children.toVector.sortBy(_._1).foreach { (name, sub) =>
          val op = summon[LayerApi].op(name, unknownLoc, FirrtlLayerConvention.Bind)
          parent match
            case None    => summon[Circuit].block.appendOwnedOperation(op.operation)
            case Some(p) => p.block.appendOwnedOperation(op.operation)
          emitLayers(sub, Some(op))
        }
      emitLayers(resolved.layerDecls.values.foldLeft(LayerTree.empty)(_.merge(_)), None)

      // ============ wrapper modules: one firrtl.module per structural key ============
      dd.definitions.foreach { d =>
        val rep = d.instances.head
        spec.wrapper(rep).foreach { w =>
          val ports      = resolved.portPlans.filter(_.module == rep)
          val portFields = ports.map { p =>
            summon[FirrtlBundleFieldApi].createFirrtlBundleField(
              p.name.encoded,
              p.direction == PortDirection.Input,
              translate(p.interface)
            )
          }
          val portIndex  = ports.zipWithIndex.map((p, i) => p.name.encoded -> i).toMap
          val module     = summon[ModuleApi].op(
            d.name,
            unknownLoc,
            FirrtlConvention.Scalarized,
            portFields.map(f => (f, unknownLoc)),
            leafPaths(resolved.layerDecls.getOrElse(rep, LayerTree.empty))
          )
          given Block    = module.block

          val childValues = mutable.Map.empty[(String, String), Value]
          // Probe sinks are enacted under a layerblock: FIRRTL has no input probe ports, so the wrapper resolves
          // every probe and feeds plain data into the sink instance, which lives inside the layer (bind pattern).
          val sinkBlocks  = mutable.Map.empty[String, Block]
          w.children.foreach { c =>
            val childId = rep / c
            spec.modules(childId) match
              case gm: GeneratorModuleSpec =>
                val rgm        = resolved.generatorModule(childId).get
                val sinkLayers = rgm.view.verification.sinks
                  .flatMap(sv => resolved.dvGroups.find(_.sink == sv.sink).toVector.flatMap(_.layers))
                  .distinct
                val instBlock: Block =
                  if rgm.view.verification.sinks.isEmpty then summon[Block]
                  else if rgm.view.nodes.nonEmpty then
                    errors += ElaborationError.Unsupported(
                      s"sink generator ${childId.show} mixes probe sinks with design nodes"
                    )
                    summon[Block]
                  else if sinkLayers.sizeIs != 1 then
                    errors += ElaborationError.Unsupported(
                      s"sink generator ${childId.show} collects probes from ${sinkLayers.size} distinct layers"
                    )
                    summon[Block]
                  else
                    // Layerblocks nest structurally: `layerblock @a { layerblock @a::@b { … } }`.
                    var blk = summon[Block]
                    sinkLayers.head.segments.indices.foreach { i =>
                      val lb = summon[LayerBlockApi].op(sinkLayers.head.segments.take(i + 1), unknownLoc)
                      locally {
                        given Block = blk
                        lb.operation.appendToBlock()
                      }
                      blk = lb.block
                    }
                    sinkBlocks(c) = blk
                    blk
                val instOp = locally {
                  given Block = instBlock
                  backendOf(gm.entry.id).instantiate(rgm.fullParam, c, gm.loc)
                }
                val names = instOp.getInherentAttributeByName("portNames")
                val count  = names.arrayAttrGetNumElements
                val byName = Seq
                  .tabulate(count)(i => names.arrayAttrGetElement(i).stringAttrGetValue -> i)
                  .toMap
                byName.foreach((n, i) => childValues((c, n)) = instOp.getResult(i))

                // The single binding checkpoint (@dec-binding-check). Everything the settled design promises about
                // this generator's ports is verified here, and only here: presence, root direction, and exact
                // interface structure (with the first divergence path on mismatch); ports the generator declares
                // beyond the settled design are rejected too.
                val dirs = instOp.getInherentAttributeByName("portDirections")

                def typeText(t: MlirType): String =
                  val sb = new StringBuilder
                  t.print(sb ++= _)
                  sb.result()

                /** First path where the two types diverge, with the local shapes; None when equivalent. */
                def firstDiff(exp: MlirType, act: MlirType, path: String): Option[String] =
                  if exp.isEquivalentTo(act, true) then None
                  else if exp.isBundle && act.isBundle then
                    val (ne, na) = (exp.getBundleNumFields.toInt, act.getBundleNumFields.toInt)
                    if ne != na then Some(s"$path: $ne fields expected, generator has $na")
                    else
                      (0 until ne).view.flatMap { i =>
                        val (fe, fa) = (exp.getBundleFieldByIndex(i), act.getBundleFieldByIndex(i))
                        if fe.getName != fa.getName then
                          Some(s"$path: field $i is '${fa.getName}', expected '${fe.getName}'")
                        else if fe.getIsFlip != fa.getIsFlip then
                          Some(s"$path.${fe.getName}: flip is ${fa.getIsFlip}, expected ${fe.getIsFlip}")
                        else firstDiff(fe.getType, fa.getType, s"$path.${fe.getName}")
                      }.headOption.orElse(Some(s"$path: ${typeText(exp)} vs ${typeText(act)}"))
                  else if exp.isVector && act.isVector then
                    if exp.getVectorElementNum != act.getVectorElementNum then
                      Some(
                        s"$path: Vec[${exp.getVectorElementNum}] expected, generator has Vec[${act.getVectorElementNum}]"
                      )
                    else firstDiff(exp.getVectorElementType, act.getVectorElementType, s"$path[]")
                  else Some(s"$path: expected ${typeText(exp)}, generator has ${typeText(act)}")

                def checkPort(name: String, expectOutput: Boolean, expectedInterface: ProtocolInterface): Unit =
                  byName.get(name) match
                    case None    =>
                      errors += ElaborationError.PortMismatch(
                        childId,
                        name,
                        "declared endpoint has no matching generator port",
                        gm.loc
                      )
                    case Some(i) =>
                      val actualOutput = dirs.denseBoolArrayGetElement(i)
                      if actualOutput != expectOutput then
                        errors += ElaborationError.PortMismatch(
                          childId,
                          name,
                          s"port direction is ${
                              if actualOutput then "output" else "input"
                            }, expected ${if expectOutput then "output" else "input"}",
                          gm.loc
                        )
                      firstDiff(translate(expectedInterface), instOp.getResult(i).getType, name).foreach { diff =>
                        errors += ElaborationError.PortMismatch(
                          childId,
                          name,
                          s"port type differs from the settled interface at $diff",
                          gm.loc
                        )
                      }

                rgm.view.nodes.foreach { nv =>
                  checkPort(nv.node.name, nv.direction == NodeDirection.Outward, nv.edge.interface)
                }
                // Probe sources keep their probe types; sink ports carry the probe-stripped interface
                // (the shape after ref.resolve at this wrapper).
                rgm.view.verification.sources.foreach(s => checkPort(s.source.name, true, s.interface))
                rgm.view.verification.sinks
                  .foreach(s => checkPort(s.sink.name, false, ProtocolBundle.stripProbes(s.interfaces.sink)))
                val declared = rgm.view.nodes.map(_.node.name).toSet ++
                  rgm.view.verification.sources.map(_.source.name) ++
                  rgm.view.verification.sinks.map(_.sink.name)
                (byName.keySet -- declared).toVector.sorted.foreach { extra =>
                  errors += ElaborationError.PortMismatch(
                    childId,
                    extra,
                    "generator port has no corresponding declared endpoint",
                    gm.loc
                  )
                }
              case _:  WrapperModuleSpec   =>
                val childPorts = resolved.portPlans.filter(_.module == childId)
                val fields     = childPorts.map { p =>
                  summon[FirrtlBundleFieldApi].createFirrtlBundleField(
                    p.name.encoded,
                    p.direction == PortDirection.Input,
                    translate(p.interface)
                  )
                }
                val instOp     = summon[InstanceApi].op(
                  moduleNames(childId),
                  c,
                  FirrtlNameKind.Interesting,
                  unknownLoc,
                  fields,
                  leafPaths(resolved.layerDecls.getOrElse(childId, LayerTree.empty))
                )
                instOp.operation.appendToBlock()
                childPorts.zipWithIndex
                  .foreach((p, i) => childValues((c, p.name.encoded)) = instOp.operation.getResult(i))
          }

          def baseOf(e: LocalEndpoint): Either[String, Value] = e match
            case LocalEndpoint.ThisPort(name)           =>
              portIndex.get(name.encoded).map(i => module.getIO(i)).toRight(s"missing port ${name.encoded}")
            case LocalEndpoint.ChildPort(inst, port, _) =>
              childValues.get((inst, port.encoded)).toRight(s"missing child port $inst.${port.encoded}")

          def subOf(e: LocalEndpoint): InterfacePath = e match
            case LocalEndpoint.ChildPort(_, _, sub) => sub
            case _                                  => InterfacePath.root

          /** Walk named fields; open bundles (probe-carrying) use `opensubfield`, plain data `subfield`. */
          def navigate(
            base: Value,
            path: InterfacePath,
            open: Boolean
          )(
            using Block
          ): Either[String, Value] =
            path.segments.foldLeft(Right(base): Either[String, Value]) {
              case (Right(v), InterfacePath.Segment.Field(n)) =>
                val idx = v.getType.getBundleFieldIndex(n)
                if idx < 0 then Left(s"no field '$n' while navigating a verification path")
                else
                  val sub =
                    if open then summon[OpenSubfieldApi].op(v, idx, unknownLoc)
                    else summon[SubfieldApi].op(v, idx, unknownLoc)
                  sub.operation.appendToBlock()
                  Right(sub.result)
              case (Right(_), InterfacePath.Segment.Index(i)) =>
                Left(s"Vec index [$i] in a verification path is not supported yet")
              case (l @ Left(_), _)                           => l
            }

          resolved.wirePlans.filter(_.module == rep).foreach { wp =>
            def fail(detail: String): Unit = errors += ElaborationError.InvalidCircuit(s"${d.name}: $detail")
            wp.origin match
              case PlanOrigin.Design(_)          =>
                (baseOf(wp.from), baseOf(wp.to)) match
                  case (Right(src), Right(dst)) =>
                    summon[ConnectApi].op(src, dst, unknownLoc).operation.appendToBlock()
                  case (l, r)                   => Seq(l, r).foreach(_.left.foreach(fail))
              case PlanOrigin.Verification(bind) =>
                val group  = resolved.dvGroups.find(_.binds.contains(bind)).get
                val bundle = group.interfaces.sources(group.binds.indexOf(bind))
                (baseOf(wp.from), baseOf(wp.to)) match
                  case (Right(srcBase), Right(dstBase)) =>
                    wp.to match
                      case LocalEndpoint.ThisPort(_)             =>
                        // Pass-through across this boundary: per-leaf ref.define (doc @sec-dv-routing).
                        ProtocolBundle.leaves(bundle).foreach { (leafPath, _) =>
                          val wired = for
                            src <- navigate(srcBase, leafPath, open = true)
                            dst <- navigate(dstBase, leafPath, open = true)
                          yield summon[RefDefineApi].op(dst, src, unknownLoc).operation.appendToBlock()
                          wired.left.foreach(fail)
                        }
                      case LocalEndpoint.ChildPort(inst, _, sub) =>
                        // The sink end: resolve each probe inside the sink's layerblock and connect the data.
                        sinkBlocks.get(inst) match
                          case None     => fail(s"sink instance '$inst' has no layer block")
                          case Some(lb) =>
                            given Block = lb
                            ProtocolBundle.leaves(bundle).foreach { (leafPath, _) =>
                              val wired = for
                                src <- navigate(srcBase, leafPath, open = true)
                                dst <- navigate(dstBase, InterfacePath(sub.segments ++ leafPath.segments), open = false)
                              yield {
                                val res = summon[RefResolveApi].op(src, unknownLoc)
                                res.operation.appendToBlock()
                                summon[ConnectApi].op(res.result, dst, unknownLoc).operation.appendToBlock()
                              }
                              wired.left.foreach(fail)
                            }
                  case (l, r)                           => Seq(l, r).foreach(_.left.foreach(fail))
          }

          module.appendToCircuit()
        }
      }
      if errors.nonEmpty then return Left(NormalizedErrors(errors.toVector))

      // ============ link the per-module circuits dumped by the backends ============
      // Demand-driven: parse exactly the `<moduleName>.mlirbc` files of modules that are referenced but not yet
      // defined, transitively — stale or unrelated files in the dump directory are never touched.
      def collectRefs(root: Operation): Set[String] =
        val out = mutable.Set.empty[String]
        root.walk(
          op =>
            if op.getName.str == "firrtl.instance" then
              out += op.getInherentAttributeByName("moduleName").flatSymbolRefAttrGetValue
            WalkResultEnum.Advance
          ,
          WalkEnum.PreOrder
        )
        out.toSet

      val defined = mutable.Set.empty[String]
      summon[Circuit].operation.walk(
        op =>
          if op.getName.str == "firrtl.module" || op.getName.str == "firrtl.extmodule" then
            defined += op.getInherentAttributeByName("sym_name").stringAttrGetValue
          WalkResultEnum.Advance
        ,
        WalkEnum.PreOrder
      )

      val unresolved = mutable.Set.empty[String]
      val needed     = mutable.Set.from(collectRefs(summon[Circuit].operation) -- defined)
      while needed.nonEmpty do
        val sym = needed.head
        needed -= sym
        if !defined(sym) && !unresolved(sym) then
          val file = mlirbcDir / s"$sym.mlirbc"
          if !os.exists(file) then unresolved += sym
          else
            val moved = mutable.ArrayBuffer.empty[Operation]
            try
              // Manual block iteration: operations obtained through direct calls live in our arena, unlike the
              // transient wrappers a walk callback receives.
              def isNullOp(op: Operation): Boolean           =
                op._segment.get(java.lang.foreign.ValueLayout.ADDRESS, 0).address == 0
              def opsIn(first: Operation): Vector[Operation] =
                val buf = mutable.ArrayBuffer.empty[Operation]
                var cur = first
                while !isNullOp(cur) do
                  buf += cur
                  cur = cur.getNextInBlock
                buf.toVector
              val parsed = summon[MlirModuleApi].moduleCreateParse(os.read.bytes(file))
              if parsed._segment.get(java.lang.foreign.ValueLayout.ADDRESS, 0).address == 0 then unresolved += sym
              else
                val topOps     = opsIn(parsed.getOperation.getFirstRegion.getFirstBlock.getFirstOperation)
                val circuitOps = topOps.filter(_.getName.str == "firrtl.circuit")
                val moduleOps  = circuitOps
                  .flatMap(c => opsIn(c.getFirstRegion.getFirstBlock.getFirstOperation))
                  .filter(_.getName.str == "firrtl.module")
                moduleOps.foreach { op =>
                  val s2 = op.getInherentAttributeByName("sym_name").stringAttrGetValue
                  if !defined(s2) then
                    op.removeFromParent()
                    summon[Circuit].block.appendOwnedOperation(op)
                    defined += s2
                    moved += op
                }
            catch
              case e: Exception =>
                unresolved += sym
                errors += ElaborationError.InvalidCircuit(s"while linking $file: $e")
            if !defined(sym) then unresolved += sym
            moved.foreach(op => needed ++= (collectRefs(op) -- defined -- unresolved))

      unresolved.toVector.sorted.foreach(sym => errors += ElaborationError.MissingModule(sym))
      if errors.nonEmpty then return Left(NormalizedErrors(errors.toVector))

      if !summon[MlirModule].getOperation.verify then
        return Left(Vector(ElaborationError.InvalidCircuit("MLIR verification of the linked circuit failed")))

      // ============ artifacts: FIRRTL text, then the firtool pipeline to Verilog ============
      val fir = new StringBuilder
      summon[MlirModule].exportFIRRTL(fir ++= _)

      given FirtoolOptions = summon[FirtoolApi].firtoolOptionsCreateDefault
      given PassManager    = summon[PassManagerApi].passManagerCreate
      val verilog          = new StringBuilder
      val firtoolOptions   = summon[FirtoolOptions]
      summon[PassManager].preprocessTransforms(firtoolOptions)
      summon[PassManager].chirrtlToLowFIRRTL(firtoolOptions)
      summon[PassManager].lowFIRRTLToHW(firtoolOptions, "")
      summon[PassManager].hwToSV(firtoolOptions)
      summon[PassManager].exportVerilog(firtoolOptions, verilog ++= _)
      summon[PassManager].runOnOpOrThrow(
        summon[MlirModule].getOperation,
        s"firtool lowering pipeline for circuit '$circuitName'"
      )

      Right(ElaboratedDesign(circuitName, fir.toString, verilog.toString, moduleNames.toMap))
    finally
      if context != null then context.destroy()
      arena.close()

  private def NormalizedErrors(errors: Vector[ElaborationError]): Vector[ElaborationError] =
    errors.sortBy(_.show)
