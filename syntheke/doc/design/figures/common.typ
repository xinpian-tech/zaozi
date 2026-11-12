#import "../lib.typ": *

// Drawing helpers shared by the figures, called inside a cetz canvas.
#let mod(a, b, label, bg: none, border: 0.8pt + c-hier) = {
  import cetz.draw: *
  rect(a, b, stroke: border, fill: bg, radius: 2pt)
  if label != none { content((a.at(0) + 0.12, b.at(1) - 0.12), anchor: "north-west", small(label)) }
}
#let pin(p, label: none, bg: white, inside: "west", border: 0.6pt) = {
  import cetz.draw: *
  circle(p, radius: 0.12, fill: bg, stroke: border)
  if label != none {
    let dx = if inside == "west" { -0.2 } else { 0.2 }
    content((p.at(0) + dx, p.at(1)), anchor: if inside == "west" { "east" } else { "west" }, code(label))
  }
}
#let arrow(a, b, paint: c-edge, thickness: 1pt, dash: none) = {
  import cetz.draw: *
  line(a, b, stroke: (paint: paint, thickness: thickness, dash: dash), mark: (end: ">", fill: paint))
}
#let constant(p, label: none) = {
  import cetz.draw: *
  let (x, y) = p
  line((x, y + 0.16), (x + 0.16, y), (x, y - 0.16), (x - 0.16, y), close: true, fill: luma(60), stroke: none)
  if label != none { content((x, y + 0.36), code(label)) }
}
#let chip(p, label, bg) = {
  import cetz.draw: *
  content(p, box(fill: bg, inset: (x: 3pt, y: 2pt), radius: 2pt, code(label)))
}
