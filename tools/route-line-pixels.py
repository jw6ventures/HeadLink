#!/usr/bin/env python3
"""Counts route-coloured pixels in the lab receiver's decoded frames (P6.4).

The emulator lab's destination run (tools/emulator-lab.sh, LAB_DESTINATION=1) shares a
destination once the app is projecting, so the first frames the receiver decoded were
drawn before any route existed and the last ones after two had been computed. The route
line is MapEngine.ROUTE_COLOR, 34,199,240: a cyan used nowhere in the owned map theme or
the dashboard chrome, so a count near zero at the start and a large one at the end is the
line appearing in the picture the head unit received, not in the app's own counters.

H.264 blurs a 9-pixel line's edges into the ground, so the match is a box around the
colour rather than the exact value, and only the line's core is counted.

    tools/route-line-pixels.py <frames dir>

Needs Pillow. Prints one line per frame and exits non-zero if the check fails.
"""
import pathlib
import sys

from PIL import Image

# Before any route: some slack for compression noise at an edge that happens to be cyan-ish.
BEFORE_MAX = 200
# After: a 9 px line from the vehicle to the pane's edge at the follow view's zoom is
# several thousand pixels; a thousand is well clear of noise and well short of that.
AFTER_MIN = 1000
# Frames at each end to judge by: the receiver writes one per second.
EDGE = 3


def route_pixels(path: pathlib.Path) -> int:
    data = Image.open(path).convert("RGB").tobytes()
    count = 0
    for i in range(0, len(data), 3):
        r, g, b = data[i], data[i + 1], data[i + 2]
        if r < 90 and 165 <= g <= 235 and b >= 200:
            count += 1
    return count


def main() -> int:
    frames = sorted(pathlib.Path(sys.argv[1]).glob("*.png"))
    if len(frames) < 2 * EDGE:
        print(f"route-line: FAIL only {len(frames)} frames decoded", file=sys.stderr)
        return 1
    counts = [route_pixels(frame) for frame in frames]
    for frame, count in zip(frames, counts):
        print(f"route-line: {frame.name} route_pixels={count}")
    before = max(counts[:EDGE])
    after = min(counts[-EDGE:])
    print(f"route-line: first {EDGE} frames max={before}, last {EDGE} frames min={after}")
    if before > BEFORE_MAX:
        print(f"route-line: FAIL route colour before any route ({before} > {BEFORE_MAX})", file=sys.stderr)
        return 1
    if after < AFTER_MIN:
        print(f"route-line: FAIL no route line at the end ({after} < {AFTER_MIN})", file=sys.stderr)
        return 1
    print("route-line: pass")
    return 0


if __name__ == "__main__":
    sys.exit(main())
