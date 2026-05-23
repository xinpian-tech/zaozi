// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package org.llvm.circt.scalalib.dialect.firrtl.operation

import org.llvm.mlir.scalalib.capi.support.HasOperation
import org.llvm.mlir.scalalib.capi.ir.Operation

class ClockDividerIntrinsic(val _operation: Operation)
class ClockGateIntrinsic(val _operation: Operation)
class ClockInverterIntrinsic(val _operation: Operation)
class DPICallIntrinsic(val _operation: Operation)
class FPGAProbeIntrinsic(val _operation: Operation)
class GenericIntrinsic(val _operation: Operation)
class HasBeenResetIntrinsic(val _operation: Operation)
class IsXIntrinsic(val _operation: Operation)
class PlusArgsTestIntrinsic(val _operation: Operation)
class PlusArgsValueIntrinsic(val _operation: Operation)
class UnclockedAssumeIntrinsic(val _operation: Operation)

class VerifAssertIntrinsic(val _operation: Operation)
trait VerifAssertIntrinsicApi extends HasOperation[VerifAssertIntrinsic]
end VerifAssertIntrinsicApi

class VerifAssumeIntrinsic(val _operation: Operation)
trait VerifAssumeIntrinsicApi extends HasOperation[VerifAssumeIntrinsic]
end VerifAssumeIntrinsicApi

class VerifCoverIntrinsic(val _operation: Operation)
trait VerifCoverIntrinsicApi extends HasOperation[VerifCoverIntrinsic]
end VerifCoverIntrinsicApi

class VerifEnsureIntrinsic(val _operation: Operation)
trait VerifEnsureIntrinsicApi extends HasOperation[VerifEnsureIntrinsic]
end VerifEnsureIntrinsicApi

class VerifRequireIntrinsic(val _operation: Operation)
trait VerifRequireIntrinsicApi extends HasOperation[VerifRequireIntrinsic]
end VerifRequireIntrinsicApi
