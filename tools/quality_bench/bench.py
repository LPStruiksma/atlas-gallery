#!/usr/bin/env python3
"""Decode-free reproducible operation/timing proxy using generated luminance only."""
import argparse, math, platform, statistics, time

def generated(width, height, seed):
    # Fixed LCG: synthetic, contains no external image data.
    value = seed
    out = []
    for _ in range(width * height):
        value = (1664525 * value + 1013904223) & 0xffffffff
        out.append(((value >> 16) & 255) / 255.0)
    return out

def laplacian_variance(values, width, height):
    count = 0; mean = 0.0; m2 = 0.0
    for y in range(1, height - 1):
        for x in range(1, width - 1):
            i = y * width + x
            lap = values[i-width] + values[i+width] + values[i-1] + values[i+1] - 4 * values[i]
            count += 1; delta = lap - mean; mean += delta / count; m2 += delta * (lap - mean)
    return m2 / count

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--width", type=int, default=256); p.add_argument("--height", type=int, default=192)
    p.add_argument("--samples", type=int, default=50); p.add_argument("--warmups", type=int, default=10)
    a = p.parse_args(); values = generated(a.width, a.height, 668)
    for _ in range(a.warmups): laplacian_variance(values, a.width, a.height)
    times = []
    for _ in range(a.samples):
        start = time.perf_counter_ns(); laplacian_variance(values, a.width, a.height); times.append((time.perf_counter_ns()-start)/1e6)
    ordered = sorted(times)
    p95 = ordered[math.ceil(.95 * len(ordered)) - 1]
    print(f"platform={platform.platform()} python={platform.python_version()} dimensions={a.width}x{a.height}")
    print(f"warmups={a.warmups} samples={a.samples} decode=excluded memory=NOT_MEASURED")
    print(f"p50_ms={statistics.median(times):.3f} p95_ms={p95:.3f} checksum={laplacian_variance(values,a.width,a.height):.9f}")

if __name__ == "__main__": main()
