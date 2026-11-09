# Neon Reaper 3.0 "DREAD" - independent math / wallet audit

Auditor: independent agent. Branch `worktree-agent-a8e45ec3c88953e6d`, based on `ccr-4739e32a-lc758b` @ 8752596 ("Merge CHARS-A final").
Scope: module `:reaper` only (`engine/*`, `data/Wallet.kt`, `tools/reaper_model.py`); `:app` (Dragon's Hoard) was not touched or audited.
Claim under test: *both games target 94.00 % RTP; the house keeps an edge no matter what the player does and the game cannot lose money over time.*
Everything below was measured with code that does **not** import the author's `tools/reaper_model.py` or `ExactMath.kt` (the author's model is
only compared against, never used as an input). All numbers come from the shipped constants: `tools/audit/config_snapshot.txt` is a dump of the real
`ReaperConfig.kt`, kept honest by `AuditTest.configSnapshotIsCurrent`.

## 0. Verdict

**The house edge holds.** I could not find any stake pattern, play pattern, feature-buy timing, quit/restart trick, rounding rule, crash/replay sequence
or file edit that gives the player a positive expected result, or any RTP above the target.

* Measured RTP (constant stake, 25-book cap included): **94.000 % at stake 1** (95 % CI 93.999-94.001), 93.999 / 93.996 / 93.992 / 93.984 % at stakes 2 / 3 / 4 / 5, falling to 93.83 % at 10, 91.86 % at 25 and **88.93 % at 40**.
  The headline "94.00 %" is true to within 0.001 percentage points at stake 1 (the exact value of the shipped constants from the author's own exact means is 94.0003 %; my exhaustive enumeration + exact vault distribution + 10^9-session Seance Monte-Carlo gives 94.0000 % +/- 0.0004).
* The best staking policy that exists is "play stake 1" (94.000 %). An **exact** search over all 4368 vault cycles (every adaptive staking policy is one of them) and an optimal-stopping DP over (relics, stake sum) with every stake, quitting and both feature buys available returns **0.000 tokens of expected profit** for every horizon up to 3000 spins.
* Feature buys return 92.79 % (Seance) and 91.46 % (Vault) at stake 1 and less at every higher stake: prices 31x are >= exact EV / 0.93 (30.93 and 30.49).
* No finding changes the house edge. 12 findings (0 critical / 0 high / 0 medium / 6 low / 6 info) are listed in section 5: the most relevant are AUD-1 (the 25-book cap is per component, so one paid spin can return up to 3 caps = 6000 tokens), AUD-2 (the simulation tests that are said to verify "94 % at every stake" can only resolve about +/- 1 point), AUD-5 / AUD-6 (wall-clock password lockout, no rollback protection of the signed wallet) and AUD-9 (stale rules text).
* No main source file was changed. 5 new test classes (+ 2 helpers and tools/audit) are added; all pass on the current code (2 tests are `@Ignore`d on purpose to document AUD-6 and AUD-8).

## 1. RTP, independent measurement

### 1.1 Method

RTP at a constant stake `s` is the sum of independent pieces, each a mean of `min(payout, c)` with the cap `c = 2000 tokens / s` (in multiples of the stake):

    RTP(s) = (1-f) [ E min(X_plain,c) + sum_k Pp(k) M_k(c) ]
           +   f   [ sum_t w_t E min(X_t,c) + sum_k sum_t w_t P_t(k) M_k(c) ]
           + (1/135) E min(V,c)

`f` = FEATURE_RATE (an exact constant of the game), `w_t` the mini-feature weights, `X` = line wins x multiplier + soul coins + coffin pay on the final grid,
`M_k` = THE SEANCE started by `k` coffins, `V` = Soul Vault total (bonus bet = stake for constant stakes). Because the game is a renewal process the vault term is exactly `p_relic / 5 = 1/135` per spin.
The decomposition is exact; how each piece was obtained is what makes the check independent and tight (this is the "importance splitting / component-wise simulation" asked for):

| piece | how it was obtained | sampling error |
|---|---|---|
| plain spin `X_plain`, `Pp(k)` | **exhaustive enumeration of all 74^5 = 2,219,006,624 reel-stop combinations** (`tools/audit/enum_grid.c`, 50 s), every one of the 20 lines evaluated by my own rule implementation | none (exact) |
| Seance free spin `(line multiple, coffins)` | exhaustive enumeration of all 58^5 = 656,356,768 free-spin stop combinations | none (exact pmf) |
| Reaper's Touch | exhaustive enumeration for each of the 5 wild-reel patterns | none (exact) |
| Demon's Bargain | exact convolution of the plain-grid pmf with the exact coin-total pmf | none (exact) |
| Murder of Crows, Skull's Curse | C Monte-Carlo, 4x10^8 spins each (`audit_mc mini`) | <= 0.0008 x stake per mini spin |
| THE SEANCE for 3 / 4 / 5 coffins | C Monte-Carlo, 10^9 / 6x10^8 / 4x10^8 sessions drawn from the **exact** free-spin pmf, ramp x1..x10 and retrigger rule applied spin by spin | 0.0009 / 0.0019 / 0.0035 x stake |
| Soul Vault | **exact distribution of the vault total** (`tools/audit/vault_exact.py`: the (filled cells, respins, soul sum, jackpot sum) chain propagated as a probability array, 15 s) - gives E[V] and E[min(V, c)] for every cap with no sampling; cross-checked by a C Monte-Carlo of 3x10^8 vaults written from the rules (28.3489 +/- 0.0013 vs exact 28.351329, z = -1.9) | none (exact) |
| self-test of the MC code | Reaper's Touch and Demon's Bargain also simulated (10^8 each) and compared with their exact values: z = -1.26 and +0.19 | |

The shipped Kotlin engine is tied to this in three further ways (all in the new tests): `AuditDifferentialTest` demands **exact equality** between the engine and a
second implementation written from the rules on 500k spins / 360k Seances / 400k vaults; `AuditSimTest` compares every engine component mean with the reference
(|z| < 1.4 everywhere); and a 10^8-spin run of `Round.play` (the function the Wallet calls) at stakes 1, 10 and 40 (section 1.4).

### 1.2 Stake-1 budget (no cap), claimed vs measured

| component | claimed (`ReaperConfig`) | measured | notes |
|---|---|---|---|
| lines + mini features + soul coins | 57.77 % | **57.768 %** | plain-grid mean 0.5065465 exact; minis exact/MC |
| coffin scatter pays (2 / 10 / 50 x) | 1.11 % | **1.113 %** | exact |
| THE SEANCE | 14.12 % | **14.117 %** | one per **213.2** spins (claimed ~213), mean **30.09 x** stake (claimed ~30) |
| Soul Vault | 21.00 % | **21.001 %** | one per 135 spins, mean **28.351329** x bonus bet (exact; the author's DP gives 28.351329, P(full board) 1 in 111,643 identical) |
| **total** | **94.000 %** | **94.0000 %** (95 % CI 93.999-94.001) | |

Other published figures: plain-spin win frequency 31.28 % = 1 in 3.20 (claimed 31.3 %, 1 in 3.2; exact, exhaustive); win frequency over all spins 31.6 % (1 in 3.16);
mini feature 1 in 154 spins (exact constant); **every win pays at least the stake**: smallest positive total over all 2.2 x 10^9 plain grids is exactly 1 x stake and the cap (2000) is >= the largest stake (40).
Bonus sizes: Seance started by 3 / 4 / 5 coffins = 28.765 / 58.620 / 119.689 x stake (8.33 / 12.50 / 20.83 spins played), 95.7 % / 4.2 % / 0.07 % of Seances; P(Seance > 50x) = 15.0 % / 47.6 % / 87.6 %.
Volatility: SD of the return of one spin = **4.78 x stake** (10^8 spins), so (normal approximation, stake 1) a player is ahead of the house after N spins in about 45 % of sessions at N=100, 35 % at 1,000, 10 % at 10,000 and 0.004 % at 100,000.

### 1.3 RTP by stake (constant stake), 95 % CI = +/- 1.96 SE

Shares of the wager; "spin pays" = lines + minis + soul coins + coffin pays (capped jointly per spin). SE (all pieces combined) is 0.0003-0.0004 percentage points.

| stake | spin pays | Seance | Vault | **RTP** | 95 % CI | published `RTP_BY_STAKE` | diff (pp) | buy Seance | buy Vault |
|---:|---:|---:|---:|---:|---|---:|---:|---:|---:|
| 1 | 58.882 | 14.117 | 21.001 | **94.0000 %** | 93.999 - 94.001 | 94.00 | -0.000 | 92.79 % | 91.46 % |
| 2 | 58.882 | 14.117 | 21.000 | **93.9993 %** | 93.998 - 94.000 | 94.00 | -0.001 | 92.79 % | 91.45 % |
| 3 | 58.881 | 14.117 | 20.998 | **93.9961 %** | 93.995 - 93.997 | 94.00 | -0.004 | 92.79 % | 91.44 % |
| 4 | 58.880 | 14.115 | 20.997 | **93.9921 %** | 93.991 - 93.993 | 93.99 | +0.002 | 92.78 % | 91.44 % |
| 5 | 58.879 | 14.109 | 20.996 | **93.9840 %** | 93.983 - 93.985 | 93.98 | +0.004 | 92.75 % | 91.44 % |
| 8 | 58.868 | 14.058 | 20.990 | **93.9173 %** | 93.916 - 93.918 | 93.91 | +0.007 | 92.45 % | 91.41 % |
| 10 | 58.855 | 14.003 | 20.972 | **93.8301 %** | 93.829 - 93.831 | 93.82 | +0.010 | 92.13 % | 91.33 % |
| 15 | 58.805 | 13.787 | 20.685 | **93.2764 %** | 93.276 - 93.277 | 93.26 | +0.016 | 90.92 % | 90.08 % |
| 20 | 58.726 | 13.454 | 20.436 | **92.6171 %** | 92.616 - 92.618 | 92.60 | +0.017 | 89.03 % | 89.00 % |
| 25 | 58.643 | 13.038 | 20.184 | **91.8645 %** | 91.864 - 91.865 | 91.84 | +0.024 | 86.59 % | 87.90 % |
| 30 | 58.555 | 12.574 | 19.866 | **90.9948 %** | 90.994 - 90.995 | 90.97 | +0.025 | 83.81 % | 86.51 % |
| 40 | 58.323 | 11.594 | 19.015 | **88.9313 %** | 88.931 - 88.932 | 88.90 | +0.031 | 77.75 % | 82.81 % |

No stake exceeds the target (highest RTP + 3 SE = 94.0013 % at stake 1, i.e. 94.00 % to rounding). The published table is conservative by up to 0.031 pp (AUD-3).
RTP is non-increasing in the stake, as the "linear or concave" invariant requires (section 4d).

### 1.4 End-to-end check of the shipped `Round.play` (the function the Wallet calls)

`AUDIT_SCALE=50 gradle :reaper:testDebugUnitTest --tests '*AuditSimTest.fullRounds*'` (10^8 spins per stake, real engine, real relic meter, real RNG order):

| stake | engine RTP | reference | z | Seances / expected | Vaults / expected |
|---:|---|---|---:|---|---|
| 1 | 94.00 % +/- 0.05 | 94.000 % | +0.1 | z +1.69 | z -0.06 |
| 10 | 93.80 % +/- 0.05 | 93.830 % | -0.6 | z +0.60 | z -0.17 |
| 40 | 88.96 % +/- 0.04 | 88.931 % | +0.77 | z +0.51 | z -0.29 |

## 2. Exploit hunt

### 2.1 Why the search can be exact (and what it gives)

Spin outcomes (lines, minis, coffins, Seance) have the same distribution at every spin and the stake only scales them (cap aside), so the only state a player can
influence is the relic meter (relics held `r`, sum `S` of the stakes on the relic-revealing spins). A relic is revealed with probability `p = 5/135` whatever the
stake; between relic events the state does not change, so an optimal policy repeats one stake per state and **a policy is a sequence of five stakes per vault cycle**;
the vault pays `floor(sum/5)` as bonus bet (`RelicMeter`). Return per token wagered of a cycle: `(sum s_i a(s_i) + p E[min(2000, V b)]) / sum s_i` with `a(s)` the measured non-vault return per token at stake `s`.

* **Exhaustive cycle search** (`tools/audit/policy_mdp.py`, `AuditTest.noFiveStakeVaultCycle...`): all 4368 multisets of five stakes. Best = (1,1,1,1,1) **94.000 %**; best policy that mixes stakes (1,2,2,2,3) 93.999 %; worst (1,1,1,1,5) 84.66 %; (1,1,1,1,40) = 89.28 %, (1,1,40,40,40) = 89.87 %, constant 40 = 88.93 %.
  Power check (same code, other rounding rule): if the bonus bet were **rounded up** the best cycle (1,1,1,1,2) would return **108.0 %**; rounded to nearest **99.25 %** (1,1,2,2,2). Only the `floor` of the shipped rule keeps every cycle <= 94 %, exactly as `RelicMeter` documents.
* **Optimal stopping / buys DP**: backward induction over (relics, stake-sum) with all 12 stakes, "quit" and both feature buys as actions: best expected profit from an empty meter = **0.000000000 tokens** for horizons 1, 5, 10, 50, 100, 500, 1000, 3000 (quitting at once is optimal). No autoplay limit, session limit or restart can change that: they are stopping rules.
* **Stored value of a pre-built meter** (a property worth knowing, not an edge): 4 relics collected at stake 40 are worth +844 tokens to whoever plays on (the original player wagered ~4320 tokens and expected to lose ~1300 of them), 4 relics at stake 10 +219, at stake 1 +21. The meter is therefore transferable value if a device is handed from player to player; the house total is unchanged (Wald).
* **Wald check on the real engine** (`AuditTest.relicSpinsAreRandomSamples...`): for three adaptive policies the sum of stakes on relic-revealing spins equals `p x tokens wagered` (z = 0.36 / -0.85 / -0.84). This is the one fact the strategy-proofness argument rests on (relic chance independent of stake and history); the test fails if a future change breaks it.

### 2.2 Adaptive policies replayed on the real engine (`AuditExploitTest`, 1.2 x 10^6 spins each, batch-means SE)

| policy | RTP | exact (cycle formula) |
|---|---|---|
| constant stake 1 / 5 | 94.78 +/- 0.41 / 94.33 +/- 0.40 (+1.9 sigma noise) | 94.000 / 93.984 |
| stake 1, stake 40 once 4 relics held | 89.74 +/- 0.52 | 89.28 |
| stake 1, 40 once 2+ relics and projected bonus bet >= 8 | 90.07 +/- 0.34 | 89.87 |
| 40 right after a relic was revealed | 92.19 +/- 1.08 | |
| 40 for <= 3 spins after a relic with 3+ relics held, else 2 | 90.54 +/- 0.64 | |
| martingale (double after each loss, cap 40) | 91.45 +/- 0.47 | |
| anti-martingale / Paroli (double after each win up to 3) | 91.38 +/- 0.32 | |
| stake ramp after losses, reset on a win | 91.44 +/- 0.45 | |
| bankroll chase (40 when 100+ tokens down) | 89.28 +/- 0.24 | |
| "house money" (25 when ahead, else 1) | 93.36 +/- 0.40 | |
| random stake each spin / alternate 1,40 | 90.58 +/- 0.48 / 89.59 +/- 0.34 | |
| **oracle that sees the spin before staking** (power check) | **360.4 %** | |

The simulation SE (0.2-1.1 pp) is the honest resolution of this table; the rigorous statement is the exact search above. The oracle row shows the harness would notice a real exploit.

### 2.3 Every idea in the brief, one by one

| idea | outcome |
|---|---|
| raise the stake only when the RelicMeter is high / only when relics are close | 89-90 % (exact cycle), see above |
| lower the stake right before a trigger resolves; change stake between trigger and replay | impossible by construction: a spin is settled and saved at press time; the Seance / Vault are paid for the stake that triggered them (`theBonusIsPaidForTheStakeThatTriggered...`, bet 25 stays 25 after the stake is set to 1) |
| martingale, anti-martingale, ramps, bankroll chasing | all < 94 % (table 2.2); higher stakes only lower RTP (cap) |
| feature buy "when favourable" (full meter, after / before cap effects, highest stake) | a buy never touches the relic meter (`aBuyNeverTouchesTheRelicMeter`), so meter state cannot make it favourable; buy RTP is 92.79 % / 91.45 % at stake 1 and **decreases** with the stake because the 25-book cap compresses the tail (77.75 % / 82.80 % at 40); the DP never chooses a buy |
| autoplay with limits, quitting, restarting | stopping rules cannot create expectation; restarting keeps the meter (it is saved), `factoryReset` zeroes it |
| cash-out rounding to macks | rounds DOWN, odd tokens (< 10) stay on the meter; never pays more than requested or than the balance (`cashOutsRoundDown...`) |
| Money formatting edge cases | exact for all non-negative Long values (up to 115292150460684697 books 4 macks 7 tokens); negative values are mis-formatted by `describe` / `short` but the app never passes one (net result uses abs) |
| integer rounding: Seance bet, bonus bet, win, cap | Seance bet = triggering stake (integer), bonus bet = floor of the mean (never up), wins are exact tokens, `settle` is `coerceIn(0, 2000)`; no rounding anywhere can favour the player |
| 25-book cap tail at the highest stake | only ever lowers RTP |

## 3. Persistence, crash safety, double-pay, tampering (`data/AuditWalletTest`, Robolectric)

The spin, the debit, the credit, the relic meter and the seeds + bets of any bonus are written with **one** `save()` (tmp file + `fd.sync()` + rename), so a kill at any instant leaves either the pre-spin or the post-spin file.

| scenario | result |
|---|---|
| kill right after a spin that starts a Seance (3 restarts: before watching, half-way, after `markSeanceSeen`) | credit saved atomically (`balance = before - stake + win + Seance`), `pendingSeance()` regenerates the **identical** session (seed, stops, multipliers, retriggers, `totalMult`), spins / buys / cash-outs refused until it is marked seen, `markSeanceSeen` twice is harmless, credited exactly once, counters (`seances`, `seanceWon`) right |
| same for a Soul Vault (meter pre-loaded to 4 relics, seed searched with the same `Round.play`) | identical replay (cells, kinds, steps, `totalMult`, bonus bet 10), exactly once |
| one spin that unlocks both bonuses, kill between them | Seance marked seen, kill: the Vault is still pending and identical; both paid once |
| bonus bet / Seance bet vs stake selected later | paid for the triggering stake (25), unaffected by changing the stake to 1 before the replay |
| kill between tmp-write and rename (tmp path unwritable -> `IOException`) | wallet.dat untouched, restart shows the pre-spin state: the spin never happened and its result was never shown |
| buy then kill; two buys in a row; buy while a bonus is pending | the bought bonus replays identically; second buy refused until it is seen, then allowed; price counted as wagered |
| buy with insufficient credit (309 tokens for 310) | `null`, nothing changes, nothing written |
| stake 0, -1, -10, 6, 7, 9, 11, 41, 100, Int.MIN/MAX for spins and buys | refused, balance / counters / file bytes unchanged |
| 3500 random operations with 149 kills at random moments (spins, buys, marking, partial viewing) vs an independent ledger | balance, `totalWagered`, `totalWon` and pending flags exact after every operation and every restart (2606 spins, 230 buys) |
| balance 4 x 10^18 tokens | still exact over 300 spins at stake 40; wrap-around at Long.MAX is unguarded (AUD-8, unreachable) |
| deposits | 0, negative, > 10,000,000 refused; wrong password refused; the 5th wrong password locks (persisted across restarts) |
| tampering: 13 different edits that keep the old signature (balance, unseen flag, relic stake sum, bet, stake index, a space, missing / flipped / foreign signature, truncation, empty, garbage) | **all** load as TAMPERED (TILT): spins, buys, deposits, cash-outs refused, `save()` refuses to overwrite the evidence |
| TILT -> reset | `factoryReset(null)` wipes balance / flags / password (no password needed), nothing pending survives (player-hostile, house-neutral: AUD-11) |
| restoring an older, validly signed `wallet.dat` | **accepted** (AUD-6, needs file access; `allowBackup=false` and the extraction rules close backup / device-transfer) |

## 4. Claim checks

* **(a) every base-game win >= stake**: true. The engine's line rule equals an independent reference rule on all 12^5 = 248,832 symbol combinations; the smallest positive total over all 2.2 x 10^9 reel stops is 1 x stake; random spins / Seances / vaults at every stake never pay between 0 and the stake (vault >= min(5 bet, cap)).
* **(b) the cap is applied consistently**: every credit path in `Wallet` uses `payTokens` (= `settle(raw)` = `coerceIn(0, 2000)`) for a spin, a Seance, a Vault and for bought bonuses; no rawTokens reach the balance. **But the cap is per component, not per round** (AUD-1): a spin + the Seance + the Vault it unlocks are capped separately, so one paid spin can return 3 x 2000 = 6000 tokens (75 books). `AuditTest.theCapIsAppliedToASpinASeanceAndAVaultSeparately` builds such a round (6000 tokens). The in-app rules say "per spin and per Soul Vault" and omit the Seance.
* **(c) buy prices >= exact EV / 0.93**: Seance (3-coffin award) EV 28.7649 x stake -> 30.93 <= 31; Vault EV 28.3489 x bonus bet -> 30.48 <= 31; the author's exact values (28.7652, 28.3513) give 30.93 / 30.49. Margin on the Seance price: 0.07 of 31 (0.2 %).
* **(d) every money component is linear in the stake or concave**: confirmed in code (the only stake-dependent operations are `raw = mult x stake`, `coerceIn(0, 2000)`, the floored mean in `RelicMeter`, and `price = 31 x stake`) and by test (`everyMoneyComponentIsLinear...`: same random draws at all 12 stakes: the multiple of the stake never depends on the stake, `tokens = min(2000, mult x stake)`, tokens / stake never increases with the stake) for spins, minis, Seances and vaults.

## 5. Findings

| id | severity | title | reproduce | suggested fix |
|---|---|---|---|---|
| AUD-1 | low | The 25-book cap is **per component** (spin, Seance, Vault), so one round can pay up to 6000 tokens (75 books) | `AuditTest.theCapIsAppliedToASpinASeanceAndAVaultSeparately` | either document "per component" everywhere (and add the Seance to the rules sentence) or cap `RoundResult.totalTokens` as a whole (lowers RTP only in astronomically rare rounds; the published table is already below the target) |
| AUD-2 | low | The simulations that the docs cite as verifying "94 % at every stake" cannot resolve better than about 1 point: `FullGameTest` accepts `max(5 SE, 0.003)` with SE = 0.24 pp at 4M spins (+/- 1.2 pp), `NoExploitTest` policies have SE 0.2-0.4 pp. Only the exact tests carry weight. | read the tolerances | keep them as smoke tests; use `AuditTest` (deterministic 4368-cycle search + engine-vs-reference), `AuditSimTest` and `tools/audit` for real resolution; add `--tests "*AuditTest*"` (10 s) to `build.yml` |
| AUD-3 | low | Published `RTP_BY_STAKE` is conservative by 0.015-0.031 pp for stakes 15-40 (measured 88.931 % vs 88.90 % at stake 40); `CalibrationTest` tolerance is 0.2 pp | section 1.3, `AuditSimTest.publishedRtpTableIsConservativeAndWithinTolerance` | paste the measured column into `RTP_BY_STAKE` (the player-facing text uses 1 decimal, so only the stake-25 line would change: 91.8 -> 91.9) |
| AUD-4 | low | `tools/reaper_model.py check` compares only part of the config (reel **counts**, pays, scatter / free-spin tables, feature rate, relic chance, buy prices). Not checked: paylines, the actual strip order, mini-feature / coin / raven / skull tables, every Soul Vault parameter. Today everything is identical. | `python3 tools/audit/check_author_model.py` (0 differences) | run that script (or the snapshot test) in CI |
| AUD-5 | low | Password lockout is wall-clock based (`System.currentTimeMillis`, `lockUntil > now`): setting the device date forward ends a lockout and gives unlimited guesses; PBKDF2-SHA256 uses 40,000 iterations (OWASP 2023: 600,000), minimum password length 4 | code review (the JVM test clock cannot be moved); persistence of the counter is tested | count failures without time decay (or use `elapsedRealtime` + boot id), raise iterations (the per-wallet `iter` field allows an in-place upgrade) |
| AUD-6 | low | No rollback protection: the HMAC covers the file contents only, so restoring an older valid `wallet.dat` is accepted (free re-roll of a loss) | `AuditWalletTest.currentlyARestoredOlderWalletIsAccepted` (passes today), `restoringAnOlderSignedWalletIsDetected` (`@Ignore`, desired behaviour) | needs root / file access (`allowBackup=false`, backup and device-transfer exclude files); mitigation: a monotonic spin counter stored in a second signed location (does not stop a root user who restores both) |
| AUD-7 | info | `save()` fsyncs the temp file but not the directory after the rename; a power cut right after a losing spin could in theory restore the pre-spin file (not reproducible here) | review | open the parent directory and `FileDescriptor.sync()` it, or use `AtomicFile` |
| AUD-8 | info | No overflow guard at `Long.MAX_VALUE`; unreachable (a deposit is <= 10,000,000 and needs the password; a round pays <= 6000) | `AuditWalletTest.balanceNearLongMaxDoesNotWrapNegative` (`@Ignore`) | `Math.addExact` in `deposit` |
| AUD-9 | info | Stale player-facing / project text: the rules dialog says "MINI FEATURES (about 1 spin in 100)" (real: 1 in 154) and "about one spin in 25 reveals a relic" (1 in 27), does not describe the coffin scatter or THE SEANCE and lists the cap "per spin and per Soul Vault"; "Pumpkin's Treats" naming; `VaultView.kt:582` has a dead "rounded down to whole macks" branch; `docs/MATH.md` still shows the 2.1.0 budget (59.34 / 11.98 / 22.68) and buy text | read `Dialogs.info()`, `docs/MATH.md` | re-check the final DIALOGS text against section 1.2 of this file; update MATH.md |
| AUD-10 | info | Bought bonuses are celebrated with the same win tiers / `SÉANCE TOTAL` fanfare even when they return less than the 31 x stake paid (e.g. 5 x). UKGC-style "losses disguised as wins" rule is applied to spins only | `ReaperView.finishSeance` (`soulRate`), `onStopped` (tier from `win/stake`) | compare against the price for bought rounds and stay quiet when the return is below it |
| AUD-11 | info | TILT is final: any corruption (not only tampering) wipes the credit; the reset needs no password and clears the password | `AuditWalletTest.tiltThenResetWipesTheBalance...` | keep a previous generation (`wallet.bak`) and offer recovery by staff |
| AUD-12 | info | The relic meter is transferable value (up to ~844 tokens for 4 relics at stake 40): if a device is passed between players the next one profits from relics the first one paid for. Group RTP is unchanged (Wald). | `policy_mdp.py` section (3) | none needed; document |

## 6. What I tried that did NOT work (the house held)

* beating the bonus-bet rule: all 4368 five-stake cycles <= 94.000 %; mixing stakes loses to the floor; "bet big when relics arrive fast" (the exploit the previous audit found, 101-104 % on the retired rule) now returns 89-90 %;
* any stopping rule or quit / restart policy (DP value 0 at every horizon); autoplay limits are stopping rules;
* feature buys at any stake or meter state (best case 92.79 % / 91.45 %, falling to 77.75 % / 82.80 % at stake 40); buying cannot change the meter;
* changing the stake between the trigger and the replay, or after a kill: the bonus keeps the triggering stake (stored with the seed);
* replaying a Seance / Vault for money: any number of kills, reloads and `markSeen` calls pay exactly once (ledger test with 149 kills);
* two buys in a row, buying while a bonus is pending, buying without credit, stake 0 / negative / off-list: all refused with no state change;
* editing the signed file (13 variants) or its signature: every variant lands in TILT; a tilted wallet cannot spin, buy, deposit or cash out and does not overwrite its file;
* cash-out rounding, Money formatting, integer rounding anywhere on the money path: all favour the house or are exact; the cap is applied on every credit path;
* the Python model vs the shipped constants: reel strips (order), paylines, every feature table and every vault parameter are identical; the author's exact means agree with my enumeration to 15 digits (plain-grid mean 0.5065464824858494 vs 0.5065464824858495, coffin chance 0.0047031207, Seance spin mean 0.7329318192).

## 7. Reproduce

```bash
python3 tools/audit/run_audit.py --out build/audit              # gcc + exhaustive enumeration + C Monte-Carlo + report (~10 min, one core)
python3 tools/audit/policy_mdp.py build/audit                   # exact best-policy search, optimal-stopping DP, stored meter value
python3 tools/audit/check_author_model.py                       # tools/reaper_model.py vs every shipped constant
gradle :reaper:testDebugUnitTest --tests '*AuditTest*' --tests '*AuditWalletTest*'                 # ~30 s, suggested for CI
gradle :reaper:testDebugUnitTest --tests '*AuditDifferentialTest*' --tests '*AuditSimTest*' --tests '*AuditExploitTest*'   # ~1.5 min
AUDIT_SCALE=50 gradle :reaper:testDebugUnitTest --tests '*AuditSimTest.fullRounds*'              # the 10^8-spin run of section 1.4
```
When `AuditTest.configSnapshotIsCurrent` fails the money maths was edited: copy `reaper/build/audit/config_snapshot.txt` over `tools/audit/config_snapshot.txt`,
re-run the three Python commands and refresh `AuditReference` (see its comment).

## 8. Files added (no main source changed)

`tools/audit/{enum_grid.c, audit_mc.c, audit_common.h, audit_lib.py, audit_rtp.py, vault_exact.py, run_audit.py, policy_mdp.py, check_author_model.py, config_snapshot.txt}`;
tests `engine/{AuditTest, AuditDifferentialTest, AuditSimTest, AuditExploitTest, AuditReferenceModel, AuditConfigSnapshot}.kt`, `data/AuditWalletTest.kt`.
