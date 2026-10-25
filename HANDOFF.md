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
- [ ] base view: feature fly-in animations, coins on reels, multiplier, win lock + count-up + tiered SFX, big hold-to-spin button
- [ ] vault: spin button per respin, new epic music
- [ ] audio: fanfare/count-end/coin-drop/spin-press SFX, new vault music
- [ ] release v1.2.0
