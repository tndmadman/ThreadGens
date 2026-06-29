#!/usr/bin/env python3
"""
ThreadGens ComfyUI profile generator.

Creates AI-generated face/selfie style profile pictures using a local ComfyUI server.
Also writes matching Reddit-style usernames to data/author_names.txt.

Requirements:
- ComfyUI running locally, usually http://127.0.0.1:8188
- At least one checkpoint installed in ComfyUI
- Optional: Ollama running locally for LLM-generated usernames
- No Python packages required. Uses only the standard library.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import random
import re
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Tuple

ADJECTIVES = [
    "Null", "Sleepy", "Crimson", "Static", "Tiny", "Cosmic", "Glitchy", "Dusty",
    "Feral", "Lucky", "Pixel", "Turbo", "Silent", "Rusty", "Neon", "Odd",
    "Bitter", "Soft", "Mild", "Chaotic", "Quantum", "Local", "Hidden", "Midnight",
    "Spicy", "Wired", "Random", "Broken", "Golden", "Caffeinated", "Ancient", "Fuzzy",
    "Analog", "Velvet", "Urban", "Mossy", "Solar", "Noisy", "Pocket", "LoFi",
]

NOUNS = [
    "Ninja", "Waffle", "Goblin", "Otter", "Mailbox", "Raccoon", "Wizard", "Banana",
    "Printer", "Penguin", "Moth", "Satellite", "Toaster", "Ferret", "Debugger", "Pigeon",
    "Mushroom", "Gremlin", "Lizard", "Keyboard", "Ghost", "Spoon", "Beetle", "Comet",
    "Possum", "Turnip", "Dragon", "Cactus", "Potato", "Moose", "Packet", "Hamster",
    "Mango", "Signal", "Lantern", "Skater", "Courier", "Fox", "Miner", "Runner",
]

REDDIT_WORDS = [
    "coffee", "toast", "parking", "garage", "laundry", "couch", "fridge", "basement",
    "neighbor", "mailbox", "receipt", "cereal", "soup", "noodles", "trash", "pickup",
    "driveway", "keyboard", "headphones", "blanket", "porch", "window", "wallet", "folder",
    "dog", "cat", "bird", "fish", "moth", "rat", "opossum", "pigeon", "mango", "potato",
    "salt", "pepper", "static", "signal", "router", "bluetooth", "battery", "charger",
    "sleep", "midnight", "morning", "Tuesday", "weekend", "shift", "break", "receipt",
]

FACE_DETAILS = [
    "messy hair", "short hair", "curly hair", "beanie", "hoodie", "round glasses",
    "soft smile", "tired expression", "raised eyebrow", "freckles", "stubble",
    "bright eyes", "casual jacket", "gaming headset", "denim jacket", "black t-shirt",
    "warm indoor lighting", "phone selfie angle", "soft window light", "subtle neon light",
]

BACKGROUND_DETAILS = [
    "bedroom background", "plain wall background", "desk setup background", "soft blurred room",
    "car seat background", "coffee shop background", "night street background", "apartment hallway background",
    "computer monitor glow", "bookshelf background", "garage background", "laundromat background",
]

BAD_USERNAME_WORDS = [
    "ninja", "wizard", "dragon", "gamer", "epic", "pro", "legend", "king", "queen", "lord",
    "master", "beast", "alpha", "sigma", "xoxo", "official", "real", "admin", "mod",
    "reddit", "redditor", "username", "user", "ai", "bot", "chatgpt", "influencer",
]

NEGATIVE_PROMPT = (
    "abstract avatar, blob, icon, logo, mascot, cartoon symbol, bad face, deformed face, "
    "extra eyes, extra mouth, duplicate face, distorted, melted, low quality, blurry, "
    "text, username text, watermark, signature, frame, border, nsfw, nude, child"
)


def seed_int(value: str) -> int:
    return int(hashlib.sha256(value.encode("utf-8")).hexdigest()[:16], 16)


def make_random_username(rng: random.Random, used: set[str]) -> str:
    for _ in range(500):
        word_a = rng.choice(REDDIT_WORDS).lower()
        word_b = rng.choice(REDDIT_WORDS).lower()
        number = rng.choice(["", "", "", str(rng.randint(7, 99)), str(rng.randint(1984, 2006))])
        template = rng.choice([
            f"{word_a}_{word_b}{number}",
            f"{word_a}{word_b}{number}",
            f"throwaway_{word_a}{number}",
            f"{word_a}_alt",
            f"not_{word_a}",
            f"probably_{word_a}",
            f"{word_a}_account{rng.randint(2, 9)}",
        ])
        name = clean_username(template)
        if is_valid_username(name, used):
            used.add(name)
            return name
    fallback = f"throwaway_{rng.randint(1000, 9999)}"
    used.add(fallback)
    return fallback


def is_valid_username(name: str, used: set[str]) -> bool:
    if name in used:
        return False
    if re.fullmatch(r"[a-z0-9_-]{5,20}", name) is None:
        return False
    if name.startswith(("_", "-")) or name.endswith(("_", "-")):
        return False
    if "__" in name or "--" in name or "_-" in name or "-_" in name:
        return False
    if re.search(r"\d{5,}$", name):
        return False
    if any(bad in name for bad in BAD_USERNAME_WORDS):
        return False
    return True


def clean_username(raw: str) -> str:
    name = raw.strip().lower()
    name = re.sub(r"^[-*•\d.)\s]+", "", name)
    name = re.sub(r"^(username|user|name)\s*[:=-]\s*", "", name, flags=re.IGNORECASE)
    name = name.replace("u/", "").replace("@", "")
    name = re.sub(r"\s+", "_", name)
    name = re.sub(r"[^a-z0-9_-]+", "", name)
    name = re.sub(r"[_-]{2,}", "_", name)
    name = name.strip("_-")
    if len(name) > 20:
        name = name[:20].strip("_-")
    return name


def request_json(method: str, url: str, payload: Optional[dict] = None, timeout: int = 30) -> dict:
    data = None
    headers = {"Content-Type": "application/json"}
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def request_bytes(url: str, timeout: int = 60) -> bytes:
    with urllib.request.urlopen(url, timeout=timeout) as response:
        return response.read()


def request_ollama_usernames(ollama_url: str, model: str, count: int, used: set[str]) -> List[str]:
    prompt = (
        f"Generate exactly {count} realistic Reddit usernames for fictional everyday users.\n"
        "Return one username per line only. No bullets. No numbering. No explanations.\n\n"
        "Style rules:\n"
        "- Make them look like normal Reddit accounts, not gamer tags.\n"
        "- Mostly lowercase.\n"
        "- Use simple boring words, alt accounts, throwaways, habits, objects, mild jokes, or everyday phrases.\n"
        "- Allowed characters: lowercase letters, numbers, underscores, and hyphens.\n"
        "- 5 to 20 characters each.\n"
        "- Optional small numbers are okay, but no huge random number strings.\n"
        "- Do not use celebrity names, real full names, brands, Reddit, redditor, admin, mod, bot, AI, gamer, ninja, wizard, dragon, king, queen, legend, pro, official, or influencer.\n"
        "- Avoid camelCase and TitleCase.\n\n"
        "Good examples:\n"
        "throwaway_couch\n"
        "parking_lot_22\n"
        "coffee_receipt\n"
        "not_my_fridge\n"
        "blue_civic_guy\n"
        "soup_account3\n"
        "probably_tuesday\n"
        "old_router_9\n"
        "porch_light\n"
        "laundry_alt\n\n"
        "Bad examples:\n"
        "EpicDragonKing\n"
        "CoolGamer420\n"
        "RedditUser12345\n"
        "xXShadowNinjaXx\n"
        "InfluencerVibes\n"
    )
    payload = {"model": model, "prompt": prompt, "stream": False}
    response = request_json("POST", ollama_url, payload, timeout=180)
    text = response.get("response", "")
    names: List[str] = []
    for raw_line in text.replace("\r", "\n").split("\n"):
        name = clean_username(raw_line)
        if is_valid_username(name, used):
            used.add(name)
            names.append(name)
        if len(names) >= count:
            break
    return names


def generate_usernames(args: argparse.Namespace, rng: random.Random, used: set[str]) -> List[str]:
    if args.username_mode == "random":
        print("Username mode: fast random")
        return [make_random_username(rng, used) for _ in range(args.count)]

    print(f"Username mode: Ollama-generated using {args.ollama_model}")
    names: List[str] = []
    attempts = 0
    while len(names) < args.count and attempts < 4:
        attempts += 1
        remaining = args.count - len(names)
        try:
            names.extend(request_ollama_usernames(args.ollama_url, args.ollama_model, remaining, used))
        except Exception as exc:
            print(f"Ollama username generation failed on attempt {attempts}: {exc}")
            break

    if len(names) < args.count:
        print(f"Ollama only produced {len(names)} usable usernames. Filling the rest with fast random Reddit-like names.")
        while len(names) < args.count:
            names.append(make_random_username(rng, used))
    return names[:args.count]


def check_comfy(base_url: str) -> None:
    try:
        request_json("GET", f"{base_url}/system_stats", timeout=10)
    except Exception as exc:
        raise SystemExit(
            f"Could not reach ComfyUI at {base_url}. Start ComfyUI first, then rerun this script.\n{exc}"
        )


def list_checkpoints(base_url: str) -> List[str]:
    info = request_json("GET", f"{base_url}/object_info/CheckpointLoaderSimple", timeout=20)
    ckpt_info = info.get("CheckpointLoaderSimple", {}).get("input", {}).get("required", {}).get("ckpt_name", [])
    if ckpt_info and isinstance(ckpt_info[0], list):
        return ckpt_info[0]
    return []


def build_prompt(username: str, rng: random.Random, style_prompt: str) -> str:
    details = rng.sample(FACE_DETAILS, k=3)
    background = rng.choice(BACKGROUND_DETAILS)
    age = rng.choice(["adult in their 20s", "adult in their 30s", "adult in their 40s"])
    vibe = username.replace("_", " ").replace("-", " ")
    return (
        f"fictional {age}, close-up social media profile selfie, face centered, shoulders visible, "
        f"natural candid phone photo, {background}, {', '.join(details)}, "
        f"subtle personality inspired by username {vibe}, realistic skin texture, sharp eyes, "
        f"square crop, profile picture, {style_prompt}"
    )


def build_workflow(
    checkpoint: str,
    prompt: str,
    negative: str,
    seed: int,
    size: int,
    steps: int,
    cfg: float,
    sampler: str,
    scheduler: str,
    filename_prefix: str,
) -> Dict[str, dict]:
    return {
        "4": {"class_type": "CheckpointLoaderSimple", "inputs": {"ckpt_name": checkpoint}},
        "5": {"class_type": "EmptyLatentImage", "inputs": {"width": size, "height": size, "batch_size": 1}},
        "6": {"class_type": "CLIPTextEncode", "inputs": {"text": prompt, "clip": ["4", 1]}},
        "7": {"class_type": "CLIPTextEncode", "inputs": {"text": negative, "clip": ["4", 1]}},
        "3": {
            "class_type": "KSampler",
            "inputs": {
                "seed": seed,
                "steps": steps,
                "cfg": cfg,
                "sampler_name": sampler,
                "scheduler": scheduler,
                "denoise": 1.0,
                "model": ["4", 0],
                "positive": ["6", 0],
                "negative": ["7", 0],
                "latent_image": ["5", 0],
            },
        },
        "8": {"class_type": "VAEDecode", "inputs": {"samples": ["3", 0], "vae": ["4", 2]}},
        "9": {"class_type": "SaveImage", "inputs": {"filename_prefix": filename_prefix, "images": ["8", 0]}},
    }


def queue_prompt(base_url: str, workflow: Dict[str, dict], client_id: str) -> str:
    response = request_json("POST", f"{base_url}/prompt", {"prompt": workflow, "client_id": client_id}, timeout=30)
    prompt_id = response.get("prompt_id")
    if not prompt_id:
        raise RuntimeError(f"ComfyUI did not return prompt_id: {response}")
    return prompt_id


def wait_for_output(base_url: str, prompt_id: str, timeout_seconds: int) -> List[dict]:
    deadline = time.time() + timeout_seconds
    while time.time() < deadline:
        history = request_json("GET", f"{base_url}/history/{prompt_id}", timeout=30)
        item = history.get(prompt_id)
        if item:
            outputs = item.get("outputs", {})
            images = []
            for output in outputs.values():
                images.extend(output.get("images", []))
            if images:
                return images
        time.sleep(1.0)
    raise TimeoutError(f"Timed out waiting for ComfyUI output for prompt {prompt_id}")


def download_comfy_image(base_url: str, image_info: dict, output_path: Path) -> None:
    query = urllib.parse.urlencode(
        {
            "filename": image_info["filename"],
            "subfolder": image_info.get("subfolder", ""),
            "type": image_info.get("type", "output"),
        }
    )
    data = request_bytes(f"{base_url}/view?{query}", timeout=60)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_bytes(data)


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


def backup_file(path: Path) -> Optional[Path]:
    if not path.exists():
        return None
    stamp = time.strftime("%Y%m%d_%H%M%S")
    backup = path.with_name(f"{path.stem}.backup_{stamp}{path.suffix}")
    backup.write_text(path.read_text(encoding="utf-8"), encoding="utf-8")
    return backup


def write_names(path: Path, names: Iterable[str], append: bool) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    new_lines = list(names)
    all_lines = read_existing_names(path) + new_lines if append and path.exists() else new_lines
    path.write_text("\n".join(all_lines) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate AI selfie profile pictures through local ComfyUI.")
    parser.add_argument("--count", type=int, default=25)
    parser.add_argument("--size", type=int, default=512)
    parser.add_argument("--pfp-dir", default="assets/pfp")
    parser.add_argument("--names-file", default="data/author_names.txt")
    parser.add_argument("--prefix", default="tg_ai_profile")
    parser.add_argument("--seed", default="threadgens-ai-profiles")
    parser.add_argument("--comfy-url", default="http://127.0.0.1:8188")
    parser.add_argument("--checkpoint", default="", help="ComfyUI checkpoint filename. Blank = first available checkpoint.")
    parser.add_argument("--steps", type=int, default=18)
    parser.add_argument("--cfg", type=float, default=6.5)
    parser.add_argument("--sampler", default="euler")
    parser.add_argument("--scheduler", default="normal")
    parser.add_argument("--style", default="not a celebrity, not a real person, believable casual selfie")
    parser.add_argument("--username-mode", choices=["random", "ollama"], default="random")
    parser.add_argument("--ollama-url", default="http://127.0.0.1:11434/api/generate")
    parser.add_argument("--ollama-model", default="llama3.1:8b")
    parser.add_argument("--append-names", action="store_true")
    parser.add_argument("--no-backup", action="store_true")
    parser.add_argument("--timeout", type=int, default=360)
    parser.add_argument("--list-checkpoints", action="store_true")
    args = parser.parse_args()

    if args.count <= 0:
        raise SystemExit("--count must be greater than 0")
    if args.size < 256:
        raise SystemExit("--size must be at least 256 for face avatars")

    base_url = args.comfy_url.rstrip("/")
    check_comfy(base_url)

    checkpoints = list_checkpoints(base_url)
    if args.list_checkpoints:
        print("Available checkpoints:")
        for checkpoint in checkpoints:
            print(f"- {checkpoint}")
        return

    checkpoint = args.checkpoint.strip()
    if not checkpoint:
        if not checkpoints:
            raise SystemExit("No ComfyUI checkpoints were found. Put a model in ComfyUI/models/checkpoints first.")
        checkpoint = checkpoints[0]
        print(f"No checkpoint entered. Using first available checkpoint: {checkpoint}")
    elif checkpoints and checkpoint not in checkpoints:
        print(f"Warning: checkpoint was not found in ComfyUI list: {checkpoint}")
        print("Continuing anyway because ComfyUI may still resolve it.")

    pfp_dir = Path(args.pfp_dir)
    names_file = Path(args.names_file)
    rng = random.Random(seed_int(args.seed + str(time.time_ns())))
    used_names = set(read_existing_names(names_file))

    if not args.append_names and not args.no_backup:
        backup = backup_file(names_file)
        if backup:
            print(f"Backed up existing usernames: {backup}")

    usernames = generate_usernames(args, rng, used_names)
    start_index = next_profile_index(pfp_dir, args.prefix)
    client_id = str(uuid.uuid4())

    print(f"ComfyUI: {base_url}")
    print(f"Checkpoint: {checkpoint}")
    print(f"Generating {args.count} AI selfie profile pictures into {pfp_dir}")

    for offset, username in enumerate(usernames):
        file_number = start_index + offset
        output_path = pfp_dir / f"{args.prefix}_{file_number:04d}.png"
        seed = seed_int(f"{username}-{time.time_ns()}") % 18446744073709551615
        prompt = build_prompt(username, rng, args.style)
        filename_prefix = f"threadgens_profile_{file_number:04d}"
        workflow = build_workflow(
            checkpoint=checkpoint,
            prompt=prompt,
            negative=NEGATIVE_PROMPT,
            seed=seed,
            size=args.size,
            steps=args.steps,
            cfg=args.cfg,
            sampler=args.sampler,
            scheduler=args.scheduler,
            filename_prefix=filename_prefix,
        )

        print(f"[{offset + 1:03d}/{args.count:03d}] queue {username} seed={seed}")
        prompt_id = queue_prompt(base_url, workflow, client_id)
        images = wait_for_output(base_url, prompt_id, args.timeout)
        download_comfy_image(base_url, images[0], output_path)
        print(f"[{offset + 1:03d}/{args.count:03d}] saved {username} -> {output_path}")

    write_names(names_file, usernames, append=args.append_names)
    print("Done.")
    print(f"Generated AI PNGs: {pfp_dir}")
    print(f"Username file: {names_file}")


if __name__ == "__main__":
    try:
        main()
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        raise SystemExit(f"ComfyUI HTTP error {exc.code}: {body}")
