# vmcast wire protocol (v1)

UDP, default port 6990. All integers little-endian. Every packet starts with the
2-byte magic `"VC"` followed by a 1-byte type.

## Client -> sender

### HELLO (type 1), 16 bytes, sent every 250 ms
| off | size | field |
|----:|-----:|-------|
| 0 | 2 | magic `VC` |
| 2 | 1 | type = 1 |
| 3 | 1 | requested codec (0 = PCM s16le) |
| 4 | 4 | client id (random per session) |
| 8 | 8 | client timestamp (ns, echoed back in PONG) |

The sender streams to every address that sent a HELLO within the last 3 s.
That's the whole "connection": it works through NAT and WireGuard without any
configuration on the PC.

Optional Opus extension (HELLO is then 20 bytes and codec = 1):
| off | size | field |
|----:|-----:|-------|
| 16 | 2 | bitrate in kbps (16..256) |
| 18 | 1 | frame duration in 0.5 ms units (5, 10, 20 or 40) |
| 19 | 1 | redundancy: previous frames repeated in each packet (0..3) |

The sender runs one Opus encoder; with several Opus clients the most recent
one's settings win. Opus requires Voicemeeter at 48 kHz.

### BYE (type 4), 8 bytes
magic, type = 4, 1 pad byte, client id. Sender drops the client immediately.

## Sender -> client

### AUDIO (type 2), 24-byte header + payload
| off | size | field |
|----:|-----:|-------|
| 0 | 2 | magic `VC` |
| 2 | 1 | type = 2 |
| 3 | 1 | codec (0 = PCM s16le interleaved) |
| 4 | 1 | channels |
| 5 | 1 | flags (reserved) |
| 6 | 2 | frames in this packet |
| 8 | 4 | sequence number |
| 12 | 4 | frame position of first frame (wraps) |
| 16 | 4 | sample rate |
| 20 | 4 | stream id (changes when the sender (re)starts the audio stream) |
| 24 | n | payload |

Codec 1 (Opus): `frames` is the Opus frame size, the frame position is that of
the newest frame, and the payload is `u8 count` followed by `count` x
(`u16 len`, Opus frame), newest first. Frame k starts at `pos - k * frames`.
The receiver decodes in order, fills gaps from the redundant copies and runs
Opus packet-loss concealment for frames that never arrived.

PCM packets are variable-size (everything the Voicemeeter callback produced, split
into chunks of at most `--max-frames`). This avoids holding leftover samples
back until the next callback.

### PONG (type 3), 16 bytes
magic, type = 3, 1 pad byte, client id, echoed client timestamp. Used for RTT.
