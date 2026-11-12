#import "../lib.typ": *

// Rules against time: a hollow mark where code is written, a filled mark where it runs.
#let lifecycle = cetz.canvas(length: 1cm, {
  import cetz.draw: *
  let bands = ((0, 5.0, [build]), (5.0, 9.6, [negotiation]), (9.6, 12.4, [elaboration]), (12.4, 15.0, [link, lower]))
  for (i, (a, b, label)) in bands.enumerate() {
    rect((a, 0), (b, 4.8), fill: if calc.even(i) { luma(248) } else { white }, stroke: none)
    line((b, 0), (b, 4.8), stroke: (paint: luma(190), dash: "dashed", thickness: 0.5pt))
    content(((a + b) / 2, -0.3), small(label))
  }
  let rows = ("fixed", "derive", "parameters", "probe", "generator")
  let y(row) = 4.2 - rows.position(r => r == row) * 0.75
  for r in rows { content((-0.15, y(r)), anchor: "east", code(r)) }
  let wrote(x, r) = circle((x, y(r)), radius: 0.11, fill: white, stroke: 0.9pt + c-down)
  let ran(x, r) = circle((x, y(r)), radius: 0.11, fill: c-edge, stroke: 0.9pt + c-edge)
  let later(a, b, r) = bezier((a + 0.15, y(r)), (b - 0.15, y(r)), ((a + b) / 2, y(r) + 0.45),
    stroke: (paint: c-dim, dash: "dotted", thickness: 0.8pt), mark: (end: ">"))

  ran(2.0, "fixed")
  wrote(2.3, "derive"); ran(6.0, "derive"); later(2.3, 6.0, "derive")
  wrote(2.7, "parameters"); ran(7.4, "parameters"); later(2.7, 7.4, "parameters")
  wrote(3.1, "probe"); ran(8.4, "probe"); later(3.1, 8.4, "probe")
  ran(11.0, "generator")

  // a frozen design D: built and negotiated inside this build, on first use
  let yd = 0.75
  content((-0.15, yd), anchor: "east", code("D"))
  rect((1.0, yd - 0.16), (1.7, yd + 0.16), fill: luma(225), stroke: 0.5pt)
  rect((1.7, yd - 0.16), (2.3, yd + 0.16), fill: c-clk-a, stroke: 0.5pt)
  line((2.35, yd + 0.05), (3.4, y("probe") - 0.2), stroke: 0.6pt + c-dim, mark: (end: ">"))

  // what each stage leaves
  for (x, label) in ((5.0, "DesignSpec"), (9.6, "ResolvedDesign"), (12.4, "mlirbc"), (15.0, "Verilog")) {
    let p = (x, 5.1)
    line((x, 5.25), (x + 0.15, 5.1), (x, 4.95), (x - 0.15, 5.1), close: true, fill: c-hier, stroke: none)
    content((x, 5.45), code(label))
  }
})
