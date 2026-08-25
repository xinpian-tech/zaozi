// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke

/** Context-function façade over [[WrapperScope]] and [[GeneratorScope]], so design bodies read declaratively:
  *
  * {{{
  * val spec = Design {
  *   val core = generator("core", coreEntry) { ... }
  *   ...
  * }
  * }}}
  */

def wrapper(
  name:     String
)(body:     WrapperScope ?=> Unit
)(
  using ws: WrapperScope,
  loc:      SourceLocation
): ModuleId =
  ws.wrapper(name)(body)

def generator[FP](
  name:  String,
  entry: GeneratorEntry[FP]
)(body:  GeneratorScope[FP] ?=> Unit
)(
  using
  ws:    WrapperScope,
  loc:   SourceLocation
): ModuleId =
  ws.generator(name, entry)(body)

def inward(
  p:        Protocol
)(name:     String
)(
  using gs: GeneratorScope[?],
  loc:      SourceLocation
): InwardNodeBuilder[p.type] =
  gs.inward(p)(name)

def outward(
  p:        Protocol
)(name:     String
)(
  using gs: GeneratorScope[?],
  loc:      SourceLocation
): OutwardNodeBuilder[p.type] =
  gs.outward(p)(name)

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
)(name:  String,
  down:  p.Down,
  layer: LayerPath
)(
  using
  gs:    GeneratorScope[?],
  loc:   SourceLocation
): DVSourceRef[p.type] =
  gs.dvSource(p)(name, down, layer)

def dvSink(
  p:        DVProtocol
)(name:     String
)(
  using gs: GeneratorScope[?],
  loc:      SourceLocation
): DVSinkRef[p.type] =
  gs.dvSink(p)(name)

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
