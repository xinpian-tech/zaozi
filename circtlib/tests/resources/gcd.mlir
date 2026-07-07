module {
  hw.hierpath private @xmrPath [@GCD::@sym]
  hw.module @GCD(in %clock : !seq.clock, in %reset : i1, out input_ready : i1, in %input_valid : i1, in %input_bits_x : i16, in %input_bits_y : i16, out output_valid : i1, out output_bits : i16) {
    %c0_i16 = hw.constant 0 : i16
    %false = hw.constant false
    %true = hw.constant true
    %x = seq.firreg %7 clock %clock {clockEdge = 0 : i32, firrtl.random_init_start = 0 : ui64, sv.namehint = "x"} : i16
    %y = seq.firreg %8 clock %clock reset sync %reset, %c0_i16 {clockEdge = 0 : i32, resetPolarity = 0 : i32, firrtl.random_init_start = 16 : ui64} : i16
    %startupFlag = seq.firreg %9 clock %clock reset sync %reset, %false {clockEdge = 0 : i32, resetPolarity = 0 : i32, firrtl.random_init_start = 32 : ui64} : i1
    %0 = comb.icmp bin ne %y, %c0_i16 {sv.namehint = "busy"} : i16
    %1 = comb.icmp bin ugt %x, %y : i16
    %2 = comb.sub bin %x, %y {sv.namehint = "_x_T"} : i16
    %3 = comb.mux bin %1, %2, %x : i16
    %4 = comb.sub bin %y, %x {sv.namehint = "_y_T"} : i16
    %5 = comb.mux bin %1, %y, %4 : i16
    %6 = comb.and bin %10, %input_valid : i1
    %7 = comb.mux bin %6, %input_bits_x, %3 : i16
    %8 = comb.mux bin %6, %input_bits_y, %5 : i16
    %9 = comb.or %6, %startupFlag : i1
    %10 = comb.xor bin %0, %true {sv.namehint = "input_ready"} : i1
    %11 = comb.and bin %startupFlag, %10 {sv.namehint = "output_valid"} : i1
    %probeWire_busy_probe = hw.wire %0 sym @sym  : i1
    hw.output %10, %11, %x : i1, i1, i16
  }
  om.class @GCDOM(%basepath: !om.basepath)  -> (width: !om.integer, useAsyncReset: i1) {
    %0 = om.constant #om.integer<16 : si8> : !om.integer
    %1 = om.constant false
    om.class.fields %0, %1 : !om.integer, i1 
  }
  om.class @GCD_Class(%basepath: !om.basepath)  -> (om: !om.any) {
    %0 = om.object @GCDOM(%basepath) : (!om.basepath) -> !om.class.type<@GCDOM>
    %1 = om.any_cast %0 : (!om.class.type<@GCDOM>) -> !om.any
    om.class.fields %1 : !om.any 
  }
}
