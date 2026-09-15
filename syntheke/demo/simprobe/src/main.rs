

mod probe;

use std::time::{Duration, Instant};

use anyhow::{Context, Result, bail};
use probe_rs::architecture::riscv::communication_interface::{
    MemoryAccessMethod, RiscvBusAccess, Sbcs,
};
use probe_rs::config::Registry;
use probe_rs::probe::Probe;
use probe_rs::{Core, MemoryInterface, Permissions, RegisterId, Session};

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

fn retained_state(core: &mut Core<'_>) -> Result<Vec<(u16, u32)>> {
    (0x1001..=0x100f)
        .chain([0x300, 0x304, 0x305, 0x340, 0x341, 0x342, 0x343, 0x7b0, 0x7b1])
        .map(|address| {
            let value: u32 = core.read_core_reg(RegisterId(address))
                .with_context(|| format!("reading hart 1 register {address:#06x}"))?;
            Ok((address, value))
        })
        .collect()
}

fn wait_power(bus: &mut impl MemoryInterface, base: u64, expected: u32) -> Result<()> {
    let deadline = Instant::now() + Duration::from_secs(30);
    loop {
        let cpu0 = bus.read_word_32(base + 4)?;
        let cpu1 = bus.read_word_32(base + 8)?;
        if cpu0 & 0xf != 0x5 {
            bail!("CPU0 must remain powered: status={cpu0:#010x}");
        }
        if cpu1 & 0xf == expected {
            return Ok(());
        }
        if Instant::now() >= deadline {
            bail!("CPU1 power transition timed out: status={cpu1:#010x}, expected={expected:#x}");
        }
    }
}

fn power_cycle(session: &mut Session, base: u64) -> Result<()> {
    let mut bus = session.get_riscv_interface(0)?;
    let sbcs: u32 = bus.read_dm_register::<Sbcs>()?.into();
    if sbcs >> 29 != 1 || sbcs & (1 << 2) == 0 {
        bail!("retention demo requires 32-bit Debug Module system bus access");
    }
    bus.memory_access_config().set_region_override(
        RiscvBusAccess::A32, base..base + 12, MemoryAccessMethod::SystemBus,
    );
    wait_power(&mut bus, base, 0x5)?;
    bus.write_word_32(base, 0x1)?;
    wait_power(&mut bus, base, 0x8)?;
    println!("[simprobe] CPU1 supply off and isolated; CPU0 supply remains on");
    bus.write_word_32(base, 0x3)?;
    wait_power(&mut bus, base, 0x5)?;
    Ok(())
}

fn retention_demo(session: &mut Session, base: u64) -> Result<()> {
    let saved = {
        let mut core = session.core(1)?;
        core.halt(Duration::from_secs(10)).context("halting hart 1 before retention")?;
        retained_state(&mut core)?
    };

    power_cycle(session, base)?;

    {
        let mut core = session.core(1)?;
        if !core.status()?.is_halted() {
            bail!("hart 1 did not preserve its halted state across power loss");
        }
        let restored = retained_state(&mut core)?;
        for ((address, before), (_, after)) in saved.iter().zip(&restored) {
            if before != after {
                bail!("hart 1 register {address:#06x} lost retention: {before:#010x} -> {after:#010x}");
            }
        }
        let dpc = restored.last().unwrap().1;
        println!("[simprobe] hart 1 retained 15 GPRs, 7 machine CSRs and DPC/DCSR; DPC={dpc:#010x}");
        core.run().context("resuming hart 1 after retained-state readback")?;
        if !core.status()?.is_running() {
            bail!("hart 1 did not resume before the running-state power cycle");
        }
    }

    power_cycle(session, base)?;

    if !session.core(1)?.status()?.is_running() {
        bail!("hart 1 did not automatically resume after its running-state power cycle");
    }
    println!("[simprobe] hart 1 automatically resumed after retention without a debugger resume request");
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

    for (hart, pc) in &args.hart_pcs {
        let mut core = session.core(*hart).with_context(|| format!("selecting hart {hart}"))?;
        core.halt(Duration::from_secs(10)).with_context(|| format!("halting hart {hart}"))?;
        let program_counter = core.program_counter();
        core.write_core_reg(program_counter, *pc).with_context(|| format!("setting hart {hart} pc"))?;
        core.run().with_context(|| format!("resuming hart {hart}"))?;
        println!("[simprobe] hart {hart} runs from {pc:#010x}");
    }

    if let Some(base) = args.power_base {
        retention_demo(&mut session, base)?;
    }

    Ok(())
}
