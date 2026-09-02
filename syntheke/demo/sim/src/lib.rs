// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>

//! What the demo SoC's testbench does that RTL cannot: hold the debugger's socket open on the JTAG pins, and answer
//! the memory port out of a DRAM simulator. The SystemVerilog beside this crate does the pins and the handshakes and
//! nothing else; everything with state lives here, and reaches the simulation through DPI.
//!
//! Each module exports plain `extern "C"` functions, which is all a DPI import is.

mod dram;
mod jtag;
