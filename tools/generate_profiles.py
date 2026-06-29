#!/usr/bin/env python3
"""
ThreadGens local profile generator.

Creates cheap local procedural avatar PNGs and Reddit-style usernames.
No AI model, no web calls, no Python packages required.

Outputs by default:
- assets/pfp/tg_profile_0001.png ...
- data/author_names.txt
"""

from __future__ import annotations

import argparse
import hashlib
import math
import random
import struct
import time
import zlib
from pathlib import Path
from typing import Iterable, List, Sequence, Tuple

RGB = Tuple[int, int, int]

ADJECTIVES = [
    "Null", "Sleepy", "Crimson", "Static", "Tiny", "Cosmic", "Glitchy", "Dusty",
    "Feral", "Lucky", "Pixel", "Turbo", "Silent", "Rusty", "Neon", "Odd",
    "Bitter", "Soft", "Mild", "Chaotic", "Quantum", "Local", "Hidden", "Midnight",
    "Spicy", "Wired", "Random", "Broken", "Golden", "Caffeinated", "Ancient", "Fuzzy",
]

NOUNS = [
    "Ninja", "Waffle", "Goblin", "Otter", "Mailbox", "Raccoon", "Wizard", "Banana",
    "Printer", "Penguin", "Moth", "Satellite", "Toaster", "Ferret", "Debugger", "Pigeon",
    "Mushroom", "Gremlin", "Lizard", "Keyboard", "Ghost", "Spoon", "Beetle", "Comet",
    "Possum", "Turnip", "Dragon", "Cactus", "Potato", "Moose", "Packet", "Hamster",
]

SEPARATORS = ["", "_", "", "", "x", "The"]

PALETTES: Sequence[Tuple[RGB, RGB, RGB]] = [
    ((255, 69, 0), (255, 181, 112), (42, 42, 46)),
    ((135, 206, 250), (255, 126, 170), (30, 30, 34)),
    ((139, 92, 246), (45, 212, 191), (26, 26, 30)),
    ((245, 158, 11), (239, 68, 68), (32, 32, 36)),
    ((34, 197, 94), (250, 204, 21), (22, 22, 26)),
    ((56, 189, 248), (99, 102, 241), (20, 20, 24)),
    ((251, 113, 133), (251, 191, 36), (28, 28, 32)),
    ((163, 230, 53), (14, 165, 233), (24, 24, 28)),
]


def clamp(value: int) -> int:
    return max(0, min(255, value))


def mix(a: RGB, b: RGB, t: float) -> RGB:
    return (
        clamp(int(a[0] + (b[0] - a[0]) * t)),
        clamp(int(a[1] + (b[1] - a[1]) * t)),
        clamp(int(a[2] + (b[2] - a[2]) * t)),
    )


def lighten(color: RGB, amount: float) -> RGB:
    return mix(color, (255, 255, 255), amount)


def darken(color: RGB, amount: float) -> RGB:
    return mix(color, (0, 0, 0), amount)


def write_png(path: Path, width: int, height: int, pixels: Sequence[RGB]) -> None:
    """Write RGB pixels as a PNG using only the Python standard library."""
    path.parent.mkdir(parents=True, exist_ok=True)

    def chunk(kind: bytes, data: bytes) -> bytes:
        return (
            struct.pack(">I", len(data))
            + kind
            + data
            + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
        )

    raw = bytearray()
    for y in range(height):
        raw.append(0)  # PNG filter: none
        row_start = y * width
        for r, g, b in pixels[row_start:row_start + width]:
            raw.extend((r, g, b))

    png = bytearray(b"\x89PNG\r\n\x1a\n")
    png.extend(chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)))
    png.extend(chunk(b"IDAT", zlib.compress(bytes(raw), level=9)))
    png.extend(chunk(b"IEND", b""))
    path.write_bytes(bytes(png))


def seed_int(value: str) -> int:
    return int(hashlib.sha256(value.encode("utf-8")).hexdigest()[:16], 16)


def make_username(rng: random.Random, used: set[str]) -> str:
    for _ in range(500):
        adjective = rng.choice(ADJECTIVES)
        noun = rng.choice(NOUNS)
        sep = rng.choice(SEPARATORS)
        number = rng.randint(2, 9999)

        if sep == "":
            name = f"{adjective}{noun}{number if rng.random() < 0.65 else ''}"
        elif sep == "The":
            name = f"{adjective}The{noun}{number if rng.random() < 0.45 else ''}"
        else:
            name = f"{adjective}{sep}{noun}{number if rng.random() < 0.55 else ''}"

        if 5 <= len(name) <= 24 and name not in used:
            used.add(name)
            return name

    fallback = f"User{rng.randint(100000, 999999)}"
    used.add(fallback)
    return fallback


def draw_avatar(username: str, size: int) -> List[RGB]:
    rng = random.Random(seed_int(username))
    primary, secondary, base = rng.choice(PALETTES)
    pixels: List[RGB] = []
    center = (size - 1) / 2.0
    radius = size * 0.49

    # Background: circular-ish gradient on square canvas. Java crops it into a circle.
    for y in range(size):
        for x in range(size):
            dx = x - center
            dy = y - center
            dist = math.sqrt(dx * dx + dy * dy) / max(1.0, radius)
            angle = (math.atan2(dy, dx) + math.pi) / (2 * math.pi)
            t = min(1.0, max(0.0, dist * 0.72 + angle * 0.28))
            color = mix(primary, secondary, t)
            if dist > 0.88:
                color = mix(color, base, min(1.0, (dist - 0.88) * 4.0))
            pixels.append(color)

    # Symmetric geometric marks so each avatar looks generated but cheap.
    shape_count = rng.randint(4, 8)
    for _ in range(shape_count):
        mark = lighten(rng.choice([primary, secondary]), rng.uniform(0.15, 0.42))
        mark = darken(mark, rng.uniform(0.0, 0.18))
        cx = rng.randint(int(size * 0.22), int(size * 0.78))
        cy = rng.randint(int(size * 0.20), int(size * 0.80))
        rr = rng.randint(int(size * 0.05), int(size * 0.16))
        mirrored = rng.random() < 0.72
        square = rng.random() < 0.35

        centers = [(cx, cy)]
        if mirrored:
            centers.append((size - cx - 1, cy))

        for mx, my in centers:
            for yy in range(max(0, my - rr), min(size, my + rr + 1)):
                for xx in range(max(0, mx - rr), min(size, mx + rr + 1)):
                    inside = abs(xx - mx) <= rr and abs(yy - my) <= rr if square else (xx - mx) ** 2 + (yy - my) ** 2 <= rr ** 2
                    if inside:
                        idx = yy * size + xx
                        pixels[idx] = mix(pixels[idx], mark, 0.68)

    # Soft border ring.
    border = lighten(base, 0.25)
    for y in range(size):
        for x in range(size):
            dx = x - center
            dy = y - center
            dist = math.sqrt(dx * dx + dy * dy) / max(1.0, radius)
            if 0.91 <= dist <= 0.99:
                idx = y * size + x
                pixels[idx] = mix(pixels[idx], border, 0.55)

    return pixels


def next_profile_index(pfp_dir: Path, prefix: str) -> int:
    highest = 0
    for path in pfp_dir.glob(f"{prefix}_*.png"):
        stem = path.stem
        number = stem.replace(f"{prefix}_", "", 1)
        if number.isdigit():
            highest = max(highest, int(number))
    return highest + 1


def read_existing_names(path: Path) -> List[str]:
    if not path.exists():
        return []
    return [line.strip() for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def write_names(path: Path, names: Iterable[str], append: bool) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    new_lines = list(names)
    if append and path.exists():
        existing = read_existing_names(path)
        all_lines = existing + new_lines
    else:
        all_lines = new_lines
    path.write_text("\n".join(all_lines) + "\n", encoding="utf-8")


def backup_file(path: Path) -> Path | None:
    if not path.exists():
        return None
    stamp = time.strftime("%Y%m%d_%H%M%S")
    backup = path.with_name(f"{path.stem}.backup_{stamp}{path.suffix}")
    backup.write_text(path.read_text(encoding="utf-8"), encoding="utf-8")
    return backup


def main() -> None:
    parser = argparse.ArgumentParser(description="Mass-generate local ThreadGens profile pictures and usernames.")
    parser.add_argument("--count", type=int, default=100, help="How many profiles to generate")
    parser.add_argument("--size", type=int, default=256, help="PNG size in pixels")
    parser.add_argument("--pfp-dir", default="assets/pfp", help="Output folder for PNG profile pictures")
    parser.add_argument("--names-file", default="data/author_names.txt", help="Username list file to write")
    parser.add_argument("--prefix", default="tg_profile", help="Profile PNG filename prefix")
    parser.add_argument("--seed", default="threadgens", help="Seed for reproducible username generation")
    parser.add_argument("--append-names", action="store_true", help="Append new usernames instead of replacing the names file")
    parser.add_argument("--no-backup", action="store_true", help="Do not back up author_names.txt before replacing")
    args = parser.parse_args()

    if args.count <= 0:
        raise SystemExit("--count must be greater than 0")
    if args.size < 64:
        raise SystemExit("--size must be at least 64")

    pfp_dir = Path(args.pfp_dir)
    names_file = Path(args.names_file)
    rng = random.Random(seed_int(args.seed + str(time.time_ns())))
    used_names = set(read_existing_names(names_file))

    if not args.append_names and not args.no_backup:
        backup = backup_file(names_file)
        if backup:
            print(f"Backed up existing usernames: {backup}")

    start_index = next_profile_index(pfp_dir, args.prefix)
    generated_names: List[str] = []

    print(f"Generating {args.count} profile pictures into {pfp_dir}")
    print(f"Writing usernames to {names_file} ({'append' if args.append_names else 'replace'})")

    for offset in range(args.count):
        username = make_username(rng, used_names)
        generated_names.append(username)
        filename = f"{args.prefix}_{start_index + offset:04d}.png"
        output_path = pfp_dir / filename
        pixels = draw_avatar(username, args.size)
        write_png(output_path, args.size, args.size, pixels)
        print(f"[{offset + 1:03d}/{args.count:03d}] {username} -> {output_path}")

    write_names(names_file, generated_names, append=args.append_names)
    print("Done.")
    print(f"Generated PNGs: {pfp_dir}")
    print(f"Username file: {names_file}")


if __name__ == "__main__":
    main()
