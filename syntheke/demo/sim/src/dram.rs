// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Jiuyang Liu <liu@jiuyang.me>

//! The simulation side of the memory port: every access goes to Ramulator for its timing and to the store for its
//! data. Ramulator models when an access finishes, not what it returns — a memory simulator is not a memory — so the
//! bytes live here and a completion is what releases them.

use std::collections::HashMap;
use std::ffi::{c_char, c_int, CStr};

#[allow(non_camel_case_types, non_upper_case_globals, dead_code)]
mod capi {
    include!(concat!(env!("OUT_DIR"), "/ramulator.rs"));
}

/// One beat, the granularity of both the store and a request.
const BEAT: usize = 16;
/// Every request carries a tag back; these two are enough, since the port holds one of each at a time.
const READ_TAG: u64 = 0;
const WRITE_TAG: u64 = 1;

struct Dram {
    ramulator: *mut capi::ramulator_t,
    /// Where the fabric decodes this memory, so Ramulator sees an offset into the device it models.
    base: u64,
    /// How many DRAM cycles pass in one bus clock, from Ramulator's own tCK and the port's settled period.
    ticks_per_clock: u32,
    store: HashMap<u64, [u8; BEAT]>,
    write_busy: bool,
    writes_done: i32,
    read_busy: bool,
    read_ready: bool,
    read_address: u64,
}

static mut DRAM: Option<Dram> = None;

/// The simulation is single-threaded and calls these from its one process; the state is this module's alone.
#[allow(static_mut_refs)]
fn dram() -> &'static mut Dram {
    unsafe { DRAM.as_mut().expect("dram_dpi_open was not called, or it failed") }
}

/// Bring the model up from an exported Ramulator configuration. Returns how many DRAM cycles make one bus clock —
/// derived, not stated: Ramulator's tCK against the period of the port it answers. -1 if it cannot be opened.
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

/// One bus clock: the DRAM's own runs at whatever ratio its timing implies.
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

/// Offer a write beat. Returns 0 when the model cannot take it yet, in which case the beat stands and is offered
/// again next cycle.
#[no_mangle]
pub extern "C" fn dram_dpi_write(address: i64, d0: c_int, d1: c_int, d2: c_int, d3: c_int, strobe: c_int) -> c_int {
    let dram = dram();
    if dram.write_busy {
        return 0;
    }

    let beat = (address as u64) & !(BEAT as u64 - 1);
    // The data is ours the moment the beat arrives; Ramulator only says when the access is over.
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

/// How many writes have finished since the last call.
#[no_mangle]
pub extern "C" fn dram_dpi_write_done() -> c_int {
    let dram = dram();
    let done = dram.writes_done;
    dram.writes_done = 0;
    done
}

/// Offer a read. Returns 0 when the model cannot take it yet.
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

/// Take a finished read's data. Returns 0 when none is ready.
#[no_mangle]
pub extern "C" fn dram_dpi_read_done(d0: *mut c_int, d1: *mut c_int, d2: *mut c_int, d3: *mut c_int) -> c_int {
    let dram = dram();
    if !dram.read_ready {
        return 0;
    }
    dram.read_ready = false;

    // A block nobody has written reads as zero, the way a memory nobody has loaded does.
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
