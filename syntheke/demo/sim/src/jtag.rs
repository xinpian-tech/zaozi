

use std::io::{ErrorKind, Read, Write};
use std::net::{Ipv4Addr, SocketAddrV4, TcpListener, TcpStream};
use std::os::raw::c_int;

const IDLE_POLL_PERIOD: u32 = 64;

#[derive(Default)]
struct Bridge {
    listener: Option<TcpListener>,
    debugger: Option<TcpStream>,
    batch:    Vec<u8>,
    position: usize,
    reply:      Vec<u8>,
    idle_polls: u32,
}

static mut BRIDGE: Option<Bridge> = None;

#[allow(static_mut_refs)]
fn bridge() -> &'static mut Bridge {
    unsafe { BRIDGE.get_or_insert_with(Bridge::default) }
}

impl Bridge {
    fn poll_batch(&mut self) {
        let Some(listener) = self.listener.as_ref() else { return };

        if self.debugger.is_none() {
            match listener.accept() {
                Ok((stream, _)) => {
                    let _ = stream.set_nodelay(true);
                    let _ = stream.set_nonblocking(true);
                    self.debugger = Some(stream);
                    eprintln!("[JtagDpi] debugger connected");
                }
                Err(_) => return,
            }
            return;
        }

        let stream = self.debugger.as_mut().expect("connected above");
        let mut header = [0u8; 4];
        match stream.peek(&mut header) {
            Ok(4) => (),
            Ok(0) => {
                eprintln!("[JtagDpi] debugger disconnected");
                self.drop_debugger();
                return;
            }
            Ok(_) => return,
            Err(e) if e.kind() == ErrorKind::WouldBlock => return,
            Err(_) => {
                self.drop_debugger();
                return;
            }
        }

        let count = u32::from_le_bytes(header) as usize;
        let mut batch = vec![0u8; 4 + count];
        if read_exact_blocking(stream, &mut batch).is_err() {
            self.drop_debugger();
            return;
        }
        batch.drain(..4);

        self.batch = batch;
        self.position = 0;
        self.reply.clear();
    }

    fn drop_debugger(&mut self) {
        self.debugger = None;
        self.batch.clear();
        self.position = 0;
        self.reply.clear();
    }

    fn answer(&mut self) {
        if let (Some(stream), false) = (self.debugger.as_mut(), self.reply.is_empty()) {
            let _ = write_all_blocking(stream, &self.reply);
        }
        self.batch.clear();
        self.position = 0;
        self.reply.clear();
    }
}

fn read_exact_blocking(stream: &mut TcpStream, buffer: &mut [u8]) -> std::io::Result<()> {
    let mut filled = 0;
    while filled < buffer.len() {
        match stream.read(&mut buffer[filled..]) {
            Ok(0) => return Err(std::io::Error::from(ErrorKind::UnexpectedEof)),
            Ok(n) => filled += n,
            Err(e) if e.kind() == ErrorKind::WouldBlock || e.kind() == ErrorKind::Interrupted => (),
            Err(e) => return Err(e),
        }
    }
    Ok(())
}

fn write_all_blocking(stream: &mut TcpStream, buffer: &[u8]) -> std::io::Result<()> {
    let mut sent = 0;
    while sent < buffer.len() {
        match stream.write(&buffer[sent..]) {
            Ok(0) => return Err(std::io::Error::from(ErrorKind::WriteZero)),
            Ok(n) => sent += n,
            Err(e) if e.kind() == ErrorKind::WouldBlock || e.kind() == ErrorKind::Interrupted => (),
            Err(e) => return Err(e),
        }
    }
    Ok(())
}

#[no_mangle]
pub extern "C" fn jtag_dpi_open(port: c_int) -> c_int {
    let address = SocketAddrV4::new(Ipv4Addr::LOCALHOST, port as u16);
    match TcpListener::bind(address) {
        Ok(listener) => {
            let _ = listener.set_nonblocking(true);
            bridge().listener = Some(listener);
            eprintln!("[JtagDpi] listening on {address}");
            0
        }
        Err(e) => {
            eprintln!("[JtagDpi] cannot listen on {address}: {e}");
            -1
        }
    }
}

#[no_mangle]
pub extern "C" fn jtag_dpi_step(tdo: c_int, tms: *mut c_int, tdi: *mut c_int) -> c_int {
    let bridge = bridge();
    unsafe {
        *tms = 0;
        *tdi = 0;
    }

    if bridge.position == bridge.batch.len() {
        if !bridge.batch.is_empty() {
            bridge.answer();
        }
        if bridge.idle_polls % IDLE_POLL_PERIOD != 0 {
            bridge.idle_polls += 1;
            return 0;
        }
        bridge.idle_polls += 1;
        bridge.poll_batch();
        if bridge.position == bridge.batch.len() {
            return 0;
        }
        bridge.idle_polls = 0;
    }

    let bit = bridge.batch[bridge.position];
    bridge.position += 1;
    unsafe {
        *tms = (bit & 1) as c_int;
        *tdi = ((bit >> 1) & 1) as c_int;
    }
    if bit & 4 != 0 {
        bridge.reply.push((tdo & 1) as u8);
    }
    1
}
