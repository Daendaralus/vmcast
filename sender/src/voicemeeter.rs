//! Minimal binding to VoicemeeterRemote64.dll: login + bus-output insert callback.
//!
//! The callback runs on Voicemeeter's time-critical audio thread, so it only
//! copies the insert through, converts the selected bus to i16 and pushes it into
//! a lock-free ring buffer. Waking the network thread uses `Thread::unpark`,
//! which on Windows is a non-blocking WakeByAddressSingle.

use std::cell::UnsafeCell;
use std::ffi::c_void;
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering};
use std::thread::Thread;

use libloading::{Library, Symbol};

pub const DEFAULT_DLL: &str = r"C:\Program Files (x86)\VB\Voicemeeter\VoicemeeterRemote64.dll";

const CB_STARTING: i32 = 1;
const CB_ENDING: i32 = 2;
const CB_CHANGE: i32 = 3;
const CB_BUFFER_OUT: i32 = 11;
const AUDIOCALLBACK_OUT: i32 = 0x2;

#[repr(C)]
struct AudioInfo {
    samplerate: i32,
    nb_sample_per_frame: i32,
}

#[repr(C)]
struct AudioBuffer {
    sr: i32,
    nbs: i32,
    nbi: i32,
    nbo: i32,
    r: [*mut f32; 128],
    w: [*mut f32; 128],
}

type AudioCallback = unsafe extern "system" fn(*mut c_void, i32, *mut c_void, i32) -> i32;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Flavor {
    Basic,
    Banana,
    Potato,
}

impl Flavor {
    /// First channel of `bus` ("A1".."A5", "B1".."B3") in the BUFFER_OUT layout.
    pub fn bus_channel(self, bus: &str) -> Option<usize> {
        let bus = bus.to_ascii_uppercase();
        let (kind, n) = bus.split_at(1);
        let n: usize = n.parse().ok().filter(|&n| n >= 1)?;
        let (a_count, b_count) = match self {
            Flavor::Basic => (1, 1),
            Flavor::Banana => (3, 2),
            Flavor::Potato => (5, 3),
        };
        match kind {
            // Basic exposes a single physical bus labelled "A1 / A2".
            "A" if self == Flavor::Basic && n <= 2 => Some(0),
            "A" if n <= a_count => Some((n - 1) * 8),
            "B" if n <= b_count => Some((a_count + n - 1) * 8),
            _ => None,
        }
    }
}

pub struct Shared {
    producer: UnsafeCell<rtrb::Producer<i16>>,
    ch_left: usize,
    waker: Thread,
    pub sample_rate: AtomicU32,
    pub block_frames: AtomicU32,
    pub restart_requested: AtomicBool,
    pub overruns: AtomicU64,
    pub frames_in: AtomicU64,
}

// The producer is only touched from the (non re-entrant) audio callback.
unsafe impl Sync for Shared {}

impl Shared {
    pub fn new(producer: rtrb::Producer<i16>, ch_left: usize, waker: Thread) -> Self {
        Shared {
            producer: UnsafeCell::new(producer),
            ch_left,
            waker,
            sample_rate: AtomicU32::new(0),
            block_frames: AtomicU32::new(0),
            restart_requested: AtomicBool::new(false),
            overruns: AtomicU64::new(0),
            frames_in: AtomicU64::new(0),
        }
    }
}

unsafe extern "system" fn audio_callback(user: *mut c_void, cmd: i32, data: *mut c_void, _nnn: i32) -> i32 {
    let shared = &*(user as *const Shared);
    match cmd {
        CB_STARTING => {
            let info = &*(data as *const AudioInfo);
            shared.sample_rate.store(info.samplerate as u32, Ordering::Relaxed);
            shared.block_frames.store(info.nb_sample_per_frame as u32, Ordering::Relaxed);
        }
        CB_CHANGE => shared.restart_requested.store(true, Ordering::Relaxed),
        CB_ENDING => {}
        CB_BUFFER_OUT => {
            let buf = &*(data as *const AudioBuffer);
            let n = buf.nbs.max(0) as usize;
            // Insert semantics: whatever we leave in `w` is what Voicemeeter outputs.
            for c in 0..(buf.nbi.min(buf.nbo).clamp(0, 128) as usize) {
                let (r, w) = (buf.r[c], buf.w[c]);
                if !r.is_null() && !w.is_null() && r != w {
                    std::ptr::copy_nonoverlapping(r, w, n);
                }
            }
            let (cl, cr) = (shared.ch_left, shared.ch_left + 1);
            if cr >= buf.nbi as usize || buf.r[cl].is_null() || buf.r[cr].is_null() {
                return 0;
            }
            let left = std::slice::from_raw_parts(buf.r[cl], n);
            let right = std::slice::from_raw_parts(buf.r[cr], n);
            let producer = &mut *shared.producer.get();
            match producer.write_chunk_uninit(n * 2) {
                Ok(chunk) => {
                    let to_i16 = |x: f32| (x.clamp(-1.0, 1.0) * 32767.0) as i16;
                    chunk.fill_from_iter(left.iter().zip(right).flat_map(|(&l, &r)| [to_i16(l), to_i16(r)]));
                    shared.frames_in.fetch_add(n as u64, Ordering::Relaxed);
                }
                Err(_) => {
                    shared.overruns.fetch_add(1, Ordering::Relaxed);
                }
            }
            shared.waker.unpark();
        }
        _ => {}
    }
    0
}

pub struct Remote {
    lib: Library,
    registered: bool,
}

impl Remote {
    pub fn load(path: &str) -> Result<Self, String> {
        let lib = unsafe { Library::new(path) }.map_err(|e| format!("loading {path}: {e}"))?;
        let remote = Remote { lib, registered: false };
        let rc = remote.call0(b"VBVMR_Login\0")?;
        if rc < 0 {
            return Err(format!("VBVMR_Login failed ({rc})"));
        }
        if rc == 1 {
            log!("Voicemeeter is not running yet; waiting for it...");
        }
        Ok(remote)
    }

    fn call0(&self, name: &[u8]) -> Result<i32, String> {
        unsafe {
            let f: Symbol<unsafe extern "system" fn() -> i32> =
                self.lib.get(name).map_err(|e| format!("{}: {e}", String::from_utf8_lossy(name)))?;
            Ok(f())
        }
    }

    pub fn flavor(&self) -> Result<Flavor, String> {
        let mut t: i32 = 0;
        let rc = unsafe {
            let f: Symbol<unsafe extern "system" fn(*mut i32) -> i32> =
                self.lib.get(b"VBVMR_GetVoicemeeterType\0").map_err(|e| e.to_string())?;
            f(&mut t)
        };
        match (rc, t) {
            (0, 1) => Ok(Flavor::Basic),
            (0, 2) => Ok(Flavor::Banana),
            (0, 3) => Ok(Flavor::Potato),
            _ => Err(format!("GetVoicemeeterType rc={rc} type={t}")),
        }
    }

    /// `shared` must outlive the registration (we leak it in main).
    pub fn register(&mut self, shared: &'static Shared) -> Result<(), String> {
        let mut name = [0u8; 64];
        name[..6].copy_from_slice(b"vmcast");
        let rc = unsafe {
            let f: Symbol<unsafe extern "system" fn(i32, AudioCallback, *mut c_void, *mut u8) -> i32> =
                self.lib.get(b"VBVMR_AudioCallbackRegister\0").map_err(|e| e.to_string())?;
            f(AUDIOCALLBACK_OUT, audio_callback, shared as *const Shared as *mut c_void, name.as_mut_ptr())
        };
        match rc {
            0 => {
                self.registered = true;
                Ok(())
            }
            1 => {
                let end = name.iter().position(|&b| b == 0).unwrap_or(64);
                Err(format!(
                    "bus insert callback already taken by '{}'",
                    String::from_utf8_lossy(&name[..end])
                ))
            }
            _ => Err(format!("VBVMR_AudioCallbackRegister failed ({rc})")),
        }
    }

    pub fn start(&self) -> Result<(), String> {
        match self.call0(b"VBVMR_AudioCallbackStart\0")? {
            0 => Ok(()),
            rc => Err(format!("VBVMR_AudioCallbackStart failed ({rc})")),
        }
    }

    pub fn unregister(&mut self) {
        if self.registered {
            let _ = self.call0(b"VBVMR_AudioCallbackUnregister\0");
            self.registered = false;
        }
    }

    pub fn stop(&self) {
        let _ = self.call0(b"VBVMR_AudioCallbackStop\0");
    }
}

impl Drop for Remote {
    fn drop(&mut self) {
        if self.registered {
            let _ = self.call0(b"VBVMR_AudioCallbackUnregister\0");
        }
        let _ = self.call0(b"VBVMR_Logout\0");
    }
}
