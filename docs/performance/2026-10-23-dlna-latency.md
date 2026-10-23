# DLNA live-stream delivery corrections

The previous HTTP sender waited whenever its cursor was less than 512 KiB
behind the producer. This withheld the newest bytes, added variable latency,
and could indefinitely retain a small final frame. Socket writes now provide
network backpressure directly, outside the producer lock.

New live requests join the newest retained random-access point. Bounded byte
ranges return exact offsets and lengths; open-ended requests start a fresh 200
live response because a previous live response's byte offsets are not global
ring-buffer coordinates. Overwritten ring data is never served as historical
data. Compatibility with receivers other than the tested Android app remains
to be checked, particularly their handling of open-ended Range requests.

MPEG-TS now includes PCR on its declared video PID, an 80 ms idle PCR heartbeat,
H.264 access-unit delimiters, and random-access flags. Muxing and publication
are serialized across audio, video and heartbeat producers. Keyframe marking
and ring publication are atomic. HTTP statistics distinguish sender backlog
from buffering inside the receiving player.

## Verification

Run with Python and JDK 17, without an Android runtime:

```powershell
python tools/test_dlna_http.py
python tools/test_dlna_muxer.py
```

The HTTP harness exercises seven real loopback-socket cases. The mux harness
checks 15 PES boundary sizes, PCR, AUD, random-access flags and idle heartbeat.
`flutter build apk --debug` succeeds. FFmpeg decoded audio/video for 12 seconds
each with moving and static source content without decoder errors.

An eight-second sample of the final stream had no TS continuity errors and a
maximum PCR interval of 89.6 ms. Two independent code-review passes found no
blocking defect. Bounded ranges still describe the server's retained global
bytes, not resumable offsets relative to an earlier fresh 200 response.

## Measured limitation

Both test devices are Android 14 LDPlayer instances. The third-party receiver
is Guangyuansu Cast 1.1.0-fe97f4c. Its automatic setting selects the Android
system player (Player 0), despite its IjkVideoView wrapper's name. Its alternate
player (Player 4, AliFrameWork) failed on this emulator and was reverted.

Concurrent screenshots measured 23.224 s before these changes, 16.228 s in one
subsequent session, and 18.924 s after about 130 s in the final session. These
are separate observations, not a controlled long-duration A/B benchmark.
Sender backlog was zero during the final measurement; the receiver still
paused to buffer for 12.716 s. The overall low-latency goal is **not achieved**.

Reading the same HTTP stream with a low-buffer FFmpeg decoder yielded a frame
whose stopwatch was 133 ms behind a subsequent source screenshot. This includes
capture timing and is not the third-party receiver's screen latency.

Android 14's default system-player buffering settings are 5 s for preparation
and 15 s for resuming after underrun. These are source defaults, not a universal
claim about every device:

- [Android GenericSource.cpp](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/android14-release/media/libmediaplayerservice/nuplayer/GenericSource.cpp)
- [Android NuCachedSource2.cpp](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/android14-release/media/libdatasource/NuCachedSource2.cpp)

Further work must measure actual presentation latency and address receiving
player buffering. Do not equate zero sender backlog with low display latency.
