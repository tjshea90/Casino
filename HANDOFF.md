# >>> LIVE STATUS — READ THIS FIRST (updated every milestone; newest session on top) <<<

**Resume protocol (cold start, nothing else needed):**
1. `git fetch origin && git branch --show-current` — work on the branch you were assigned (this task: `ccr-5cf48990-4m3710`).
   Never push to another branch without the user's OK. `main` is not used.
2. Read this block, then `git log --oneline -12`, then continue at the first unchecked box below.
3. Build/test: `echo "sdk.dir=/opt/android-sdk" > local.properties` then
   `gradle :app:testDebugUnitTest --tests '*RtpTest*'` and `gradle :reaper:testDebugUnitTest --tests '*Rtp*'`
   (Robolectric screenshot tests need `~/robolectric-deps`, see the Dragon's Hoard section).
4. **Checkpoint ladder** (assume you can be cut off at any second):
   - Rung 1 — after every meaningful step: `tools/checkpoint.sh "what you just did"` (commit + push with retries).
   - Rung 2 — after every milestone: tick the box below and update the status line (keep this block true).
   - Rung 3 — when a unit passes its tests: normal descriptive commit via the script.
   - Rung 4 — shipping: `SHIP=1 tools/checkpoint.sh "release vX"` after copying APKs into `release/` and bumping
     versionName/versionCode. **Pushing `release/*.apk` publishes a public GitHub Release** (release.yml) — only on purpose.

## Task (session 6): "Change the RTP math for the entire app to 94 %, research how online slots (BetMGM etc.) work, make it fun with frequent wins, keep a verified house edge"
Target for BOTH apps: **theoretical RTP 94.00 % (house edge 6.00 % of every token wagered), verified three independent ways**
(exact closed form in `tools/*.py`, brute-force enumeration/analytic means in unit tests, Monte-Carlo of the real engine).

Design decisions (with reasons):
- Industry methods adopted: weighted virtual reel strips, separate base/feature reel sets, RTP "budget" split between
  base game / features / bonus, hit-frequency + volatility tuning, count-dependent free spins (3/4/5 scatters = 10/15/25),
  pair pays on the top symbols (classic IGT/Aristocrat hit-frequency tool), Hold & Spin with fixed jackpot tiers,
  SecureRandom outcome RNG decided before the animation, published RTP + "actual RTP" session stats.
  See `docs/MATH.md` for the write-up and sources.
- Honest presentation: pair/low pays that return LESS than the stake are "partial returns" — they play a quiet chime and
  docs/in-app info report BOTH "any win" frequency and "win >= stake" frequency. Neon Reaper has no partial wins at all
  (every win >= stake).
- Dragon's Hoard keeps its pay table except new 1x pair pays on TIGER/KOI; reels re-weighted (see GameConfig).
- Real progressive jackpots were NOT added: a growing pool makes theoretical RTP depend on pool state; the Soul Vault
  keeps fixed MINI/MINOR/MAJOR/GRAND tiers so RTP is exact.

Work queue (tick as you go):
- [x] Merge app branch into session branch; baseline tests run in the sandbox (Gradle OK, ~1 min)
- [x] Web research done (summarised in docs/MATH.md once written)
- [x] `tools/dragon_model.py` exact model + simulator + tuner (reproduces Kotlin 80 % numbers to 5 decimals)
- [x] `tools/checkpoint.sh`, resume protocol (this block)
- [x] Dragon's Hoard: Kotlin config/engine (pair pays, FS by scatter count, new reels) + RtpTest at 94 % + UI text/chime
      (exact 93.996 %, sim 94.05 %, any-win 1 in 2.28, win>=stake 1 in 6.1, FS every ~109 spins, SD 5.45); screenshots of paytable/stats checked
- [x] Neon Reaper: exact model `tools/reaper_model.py`, rebudget, uniform FEATURE_RATE 0.009924, bonusBet=floor, tests (RtpExactTest, CalibrationTest,
      FullGameTest, NoExploitTest all green: stake1 94.00 %, 10 93.93 %, 25 92.74 %, 40 90.92 %; hit 1 in 2.95; vault every 125 spins)
      — whole :reaper suite green (29/29, incl. GL tests after `apt-get install libegl1 libegl-mesa0 libgles2 libgbm1`, run with EGL_PLATFORM=surfaceless)
- [x] docs/MATH.md, README, CLAUDE.md numbers (94 %), CI test list (build.yml runs the fast exact tests)
- [x] Full test run both modules (green)
- [x] Independent audit by a separate agent (it recomputed both RTPs from scratch: DH 93.9955 %, NR 93.9995 % uncapped — all match) found
      4 issues, status:
      1. **REAL EXPLOIT (fixed in code, tests being updated):** NR bonus bet = floor(average stake of ALL spins in the cycle) is beaten by an
         ADAPTIVE policy (bet big only when relics arrive fast): "bet 1; with 4 relics and <=60 spins since the last vault bet 40" = 101.3 %,
         threshold policy 104.0 %, optimal 104.4 % (I reproduced 101.28 / 103.96). Fix = `RelicMeter`: bonus bet = floor(mean stake of the FIVE
         RELIC-REVEALING SPINS) — neutral for every policy by Wald's identity (a relic spin is a random sample of the player's stakes);
         same policies now give 92.6 / 92.5 %. `Wallet` uses `RelicMeter`; HUD shows `projectedBonusBet`.
      2. NR help text overclaimed — reworded ("up to 94 %", "at least 6 % however you vary stakes"). [x]
      3. `tools/dragon_model.py` still had the OLD 80 % reel counts (printed 0.83886) — fixed; both models now have a `check` command that compares with the Kotlin config. [x]
      4. Dragon's help said "choose 1–10 lines" but only 1,3,5,7,9,10 exist — fixed. [x]
      Engine tests with RelicMeter green: adaptive exploit policies now 92.6/92.7/92.4/91.1 % (old rule 101-105 %). TODO: full suites, rebuild APKs, ship.
      Known pre-existing limits noted by the audit (not changed): password lockout uses wall-clock time; signed wallet file has no rollback counter.
- [x] Version bump committed (app 1.1.0/code 2, reaper 2.1.0/code 5); release builds verified (same signing cert 1bc61b76… as old APKs => in-place upgrade)
- [x] Release APKs rebuilt from final source (Dragon's Hoard 1.1.0/code 2, Neon Reaper 2.1.0/code 5, same signing cert 1bc61b76… => in-place upgrade,
      new strings verified inside the dex) and copied to `release/`; shipped with `SHIP=1 tools/checkpoint.sh` => GitHub Release "build-6".
      NOTE: the very first checkpoint push of this branch ALREADY published a stray release "Casino build 5" (old, unchanged APKs) because the merge
      brought release/*.apk into the diff vs the previous remote tip. Harmless but unannounced; user may delete it.

Status line (final, session 6): everything DONE and shipped. History: Dragon's Hoard DONE at 94 % (tests green). Neon Reaper DESIGN FINAL (in tools/reaper_model.py, validated vs old Kotlin
calibration): reels [1,3,3,5,5,5,6,6,8,9,12], relic chance 5/125 (bonus ~every 125 spins), ONE stake-independent mini-feature rate
0.00992, bonus bet = FLOOR(average stake), vault + minis unchanged. Exact budget at stake 1: base 59.34 % + minis 11.98 % + vault 22.68 %
= 94.000 %; base hit 32.8 % (1 in 3.05). The 25-book cap only lowers RTP at high stakes (stake 10 93.94, 25 92.76, 40 90.95).
WHY uniform rate + floor: the OLD rule (roundToInt average stake + per-stake rate) is EXPLOITABLE by mixing stakes — at 94 % the worst
mix (30 % stake 1 / 70 % stake 2) pays 98.0 % RTP; with floor + uniform rate the worst strategy found is 93.9 %.
Kotlin for :reaper DONE and green (engine tests). NEXT: full-suite runs + screenshots, docs/MATH.md, README, independent math review, release build.

---
# HANDOFF — Casino repo (two apps)

| App | Module | Status |
|-----|--------|--------|
| Dragon's Hoard | `:app` | DONE (v1.0.0, `release/DragonsHoard.apk`) |
| Neon Reaper | `:reaper` | DONE v1.1.0 (`release/NeonReaper.apk`) — see "NEON REAPER" section at the bottom |

---
# Dragon's Hoard (app 1)

> **Cold-resume protocol for any new Claude session:** read this file top to bottom,
> then `git log --oneline -15`, then continue at the first unchecked item in
> "Work queue". Update this file and commit+push after every meaningful step
> (`git push -u origin claude/slot-machine-app-3z9h9s`).

## Goal (from the user)
Slot machine APK for **Android 10 Go, MediaTek MT8168, armv7** (user's apps generally target Android 16).
- Currency: **tokens**. 10 tokens = 1 **mack**. 80 tokens = 8 macks = 1 **book**.
- Wins with an uneven token amount are **rounded down to the mack**.
- **Deposit / withdraw protected by a user-set password**; no balance change without it
  (other than playing).
- **RTP 80 %**, **medium volatility**.
- Real-casino behaviour: free spins, wager selection, wilds/scatters, big-win
  celebrations, rare huge wins; fun theme, rich visuals/animations.

## Key decisions
- Native **Kotlin + framework Views/Canvas only** (no AndroidX, no Compose) → tiny APK,
  smooth on MT8168 Go. All art is drawn procedurally (no bitmaps). Sound is synthesized at runtime.
- minSdk 29, targetSdk/compileSdk 36. No native code → runs on armv7 & arm64.
- Pays are **integer macks per 1-mack line bet**, line bet is whole macks, so RTP is
  exactly 80 % at every bet; the floor-to-mack rule is still applied to every payout.
- Security: password → PBKDF2-SHA256 (salted). Wallet file is HMAC-signed with a
  non-exportable **Android Keystore** key; tampering locks the wallet. `allowBackup=false`.
  Failed password attempts → escalating lockout.
- Fixed signing keystore in `release/` (password `dragonhoard`) so APK upgrades install over each other.

## Layout
- `app/src/main/java/com/tjshea/dragonhoard/engine/` — pure-Kotlin slot math (unit-tested).
- `.../data/` — wallet, password, persistence, tamper protection.
- `.../ui/` — Activity, custom `SlotView`, dialogs.
- `.../audio/` — synthesized sound effects.
- `app/src/test/...` — exact RTP calculation + Monte-Carlo test.
- `release/DragonsHoard.apk` — latest built APK (also built by GitHub Actions).

## Visual testing without an emulator
`ScreenshotTest` (Robolectric native graphics) renders the cabinet and dialogs to `app/build/screens/*.png`.
Robolectric's runtime jars must be pre-fetched into `~/robolectric-deps/` (Maven Central is blocked here; the
Google mirror works):
```
mkdir -p ~/robolectric-deps && cd ~/robolectric-deps
for v in 14-robolectric-10818077-i7 15-robolectric-12650502-i7; do curl -fLO https://maven-central.storage-download.googleapis.com/maven2/org/robolectric/android-all-instrumented/$v/android-all-instrumented-$v.jar; done
```

## Build
```
echo "sdk.dir=/opt/android-sdk" > local.properties
gradle :app:testDebugUnitTest :app:assembleRelease
cp app/build/outputs/apk/release/app-release.apk release/DragonsHoard.apk
```

## Work queue
- [x] Project scaffold, keystore, handoff doc
- [x] Tune reel strips to 80 % RTP (python tuner in scratchpad; result goes in `engine/GameConfig.kt`)
- [x] Engine (spin, line eval, scatter, free spins) + exact RTP unit test
- [x] Wallet + password security (data/Wallet.kt, data/Security.kt)
- [x] SlotView rendering & animations, sounds
- [x] Dialogs: setup password, deposit, withdraw, paytable/help, stats
- [x] Build release APK → `release/`, GitHub Actions workflow
- [x] Polish pass 1 (tease effect, shader caching, flow fixes, wallet tests)
- [ ] Ideas if the user asks for more: background music loop, gamble (double-up) feature,
      progressive jackpot meters, more themes, real-device performance check on MT8168

## Status log
- Session 1: scaffold created. Engine done: exact RTP 79.999%, sim 80.4%, hit 33%, SD 4.6, FS every ~126 spins.
- Session 1 (cont.): full game built; 13 unit tests pass; release APK 114 KB in `release/`; README with screenshots.


---
# NEON REAPER (app 2) — module `:reaper`, package `com.tjshea.neonreaper`

## User requirements (session 2)
1. RTP 87 % (house edge ~13 %) at EVERY stake. 2. Winning spin ~1 in 4.
3. Single STAKE button (no lines UI). 4. Stake 1–40 credits. **1 credit = 1 token** (user's choice).
5. Same password/wallet/architecture as app 1. 6. Same currency + round-down-to-mack rule.
7. Dark theme: black, neon green, red; hooded red-eyed figures, grim reapers. 8. Subtle scary background music.
9. Big/huge wins proportional to stake, **hard cap 25 books (2000 tokens) per spin and per bonus** (user's choice),
   still 87 % at every stake.
10. Lots of animation; characters appear/enlarge on big wins, with SFX.
11. Collect 5 relics (random chance per spin) → separate BONUS screen (Hold & Spin style), big payouts, transition in/out,
    show total, add to balance, return to base game.

## Math design
- 5x3, 20 fixed paylines, pays are integer multiples of the stake (so stakes that are multiples of 10 never lose to rounding).
- Rounding to mack and the 2000-token cap make RTP stake-dependent, so a visible **"Reaper's Touch" random wild-reel feature**
  has a per-stake trigger rate f(stake) computed so RTP(stake) = 87 % exactly (table in ReaperConfig, verified by test).
- Bonus: each spin has chance p to award the next missing relic; 5 relics → Soul Vault bonus (Hold & Spin, 5x4 grid,
  3 respins reset on every landing orb, value orbs × bonus bet, MINI/MINOR/MAJOR jackpot orbs, Reaper collector orb,
  GRAND for a full grid). Bonus bet = average stake while collecting (prevents min-stake-then-max-stake exploit).
- Small stakes (<10 tokens): wins below 10 tokens round to 0 (user accepted), so hit rate is lower there.

## Work queue (app 2)
- [x] Python tuner (tools/reaper_tune.py, tools/reaper_bonus.py) → reel counts, feature, bonus params
- [x] Engine + per-stake calibration (CalibrationTest prints FEATURE_RATE; all stakes 87.0 %), FullGameTest sim 86–88 %
- [x] Wallet/Security (+ relics, average stake, crash-safe Soul Vault replay via stored seed)
- [x] Art (ui/Art.kt), base game (ui/ReaperView.kt), music + SFX (audio/Audio.kt)
- [x] Bonus screen (ui/VaultView.kt) + trigger/return transitions (MainActivity overlays VaultView)
- [x] Release APK `release/NeonReaper.apk`, CI builds both apps, README
- [x] Tests: CalibrationTest, FullGameTest, WalletTest, ActivityTest (launch + dialogs), ScreensTest/ArtSheetTest (renders to reaper/build/screens)
- [ ] Possible next steps if asked: real-device tuning of animation speed/volume on the MT8168, more bonus variety (e.g. extra rows), haptics tuning

## Key files (app 2)
- engine/ReaperConfig.kt — pays, reels, stakes, FEATURE_RATE table (re-run CalibrationTest after ANY math change and paste its printed table)
- engine/ReaperEngine.kt, engine/SoulVault.kt (bonus is generated from a seed: credited at trigger, replayed by UI; replays after restart)
- data/Wallet.kt — relics, average-stake bonus bet, vaultUnseen gate (spins blocked until the bonus has been shown)
- Session 2: Neon Reaper built end to end; per-stake RTP 87.0 %; 148 KB APK.

## NEON REAPER v1.1 (session 3) — user feedback round
User asked for:
0. GitHub Releases page with permanent latest-download links (workflow `.github/workflows/release.yml`).
1. Better background music. 2. Much better, 3D-looking graphics/characters.
3. Relics far more often (bonus attainable, not extremely rare) → target bonus ≈ every 90 spins.
4. Winning spin ≈ 1 in 3.5 at EVERY stake; wins may be < stake; occasional 2×/5×/10×/15× and big wins.
5. Every win pauses (even autoplay) with a big clear token amount + sound.
6. 3D animated characters on the reels: dark red-eyed ones, some smiling/laughing; occasional extra animations + SFX.
**Rule change from user: rounding to the mack happens ONLY at cash-out, never in game. In-game wins are exact tokens.**
Design: line pays are integer multiples of the stake (so no fractional tokens at any stake), 25-book cap stays.

Work queue v1.1:
- [x] release.yml workflow (fires when release/*.apk changes; workflow_dispatch only works from default branch)
- [x] math: new pays/reels (hit ≈ 28.6 %), relic chance, vault retune, calibration, cash-out rounding, tests
- [x] audio: reverb + composed music + laugh/cackle/giggle/caw/chatter SFX (AudioRenderTest renders WAVs to reaper/build/audio)
- [x] 3D art (ui/Art3D.kt `Characters`): drum reels, animated characters, win banner, idle character events + voices
- [x] screenshots, release build v1.1.0 (versionCode 2) → release/NeonReaper.apk; release workflow publishes it
- Note: CI build.yml was failing at android-actions/setup-android; fixed by using the runner's preinstalled SDK.

## NEON REAPER v1.2 (session 4) — user feedback round
User asked for:
1. Confirm house keeps 13 % over time (answer: 13 % of every token WAGERED; explain vs deposits).
2. Relics rare enough that bonuses don't make the machine lose money (bonus is inside the 87 %; verify with long sims).
3. Soul Vault: special music + a SPIN button for each respin (press sound + spin sounds) instead of auto respins.
4. Wins can't be skipped instantly: brief pause showing the amount; count-up (suspenseful, not slow) for non-small wins;
   positive win SFX, more exciting for bigger wins.
5. More 3D graphics/characters + rare MINI FEATURES: a 3D character floats to the front with a sound and alters the reels
   for that spin (raven drops wilds, pumpkin spits soul coins with cash values, skull multiplies wins, reaper wild reels).
6. (mid-turn) SPIN button much larger; HOLD spin = rapid auto spins until released.
Plan: rebudget RTP (base ↓, bonus a bit rarer, features ↑), per-stake calibration of total feature rate.

Work queue v1.2:
- [x] engine: mini features (RAVEN wilds, PUMPKIN coins, SKULL multiplier, REAPER wild reels), rebudget + calibrate, tests
- [x] base view: feature fly-in animations, coins on reels, multiplier, win lock + count-up + tiered SFX, big hold-to-spin button
- [x] vault: spin button per respin, new epic music
- [x] audio: fanfare/count-end/press SFX, new vault music
- [x] release v1.2.0 (versionCode 3). Tests fork per class (exit 137 = OOM otherwise).
- [x] v2.0 OpenGL 3D engine shipped (see v2.0 section below)
7. (mid-turn) "Make the graphics more 3D and animated like a video game with a good graphics engine."
   → Plan v2.0: OpenGL ES 3 renderer (GLSurfaceView) behind a transparent HUD View: real 3D reel cylinders textured
     from the Characters bitmaps with per-pixel lighting/specular, perspective camera (sway, zoom on big wins, shake),
     GPU particles, bloom-style glow, 3D environment; characters as lit billboards in depth. Do AFTER v1.2 ships.
8. (mid-turn) Win pop-up must be a huge clear "8 TOKENS" — no "× your stake" text anywhere.

## NEON REAPER v2.0 (session 5) — OpenGL 3D engine (user: "Build the 3d engine with opengl. Polished graphics,
## individual scary Halloween characters")
Architecture (package `com.tjshea.neonreaper.gl`):
- `Gl` interface (subset of GLES 3.0) → `AndroidGl` (GLES30) on device, `LwjglGl` (LWJGL + Mesa EGL surfaceless) in
  JVM tests so 3D scenes can be rendered to PNG here (apt: libegl1 libegl-mesa0 libgles2 libgbm1; env EGL_PLATFORM=surfaceless).
- `Mat4` math, `Mesh`/`MeshBuilder` (procedural lathe/sphere/box/drum-face/wings...), `Shaders` (lit PBR-lite with
  procedural surfaces for pumpkin face/skull sockets/ghost face, sky with clouds+stars+moon, fog, particles, glow).
- `Scene3D` renders `SceneState` (snapshot filled by ReaperView each frame). `Layout3D` = shared camera/reel math so
  the 2D HUD (ReaperView, now transparent) can place win lines/boxes/text over 3D cells.
- `GameGLView` (GLSurfaceView) sits behind the HUD. 2D drawing stays as fallback when GL is unavailable.
Characters (3D procedural meshes): Grim Reaper, Hooded Watcher, Jack-o'-lantern, Skull, Ghost, Raven, Bats; reel
character symbols are live 3D models standing on the drum faces; royals are textured faces from the Canvas atlas.

Work queue v2.0:
- [x] A. Gl/AndroidGl/LwjglGl, Mat4, Mesh, Shaders, GlSmokeTest renders build/screens/gl_smoke.png (Headless.kt = EGL surfaceless + PNG writer)
- [x] B. Layout3D + 3D reel drums + atlas + cabinet frame
- [x] C. Environment: sky/moon/clouds, ground, tombstones, trees, fog, fireflies, lightning, camera
- [x] D. 3D characters + animation (jaw, eyes, flicker, flap), side characters, mini fly-ins, big-win reaper, coins
- [x] E. HUD integration (transparent ReaperView, projection for lines), GameGLView, fallback, tests, release v2.0.0
- [x] F. Polish: open cowl (per-pixel elliptical cut, uParam.z) so reaper skulls / watcher red eyes show; wailing
      ghosts (hollow eyes + red ember, screaming mouth); adaptive resolution (GameGLView drops to 70 % if >half of
      90 frames take >38 ms). Released v2.0.0 (versionCode 4).

v2.0 testing notes:
- Run order for screenshots: `AtlasExportTest` → `HudExportTest` → `SceneRenderTest` (writes gl_*.png and
  full_*.png = real HUD composited over the 3D world, exactly what the device shows). All optional inputs.
- `SceneSink` interface: GameGLView on device, `RecordingSink` in tests. ReaperView.gl3d != null ⇒ 3D mode
  (HUD draws only chrome/text; world is GL). gl3d = null ⇒ old 2D renderer (fallback when GLES3 missing/fails).
- MainActivity.attach3d(): GLSurfaceView at index 0 behind ReaperView; paused during Soul Vault (setActive(false)).
- Ideas for next round: Soul Vault in 3D; per-device quality toggle in settings; LOD for fingers/teeth.

- Downloads: phone browsers hang at 100 % on .apk; release.yml also publishes NeonReaper.zip / DragonsHoard.zip.
