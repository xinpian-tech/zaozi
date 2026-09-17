package me.jiuyang.syntheke.circt

import me.jiuyang.syntheke.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Operation}

import java.lang.foreign.Arena

private[syntheke] trait GeneratorBackendProvider:
  def createBackend(): GeneratorBackend

trait GeneratorBackend:
  def moduleName(fullParam: Any): String

  def layers(fullParam: Any): Seq[Vector[String]]

  def instantiate(
    fullParam:    Any,
    instanceName: String,
    loc:          (sourcecode.File, sourcecode.Line)
  )(
    using Arena,
    Context,
    Block
  ): Operation

object GeneratorBackend:
  def define[FP: upickle.default.Writer](name: String)(
    probeFn: FP => ProbeDeclaration
  )(backend: GeneratorDefinition[FP] => GeneratorBackend): GeneratorDefinition[FP] =
    new GeneratorDefinition[FP](name) with GeneratorBackendProvider:
      def probes(fullParam: FP): ProbeDeclaration = probeFn(fullParam)
      def createBackend(): GeneratorBackend = backend(this)

  def defineTestbench[FP: upickle.default.Writer](name: String)(
    probeFn: FP => ProbeDeclaration,
    observationFn: FP => ProbeBindings
  )(backend: TestbenchDefinition[FP] => GeneratorBackend): TestbenchDefinition[FP] =
    new TestbenchDefinition[FP](name) with GeneratorBackendProvider:
      def probes(fullParam: FP): ProbeDeclaration = probeFn(fullParam)
      def observations(fullParam: FP): ProbeBindings = observationFn(fullParam)
      def createBackend(): GeneratorBackend = backend(this)

  private def canonical(v: ujson.Value): ujson.Value = v match
    case obj: ujson.Obj => ujson.Obj.from(obj.value.toVector.sortBy(_._1).map((k, w) => k -> canonical(w)))
    case arr: ujson.Arr => ujson.Arr.from(arr.value.map(canonical))
    case other => other

  def canonicalModuleName[FP](
    definition: GeneratorDefinition[FP],
    fullParam: FP,
    observationABI: Option[ujson.Value]
  ): String =
    val parameter = upickle.default.writeJs(fullParam)(using definition.fullParamWriter)
    val payload = ujson.write(
      canonical(
        observationABI.fold(parameter)(abi => ujson.Obj("parameter" -> parameter, "observations" -> abi))
      )
    )
    val digest  = java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(
        s"${definition.name}\n$payload".getBytes(java.nio.charset.StandardCharsets.UTF_8)
      )
    val hash    = digest.take(8).map(b => f"$b%02x").mkString
    s"${definition.name.map(c => if c.isLetterOrDigit then c else '_')}_$hash"
