// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.dialect.firrtl.operation

import org.llvm.mlir.scalalib.capi.support.HasOperation
import org.llvm.mlir.scalalib.capi.ir.{Context, Location, Operation, Type, Value}

import java.lang.foreign.Arena

class ClockDividerIntrinsic(val _operation: Operation)
class ClockGateIntrinsic(val _operation: Operation)
class ClockInverterIntrinsic(val _operation: Operation)
class DPICallIntrinsic(val _operation: Operation)
trait DPICallIntrinsicApi extends HasOperation[DPICallIntrinsic]:
  /** `firrtl.int.dpi.call` — call the external DPI function `functionName` on the rising edge
    * of `clock` when `enable` is high, passing `inputs` and yielding one result of type
    * `result`. firtool lowers it to a SystemVerilog `import "DPI-C"` and a clocked call, so a
    * Zaozi design can hand cycles to an external C/Rust/Python frontend.
    */
  def op(
    functionName: String,
    result:       Type,
    clock:        Value,
    enable:       Value,
    inputs:       Seq[Value],
    location:     Location
  )(
    using Arena,
    Context
  ): DPICallIntrinsic
  extension (ref: DPICallIntrinsic)
    def result(
      using Arena
    ): Value
end DPICallIntrinsicApi
class FPGAProbeIntrinsic(val _operation: Operation)
class GenericIntrinsic(val _operation: Operation)
class HasBeenResetIntrinsic(val _operation: Operation)
class IsXIntrinsic(val _operation: Operation)
class PlusArgsTestIntrinsic(val _operation: Operation)
class PlusArgsValueIntrinsic(val _operation: Operation)
class UnclockedAssumeIntrinsic(val _operation: Operation)
