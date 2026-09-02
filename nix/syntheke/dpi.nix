# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>

# The testbench's own behaviour, as a static library the verilated simulation links: the debugger's socket on the JTAG
# pins, and the memory behind the bus port. Rust throughout; Ramulator is reached through its C ABI.
{ lib
, rustPlatform
, ramulator-capi
}:

rustPlatform.buildRustPackage {
  pname = "syntheke-dpi";
  version = "0.1.0";

  src = ../../syntheke/demo/sim;
  cargoLock.lockFile = ../../syntheke/demo/sim/Cargo.lock;

  # bindgen needs libclang, and the header and library to bind come from the C ABI package.
  nativeBuildInputs = [ rustPlatform.bindgenHook ];
  buildInputs = [ ramulator-capi ];
  RAMULATOR_CAPI_INSTALL_PATH = ramulator-capi;

  # A cdylib is not a Rust crate anyone links with cargo; install it where a linker will look.
  postInstall = ''
    mkdir -p $out/lib
    find target -name 'libsyntheke_dpi.so' -exec install -Dm555 {} $out/lib/libsyntheke_dpi.so \;
  '';

  meta = {
    description = "The DPI behind the syntheke demo's testbench";
    license = lib.licenses.asl20;
    platforms = lib.platforms.unix;
  };
}
