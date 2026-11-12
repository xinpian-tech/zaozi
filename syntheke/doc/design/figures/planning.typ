#import "common.typ": *

#let planning = cetz.canvas(length: 1cm, {
  import cetz.draw: *
  mod((0, 0), (15.6, 4.6), [`w` (lowest common ancestor)])
  mod((0.4, 0.5), (5.0, 3.8), [`m1`])
  mod((0.8, 1.2), (2.6, 2.8), [`a`], bg: c-fill)
  mod((7.6, 0.5), (15.2, 3.8), [`m2`])
  mod((9.8, 0.9), (14.8, 3.3), [`n`])
  mod((12.8, 1.3), (14.4, 2.9), [`b`], bg: c-fill)
  // generator ports keep their names; every crossed border gets a planned port
  pin((2.6, 2.0), label: "x")
  pin((5.0, 2.0), bg: luma(220))
  content((5.0, 2.4), anchor: "south", code("inst_a_node_x_out"))
  pin((7.6, 2.0), bg: luma(220))
  content((7.6, 1.6), anchor: "north", code("inst_n_inst_b_node_y_in"))
  pin((9.8, 2.0), bg: luma(220))
  content((9.8, 2.4), anchor: "south", code("inst_b_node_y_in"))
  pin((12.8, 2.0), label: "y", inside: "east")
  arrow((2.75, 2.0), (4.85, 2.0))
  arrow((5.15, 2.0), (7.45, 2.0), thickness: 1.6pt)
  arrow((7.75, 2.0), (9.65, 2.0))
  arrow((9.95, 2.0), (12.65, 2.0))
})
