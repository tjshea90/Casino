# Dragon's Hoard — Android slot machine

A fully offline, casino-style 5-reel video slot for Android 10 (Go edition) and up,
optimised for the MediaTek MT8168 (armv7). **APK: [`release/DragonsHoard.apk`](release/DragonsHoard.apk)** (~115 KB).

![Cabinet](docs/idle_1280x800.jpg)

## Install
1. Copy `release/DragonsHoard.apk` to the tablet (or download it from GitHub on the device).
2. Open it and allow "Install unknown apps" for your file manager/browser when asked.
3. Launch **Dragon's Hoard**, set your machine password, then tap **DEPOSIT**.

## Currency
| Unit | Tokens |
|------|--------|
| 1 token | 1 |
| 1 mack | 10 tokens |
| 1 book | 8 macks = 80 tokens |

Bets are whole macks; every payout is rounded **down** to a whole mack.

## Password protection
- Deposits, cash-outs, password changes and machine reset all require the password.
- Password is stored only as a salted PBKDF2-SHA256 hash.
- The credit file is signed with an Android Keystore key; any outside edit puts the machine in **TILT** and disables play.
- 5 wrong passwords → lockout starting at 30 s, doubling up to 1 hour.
- App backup / device transfer is disabled so an old balance can't be restored.
- There is no password recovery (by design).

## The game
- 5 reels × 3 rows, 10 paylines, choose 1–10 lines and 1–40 macks per line.
- **Dragon Wild** substitutes for everything except the scatter; 5 wilds on a line pay 1,000× the line bet.
- **Gold Coin Scatter** pays anywhere (2× / 10× / 50× total bet); 3+ award **10 free spins with all wins ×3**, retriggerable, on richer reels.
- Real-cabinet behaviour: reel anticipation ("tease") when 2 scatters land, slam-stop, win roll-ups,
  BIG / SUPER / MEGA / EPIC win celebrations with coin showers, chasing lights, autoplay, turbo, synthesized sounds, haptics.

## Math (verified by unit tests)
| | |
|---|---|
| Theoretical RTP | **80.00 %** (exact: 79.999 %) — identical at every bet |
| Base-game line wins | 53.7 % |
| Scatter pays | 1.9 % |
| Free-spin feature | 24.4 % (triggers ~1 in 126 spins, averages ~31× total bet) |
| Hit frequency (10 lines) | ~33 % |
| Volatility | Medium (SD ≈ 4.6 × bet) |

`app/src/test/.../RtpTest.kt` computes the RTP exactly over every reel combination and also runs a
3-million-spin Monte-Carlo simulation of the full game.

| Win presentation | Big win |
|---|---|
| ![](docs/win_line_cycle.jpg) | ![](docs/big_win_end.jpg) |

## Build
See `HANDOFF.md`. `gradle :app:testDebugUnitTest :app:assembleRelease`. GitHub Actions also builds the APK on every push.
