# Casino math — 94 % RTP, how it is built and how it is verified

Both games (Dragon's Hoard `:app`, Neon Reaper `:reaper`) now have a **theoretical RTP of 94.00 %**: over time the
machine keeps **6 %** of every token wagered. RTP is a *long-run average* — any single session can be far above or below it.

| | Dragon's Hoard | Neon Reaper |
|---|---|---|
| Theoretical RTP | **93.996 %** (exact), identical at every bet and line count | **94.00 %** at stakes 1–5 (exact); the 25-book win cap trims higher stakes: 10 → 93.9 %, 15 → 93.6 %, 20 → 93.2 %, 25 → 92.7 %, 30 → 92.2 %, 40 → 90.9 % |
| Was | 80 % | 87 % |
| Any win | **43.9 %** of spins (1 in 2.3) — was 32.8 % | **33.9 %** of spins (1 in 2.95) — was 28.3 % |
| Win ≥ the whole stake | 16.3 % (1 in 6.1) — pair/low pays can return less than the stake | = any win: **every** Reaper win pays at least the stake |
| Feature | free spins every ~109 spins (was ~126); 3 / 4 / 5 scatters = 10 / 15 / 25 spins, all wins ×3, retriggers | Soul Vault bonus every ~125 spins (was 140); mini feature every ~101 spins |
| Volatility | medium (SD ≈ 5.5 × total bet) | medium |
| Max win | 5 wilds on a line = 1,000× line bet (×3 in free spins) | 25 books (2,000 tokens) per spin and per Soul Vault |
| Python reference | `tools/dragon_model.py` | `tools/reaper_model.py` |
| Kotlin tests | `RtpTest` | `RtpExactTest`, `CalibrationTest`, `FullGameTest`, `NoExploitTest` |

## 1. How online slots work (research) and what this app copies

Sources are listed at the end; most pages found were general explainers, so the design rests on the maths below, which is
re-computed in code, not on any web claim.

1. **The RNG decides first, the animation is decoration.** A (lab-certified) random number generator picks the outcome at
   the instant you press spin; spinning reels only display it. → Both wallets call `SecureRandom` and settle + save the spin
   *before* any animation (`Wallet.playSpin`), so killing the app cannot lose or duplicate a win.
2. **Virtual reels with weighted stops.** The reel on screen is a strip of N virtual stops; each symbol is given a number
   of stops, so a symbol can be rare even if it looks common. Designers move RTP, hit rate and volatility by editing those
   counts. → `BASE_COUNTS` / `FREE_COUNTS` (Dragon's Hoard) and `COUNTS` (Neon Reaper).
3. **RTP is calculated, published and certified, not "measured".** RTP = Σ P(outcome) × payout over every outcome,
   features included — the *PAR sheet*. Labs check it; games show it in their info screen (BetMGM's game-info panel lists
   RTP; progressive titles list base RTP *plus* a jackpot contribution; operators may run different RTP variants of one game).
   → Both games print their RTP, hit frequency and feature frequency in the in-app rules, and the tests recompute them exactly.
4. **Budget the RTP.** A slot's RTP is split between base-game lines, scatters, bonus features and jackpots (a commonly
   quoted modern split is ~70 % base / ~30 % bonus). A high hit frequency means small average wins, so the budget is spent on
   many 1–2× wins and a few feature spikes. → see the budgets below.
5. **Feature toolbox**: wilds, scatter-triggered free spins with multipliers and retriggers, count-dependent awards
   (more scatters = more spins), Hold & Spin with fixed jackpot tiers, random "mystery" modifiers, collect-to-unlock meters.
   → Dragon's Hoard: wild, scatter pays, free spins ×3 with retrigger, **new** 10/15/25 spins by scatter count, **new**
   1× pair pays on Tiger/Koi. Neon Reaper: wild, four mystery mini features, relic meter → Soul Vault Hold & Spin with
   MINI/MINOR/MAJOR/GRAND tiers.
6. **Engagement vs honesty.** Frequent small wins keep sessions long. Research on "losses disguised as wins" (a win smaller
   than the stake celebrated like a win) shows players over-estimate how much they win. → We keep the high hit rate but:
   Neon Reaper has no sub-stake wins at all; Dragon's Hoard plays only a quiet chime when a win returns less than the whole
   stake, and the in-app rules state both "any win" and "win ≥ stake" frequencies and the true RTP.
7. **Not copied on purpose:** real progressive jackpots. A growing pool makes the theoretical RTP depend on the pool, so
   the house edge could not be stated exactly. The Soul Vault uses *fixed* jackpot tiers instead (RTP exact).

## 2. Dragon's Hoard (exact)

RTP = lines 59.036 % + scatters 2.245 % + free spins 32.715 % = **93.996 %** (house edge 6.004 %).

* 5 reels × 3 rows, 10 lines. Per-line expectation needs only each reel's symbol frequencies (a uniform stop gives every
  row the strip's frequencies) → every line has the same value, so RTP is a fraction of the *total* bet whatever
  lines/bet are chosen. Scatters are spaced ≥ 3 apart so a reel shows at most one (asserted by a test).
* Free spins: N = awarded₍base₎ / (1 − awarded₍free reels₎) expected spins per trigger including retriggers (11.4 per
  trigger); each pays ×3 on the free-spin reels.
* Pay changes vs the 80 % game: Tiger and Koi pay **1× the line bet for a pair** (wild substitutes); free spins by scatter
  count 10 / 15 / 25. Reel counts re-tuned (`tools/dragon_model.py tune` + `polish`) to land in 93.990–94.000 %.
* Verified three ways: closed form (Python) = brute-force enumeration of all 11⁵ line outcomes (Python and Kotlin) =
  3,000,000-spin simulation of the real engine (RTP 94.05 %, any win 43.9 %, win ≥ stake 16.3 %, SD 5.45).

## 3. Neon Reaper (exact + measured cap)

Budget at stakes 1–5: **base 59.34 % + mini features 11.98 % + Soul Vault 22.68 % = 94.000 %**.

* Base game (20 lines): E[spin] = 0.5993 × stake, every win ≥ 1× stake, hit 32.9 % of spins. Reels re-weighted from
  `[1,3,3,4,5,5,7,7,8,8,9]` to `[1,3,3,5,5,5,6,6,8,9,12]` — more of the commonest card, characters still ≈ 33 % of every
  strip so the 3D cast keeps appearing.
* Mini features (one every ~101 spins, mean 12.07×): Reaper's Touch 9.44×, Raven's Flock 11.43×, Pumpkin's Treats 16.86×,
  Skull's Curse 8.18× — each mean is computed exactly (wild reels, hypergeometric random wild cells, coin counts × values).
* Soul Vault (every ~125 spins, mean 28.35× the bonus bet, SD 22): exact Markov DP over (filled cells, respins left).
  P(GRAND, full board) = 1 in 111,643 vaults.
* **The 25-book cap** (2,000 tokens per spin and per bonus) only affects tails, so it is measured as
  `E[(X − 2000/stake)⁺]` with millions of samples of the *real* engine and subtracted from the exact mean (accuracy ≈ ±0.02 %).
  The result is the per-stake table above; it can only be ≤ 94 %.

### The exploit found and fixed while raising 87 → 94 %

The Soul Vault pays on the **average stake while collecting relics**. The old code rounded that average (ties up) and used a
mini-feature rate that grew with the stake to "refill" the cap loss. At 87 % this was invisible; at 94 % it matters:

| Staking pattern | Old rules at 94 % | New rules |
|---|---|---|
| 70 % of spins at stake 2, 30 % at stake 1 | **98.0 % RTP** (house edge 2 %) | 84.6 % |
| alternate stakes 1, 2 | up to +33 % bonus bet | 86.5 % |
| 20 % stake 40 / 80 % stake 2 | — | 92.2 % |
| constant stake 1 / 10 / 40 | 94.0 / 93.9 / 90.9 % | same |

Fix: `ReaperEngine.bonusBet` = **floor** of the average (never above what was really wagered) and **one** stake-independent
mini-feature rate. Worst strategy found by a grid search over mixes and phase strategies: 93.9 %. `NoExploitTest` replays
those patterns on the real engine and fails if any beats 94 %. Trade-off: mixing stakes gives a smaller bonus bet than a steady
stake — the in-app help says so.

## 4. Re-verify everything

```bash
python3 tools/dragon_model.py sim 3000000     # exact RTP, brute-force check, Monte-Carlo cross-check
python3 tools/reaper_model.py                 # exact budget, cap table, uniform feature rate
echo "sdk.dir=/opt/android-sdk" > local.properties
gradle :app:testDebugUnitTest --tests '*RtpTest*'
gradle :reaper:testDebugUnitTest --tests 'com.tjshea.neonreaper.engine.*'
```

Changing any pay, reel count, feature weight, relic chance or vault parameter requires re-running both Python models and
pasting the new numbers (`GameConfig` / `ReaperConfig`, including `RTP_BY_STAKE` and the `*_TEXT` constants); the tests fail
if the published figures stop being true.

## Sources (general background; treat figures as illustrative)

* RNG, virtual reels, RTP/hit-frequency/volatility explainers: [Slot Math Models 101 — PAR sheets, hit frequency & paytable design](https://mmccaa-site.pages.dev/blog/slot-math-model-par-sheet-en), [Hit Frequency vs. RTP](https://www.effortlessmath.com/blog/hit-frequency-vs-rtp-different-stories/).
* BetMGM: [BetMGM jackpot slots overview](https://www.gambling.com/us/online-casinos/slots/jackpot-slots) (RTP shown in the info panel; base RTP + jackpot contribution), [BetMGM progressive payouts 2025](https://www.gambling.com/news/betmgm-paid-out-more-than-120m-in-progressive-jackpots-in-2025). Published RTP figures for BetMGM's *MGM Grand Millions* differ between sources (≈ 89–95 %), so no figure from there is used.
* Losses disguised as wins / near misses: Dixon et al. 2010 (*Addiction*); Graydon et al. 2017, [systematic review, J Gambl Stud](https://ouci.dntb.gov.ua/works/4Obdj6z7); [University of Waterloo Gambling Research Lab](https://uwaterloo.ca/gambling-research-lab/node/90).
