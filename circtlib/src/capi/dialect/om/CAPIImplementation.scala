// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Yuhang Zeng <unlsycn@unlsycn.com>
package org.llvm.circt.scalalib.capi.dialect.om

import org.llvm.circt.raw.structs as CirctStructs
import scala.scalanative.unsafe.*

given OMEvaluatorApi with
  extension (ref: OMEvaluator)

    inline def sizeOf: Int = sizeof[CirctStructs.OMEvaluator].toInt
end given

given OMEvaluatorValueApi with
  extension (ref: OMEvaluatorValue)

    inline def sizeOf: Int = sizeof[CirctStructs.OMEvaluatorValue].toInt
end given
