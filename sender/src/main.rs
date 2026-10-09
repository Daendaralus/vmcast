mod opus_pipe;
mod qos;
mod voicemeeter;

use std::net::{SocketAddr, UdpSocket};
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use opus_pipe::{OpusConfig, OpusPipe, OPUS_RATE};
use voicemeeter::{Remote, Shared};

const MAGIC: &[u8; 2] = b"VC";
const T_HELLO: u8 = 1;
const T_AUDIO: u8 = 2;
const T_PONG: u8 = 3;
const T_BYE: u8 = 4;
const CODEC_PCM16: u8 = 0;
const CODEC_OPUS: u8 = 1;
const HEADER_LEN: usize = 24;
const CLIENT_TIMEOUT: Duration = Duration::from_secs(3);

static STOP: AtomicBool = AtomicBool::new(false);
static STREAM_ID: AtomicU32 = AtomicU32::new(0);
static SHARED: OnceLock<&'static Shared> = OnceLock::new();

struct Args {
    port: u16,
    bus: String,
    max_frames: usize,
    dll: String,
    qos: bool,
}

fn parse_args() -> Args {
    let mut a = Args {
        port: 6990,
        bus: "A1".into(),
        max_frames: 240,
        dll: voicemeeter::DEFAULT_DLL.into(),
        qos: true,
    };
    let mut it = std::env::args().skip(1);
    while let Some(arg) = it.next() {
        let mut val = || it.next().unwrap_or_else(|| usage(&format!("{arg} needs a value")));
        match arg.as_str() {
            "--port" => a.port = val().parse().unwrap_or_else(|_| usage("bad --port")),
            "--bus" => a.bus = val(),
            "--max-frames" => a.max_frames = val().parse().unwrap_or_else(|_| usage("bad --max-frames")),
            "--dll" => a.dll = val(),
            "--no-qos" => a.qos = false,
            "-h" | "--help" => usage(""),
            other => usage(&format!("unknown argument {other}")),
        }
    }
    a.max_frames = a.max_frames.clamp(16, 300); // 300 stereo s16 frames = 1200 B payload, fits a WireGuard MTU
    a
}

fn usage(err: &str) -> ! {
    if !err.is_empty() {
        eprintln!("error: {err}\n");
    }
    eprintln!(
        "vmcast - stream a Voicemeeter bus to the vmcast Android app\n\n\
         usage: vmcast [--bus A1|A2..A5|B1..B3] [--port 6990] [--max-frames 240] [--dll PATH] [--no-qos]"
    );
    std::process::exit(if err.is_empty() { 0 } else { 2 })
}

fn new_stream_id() -> u32 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.subsec_nanos() ^ d.as_secs() as u32).unwrap_or(1)
}

struct Client {
    addr: SocketAddr,
    id: u32,
    last_seen: Instant,
    /// None = raw PCM.
    opus: Option<OpusConfig>,
}

type Clients = Arc<Mutex<Vec<Client>>>;

fn main() {
    let args = parse_args();
    ctrlc::set_handler(|| STOP.store(true, Ordering::SeqCst)).expect("ctrl-c handler");

    let mut remote = Remote::load(&args.dll).unwrap_or_else(|e| fatal(&e));
    let flavor = loop {
        match remote.flavor() {
            Ok(f) => break f,
            Err(_) if !STOP.load(Ordering::SeqCst) => thread::sleep(Duration::from_secs(1)),
            Err(e) => fatal(&e),
        }
    };
    let ch_left = flavor
        .bus_channel(&args.bus)
        .unwrap_or_else(|| fatal(&format!("bus {} does not exist on Voicemeeter {flavor:?}", args.bus)));

    let socket = UdpSocket::bind(("0.0.0.0", args.port)).unwrap_or_else(|e| fatal(&format!("bind :{}: {e}", args.port)));
    let clients: Clients = Arc::new(Mutex::new(Vec::new()));

    // ~0.5 s of stereo audio at 96 kHz; normally it holds less than one Voicemeeter block.
    let (producer, consumer) = rtrb::RingBuffer::<i16>::new(96_000);
    STREAM_ID.store(new_stream_id(), Ordering::Relaxed);

    let net = {
        let socket = socket.try_clone().expect("socket clone");
        let clients = clients.clone();
        let max_frames = args.max_frames;
        thread::Builder::new()
            .name("vmcast-send".into())
            .spawn(move || send_loop(consumer, socket, clients, max_frames))
            .expect("spawn sender")
    };

    let shared: &'static Shared = Box::leak(Box::new(Shared::new(producer, ch_left, net.thread().clone())));
    let _ = SHARED.set(shared);

    {
        let socket = socket.try_clone().expect("socket clone");
        let clients = clients.clone();
        let qos = if args.qos { qos::Qos::new() } else { None };
        thread::Builder::new()
            .name("vmcast-recv".into())
            .spawn(move || recv_loop(socket, clients, qos))
            .expect("spawn receiver");
    }

    remote.register(shared).unwrap_or_else(|e| fatal(&e));
    remote.start().unwrap_or_else(|e| fatal(&e));
    println!(
        "vmcast: Voicemeeter {flavor:?}, bus {} (channels {}-{}), listening on UDP {}",
        args.bus.to_ascii_uppercase(),
        ch_left,
        ch_left + 1,
        args.port
    );

    let mut last_stats = Instant::now();
    let mut last_frames = 0u64;
    while !STOP.load(Ordering::SeqCst) {
        thread::sleep(Duration::from_millis(100));
        if shared.restart_requested.swap(false, Ordering::Relaxed) {
            println!("audio stream changed, restarting callback");
            remote.stop();
            STREAM_ID.store(new_stream_id(), Ordering::Relaxed);
            if let Err(e) = remote.start() {
                eprintln!("{e}");
            }
        }
        if last_stats.elapsed() >= Duration::from_secs(5) {
            let frames = shared.frames_in.load(Ordering::Relaxed);
            let secs = last_stats.elapsed().as_secs_f64();
            let sr = shared.sample_rate.load(Ordering::Relaxed);
            let block = shared.block_frames.load(Ordering::Relaxed);
            let n_clients = clients.lock().unwrap().len();
            println!(
                "{sr} Hz, block {block} frames ({:.1} ms), {:.0} frames/s in, {n_clients} client(s), {} overruns",
                if sr > 0 { block as f64 * 1000.0 / sr as f64 } else { 0.0 },
                (frames - last_frames) as f64 / secs,
                shared.overruns.load(Ordering::Relaxed),
            );
            last_frames = frames;
            last_stats = Instant::now();
        }
    }

    println!("stopping");
    remote.stop();
    drop(remote); // unregisters the callback and logs out
}

fn fatal(msg: &str) -> ! {
    eprintln!("vmcast: {msg}");
    std::process::exit(1)
}

fn send_loop(mut consumer: rtrb::Consumer<i16>, socket: UdpSocket, clients: Clients, max_frames: usize) {
    let mut samples: Vec<i16> = Vec::with_capacity(96_000);
    let mut packet: Vec<u8> = Vec::with_capacity(HEADER_LEN + max_frames * 4);
    let mut targets: Vec<SocketAddr> = Vec::new();
    let mut opus_targets: Vec<SocketAddr> = Vec::new();
    let mut opus: Option<OpusPipe> = None;
    let mut opus_stream: u32 = 0;
    let mut opus_rate_warned = false;
    let mut seq: u32 = 0;
    let mut pos: u32 = 0;

    while !STOP.load(Ordering::Relaxed) {
        thread::park_timeout(Duration::from_millis(50));
        let avail = consumer.slots() & !1;
        if avail == 0 {
            continue;
        }
        let chunk = consumer.read_chunk(avail).expect("slots checked");
        let (a, b) = chunk.as_slices();
        samples.clear();
        samples.extend_from_slice(a);
        samples.extend_from_slice(b);
        chunk.commit_all();

        targets.clear();
        opus_targets.clear();
        // One Opus encoder at a time: the most recently seen Opus client decides its settings.
        let mut wanted_opus: Option<(Instant, OpusConfig)> = None;
        {
            let mut list = clients.lock().unwrap();
            list.retain(|c| c.last_seen.elapsed() < CLIENT_TIMEOUT);
            for c in list.iter() {
                match c.opus {
                    None => targets.push(c.addr),
                    Some(cfg) => {
                        opus_targets.push(c.addr);
                        if wanted_opus.map_or(true, |(t, _)| c.last_seen > t) {
                            wanted_opus = Some((c.last_seen, cfg));
                        }
                    }
                }
            }
        }
        let sample_rate = SHARED.get().map_or(0, |s| s.sample_rate.load(Ordering::Relaxed));
        let stream_id = STREAM_ID.load(Ordering::Relaxed);

        match wanted_opus.map(|(_, cfg)| cfg) {
            Some(cfg) if sample_rate == OPUS_RATE => {
                let stale = opus.as_ref().map_or(true, |p| p.config != cfg) || opus_stream != stream_id;
                if stale {
                    match OpusPipe::new(cfg) {
                        Ok(p) => {
                            println!(
                                "opus: {} kbps, {:.1} ms frames, redundancy {}",
                                cfg.bitrate_kbps,
                                cfg.frame_samples as f64 / 48.0,
                                cfg.redundancy
                            );
                            opus = Some(p);
                        }
                        Err(e) => eprintln!("opus encoder: {e}"),
                    }
                    opus_stream = stream_id;
                }
            }
            Some(_) => {
                if !opus_rate_warned {
                    eprintln!("opus needs Voicemeeter at 48 kHz (running at {sample_rate} Hz); Opus clients get nothing");
                    opus_rate_warned = true;
                }
                opus = None;
            }
            None => opus = None,
        }
        if let Some(pipe) = opus.as_mut() {
            let frame = pipe.config.frame_samples;
            pipe.push(&samples, pos, |pkt, pseq| {
                packet.clear();
                packet.extend_from_slice(MAGIC);
                packet.extend_from_slice(&[T_AUDIO, CODEC_OPUS, 2, 0]);
                packet.extend_from_slice(&(frame as u16).to_le_bytes());
                packet.extend_from_slice(&pseq.to_le_bytes());
                packet.extend_from_slice(&pkt.pos.to_le_bytes());
                packet.extend_from_slice(&OPUS_RATE.to_le_bytes());
                packet.extend_from_slice(&stream_id.to_le_bytes());
                packet.push(pkt.frames.len() as u8);
                for f in pkt.frames {
                    packet.extend_from_slice(&(f.len() as u16).to_le_bytes());
                    packet.extend_from_slice(f);
                }
                for t in &opus_targets {
                    let _ = socket.send_to(&packet, t);
                }
            });
        }

        for frames in samples.chunks(max_frames * 2) {
            let n = frames.len() / 2;
            if !targets.is_empty() {
                packet.clear();
                packet.extend_from_slice(MAGIC);
                packet.extend_from_slice(&[T_AUDIO, CODEC_PCM16, 2, 0]);
                packet.extend_from_slice(&(n as u16).to_le_bytes());
                packet.extend_from_slice(&seq.to_le_bytes());
                packet.extend_from_slice(&pos.to_le_bytes());
                packet.extend_from_slice(&sample_rate.to_le_bytes());
                packet.extend_from_slice(&stream_id.to_le_bytes());
                for s in frames {
                    packet.extend_from_slice(&s.to_le_bytes());
                }
                for t in &targets {
                    let _ = socket.send_to(&packet, t);
                }
            }
            seq = seq.wrapping_add(1);
            pos = pos.wrapping_add(n as u32);
        }
    }
}

fn recv_loop(socket: UdpSocket, clients: Clients, mut qos: Option<qos::Qos>) {
    socket.set_read_timeout(Some(Duration::from_millis(500))).ok();
    let mut buf = [0u8; 1500];
    while !STOP.load(Ordering::Relaxed) {
        let Ok((len, from)) = socket.recv_from(&mut buf) else { continue };
        let msg = &buf[..len];
        if len < 8 || &msg[..2] != MAGIC {
            continue;
        }
        let id = u32::from_le_bytes(msg[4..8].try_into().unwrap());
        match msg[2] {
            T_HELLO if len >= 16 => {
                // Optional extension (20 bytes): u16 kbps, u8 frame duration in 0.5 ms, u8 redundancy.
                let opus = (msg[3] == CODEC_OPUS && len >= 20).then(|| {
                    OpusConfig::from_hello(u16::from_le_bytes([msg[16], msg[17]]), msg[18], msg[19])
                });
                {
                    let mut list = clients.lock().unwrap();
                    match list.iter_mut().find(|c| c.id == id) {
                        Some(c) => {
                            c.opus = opus;
                            if c.addr != from {
                                println!("client {id:08x} moved {} -> {from}", c.addr);
                                c.addr = from;
                                if let Some(q) = qos.as_mut() {
                                    q.add_destination(&socket, from);
                                }
                            }
                            c.last_seen = Instant::now();
                        }
                        None => {
                            println!(
                                "client {id:08x} connected from {from} ({})",
                                if opus.is_some() { "opus" } else { "pcm" }
                            );
                            if let Some(q) = qos.as_mut() {
                                q.add_destination(&socket, from);
                            }
                            list.push(Client { addr: from, id, last_seen: Instant::now(), opus });
                        }
                    }
                }
                let mut pong = [0u8; 16];
                pong[..2].copy_from_slice(MAGIC);
                pong[2] = T_PONG;
                pong[4..8].copy_from_slice(&id.to_le_bytes());
                pong[8..16].copy_from_slice(&msg[8..16]);
                let _ = socket.send_to(&pong, from);
            }
            T_BYE => {
                let mut list = clients.lock().unwrap();
                if let Some(i) = list.iter().position(|c| c.id == id) {
                    println!("client {id:08x} disconnected");
                    list.remove(i);
                }
            }
            _ => {}
        }
    }
}
