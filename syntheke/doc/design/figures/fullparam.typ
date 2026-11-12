#import "common.typ": *

#let fullparam = cetz.canvas(length: 1cm, {
  import cetz.draw: *
  // the module's edge view: each node with its settled edge
  rect((0, 0), (3.2, 2.2), radius: 2pt, stroke: 0.6pt, fill: c-fill)
  content((1.6, 2.45), code("EdgeView"))
  for (i, n) in ("in0", "in1", "out0").enumerate() {
    let y = 1.75 - i * 0.6
    pin((0.45, y), label: n, inside: "east")
    chip((2.5, y), "Edge", rgb("#d8f0de"))
  }
  rect((0, 2.9), (3.2, 3.7), radius: 2pt, stroke: 0.6pt, fill: rgb("#eef2fb"))
  content((1.6, 3.3), code("user parameter"))
  arrow((3.3, 1.1), (4.4, 1.7), paint: luma(60), thickness: 0.7pt)
  arrow((3.3, 3.3), (4.4, 2.1), paint: luma(60), thickness: 0.7pt)
  rect((4.5, 1.5), (6.4, 2.3), radius: 2pt, stroke: 0.7pt)
  content((5.45, 1.9), code("parameters"))
  arrow((6.5, 1.9), (7.4, 1.9), paint: luma(60), thickness: 0.7pt)
  // the full parameter, serializable
  rect((7.5, 1.25), (8.9, 2.55), radius: 2pt, stroke: 0.7pt, fill: rgb("#fff4d6"))
  content((8.2, 2.1), code("FP"))
  content((8.2, 1.6), text(size: 6pt, fill: c-dim, raw("{…}")))
  line((9.6, -0.2), (9.6, 4.0), stroke: 2pt)
  arrow((9.0, 1.9), (10.3, 1.9), paint: c-edge, thickness: 1.2pt)
  rect((10.4, 1.5), (12.0, 2.3), radius: 2pt, stroke: 0.7pt)
  content((11.2, 1.9), code("generator"))
  arrow((12.1, 1.9), (12.8, 1.9), paint: luma(60), thickness: 0.7pt)
  mod((12.9, 0.9), (15.0, 2.9), none, bg: c-fill)
  for (i, y) in (2.4, 1.9, 1.4).enumerate() { pin((12.9, y)) }
  content((4.8, -0.1), small(text(fill: c-dim)[negotiation]))
  content((12.0, -0.1), small(text(fill: c-dim)[elaboration]))
})
