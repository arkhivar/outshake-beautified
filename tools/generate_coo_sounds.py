#!/usr/bin/env python3
"""Original Outshake pigeon/seed miniatures, generated without samples or dependencies.

Run: python3 tools/generate_coo_sounds.py
QA:  python3 tools/generate_coo_sounds.py --check

24 kHz mono signed 16-bit PCM WAV; fixed PRNG seeds; no external recordings or models.
The voice is a breathy, formant-shaped harmonic coo with slow pitch sag, tiny throat
flutter and a low subharmonic, not a fixed-frequency notification oscillator. Seed
grains use filtered noise and damped inharmonic wood resonances. Raised-cosine
attacks/releases and ample peak headroom avoid clicks and clipping. No long reverb,
loops, bells, or stereo tricks: intimate and unobtrusive on a small phone speaker.
All sounds are authored for this app and may be redistributed with it.
"""

import argparse
import hashlib
import io
import math
from pathlib import Path
import random
import struct
import wave

RATE = 24_000
TAU = 2 * math.pi
OUTPUT = Path(__file__).resolve().parents[1] / "app/src/main/res/raw"


def taper(t, duration, attack=0.04, release=0.11):
    """Zero-valued endpoints and smooth derivatives; no rectangular gates."""
    if t <= 0 or t >= duration:
        return 0.0
    fade_in = 0.5 - 0.5 * math.cos(math.pi * min(t / attack, 1.0))
    fade_out = 0.5 - 0.5 * math.cos(math.pi * min((duration - t) / release, 1.0))
    return fade_in * fade_out


def coo(track, rng, start, duration, pitch, sag, amplitude):
    phase = 0.0
    breath_low = 0.0
    breath_slow = 0.0
    for i in range(round(duration * RATE)):
        t = i / RATE
        u = t / duration
        # A living, non-musical contour: slight opening lift, then a settling exhalation.
        frequency = pitch + 9.0 * math.sin(math.pi * u) - sag * u
        frequency *= 1.0 + 0.008 * math.sin(TAU * 5.3 * t + 0.7)
        frequency += 0.8 * math.sin(TAU * 13.7 * t)
        phase += TAU * frequency / RATE
        formant = 435 + 75 * math.sin(math.pi * u) - 45 * u
        voice = 0.0
        for harmonic in range(1, 9):
            hz = harmonic * frequency
            throat = math.exp(-0.5 * ((hz - formant) / 190) ** 2)
            upper = 0.16 * math.exp(-0.5 * ((hz - 1030) / 240) ** 2)
            weight = (0.22 + throat + upper) / harmonic ** 1.28
            voice += weight * math.sin(harmonic * phase + 0.13 * harmonic)
        voice += 0.055 * math.sin(phase * 0.5 + 0.3)
        noise = rng.uniform(-1.0, 1.0)
        breath_low += 0.19 * (noise - breath_low)
        breath_slow += 0.027 * (noise - breath_slow)
        breath = breath_low - breath_slow
        # Rounded pulses and an airy onset avoid a pure sine-wave "message received" sound.
        chest = 0.90 + 0.065 * math.sin(TAU * 22.4 * t + 0.6 * math.sin(TAU * 3 * t))
        exhale = 0.085 + 0.07 * (1.0 - u)
        envelope = taper(t, duration, min(0.055, duration * 0.25), min(0.14, duration * 0.45))
        sample = amplitude * envelope * ((voice * chest) + exhale * breath)
        track[round(start * RATE) + i] += sample


def seed_tick(track, rng, start, amplitude):
    """A tiny rolled seed: noisy, woody, and irregular, not a high metallic click."""
    duration = rng.uniform(0.023, 0.038)
    resonances = (rng.uniform(680, 850), rng.uniform(1130, 1310), rng.uniform(1740, 1990))
    smoothed = 0.0
    for i in range(round(duration * RATE)):
        t = i / RATE
        noise = rng.uniform(-1.0, 1.0)
        smoothed += 0.37 * (noise - smoothed)
        woody = sum(math.sin(TAU * f * t) * g for f, g in zip(resonances, (0.55, 0.24, 0.10)))
        grain = taper(t, duration, 0.0025, 0.012) * math.exp(-t / 0.008)
        track[round(start * RATE) + i] += amplitude * grain * (0.68 * smoothed + 0.32 * woody)


def render(name):
    if name == "pigeon_feed":
        # Two soft "coo, coo" syllables and a few close seed grains.
        duration, peak, seed = 1.04, 0.44, 80421
        voices = [(0.11, 0.26, 218, 16, 0.74), (0.42, 0.50, 205, 28, 1.0)]
        ticks = [(0.025, 0.28), (0.068, 0.19), (0.352, 0.14)]
    elif name == "pigeon_rest":
        # One lower, falling exhalation: the bird settles, without an error/alarm tone.
        duration, peak, seed = 0.88, 0.40, 80422
        voices = [(0.055, 0.70, 192, 34, 1.0)]
        ticks = [(0.026, 0.12)]
    elif name == "pigeon_pet":
        # A little closed-mouth murmur and two soft pecks, deliberately the quietest cue.
        duration, peak, seed = 0.44, 0.30, 80423
        voices = [(0.065, 0.235, 232, 19, 0.62)]
        ticks = [(0.018, 0.28), (0.314, 0.20), (0.354, 0.09)]
    else:
        raise ValueError(name)
    rng = random.Random(seed)
    track = [0.0] * round(duration * RATE)
    for args in voices:
        coo(track, rng, *args)
    for args in ticks:
        seed_tick(track, rng, *args)
    # Gentle DC blocking, then a final taper including any filter tail.
    previous_in = previous_out = 0.0
    for i, sample in enumerate(track):
        filtered = sample - previous_in + 0.995 * previous_out
        previous_in, previous_out = sample, filtered
        track[i] = filtered * taper(i / RATE, duration - 1 / RATE, 0.009, 0.045)
    scale = peak / max(abs(value) for value in track)
    pcm = [round(value * scale * 32767) for value in track]
    assert duration <= 1.2
    assert max(abs(value) for value in pcm) < 32767
    assert pcm[0] == pcm[-1] == 0
    assert abs(sum(pcm) / len(pcm) / 32768) < 0.002
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(RATE)
        wav.writeframes(struct.pack(f"<{len(pcm)}h", *pcm))
    data = buffer.getvalue()
    rms = math.sqrt(sum((value / 32768) ** 2 for value in pcm) / len(pcm))
    print(f"{name}: {duration:.2f}s, peak {20 * math.log10(peak):.2f} dBFS, "
          f"RMS {20 * math.log10(rms):.2f} dBFS, {len(data)} bytes, "
          f"sha256 {hashlib.sha256(data).hexdigest()}")
    return data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="verify checked-in WAVs without writing")
    args = parser.parse_args()
    for name in ("pigeon_feed", "pigeon_rest", "pigeon_pet"):
        data = render(name)
        target = OUTPUT / f"{name}.wav"
        if args.check:
            if not target.exists() or target.read_bytes() != data:
                raise SystemExit(f"Missing or non-reproducible audio: {target}")
        else:
            OUTPUT.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
    print("All cues verified." if args.check else "Original PCM cues generated.")


if __name__ == "__main__":
    main()
