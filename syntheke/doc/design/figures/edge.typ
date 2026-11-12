#import "common.typ": *

#let edge-figure = cetz.canvas(length: 1cm, {
  import cetz.draw: *
  mod((0, 0.4), (3, 3.2), [source module], bg: c-fill)
  mod((9, 0.4), (12, 3.2), [target module], bg: c-fill)
  pin((3, 1.8), label: "o")
  pin((9, 1.8), label: "i", inside: "east")
  arrow((3.2, 2.15), (8.8, 2.15), paint: c-down)
  content((6, 2.45), text(fill: c-down, code("Down")))
  arrow((8.8, 1.45), (3.2, 1.45), paint: c-up)
  content((6, 1.15), text(fill: c-up, code("Up")))
  // negotiate turns the two into the edge
  rect((5.0, -0.75), (7.0, -0.15), radius: 2pt, stroke: 0.7pt)
  content((6, -0.45), code("negotiate"))
  line((4.6, 2.15), (4.6, -0.45), (5.0, -0.45), stroke: (paint: c-down, dash: "dotted"), mark: (end: ">"))
  line((7.4, 1.45), (7.4, -0.45), (7.0, -0.45), stroke: (paint: c-up, dash: "dotted"), mark: (end: ">"))
  arrow((6, -0.8), (6, -1.4), paint: c-edge)
  chip((6, -1.65), "Edge", rgb("#d8f0de"))
  arrow((6, -1.9), (6, -2.4), paint: luma(60), thickness: 0.6pt)
  // the interface: a bundle seen from the source, one field flipped
  let fy = -2.7
  for (i, flipped) in (false, false, true).enumerate() {
    let y = fy - i * 0.35
    rect((4.9, y - 0.13), (7.1, y + 0.13), fill: white, stroke: 0.5pt)
    if flipped { arrow((6.6, y), (5.4, y), paint: c-up, thickness: 0.7pt) }
    else { arrow((5.4, y), (6.6, y), paint: c-down, thickness: 0.7pt) }
  }
  line((4.9, -3.2), (3.0, 1.6), stroke: (paint: luma(150), dash: "dashed", thickness: 0.5pt))
  line((7.1, -3.2), (9.0, 1.6), stroke: (paint: luma(150), dash: "dashed", thickness: 0.5pt))
  content((3.6, -1.2), code("Output"))
  content((8.4, -1.2), code("Input"))
})
