// Syntheke 设计文档 — 共享样式与图件
#import "@preview/fletcher:0.5.8" as fletcher: diagram, node, edge
#import "@preview/cetz:0.3.4"

// ============ 颜色约定 ============
// 全文档统一：下行=蓝，上行=红，结算边=绿，层次=土黄，验证=紫。
#let c-down = rgb("#1f6feb")
#let c-up = rgb("#c0392b")
#let c-edge = rgb("#1a7f37")
#let c-hier = rgb("#9a6700")
#let c-dv = rgb("#8250df")
#let c-dim = luma(110)
#let c-fill = luma(247)

// ============ 文档设置 ============
#let setup(body) = {
  set page(margin: (x: 2.2cm, y: 2.4cm), numbering: "1")
  set text(font: ("Noto Serif CJK SC",), lang: "zh", region: "cn", size: 10.5pt)
  set par(justify: true, leading: 0.82em, spacing: 1.15em)
  set heading(numbering: "1.1")
  show heading.where(level: 1): it => {
    pagebreak(weak: true)
    v(4mm)
    text(size: 17pt, it)
    v(3mm)
  }
  show heading.where(level: 2): it => {
    v(2.5mm)
    text(size: 13pt, it)
    v(1mm)
  }
  show heading.where(level: 3): it => {
    v(1.5mm)
    text(size: 11pt, it)
    v(0.5mm)
  }
  show raw: set text(font: ("JetBrains Mono", "Noto Sans Mono CJK SC"), size: 9pt)
  show raw.where(block: true): it => block(
    width: 100%,
    fill: c-fill,
    inset: 8pt,
    radius: 3pt,
    it,
  )
  // 仅外部链接加下划线；文内交叉引用保持正文样式
  show link: it => if type(it.dest) == str { underline(it) } else { it }
  // 交叉引用渲染：一级标题 → “第 N 章”，其余标题 → “§N.M”；决策/开放问题沿用默认（补充词 + 编号）
  show ref: it => {
    let el = it.element
    if el != none and el.func() == heading {
      let nums = counter(heading).at(el.location())
      if el.level == 1 {
        link(el.location(), [第 #nums.first() 章])
      } else {
        link(el.location(), [§#nums.map(str).join(".")])
      }
    } else {
      it
    }
  }
  set table(stroke: 0.5pt + luma(170), inset: 6pt)
  show figure.caption: set text(size: 9pt, fill: luma(80))
  set figure(gap: 3mm)
  body
}

// ============ 术语 ============
// 术语首次出现：中文加粗，附英文。
#let term(zh, en) = [*#zh*（#en）]

// ============ 决策框 / 开放问题框 ============
#let decision-counter = counter("syn-decision")
#let open-counter = counter("syn-open")

// 决策/开放问题为可交叉引用的编号元素（figure kind），@label 渲染为“决策 N”/“开放问题 N”。
#let 决策(title, body) = figure(
  kind: "decision",
  supplement: [决策],
  numbering: "1",
  caption: none,
  block(
    width: 100%,
    stroke: (left: 2.5pt + c-edge),
    fill: rgb("#f2f9f4"),
    inset: 9pt,
    radius: 2pt,
    align(left)[
      #decision-counter.step()
      #text(fill: c-edge, weight: "bold")[决策 #context decision-counter.display()　#title]
      #linebreak()
      #body
    ],
  ),
)

#let 开放(title, body) = figure(
  kind: "open-question",
  supplement: [开放问题],
  numbering: "1",
  caption: none,
  block(
    width: 100%,
    stroke: (left: 2.5pt + rgb("#bf8700")),
    fill: rgb("#fdf8ef"),
    inset: 9pt,
    radius: 2pt,
    align(left)[
      #open-counter.step()
      #text(fill: rgb("#9a6700"), weight: "bold")[开放问题 #context open-counter.display()　#title]
      #linebreak()
      #body
    ],
  ),
)

#let 不变量(body) = block(
  width: 100%,
  stroke: (left: 2.5pt + c-down),
  fill: rgb("#f0f6fd"),
  inset: 9pt,
  radius: 2pt,
)[
  #text(fill: c-down, weight: "bold")[不变量]
  #h(1em)
  #body
]

// ============ 图件 ============
// 统一的 fletcher 参数；图内文字统一缩小。
#let syn-diagram(..args) = text(
  size: 9pt,
  diagram(
    node-stroke: 0.7pt,
    node-corner-radius: 2.5pt,
    node-inset: 6.5pt,
    edge-stroke: 0.7pt,
    spacing: (13mm, 9mm),
    ..args,
  ),
)

#let syn-canvas(body) = text(size: 9pt, cetz.canvas(body))

// 居中带题图
#let 图(caption, body) = figure(align(center, body), caption: caption)
