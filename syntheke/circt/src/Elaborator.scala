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

/** Thrown by [[Elaborator.elaborate]] at the first error found. The message carries the stable identifiers, the
  * relevant source locations, and for binding-check failures the first path where the structures diverge (doc @dec-binding-check).
  */
final class ElaborationException(message: String) extends RuntimeException(message)

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
  * circuit, verifies it, and runs the firtool pipeline to Verilog — no textual FIRRTL is ever constructed by hand. Fail
  * fast: the first error throws an [[ElaborationException]] on the spot.
  */
object Elaborator:

  private def fail(message: String): Nothing = throw ElaborationException(message)

  def elaborate(
    resolved:  ResolvedDesign,
    backends:  Seq[GeneratorBackend],
    mlirbcDir: os.Path = os.Path(sys.env.getOrElse("ZAOZI_OUTDIR", os.pwd.toString), os.pwd)
  ): ElaboratedDesign =
    val backendOf = backends.map(b => b.id -> b).toMap
    val spec      = resolved.spec
    val dd        = Dedup.dedup(resolved)

    // Module names: generator modules are named by their backend (stable per canonical FullParam, matching the
    // structural key); wrapper modules by the dedup naming rules.
    val moduleNames: Map[ModuleId, String] = spec.moduleOrder.map { id =>
      id -> (spec.modules(id) match
        case g: GeneratorModuleSpec =>
          backendOf
            .get(g.entry.id)
            .fold(fail(s"missing backend for generator ${g.entry.id.show} at ${id.show}"))(b =>
              b.moduleName(resolved.generatorModule(id).get.fullParam)
            )
        case _: WrapperModuleSpec   => dd.nameOf(id))
    }.toMap

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
            // Port order must match across every instance sharing this key; the key sorts ports by encoded name,
            // so definition and instance emission both use that order.
            val ports      = resolved.portPlans.filter(_.module == rep).sortBy(_.name.encoded)
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
                  }.headOption
                    .orElse(Some(s"$path: ${typeText(exp)} vs ${typeText(act)}"))
              else if exp.isVector && act.isVector then
                if exp.getVectorElementNum != act.getVectorElementNum then
                  Some(
                    s"$path: Vec[${exp.getVectorElementNum}] expected, generator has Vec[${act.getVectorElementNum}]"
                  )
                else firstDiff(exp.getVectorElementType, act.getVectorElementType, s"$path[]")
              else Some(s"$path: expected ${typeText(exp)}, generator has ${typeText(act)}")

            /** Emit one child instance; returns its port values and, for a probe sink, its layerblock. */
            def emitChild(c: String): (Vector[((String, String), Value)], Option[(String, Block)]) =
              val childId = rep / c
              spec.modules(childId) match
                case gm: GeneratorModuleSpec =>
                  val rgm        = resolved.generatorModule(childId).get
                  val sinkLayers = rgm.view.verification.sinks
                    .flatMap(sv => resolved.dvGroups.find(_.sink == sv.sink).toVector.flatMap(_.layers))
                    .distinct
                  // Probe sinks are enacted under a layerblock: FIRRTL has no input probe ports, so the wrapper
                  // resolves every probe and feeds plain data into the sink instance inside the layer (bind pattern).
                  val sinkBlock: Option[Block] =
                    if rgm.view.verification.sinks.isEmpty then None
                    else if rgm.view.nodes.nonEmpty then
                      fail(s"unsupported: sink generator ${childId.show} mixes probe sinks with design nodes")
                    else if sinkLayers.sizeIs != 1 then
                      fail(
                        s"unsupported: sink generator ${childId.show} collects probes from ${sinkLayers.size} distinct layers"
                      )
                    else
                      // Layerblocks nest structurally: `layerblock @a { layerblock @a::@b { … } }`.
                      Some(sinkLayers.head.segments.indices.foldLeft(summon[Block]) { (outer, i) =>
                        val lb = summon[LayerBlockApi].op(sinkLayers.head.segments.take(i + 1), unknownLoc)
                        locally {
                          given Block = outer
                          lb.operation.appendToBlock()
                        }
                        lb.block
                      })
                  val instOp = locally {
                    given Block = sinkBlock.getOrElse(summon[Block])
                    backendOf(gm.entry.id).instantiate(rgm.fullParam, c, gm.loc)
                  }
                  val names = instOp.getInherentAttributeByName("portNames")
                  val byName = Seq
                    .tabulate(names.arrayAttrGetNumElements)(i => names.arrayAttrGetElement(i).stringAttrGetValue -> i)
                    .toMap

                  // The single binding checkpoint (@dec-binding-check). Everything the settled design promises about
                  // this generator's ports is verified here, and only here: presence, root direction, and exact
                  // interface structure (with the first divergence path on mismatch); ports the generator declares
                  // beyond the settled design are rejected too.
                  val dirs = instOp.getInherentAttributeByName("portDirections")
                  def checkPort(name: String, expectOutput: Boolean, expectedInterface: ProtocolInterface): Unit =
                    byName.get(name) match
                      case None    =>
                        fail(
                          s"port mismatch at ${childId.show}#$name: declared endpoint has no matching generator port, " +
                            s"at ${gm.loc.show}"
                        )
                      case Some(i) =>
                        val actualOutput = dirs.denseBoolArrayGetElement(i)
                        if actualOutput != expectOutput then
                          fail(
                            s"port mismatch at ${childId.show}#$name: port direction is ${
                                if actualOutput then "output" else "input"
                              }, expected ${if expectOutput then "output" else "input"}, at ${gm.loc.show}"
                          )
                        firstDiff(translate(expectedInterface), instOp.getResult(i).getType, name).foreach { diff =>
                          fail(
                            s"port mismatch at ${childId.show}#$name: port type differs from the settled interface " +
                              s"at $diff, at ${gm.loc.show}"
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
                    fail(
                      s"port mismatch at ${childId.show}#$extra: generator port has no corresponding declared " +
                        s"endpoint, at ${gm.loc.show}"
                    )
                  }
                  (byName.toVector.map((n, i) => ((c, n), instOp.getResult(i))), sinkBlock.map(c -> _))
                case _:  WrapperModuleSpec   =>
                  val childPorts = resolved.portPlans.filter(_.module == childId).sortBy(_.name.encoded)
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
                  (
                    childPorts.zipWithIndex.map((p, i) => ((c, p.name.encoded), instOp.operation.getResult(i))),
                    None
                  )

            val childInfos  = w.children.map(emitChild)
            val childValues = childInfos.flatMap(_._1).toMap
            val sinkBlocks  = childInfos.flatMap(_._2).toMap

            def baseOf(e: LocalEndpoint): Value = e match
              case LocalEndpoint.ThisPort(name)           =>
                portIndex
                  .get(name.encoded)
                  .fold(fail(s"${d.name}: missing port ${name.encoded}"))(i => module.getIO(i))
              case LocalEndpoint.ChildPort(inst, port, _) =>
                childValues.getOrElse(
                  (inst, port.encoded),
                  fail(s"${d.name}: missing child port $inst.${port.encoded}")
                )

            /** Walk named fields; open bundles (probe-carrying) use `opensubfield`, plain data `subfield`. */
            def navigate(
              base: Value,
              path: InterfacePath,
              open: Boolean
            )(
              using Block
            ): Value =
              path.segments.foldLeft(base) { (v, seg) =>
                seg match
                  case InterfacePath.Segment.Field(n) =>
                    val idx = v.getType.getBundleFieldIndex(n)
                    if idx < 0 then fail(s"${d.name}: no field '$n' while navigating a verification path")
                    val sub =
                      if open then summon[OpenSubfieldApi].op(v, idx, unknownLoc)
                      else summon[SubfieldApi].op(v, idx, unknownLoc)
                    sub.operation.appendToBlock()
                    sub.result
                  case InterfacePath.Segment.Index(i) =>
                    fail(s"${d.name}: unsupported: Vec index [$i] in a verification path")
              }

            resolved.wirePlans.filter(_.module == rep).foreach { wp =>
              wp.origin match
                case PlanOrigin.Design(_)          =>
                  summon[ConnectApi].op(baseOf(wp.from), baseOf(wp.to), unknownLoc).operation.appendToBlock()
                case PlanOrigin.Verification(bind) =>
                  val group  = resolved.dvGroups.find(_.binds.contains(bind)).get
                  val bundle = group.interfaces.sources(group.binds.indexOf(bind))
                  wp.to match
                    case LocalEndpoint.ThisPort(_)             =>
                      // Pass-through across this boundary: per-leaf ref.define (doc @sec-dv-routing).
                      ProtocolBundle.leaves(bundle).foreach { (leafPath, _) =>
                        val src = navigate(baseOf(wp.from), leafPath, open = true)
                        val dst = navigate(baseOf(wp.to), leafPath, open = true)
                        summon[RefDefineApi].op(dst, src, unknownLoc).operation.appendToBlock()
                      }
                    case LocalEndpoint.ChildPort(inst, _, sub) =>
                      // The sink end: resolve each probe inside the sink's layerblock and connect the data.
                      val lb = sinkBlocks.getOrElse(inst, fail(s"${d.name}: sink instance '$inst' has no layer block"))
                      locally {
                        given Block = lb
                        ProtocolBundle.leaves(bundle).foreach { (leafPath, _) =>
                          val src = navigate(baseOf(wp.from), leafPath, open = true)
                          val dst =
                            navigate(baseOf(wp.to), InterfacePath(sub.segments ++ leafPath.segments), open = false)
                          val res = summon[RefResolveApi].op(src, unknownLoc)
                          res.operation.appendToBlock()
                          summon[ConnectApi].op(res.result, dst, unknownLoc).operation.appendToBlock()
                        }
                      }
            }

            module.appendToCircuit()
          }
        }

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

        def collectDefined(root: Operation): Set[String] =
          val out = mutable.Set.empty[String]
          root.walk(
            op =>
              if op.getName.str == "firrtl.module" || op.getName.str == "firrtl.extmodule" then
                out += op.getInherentAttributeByName("sym_name").stringAttrGetValue
              WalkResultEnum.Advance
            ,
            WalkEnum.PreOrder
          )
          out.toSet

        // Manual block iteration: operations obtained through direct calls live in our arena, unlike the transient
        // wrappers a walk callback receives.
        def isNullOp(op: Operation): Boolean           =
          op._segment.get(java.lang.foreign.ValueLayout.ADDRESS, 0).address == 0
        def opsIn(first: Operation): Vector[Operation] =
          Iterator.iterate(first)(_.getNextInBlock).takeWhile(op => !isNullOp(op)).toVector

        @annotation.tailrec
        def link(needed: List[String], defined: Set[String]): Unit = needed match
          case Nil                         => ()
          case sym :: rest if defined(sym) => link(rest, defined)
          case sym :: rest                 =>
            val file      = mlirbcDir / s"$sym.mlirbc"
            if !os.exists(file) then fail(s"instantiated module '$sym' has no definition ($file not found)")
            val parsed    = summon[MlirModuleApi].moduleCreateParse(os.read.bytes(file))
            if parsed._segment.get(java.lang.foreign.ValueLayout.ADDRESS, 0).address == 0 then
              fail(s"cannot parse $file")
            val moduleOps = opsIn(parsed.getOperation.getFirstRegion.getFirstBlock.getFirstOperation)
              .filter(_.getName.str == "firrtl.circuit")
              .flatMap(c => opsIn(c.getFirstRegion.getFirstBlock.getFirstOperation))
              .filter(_.getName.str == "firrtl.module")
            val moved     = moduleOps.filter { op =>
              val s2 = op.getInherentAttributeByName("sym_name").stringAttrGetValue
              if defined(s2) then false
              else
                op.removeFromParent()
                summon[Circuit].block.appendOwnedOperation(op)
                true
            }
            val defined2  = defined ++ moved.map(_.getInherentAttributeByName("sym_name").stringAttrGetValue)
            if !defined2(sym) then fail(s"instantiated module '$sym' has no definition in $file")
            link(rest ++ moved.flatMap(op => collectRefs(op) -- defined2), defined2)

        val defined0 = collectDefined(summon[Circuit].operation)
        link((collectRefs(summon[Circuit].operation) -- defined0).toList, defined0)

        if !summon[MlirModule].getOperation.verify then fail("MLIR verification of the linked circuit failed")

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

        ElaboratedDesign(circuitName, fir.toString, verilog.toString, moduleNames)
      finally summon[Context].destroy()
    finally arena.close()
