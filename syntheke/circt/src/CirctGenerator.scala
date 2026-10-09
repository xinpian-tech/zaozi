package me.jiuyang.syntheke.circt

import me.jiuyang.syntheke.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Module as MlirModule, Operation}

import java.lang.foreign.Arena

/** An instance a generator placed, and every module it elaborated for it, each a circuit of its own, by module name. */
final case class Instantiated(instance: Operation, definitions: Vector[(String, MlirModule)])

/** A generator whose hardware is a FIRRTL module. Each hardware language implements one. */
abstract class CirctGenerator[FP: upickle.default.Writer](name: String) extends GeneratorDefinition[FP](name):
  /** The module the generator emits for `fullParam`: by default its name and a digest of the parameter, so one full
    * parameter is one module.
    */
  def moduleName(fullParam: FP): String =
    def canonical(v: ujson.Value): ujson.Value = v match
      case obj: ujson.Obj => ujson.Obj.from(obj.value.toVector.sortBy(_._1).map((k, w) => k -> canonical(w)))
      case arr: ujson.Arr => ujson.Arr.from(arr.value.map(canonical))
      case other          => other
    val payload = ujson.write(canonical(upickle.default.writeJs(fullParam)))
    val digest  = java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(s"$name\n$payload".getBytes(java.nio.charset.StandardCharsets.UTF_8))
    s"${name.map(c => if c.isLetterOrDigit then c else '_')}_${digest.take(8).map(b => f"$b%02x").mkString}"

  /** The layers the module declares. */
  def layers(fullParam: FP): LayerTree

  def instantiate(fullParam: FP, instanceName: String, loc: SourceLoc)(using Arena, Context, Block): Instantiated
