#!/usr/bin/env node
// UC31 — Placeholder audio generator (ADR 0020).
//
// Synthesises the short PLACEHOLDER sound effects + ambient music loops the game ships until real,
// licensed clips are sourced. Pure Node stdlib (no deps): writes 16-bit mono PCM WAVs that libGDX's
// Sound/Music can load directly. Run from the repo root:
//
//     node tools/gen-audio-placeholders.mjs
//
// Outputs (paths match LibGdxAudioService's `audio/sfx/<name>.wav` / `audio/music/<name>.wav`):
//   assets/audio/sfx/{thrust,weapon_fire,enemy_hit,enemy_destroyed,mining_tick,dock,jump,ui_tap,
//                     mission_accept,mission_complete}.wav
//   assets/audio/music/{flight,station}.wav
//
// These are intentionally simple synthesised tones/blips — placeholder content only. The audio SYSTEM
// (service, event wiring, settings, headless-safety) is real and tested; only the clip *content* is a
// placeholder. THRUST and the music tracks are authored as seamless loops (integer cycle counts).

import { writeFileSync, mkdirSync } from 'node:fs'
import { dirname } from 'node:path'

const SR = 22050 // sample rate (Hz) — small files, ample for placeholder cues

const TAU = Math.PI * 2
const clamp = (x, lo, hi) => Math.max(lo, Math.min(hi, x))
// Deterministic pseudo-noise so regenerating produces byte-identical files (no Math.random()).
let seed = 0x9e3779b9
const noise = () => {
  seed = (seed * 1664525 + 1013904223) >>> 0
  return (seed / 0xffffffff) * 2 - 1
}

const sine = (f, t) => Math.sin(TAU * f * t)
const seconds = (n) => Math.round(n * SR)

// Write a float[-1,1] sample array to a 16-bit mono PCM WAV.
function writeWav(path, samples) {
  const n = samples.length
  const buf = Buffer.alloc(44 + n * 2)
  buf.write('RIFF', 0)
  buf.writeUInt32LE(36 + n * 2, 4)
  buf.write('WAVE', 8)
  buf.write('fmt ', 12)
  buf.writeUInt32LE(16, 16) // PCM fmt chunk size
  buf.writeUInt16LE(1, 20) // audio format = PCM
  buf.writeUInt16LE(1, 22) // channels = mono
  buf.writeUInt32LE(SR, 24)
  buf.writeUInt32LE(SR * 2, 28) // byte rate
  buf.writeUInt16LE(2, 32) // block align
  buf.writeUInt16LE(16, 34) // bits per sample
  buf.write('data', 36)
  buf.writeUInt32LE(n * 2, 40)
  for (let i = 0; i < n; i++) {
    buf.writeInt16LE(Math.round(clamp(samples[i], -1, 1) * 32767), 44 + i * 2)
  }
  mkdirSync(dirname(path), { recursive: true })
  writeFileSync(path, buf)
  console.log(`wrote ${path} (${(buf.length / 1024).toFixed(1)} KiB, ${(n / SR).toFixed(2)}s)`)
}

// Normalise to a target peak so nothing clips and levels are even across cues.
function normalize(samples, peak = 0.7) {
  let max = 0
  for (const s of samples) max = Math.max(max, Math.abs(s))
  if (max < 1e-6) return samples
  const g = peak / max
  return samples.map((s) => s * g)
}

// One-shot cue: `gen(t)` produces the raw signal, shaped by an exponential decay envelope.
function oneShot(durS, gen, { decay = 6, peak = 0.7 } = {}) {
  const n = seconds(durS)
  const out = new Array(n)
  for (let i = 0; i < n; i++) {
    const t = i / SR
    out[i] = gen(t) * Math.exp(-decay * (t / durS))
  }
  return normalize(out, peak)
}

// Seamless loop: integer cycle counts in `gen` keep the end meeting the start (no click on wrap).
function loop(durS, gen, peak = 0.45) {
  const n = seconds(durS)
  const out = new Array(n)
  for (let i = 0; i < n; i++) out[i] = gen(i / SR)
  return normalize(out, peak)
}

const sweep = (f0, f1, t, durS) => sine(f0 + (f1 - f0) * (t / durS), t)

const SFX = {
  // Looping engine rumble (0.5s → 35 cycles @70Hz, 55 @110Hz: seamless). Held while thrusting.
  thrust: loop(0.5, (t) => 0.7 * sine(70, t) + 0.5 * sine(110, t) + 0.12 * noise(), 0.5),
  // Pew: fast downward zap.
  weapon_fire: oneShot(0.13, (t) => sweep(900, 300, t, 0.13), { decay: 7 }),
  // Short bright impact tick + a little grit.
  enemy_hit: oneShot(0.1, (t) => 0.6 * sine(420, t) + 0.5 * noise(), { decay: 9 }),
  // Explosion: noise body with a downward boom.
  enemy_destroyed: oneShot(0.45, (t) => 0.7 * noise() + 0.5 * sweep(260, 70, t, 0.45), { decay: 5 }),
  // Tiny high tick per productive mining pulse.
  mining_tick: oneShot(0.06, (t) => sine(1200, t), { decay: 12 }),
  // Two-tone confirming docking.
  dock: oneShot(0.3, (t) => sine(t < 0.12 ? 440 : 660, t), { decay: 4 }),
  // Whoosh up through the gate.
  jump: oneShot(0.4, (t) => sweep(200, 1200, t, 0.4) * 0.9 + 0.15 * noise(), { decay: 3.5 }),
  // Crisp UI click.
  ui_tap: oneShot(0.05, (t) => sine(1000, t), { decay: 14 }),
  // Rising two-note accept.
  mission_accept: oneShot(0.3, (t) => sine(t < 0.15 ? 523 : 659, t), { decay: 4 }),
  // Three-note arpeggio for completion.
  mission_complete: oneShot(0.5, (t) => sine(t < 0.16 ? 523 : t < 0.33 ? 659 : 784, t), { decay: 3 }),
}

// 4s pads → any integer freq + the 0.25Hz LFO complete whole cycles, so the loop is seamless.
const pad = (freqs, lfoHz) => (t) => {
  const lfo = 0.8 + 0.2 * sine(lfoHz, t)
  let s = 0
  for (const f of freqs) s += sine(f, t)
  return (s / freqs.length) * lfo
}
const MUSIC = {
  flight: loop(4, pad([110, 165, 220], 0.25), 0.4), // A-minor-ish roaming pad
  station: loop(4, pad([131, 196, 262], 0.25), 0.4), // warmer C pad at the station
}

for (const [name, samples] of Object.entries(SFX)) writeWav(`assets/audio/sfx/${name}.wav`, samples)
for (const [name, samples] of Object.entries(MUSIC)) writeWav(`assets/audio/music/${name}.wav`, samples)
console.log('done — placeholder audio regenerated')
