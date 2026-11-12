// Shared style and figure helpers of the Syntheke design document.
#import "@preview/fletcher:0.5.8" as fletcher: diagram, node, edge
#import "@preview/cetz:0.3.4"

// Colors: Down blue, Up red, settled edges green, hierarchy brown, probes purple.
#let c-down = rgb("#1f6feb")
#let c-up = rgb("#c0392b")
#let c-edge = rgb("#1a7f37")
#let c-hier = rgb("#9a6700")
#let c-dv = rgb("#8250df")
#let c-dim = luma(110)
#let c-fill = luma(247)
#let c-clk-a = rgb("#dbe9fb")
#let c-clk-b = rgb("#fde2c4")

#let fig(caption, body) = figure(body, caption: caption, kind: image)
#let small(body) = text(size: 8pt, body)
#let code(body) = text(font: "DejaVu Sans Mono", size: 6.8pt, body)

#let setup(body) = {
  set document(title: "Syntheke Design")
  set page(margin: (x: 2.2cm, y: 2.4cm), numbering: "1")
  set text(size: 10.5pt, lang: "en")
  set par(justify: true, leading: 0.7em, spacing: 1.1em)
  set heading(numbering: "1.1")
  show heading.where(level: 1): it => {
    pagebreak(weak: true)
    v(3mm)
    text(size: 16pt, it)
    v(2mm)
  }
  show raw: set text(size: 9pt)
  show raw.where(block: true): it => block(width: 100%, fill: luma(246), inset: 8pt, radius: 3pt, it)
  show ref: it => {
    let el = it.element
    if el != none and el.func() == heading and el.level == 1 {
      link(el.location(), [Chapter #counter(heading).at(el.location()).first()])
    } else { it }
  }
  set table(stroke: 0.5pt + luma(180), inset: 5pt)
  show table: set par(justify: false)
  show table.cell.where(y: 0): set text(weight: "bold")
  set figure(gap: 3mm)
  show figure.caption: set text(size: 9pt, fill: luma(70))
  show figure: set block(breakable: false)
  body
}
