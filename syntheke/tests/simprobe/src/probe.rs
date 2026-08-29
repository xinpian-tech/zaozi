// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>

//! A probe-rs debug probe whose TAP is a running simulation.
//!
//! Everything above the pins is probe-rs's: the JTAG state machine, the debug transport module, the debug module, the
//! RISC-V debug sequences. This only moves bits — it buffers what probe-rs shifts and hands the batch to the
//! simulation's DPI bridge over a socket, which drives `tck`, `tms` and `tdi` and samples `tdo`.

use std::io::{Read, Write};
use std::net::TcpStream;

use bitvec::prelude::*;
use probe_rs::architecture::riscv::communication_interface::{RiscvError, RiscvInterfaceBuilder};
use probe_rs::architecture::riscv::dtm::jtag_dtm::JtagDtmBuilder;
use probe_rs::probe::{
    AutoImplementJtagAccess, DebugProbe, DebugProbeError, JtagAccess, JtagDriverState, RawJtagIo,
    WireProtocol,
};

/// One shifted bit as the bridge sees it: what to drive, and whether to keep what comes back.
const TMS: u8 = 1 << 0;
const TDI: u8 = 1 << 1;
const CAPTURE: u8 = 1 << 2;

pub struct SimProbe {
    stream: TcpStream,
    /// Bits probe-rs has shifted but the simulation has not been asked to clock out yet.
    pending: Vec<u8>,
    /// TDO of every captured bit since the last read.
    captured: BitVec,
    jtag_state: JtagDriverState,
    speed_khz: u32,
}

impl std::fmt::Debug for SimProbe {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("SimProbe").field("pending", &self.pending.len()).finish()
    }
}

impl SimProbe {
    /// Wait for the simulation to come up — verilating and elaborating take a while — then take its socket.
    pub fn connect(address: &str, timeout: std::time::Duration) -> std::io::Result<Self> {
        let deadline = std::time::Instant::now() + timeout;
        let stream = loop {
            match TcpStream::connect(address) {
                Ok(stream) => break stream,
                Err(e) if std::time::Instant::now() < deadline => {
                    let _ = e;
                    std::thread::sleep(std::time::Duration::from_millis(50));
                }
                Err(e) => return Err(e),
            }
        };
        stream.set_nodelay(true)?;
        Ok(Self {
            stream,
            pending: Vec::new(),
            captured: BitVec::new(),
            jtag_state: JtagDriverState::default(),
            speed_khz: 1_000,
        })
    }

    /// Hand the buffered bits to the simulation and take back what it captured. One round trip per flush, so probe-rs
    /// batching its shifts is what keeps this quick.
    fn flush(&mut self) -> Result<(), DebugProbeError> {
        if self.pending.is_empty() {
            return Ok(());
        }
        let wanted = self.pending.iter().filter(|b| *b & CAPTURE != 0).count();

        let mut frame = (self.pending.len() as u32).to_le_bytes().to_vec();
        frame.extend_from_slice(&self.pending);
        self.stream.write_all(&frame).map_err(io_err)?;
        self.stream.flush().map_err(io_err)?;
        self.pending.clear();

        let mut tdo = vec![0u8; wanted];
        self.stream.read_exact(&mut tdo).map_err(io_err)?;
        for bit in tdo {
            self.captured.push(bit != 0);
        }
        Ok(())
    }
}

fn io_err(e: std::io::Error) -> DebugProbeError {
    DebugProbeError::Other(format!("simulation bridge: {e}"))
}

impl AutoImplementJtagAccess for SimProbe {}

impl RawJtagIo for SimProbe {
    fn shift_bit(&mut self, tms: bool, tdi: bool, capture: bool) -> Result<(), DebugProbeError> {
        self.jtag_state.state.update(tms);
        let mut encoded = 0u8;
        if tms {
            encoded |= TMS;
        }
        if tdi {
            encoded |= TDI;
        }
        if capture {
            encoded |= CAPTURE;
        }
        self.pending.push(encoded);
        Ok(())
    }

    fn read_captured_bits(&mut self) -> Result<BitVec, DebugProbeError> {
        self.flush()?;
        Ok(std::mem::take(&mut self.captured))
    }

    fn state_mut(&mut self) -> &mut JtagDriverState {
        &mut self.jtag_state
    }

    fn state(&self) -> &JtagDriverState {
        &self.jtag_state
    }
}

impl DebugProbe for SimProbe {
    fn get_name(&self) -> &str {
        "syntheke simulation bridge"
    }

    fn speed_khz(&self) -> u32 {
        self.speed_khz
    }

    fn set_speed(&mut self, speed_khz: u32) -> Result<u32, DebugProbeError> {
        // The simulation clocks tck itself; the number is bookkeeping.
        self.speed_khz = speed_khz;
        Ok(speed_khz)
    }

    fn attach(&mut self) -> Result<(), DebugProbeError> {
        self.select_target(0)
    }

    fn detach(&mut self) -> Result<(), probe_rs::Error> {
        Ok(())
    }

    fn target_reset(&mut self) -> Result<(), DebugProbeError> {
        Err(DebugProbeError::NotImplemented { function_name: "target_reset" })
    }

    fn target_reset_assert(&mut self) -> Result<(), DebugProbeError> {
        Err(DebugProbeError::NotImplemented { function_name: "target_reset_assert" })
    }

    fn target_reset_deassert(&mut self) -> Result<(), DebugProbeError> {
        Err(DebugProbeError::NotImplemented { function_name: "target_reset_deassert" })
    }

    fn select_protocol(&mut self, protocol: WireProtocol) -> Result<(), DebugProbeError> {
        if protocol == WireProtocol::Jtag {
            Ok(())
        } else {
            Err(DebugProbeError::UnsupportedProtocol(protocol))
        }
    }

    fn active_protocol(&self) -> Option<WireProtocol> {
        Some(WireProtocol::Jtag)
    }

    fn try_as_jtag_probe(&mut self) -> Option<&mut dyn JtagAccess> {
        Some(self)
    }

    fn try_get_riscv_interface_builder<'probe>(
        &'probe mut self,
    ) -> Result<Box<dyn RiscvInterfaceBuilder<'probe> + 'probe>, RiscvError> {
        Ok(Box::new(JtagDtmBuilder::new(self)))
    }

    fn has_riscv_interface(&self) -> bool {
        true
    }

    fn into_probe(self: Box<Self>) -> Box<dyn DebugProbe> {
        self
    }
}
