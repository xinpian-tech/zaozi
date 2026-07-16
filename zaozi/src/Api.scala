// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi

import scala.annotation.targetName
import scala.util.chaining.*

import me.jiuyang.zaozi.magic.macros.summonLayersImpl
import me.jiuyang.zaozi.ltltpe.*
import me.jiuyang.zaozi.reftpe.*
import me.jiuyang.zaozi.valuetpe.*

import org.llvm.circt.scalalib.capi.dialect.firrtl.FirrtlEventControl
import org.llvm.circt.scalalib.dialect.firrtl.operation.{ExtModule as CirctExtModule, Module as CirctModule, When}
import org.llvm.circt.scalalib.dialect.firrtl.operation.{RegResetPolarity, RegResetType}
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Operation, Type, Value}

import java.lang.foreign.Arena

/** Rebuild the [[Layer]] with each [[Layer]] contains the entire tree. */
trait LayerTree:
  layer =>
  def name:      String
  def children:  Seq[LayerTree]
  def parent:    Option[LayerTree] = None
  def hierarchy: Seq[LayerTree]    =
    parent match
      case Some(p) => p.hierarchy :+ this
      case None    => Seq(this)
  def _dfs:      Seq[LayerTree]    =
    this +: children.flatMap(_._dfs)
  def _rebuild:  LayerTree         =
    def rebuildLayer(_oldLayer: LayerTree, _parent: Option[LayerTree]): LayerTree =
      new LayerTree:
        override def name:     String            = _oldLayer.name
        override def children: Seq[LayerTree]    =
          _oldLayer.children.map(child => rebuildLayer(child, Some(this)))
        override def parent:   Option[LayerTree] = _parent
    rebuildLayer(this, None)

/** Serializable Layer definition. */
case class Layer(name: String, children: Seq[Layer] = Seq.empty):
  layer =>
  def toLayerTree: LayerTree =
    new LayerTree:
      def name:     String         = layer.name
      def children: Seq[LayerTree] = layer.children.map(_.toLayerTree)
    ._rebuild
extension (layers: Seq[Layer]) def toLayerTrees: Seq[LayerTree]   = layers.map(_.toLayerTree)
extension (layer:  LayerTree) def nameHierarchy = layer.hierarchy.map(_.name)
extension (layers: Seq[LayerTree])
  def nameHierarchies:                           Seq[Seq[String]] =
    layers.flatMap(_._dfs).filter(_.children.isEmpty).map(_.nameHierarchy)

/** Base type for a [[Generator]]'s parameters: the compile-time (Scala-level) configuration of a module, e.g. bit
  * widths and feature flags. Must be a `case class` for structural equality (used to dedupe elaborated module
  * instances) and needs an `upickle.default.ReadWriter` given instance for CLI parsing.
  */
abstract class Parameter extends Product

/** Base type for a [[Generator]]'s optional debug/verification layers (FIRRTL layers): a `Seq[Layer]` naming the layer
  * tree that field probes ([[me.jiuyang.zaozi.valuetpe.RProbe]]/[[me.jiuyang.zaozi.valuetpe.RWProbe]]) in its
  * [[DVInterface]] can be colored with. `layers = Seq.empty` if the module has no debug layers.
  */
abstract class LayerInterface[P <: Parameter](parameter: P) extends Seq[LayerTree]:
  def layers: Seq[Layer]

  final override def apply(idx: Int) = layers.toLayerTrees(idx)
  final override def iterator        = layers.toLayerTrees.iterator
  final override def length          = layers.toLayerTrees.length

/** Base type for a [[Generator]]'s hardware IO: the ports visible on the emitted module. Implemented as either
  * [[HWBundle]] (statically-shaped) or [[HWRecord]] (dynamically-shaped).
  */
trait HWInterface[P <: Parameter](parameter: P) extends Aggregate:
  this: Bundle | Record =>

/** A [[Generator]]'s IO declared as a [[Bundle]] -- fields are `val`s, as in [[me.jiuyang.hello.HelloWorldIO]] /
  * `me.jiuyang.varadder.VarAdderIO`.
  */
abstract class HWBundle[P <: Parameter](parameter: P) extends HWInterface(parameter) with Bundle

/** A [[Generator]]'s IO declared as a [[Record]] -- fields are named dynamically, for ports whose shape isn't known
  * until `parameter` is inspected.
  */
abstract class HWRecord[P <: Parameter](parameter: P) extends HWInterface(parameter) with Record

/** Base type for a [[Generator]]'s debug/verification interface: the probe fields exposed alongside the ordinary IO,
  * colored by the module's [[LayerInterface]]. Implemented as either [[DVBundle]] (statically shaped) or [[DVRecord]]
  * (dynamically shaped); see [[me.jiuyang.zaozi.valuetpe.ProbeBundle]]/ [[me.jiuyang.zaozi.valuetpe.ProbeRecord]] for
  * the field-declaration API.
  */
trait DVInterface[P <: Parameter, L <: LayerInterface[P]](parameter: P) extends Aggregate:
  this: ProbeBundle | ProbeRecord =>
  private var _layersOpt:              Option[L]         = None
  transparent inline def summonLayers: LayerInterface[?] = ${ summonLayersImpl }
  transparent inline def layers:       L                 = _layersOpt.getOrElse:
    summonLayers.asInstanceOf[L].tap(l => _layersOpt = Some(l))

/** A [[Generator]]'s debug interface declared as a [[ProbeBundle]] -- probe fields are `val`s, as in
  * `me.jiuyang.varadder.VarAdderProbe`. Modules with no probes still declare one, typically empty.
  */
abstract class DVBundle[P <: Parameter, L <: LayerInterface[P]](parameter: P)
    extends DVInterface[P, L](parameter)
    with ProbeBundle

/** A [[Generator]]'s debug interface declared as a [[ProbeRecord]] -- probe fields are named dynamically. */
abstract class DVRecord[P <: Parameter, L <: LayerInterface[P]](parameter: P)
    extends DVInterface[P, L](parameter)
    with ProbeRecord

/** Per-elaboration mutable state threaded implicitly through architecture construction; currently just the counter used
  * to name otherwise-unnamed signals uniquely.
  */
class InstanceContext:
  class AnonSignalCounter(private var _count: Int):
    def count = _count
    def inc() =
      val o = count
      _count += 1
      o

  val anonSignalCounter = new AnonSignalCounter(0)

/** The implicit clock a `Reg`/`RegInit` is built under (see `me.jiuyang.zaozi.default.ConstructorApi`), given via
  * `using ClockScope`. Construct one with [[ClockScope.posedge]]/[[ClockScope.negedge]] and bring it into scope with
  * `given ClockScope = ...` (see `me.jiuyang.subleq.Subleq`).
  */
final case class ClockScope private[zaozi] (
  clock: Ref[Clock],
  clockEdge: FirrtlEventControl = FirrtlEventControl.AtPosEdge):
  def apply[T](body: ClockScope ?=> T): T = body(
    using this
  )

object ClockScope:
  def posedge(clock: Ref[Clock]): ClockScope = ClockScope(clock, FirrtlEventControl.AtPosEdge)
  def negedge(clock: Ref[Clock]): ClockScope = ClockScope(clock, FirrtlEventControl.AtNegEdge)
end ClockScope

/** The implicit reset (and its type/polarity) a `RegInit` is built under (see
  * `me.jiuyang.zaozi.default.ConstructorApi`), given via `using ResetScope`. Construct one with one of
  * [[ResetScope.syncActiveHigh]], [[ResetScope.syncActiveLow]], [[ResetScope.asyncActiveHigh]],
  * [[ResetScope.asyncActiveLow]], and bring it into scope with `given ResetScope = ...` (see
  * `me.jiuyang.subleq.Subleq`).
  */
final case class ResetScope private[zaozi] (
  reset:     Ref[Reset],
  resetType: RegResetType,
  resetPolarity: RegResetPolarity):
  def apply[T](body: ResetScope ?=> T): T = body(
    using this
  )

object ResetScope:
  def syncActiveHigh(reset: Ref[Reset]): ResetScope =
    ResetScope(reset, RegResetType.SyncReset, RegResetPolarity.PosReset)

  def syncActiveLow(reset: Ref[Reset]): ResetScope =
    ResetScope(reset, RegResetType.SyncReset, RegResetPolarity.NegReset)

  def asyncActiveHigh(reset: Ref[Reset]): ResetScope =
    ResetScope(reset, RegResetType.AsyncReset, RegResetPolarity.PosReset)

  def asyncActiveLow(reset: Ref[Reset]): ResetScope =
    ResetScope(reset, RegResetType.AsyncReset, RegResetPolarity.NegReset)
end ResetScope

/** A hardware module generator, parameterized over [[Parameter]] (`PARAM`), [[LayerInterface]] (`L`), [[HWInterface]]
  * (`I`), and [[DVInterface]] (`P`). Concrete generators are `object`s annotated `@generator` (see
  * `me.jiuyang.hello.HelloWorld`, `me.jiuyang.subleq.Subleq`, `me.jiuyang.varadder.VarAdder`), which fills in
  * [[layers]], [[interface]], [[probe]], [[parseParameter]], and [[main]] by macro -- an author only writes
  * [[architecture]] (the module body) and, optionally, overrides [[moduleName]].
  */
trait Generator[PARAM <: Parameter, L <: LayerInterface[PARAM], I <: HWInterface[PARAM], P <: DVInterface[PARAM, L]]:
  /* For traits with self-type annotation that don't want type parameters
     e.g
      trait SomeGenerator:
        this: Generator[?, ?, ?] =>
        private val self = this.asInstanceOf[Generator[this.TPARAM, this.TINTF, this.TPROBE]]
   */
  type TPARAM = PARAM
  type TLAYER = L
  type TINTF  = I
  type TPROBE = P

  /** The emitted FIRRTL/Verilog module's name; defaults to the generator's class name plus a hash of `parameter`, so
    * distinct parameterizations never collide. Override for a stable, human-chosen name (see
    * `me.jiuyang.stdlib.BrentKungAdder`).
    */
  def moduleName(parameter: PARAM): String =
    s"${this.getClass.getSimpleName.stripSuffix("$")}_${parameter.hashCode.toHexString}"

  /** The module body: given the elaborated `Interface[I]` (IO) and `Interface[P]` (probes) implicitly in scope, wire up
    * the design. This is the one method every generator author implements by hand.
    */
  def architecture(parameter: PARAM): (
    Arena,
    Context,
    Block,
    Interface[I],
    Interface[P],
    L,
    InstanceContext
  ) ?=> Unit

  private[zaozi] val elaboratedModules = scala.collection.mutable.HashSet.empty[PARAM]

  // fields should be generated by macro automatically
  def layers(parameter:    PARAM): L
  def interface(parameter: PARAM): I
  def probe(parameter:     PARAM): P

  /** Parses `parameter` from command-line args; generated from `PARAM`'s fields by the `@generator` macro. */
  def parseParameter(args: Seq[String]): PARAM

  /** CLI entry point generated by the `@generator` macro: parses `args` into a `PARAM` and dumps the module. */
  def main(args: Array[String]): Unit

/** Base type for the (Verilog-side) parameters of a [[VerilogWrapper]], analogous to [[Parameter]] but for the
  * black-boxed external module a `VerilogWrapper` describes.
  */
abstract class VerilogParameter extends Product

/** Describes an externally-provided Verilog module (an `extmodule`) so it can be instantiated from a [[Generator]]'s
  * architecture like any other module, without Zaozi generating its body. See
  * `me.jiuyang.zaozi.default.VerilogWrapperApi` for the `extmodule`/`instance`/`instantiate` builders.
  */
trait VerilogWrapper[
  PARAM <: Parameter,
  L <: LayerInterface[PARAM],
  I <: HWInterface[PARAM],
  P <: DVInterface[PARAM, L],
  V <: VerilogParameter]:
  type TPARAM  = PARAM
  type TLAYER  = L
  type TINTF   = I
  type TPROBE  = P
  type TVPARAM = V

  def verilogModuleName(parameter: PARAM): String

  // ensure that the moduleName is unique for each verilog parameter
  def moduleName(parameter: PARAM): String =
    s"${verilogModuleName(parameter)}_${verilogParameter(parameter).hashCode.toHexString}"

  def verilogParameter(parameter: PARAM): V

  private[zaozi] val elaboratedModules = scala.collection.mutable.HashSet.empty[PARAM]

  // fields should be generated by macro automatically
  def layers(parameter:    PARAM): L
  def interface(parameter: PARAM): I
  def probe(parameter:     PARAM): P

  def parseParameter(args: Seq[String]): PARAM

  def main(args: Array[String]): Unit

// No type params in trait for easy exporting
trait GeneratorApi:
  extension [PARAM <: Parameter, L <: LayerInterface[PARAM], I <: HWInterface[PARAM], P <: DVInterface[PARAM, L]](
    generator: Generator[PARAM, L, I, P]
  )
    private[zaozi] def module(
      parameter: PARAM
    )(
      using Arena,
      Context
    ): CirctModule

    private[zaozi] def instance(
      parameter: PARAM
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Instance[I, P]

    /** Elaborates (once per distinct `parameter`, memoized) and instantiates `generator` as a sub-module of the
      * enclosing architecture, e.g. `Subtractor.instantiate(SubtractorParameter(width))`. Returns an [[Instance]] whose
      * `.io`/`.probe` are the sub-module's ports/probes, wired with `:=`/`<==` like any other `Referable`.
      */
    def instantiate(
      parameter: PARAM
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Instance[I, P]

    /** Elaborates `generator` at `parameter` and writes the resulting MLIR bytecode to disk, without emitting
      * FIRRTL/Verilog.
      */
    def dumpMlirbc(
      parameter: PARAM
    )(
      using Arena,
      Context
    ): Unit

    /** Implementation of [[Generator.main]], generated by the `@generator` macro: parses `args` into a `PARAM` and
      * dumps the elaborated module.
      */
    def mainImpl(
      args: Array[String]
    )(
      using upickle.default.ReadWriter[PARAM]
    ): Unit

trait VerilogWrapperApi:
  extension [
    PARAM <: Parameter,
    L <: LayerInterface[PARAM],
    I <: HWInterface[PARAM],
    P <: DVInterface[PARAM, L],
    V <: VerilogParameter
  ](wrapper: VerilogWrapper[PARAM, L, I, P, V]
  )
    private[zaozi] def instance(
      parameter: PARAM
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Instance[I, P]

    /** Instantiates the external module `wrapper` describes as a sub-module of the enclosing architecture, the
      * [[VerilogWrapper]] counterpart to `Generator#instantiate`.
      */
    def instantiate(
      parameter: PARAM
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Instance[I, P]

/** Constructors for hardware types, values, and control-flow -- the vocabulary an architecture body is written in.
  * Implemented by `me.jiuyang.zaozi.default.ConstructorApi` and typically brought into scope wholesale via
  * `import me.jiuyang.zaozi.default.{*, given}`.
  */
trait ConstructorApi:
  def Clock(): Clock

  def Reset(): Reset

  def UInt(width: Int): UInt

  def Bits(width: Int): Bits

  def SInt(width: Int): SInt

  def Bool(): Bool

  def Vec[T <: Data](size: Int, tpe: T): Vec[T]

  /** Structural `if`: connects to a sink conditionally on `cond`, evaluated combinationally (unlike Scala's `if`, both
    * `when` and [[otherwise]] participate in hardware, not just one taken branch). For a value-level (expression)
    * conditional, see `Bool#?` instead. Pair with [[otherwise]] for the else branch; an omitted `otherwise` leaves the
    * sink unassigned along that path.
    */
  def when[COND <: Referable[Bool]](
    cond: COND
  )(body: (Arena, Context, Block) ?=> Unit
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line
  ): When

  extension (when: When)
    /** The `else` branch of a [[when]]. */
    def otherwise(
      body: (Arena, Context, Block) ?=> Unit
    )(
      using Arena,
      Context
    ): Unit

  extension (layer: LayerTree) def apply(name: String): LayerTree

  extension (layers: Seq[LayerTree]) def apply(name: String): LayerTree

  /** Declares a debug/verification layer named `layerName` (nested under any enclosing `layer` in scope) and runs
    * `body` under it -- probe fields defined inside are colored with this layer and only observable when it's enabled
    * at Verilog emission time.
    */
  def layer(
    layerName: String
  )(body:      (
      Arena,
      Context,
      Block,
      LayerTree,
      Seq[LayerTree],
      sourcecode.File,
      sourcecode.Line
    ) ?=> Unit
  )(
    using Arena,
    Context,
    Block,
    Seq[LayerTree],
    sourcecode.File,
    sourcecode.Line
  ): Unit

  /** Declares a combinational signal of type `T`: undriven until assigned with `:=`/`:<=`/etc., and readable anywhere
    * afterward in the same architecture.
    */
  def Wire[T <: Data](
    refType: T
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): Wire[T]

  /** Declares a clocked register of type `T`, initialized to an undefined value (holds whatever value it's assigned on
    * the first clock edge it's written). Requires a [[ClockScope]] in scope; for a reset value, use [[RegInit]]
    * instead.
    */
  def Reg[T <: Data](
    refType: T
  )(
    using ClockScope
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): Reg[T]

  /** Declares a clocked register initialized to the literal `input` on reset. Requires both a [[ClockScope]] and a
    * [[ResetScope]] in scope; `input` must be a `Const` (a literal, e.g. `0.U(8)`), not an arbitrary wire -- see
    * `me.jiuyang.zaozi.reftpe.Const`.
    */
  def RegInit[T <: Data](
    input: Const[T]
  )(
    using ClockScope
  )(
    using ResetScope
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): Reg[T]

  /** Gives `ref` an explicit, named intermediate value in the emitted FIRRTL/Verilog (a `node`), useful for naming an
    * otherwise-anonymous expression for readability or waveform debugging.
    */
  def Node[T <: Data](
    ref: Referable[T]
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ):   Node[T]
  extension (bigInt: BigInt)
    /** A `width`-bit unsigned literal. */
    def U(
      width: Int
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line
    ): Const[UInt]

    /** An unsigned literal at its natural (minimal) width, i.e. `bitLength`. */
    def U(
      using Arena,
      Context,
      Block
    ): Const[UInt]

    /** A `Bits` literal at its natural (minimal) width. */
    def B(
      using Arena,
      Context,
      Block
    ): Const[Bits]

    /** A `width`-bit signed (two's-complement) literal. */
    def S(
      width: Int
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line
    ): Const[SInt]

    /** A signed literal at its natural width, including the sign bit. */
    def S(
      using Arena,
      Context,
      Block
    ): Const[SInt]
  extension (bool:   Boolean)
    /** A 1-bit `Bool` literal. */
    def B(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line
    ): Const[Bool]
end ConstructorApi

trait AsBits[D <: Data]:
  extension [R <: Referable[D]](ref: R)
    def asBits(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Propagated[R, Bits]
trait AsBool[D <: Data]:
  extension [R <: Referable[D]](ref: R)
    def asBool(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Propagated[R, Bool]
trait AsSInt[D <: Data]:
  extension [R <: Referable[D]](ref: R)
    def asSInt(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Propagated[R, SInt]
trait AsUInt[D <: Data]:
  extension [R <: Referable[D]](ref: R)
    def asUInt(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Propagated[R, UInt]

/** Bitcasts to a [[Bundle]] shape: reinterprets the same underlying bits as `tpe`'s fields, with no runtime cost.
  */
trait AsBundle[D <: Data]:
  extension [R <: Referable[D]](ref: R)
    def asBundle[T <: Bundle](
      tpe: T
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Propagated[R, T]

/** Bitcasts to a [[Record]] shape: reinterprets the same underlying bits as `tpe`'s named fields. */
trait AsRecord[D <: Data]:
  extension [R <: Referable[D]](ref: R)
    def asRecord[T <: Record](
      tpe: T
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Propagated[R, T]

/** Bitcasts to a [[Vec]] of `tpe` elements: the source width must divide evenly by `tpe`'s width, producing
  * `srcWidth / tpe.width` elements.
  */
trait AsVec[D <: Data]:
  extension [R <: Referable[D]](ref: R)
    def asVec[E <: Data](
      tpe: E
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Propagated[R, Vec[E]]

/** Zero-cost re-view of a [[me.jiuyang.zaozi.valuetpe.Bundle Bundle]] as a string-keyed
  * [[me.jiuyang.zaozi.valuetpe.Record Record]] over the same value (no bitcast; cf. [[AsRecord]]).
  */
trait AsRecordView[D <: Bundle]:
  extension [R <: Referable[D] & HasOperation](ref: R) def asRecord: Propagated[R, Record]

/** The [[ProbeBundle]]/[[ProbeRecord]] counterpart to [[AsRecordView]]. */
trait AsProbeRecordView[D <: ProbeBundle]:
  extension [R <: Referable[D] & HasOperation](ref: R) def asRecord: Propagated[R, ProbeRecord]

/** Connects probe reference fields ([[RProbe]]/[[RWProbe]]), via `<==`, three ways: define a probe from the data it
  * observes (`probeField <== dataValue`), alias one probe field to another (`probeField <== otherProbeField`), or, on
  * the reading side, resolve a probe back into an ordinary value (`dataValue <== probeField`). Ordinary `:=`/`:<=`/etc.
  * deliberately reject probe types (see the "probe types do not participate in bulk connects" error in
  * `me.jiuyang.zaozi.default.Connect`) -- `<==` is the only way to connect them, and it requires an enclosing [[layer]]
  * for the color check.
  */
trait ProbeConnect[D <: Data & CanProbe, P <: RWProbe[D] | RProbe[D], DATA <: Referable[D], PROBE <: Referable[P]]:
  extension (ref: PROBE)
    @targetName("send")
    def <==(
      that: DATA
    )(
      using Arena,
      Context,
      Block,
      LayerTree,
      sourcecode.File,
      sourcecode.Line
    ): Unit

    @targetName("define")
    def <==(
      that: PROBE
    )(
      using Arena,
      Context,
      Block,
      LayerTree,
      sourcecode.File,
      sourcecode.Line
    ): Unit

  extension (ref: DATA)
    @targetName("resolve")
    def <==(
      that: PROBE
    )(
      using Arena,
      Context,
      Block,
      LayerTree,
      sourcecode.File,
      sourcecode.Line
    ): Unit

/** Marks `ref` as intentionally undriven (FIRRTL's invalid value), e.g. `io.sum.dontCare()` before conditionally
  * assigning it -- the counterpart to Chisel's `:= DontCare`.
  */
trait DontCare[D <: Data, SINK <: Writable[D]]:
  extension (ref: SINK)
    def dontCare(
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line
    ): Unit

/** Thrown when a [[Connect]] operator's sink/source shapes don't line up (mismatched field names, widths, orientations,
  * or `Vec` lengths); the message lists every mismatch found, not just the first.
  */
final class ConnectException(message: String) extends Exception(message)

/** The connect operators, mirroring Chisel's "new" (`:=`, `:<>=`, `:<=`, `:>=`) connection operators. All but `:=`
  * recurse structurally through `Vec`/`Bundle`/`Record`, matching sink and source fields by name and validating
  * widths/orientations before emitting anything (a mismatch throws [[ConnectException]] listing every error found, not
  * just the first).
  */
trait Connect[A <: Connectable]:
  extension [SINK <: Writable[A]](sink:  SINK)
    /** Plain mono-directional connect, sink `<-` source. Only defined for scalar ([[Element]]) types -- no structural
      * recursion, so it's the one operator usable inside `when`/`otherwise` branches where the sink's shape must
      * already be known to be a leaf.
      */
    def :=[SRC <: Referable[A]](
      src: SRC
    )(
      using A <:< Element,
      Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line
    ): Unit

    /** Bidirectional bulk connect: aligned fields flow sink `<-` source, flipped fields flow sink `->` source,
      * recursively -- the usual way to wire a sub-module's IO or a nested bundle both ways at once.
      */
    def :<>=[SRC <: Writable[A]](
      src: SRC
    )(
      using Arena,
      Context,
      Block,
      TypeImpl,
      sourcecode.File,
      sourcecode.Line
    ): Unit

    /** Half of [[:<>=]]: connects only the aligned (non-flipped) leaves, sink `<-` source. */
    def :<=[SRC <: Referable[A]](
      src: SRC
    )(
      using Arena,
      Context,
      Block,
      TypeImpl,
      sourcecode.File,
      sourcecode.Line
    ): Unit
  extension [SINK <: Referable[A]](sink: SINK)
    /** Half of [[:<>=]]: connects only the flipped leaves, sink `->` source. */
    def :>=[SRC <: Writable[A]](
      src: SRC
    )(
      using Arena,
      Context,
      Block,
      TypeImpl,
      sourcecode.File,
      sourcecode.Line
    ): Unit

/** Zero-extending conversion capability (no `given` instance is currently registered for any `D`). */
trait Cvt[D <: Data, RET <: Data]:
  extension [R <: Referable[D]](ref: R)
    def zext(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[RET]
trait Neg[D <: Data, RET <: Data]:
  extension [R <: Referable[D]](ref: R)
    def unary_!(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[RET]
trait Not[D <: Data, RET <: Data]:
  extension [R <: Referable[D]](ref: R)
    def unary_~(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[RET]
trait AndR[D <: Data, RET <: Data]:
  extension [R <: Referable[D]](ref: R)
    def andR(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[RET]
trait OrR[D <: Data, RET <: Data]:
  extension [R <: Referable[D]](ref: R)
    def orR(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[RET]
trait XorR[D <: Data, RET <: Data]:
  extension [R <: Referable[D]](ref: R)
    def xorR(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[RET]
trait Add[D <: Data, RET <: Data]:
  extension [LHS <: Referable[D]](ref: LHS)
    def +[RHS <: Referable[D]](
      that: RHS
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[RET]
trait Sub[D <: Data, RET <: Data]:
  extension [LHS <: Referable[D]](ref: LHS)
    def -[RHS <: Referable[D]](
      that: RHS
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[RET]
trait Mul[D <: Data, RET <: Data]:
  extension [LHS <: Referable[D]](ref: LHS)
    def *[RHS <: Referable[D]](
      that: RHS
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[RET]
trait Div[D <: Data, RET <: Data]:
  extension [LHS <: Referable[D]](ref: LHS)
    def /[RHS <: Referable[D]](
      that: RHS
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[RET]
trait Rem[D <: Data, RET <: Data]:
  extension [LHS <: Referable[D]](ref: LHS)
    def %[RHS <: Referable[D]](
      that: RHS
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[RET]
trait Lt[D <: Data, RET <: Data]:
  extension [LHS <: Referable[D]](ref: LHS)
    def <[RHS <: Referable[D]](
      that: RHS
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[RET]
trait Leq[D <: Data, RET <: Data]:
  extension [LHS <: Referable[D]](ref: LHS)
    def <=[RHS <: Referable[D]](
      that: RHS
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[RET]
trait Gt[D <: Data, RET <: Data]:
  extension [LHS <: Referable[D]](ref: LHS)
    def >[RHS <: Referable[D]](
      that: RHS
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[RET]
trait Geq[D <: Data, RET <: Data]:
  extension [LHS <: Referable[D]](ref: LHS)
    def >=[RHS <: Referable[D]](
      that: RHS
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[RET]
trait Eq[D <: Data, RET <: Data]:
  extension [LHS <: Referable[D]](ref: LHS)
    def ===[RHS <: Referable[D]](
      that: RHS
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[RET]
trait Neq[D <: Data, RET <: Data]:
  extension [LHS <: Referable[D]](ref: LHS)
    def =/=[RHS <: Referable[D]](
      that: RHS
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[RET]
trait And[D <: Data, RET <: Data]:
  extension [LHS <: Referable[D]](ref: LHS)
    def &[RHS <: Referable[D]](
      that: RHS
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[RET]
trait Or[D <: Data, RET <: Data]:
  extension [LHS <: Referable[D]](ref: LHS)
    def |[RHS <: Referable[D]](
      that: RHS
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[RET]
trait Xor[D <: Data, RET <: Data]:
  extension [LHS <: Referable[D]](ref: LHS)
    def ^[RHS <: Referable[D]](
      that: RHS
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[RET]

/** Bit concatenation: `a ## b` places `a` at the high (MSB) end and `b` at the low (LSB) end, with combined width
  * `a.width + b.width`.
  */
trait Cat[D <: Data]:
  extension [LHS <: Referable[D]](ref: LHS)
    def ##[RHS <: Referable[D]](
      that: RHS
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[D]

/** Left shift, either by a compile-time-constant `Int` (static shift, grows the width by the shift amount -- no bits
  * are lost) or by a `Referable[UInt]` (dynamic/barrel shift, width grows to accommodate the maximum possible shift).
  * Unlike [[Shr]]'s `Int` case, the static case here does not pad back to the original width.
  */
trait Shl[D <: Data, OUT <: Data]:
  extension [R <: Referable[D]](ref: R)
    def <<(
      that: Int | Referable[UInt]
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[OUT]

/** Right shift, either by a compile-time-constant `Int` (static shift; the result is padded back to the original width,
  * unlike [[Shl]]'s static case which grows) or by a `Referable[UInt]` (dynamic/barrel shift, width unchanged). Sign-
  * or zero-extends new high bits per `D` (arithmetic for `SInt`, logical otherwise).
  */
trait Shr[D <: Data, OUT <: Data]:
  extension [R <: Referable[D]](ref: R)
    def >>(
      that: Int | Referable[UInt]
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[D]

/** Keeps the top (MSB-most) `that` bits, discarding the rest. */
trait Head[D <: Data, OUT <: Data]:
  extension [R <: Referable[D]](ref: R)
    def head(
      that: Int
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[OUT]

/** Drops the top (MSB-most) `that` bits, keeping the rest -- note this does NOT resize to `that` bits, it removes
  * `that` bits from the current width (a common source of off-by-width bugs; see the regression guard in
  * `me.jiuyang.subleqtest.SubleqStructuralSpec`).
  */
trait Tail[D <: Data, OUT <: Data]:
  extension [R <: Referable[D]](ref: R)
    def tail(
      that: Int
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[OUT]

/** Extends to `that` bits (a no-op if already `>= that` bits), sign-extending for `SInt` and zero-extending otherwise.
  */
trait Pad[D <: Data, OUT <: Data]:
  extension [R <: Referable[D]](ref: R)
    def pad(
      that: Int
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[OUT]

/** Slices out bits `[hi:lo]`, inclusive on both ends and 0-indexed from the LSB (so `bits(width - 1, 0)` is the whole
  * value). Requires `hi >= lo`.
  */
trait ExtractRange[D <: Data, E <: Data]:
  extension [R <: Referable[D]](ref: R)
    def bits(
      hi: Int,
      lo: Int
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[E]

/** Slices out a single bit at `idx` (0-indexed from the LSB) as an `E` (e.g. `Bool` for `Bits#bit`). */
trait ExtractElement[D <: Data, E <: Data]:
  extension [R <: Referable[D]](ref: R)
    def bit(
      idx: Int
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[E]

/** Value-level (expression) conditional: `cond ? (thenValue, elseValue)`, evaluated combinationally (both branches are
  * elaborated regardless of `cond`, unlike a Scala `if`). This is Zaozi's `Mux`; for a structural (statement-level)
  * conditional, see [[ConstructorApi.when]] instead.
  */
trait Mux[Cond <: Data]:
  extension [CondR <: Referable[Cond]](ref: CondR)
    def ?[Ret <: Data](
      con: Referable[Ret],
      alt: Referable[Ret]
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Node[Ret]

/** Indexes into a [[Vec]]-like `D`, either statically (`Int`, a fixed `SubindexOp`) or dynamically (`Referable[UInt]`,
  * a `SubaccessOp` selected at runtime). `apply` is the usual spelling, e.g. `vec(3)` or `vec(io.sel)`.
  */
trait RefElement[D <: Data, E <: Data]:
  extension [R <: Referable[D]](ref: R)
    def ref(
      idx: Int | Referable[UInt]
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Ref[E]
trait GetLength[E <: Data, V <: Vec[E]]:
  extension [R <: Referable[V]](ref: R)
    def length(
      using Arena,
      Context
    ): Int

/** The full extension-method surface of [[Bits]]: casts, bitwise ops, structural ops (concat/slice/shift), but no
  * arithmetic (`+`, `-`, ... -- cast to [[UInt]]/[[SInt]] first). Implemented by `me.jiuyang.zaozi.default.BitsApi`.
  */
trait BitsApi
    extends AsSInt[Bits]
    with AsUInt[Bits]
    with AsBool[Bits]
    with AsBundle[Bits]
    with AsRecord[Bits]
    with AsVec[Bits]
    with Not[Bits, Bits]
    with AndR[Bits, Bool]
    with OrR[Bits, Bool]
    with XorR[Bits, Bool]
    with Eq[Bits, Bool]
    with Neq[Bits, Bool]
    with And[Bits, Bits]
    with Or[Bits, Bits]
    with Xor[Bits, Bits]
    with Cat[Bits]
    with Shl[Bits, Bits]
    with Shr[Bits, Bits]
    with Head[Bits, Bits]
    with Tail[Bits, Bits]
    with Pad[Bits, Bits]
    with ExtractElement[Bits, Bool]
    with ExtractRange[Bits, Bits]

/** The extension-method surface of [[Bool]]: logical ops, equality, and the [[Mux]] `?` operator. Implemented by
  * `me.jiuyang.zaozi.default.BoolApi`.
  */
trait BoolApi
    extends AsBits[Bool]
    with Neg[Bool, Bool]
    with Eq[Bool, Bool]
    with Neq[Bool, Bool]
    with And[Bool, Bool]
    with Or[Bool, Bool]
    with Xor[Bool, Bool]
    with Mux[Bool]

/** The extension-method surface of [[UInt]]: arithmetic, comparison, and static/dynamic shifts. Implemented by
  * `me.jiuyang.zaozi.default.UIntApi`.
  */
trait UIntApi
    extends AsBits[UInt]
    with Add[UInt, UInt]
    with Sub[UInt, UInt]
    with Mul[UInt, UInt]
    with Div[UInt, UInt]
    with Rem[UInt, UInt]
    with Lt[UInt, Bool]
    with Leq[UInt, Bool]
    with Gt[UInt, Bool]
    with Geq[UInt, Bool]
    with Eq[UInt, Bool]
    with Neq[UInt, Bool]
    with Shl[UInt, UInt]
    with Shr[UInt, UInt]

/** The extension-method surface of [[SInt]]: signed arithmetic, comparison, and static/dynamic shifts. Implemented by
  * `me.jiuyang.zaozi.default.SIntApi`.
  */
trait SIntApi
    extends AsBits[SInt]
    with Add[SInt, SInt]
    with Sub[SInt, SInt]
    with Mul[SInt, SInt]
    with Div[SInt, SInt]
    with Rem[SInt, SInt]
    with Lt[SInt, Bool]
    with Leq[SInt, Bool]
    with Gt[SInt, Bool]
    with Geq[SInt, Bool]
    with Neq[SInt, Bool]
    with Shl[SInt, SInt]
    with Shr[SInt, SInt]

/** The extension-method surface of [[Bundle]]/[[me.jiuyang.zaozi.valuetpe.ProbeBundle]]: just `asBits`. Implemented by
  * `me.jiuyang.zaozi.default.BundleApi`.
  */
trait BundleApi[T <: Bundle | ProbeBundle] extends AsBits[T]

/** The extension-method surface of [[Record]]/[[me.jiuyang.zaozi.valuetpe.ProbeRecord]]: just `asBits`. Implemented by
  * `me.jiuyang.zaozi.default.RecordApi`.
  */
trait RecordApi[T <: Record | ProbeRecord] extends AsBits[T]

/** The extension-method surface of [[Vec]]: `asBits`, indexing ([[RefElement]]), and [[GetLength]]. Implemented by
  * `me.jiuyang.zaozi.default.VecApi`.
  */
trait VecApi[E <: Data, V <: Vec[E]] extends AsBits[V] with RefElement[V, E] with GetLength[E, V]

/** The extension-method surface of [[Clock]] -- currently empty; clocks are only used positionally (as the argument to
  * [[ClockScope.posedge]]/[[ClockScope.negedge]]).
  */
trait ClockApi

/** The extension-method surface of [[Reset]]: just `asBool`. Implemented by `me.jiuyang.zaozi.default.ResetApi`. */
trait ResetApi extends AsBool[Reset]

/** Maps a tuple of `Referable[t]` argument types to the corresponding tuple of `Referable[t] & HasOperation` results
  * the tupled [[ContractApi.Contract]] overload hands to its body -- internal plumbing for that overload's
  * variadic-tuple ergonomics, not something called directly.
  */
type ContractTuple[A <: Tuple] <: Tuple = A match
  case EmptyTuple           => EmptyTuple
  case Referable[t] *: tail => (Referable[t] & HasOperation) *: ContractTuple[tail]

/** Evidence needed to convert a heterogeneous tuple of `Referable`s to/from a `Seq`, so the tupled
  * [[ContractApi.Contract]] overload can accept any arity. Not called directly.
  */
trait ContractTupleArgs[A <: Tuple]:
  def values(args:    A):                                        Seq[Referable[? <: Data] & HasOperation]
  def results(values: Seq[Referable[? <: Data] & HasOperation]): ContractTuple[A]

object ContractTupleArgs:
  given empty: ContractTupleArgs[EmptyTuple] with
    def values(args: EmptyTuple):                                  Seq[Referable[? <: Data] & HasOperation] = Seq.empty
    def results(values: Seq[Referable[? <: Data] & HasOperation]): EmptyTuple                               =
      EmptyTuple

  given cons[T <: Data, H <: Referable[T] & HasOperation, Tail <: Tuple](
    using tailArgs: ContractTupleArgs[Tail]
  ): ContractTupleArgs[H *: Tail] with
    def values(args: H *: Tail): Seq[Referable[? <: Data] & HasOperation] =
      args.head +: tailArgs.values(args.tail)

    def results(values: Seq[Referable[? <: Data] & HasOperation]): ContractTuple[H *: Tail] =
      (values.head *: tailArgs.results(values.tail)).asInstanceOf[ContractTuple[H *: Tail]]

/** Formal contracts (CIRCT's `verif.contract`): a boundary that states a behavioral property of one or more values --
  * via [[Require]] (an assumption the caller must uphold) and [[Ensure]] (a property the contract asserts about its
  * result) -- separately from the implementation that produces them. Used in `me.jiuyang.stdlib.PrefixAdderCommon` to
  * state "this prefix-tree network computes `A + B + CI`" independent of the tree shape, and in
  * `me.jiuyang.zaozitest.ContractSpec` for minimal examples.
  *
  * '''What actually gets checked''': `Ensure` lowers to an `assert property` in a separate `..._CheckContract_N` module
  * tagged `// FORMAL TEST` in the exported Verilog (see `me.jiuyang.zaozi.default.SVAApi`'s emission pipeline), and
  * callers of the contracted value see it as an `assume`d free variable satisfying the stated property. Nothing in this
  * repository's own build currently runs a solver against that assertion -- it is a machine-checkable statement of
  * intent for an external formal tool, not a check `mill test` enforces today. Verify a contracted design's actual
  * numeric behavior with simulation (see `SubleqSimSpec`/`VarAdderSimSpec`) or by structurally matching the emitted
  * RTL, not by relying on `Ensure` alone.
  */
trait ContractApi:
  /** The no-argument form: states properties about values already in scope, e.g. `Require((p >= 1.U).I)` /
    * `Ensure((p + p >= 2.U).I)`.
    */
  def Contract(
    body: => Unit
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    TypeImpl
  ): Unit

  /** Wraps a single value at a contract boundary: `body` states what's true of the value passed to it (via
    * [[Ensure]]/[[Require]]), and the returned reference is a fresh, contract-bounded view of `arg`.
    */
  def Contract[T <: Data](
    arg:  Referable[T] & HasOperation
  )(body: (Referable[T] & HasOperation) => (Arena, Context, Block) ?=> Unit
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    TypeImpl
  ): Referable[T] & HasOperation

  /** The tupled form of the single-value [[Contract]] overload, for stating a joint property of several values at once
    * (e.g. `Contract((carry, sum)) { case (c, s) => Ensure((c + s === a + b).I) }` in
    * `me.jiuyang.stdlib.PrefixAdderCommon`).
    */
  def Contract[A <: Tuple](
    args: A
  )(body: ContractTuple[A] => (Arena, Context, Block) ?=> Unit
  )(
    using ContractTupleArgs[A]
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    TypeImpl
  ): ContractTuple[A]

  /** States a property the contract's caller must guarantee (an assumption, lowered to `assume property`). */
  def Require(
    property: Immediate | Sequence | Property,
    label:    Option[String] = None
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    TypeImpl
  ): Unit

  /** States a property the contract guarantees about its result (an obligation, lowered to `assert property` in a
    * separate formal-test module -- see the "what actually gets checked" note on [[ContractApi]]).
    */
  def Ensure(
    property: Immediate | Sequence | Property,
    label:    Option[String] = None
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    TypeImpl
  ): Unit

/** SystemVerilog Assertion combinators for building [[Immediate]]/[[Sequence]]/[[Property]] properties (used with
  * [[ContractApi.Require]]/[[ContractApi.Ensure]] and [[Assert]]/[[Assume]]/[[Cover]]), plus the assertion directives
  * themselves. Each combinator's doc names the SVA syntax it corresponds to.
  */
trait SVAApi:
  def posedge(clock: Referable[Clock] & HasOperation): ClockEvent
  def negedge(clock: Referable[Clock] & HasOperation): ClockEvent

  /** SVA: always p
    */
  def always(
    property: Immediate | Sequence | Property
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): Property

  /** SVA: s_eventually p
    */
  def eventually(
    property: Immediate | Sequence | Property
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): Property

  extension [T <: Referable[Bool] & HasOperation](ref: T)
    def S(
      using ClockEvent
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Sequence

    def I(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Immediate

    /** SVA: bool_expr throughout s
      */
    infix def throughout(
      that: Sequence
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Sequence

  extension (ref: Immediate)
    /** SVA: not p
      *
      * This is property negation. CIRCT's `ltl.not` accepts any property-like operand and always produces
      * `!ltl.property`.
      */
    def unary_!(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property

    /** SVA: p1 and p2
      */
    def &(
      that: Immediate
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Immediate
    def &(
      that: Sequence
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Sequence
    def &(
      that: Property
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property

    /** SVA: s1 intersect s2
      */
    infix def intersect(
      that: Immediate
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Immediate
    infix def intersect(
      that: Sequence
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Sequence
    infix def intersect(
      that: Property
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property

    /** SVA: s1 or s2
      */
    def |(
      that: Immediate
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Immediate
    def |(
      that: Sequence
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Sequence
    def |(
      that: Property
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property

    /** SVA: s|->p
      */
    def |->(
      that: Immediate | Sequence | Property
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property

    /** SVA: s #-# p
      */
    def #-#(
      that: Immediate | Sequence | Property
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property

    /** SVA: p1 implies p2
      */
    infix def implies(
      that: Immediate | Sequence | Property
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property

    /** SVA: p1 iff p2
      */
    infix def iff(
      that: Immediate | Sequence | Property
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property

    /** SVA: p1 until p2
      */
    infix def until(
      that: Immediate | Sequence | Property
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property

    /** SVA: p1 until_with p2
      */
    infix def untilWith(
      that: Immediate | Sequence | Property
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property

  extension (ref: Sequence)
    /** SVA: not p
      *
      * This is property negation. CIRCT's `ltl.not` accepts any property-like operand and always produces
      * `!ltl.property`.
      */
    def unary_!(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property

    /** SVA: a ## b, shorthand for a ##0 b.
      */
    def ##(
      that: Sequence
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Sequence

    /** SVA: a ### b, shorthand for a ##1 b.
      */
    def ###(
      that: Sequence
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Sequence

    /** SVA: a ##n b
      */
    def ##(
      n:    Int
    )(that: Sequence
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Sequence

    /** SVA: a ##[min:max] b
      */
    def ##(
      min:  Int,
      max:  Option[Int]
    )(that: Sequence
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Sequence

    /** SVA: s [*n]
      */
    def *(
      n: Int
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Sequence

    /** Ranged Repeat
      * @param min
      *   for minimal
      * @param max
      *   is [[None]] for unbounded, otherwise for maximal
      *
      * SVA: s [*n:$] if `max` is [[None]], s [*n:m] if `max` is [[Some]]
      */
    def *(
      min: Int,
      max: Option[Int]
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Sequence

    /** SVA: s [->n:m]
      */
    def *->(
      min: Int,
      max: Int
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Sequence

    /** SVA: s[=min:max]
      */
    def *=(
      min: Int,
      max: Int
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Sequence

    /** SVA: s1 ##[+] s2
      */
    def ##+(
      that: Sequence
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Sequence

    /** SVA: s1 ##[*] s2
      */
    def ##*(
      that: Sequence
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Sequence

    /** SVA: s1 within s2
      */
    infix def within(
      that: Sequence
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Sequence

    /** SVA: s|=>p
      */
    def |=>(
      that: Immediate | Sequence | Property
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property

    /** SVA: s #=# p
      */
    def #=#(
      that: Immediate | Sequence | Property
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property

    /** SVA: p1 and p2
      */
    def &(
      that: Immediate | Sequence
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Sequence
    def &(
      that: Property
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property

    /** SVA: s1 intersect s2
      */
    infix def intersect(
      that: Immediate | Sequence
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Sequence
    infix def intersect(
      that: Property
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property

    /** SVA: s1 or s2
      */
    def |(
      that: Immediate | Sequence
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Sequence
    def |(
      that: Property
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property

    /** SVA: s|->p
      */
    def |->(
      that: Immediate | Sequence | Property
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property

    /** SVA: s #-# p
      */
    def #-#(
      that: Immediate | Sequence | Property
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property

    /** SVA: p1 implies p2
      */
    infix def implies(
      that: Immediate | Sequence | Property
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property

    /** SVA: p1 iff p2
      */
    infix def iff(
      that: Immediate | Sequence | Property
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property

    /** SVA: p1 until p2
      */
    infix def until(
      that: Immediate | Sequence | Property
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property

    /** SVA: p1 until_with p2
      */
    infix def untilWith(
      that: Immediate | Sequence | Property
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property

  extension (ref: Property)
    /** SVA: not p
      *
      * This is property negation. CIRCT's `ltl.not` accepts any property-like operand and always produces
      * `!ltl.property`.
      */
    def unary_!(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property

    /** SVA: p1 and p2
      */
    def &(
      that: Immediate | Sequence | Property
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property

    /** SVA: p1 intersect p2
      */
    infix def intersect(
      that: Immediate | Sequence | Property
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property

    /** SVA: p1 or p2
      */
    def |(
      that: Immediate | Sequence | Property
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property

    /** SVA: p1 implies p2
      */
    infix def implies(
      that: Immediate | Sequence | Property
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property

    /** SVA: p1 iff p2
      */
    infix def iff(
      that: Immediate | Sequence | Property
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property

    /** SVA: p1 until p2
      */
    infix def until(
      that: Immediate | Sequence | Property
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property

    /** SVA: p1 until_with p2
      */
    infix def untilWith(
      that: Immediate | Sequence | Property
    )(
      using Arena,
      Context,
      Block,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine,
      InstanceContext
    ): Property

  def Assert(
    property: Immediate | Sequence | Property
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): Unit
  def Assume(
    property: Immediate | Sequence | Property
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): Unit
  def Cover(
    property: Immediate | Sequence | Property
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): Unit
  def Assert(
    property: Immediate | Sequence | Property,
    label:    String
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): Unit
  def Assume(
    property: Immediate | Sequence | Property,
    label:    String
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): Unit
  def Cover(
    property: Immediate | Sequence | Property,
    label:    String
  )(
    using Arena,
    Context,
    Block,
    sourcecode.File,
    sourcecode.Line,
    sourcecode.Name.Machine,
    InstanceContext
  ): Unit

/** Internal seam between the public API traits above (`Generator`, `ConstructorApi`, `BitsApi`, ...) and their concrete
  * implementations in `me.jiuyang.zaozi.default`: the raw MLIR `Operation`/`Value`/`Type` accessors every `given`
  * implementation is built on. Entirely `private[zaozi]` -- not part of the surface a generator author calls.
  */
trait TypeImpl:
  extension (ref: Interface[?])
    private[zaozi] def operationImpl: Operation
    private[zaozi] def referImpl(
      using Arena
    ):                                Value
  extension (ref: Wire[?])
    private[zaozi] def operationImpl: Operation
    private[zaozi] def referImpl(
      using Arena
    ):                                Value
  extension (ref: Reg[?])
    private[zaozi] def operationImpl: Operation
    private[zaozi] def referImpl(
      using Arena
    ):                                Value
  extension (ref: Node[?])
    private[zaozi] def operationImpl: Operation
    private[zaozi] def referImpl(
      using Arena
    ):                                Value
  extension (ref: Ref[?])
    private[zaozi] def operationImpl: Operation
    private[zaozi] def referImpl(
      using Arena
    ):                                Value
  extension (ref: Const[?])
    private[zaozi] def operationImpl: Operation
    private[zaozi] def referImpl(
      using Arena
    ):                                Value
  extension (ref: Instance[?, ?])
    private[zaozi] def operationImpl:        Operation
    private[zaozi] def ioImpl[T <: Data]:    Wire[T]
    private[zaozi] def probeImpl[T <: Data]: Wire[T]
  extension (ref: Sequence)
    private[zaozi] def operationImpl: Operation
    private[zaozi] def referImpl(
      using Arena
    ):                                Value
    private[zaozi] def toMlirTypeImpl(
      using Arena,
      Context
    ):                                Type
  extension (ref: Property)
    private[zaozi] def operationImpl: Operation
    private[zaozi] def referImpl(
      using Arena
    ):                                Value
    private[zaozi] def toMlirTypeImpl(
      using Arena,
      Context
    ):                                Type
  extension (ref: Immediate)
    private[zaozi] def operationImpl: Operation
    private[zaozi] def referImpl(
      using Arena
    ):                                Value
    private[zaozi] def toMlirTypeImpl(
      using Arena,
      Context
    ):                                Type

  extension (ref:  Reset)
    private[zaozi] def toMlirTypeImpl(
      using Arena,
      Context
    ):                                      Type
  extension (ref:  Clock)
    private[zaozi] def toMlirTypeImpl(
      using Arena,
      Context
    ):                                      Type
  extension (ref:  UInt)
    private[zaozi] def toMlirTypeImpl(
      using Arena,
      Context
    ):                                      Type
  extension (ref:  SInt)
    private[zaozi] def toMlirTypeImpl(
      using Arena,
      Context
    ):                                      Type
  extension (ref:  Bits)
    private[zaozi] def toMlirTypeImpl(
      using Arena,
      Context
    ):                                      Type
  extension (ref:  Analog)
    private[zaozi] def toMlirTypeImpl(
      using Arena,
      Context
    ):                                      Type
  extension (ref:  Bool)
    private[zaozi] def toMlirTypeImpl(
      using Arena,
      Context
    ):                                      Type
  extension (ref:  ProbeBundle)
    private[zaozi] def toMlirTypeImpl(
      using Arena,
      Context
    ): Type
    private[zaozi] def ReadProbeImpl[T <: Data & CanProbe](
      tpe:   T,
      layer: LayerTree
    )(
      using sourcecode.Name.Machine
    ): BundleField[RProbe[T]]
    private[zaozi] def ReadWriteProbeImpl[T <: Data & CanProbe](
      tpe:   T,
      layer: LayerTree
    )(
      using sourcecode.Name.Machine
    ): BundleField[RWProbe[T]]
  extension (ref:  Bundle)
    private[zaozi] def toMlirTypeImpl(
      using Arena,
      Context
    ): Type
    private[zaozi] def FlippedImpl[T <: Data](
      tpe: T
    )(
      using sourcecode.Name.Machine
    ): BundleField[T]
    private[zaozi] def AlignedImpl[T <: Data](
      tpe: T
    )(
      using sourcecode.Name.Machine
    ): BundleField[T]
  extension (ref:  Aggregate) def elements: Seq[BundleField[?]]
  extension (ref:  ProbeRecord)
    private[zaozi] def toMlirTypeImpl(
      using Arena,
      Context
    ): Type
    private[zaozi] def ReadProbeImpl[T <: Data & CanProbe](
      name:  String,
      tpe:   T,
      layer: LayerTree
    ): BundleField[RProbe[T]]
    private[zaozi] def ReadWriteProbeImpl[T <: Data & CanProbe](
      name:  String,
      tpe:   T,
      layer: LayerTree
    ): BundleField[RWProbe[T]]
  extension (ref:  Record)
    private[zaozi] def toMlirTypeImpl(
      using Arena,
      Context
    ): Type
    private[zaozi] def FlippedImpl[T <: Data](
      name: String,
      tpe:  T
    ): BundleField[T]
    private[zaozi] def AlignedImpl[T <: Data](
      name: String,
      tpe:  T
    ): BundleField[T]
  extension (ref:  RProbe[?])
    private[zaozi] def toMlirTypeImpl(
      using Arena,
      Context,
      TypeImpl
    ):                                      Type
  extension (ref:  RWProbe[?])
    private[zaozi] def toMlirTypeImpl(
      using Arena,
      Context,
      TypeImpl
    ):                                      Type
  extension (data: Data)
    private[zaozi] def widthImpl(
      using Arena,
      Context
    ):                                      Int
  extension (ref:  Vec[?])
    private[zaozi] def countImpl(
      using Arena,
      Context
    ): Int
    private[zaozi] def toMlirTypeImpl(
      using Arena,
      Context
    ): Type
  extension (ref:  ProbeBundle)
    private[zaozi] def getRefViaFieldValNameImpl[E <: Data](
      refer:        Value,
      fieldValName: String
    )(
      using Arena,
      Block,
      Context,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    )(
      using TypeImpl
    ): Ref[E]
    private[zaozi] def getOptionRefViaFieldValNameImpl[E <: Data](
      refer:        Value,
      fieldValName: String
    )(
      using Arena,
      Block,
      Context,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    )(
      using TypeImpl
    ): Option[Ref[E]]
  extension (ref:  Bundle)
    private[zaozi] def getRefViaFieldValNameImpl[E <: Data](
      refer:        Value,
      fieldValName: String
    )(
      using Arena,
      Block,
      Context,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    )(
      using TypeImpl
    ): Ref[E]
    private[zaozi] def getOptionRefViaFieldValNameImpl[E <: Data](
      refer:        Value,
      fieldValName: String
    )(
      using Arena,
      Block,
      Context,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    )(
      using TypeImpl
    ): Option[Ref[E]]

  extension (ref: ProbeRecord)
    private[zaozi] def getUntypedRefViaFieldValNameImpl(
      refer:        Value,
      fieldValName: String
    )(
      using Arena,
      Block,
      Context,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    )(
      using TypeImpl
    ): Ref[Data]

  extension (ref: Record)
    private[zaozi] def getUntypedRefViaFieldValNameImpl(
      refer:        Value,
      fieldValName: String
    )(
      using Arena,
      Block,
      Context,
      sourcecode.File,
      sourcecode.Line,
      sourcecode.Name.Machine
    )(
      using TypeImpl
    ): Ref[Data]
