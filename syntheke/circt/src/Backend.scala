// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.circt

import me.jiuyang.syntheke.*
import org.llvm.mlir.scalalib.capi.ir.{Block, Context, Operation}

import java.lang.foreign.Arena

/** Binds one syntheke [[GeneratorEntry]] to the hardware implementation that enacts it (doc @sec-generator-contract).
  *
  * A backend consumes the serializable full parameter and produces the generator's circuit: `instantiate` creates the
  * instance operation inside the wrapper module currently under emission and dumps the generator's own module (and its
  * transitive children) as per-module `.mlirbc` circuits, which the elaborator links into the design circuit
  * afterwards.
  */
trait GeneratorBackend:
  def entry: GeneratorEntry[?]

  /** The module name is the linking key: instances reference it and the dumped `.mlirbc` file is found by it, so it
    * must be a faithful encoding of the identity (generator name, canonical FullParam) — globally unique across
    * backends, not merely stable within one. Use [[GeneratorBackend.canonicalModuleName]].
    */
  def moduleName(fullParam: Any): String

  /** Create the instance operation in the current block; results are the ports, named by the `portNames` attribute. */
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
  /** Canonical JSON: object keys sorted recursively; arrays keep order. */
  private def canonical(v: ujson.Value): ujson.Value = v match
    case obj: ujson.Obj => ujson.Obj.from(obj.value.toVector.sortBy(_._1).map((k, w) => k -> canonical(w)))
    case arr: ujson.Arr => ujson.Arr.from(arr.value.map(canonical))
    case other => other

  /** The canonical linking key: the sanitized generator name plus a strong hash over (name, canonical FullParam JSON).
    * Distinct identities cannot collide in the flat symbol namespace the linker resolves by name.
    */
  def canonicalModuleName[FP](entry: GeneratorEntry[FP], fullParam: FP): String =
    val payload = ujson.write(
      canonical(
        upickle.default.writeJs(fullParam)(
          using entry.fullParamRW
        )
      )
    )
    val digest  = java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(
        s"${entry.name}\n$payload".getBytes(java.nio.charset.StandardCharsets.UTF_8)
      )
    val hash    = digest.take(8).map(b => f"$b%02x").mkString
    s"${entry.name.map(c => if c.isLetterOrDigit then c else '_')}_$hash"
