// SPDX-License-Identifier: Apache-2.0
package me.jiuyang.zaozitest

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.reftpe.Interface
import me.jiuyang.zaozi.valuetpe.*
import utest.*

object ElaborationFailureSpec extends TestSuite:
  val tests = Tests:
    test("retry a child whose first definition failed"):
      val marker   = new IllegalStateException("first child definition")
      var attempts = 0

      @generator
      object Child extends Generator[WidthSpecParameter, WidthSpecLayers, WidthSpecIO, WidthSpecProbe]:
        def architecture(parameter: WidthSpecParameter) =
          attempts += 1
          if attempts == 1 then throw marker
          val io = summon[Interface[WidthSpecIO]]
          io.o := io.i

      @generator
      object Parent extends Generator[WidthSpecParameter, WidthSpecLayers, WidthSpecIO, WidthSpecProbe]:
        def architecture(parameter: WidthSpecParameter) =
          val io    = summon[Interface[WidthSpecIO]]
          val child =
            try Child.instantiate(parameter)
            catch case error: IllegalStateException if error eq marker => Child.instantiate(parameter)
          child.io.i := io.i
          io.o := child.io.o

      val p         = WidthSpecParameter(8)
      val directory = os.temp.dir(deleteOnExit = false)
      try
        val file   = directory / s"${Child.moduleName(p)}.mlirbc"
        os.write(file, "previous run")
        val result = Elaborate(Parent, p, directory)
        assert(attempts == 2, result.modules(Child.moduleName(p)) == file)
        os.proc("circt-opt", file).call()
      finally os.remove.all(directory)
