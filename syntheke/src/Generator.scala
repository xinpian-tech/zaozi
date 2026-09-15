package me.jiuyang.syntheke

import upickle.default.Writer

abstract class GeneratorDefinition[FP] private[syntheke] (
  val name: String
)(
  using val fullParamWriter: upickle.default.Writer[FP]):
  def probes(fullParam: FP): ProbeDeclaration

  final override def equals(other: Any): Boolean = other match
    case definition: GeneratorDefinition[?] => this eq definition
    case _ => false
  final override def hashCode():         Int     = System.identityHashCode(this)

abstract class TestbenchDefinition[FP] private[syntheke] (
  name: String
)(using Writer[FP]) extends GeneratorDefinition[FP](name):
  def observations(fullParam: FP): ProbeBindings

final class GeneratorCall[FP] private[syntheke] ():
  def apply[A: Dangles](
    body:       GeneratorScope[FP] ?=> (A, Vector[Constraint])
  )(
    using
    definition: GeneratorDefinition[FP],
    ws:         WrapperScope,
    name:       sourcecode.Name,
    file:       sourcecode.File,
    line:       sourcecode.Line
  ): A = ws.generator(name.value, definition)(body)

final class TestbenchCall[FP] private[syntheke] ():
  def apply[A: Dangles](
    body:       TestbenchScope[FP] ?=> (A, Vector[Constraint])
  )(
    using
    definition: TestbenchDefinition[FP],
    ws:         WrapperScope,
    name:       sourcecode.Name,
    file:       sourcecode.File,
    line:       sourcecode.Line
  ): A = ws.testbench(name.value, definition)(body)
