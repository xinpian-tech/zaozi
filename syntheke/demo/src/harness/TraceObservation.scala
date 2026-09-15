package me.jiuyang.syntheke.demo.harness

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.zaozi.*
import me.jiuyang.syntheke.demo.{*, given}

private[demo] object TraceObservation:
  def select(catalog: ProbeCatalog): Vector[TraceSource] =
    catalog.query[InstructionRetirement].map { trace =>
      val module = trace.id.module
      TraceSource(
        module.show,
        trace.parameters,
        trace.observe
      )
    }
