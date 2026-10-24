# HANDOFF — Dragon's Hoard slot machine (Android)

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
