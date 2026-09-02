// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>

//! Bind Ramulator's C ABI. `$RAMULATOR_CAPI_INSTALL_PATH` is the package that provides it — the header to bind and
//! the library to link.

use std::env;
use std::path::PathBuf;

fn main() {
    let prefix = PathBuf::from(
        env::var("RAMULATOR_CAPI_INSTALL_PATH")
            .expect("RAMULATOR_CAPI_INSTALL_PATH is unset: `nix develop .#syntheke` provides it"),
    );
    let header = prefix.join("include/ramulator_capi.h");

    println!("cargo:rustc-link-search=native={}", prefix.join("lib").display());
    println!("cargo:rustc-link-lib=dylib=ramulator_capi");
    println!("cargo:rerun-if-changed={}", header.display());
    println!("cargo:rerun-if-env-changed=RAMULATOR_CAPI_INSTALL_PATH");

    let bindings = bindgen::Builder::default()
        .header(header.to_str().expect("header path is not UTF-8"))
        .allowlist_function("ramulator_.*")
        .allowlist_type("ramulator_t")
        .generate()
        .expect("cannot bind ramulator_capi.h");

    bindings
        .write_to_file(PathBuf::from(env::var("OUT_DIR").unwrap()).join("ramulator.rs"))
        .expect("cannot write the Ramulator bindings");
}
