# Neon Reaper 3.0 "DREAD" — device-risk review (what the headless tests cannot see)

Status: the independent reviewer was **stopped by a usage limit before it wrote its own report**. This file records what its saved work
contained (a working-tree snapshot, merged as commit a1d4d8c) plus what the integrator verified and changed afterwards. Nothing here was
run on a real tablet; the numbers come from the headless Mesa renderer (llvmpipe, a software GPU) and Robolectric.

## Bugs found and fixed

| # | severity | where | what went wrong | fix | test |
|---|---|---|---|---|---|
| R1 | medium (money display) | `ReaperView.animateSpin` | every free spin reset `pendingVault`: when one spin unlocked BOTH a Séance and a Soul Vault, the first free spin revealed the Vault payout in the credit meter before the Vault screen had been shown (also after an app restart: `resumeSeance` did not re-hide it) | free spins no longer touch `pendingVault`; `resumeSeance` re-hides a pending Vault | `ReviewFlowTest.aVaultUnlockedTogetherWithASeanceStaysHiddenUntilItIsShown`, `aResumedSeanceWithAPendingVaultKeepsTheVaultHidden` |
| R2 | medium (OOM / crash) | `audio/Audio.kt` first-launch render | the synthesiser needs ~32 bytes of heap per output frame (≈45 MB for a 64 s BASE layer); on a 96–128 MB app heap a render running next to the game could throw `OutOfMemoryError` on ANY thread | `AudioRender.fits(job, freeHeap)`: an asset that does not fit is skipped (silent) and retried at the next launch | `ReviewAudioGuardTest` |
| R3 | medium (jank / battery) | `gl/PostFx.ensure` | a GPU that refuses the post-processing framebuffers made the game create and delete 3 textures, 3 framebuffers and a renderbuffer **every frame** | the refused size is remembered and not retried for ~300 frames | `ReviewGlTest.aGpuThatRefusesThePostFramebuffersIsNotAskedAgainEveryFrame` |
| R4 | medium (crash) | `ui/Dialogs.background` | an exception in a background wallet call (full disk while saving, OOM) killed the worker thread = the whole process | failures are caught and shown ("could not be saved: is the storage full?"), buttons re-enable | `ReviewDialogsTest` |
| R5 | low | `AndroidManifest.xml` | `VIBRATE` was requested but never used (`performHapticFeedback` needs no permission) | removed | `ReviewManifestTest` |
| R6 | info | `gl/Mesh` | indices are 16-bit; nothing guarded a mesh above 65,536 vertices | `Mesh.maxVertices` probe; largest mesh is 3,389 vertices | `ReviewGlTest.everyMeshFitsSixteenBitIndices` |

## Performance changes made after the review (integrator)

* **GL call overhead.** The reviewer's counter showed **5,967 GL calls per busy frame** (247 draws): every draw re-specified 4 vertex
  attributes and 2 buffers and ~13 uniforms. Meshes now use **vertex array objects** (one `glBindVertexArray` per draw, the index buffer
  and layout are recorded once; the glow batch and the particle points have their own) and `Program` skips uniform writes that repeat the
  previous value. Result: **1,964 calls per busy frame (−67 %)**, and all 24 reference renders are **pixel-identical** (max channel
  difference 0). `Mesh.upload` binds its own VAO first so uploading can never overwrite another mesh's index binding.
* **Automatic quality** now has a last resort for very weak GPUs: tier 2 → 1 → resolution 0.7× → post off → resolution 0.55×
  (`GameGLView`); "Low" in the settings means no post-processing at all.
* Glow sprite upload is limited to the sprites of the frame (was the whole 73 KB buffer every frame).

## Measured (headless, llvmpipe — relative numbers only)

| item | value |
|---|---|
| draw calls / triangles, busy 15-character frame | 247 / 161k |
| allocation per frame | GL thread 26.7 KB, HUD (3D mode) 7.4 KB |
| `Scene3D.init()` (shaders, world meshes, 8 cast models) | 467 ms cold JVM |
| frame cost with post off / tier 1 / tier 3 | −10 % / −10 % / −10…0 % vs tier 2 (software GPU: only the ordering matters) |
| fbm noise removed from ground/sky/fog | ≈ −5…−15 % (the lit vertex-material shader is the main cost) |

## Checked and OK
* Wallet file format: every new key is read with `opt*` defaults, nothing was removed, so a 2.1.0 `wallet.dat` loads (the signature covers the stored text).
* Kill / restart: an unseen Séance replays and pays once (`ResumeTest`); pausing the app for minutes in the middle of a Séance ends clean; rotating to portrait during the cinematic and the free spins does not break the flow (`ReviewFlowTest`); a buy while a bonus is unseen is refused; autoplay continues after a Séance and never spins during it.
* Soak: 3,000 spins (28 Séances, 56 Vaults, random buys and stakes) through the real state machine never get stuck and the wallet identity holds (`SoakFlowTest`).
* A frame leaves the framebuffer binding and texture units as it found them (`ReviewGlTest.aFrameLeavesTheFramebufferAndTextureUnitsAsItFoundThem`).
* Context loss: a new `Scene3D` is created in `onSurfaceCreated`; the process-wide `Cast` registry is reset per scene so no model reuses stale GPU buffers.

## Not reviewed / open (honest list)
* The fragment-shader cost on a PowerVR GE8300 (only llvmpipe numbers exist); the auto-quality controller is the safety net.
* R8 / ProGuard: the release build links and the APK is 700 KB, but nothing exercised it on a device (the code uses no reflection).
* Android 16 large-screen orientation rules (the activity is `sensorLandscape`; portrait layouts exist and are rendered in tests).
* Audio on a real device: AudioTrack / SoundPool behaviour, audio focus, the first-launch render time on a slow CPU.
