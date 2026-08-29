// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2025 Jiuyang Liu <liu@jiuyang.me>

//! Bring up the syntheke demo SoC the way a debugger does: attach over JTAG, halt the harts, download the program,
//! give each hart its start PC, and let them go.
//!
//! The JTAG pins belong to a running simulation, reached through the DPI bridge ([`probe::SimProbe`]); everything
//! above them — the TAP walk, the debug transport, the debug module, the RISC-V sequences — is probe-rs.

mod probe;

use std::time::Duration;

use anyhow::{Context, Result, bail};
use probe_rs::config::Registry;
use probe_rs::probe::Probe;
use probe_rs::{MemoryInterface, Permissions};

use crate::probe::SimProbe;

struct Args {
    bridge:   String,
    target:   String,
    chip:     String,
    image:    String,
    load:     u64,
    hart_pcs: Vec<(usize, u64)>,
}

fn parse_args() -> Result<Args> {
    let mut bridge = "127.0.0.1:5555".to_string();
    let mut target = String::new();
    let mut chip = String::new();
    let mut image = String::new();
    let mut load = 0u64;
    let mut hart_pcs = Vec::new();

    let mut argv = std::env::args().skip(1);
    while let Some(flag) = argv.next() {
        let mut value = || argv.next().with_context(|| format!("{flag} needs a value"));
        match flag.as_str() {
            "--bridge" => bridge = value()?,
            "--target" => target = value()?,
            "--chip" => chip = value()?,
            "--image" => image = value()?,
            "--load" => load = parse_u64(&value()?)?,
            // --hart-pc <index>:<address>, once per hart to start
            "--hart-pc" => {
                let spec = value()?;
                let (index, address) = spec.split_once(':').context("--hart-pc wants <index>:<address>")?;
                hart_pcs.push((index.parse()?, parse_u64(address)?));
            }
            other => bail!("unknown flag {other}"),
        }
    }
    if target.is_empty() || chip.is_empty() || image.is_empty() {
        bail!("usage: simprobe --target <yaml> --chip <name> --image <bin> --load <addr> --hart-pc <i>:<addr> ...");
    }
    Ok(Args { bridge, target, chip, image, load, hart_pcs })
}

fn parse_u64(s: &str) -> Result<u64> {
    let s = s.trim();
    Ok(match s.strip_prefix("0x") {
        Some(hex) => u64::from_str_radix(hex, 16)?,
        None => s.parse()?,
    })
}

fn main() -> Result<()> {
    let args = parse_args()?;

    let bytes = std::fs::read(&args.image).with_context(|| format!("reading {}", args.image))?;
    if bytes.len() % 4 != 0 {
        bail!("image {} is not a whole number of 32-bit words", args.image);
    }
    let words: Vec<u32> =
        bytes.chunks_exact(4).map(|w| u32::from_le_bytes([w[0], w[1], w[2], w[3]])).collect();

    // The target is this SoC, described beside the crate: probe-rs ships no built-in family for it.
    let yaml = std::fs::read_to_string(&args.target).with_context(|| format!("reading {}", args.target))?;
    let mut registry = Registry::new();
    registry.add_target_family_from_yaml(&yaml).context("registering the SoC target")?;

    let probe = Probe::from_specific_probe(Box::new(
        SimProbe::connect(&args.bridge, Duration::from_secs(120))
            .with_context(|| format!("connecting to {}", args.bridge))?,
    ));
    let mut session = probe
        .attach_with_registry(args.chip.as_str(), Permissions::default(), &registry)
        .context("attaching to the SoC")?;

    // The program goes in through hart 0's abstract memory access — the only method this debug module offers, and
    // the one probe-rs falls back to when there is no program buffer.
    {
        let mut core = session.core(0).context("selecting hart 0")?;
        core.halt(Duration::from_secs(10)).context("halting hart 0")?;
        core.write_32(args.load, &words).context("downloading the program")?;
        println!("[simprobe] wrote {} words at {:#010x}", words.len(), args.load);
    }

    for (hart, pc) in &args.hart_pcs {
        let mut core = session.core(*hart).with_context(|| format!("selecting hart {hart}"))?;
        core.halt(Duration::from_secs(10)).with_context(|| format!("halting hart {hart}"))?;
        let program_counter = core.program_counter();
        core.write_core_reg(program_counter, *pc).with_context(|| format!("setting hart {hart} pc"))?;
        core.run().with_context(|| format!("resuming hart {hart}"))?;
        println!("[simprobe] hart {hart} runs from {pc:#010x}");
    }

    Ok(())
}
