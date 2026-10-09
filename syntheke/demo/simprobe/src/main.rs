

mod probe;

use std::time::{Duration, Instant};

use anyhow::{Context, Result, bail};
use probe_rs::architecture::riscv::communication_interface::{
    MemoryAccessMethod, RiscvBusAccess, Sbcs,
};
use probe_rs::config::Registry;
use probe_rs::probe::Probe;
use probe_rs::{MemoryInterface, Permissions, Session};

use crate::probe::SimProbe;

struct Args {
    bridge:   String,
    target:   String,
    chip:     String,
    image:    String,
    load:     u64,
    hart_pcs: Vec<(usize, u64)>,
    power_base: Option<u64>,
}

fn parse_args() -> Result<Args> {
    let mut bridge = "127.0.0.1:5555".to_string();
    let mut target = String::new();
    let mut chip = String::new();
    let mut image = String::new();
    let mut load = 0u64;
    let mut hart_pcs = Vec::new();
    let mut power_base = None;

    let mut argv = std::env::args().skip(1);
    while let Some(flag) = argv.next() {
        let mut value = || argv.next().with_context(|| format!("{flag} needs a value"));
        match flag.as_str() {
            "--bridge" => bridge = value()?,
            "--target" => target = value()?,
            "--chip" => chip = value()?,
            "--image" => image = value()?,
            "--load" => load = parse_u64(&value()?)?,
            "--power-base" => power_base = Some(parse_u64(&value()?)?),
            "--hart-pc" => {
                let spec = value()?;
                let (index, address) = spec.split_once(':').context("--hart-pc wants <index>:<address>")?;
                hart_pcs.push((index.parse()?, parse_u64(address)?));
            }
            other => bail!("unknown flag {other}"),
        }
    }
    if target.is_empty() || chip.is_empty() || image.is_empty() {
        bail!("usage: simprobe --target <yaml> --chip <name> --image <bin> --load <addr> --hart-pc <i>:<addr> ... [--power-base <addr>]");
    }
    Ok(Args { bridge, target, chip, image, load, hart_pcs, power_base })
}

fn parse_u64(s: &str) -> Result<u64> {
    let s = s.trim();
    Ok(match s.strip_prefix("0x") {
        Some(hex) => u64::from_str_radix(hex, 16)?,
        None => s.parse()?,
    })
}

// The PRCM of zaozi PR #159 orders its domains by name: cpu0, then cpu1, three words each (request, status,
// event). A status holds the requested mode code, then the done, invalid and fault bits.
const BANK: u64 = 12;
const STATUS: u64 = 4;
const OFF: u32 = 0;
const RUN: u32 = 1;
const DONE: u32 = 1 << 1;

fn wait_mode(bus: &mut impl MemoryInterface, base: u64, mode: u32) -> Result<()> {
    let deadline = Instant::now() + Duration::from_secs(5);
    loop {
        let cpu0 = bus.read_word_32(base + STATUS)?;
        let cpu1 = bus.read_word_32(base + BANK + STATUS)?;
        if cpu0 & 0xf != RUN | DONE {
            bail!("CPU0 must remain running: status={cpu0:#010x}");
        }
        if cpu1 & 0xf == mode | DONE {
            return Ok(());
        }
        if Instant::now() >= deadline {
            bail!("CPU1 did not reach mode {mode}: status={cpu1:#010x}");
        }
    }
}

fn power_cycle(session: &mut Session, base: u64) -> Result<()> {
    let mut bus = session.get_riscv_interface(0)?;
    let sbcs: u32 = bus.read_dm_register::<Sbcs>()?.into();
    if sbcs >> 29 != 1 || sbcs & (1 << 2) == 0 {
        bail!("the power demo requires 32-bit Debug Module system bus access");
    }
    bus.memory_access_config().set_region_override(
        RiscvBusAccess::A32, base..base + 2 * BANK, MemoryAccessMethod::SystemBus,
    );
    wait_mode(&mut bus, base, RUN)?;
    bus.write_word_32(base + BANK, OFF)?;
    wait_mode(&mut bus, base, OFF)?;
    println!("[simprobe] the PRCM sequenced CPU1 to Off; CPU0 keeps running");
    bus.write_word_32(base + BANK, RUN)?;
    wait_mode(&mut bus, base, RUN)?;
    Ok(())
}

fn power_demo(session: &mut Session, base: u64) -> Result<()> {
    power_cycle(session, base)?;
    // Back in Run the hart is out of reset and halted, as after a cold reset.
    if !session.core(1)?.status().context("reaching hart 1 after its power cycle")?.is_halted() {
        bail!("hart 1 is not halted out of its power-on reset");
    }
    println!("[simprobe] the PRCM brought CPU1 back to Run; hart 1 is out of reset and halted");
    Ok(())
}

fn main() -> Result<()> {
    let args = parse_args()?;

    let bytes = std::fs::read(&args.image).with_context(|| format!("reading {}", args.image))?;
    if bytes.len() % 4 != 0 {
        bail!("image {} is not a whole number of 32-bit words", args.image);
    }
    let words: Vec<u32> =
        bytes.chunks_exact(4).map(|w| u32::from_le_bytes([w[0], w[1], w[2], w[3]])).collect();

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

    {
        let mut core = session.core(0).context("selecting hart 0")?;
        core.halt(Duration::from_secs(10)).context("halting hart 0")?;
        core.write_32(args.load, &words).context("downloading the program")?;
        println!("[simprobe] wrote {} words at {:#010x}", words.len(), args.load);
    }

    // CPU1 goes through a power cycle before the program runs: nothing it holds survives Off.
    if let Some(base) = args.power_base {
        power_demo(&mut session, base)?;
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
