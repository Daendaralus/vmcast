//! Opus encoding for lossy links (mobile / WireGuard).
//!
//! Music at useful bitrates runs in Opus' CELT mode, where the built-in FEC does
//! nothing, so loss protection is plain redundancy: every packet also carries
//! the previous `redundancy` encoded frames. A single lost packet is then
//! recovered from the next one, at the cost of (1 + redundancy) x bitrate.

use std::collections::VecDeque;

use opus::{Application, Bitrate, Channels, Encoder};

pub const OPUS_RATE: u32 = 48_000;
const MAX_FRAME_BYTES: usize = 1275; // largest possible Opus frame

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct OpusConfig {
    pub bitrate_kbps: u16,
    pub frame_samples: usize, // per channel at 48 kHz: 120, 240, 480 or 960
    pub redundancy: usize,
}

impl OpusConfig {
    /// From the HELLO extension: bitrate in kbps, frame duration in half
    /// milliseconds, redundancy count. Out-of-range values are clamped.
    pub fn from_hello(kbps: u16, frame_half_ms: u8, redundancy: u8) -> Self {
        let frame_samples = match frame_half_ms {
            0..=5 => 120,
            6..=10 => 240,
            11..=20 => 480,
            _ => 960,
        };
        OpusConfig {
            bitrate_kbps: kbps.clamp(16, 256),
            frame_samples,
            redundancy: (redundancy as usize).min(3),
        }
    }
}

pub struct OpusPipe {
    pub config: OpusConfig,
    encoder: Encoder,
    pending: Vec<i16>,
    pending_pos: Option<u32>,
    history: VecDeque<Vec<u8>>, // newest first
    scratch: Vec<u8>,
    pub seq: u32,
}

/// One encoded frame ready to send: frame position of the newest frame plus the
/// frames (newest first) to put in the packet.
pub struct OpusPacket<'a> {
    pub pos: u32,
    pub frames: &'a VecDeque<Vec<u8>>,
}

impl OpusPipe {
    pub fn new(config: OpusConfig) -> Result<Self, opus::Error> {
        // LowDelay = OPUS_APPLICATION_RESTRICTED_LOWDELAY: CELT only, ~2.5 ms
        // less algorithmic delay than the default application mode.
        let mut encoder = Encoder::new(OPUS_RATE, Channels::Stereo, Application::LowDelay)?;
        encoder.set_bitrate(Bitrate::Bits(config.bitrate_kbps as i32 * 1000))?;
        Ok(OpusPipe {
            config,
            encoder,
            pending: Vec::with_capacity(config.frame_samples * 2),
            pending_pos: None,
            history: VecDeque::with_capacity(config.redundancy + 1),
            scratch: vec![0; MAX_FRAME_BYTES],
            seq: 0,
        })
    }

    /// Feed interleaved stereo samples starting at frame position `pos`; calls
    /// `emit` for every completed Opus frame.
    pub fn push(&mut self, mut samples: &[i16], mut pos: u32, mut emit: impl FnMut(&OpusPacket, u32)) {
        let frame_len = self.config.frame_samples * 2;
        while !samples.is_empty() {
            if self.pending_pos.is_none() {
                self.pending_pos = Some(pos);
            }
            let take = (frame_len - self.pending.len()).min(samples.len());
            self.pending.extend_from_slice(&samples[..take]);
            samples = &samples[take..];
            pos = pos.wrapping_add((take / 2) as u32);
            if self.pending.len() < frame_len {
                break;
            }
            let frame_pos = self.pending_pos.take().unwrap();
            match self.encoder.encode(&self.pending, &mut self.scratch) {
                Ok(n) => {
                    if self.history.len() > self.config.redundancy {
                        self.history.pop_back();
                    }
                    self.history.push_front(self.scratch[..n].to_vec());
                    emit(&OpusPacket { pos: frame_pos, frames: &self.history }, self.seq);
                    self.seq = self.seq.wrapping_add(1);
                }
                Err(e) => eprintln!("opus encode: {e}"),
            }
            self.pending.clear();
        }
    }
}
