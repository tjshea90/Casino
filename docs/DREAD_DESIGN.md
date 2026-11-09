# Neon Reaper 3.0 "DREAD" — design notes

Scope: module `:reaper` only (Dragon's Hoard is untouched). This document records *why* the game is built the way it is:
what major-brand online slots do to keep players engaged, which of those techniques this game copies, which it
**refuses** on purpose, and the audio / visual direction. The money maths lives in `docs/MATH.md`.

## 1. What major brands do (and what we do about it)

Sources are summarised at the end. Industry blogs and affiliate sites disagree with each other on specifics, so numbers
quoted from them are *illustrative*; the design rests on the maths in `tools/reaper_model.py`, which is recomputed in tests.

| # | Technique (brand examples) | Why it works | Neon Reaper 3.0 |
|---|---|---|---|
| 1 | **Variable-ratio rewards**: unpredictable wins at a decent hit rate (typical online slot ≈ 25–35 % of spins pay) | The strongest known reinforcement schedule | base hit rate ≈ 1 in 3, every win pays ≥ the stake |
| 2 | **Layered features**: random modifiers in the base game + a bonus round that carries most of the "wow" (Pragmatic, NetEnt, Play'n GO) | Variety of surprise; the bonus is the dream | 4 mini features, **Soul Vault** (relic meter → hold & spin, jackpot tiers), **THE SÉANCE** (3+ coffins → free spins with a rising multiplier) |
| 3 | **Progress meters / collect-to-unlock** (Lightning Link, Dragon Link) | Goal gradient: you feel close to something | 5-relic meter feeds the Vault; Soul Rank bar |
| 4 | **Free spins with multipliers and retriggers** (Gates of Olympus, Sweet Bonanza, Dead or Alive) | Escalation inside a bonus; "it could go on" | Séance: 8/12/20 spins, multiplier ×1…×10, +spins on retrigger |
| 5 | **Anticipation ("tease")**: the last reels slow down, music rises, lights swell when 2 of 3 trigger symbols have landed | Peak arousal comes from *expecting* the win, measured physiologically | coffin / relic anticipation: remaining reels slow, heartbeat + riser, screen darkens. Only triggered by symbols that really landed |
| 6 | **Escalating celebration tiers + count-up** ("Big / Mega / Epic win"), sound pitch rising with the counter | Makes wins feel larger and gives time to savour them | SOUL REAPED 10× · DEATH'S TOLL 25× · HELLFIRE 50× · APOCALYPSE 100×, locked count-up, skippable only after the amount is shown |
| 7 | **Multisensory reinforcement** (light + sound + haptics + motion synchronised to the result) | Strong cue–reward association | 3D camera shake, lightning, bloom flashes, vibration, tiered stingers, characters react |
| 8 | **Feature buy / bonus buy** (Pragmatic, Hacksaw, Nolimit; ≈ 100× bet is the common price) and **ante / double-chance bets** (+25 % stake for ~2× feature chance) | Agency and instant access to the bonus | Feature Buy for the Séance and the Vault, priced **above** their exact expected payout (RTP of a buy ≤ 92 %). No ante bet (see §3) |
| 9 | **Autoplay with stop conditions** | Convenience; regulated brands add win/loss limits | autoplay with stop-on-feature, big-win and loss limits |
| 10 | **Loyalty / levels / missions** (BetMGM, Caesars, DraftKings) | Long-term return motive | **Soul Rank** (lifetime wager → titles) and **Reaper's Ledger** (achievements). Cosmetic only |
| 11 | **Dark-pattern techniques** — losses disguised as wins (LDW), engineered near-misses, "hot/cold" machines | LDWs inflate players' belief that they are winning; near-misses raise arousal and the urge to continue (Dixon et al. 2010/2011; Graydon et al. 2017 review) | **Refused**, see §2 |

## 2. What we refuse on purpose

* **Losses disguised as wins.** Every Neon Reaper win returns at least the stake, and nothing celebrates a return below the
  stake. (Great Britain banned celebrating returns ≤ stake in 2021.)
* **Engineered near-misses.** Reel strips are not weighted to show extra "almost" combinations. The anticipation effect is
  triggered by what has *actually* landed (two coffins already on the reels, the third reel still turning); it does not change the odds.
* **Free-credit promos.** Daily bonuses, free spins "gifts" and refill offers would make the machine lose money by design.
  Rank and ledger rewards are cosmetic.
* **Fake information.** No "due" / hot / cold meters. The info screen states the true RTP, hit rate and feature frequencies.
* **Real progressive jackpots** (pool-dependent RTP). The Vault tiers are fixed so the house edge is exact.

## 3. Player-protection features (what regulated brands must do, copied here)

* Session clock + wagered/won/**net result** always one tap away (Stats), and a **reality check** every 30 minutes
  (configurable 15 / 30 / 60 / off) that shows time played and net result before play continues.
* Autoplay stop conditions: single win ≥ N×, balance down by N tokens, feature triggered.
* "Reduce flashing" option (caps strobe-like bloom/lightning and screen shake).
* No ante bet: it raises the cost of every spin for a bigger feature chance; the feature buy is already an explicit, priced choice.

## 4. RTP choice

**94.00 %** (house keeps 6 % of every token wagered). Regulated online slots span roughly 92–97 % (96 % is the common
headline; operator-configurable variants go lower). 94 % was the player's own choice in session 6, leaves a safe margin for
variance and for the 25-book cap, and every new feature is priced at or below it. The invariants that make the edge hold
*whatever the player does* are listed in `docs/MATH.md §House-edge invariant`.

## 5. Art direction — "less childish, more dread"

* Palette: near-black stone and iron, **bone** white, **blood** red, sickly **toxic green** (the "neon", kept as the brand
  accent), cold moon-blue rim light. No orange pumpkins, no purple/cyan arcade UI.
* Cast: Grim Reaper (wild), Plague Doctor, Cursed Skull, Blood Raven, Demon, Wraith (a screaming banshee), Coffin (scatter).
  No smiling faces anywhere; expressions are hollow, snarling or wailing.
* World: ruined gothic crypt and graveyard under a blood moon, candles and chains, fog banks, ravens, a cabinet that looks
  like a stone-and-iron altar.
* Rendering: bloom, vignette, film grain and colour grading; dynamic candle lights; merged vertex-coloured meshes
  (fewer draw calls than before); quality tiers (Auto / Low / Medium / High) so the 2019-class tablets keep 30 fps.

## 6. Audio direction

Dark ambient / horror-film score instead of the old music box: low drones, ghost-choir pads, tolling bells, war drums,
heartbeat pulses, adaptive intensity (calm → spinning → feature → big win), escalating win stingers, character voices that are
whispers, growls, rattles, caws and wails. See `docs/AUDIO.md` once the sound design lands.

## Sources (general background; treat figures as illustrative)

* Near misses and losses disguised as wins: Dixon et al. 2011, *Dual effects of 'losses disguised as wins' and near-misses in a slot machine game* ([Waterloo copy](https://uwaterloo.ca/reasoning-decision-making-lab/sites/default/files/uploads/files/DixHarJarFugShe_2011.pdf)); Graydon et al. 2017 systematic review ([J Gambl Stud](https://link.springer.com/article/10.1007/s10899-017-9688-0)); [PMC5663799](https://pmc.ncbi.nlm.nih.gov/articles/PMC5663799/).
* UK Gambling Commission 2021 slot rules (no turbo / slam stop / autoplay, no celebration of returns ≤ stake, session time and net shown): [iGaming Business](https://igamingbusiness.com/gc-proposes-end-to-autoplay-and-quick-spin-features/), [Jurist](https://www.jurist.org/news/2021/02/great-britain-gambling-commission-introduces-stronger-regulations-for-online-slots-games), [Casino Beats](https://casinobeats.com/2021/02/02/ukgc-introduces-package-of-enhanced-protections-for-online-slots/).
* Ante bet / bonus buy / typical RTP of Pragmatic Play titles (affiliate sources, treat as indicative): [Ante Bet and Bonus Buy in Megaways Slots](https://ccyd.co.uk/news/ante-bet-bonus-buy-megaways-guide.html), [double chance slots explained](https://cryptogamble-front.vercel.app/casino/guides/double-chance-slots-explained).
* RTP, hit frequency, volatility, PAR sheets: [Slot Math Models 101](https://mmccaa-site.pages.dev/blog/slot-math-model-par-sheet-en), [easy.vegas PAR sheets](https://www.easy.vegas/games/slots/par-sheets).
