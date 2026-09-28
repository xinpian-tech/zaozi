

use std::collections::HashMap;
use std::ffi::{c_char, c_int, CStr};

#[allow(non_camel_case_types, non_upper_case_globals, dead_code)]
mod capi {
    include!(concat!(env!("OUT_DIR"), "/ramulator.rs"));
}

const BEAT: usize = 16;
const READ_TAG: u64 = 0;
const WRITE_TAG: u64 = 1;

struct Dram {
    ramulator: *mut capi::ramulator_t,
    base: u64,
    ticks_per_clock: u32,
    store: HashMap<u64, [u8; BEAT]>,
    write_busy: bool,
    writes_done: i32,
    read_busy: bool,
    read_ready: bool,
    read_address: u64,
}

static mut DRAM: Option<Dram> = None;

#[allow(static_mut_refs)]
fn dram() -> &'static mut Dram {
    unsafe { DRAM.as_mut().expect("dram_dpi_open was not called, or it failed") }
}

#[no_mangle]
pub extern "C" fn dram_dpi_open(config: *const c_char, base: i64, period_ps: i64) -> c_int {
    let path = unsafe { CStr::from_ptr(config) };
    let ramulator = unsafe { capi::ramulator_open(path.as_ptr()) };
    if ramulator.is_null() {
        return -1;
    }

    let tck_ns = unsafe { capi::ramulator_tck_ns(ramulator) };
    let ticks_per_clock = if tck_ns > 0.0 {
        ((period_ps as f64) / (tck_ns * 1000.0)).round().max(1.0) as u32
    } else {
        1
    };
    let tx_bytes = unsafe { capi::ramulator_tx_bytes(ramulator) };
    eprintln!(
        "[DramDpi] {} at {:#x}: tCK {tck_ns:.3} ns, {ticks_per_clock} DRAM cycles per bus clock, \
         {tx_bytes} bytes per transaction",
        path.to_string_lossy(),
        base,
    );

    unsafe {
        DRAM = Some(Dram {
            ramulator,
            base: base as u64,
            ticks_per_clock,
            store: HashMap::new(),
            write_busy: false,
            writes_done: 0,
            read_busy: false,
            read_ready: false,
            read_address: 0,
        });
    }
    ticks_per_clock as c_int
}

#[no_mangle]
pub extern "C" fn dram_dpi_tick() {
    let dram = dram();
    for _ in 0..dram.ticks_per_clock {
        unsafe { capi::ramulator_tick(dram.ramulator) };
    }
    let mut tag = 0u64;
    while unsafe { capi::ramulator_poll(dram.ramulator, &mut tag) } != 0 {
        match tag {
            WRITE_TAG => {
                dram.write_busy = false;
                dram.writes_done += 1;
            }
            READ_TAG => {
                dram.read_busy = false;
                dram.read_ready = true;
            }
            _ => unreachable!("the model tags requests with READ_TAG or WRITE_TAG"),
        }
    }
}

#[no_mangle]
pub extern "C" fn dram_dpi_write(address: i64, d0: c_int, d1: c_int, d2: c_int, d3: c_int, strobe: c_int) -> c_int {
    let dram = dram();
    if dram.write_busy {
        return 0;
    }

    let beat = (address as u64) & !(BEAT as u64 - 1);
    let words = [d0 as u32, d1 as u32, d2 as u32, d3 as u32];
    let block = dram.store.entry(beat).or_insert([0u8; BEAT]);
    for byte in 0..BEAT {
        if strobe & (1 << byte) != 0 {
            block[byte] = (words[byte / 4] >> (8 * (byte % 4))) as u8;
        }
    }

    let sent = unsafe {
        capi::ramulator_send(dram.ramulator, 1, beat - dram.base, BEAT as i32, WRITE_TAG)
    };
    if sent != 0 {
        dram.write_busy = true;
    }
    sent
}

#[no_mangle]
pub extern "C" fn dram_dpi_write_done() -> c_int {
    let dram = dram();
    let done = dram.writes_done;
    dram.writes_done = 0;
    done
}

#[no_mangle]
pub extern "C" fn dram_dpi_read(address: i64) -> c_int {
    let dram = dram();
    if dram.read_busy || dram.read_ready {
        return 0;
    }

    dram.read_address = (address as u64) & !(BEAT as u64 - 1);
    let sent = unsafe {
        capi::ramulator_send(dram.ramulator, 0, dram.read_address - dram.base, BEAT as i32, READ_TAG)
    };
    if sent != 0 {
        dram.read_busy = true;
    }
    sent
}

#[no_mangle]
pub extern "C" fn dram_dpi_read_done(d0: *mut c_int, d1: *mut c_int, d2: *mut c_int, d3: *mut c_int) -> c_int {
    let dram = dram();
    if !dram.read_ready {
        return 0;
    }
    dram.read_ready = false;

    let block = dram.store.get(&dram.read_address).copied().unwrap_or([0u8; BEAT]);
    let mut words = [0u32; 4];
    for byte in 0..BEAT {
        words[byte / 4] |= (block[byte] as u32) << (8 * (byte % 4));
    }
    unsafe {
        *d0 = words[0] as c_int;
        *d1 = words[1] as c_int;
        *d2 = words[2] as c_int;
        *d3 = words[3] as c_int;
    }
    1
}
