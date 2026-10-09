//! Mark outgoing audio as "voice" via the Windows qWAVE API so WMM-capable
//! routers put it in the AC_VO (lowest-latency) Wi-Fi queue.
//!
//! Windows only applies the DSCP marking for elevated processes or when a QoS
//! group policy allows it; failures are reported once and otherwise ignored.

use std::ffi::c_void;
use std::net::{SocketAddr, UdpSocket};
use std::os::windows::io::AsRawSocket;

use libloading::{Library, Symbol};

const QOS_TRAFFIC_TYPE_VOICE: i32 = 4;
const QOS_NON_ADAPTIVE_FLOW: u32 = 0x2;

#[repr(C)]
struct QosVersion {
    major: u16,
    minor: u16,
}

#[repr(C)]
struct SockaddrIn {
    family: u16,
    port_be: u16,
    addr: [u8; 4],
    zero: [u8; 8],
}

pub struct Qos {
    lib: Library,
    handle: *mut c_void,
    warned: bool,
}

unsafe impl Send for Qos {}

impl Qos {
    pub fn new() -> Option<Self> {
        unsafe {
            let lib = Library::new("qwave.dll").ok()?;
            let mut handle: *mut c_void = std::ptr::null_mut();
            let ok = {
                let create: Symbol<unsafe extern "system" fn(*const QosVersion, *mut *mut c_void) -> i32> =
                    lib.get(b"QOSCreateHandle\0").ok()?;
                create(&QosVersion { major: 1, minor: 0 }, &mut handle)
            };
            if ok == 0 {
                eprintln!("qos: QOSCreateHandle failed; packets will not be DSCP-marked");
                return None;
            }
            Some(Qos { lib, handle, warned: false })
        }
    }

    pub fn add_destination(&mut self, socket: &UdpSocket, dest: SocketAddr) {
        let SocketAddr::V4(v4) = dest else { return };
        let sa = SockaddrIn {
            family: 2,
            port_be: v4.port().to_be(),
            addr: v4.ip().octets(),
            zero: [0; 8],
        };
        let mut flow_id: u32 = 0;
        let ok = unsafe {
            let Ok(add): Result<
                Symbol<unsafe extern "system" fn(*mut c_void, usize, *const SockaddrIn, i32, u32, *mut u32) -> i32>,
                _,
            > = self.lib.get(b"QOSAddSocketToFlow\0") else {
                return;
            };
            add(
                self.handle,
                socket.as_raw_socket() as usize,
                &sa,
                QOS_TRAFFIC_TYPE_VOICE,
                QOS_NON_ADAPTIVE_FLOW,
                &mut flow_id,
            )
        };
        if ok == 0 && !self.warned {
            self.warned = true;
            eprintln!(
                "qos: QOSAddSocketToFlow failed ({}); audio goes out unmarked",
                std::io::Error::last_os_error()
            );
        }
    }
}
