# Neon Reaper 3.0 "DREAD" - audio

Everything you hear is synthesized in Kotlin at runtime: **no audio asset ships in the APK**. The direction is a premium online-slot
mix (sub thump + body + restrained sparkle, escalating stingers) fused with a dark-ambient horror-film score: tonal centre
**D minor / D Phrygian** (Eb), long D drones, bells with inharmonic partials, ghost choir, war drums, low brass. No music box, no giggles,
no cartoon boings, no cute major-key chimes. Sessions are long, so the top end is deliberately restrained (3-8 kHz is under 2 % of the
energy on average, above 8 kHz essentially nothing) and the score never repeats audibly (64 s loop, four layers).

## 1. Architecture

| File (`reaper/src/main/java/com/tjshea/neonreaper/audio/`) | Role |
|---|---|
| `Audio.kt` | The only Android-aware file: public contract (`Sfx`, `Track`, `play`, `playTrack`, ...), SoundPool for one-shots, streaming `AudioTrack` thread for the score, lazy background rendering, WAV cache. Every call is wrapped so a failure means silence, never a crash. |
| `Dsp.kt`, `Reverb.kt`, `Signal.kt` | Pure Kotlin DSP: RNG, band-limited saw / sine tables, biquad + TPT filters, stereo FDN reverb (half-rate for long tails), mixing `Bus`, mastering (loudness target, look-ahead limiter, EQ, fades), WAV I/O. |
| `Instruments.kt`, `Macros.kt` | Sound building blocks: sub thump, membrane drums, struck-bell resonator banks, bowed metal, saw pads, ghost choir (formant bank), brass, braam, organ, strings, formant voices, whispers, risers, coins, creaks, chains. |
| `SfxRecipesCore.kt`, `SfxRecipesWins.kt`, `SfxRecipesWorld.kt`, `AudioSpec.kt` | One recipe per `Sfx`, registered next to its spec (length, loudness target, hit time, auto-duck, priority tier). |
| `Scores.kt` | The three scores as perfectly periodic loop layers. |
| `MusicMixer.kt` | Real-time mixer core (no allocation, no Android): adaptive layer gains, cross-fade, duck, pause, widening of mono layers. |
| `AudioRender.kt` | Priority order of every asset, cache layout, the `renderAllTo` test hook. |

Only `Audio.kt` imports `android.*`; the rest is plain Kotlin and is unit-tested with plain JUnit.

**Playback paths.** One-shots: rendered to 16-bit 22.05 kHz WAV (mono for dry UI sounds, stereo for the rest), loaded into a
`SoundPool` (12 streams) and played with `play(sfx, volume, rate)`. Music: layers are decoded into `ShortArray`s and mixed by one thread
(`THREAD_PRIORITY_URGENT_AUDIO`, 1024-frame chunks, ~190 ms buffer) into a stereo `AudioTrack`. The thread parks while paused and is
released in `release()`; if the sink never blocks (emulators) the loop paces itself on the wall clock instead of spinning.

**Lazy rendering and cache.** On a real device a low-priority daemon thread renders in this order: core / UI one-shots (tier 0), the BASE
score (4 layers), win / reel feedback (tier 1), features and characters (tier 2), the SEANCE score, the VAULT score, the rest (tier 3).
Results are cached in `cacheDir/audio_v4` (older `audio_v1..v3` directories and a stale `.rev` generation are deleted); the next launch only
loads. Anything not rendered yet is skipped silently, and `play()` of a missing sound moves it to the front of the queue. BASE keeps its layers
in memory; SEANCE / VAULT are read from the cache when requested and dropped again after the cross-fade. Under JVM unit tests (`java.vm.name`
is not `Dalvik`) the constructor does not start any background work.

## 2. API summary

```kotlin
val audio = Audio(context)                       // inert under JVM tests, renders lazily on a device
audio.play(Audio.Sfx.WIN_TICK, volume = 0.5f, rate = Audio.scaleRate(step))   // rate 0.5..2, pitch + speed
audio.playTrack(Audio.Track.BASE)                // cross-fade ~1.5 s; same track again = resume
audio.setIntensity(0.35f)                        // 0..1, smoothed; BASE and SEANCE react
audio.duck(seconds = 1.2f, to = 0.35f)           // ~150 ms attack / ~600 ms release
audio.pauseMusic(); audio.musicOn = true; audio.sfxVolume = 1f; audio.musicVolume = 1f; audio.release()
```

Additive helpers: `Audio.scaleRate(step)` (rate that raises a D-based sound by `step` degrees of D natural minor: 0, 2, 3, 5, 7, 8, 10, 12 semitones,
clamped to 0.5..2), `Audio.hitSeconds(sfx)` (time of the main impact, for syncing banners / flashes), `Audio.durationSeconds(sfx)`,
`audio.stop(sfx)` (45 ms fade-out of the most recent instance), `audio.autoDuck` (default on).

**Retrigger guard.** Two identical triggers of the same sound at the same rate within 12 ms are merged (the second would only add +6 dB).
**Auto-duck.** Long stingers lower the music by themselves (see the `auto-duck` column); an explicit `duck()` can still be combined (the deeper
level and the longer hold win).

## 3. Sound list

Levels are whole-file RMS / peak after mastering (every one-shot peaks at or below -1.5 dBFS, i.e. no clipping; stacked `WIN_TICK`s stay far
below 0 dBFS). "hit at" is the time of the main impact; "tier" is the lazy-render priority. Lengths include the reverb tail. Every file starts
and ends with a raised-cosine fade (4 ms in, at least 4 ms out), so none clicks.

### UI and reels

| Sfx | length | hit at | RMS dBFS | peak dBFS | auto-duck | tier | what you hear |
|---|---|---|---|---|---|---|---|
| `CLICK` | 0.09 s | 0 | -24.2 | -9.8 | - | 0 | dry stone / wood tick |
| `PRESS` | 0.24 s | 0 | -21.0 | -9.0 | - | 0 | chunky mechanical button + metal tick |
| `STAKE_CHANGE` | 0.12 s | 0 | -24.1 | -12.2 | - | 0 | wood-block tick at D4 (rate = stake step) |
| `UI_OPEN` | 0.42 s | 0 | -23.0 | -9.5 | - | 0 | stone slide + soft whoosh + settle |
| `UI_CLOSE` | 0.32 s | 0 | -24.0 | -10.2 | - | 0 | reverse whoosh + stone shut |
| `SPIN_START` | 0.55 s | 0 | -18.0 | -4.8 | - | 0 | lever clunk, ratchet, chain, reels spin up |
| `SPIN` | 0.42 s | 0 | -22.0 | -9.5 | - | 0 | light reel whoosh (vault respins) |
| `STOP` | 0.30 s | 0 | -20.0 | -5.9 | - | 0 | reel stop: mid knock + sub + click |
| `REEL_THUD` | 0.48 s | 0 | -17.0 | -5.4 | - | 0 | heavy stop: sub drop, body, slap, iron clank |
| `WIN_TICK` | 0.12 s | 0 | -24.0 | -13.5 | - | 0 | dark mallet at D4 (pitch with `rate`, harmonic partials) |
| `TICK` | 0.09 s | 0 | -24.0 | -15.9 | - | 0 | dry mallet tick at D5 |
| `COUNT_END` | 0.95 s | 0 | -19.0 | -7.1 | - | 0 | soft low toll + sub + three-note shimmer |
| `ERROR` | 0.36 s | 0 | -21.0 | -11.0 | - | 0 | dull thud + minor-second buzz (D3/Eb3) |

### Wins

| Sfx | length | hit at | RMS dBFS | peak dBFS | auto-duck | tier | what you hear |
|---|---|---|---|---|---|---|---|
| `WIN_SMALL` | 0.72 s | 0.14 s | -19.0 | -5.7 | - | 0 | two glass notes, tolled hit, 3 coin pings |
| `WIN` | 1.00 s | 0.22 s | -18.0 | -4.5 | - | 0 | 3-note glass rise, toll, short choir, shimmer |
| `WIN_MED` | 1.50 s | 0.42 s | -17.0 | -3.9 | 1.1 s to 0.60 | 1 | 4-note rise, minor-to-major choir lift, braam, coins |
| `WIN_BIG` | 3.00 s | 1.00 s | -16.0 | -1.5 | 2.6 s to 0.45 | 1 | reverse choir swell + riser + drum roll, braam, major choir, toll, coins |
| `BIG_WIN` | 2.80 s | 0.70 s | -16.1 | -1.5 | 2.4 s to 0.50 | 1 | legacy: compact WIN_BIG (hit at 0.7 s) |
| `WIN_MEGA` | 4.50 s | 1.60 s | -15.1 | -1.5 | 4.0 s to 0.40 | 1 | longer build, double braam, 6-voice choir, brass motif, coin cascade |
| `WIN_EPIC` | 6.50 s | 2.40 s | -14.7 | -1.5 | 5.8 s to 0.36 | 1 | two hits (2.4 s, 4.4 s), thunder crack, brass fanfare, gongs |
| `WIN_APOCALYPSE` | 9.00 s | 3.20 s | -14.1 | -1.5 | 8.4 s to 0.30 | 1 | three hits (3.2 / 5.0 / 6.4 s), rumble, bell arpeggio, 8-voice choir, 6 s void tail |
| `FANFARE` | 2.30 s | 0 | -17.0 | -3.4 | 2.0 s to 0.50 | 1 | dark horn fanfare D-A-D-F, held D major, boom |
| `COINS` | 0.90 s | 0 | -21.0 | -4.6 | - | 1 | heavy soul coins on stone |
| `COIN_SHOWER` | 3.00 s | 0 | -20.0 | -2.6 | - | 1 | ~80 coins, density rises fast then decays, stone-pile thuds |

### Jackpots, purchases, meta

| Sfx | length | hit at | RMS dBFS | peak dBFS | auto-duck | tier | what you hear |
|---|---|---|---|---|---|---|---|
| `JACKPOT` | 2.40 s | 0.36 s | -16.6 | -1.5 | 2.0 s to 0.40 | 2 | legacy jackpot ping (orb): arpeggio + braam + choir |
| `JACKPOT_MINI` | 2.00 s | 0.32 s | -16.5 | -1.7 | 1.7 s to 0.40 | 2 | glass arpeggio, braam, choir, coins |
| `JACKPOT_MINOR` | 3.00 s | 0.50 s | -16.0 | -1.5 | 2.6 s to 0.40 | 2 | 6-note arpeggio, reverse swell, drum roll, braam |
| `JACKPOT_MAJOR` | 4.50 s | 0.80 s | -15.1 | -1.5 | 4.0 s to 0.40 | 2 | adds brass motif and a bigger hall |
| `JACKPOT_GRAND` | 6.00 s | 1.20 s | -14.2 | -1.5 | 5.5 s to 0.40 | 2 | second hit with gong + 7-voice choir, 34 coins, cathedral tail |
| `BUY_CONFIRM` | 1.60 s | 0.50 s | -17.0 | -3.3 | 1.2 s to 0.55 | 1 | 6 heavy coin clinks, war drum, horn, choir, toll |
| `RANK_UP` | 2.40 s | 0.90 s | -17.0 | -2.5 | 1.8 s to 0.60 | 3 | solemn horn rise, major choir, toll, sparkle |
| `ACHIEVEMENT` | 1.60 s | 0.15 s | -19.0 | -4.4 | - | 3 | toll + glass cluster + choir shimmer |
| `REALITY_CHECK` | 2.40 s | 0 | -22.0 | -10.4 | - | 3 | gentle low bell + bowed tone (calm on purpose) |
| `RELIC` | 1.40 s | 0.40 s | -19.0 | -5.8 | - | 1 | ascending glass bells, whoosh, low toll, whisper |
| `RELIC_FLY` | 0.75 s | 0.55 s | -22.0 | -6.4 | - | 1 | rising shimmer streak ending in a glass ping |
| `BELL` | 2.90 s | 0 | -20.0 | -6.3 | - | 2 | legacy tolling bell D3 |

### Scatter, Seance, tension, vault

| Sfx | length | hit at | RMS dBFS | peak dBFS | auto-duck | tier | what you hear |
|---|---|---|---|---|---|---|---|
| `SCATTER_LAND` | 0.78 s | 0 | -18.0 | -6.6 | - | 1 | coffin lands: wood thud, hollow resonance, D4 church bell (pitch with `rate`) |
| `COFFIN_OPEN` | 2.60 s | 1.50 s | -16.6 | -1.5 | 2.2 s to 0.50 | 2 | stick-slip creak, lid thud, minor choir swell, soul whoosh, boom |
| `SEANCE_START` | 4.70 s | 2.90 s | -15.1 | -1.5 | 4.2 s to 0.40 | 1 | whispered chant, reverse choir, drum roll, braam + major choir + gong, tolls |
| `SEANCE_MULT_UP` | 0.62 s | 0 | -19.0 | -7.4 | - | 1 | horn stab + glass note + whoosh + thump (pitch with `rate`) |
| `SEANCE_RETRIGGER` | 1.30 s | 0 | -18.0 | -3.0 | 1.0 s to 0.55 | 1 | double toll + choir sting + shimmer |
| `SEANCE_END` | 3.10 s | 0 | -17.0 | -3.7 | 2.6 s to 0.50 | 1 | low toll, boom, falling choir, souls leaving |
| `ANTICIPATE` | 2.50 s | 2.50 s | -19.0 | -3.3 | 2.4 s to 0.70 | 1 | rising riser, accelerating heartbeat, reverse bell swell (no resolution) |
| `ANTICIPATE_HIT` | 2.10 s | 0 | -15.0 | -1.5 | 1.8 s to 0.45 | 1 | boom + braam + major choir + gong + shimmer |
| `ANTICIPATE_MISS` | 0.85 s | 0 | -20.0 | -8.7 | - | 1 | deflating sub drop + falling hiss + sigh |
| `VAULT` | 3.40 s | 2.70 s | -16.0 | -1.5 | 3.0 s to 0.50 | 2 | legacy vault trigger: tolls, rumble, reverse choir, boom (hit 2.7 s) |
| `VAULT_DOOR` | 3.60 s | 2.40 s | -16.1 | -1.5 | 3.2 s to 0.50 | 2 | stone grind, chains, bolt thunks, deep boom + toll + choir |
| `ORB` | 0.62 s | 0 | -21.0 | -11.9 | - | 1 | legacy orb: glass bell A4 + coin ping |
| `ORB_LAND` | 0.60 s | 0 | -21.0 | -10.8 | - | 1 | glass bell D4 + coin ping (pitch with `rate`) |

### Characters

| Sfx | length | hit at | RMS dBFS | peak dBFS | auto-duck | tier | what you hear |
|---|---|---|---|---|---|---|---|
| `REAPER_WHISPER` | 1.80 s | 0 | -19.0 | -2.7 | - | 2 | three whispered syllables + faint murmur (stereo pair) |
| `REAPER_LAUGH` | 2.50 s | 0 | -17.6 | -1.5 | - | 2 | 5 slow falling giant-vocal-tract HA |
| `LAUGH` | 2.00 s | 0 | -17.7 | -1.5 | - | 2 | legacy: 4-syllable deep laugh |
| `CACKLE` | 1.30 s | 0 | -19.1 | -1.8 | - | 2 | low demonic chuckle (8 short breathy pulses) |
| `GIGGLE` | 1.60 s | 0 | -25.0 | -10.4 | - | 2 | faint far-off whispered 'hi hi' in a cathedral |
| `DOCTOR_RASP` | 1.90 s | 0 | -20.0 | -5.1 | - | 2 | mask inhale + exhale, wheeze, chest rattle |
| `DEMON_ROAR` | 2.10 s | 0 | -15.5 | -1.8 | 1.7 s to 0.60 | 2 | sub-harmonic growl with 36 Hz roughness + boom |
| `GROWL` | 1.40 s | 0 | -17.0 | -1.6 | - | 2 | legacy: lighter beast growl |
| `WRAITH_WAIL` | 2.60 s | 0 | -17.0 | -1.8 | 2.0 s to 0.60 | 2 | gliding banshee vowels, dissonant second voice, cathedral |
| `CAW` | 0.95 s | 0 | -19.0 | -4.5 | - | 2 | legacy: two hoarse low caws |
| `RAVEN_FLOCK` | 2.50 s | 0 | -19.0 | -5.2 | - | 2 | six caws + wing flaps, panned |
| `SKULL_RATTLE` | 1.30 s | 0 | -18.0 | -2.0 | - | 2 | accelerating/decelerating bone clicks |
| `CHATTER` | 0.95 s | 0 | -19.0 | -3.2 | - | 2 | legacy: teeth chatter (13 clicks) |

### World and features

| Sfx | length | hit at | RMS dBFS | peak dBFS | auto-duck | tier | what you hear |
|---|---|---|---|---|---|---|---|
| `BONE_CRACK` | 0.45 s | 0 | -16.3 | -1.5 | - | 2 | sharp snap + wood resonance |
| `CHAIN_RATTLE` | 1.15 s | 0 | -19.0 | -2.7 | - | 2 | random metal clinks, swelling |
| `LIGHTNING_CRACK` | 1.70 s | 0 | -16.1 | -1.5 | - | 2 | sharp crack, sizzle, rolling rumble |
| `DOOR_SLAM` | 1.30 s | 0 | -16.0 | -3.4 | - | 2 | heavy door: thump, wood crack, iron ring, rebound |
| `WIND_GUST` | 2.30 s | 0 | -22.0 | -6.7 | - | 3 | moving band-pass wind + whistle + rumble |
| `CANDLE_WHOOSH` | 0.80 s | 0 | -22.0 | -8.9 | - | 3 | flame whoosh + poof + crackle |
| `THUNDER` | 2.80 s | 0.05 s | -17.0 | -1.5 | - | 2 | crack, rolling rumble, boom |
| `SLASH` | 0.42 s | 0 | -17.0 | -2.7 | - | 2 | blade swish + metal ring + thud |
| `WHOOSH` | 0.62 s | 0 | -22.0 | -7.8 | - | 2 | soft pass-by |
| `WILD_LAND` | 0.90 s | 0.08 s | -17.0 | -2.0 | - | 1 | blade slash, impact, growl hint |
| `MINI_INTRO` | 1.40 s | 0.50 s | -18.2 | -1.5 | - | 2 | whoosh, whisper, boom, toll |
| `HEARTBEAT` | 0.75 s | 0 | -20.0 | -2.9 | - | 1 | lub-dub (mid knock + sub) |



Wins escalate clearly in length, weight and width: **WIN_SMALL 0.72 s < WIN 1.0 s < WIN_MED 1.5 s < WIN_BIG 3.0 s < WIN_MEGA 4.5 s < WIN_EPIC 6.5 s <
WIN_APOCALYPSE 9.0 s**, each a build-up, a big hit and a decaying tail. Jackpots escalate 2 s / 3 s / 4.5 s / 6 s. Voices are 0.8-2.5 s.

## 4. Music

All layers are rendered mono or stereo at 22.05 kHz / 16 bit, are exactly one loop long, and share one read position, so they are sample aligned
and can be cross-faded freely. Loops are seamless by construction: oscillator frequencies are snapped to whole cycles per loop (with a
discarded warm-up so filters are settled), notes that cross the end are wrapped, the reverb tail is folded back onto the start (the exact
periodic steady state of a linear reverb), noise textures are folded with an equal-power cross-fade, and the circular EQ is pre-rolled on the
end of the loop. Mono layers get cheap stereo widening at playback (two opposite first-order all-passes, bass stays centred).

| Track | Tempo / length | Layers (cumulative, fade in with `setIntensity`) |
|---|---|---|
| `BASE` | 75 BPM, 20 bars = **64.0 s** | **L0** always: D1 sub + D2/A2 saw drone (slow filter LFO), wind, six sparse whispers, a distant tolling bell. **L1** from 0.15 (full by 0.35): lub-dub heartbeat on every beat (breathing gaps), sub pulses every two bars, metal scrapes / chain drag / anvil. **L2** from 0.40 (full by 0.60): ghost-choir swells over the harmony, bowed metal, reverse-bell swells, high shimmer. **L3** from 0.70 (full by 0.90): war-drum ostinato (two-bar cell, fills every fourth bar), low brass swells, a staccato brass riff in D Phrygian and "hah" stabs. |
| `SEANCE` | 90 BPM, 16 bars = **42.67 s** | **L0 + L1 always on** (ritual drone, low chant, toll; toms, hand drum, shaker, "hah" syllables). **L2** from 0.30: tremolo strings ostinato + sung chant syllables. **L3** from 0.65 (full by 0.92): frenzy - sixteenth-note drum rolls, rising string glissandi with tremolo, brass stabs, risers into the loop point, gongs. |
| `VAULT` | 108 BPM, 16 bars = **35.56 s** | One epic-dark mix: organ chords and pulsing organ bass, choir melody (D minor, A major turnaround), war drums with fills, brass stabs, reverse-noise risers, tolls. Ignores intensity. |

Harmony: a D pedal under slowly moving chords (BASE: Dm - Bbmaj7/D - Gm - Eb (the Phrygian second, the dread chord) - Dm - Am - Bb - C - A - Eb/D
back into Dm). Tempo grids are exact in samples (75 BPM = 17 640 samples per beat), so loops land on bar lines.

**Adaptive rules (implemented in `MusicMixer`)**
* Layer 0 is always on. Layer k fades in over `fadeSpan` as intensity passes `fadeStart` (smoothstep): BASE 0.15 / 0.40 / 0.70 (span 0.2),
  SEANCE 0 / 0 / 0.30 / 0.65 (span 0.25 / 0.27).
* Layer gains follow the target with a one-pole filter: **1.3 s time constant up, 2.2 s down** (never a pump, never an overshoot). A layer
  that is already loaded when a track starts begins at its target (the cross-fade does the fading); a layer that arrives late (lazy
  rendering) fades in from silence.
* `playTrack` cross-fades 1.5 s with equal-power curves (the first track fades in over 1.2 s). The new track starts at its beginning.
* `duck(seconds, to)`: gain follows with 50 ms (attack, ~150 ms to 95 %) and 200 ms (release, ~600 ms to 95 %) time constants, holds for
  `seconds`; overlapping ducks keep the deeper level and the longer hold.
* Pause fades out in ~60 ms, then the AudioTrack is paused and flushed; resume fades in ~250 ms.

Suggested intensity: idle / menus 0.05, reels spinning 0.35, anticipation or a running feature 0.7, big win 1.0; SEANCE 0.5 on entry, 0.8
with a rising multiplier, 1.0 on retrigger / last spins. Set it on state changes, not every frame.

## 5. Measured numbers (this sandbox)

* `AUDIO RENDER: 83 files, wall 4247 ms, thread cpu 3425 ms, 43.03 MB`
* One-shots: 74 files, 11.33 MB on disk (<= 12 MB budget), longest 9.0 s (WIN_APOCALYPSE, 775 KB decoded, under SoundPool's 1 MB per sound).
* Music: 9 layer files, 31.70 MB decoded (<= 40 MB budget).
* Summed music at full intensity: .BASE: 4 layers, loop 64.00 s, all layers on RMS -22.2 dBFS; SEANCE: 4 layers, loop 42.67 s, all layers on RMS -21.1 dBFS; VAULT: 1 layers, loop 35.56 s, all layers on RMS -22.0 dBFS (target about -22 dBFS).

| Music layer | channels | length | size | RMS dBFS | peak dBFS |
|---|---|---|---|---|---|
| `music_BASE_L0` | stereo | 64.00 s | 5.38 MB | -28.0 | -10.7 |
| `music_BASE_L1` | mono | 64.00 s | 2.69 MB | -30.0 | -13.6 |
| `music_BASE_L2` | stereo | 64.00 s | 5.38 MB | -28.0 | -9.8 |
| `music_BASE_L3` | mono | 64.00 s | 2.69 MB | -27.0 | -13.0 |
| `music_SEANCE_L0` | stereo | 42.67 s | 3.59 MB | -28.0 | -9.9 |
| `music_SEANCE_L1` | mono | 42.67 s | 1.79 MB | -27.0 | -14.0 |
| `music_SEANCE_L2` | stereo | 42.67 s | 3.59 MB | -27.5 | -10.6 |
| `music_SEANCE_L3` | stereo | 42.67 s | 3.59 MB | -26.5 | -12.1 |
| `music_VAULT_L0` | stereo | 35.56 s | 2.99 MB | -22.0 | -6.1 |

## 6. Re-rendering and QA

```
gradle :reaper:testDebugUnitTest --tests 'com.tjshea.neonreaper.audio.*'     # renders everything into reaper/build/audio and asserts
python3 tools/audio_report.py                                                 # table + spectrogram sheets in reaper/build/audio_report
python3 tools/audio_report.py --pitch --only music_BASE                       # strongest spectral peaks as note names (tonal centre check)
```

`AudioQaTest` (plain JUnit, about 5 s) asserts per one-shot: peak <= -1 dBFS, RMS within 2 dB of its target, DC < 0.002, first / last sample
under -60 dBFS, length inside its window, decoded size under 1 MB (SoundPool limit); the brief's timings and the win / jackpot escalation; for
music: loop length, layer counts, sample-exact tempo grids, per-layer RMS, summed RMS about -22 dBFS, seam step below the local step, level
continuity across the seam; and the budgets: all assets render in at most 4 s of CPU, SFX files <= 12 MB, music <= 40 MB. `MusicMixerTest`
covers cumulative layer fades, the 1.3 s time constant, duck timings, cross-fade without dip, pause / resume, sample-accurate wrap and late
layers. `AudioDspTest` covers reverb gain normalisation, folded loop tails, the limiter, WAV round trip and alias-free oscillators.
`AudioWrapperTest` (Robolectric) calls the facade in every order and checks that the lazy pipeline starts the BASE score.

Everything is deterministic (seeded RNG): the same bytes every run. To change a sound, edit its recipe, bump `AudioRender.REV` so caches on
devices are rebuilt, and re-run the QA test and the report. Review checklist for the PNGs: risers rise, drum patterns are periodic, wins
grow, no unintended silence inside a layer, nothing above about 8 kHz dominates.

## 7. Budgets

* CPU: all 83 assets (74 one-shots, 9 loop layers) render in <= 4 s of CPU on this 4-core sandbox, cold JVM. Real target devices (MediaTek
  MT8168 tablets, Android 10 Go) are up to ~8x slower, hence the lazy low-priority rendering in priority order and the cache.
* Memory: music <= 40 MB decoded (22.05 kHz, 16 bit); BASE stays resident, one other track at a time. SFX files <= 12 MB, each under 1 MB
  decoded (SoundPool).
* Real time: the mixer renders 1024-frame chunks, ~12 flops per widened layer sample, no allocation in the loop.

## 8. Requests to the UI integrator

1. **Use the new names and pick one stinger per win.** Do not stack the legacy `FANFARE` + `BIG_WIN` + `LAUGH` any more. Suggested mapping for
   the multiplier of a win: `< 2x` `WIN_SMALL`, `2-3x` `WIN`, `3-10x` `WIN_MED`, `10x` `WIN_BIG`, `25x` `WIN_MEGA`, `50x` `WIN_EPIC`,
   `100x` `WIN_APOCALYPSE`; optionally add a character voice (`REAPER_LAUGH`, `DEMON_ROAR`, ...) on top at a lower volume.
2. **Sync to the hit.** Stingers are build-up, hit, tail. Start the banner slam / camera shake / flash at `Audio.hitSeconds(sfx)` after the
   `play()` call (WIN_BIG 1.0 s, WIN_MEGA 1.6 s, WIN_EPIC 2.4 s, WIN_APOCALYPSE 3.2 s, JACKPOT_* 0.3-1.2 s), and keep the count-up ticking
   under the build.
3. **Count-up.** `WIN_TICK` every 70-150 ms with `rate = Audio.scaleRate(step)` rising a scale step per tick (0, 1, 2, ... up to about 9);
   it stays musical and cannot clip when stacked. `COUNT_END` (<= 1 s) when the number lands. `TICK` is the dry legacy tick.
4. **Reels.** `PRESS` on the spin button, `SPIN_START` when the reels start (<= 0.55 s), `STOP` per reel, `REEL_THUD` instead of `STOP` for
   wild / scatter / big symbols, `SCATTER_LAND` with `rate = scaleRate(landedCount - 1)` (first, second, third coffin), `ORB_LAND` with
   `rate = scaleRate(step)`, `STAKE_CHANGE` with `rate = scaleRate(stakeIndex)`.
5. **Tension.** Re-trigger `ANTICIPATE` (2.5 s) while the last reels spin and call `audio.stop(Sfx.ANTICIPATE)` just before `ANTICIPATE_HIT`
   or `ANTICIPATE_MISS`.
6. **Do not call `duck()` for stingers**: `autoDuck` (default on) already ducks the music for every long stinger and voice. Call `duck()` only for
   custom moments (a dialog, a long voice line).
7. **Intensity** (see section 4): `setIntensity` on state changes; 0.05 idle, 0.35 spinning, 0.7 anticipation / feature, 1.0 big win; SEANCE as above.
   Switch the score with `playTrack`: play `SEANCE_START` first and call `playTrack(Track.SEANCE)` at its hit (2.9 s) - the 1.5 s cross-fade is
   hidden under the stinger; the same for `VAULT` (`VAULT_DOOR` hit at 2.4 s) and back to BASE after `SEANCE_END`.
8. **Lifecycle** is unchanged: `pauseMusic()` in `onPause`, `playTrack(current)` in `onResume`, `release()` in `onDestroy`.
9. **First launch.** Assets render in the background (core sounds first, the BASE score within a few seconds on a fast device, up to ~30 s
   on the slowest tablets) and are cached; a sound requested before it exists is skipped silently. Do not rely on instant audio in screenshot
   tests: `Audio(ctx)` is inert under JVM tests by design.
10. Optional: `Audio.durationSeconds(sfx)` for lock windows (e.g. the win lock), `audio.musicVolume` / `audio.sfxVolume` are 0..1 and smoothed.

## 9. Caveats

* Everything was designed and verified analytically (spectrograms, levels, spectral peaks, loop seams), not by ear: the final sign-off on taste
  (laugh / whisper voices, brass, coin brightness) should be done on the real tablet speaker; every recipe is a few lines in
  `SfxRecipes*.kt` / `Scores.kt` and can be re-voiced quickly.
* Tablet speakers do not reproduce much below ~150 Hz; the one-shots are mastered with a gentle bass shelf and carry their punch in the
  150-800 Hz body, with the sub as a bonus on phones and headphones.
* Sub-bass and the low drones are band-limited saws / sines at 22.05 kHz; the top octave of the SoundPool resampling for `rate` near 2 is
  linear interpolation (fine for the short tonal ticks it is used for).
