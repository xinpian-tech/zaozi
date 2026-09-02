// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>
package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.demo.zaoziimpl.AxiShape

/** The syntheke wrap of the chip's zaozi modules (`zaoziimpl/`) — how a plain zaozi IP gets onto the negotiation graph.
  * One file per IP, the same three declarations in each: a registry entry typed by the IP's zaozi Parameter
  * (negotiation computes it as the FullParam, doc @sec-two-layer-params), the endpoint class declaring nodes and
  * negotiation functions, and a def binding both to the entry. [[axiBackends]] binds every entry to its zaozi generator
  * — the only place the two sides meet; the elaborator checks the zaozi ports against every settled interface at
  * instantiation (@dec-binding-check).
  *
  * Only what the SoC ships is here, and not every IP of it speaks AXI: the PLL negotiates clock domains, the debug
  * transport a TAP and a DMI bus. The testbench is wrapped the same way but is not an IP of this design, so it is
  * [[Harness]]'s. The SoC that instantiates and wires all of them lives in [[Soc]].
  */

/** The settled shape at one of the module's own nodes, read from its view — what every AXI IP here asks its edge. */
def shapeOf(view: EdgeView, n: Axi4.Node): AxiShape =
  val e = view.edgeOf(n)
  AxiShape(e.addrBits, e.dataBits, e.idBits)
