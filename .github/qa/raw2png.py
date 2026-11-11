#!/usr/bin/env python3
"""Turns a raw Android screencap (adb shell screencap, no -p) into a PNG. Pure Python, no packages.

A raw grab is much faster than `screencap -p` on the emulator (no PNG encoding on the device), so QA can take
frames about a second apart mid-intro.

Usage: raw2png.py in.raw out.png [half.png]
  half.png: also a half-size copy (each 2x2 block averaged), small enough to print into the job log.
"""
import struct
import sys
import zlib


def write_png(path, w, h, rgb):
    raw = b"".join(b"\x00" + rgb[y * w * 3:(y + 1) * w * 3] for y in range(h))

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n")
        f.write(chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)))
        f.write(chunk(b"IDAT", zlib.compress(raw, 9)))
        f.write(chunk(b"IEND", b""))


def main():
    data = open(sys.argv[1], "rb").read()
    w, h = struct.unpack("<II", data[:8])
    pixels = data[len(data) - w * h * 4:]  # the header is 12 or 16 bytes depending on the Android version
    rgb = bytearray(w * h * 3)
    rgb[0::3] = pixels[0::4]
    rgb[1::3] = pixels[1::4]
    rgb[2::3] = pixels[2::4]
    write_png(sys.argv[2], w, h, bytes(rgb))
    if len(sys.argv) > 3:
        hw, hh = w // 2, h // 2
        half = bytearray(hw * hh * 3)
        row = w * 3
        for y in range(hh):
            a = rgb[2 * y * row:(2 * y + 1) * row]
            b = rgb[(2 * y + 1) * row:(2 * y + 2) * row]
            o = y * hw * 3
            for x in range(hw):
                i = 6 * x
                for c in range(3):
                    half[o + 3 * x + c] = (a[i + c] + a[i + 3 + c] + b[i + c] + b[i + 3 + c] + 2) >> 2
        write_png(sys.argv[3], hw, hh, bytes(half))


if __name__ == "__main__":
    main()
