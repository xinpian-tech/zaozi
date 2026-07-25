#import "lib.typ": *
#show: setup

// ============ 标题页 ============
#page(numbering: none)[
  #v(4.5cm)
  #align(center)[
    #text(size: 30pt, weight: "bold")[Syntheke 设计文档]
    #v(4mm)
    #text(size: 14pt)[合契 · 面向 SoC 生成器的拓扑与参数协商框架]
    #v(7mm)
    #text(size: 11.5pt, style: "italic", fill: luma(70))[起草拓扑，协商条款，兑现硬件。]
    #v(2mm)
    #text(size: 10pt, fill: luma(110))[_Draft the graph. Negotiate the terms. Enact the hardware._]
    #v(1.6cm)
    #text(size: 10pt, fill: luma(90))[
      版本 0.1（草案） \
      2026 年 7 月 24 日
    ]
  ]
]

// ============ 目录 ============
#outline(depth: 2)

// ============ 章节 ============
#include "chapters/01-motivation.typ"
#include "chapters/02-model.typ"
#include "chapters/03-protocol.typ"
#include "chapters/04-interconnect.typ"
#include "chapters/05-negotiation.typ"
#include "chapters/06-hierarchy.typ"
#include "chapters/07-hardware.typ"
#include "chapters/08-verification.typ"
#include "chapters/09-tooling.typ"
#include "chapters/10-glossary.typ"
