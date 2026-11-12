#import "common.typ": *

#let frozen = cetz.canvas(length: 1cm, {
  import cetz.draw: *
  // design D, negotiated alone against a constant outside
  mod((0, 0), (5.0, 3.4), [design `D`])
  mod((2.4, 0.6), (4.4, 2.2), [`g`], bg: c-fill)
  pin((2.4, 1.4), bg: c-clk-a)
  mod((0.4, 0.9), (1.4, 1.9), none, border: (paint: c-dim, dash: "dashed", thickness: 0.6pt))
  constant((0.9, 1.4))
  arrow((1.1, 1.4), (2.25, 1.4))
  rect((3.3, 2.6), (4.3, 3.0), fill: c-clk-a, stroke: 0.5pt)
  content((3.8, 2.8), code("clk"))
  rect((1.6, 2.6), (2.8, 3.0), fill: c-clk-b, stroke: 0.5pt)
  content((2.2, 2.8), code("div"))
  arrow((2.85, 2.8), (3.25, 2.8), paint: c-dim, thickness: 0.6pt)
  chip((2.6, -0.4), "edge e*", rgb("#d8f0de"))

  // design E: D is an instance, seen as constant nodes carrying e*
  mod((6.6, 0), (13.4, 3.4), [design `E`])
  mod((7.0, 0.6), (9.0, 2.2), [`h`], bg: c-fill)
  pin((9.0, 1.4), bg: c-clk-a)
  mod((10.6, 0.5), (12.8, 2.3), [`d`], bg: luma(232), border: (paint: c-hier, dash: "dashed", thickness: 0.8pt))
  constant((10.9, 1.4))
  arrow((9.15, 1.4), (10.7, 1.4))
  // E's clk is fed in place of D's clk; D's div is copied under the instance
  rect((7.2, 2.6), (8.2, 3.0), fill: c-clk-a, stroke: 0.5pt)
  content((7.7, 2.8), code("clk"))
  rect((11.2, 1.75), (12.6, 2.1), fill: c-clk-b, stroke: 0.5pt)
  content((11.9, 1.92), code("d.div"))
  bezier((11.2, 1.95), (8.25, 2.8), (9.8, 2.9), stroke: 0.6pt + c-dim, mark: (end: ">"))
  arrow((8.25, 2.65), (10.75, 1.6), paint: c-dim, thickness: 0.6pt, dash: "dashed")
  chip((9.9, -0.4), "edge e", rgb("#d8f0de"))
  content((11.1, -0.4), $=$)
  chip((12.0, -0.4), "edge e*", rgb("#d8f0de"))
})
