"""Measure continuity, PCR cadence and delivered PTS/PCR offsets in a TS sample.

These are transport/media-clock checks, not a measurement of screen latency.
"""
import argparse
import json
from pathlib import Path
import time
import urllib.request


def analyze(data):
    counters = {}
    counts = {}
    errors = []
    pcrs = []
    offsets = {}
    latest_pcr = None
    for offset in range(0, len(data) - 187, 188):
        packet = data[offset:offset + 188]
        if packet[0] != 0x47:
            errors.append({"sync_offset": offset})
            continue
        pid = ((packet[1] & 31) << 8) | packet[2]
        flags = (packet[3] >> 4) & 3
        counter = packet[3] & 15
        counts[pid] = counts.get(pid, 0) + 1
        payload = 4
        discontinuity = False
        if flags & 2:
            length = packet[4]
            payload += 1 + length
            if length:
                discontinuity = bool(packet[5] & 0x80)
            if length >= 7 and packet[5] & 0x10:
                base = ((packet[6] << 25) | (packet[7] << 17) | (packet[8] << 9)
                        | (packet[9] << 1) | (packet[10] >> 7))
                latest_pcr = base
                pcrs.append(base)
        if pid in counters and not discontinuity and pid != 0x1FFF:
            expected = (counters[pid] + bool(flags & 1)) & 15
            if counter != expected:
                errors.append({"pid": pid, "at": offset, "expected": expected, "actual": counter})
        counters[pid] = counter
        if flags & 1 and packet[1] & 0x40 and payload + 14 <= 188:
            pes = packet[payload:]
            if pes[:3] == b"\x00\x00\x01" and pes[7] & 0x80 and latest_pcr is not None:
                pts = (((pes[9] >> 1) & 7) << 30) | (pes[10] << 22) | ((pes[11] >> 1) << 15) | (pes[12] << 7) | (pes[13] >> 1)
                delta = ((pts - latest_pcr + (1 << 32)) % (1 << 33)) - (1 << 32)
                offsets.setdefault(pid, []).append(delta / 90.0)
    intervals = [((b - a) % (1 << 33)) / 90.0 for a, b in zip(pcrs, pcrs[1:])]
    return {"bytes": len(data), "packets_by_pid": counts, "continuity_errors": errors[:20],
            "pcr_count": len(pcrs), "max_pcr_interval_ms": max(intervals, default=None),
            "pts_minus_pcr_ms": {pid: {"min": min(values), "max": max(values)} for pid, values in offsets.items()}}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("sample", type=Path)
    parser.add_argument("--url")
    parser.add_argument("--seconds", type=float, default=8)
    args = parser.parse_args()
    if args.url:
        deadline = time.monotonic() + args.seconds
        data = bytearray()
        with urllib.request.urlopen(args.url, timeout=5) as response:
            while time.monotonic() < deadline:
                block = response.read1(188 * 128)
                if not block:
                    break
                data.extend(block)
        args.sample.write_bytes(data)
    result = analyze(args.sample.read_bytes())
    print(json.dumps(result, indent=2))
    if result["continuity_errors"]:
        raise SystemExit("FAIL: TS continuity error(s)")
