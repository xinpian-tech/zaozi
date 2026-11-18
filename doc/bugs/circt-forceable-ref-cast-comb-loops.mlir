module {
  firrtl.circuit "Repro" {
    firrtl.layer @Test bind {}
    firrtl.module @Repro(in %clock: !firrtl.clock, in %data: !firrtl.uint<8>, out %out: !firrtl.uint<8>, out %probe: !firrtl.rwprobe<uint<8>, @Test>) {
      %target, %target_ref = firrtl.reg %clock forceable : !firrtl.clock, !firrtl.uint<8>, !firrtl.rwprobe<uint<8>>
      firrtl.connect %target, %data : !firrtl.uint<8>
      firrtl.connect %out, %target : !firrtl.uint<8>
      firrtl.layerblock @Test {
        %ref = firrtl.ref.cast %target_ref : (!firrtl.rwprobe<uint<8>>) -> !firrtl.rwprobe<uint<8>, @Test>
        firrtl.ref.define %probe, %ref : !firrtl.rwprobe<uint<8>, @Test>
      }
    }
  }
}
