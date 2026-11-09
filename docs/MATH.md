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

## 3. Neon Reaper 3.0 — PAR sheet (v3 math, exact + measured cap)

Everything below is produced by `python3 tools/reaper_model.py report` (closed form + Monte-Carlo cross-checks) and enforced by the
Kotlin tests (`RtpExactTest`, `CalibrationTest`, `FullGameTest`, `NoExploitTest`).

**RTP budget at stakes 1–5 (stake = 1):**

| Component | Share of RTP | Trigger | Mean payout |
|---|---|---|---|
| 20-line base game + mini features | 57.77 % | every spin (hit rate any win 31.3 %, 1 in 3.2; every win ≥ 1× stake) | lines 0.5065× + minis |
| Coffin scatter pay (3 / 4 / 5 coffins = 2× / 10× / 50× total stake, anywhere) | 1.11 % | P(3) 0.450 %, P(4) 0.0199 %, P(5) 0.000350 % | 0.0112× per spin |
| **THE SÉANCE** (3/4/5 coffins = 8/12/20 free spins on richer reels, multiplier ×1…×10, retrigger) | 14.12 % | 1 plain spin in 213 | 30.1× stake (3 coffins 28.8×, 4: 58.6×, 5: 119.7×) |
| **SOUL VAULT** (5 relics, hold & respin, MINI/MINOR/MAJOR/GRAND) | 21.00 % | 1 per 135 spins | 28.35× the bonus bet |
| **Total** | **94.000 %** | | house edge 6.000 % |

* Mini features (one per ~154 spins, mean 11.5× incl. coins): Reaper's Touch 8.46×, Murder of Crows 10.77×, Demon's Bargain 16.76×,
  Skull's Curse 7.33× (each exact: wild reels, hypergeometric wild cells, coin counts × values).
* Séance: per free spin mean 0.733× the stake that started it (line hit 22.5 %), 1 retrigger per ~106 free spins, multiplier
  `m_t = min(10, t)` (spin 1 = ×1 … spin 10+ = ×10). Exact backward DP over (spins left, multiplier) with retriggers; the whole session pays
  in proportion to the triggering stake, so it is linear in the stake (until the 25-book cap, which only lowers it).
* Soul Vault: exact Markov DP over (filled cells, respins left); P(GRAND, full board) = 1 in 111,643 vaults; the bonus bet is
  `floor(mean stake of the 5 relic-revealing spins)` (Wald-neutral, see the audit below).
* **Feature Buy** (skip the wait): THE SÉANCE costs 31× stake (EV 28.77× → returns 92.79 %), the SOUL VAULT 31× stake (EV 28.35× →
  91.46 %): both ≤ 92.8 % < 94 %, so buying never beats playing. The price is checked against the exact EV / 0.93 in the tests.
* **The 25-book cap** (2,000 tokens per spin, per Séance and per Vault) only affects tails, so it is measured as
  `E[(X − 2000/stake)⁺]` with millions of samples of the *real* engine and subtracted from the exact mean. It can only lower the return,
  which is why RTP falls at high stakes:

| stake | 1–4 | 5 | 8 | 10 | 15 | 20 | 25 | 30 | 40 |
|---|---|---|---|---|---|---|---|---|---|
| RTP | 94.00 % | 93.99 % | 93.95 % | 93.87 % | 93.32 % | 92.67 % | 91.93 % | 91.06 % | 89.01 % |
| buy Séance | 92.79 % | 92.75 % | 92.48 % | 92.17 % | 90.98 % | 89.09 % | 86.65 % | 83.86 % | 77.79 % |
| buy Vault | 91.46 % | 91.44 % | 91.41 % | 91.33 % | 90.09 % | 89.02 % | 87.92 % | 86.54 % | 82.85 % |

**Why a long-run loss for the house is impossible:** every money component is linear in the stake or concave in it (the cap), nothing
pays more than the stake it comes from, the Vault's bonus bet is neutral by Wald's identity, bought features are priced above their
exact expected payout and there are no free-credit promotions (rank and ledger are cosmetic). `NoExploitTest` plays adaptive
staking / buying policies against the real engine and requires every one to stay ≤ 94 %.

### House-edge audit: two exploits found and fixed while raising 87 → 94 %

The Soul Vault is paid `V × bonus bet`, so *how the bonus bet is derived from the stakes you played* decides whether a smart
player can beat the headline RTP. Three rules were tried (`tools/reaper_model.py exploit`, `NoExploitTest`):

| Staking policy (uses only what the HUD shows) | v1 `round(avg stake of every spin)` | v2 `floor(avg stake of every spin)` | **shipped: `floor(mean stake of the 5 relic-revealing spins)`** |
|---|---|---|---|
| constant stake 1 / 10 / 40 | 94.0 / 93.9 / 90.9 % | same | same |
| 70 % of spins at stake 2, 30 % at stake 1 | **98.0 %** | — | 86.9 % |
| bet 1; bet 40 with 4 relics held and ≤ 60 spins since the last bonus | **102.7 %** | **101.3 %** | 92.6 % |
| bet 40 while spins ≤ 20/40/60 holding 2/3/4 relics, else 1 | **105.3 %** | **104.0 %** (optimal policy: 104.4 %) | 92.5 % |
| first 45 spins of each collection at stake 25, then 1 (no relic info) | 97.7 % | 96.7 % | 92.3 % |

* **v1** rounded the average up: stakes 1 and 2 averaging 1.5 were paid as a 2 (+33 % bonus).
* **v2** (floor) was still beaten by an *adaptive* player. The collection window has a random length N and the bonus bet is
  `W/N`, so betting big only while relics are arriving quickly (short window) gives a large average for a cheap wager
  (E[1/N] > 1/E[N]). An independent audit by a separate agent found this, solved the optimal policy exactly (104.4 %), and I
  reproduced it (101.28 % / 103.96 %).
* **Shipped rule** (`RelicMeter`): a spin reveals a relic with the same probability whatever the stake, so the stake on a
  relic-revealing spin is a random sample of the player's stakes. By Wald's identity
  `E[Σ relic-spin stakes] = p · E[tokens wagered]`, hence `E[bonus bet] ≤ p·E[wagered]/5` for **every** adaptive policy (the
  floor and the concave 25-book cap only lower it). The expected bonus per token wagered can therefore never exceed the
  vault's 22.68 % share, and the rest of the return is also capped at 71.32 % (uniform mini-feature rate) — so
  **no staking policy can exceed 94.00 %**; the best any player can do is play a constant low stake. Trade-off: mixing
  stakes costs a little (the bet is rounded down) — e.g. the 70/30 mix above returns 86.9 %.

**v3 re-check** (`python3 tools/reaper_model.py exploit`, shipped rule, with the Séance and coffin scatter added): constant stake 10 = 93.83 %
(the best policy); "bet 1, bet 40 with 4 relics and ≤ 60 spins in the window" 92.12 %; "40 while spins ≤ 20/40/60 holding 2/3/4 relics, else 1" 91.33 %;
"first 45 spins of each window at 25" 91.33 %; "bet 40 only once 4 relics are held" 89.28 %. The old round/floor rules would give 100.8 % / 103.0 % on the same policies.

The audit also independently recomputed both games from the Kotlin rules (exhaustive enumeration of ~10⁸–10⁹ stop
combinations plus 10⁹-sample simulations): Dragon's Hoard **93.9955 %** (claimed 93.996 %), Neon Reaper **93.9995 %**
uncapped (claimed 94.000 %), and constant-stake Neon Reaper 93.999 / 93.932 / 92.748 / 90.939 % at stakes 1 / 10 / 25 / 40
(table above: within 0.0002). It found no way to beat Dragon's Hoard (every spin returns the same fraction of its bet; free
spins pay in proportion to the triggering bet).

## 4. Re-verify everything

```bash
python3 tools/dragon_model.py check           # the Python model still equals GameConfig.kt (reels, pays, free spins)
python3 tools/dragon_model.py sim 3000000     # exact RTP, brute-force check, Monte-Carlo cross-check
python3 tools/reaper_model.py check           # the Python model still equals ReaperConfig.kt
python3 tools/reaper_model.py                 # exact budget, per-stake RTP table, uniform feature rate
python3 tools/reaper_model.py exploit         # adaptive staking policies under the three bonus-bet rules
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
