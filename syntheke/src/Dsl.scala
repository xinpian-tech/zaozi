// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke

/** Context-function façade over [[WrapperScope]] and [[GeneratorScope]], so design bodies read declaratively.
  *
  * Declarations are named by the val they are bound to (sourceinfo, like zaozi's instance naming):
  *
  * {{{
  * val spec = Design {
  *   val core = generator(coreEntry) {   // instance name "core"
  *     val out = outward(Wid).dFn(...)   // node name "out"
  *     ...
  *     out
  *   }
  * }
  * }}}
  *
  * When a val name cannot carry the intended name — computed names in a loop, destructured returns — provide the given
  * explicitly, exactly like overriding zaozi's instance name:
  *
  * {{{
  * given sourcecode.Name = sourcecode.Name(s"in$i")
  * inward(Axi)
  * }}}
  */

def wrapper[A: Dangles](
  body: WrapperScope ?=> A
)(
  using
  ws:   WrapperScope,
  name: sourcecode.Name,
  loc:  SourceLocation
): A =
  ws.wrapper(name.value)(body)

def generator[FP, A: Dangles](
  entry: GeneratorEntry[FP]
)(body:  GeneratorScope[FP] ?=> A
)(
  using
  ws:    WrapperScope,
  name:  sourcecode.Name,
  loc:   SourceLocation
): A =
  ws.generator(name.value, entry)(body)

def inward(
  p:    Protocol
)(
  using
  gs:   GeneratorScope[?],
  name: sourcecode.Name,
  loc:  SourceLocation
): InwardNodeBuilder[p.type] =
  gs.inward(p)(name.value)

def outward(
  p:    Protocol
)(
  using
  gs:   GeneratorScope[?],
  name: sourcecode.Name,
  loc:  SourceLocation
): OutwardNodeBuilder[p.type] =
  gs.outward(p)(name.value)

def depend(
  from:     InwardNodeBuilder[?],
  to:       OutwardNodeBuilder[?]
)(
  using gs: GeneratorScope[?],
  loc:      SourceLocation
): (DownReader[from.protocol.Down], UpReader[to.protocol.Up]) =
  gs.depend(from, to)

def dvSource(
  p:     DVProtocol
)(down:  p.Down,
  layer: LayerPath
)(
  using
  gs:    GeneratorScope[?],
  name:  sourcecode.Name,
  loc:   SourceLocation
): DVSourceRef[p.type] =
  gs.dvSource(p)(name.value, down, layer)

def dvSink(
  p:    DVProtocol
)(
  using
  gs:   GeneratorScope[?],
  name: sourcecode.Name,
  loc:  SourceLocation
): DVSinkRef[p.type] =
  gs.dvSink(p)(name.value)

def parameters[PP, FP](
  compute: EdgeView => Either[CapabilityViolation, PP]
)(combine: PP => FP
)(
  using
  gs:      GeneratorScope[FP]
): Unit =
  gs.parameters(compute)(combine)

def parametersConst[FP](
  fp:       FP
)(
  using gs: GeneratorScope[FP]
): Unit =
  gs.parametersConst(fp)
