# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>

# The debugger the demo SoC is brought up with: a probe-rs probe whose JTAG
# pins are a simulation's, over the testbench's DPI bridge.
{ lib
, rustPlatform
}:

rustPlatform.buildRustPackage {
  pname = "simprobe";
  version = "0.1.0";

  src = ../../syntheke/tests/simprobe;
  cargoLock.lockFile = ../../syntheke/tests/simprobe/Cargo.lock;

  meta = {
    description = "probe-rs debug probe over a simulation's JTAG DPI bridge";
    license = lib.licenses.asl20;
    mainProgram = "simprobe";
    platforms = lib.platforms.unix;
  };
}
