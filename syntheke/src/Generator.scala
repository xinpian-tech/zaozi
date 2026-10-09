package me.jiuyang.syntheke

import upickle.default.Writer

abstract class GeneratorDefinition[FP] private[syntheke] (
  val name: String
)(
  using val fullParamWriter: upickle.default.Writer[FP]):
  def probes(fullParam: FP): ProbeDeclaration
  def observations(fullParam: FP): ProbeBindings
