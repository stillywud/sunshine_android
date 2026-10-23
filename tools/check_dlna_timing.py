"""Check measured DLNA encoder output during an animated-screen device test.

Capture a current-process DlnaStreamer log with adb first. This checks actual
codec output rather than assuming KEY_FRAME_RATE limits Surface input.
"""
import argparse
from pathlib import Path
import re

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("log", type=Path)
parser.add_argument("--expected-fps", type=float, default=25)
parser.add_argument("--windows", type=int, default=3)
args = parser.parse_args()
rows = re.findall(r"timing fps=([\d.Ee+-]+) codecAgeUs=(-?\d+)\.\.(-?\d+) nonMonotonicCodecPts=(\d+)", args.log.read_text(encoding="utf-8", errors="replace"))
if len(rows) < args.windows:
    raise SystemExit(f"FAIL: need {args.windows} timing windows, found {len(rows)}")
rows = rows[-args.windows:]
fps = [float(r[0]) for r in rows]
print(f"Measured fps: {fps}; codec age ranges (us): {[r[1:3] for r in rows]}")
limit = args.expected_fps * 1.15 + 0.5
if max(fps) > limit:
    raise SystemExit(f"FAIL: actual encoder output {max(fps):.2f} fps exceeds configured {args.expected_fps:g} fps (tolerance {limit:.2f})")
if min(fps) < args.expected_fps * 0.7:
    raise SystemExit("FAIL: animated-source test did not sustain enough frames; check device load/source")
print("PASS: animated-screen output stays within its configured frame budget")
