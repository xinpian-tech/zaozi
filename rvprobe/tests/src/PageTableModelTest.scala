// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jianhao Ye <Clo91eaf@qq.com>
package me.jiuyang.rvprobe.tests

import me.jiuyang.rvprobe.cases.privilege.PageTableModel

import utest.*

object PageTableModelTest extends TestSuite:
  val tests = Tests {
    test("gigapage with full perms passes verification") {
      val m = new PageTableModel
      m.registerGigapage(0xcfL)
      m.verifyCodeFetchable("s_code") // should not throw
    }

    test("gigapage R-only (no X) fails verification") {
      val m = new PageTableModel
      m.registerGigapage(0xc3L) // V|R|A|D, missing X
      val e = intercept[IllegalStateException] {
        m.verifyCodeFetchable("s_code")
      }
      assert(e.getMessage.contains("X (Execute)"))
    }

    test("gigapage A=0 fails verification") {
      val m = new PageTableModel
      m.registerGigapage(0x8fL) // V|R|W|X|D, missing A
      val e = intercept[IllegalStateException] {
        m.verifyCodeFetchable("s_code")
      }
      assert(e.getMessage.contains("A (Accessed)"))
    }

    test("gigapage RW-only (no X) fails verification") {
      val m = new PageTableModel
      m.registerGigapage(0xc7L) // V|R|W|A|D, missing X
      val e = intercept[IllegalStateException] {
        m.verifyCodeFetchable("s_code")
      }
      assert(e.getMessage.contains("X (Execute)"))
    }

    test("two-level with full code perms passes") {
      val m = new PageTableModel
      m.registerTwoLevel(0xcfL, 0xc3L) // code=full, data=R-only
      m.verifyCodeFetchable("s_code")  // should not throw
    }

    test("X-only gigapage (V|X|A|D) passes") {
      val m = new PageTableModel
      m.registerGigapage(0xc9L)       // V|X|A|D
      m.verifyCodeFetchable("s_code") // should not throw
    }

    test("no mapping registered — no-op verification") {
      val m = new PageTableModel
      m.verifyCodeFetchable("s_code") // should not throw
    }

    test("error message includes label context") {
      val m = new PageTableModel
      m.registerGigapage(0xc3L)
      val e = intercept[IllegalStateException] {
        m.verifyCodeFetchable("my_label")
      }
      assert(e.getMessage.contains("my_label"))
    }
  }
