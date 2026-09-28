package me.jiuyang.syntheke.demo

import me.jiuyang.syntheke.*
import me.jiuyang.syntheke.demo.zaoziimpl.AxiShape


def shapeOf(e: AxiEdgeParams): AxiShape =
  AxiShape(e.addrBits, e.dataBits, e.idBits)
