// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.zaozi.default

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.{HasOperation, Propagated, Referable}
import me.jiuyang.zaozi.valuetpe.{Bundle, ProbeBundle, ProbeRecord, Record}

/** Implements `AsRecordView`: builds a `Record` view sharing the same `_elements`/underlying operation as the source
  * `Bundle`, so `asRecord` is a zero-cost reinterpretation, not a bitcast.
  */
given [B <: Bundle]: AsRecordView[B] with
  extension [R <: Referable[B] & HasOperation](ref: R)
    def asRecord: Propagated[R, Record] =
      val view = new Record {}
      view._elements ++= ref.getType._elements
      view.instantiating = false
      propagate[R, Record](ref, view, ref.operation)

/** The `ProbeBundle`/`ProbeRecord` counterpart to the `AsRecordView` given above. */
given [B <: ProbeBundle]: AsProbeRecordView[B] with
  extension [R <: Referable[B] & HasOperation](ref: R)
    def asRecord: Propagated[R, ProbeRecord] =
      val view = new ProbeRecord {}
      view._elements ++= ref.getType._elements
      view.instantiating = false
      propagate[R, ProbeRecord](ref, view, ref.operation)
