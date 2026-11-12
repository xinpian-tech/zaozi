#import "common.typ": *

// The graph of figure 1, once per pass. Numbers are the topological order.
#let pass(up) = cetz.canvas(length: 1cm, {
  import cetz.draw: *
  let paint = if up { c-up } else { c-down }
  mod((0, 1.6), (1.8, 2.6), [`a`], bg: c-fill)
  mod((0, 0), (1.8, 1.0), [`b`], bg: c-fill)
  mod((3.6, 0), (6.4, 2.6), [`x`], bg: c-fill)
  mod((8.2, 0.8), (10.0, 1.8), [`c`], bg: c-fill)
  let nodes = ((1.8, 2.1, "1"), (1.8, 0.5, "2"), (3.6, 2.1, "3"), (3.6, 0.5, "4"), (6.4, 1.3, "5"), (8.2, 1.3, "6"))
  for (x, y, n) in nodes {
    circle((x, y), radius: 0.17, fill: white, stroke: 0.6pt)
    content((x, y), text(size: 6pt, n))
  }
  let flow(a, b, label, dash: none) = {
    let (from, to) = if up { (b, a) } else { (a, b) }
    let dx = to.at(0) - from.at(0)
    let dy = to.at(1) - from.at(1)
    let len = calc.sqrt(dx * dx + dy * dy)
    let s = (from.at(0) + 0.2 * dx / len, from.at(1) + 0.2 * dy / len)
    let e = (to.at(0) - 0.2 * dx / len, to.at(1) - 0.2 * dy / len)
    arrow(s, e, paint: paint, dash: dash, thickness: if dash == none { 1pt } else { 0.7pt })
    if label != none { content(((from.at(0) + to.at(0)) / 2, (from.at(1) + to.at(1)) / 2 + 0.25), text(fill: paint, code(label))) }
  }
  if up {
    flow((1.8, 2.1), (3.6, 2.1), "u3")
    flow((1.8, 0.5), (3.6, 0.5), "u4")
    flow((3.6, 2.1), (6.4, 1.3), none, dash: "dotted")
    flow((3.6, 0.5), (6.4, 1.3), none, dash: "dotted")
    flow((6.4, 1.3), (8.2, 1.3), "u6")
  } else {
    flow((1.8, 2.1), (3.6, 2.1), "d1")
    flow((1.8, 0.5), (3.6, 0.5), "d2")
    flow((3.6, 2.1), (6.4, 1.3), none, dash: "dotted")
    flow((3.6, 0.5), (6.4, 1.3), none, dash: "dotted")
    flow((6.4, 1.3), (8.2, 1.3), "d5")
  }
})

#let propagation = grid(columns: (auto, auto), column-gutter: 6mm, row-gutter: 4mm, align: horizon,
  small(text(fill: c-down)[`Down`, 1 → 6]), pass(false),
  small(text(fill: c-up)[`Up`, 6 → 1]), pass(true))
