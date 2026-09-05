hw.module @Child(in %clk : !seq.clock, in %x : i1, out y : i1) {
  %init = seq.initial() {
    %c = hw.constant false
    seq.yield %c : i1
  } : () -> !seq.immutable<i1>
  %r = seq.compreg %x, %clk initial %init : i1
  hw.output %r : i1
}
hw.module @Top3(in %clk : !seq.clock, in %x : i1) {
  %true = hw.constant true
  %nx = comb.xor bin %x, %true : i1
  // x is never high, and the child's register starts at 0, so its output can never be high.
  verif.assume %nx label "never_x" : i1
  %inst.y = hw.instance "inst" @Child(clk: %clk: !seq.clock, x: %x: i1) -> (y: i1)
  %ny = comb.xor bin %inst.y, %true : i1
  verif.assert %ny label "y_never_high" : i1
}
