#!/usr/bin/env python3
"""
Exact math model for Neon Reaper 3.0 "DREAD" (module :reaper): RTP budget, per-stake calibration, simulator.

RTP is CALCULATED, not guessed.  Every component's mean payout is computed in closed form:

  base game     20 paylines, independent weighted reels -> per-line expectation from per-reel symbol marginals
                (rows are consecutive strip positions under a uniform stop), x 20.  The coffin SCATTER pays nothing on a line.
  scatter       3/4/5 coffins ANYWHERE on the FINAL grid pay 2x/10x/50x the stake and start THE SEANCE.  Scatter counts are
                Poisson-binomial over the five reels (at most one coffin per reel window: asserted); mini features that turn
                cells/reels wild can erase coffins, which is a closed-form thinning (reel wilds / hypergeometric cells).
  mini features Reaper's Touch  (whole reels turn wild: marginal = delta(wild)),
                Murder of Crows (k random cells wild: hypergeometric over the line's cells),
                Demon's Bargain (soul coins add E[count] x E[value] on top of the base game),
                Skull's Curse   (2 random wild cells, line wins x E[multiplier]).
  THE SEANCE    free spins on richer reels with a multiplier that rises by one every spin (x1, x2 ... capped) and +spins on
                retrigger.  Because the multiplier depends only on the spin number, E[session] = mu_fs * E[sum of m_t], a
                small backward DP over (spin number, spins left).
  Soul Vault    Hold & Spin: a small Markov DP over (filled cells, respins left) with the exact expected sum of orb values
                (reaper orbs double the collectable sum), jackpot orbs, and the exact probability of the full-board GRAND.
  Feature buy   price = ceil(exact expected payout / 0.93)  -> a buy returns <= 93 % (lower once the cap bites).

The ONLY thing that needs simulation is the effect of the hard cap (25 books = 2000 tokens per spin / per Seance / per vault):
E[min(X*stake, 2000)] needs the *tail* of X.  We therefore compute
    RTP_component(stake) = exact_mean - E[(X - 2000/stake)^+]
where the second term is a small tail quantity measured with millions of samples (tiny absolute error).

Calibration: the chance f that a spin gets a mini feature is the SAME at every stake and is solved at stake 1 so that
    (1-f) A_base + f A_mini + (relic_p / 5) A_vault  =  TARGET_RTP   exactly.
The cap can then only ever LOWER the return at high stakes, so no staking pattern can push RTP above the target.

Usage:  python3 tools/reaper_model.py                # report on the config in CFG (budget, hit rate, per-stake RTP, buys)
        python3 tools/reaper_model.py exact          # exact means only (instant)
        python3 tools/reaper_model.py tune           # search reel counts for the budget targets in TUNE
        python3 tools/reaper_model.py exploit        # adaptive staking policies vs the three bonus-bet rules
        python3 tools/reaper_model.py check          # verify this file still equals ReaperConfig.kt
Kotlin mirror: ReaperConfig.kt (tests: RtpExactTest checks the exact means, CalibrationTest/FullGameTest simulate).
"""
import sys
from math import comb, ceil
from types import SimpleNamespace

import numpy as np

W, DOC, SKULL, RAVEN, DEMON, WRAITH, A, K, Q, J, TEN, SCAT = range(12)
NSYM = 12
REELS, ROWS = 5, 3
NAMES = ["Reaper(wild)", "Doctor", "Skull", "Raven", "Demon", "Wraith", "A", "K", "Q", "J", "10", "Coffin(scatter)"]
PAYLINES = np.array([
    [1, 1, 1, 1, 1], [0, 0, 0, 0, 0], [2, 2, 2, 2, 2], [0, 1, 2, 1, 0], [2, 1, 0, 1, 2], [0, 0, 1, 2, 2],
    [2, 2, 1, 0, 0], [1, 0, 0, 0, 1], [1, 2, 2, 2, 1], [1, 0, 1, 2, 1], [1, 2, 1, 0, 1], [0, 1, 1, 1, 0],
    [2, 1, 1, 1, 2], [0, 1, 0, 1, 0], [2, 1, 2, 1, 2], [1, 1, 0, 1, 1], [1, 1, 2, 1, 1], [0, 0, 2, 0, 0],
    [2, 2, 0, 2, 2], [0, 2, 2, 2, 0]])
NLINES = len(PAYLINES)
CAP_TOKENS = 2000            # 25 books x 80 tokens
STAKES = [1, 2, 3, 4, 5, 8, 10, 15, 20, 25, 30, 40]
MAX_FS_SPINS = 250           # hard stop of a Seance (the chance of ever reaching it is ~1e-30)
BUY_RTP = 0.93               # feature buys are priced so that RTP(buy) <= this at the cap-free stake

# ----------------------------------------------------------------------------------------------
# CONFIG -- mirror of ReaperConfig.kt (keep in sync; RtpExactTest checks the Kotlin side)
# ----------------------------------------------------------------------------------------------
CFG = SimpleNamespace(
    pays=[(6, 20, 100), (3, 10, 40), (3, 6, 25), (2, 5, 15), (2, 4, 10), (2, 3, 8),
          (1, 2, 6), (1, 2, 5), (1, 2, 4), (1, 2, 4), (1, 2, 3), (0, 0, 0)],      # x stake for 3/4/5 of a kind
    #       W DOC SK RV DE WR  A  K  Q  J 10 SC
    counts=[1, 3, 3, 5, 5, 5, 6, 6, 8, 9, 12, 2],                                   # symbols per BASE reel strip (all 5 reels)
    fs_counts=[2, 3, 3, 4, 4, 4, 5, 5, 6, 6, 7, 2],                                 # symbols per SEANCE reel strip
    scatter_pay={3: 2, 4: 10, 5: 50},                                               # x stake, any positions on the final grid
    fs_spins={3: 8, 4: 12, 5: 20},                                                  # spins awarded by 3/4/5 coffins
    fs_extra={3: 4, 4: 8, 5: 12},                                                   # extra spins when a Seance spin shows 3/4/5 coffins
    fs_ramp_cap=10,                                                                 # multiplier of spin t is min(cap, t)
    mini_weights=[0.25, 0.30, 0.30, 0.15],                                          # reaper, crows, demon, skull
    reaper_patterns=[[1], [2], [3], [1, 3], [1, 2, 3]], reaper_weights=[0.45, 0.25, 0.15, 0.08, 0.07],
    raven_wilds=[3, 4, 5, 6], raven_weights=[0.5, 0.3, 0.15, 0.05],
    coin_counts=[3, 4, 5, 6], coin_count_weights=[0.45, 0.3, 0.17, 0.08],
    coin_values=[1, 2, 3, 5, 10, 20, 50], coin_weights=[30, 25, 18, 13, 9, 4, 1],
    skull_mults=[2, 3, 5], skull_mult_weights=[0.6, 0.3, 0.1], skull_wilds=2,
    relic_count=5, relic_chance=5 / 125,                                            # a Soul Vault about every 125 spins
    vault_cols=5, vault_rows=4, vault_start=5, vault_respins=3, vault_orb_chance=0.03,
    orb_values=[1, 2, 3, 4, 5, 8, 10, 15, 25], orb_weights=[36, 24, 14, 9, 6, 5, 3, 2, 1],
    p_major=0.003, p_minor=0.012, p_mini=0.03, p_reaper=0.02, v_mini=15, v_minor=40, v_major=150, grand=1000,
    target=0.94,
)


def build_strip(counts, seed):
    """Port of ReaperConfig.buildStrip (Kotlin)."""
    n = len(counts)
    length = sum(counts)
    strip = [0] * length
    placed = [0] * n
    order = sorted(range(n), key=lambda s: (s * 7 + seed * 3) % n)
    prev = -1
    for pos in range(length):
        filled = pos + 1
        best, best_score = -1, float("-inf")
        for s in order:
            if placed[s] >= counts[s]:
                continue
            score = counts[s] * filled / length - placed[s]
            if s == prev:
                score -= 0.75
            if score > best_score:
                best_score, best = score, s
        strip[pos] = best
        placed[best] += 1
        prev = best
    return strip


def strips(counts):
    return [np.array(build_strip(counts, r)) for r in range(REELS)]


def scatter_presence(strip):
    """P(a reel window of 3 consecutive positions shows a coffin) under a uniform stop; asserts at most one per window."""
    n = len(strip)
    hit = 0
    for s in range(n):
        c = sum(1 for k in range(ROWS) if strip[(s + k) % n] == SCAT)
        assert c <= 1, "two coffins share a reel window: spread the scatters (counts/strip order)"
        hit += c
    return hit / n


# ----------------------------------------------------------------------------------------------
# exact line expectation
# ----------------------------------------------------------------------------------------------
def line_ev(p, pays):
    """Exact expected pay (x stake) and win probability of ONE payline whose reel r shows symbol s
    with probability p[r][s].  Left-to-right; w leading wilds then first symbol t; run continues over
    t/wild; pay = max(wild pay, symbol pay) (same rule as ReaperEngine.evaluateLine).  The coffin has pay 0."""
    ev = hit = 0.0
    p_all = 1.0
    for r in range(REELS):
        p_all *= p[r][W]
    ev += p_all * pays[W][2]
    hit += p_all
    lead = 1.0
    for w in range(REELS):
        wild_pay = pays[W][w - 3] if w >= 3 else 0
        for t in range(NSYM):
            if t == W:
                continue
            p_start = lead * p[w][t]
            if p_start == 0.0:
                continue
            cont = 1.0
            for m in range(w + 1, REELS + 1):
                if m == REELS:
                    n, pr = REELS, cont
                else:
                    stay = p[m][t] + p[m][W]
                    n, pr = m, cont * (1.0 - stay)
                    cont *= stay
                if pr == 0.0:
                    continue
                sym_pay = pays[t][n - 3] if n >= 3 else 0
                pay = wild_pay if wild_pay > sym_pay else sym_pay
                if pay:
                    ev += p_start * pr * pay
                    hit += p_start * pr
        lead *= p[w][W]
    return ev, hit


def marginals(counts, wild_reels=()):
    base = [c / sum(counts) for c in counts]
    p = []
    for r in range(REELS):
        if r in wild_reels:
            row = [0.0] * NSYM
            row[W] = 1.0
            p.append(row)
        else:
            p.append(base[:])
    return p


# ----------------------------------------------------------------------------------------------
# scatter counts (Poisson-binomial over reels, thinned by mini-feature wilds)
# ----------------------------------------------------------------------------------------------
def pb_dist(ps):
    d = np.zeros(len(ps) + 1)
    d[0] = 1.0
    for p in ps:
        nd = d * (1 - p)
        nd[1:] += d[:-1] * p
        d = nd
    return d


def thin_hyper(pre, cells, k):
    """pre[n] = P(n coffins on the grid); k wild cells chosen uniformly among `cells`; returns the surviving-coffin distribution."""
    out = np.zeros(len(pre))
    for n, pn in enumerate(pre):
        if pn == 0.0:
            continue
        for j in range(0, min(n, k) + 1):
            if k - j > cells - n:
                continue
            out[n - j] += pn * comb(n, j) * comb(cells - n, k - j) / comb(cells, k)
    return out


def scatter_dists(cfg):
    """Distribution of the coffin count on the FINAL grid for a plain spin and for each mini feature."""
    ps = [scatter_presence(s) for s in strips(cfg.counts)]
    pre = pb_dist(ps)
    out = {"base": pre, "demon": pre}
    d = np.zeros(REELS + 1)
    rs = sum(cfg.reaper_weights)
    for pat, wt in zip(cfg.reaper_patterns, cfg.reaper_weights):
        d += wt / rs * pb_dist([0.0 if r in pat else p for r, p in enumerate(ps)])
    out["reaper"] = d
    d = np.zeros(REELS + 1)
    ws = sum(cfg.raven_weights)
    for k, wt in zip(cfg.raven_wilds, cfg.raven_weights):
        d += wt / ws * thin_hyper(pre, REELS * ROWS, k)
    out["raven"] = d
    out["skull"] = thin_hyper(pre, REELS * ROWS, cfg.skull_wilds)
    return out


# ----------------------------------------------------------------------------------------------
# THE SEANCE: exact expected sum of multipliers
# ----------------------------------------------------------------------------------------------
def fs_unit(cfg):
    """(mu_fs = mean line pay of one Seance spin x stake, hit prob per line, coffin-count dist per spin)."""
    ev0, hit0 = line_ev(marginals(cfg.fs_counts), cfg.pays)
    pk = pb_dist([scatter_presence(s) for s in strips(cfg.fs_counts)])
    return NLINES * ev0, hit0, pk


def fs_sum_mult(cfg, n0, pk, tmax=MAX_FS_SPINS, rmax=600):
    """E[sum over all Seance spins of the multiplier m_t = min(cap, t)] when the session starts with n0 spins.
    State (t = number of the spin about to be played, r = spins left incl. this one); every spin may add extra spins."""
    extra = [0, 0, 0, cfg.fs_extra[3], cfg.fs_extra[4], cfg.fs_extra[5]]
    nxt = np.zeros(rmax + 1)                                   # f(t+1, r)
    for t in range(tmax, 0, -1):
        cur = np.zeros(rmax + 1)
        m_t = min(cfg.fs_ramp_cap, t)
        for r in range(1, rmax + 1):
            acc = m_t
            for k, pr in enumerate(pk):
                r2 = min(rmax, r - 1 + extra[k])
                if r2 >= 1:
                    acc += pr * nxt[r2]
            cur[r] = acc
        nxt = cur
    return nxt[n0]


def fs_expected_spins(cfg, n0, pk):
    """E[number of spins played] (no ramp) = n0 / (1 - rho) with rho = E[extra spins per spin]."""
    rho = sum(pk[k] * cfg.fs_extra[k] for k in (3, 4, 5))
    return n0 / (1 - rho)


def seance_values(cfg):
    """E[Seance payout] (x stake, uncapped) for a start of n0 spins, n0 in the awards table."""
    mu, hit, pk = fs_unit(cfg)
    vals = {k: mu * fs_sum_mult(cfg, cfg.fs_spins[k], pk) for k in (3, 4, 5)}
    return mu, hit, pk, vals


def exact_means(cfg=CFG):
    """Exact mean payout (x stake, uncapped) of every component + the vault DP + Seance + scatter."""
    pays = cfg.pays
    ev0, hit0 = line_ev(marginals(cfg.counts), pays)
    mu_base = NLINES * ev0

    # Reaper's Touch: whole reels wild (every payline crosses every reel)
    mu_reaper = sum(wt * NLINES * line_ev(marginals(cfg.counts, pat), pays)[0]
                    for pat, wt in zip(cfg.reaper_patterns, cfg.reaper_weights)) / sum(cfg.reaper_weights)

    # random wild cells: for one payline, S = which of its 5 cells are wild.
    ev_sub = {}
    for mask in range(1 << REELS):
        reels_w = [r for r in range(REELS) if mask >> r & 1]
        ev_sub[mask] = line_ev(marginals(cfg.counts, reels_w), pays)[0]

    def random_wild_line_ev(k):
        tot = 0.0
        for mask, ev in ev_sub.items():
            j = bin(mask).count("1")
            if j <= k:
                tot += comb(10, k - j) / comb(15, k) * ev      # exactly these j line cells wild, others not
        return tot

    wsum = sum(cfg.raven_weights)
    mu_raven = sum(wt / wsum * NLINES * random_wild_line_ev(k) for k, wt in zip(cfg.raven_wilds, cfg.raven_weights))
    e_coins = sum(c * w for c, w in zip(cfg.coin_counts, cfg.coin_count_weights)) / sum(cfg.coin_count_weights)
    e_coin_val = sum(v * w for v, w in zip(cfg.coin_values, cfg.coin_weights)) / sum(cfg.coin_weights)
    mu_demon = mu_base + e_coins * e_coin_val
    e_mult = sum(m * w for m, w in zip(cfg.skull_mults, cfg.skull_mult_weights)) / sum(cfg.skull_mult_weights)
    mu_skull = e_mult * NLINES * random_wild_line_ev(cfg.skull_wilds)
    mw = cfg.mini_weights
    mu_mini_lines = (mw[0] * mu_reaper + mw[1] * mu_raven + mw[2] * mu_demon + mw[3] * mu_skull) / sum(mw)
    vault = vault_exact(cfg)

    # coffins: scatter pays and Seance triggers, per spin type (final grid)
    sd = scatter_dists(cfg)
    mu_fs, hit_fs, pk_fs, fs_vals = seance_values(cfg)

    def scat(dist):
        pay = sum(dist[k] * cfg.scatter_pay[k] for k in (3, 4, 5))
        fs = sum(dist[k] * fs_vals[k] for k in (3, 4, 5))
        trig = sum(dist[k] for k in (3, 4, 5))
        return pay, fs, trig

    per = {name: scat(sd[name]) for name in ("base", "reaper", "raven", "demon", "skull")}
    kinds = ["reaper", "raven", "demon", "skull"]
    mini_pay = sum(w * per[k][0] for w, k in zip(mw, kinds)) / sum(mw)
    mini_fs = sum(w * per[k][1] for w, k in zip(mw, kinds)) / sum(mw)
    mini_trig = sum(w * per[k][2] for w, k in zip(mw, kinds)) / sum(mw)
    return dict(
        base=mu_base, hit_line=hit0, reaper=mu_reaper, raven=mu_raven, demon=mu_demon, skull=mu_skull,
        mini_lines=mu_mini_lines, vault=vault['mean'], vault_full=vault['p_full'], vault_detail=vault,
        e_coins=e_coins, e_coin_val=e_coin_val,
        scat_pay_base=per['base'][0], scat_pay_mini=mini_pay, trig_base=per['base'][2], trig_mini=mini_trig,
        fs_base=per['base'][1], fs_mini=mini_fs, scat_dist=sd, per_type=per,
        mu_fs=mu_fs, hit_fs_line=hit_fs, pk_fs=pk_fs, fs_vals=fs_vals,
        fs_spins_expected={k: fs_expected_spins(cfg, cfg.fs_spins[k], pk_fs) for k in (3, 4, 5)},
        # non-vault expected payout of a plain spin / a mini spin (lines + coins + scatter pay + Seance)
        A_base=mu_base + per['base'][0] + per['base'][1],
        A_mini=mu_mini_lines + mini_pay + mini_fs,
    )


def vault_exact(cfg=CFG):
    """Exact E[total multiplier] of the Soul Vault + P(full board).

    Only the NUMBER of filled cells matters for the process (cells are i.i.d. per respin); a reaper orb
    makes the collectable sum S -> 2S, soul orbs add their value, jackpot orbs add fixed values.
    Per landing: E[S'|S] = S(1+q_r) + q_s*mu_s ; after L landings E[S_L] = a^L S + q_s mu_s (a^L-1)/q_r."""
    cells = cfg.vault_cols * cfg.vault_rows
    mu_s = sum(v * w for v, w in zip(cfg.orb_values, cfg.orb_weights)) / sum(cfg.orb_weights)
    q_r = cfg.p_reaper
    q_j = cfg.p_major + cfg.p_minor + cfg.p_mini
    q_s = 1.0 - q_r - q_j
    jbar = cfg.p_major * cfg.v_major + cfg.p_minor * cfg.v_minor + cfg.p_mini * cfg.v_mini
    a = 1.0 + q_r
    rho = cfg.vault_orb_chance
    pending = {(cfg.vault_start, cfg.vault_respins): [1.0, cfg.vault_start * mu_s, 0.0]}
    e_s = e_j = p_full = 0.0
    p_steps = 0.0
    while pending:
        key = min(pending.keys(), key=lambda kr: (kr[0], -kr[1]))
        P, MS, MJ = pending.pop(key)
        k, r = key
        e = cells - k
        r1 = r - 1
        p_steps += P
        for L in range(e + 1):
            pl = comb(e, L) * rho ** L * (1 - rho) ** (e - L)
            if pl == 0.0:
                continue
            aL = a ** L
            ms = MS * aL + P * q_s * mu_s * ((aL - 1) / q_r if q_r > 0 else L)
            mj = MJ + P * L * jbar
            p = P * pl
            ms *= pl
            mj *= pl
            k2 = k + L
            r2 = cfg.vault_respins if L > 0 else r1
            if k2 >= cells:
                p_full += p
                e_s += ms
                e_j += mj
            elif r2 <= 0:
                e_s += ms
                e_j += mj
            else:
                cur = pending.setdefault((k2, r2), [0.0, 0.0, 0.0])
                cur[0] += p
                cur[1] += ms
                cur[2] += mj
    mean = e_s + e_j + cfg.grand * p_full
    return dict(mean=mean, soul=e_s, jackpots=e_j, p_full=p_full, grand=cfg.grand * p_full, mu_soul_orb=mu_s,
                avg_steps=p_steps)


def solve_feature_rate(cfg, ex):
    trig_v = cfg.relic_chance / cfg.relic_count
    return (cfg.target - trig_v * ex['vault'] - ex['A_base']) / (ex['A_mini'] - ex['A_base'])


def buy_prices(cfg, ex):
    """Feature-buy prices in multiples of the stake and the exact RTP of each buy at the cap-free stake."""
    mu, _, pk = fs_unit(cfg)[0], None, fs_unit(cfg)[2]
    e_seance = mu * fs_sum_mult(cfg, cfg.fs_spins[3], pk)       # a bought Seance = the 3-coffin award (8 spins)
    p_seance = ceil(e_seance / BUY_RTP)
    p_vault = ceil(ex['vault'] / BUY_RTP)
    return dict(seance=p_seance, seance_ev=e_seance, seance_rtp=e_seance / p_seance,
                vault=p_vault, vault_ev=ex['vault'], vault_rtp=ex['vault'] / p_vault)


# ----------------------------------------------------------------------------------------------
# simulation (numpy) -- tail / cap corrections and spin-level hit statistics
# ----------------------------------------------------------------------------------------------
def eval_lines(grid, pays):
    """grid (N,5,3) -> per-line pays (N,20) in x stake."""
    n = grid.shape[0]
    pay_tab = np.zeros((NSYM, 6))
    for s, (a, b, c) in enumerate(pays):
        pay_tab[s, 3], pay_tab[s, 4], pay_tab[s, 5] = a, b, c
    ar = np.arange(n)
    out = np.zeros((n, NLINES))
    for li, ln in enumerate(PAYLINES):
        s = grid[:, np.arange(5), ln].astype(np.int16)
        isw = s == W
        lead = np.zeros(n, dtype=np.int16)
        run = np.ones(n, dtype=bool)
        for r in range(5):
            run &= isw[:, r]
            lead += run
        wp = np.where(lead >= 3, pay_tab[W][np.minimum(lead, 5)], 0.0)
        tgt = np.where(lead == 5, W, s[ar, np.minimum(lead, 4)])
        cnt = np.zeros(n, dtype=np.int16)
        run = np.ones(n, dtype=bool)
        for r in range(5):
            run &= (s[:, r] == tgt) | isw[:, r]
            cnt += run
        sp = np.where(cnt >= 3, pay_tab[tgt, np.minimum(cnt, 5)], 0.0)
        out[:, li] = np.where(wp > sp, wp, sp)
    return out


def sample_grid(counts, n, rng, strp=None):
    strp = strp or strips(counts)
    grid = np.empty((n, 5, 3), dtype=np.int8)
    for r in range(5):
        st = strp[r]
        stops = rng.integers(0, len(st), n)
        for row in range(3):
            grid[:, r, row] = st[(stops + row) % len(st)]
    return grid


def scatter_pay_of(cfg, cnt):
    return np.where(cnt >= 5, cfg.scatter_pay[5], np.where(cnt == 4, cfg.scatter_pay[4], np.where(cnt == 3, cfg.scatter_pay[3], 0.0)))


def sample_base(cfg, n, rng, chunk=1_000_000):
    """x stake of n plain spins: lines + scatter pay (the Seance payout itself is a separate bonus). Also returns trigger counts."""
    out, trig = [], []
    strp = strips(cfg.counts)
    for i in range(0, n, chunk):
        m = min(chunk, n - i)
        grid = sample_grid(cfg.counts, m, rng, strp)
        cnt = (grid == SCAT).sum(axis=(1, 2))
        out.append(eval_lines(grid, cfg.pays).sum(axis=1) + scatter_pay_of(cfg, cnt))
        trig.append(cnt)
    return np.concatenate(out), np.concatenate(trig)


def _pick(rng, weights, n):
    w = np.array(weights, dtype=float)
    return rng.choice(len(w), size=n, p=w / w.sum())


def sample_mini(cfg, n, rng, chunk=500_000):
    """x stake of n mini-feature spins (all four types mixed by their weights): lines (x multiplier) + coins + scatter pay.
    Also returns the coffin count on the final grid (for the Seance trigger)."""
    out, trig = [], []
    strp = strips(cfg.counts)
    for i in range(0, n, chunk):
        m = min(chunk, n - i)
        grid = sample_grid(cfg.counts, m, rng, strp)
        kind = _pick(rng, cfg.mini_weights, m)
        coins = np.zeros(m)
        mult = np.ones(m)
        pat = _pick(rng, cfg.reaper_weights, m)
        for pi, reels_w in enumerate(cfg.reaper_patterns):
            sel = (kind == 0) & (pat == pi)
            for r in reels_w:
                grid[sel, r, :] = W
        flat = grid.reshape(m, 15)
        order = np.argsort(rng.random((m, 15)), axis=1)
        nwild = np.zeros(m, dtype=int)
        rk = _pick(rng, cfg.raven_weights, m)
        nwild[kind == 1] = np.array(cfg.raven_wilds)[rk[kind == 1]]
        nwild[kind == 3] = cfg.skull_wilds
        rank = np.empty_like(order)
        np.put_along_axis(rank, order, np.arange(15)[None, :].repeat(m, 0), axis=1)
        flat[rank < nwild[:, None]] = W
        grid = flat.reshape(m, 5, 3)
        ncoin = np.array(cfg.coin_counts)[_pick(rng, cfg.coin_count_weights, m)]
        ncoin = np.where(kind == 2, ncoin, 0)
        vals = np.array(cfg.coin_values)
        for c in range(max(cfg.coin_counts)):
            has = ncoin > c
            coins[has] += vals[_pick(rng, cfg.coin_weights, int(has.sum()))]
        sk = kind == 3
        mult[sk] = np.array(cfg.skull_mults)[_pick(rng, cfg.skull_mult_weights, int(sk.sum()))]
        cnt = (grid == SCAT).sum(axis=(1, 2))
        lines = eval_lines(grid, cfg.pays).sum(axis=1)
        out.append(lines * mult + coins + scatter_pay_of(cfg, cnt))
        trig.append(cnt)
    return np.concatenate(out), np.concatenate(trig)


def sample_seance(cfg, n, rng, n0):
    """Total multiplier (x the triggering stake, uncapped) of n Seance sessions, each starting with n0[i] spins; also spins played."""
    strp = strips(cfg.fs_counts)
    total = np.zeros(n)
    remaining = np.array(n0, dtype=int).copy()
    played = np.zeros(n, dtype=int)
    extra = np.array([0, 0, 0, cfg.fs_extra[3], cfg.fs_extra[4], cfg.fs_extra[5]])
    alive = remaining > 0
    while alive.any():
        idx = np.nonzero(alive)[0]
        grid = sample_grid(cfg.fs_counts, len(idx), rng, strp)
        played[idx] += 1
        mult = np.minimum(cfg.fs_ramp_cap, played[idx])
        total[idx] += eval_lines(grid, cfg.pays).sum(axis=1) * mult
        cnt = (grid == SCAT).sum(axis=(1, 2))
        remaining[idx] += extra[cnt] - 1
        alive[idx] = (remaining[idx] > 0) & (played[idx] < MAX_FS_SPINS)
    return total, played


def sample_vault(cfg, n, rng):
    """Vectorised Soul Vault: returns total multiplier (x bonus bet) for n bonuses."""
    cells = cfg.vault_cols * cfg.vault_rows
    ov = np.array(cfg.orb_values, dtype=float)
    ow = np.array(cfg.orb_weights, dtype=float)
    ow /= ow.sum()
    S = np.zeros(n)
    for _ in range(cfg.vault_start):
        S += ov[rng.choice(len(ov), size=n, p=ow)]
    J = np.zeros(n)
    k = np.full(n, cfg.vault_start)
    r = np.full(n, cfg.vault_respins)
    alive = np.ones(n, dtype=bool)
    t1, t2, t3, t4 = cfg.p_major, cfg.p_major + cfg.p_minor, cfg.p_major + cfg.p_minor + cfg.p_mini, \
        cfg.p_major + cfg.p_minor + cfg.p_mini + cfg.p_reaper
    while alive.any():
        idx = np.nonzero(alive)[0]
        e = cells - k[idx]
        L = rng.binomial(e, cfg.vault_orb_chance)
        for t in range(int(L.max()) if len(L) else 0):
            sel = idx[L > t]
            u = rng.random(len(sel))
            S_sel = sel[u >= t4]
            S[S_sel] += ov[rng.choice(len(ov), size=len(S_sel), p=ow)]
            S[sel[(u >= t3) & (u < t4)]] *= 2.0
            J[sel[u < t1]] += cfg.v_major
            J[sel[(u >= t1) & (u < t2)]] += cfg.v_minor
            J[sel[(u >= t2) & (u < t3)]] += cfg.v_mini
        k[idx] += L
        r[idx] = np.where(L > 0, cfg.vault_respins, r[idx] - 1)
        alive[idx] = (r[idx] > 0) & (k[idx] < cells)
    return S + J + np.where(k >= cells, cfg.grand, 0.0)


def tail(x, stake, cap=CAP_TOKENS):
    """E[(X - cap/stake)^+]: what the 25-book cap removes from a mean that was computed without the cap."""
    return np.maximum(x - cap / stake, 0.0).mean()


# ----------------------------------------------------------------------------------------------
def report(cfg=CFG, n_base=8_000_000, n_mini=2_000_000, n_vault=4_000_000, n_fs=1_500_000, seed=1, stakes=STAKES, quiet=False):
    """Exact budget + per-stake RTP (cap tails measured by simulation)."""
    rng = np.random.default_rng(seed)
    ex = exact_means(cfg)
    trig_v = cfg.relic_chance / cfg.relic_count
    f = solve_feature_rate(cfg, ex)
    xb, cb = sample_base(cfg, n_base, rng)
    xm, cm = sample_mini(cfg, n_mini, rng)
    xv = sample_vault(cfg, n_vault, rng)
    # Seance sessions: start-size mix given a trigger on a plain spin / a mini spin (exact trigger probabilities)
    def fs_sessions(counts, n):
        ks = counts[counts >= 3]
        return sample_seance(cfg, len(ks), rng, [cfg.fs_spins[int(k)] for k in ks]) if len(ks) else (np.zeros(0), np.zeros(0))
    xfs_b, pl_b = fs_sessions(cb, 0)
    xfs_m, pl_m = fs_sessions(cm, 0)
    # extra sessions for the buy (start = 8 spins) and a high-resolution tail estimate
    xbuy, pl_buy = sample_seance(cfg, n_fs, rng, [cfg.fs_spins[3]] * n_fs)
    prices = buy_prices(cfg, ex)
    rows = []
    # per-trigger tail: E[(X-thr)+] per SPIN of that type = (tail over sessions) * P(trigger)
    for s in stakes:
        rb = ex['A_base'] - ex['fs_base'] - tail(xb, s)                       # lines + scatter pay of a plain spin (capped)
        rfb = ex['fs_base'] - (tail(xfs_b, s) * len(xfs_b) / n_base if len(xfs_b) else 0.0)
        rm = ex['A_mini'] - ex['fs_mini'] - tail(xm, s)
        rfm = ex['fs_mini'] - (tail(xfs_m, s) * len(xfs_m) / n_mini if len(xfs_m) else 0.0)
        rv = ex['vault'] - tail(xv, s)
        a_base = rb + rfb
        a_mini = rm + rfm
        rtp = (1 - f) * a_base + f * a_mini + trig_v * rv
        buy_fs = (prices['seance_ev'] - tail(xbuy, s)) / prices['seance']
        buy_v = (ex['vault'] - tail(xv, s)) / prices['vault']
        rows.append(dict(stake=s, base=rb, fs_base=rfb, mini=rm, fs_mini=rfm, vault=rv, A=a_base * (1 - f) + a_mini * f,
                         rtp=rtp, buy_seance=buy_fs, buy_vault=buy_v))
    hit_any = (xb > 0).mean()
    if not quiet:
        pk = ex['scat_dist']['base']
        print("EXACT mean multipliers (x stake, uncapped):")
        print("  base lines         %.4f   (per-line EV %.5f, per-line hit %.4f)" % (ex['base'], ex['base'] / NLINES, ex['hit_line']))
        print("  Reaper's Touch %.3f | Murder of Crows %.3f | Demon's Bargain %.3f | Skull's Curse %.3f  -> mini mix (lines+coins) %.3f"
              % (ex['reaper'], ex['raven'], ex['demon'], ex['skull'], ex['mini_lines']))
        print("  coffins: P(3)=%.5f P(4)=%.6f P(5)=%.8f  -> Seance on 1 in %.0f plain spins; scatter pay EV %.4f"
              % (pk[3], pk[4], pk[5], 1 / ex['trig_base'], ex['scat_pay_base']))
        print("  Seance spin: mu %.3f x stake (line hit/line %.4f), coffins/spin P(3+) = %.4f (retrigger 1 in %.0f spins)"
              % (ex['mu_fs'], ex['hit_fs_line'], ex['pk_fs'][3:].sum(), 1 / ex['pk_fs'][3:].sum()))
        for k in (3, 4, 5):
            print("     %d coffins -> %2d spins (expected %.1f played incl. retriggers) -> mean payout %.2f x stake"
                  % (k, cfg.fs_spins[k], ex['fs_spins_expected'][k], ex['fs_vals'][k]))
        vd = ex['vault_detail']
        print("  Soul Vault         %.3f  (soul orbs %.2f + jackpot orbs %.2f + GRAND %.2f; P(full board)=1/%.0f; MC mean %.3f)"
              % (ex['vault'], vd['soul'], vd['jackpots'], vd['grand'], 1 / vd['p_full'] if vd['p_full'] else 0, xv.mean()))
        lines = (1 - f) * ex['base'] + f * ex['mini_lines']
        scat = (1 - f) * ex['scat_pay_base'] + f * ex['scat_pay_mini']
        seance = (1 - f) * ex['fs_base'] + f * ex['fs_mini']
        print("  relics: Soul Vault every %.0f spins (p_relic %.4f); mini feature f = %.5f (1 in %.0f)" % (1 / trig_v, cfg.relic_chance, f, 1 / f))
        print("  RTP BUDGET at stake 1: lines+minis %.2f%% + coffin pays %.2f%% + SEANCE %.2f%% + Soul Vault %.2f%% = %.3f%%"
              % (100 * lines, 100 * scat, 100 * seance, 100 * trig_v * ex['vault'], 100 * (lines + scat + seance + trig_v * ex['vault'])))
        trig_all = (1 - f) * ex['trig_base'] + f * ex['trig_mini']
        print("  THE SEANCE every %.0f spins; avg payout %.1f x stake | Soul Vault every %.0f spins; avg %.1f x bonus bet"
              % (1 / trig_all, seance / trig_all, 1 / trig_v, ex['vault']))
        print("  plain-spin hit rate (any win, all >= stake): %.4f  (1 in %.2f)" % (hit_any, 1 / hit_any))
        print("  MC check of exact means: lines+scatter %.4f vs %.4f | mini %.3f vs %.3f | vault %.3f vs %.3f"
              % (xb.mean(), ex['A_base'] - ex['fs_base'], xm.mean(), ex['A_mini'] - ex['fs_mini'], xv.mean(), ex['vault']))
        print("  MC check of Seance: bought-8-spin session %.3f vs exact %.3f (spins played %.2f vs %.2f)"
              % (xbuy.mean(), prices['seance_ev'], pl_buy.mean(), ex['fs_spins_expected'][3]))
        print("  FEATURE BUY prices: SEANCE %dx stake (EV %.2fx -> RTP %.2f%%) | SOUL VAULT %dx stake (EV %.2fx -> RTP %.2f%%)"
              % (prices['seance'], prices['seance_ev'], 100 * prices['seance_rtp'], prices['vault'], prices['vault_ev'], 100 * prices['vault_rtp']))
        print("\nstake | lines+pay  mini  | seance(base) | vault  | RTP    | buy Seance  buy Vault")
        for r in rows:
            print("%5d | %.4f  %8.3f | %8.4f | %7.3f | %.4f | %.4f   %.4f" %
                  (r['stake'], r['base'], r['mini'], r['fs_base'], r['vault'], r['rtp'], r['buy_seance'], r['buy_vault']))
        print("FEATURE_RATE = %.6f" % f)
        print("RTP_BY_STAKE = " + ", ".join("%.4f" % r['rtp'] for r in rows))
    return ex, rows, dict(hit_base=hit_any, xb=xb, xm=xm, xv=xv, f=f, prices=prices, xbuy=xbuy, xfs_b=xfs_b)


# ----------------------------------------------------------------------------------------------
# tuning: find reel counts that hit the RTP budget targets
# ----------------------------------------------------------------------------------------------
TUNE = SimpleNamespace(
    base_len=(66, 76), fs_len=(48, 60), char_share=0.30,
    seance_share=0.16, vault_share=None, hit_min=0.300,   # share of RTP at stake 1; hit = P(any win on a plain spin)
)


def tune(cfg=CFG, iters=4000, seed=3):
    """Random hill-climb over the FS reel counts so the Seance share of RTP hits TUNE.seance_share and over the base
    counts to keep the plain-spin hit rate >= TUNE.hit_min with a coffin trigger around 1 in 190 spins."""
    import copy
    rng = np.random.default_rng(seed)
    best = copy.deepcopy(cfg)

    def score(c):
        try:
            ex = exact_means(c)
        except AssertionError:
            return 1e9, None
        f = solve_feature_rate(c, ex)
        if not (0.002 < f < 0.02):
            return 1e9, None
        trig_v = c.relic_chance / c.relic_count
        seance = (1 - f) * ex['fs_base'] + f * ex['fs_mini']
        trig_all = (1 - f) * ex['trig_base'] + f * ex['trig_mini']
        lines = (1 - f) * ex['base'] + f * ex['mini_lines']
        # plain spin hit rate from the per-line probabilities is not additive; approximate with 1-(1-h)^NLINES / overlap: use sim-free proxy
        hit_proxy = 1 - np.exp(-NLINES * ex['hit_line'] * 0.93)
        ch_share = sum(c.counts[1:6]) / sum(c.counts)
        pen = 0.0
        pen += 400 * abs(seance - TUNE.seance_share)
        pen += 2.0 * max(0.0, TUNE.hit_min - hit_proxy) * 100
        pen += 0.02 * abs(1 / trig_all - 190)
        pen += 100 * max(0.0, TUNE.char_share - ch_share)
        L = sum(c.counts)
        pen += 50 * (L < TUNE.base_len[0] or L > TUNE.base_len[1]) + 50 * (sum(c.fs_counts) < TUNE.fs_len[0] or sum(c.fs_counts) > TUNE.fs_len[1])
        return pen, dict(seance=seance, trig=1 / trig_all, lines=lines, f=f, hit=hit_proxy)

    cur, info = score(best)
    print("start score %.3f %s" % (cur, info))
    for it in range(iters):
        cand = copy.deepcopy(best)
        arr = cand.counts if rng.random() < 0.5 else cand.fs_counts
        i = int(rng.integers(0, 11))              # never touch the scatter count (index 11)
        d = int(rng.choice([-1, 1]))
        if arr[i] + d < (1 if arr is cand.counts and i in (0, 1, 2) else 0) or arr[i] + d > 14:
            continue
        arr[i] += d
        if arr is cand.counts and cand.counts[0] > 2:
            continue
        sc, inf = score(cand)
        if sc < cur:
            best, cur, info = cand, sc, inf
    print("best score %.3f %s" % (cur, info))
    print("counts    =", best.counts, "L =", sum(best.counts))
    print("fs_counts =", best.fs_counts, "L =", sum(best.fs_counts))
    return best


# ----------------------------------------------------------------------------------------------
# Strategy-proofness: can a player beat the advertised RTP by choosing stakes cleverly?
#
# The Soul Vault pays on a "bonus bet" derived from the stakes played while collecting relics.  Two rules were beaten:
#   "round"  = round(average stake of every spin in the collection window): mixing stakes 1/2 reached ~98 %.
#   "window" = floor(average stake of every spin in the window): an ADAPTIVE policy (bet big only when relics arrive
#              quickly; the window length is random and E[1/N] > 1/E[N]) reaches 101-104 % (found by an independent audit).
# Shipped rule "relic" = floor(mean stake of the five spins that REVEALED the relics) (RelicMeter.kt).  A relic spin is a
# random sample of the player's stakes (the relic chance does not depend on the stake), so by Wald's identity
# E[sum of relic-spin stakes] = p * E[tokens wagered]: the expected bonus per token is identical for every policy.
# Everything else (lines, coins, coffin pays, the Seance, feature buys) is linear in the stake or concave (the cap).
# ----------------------------------------------------------------------------------------------
def vault_bet_table(cfg, xv, max_bet=40):
    """t[b] = E[min(V*b, cap)] (tokens) for bonus bet b = 0..max_bet."""
    t = np.zeros(max_bet + 1)
    for b in range(1, max_bet + 1):
        t[b] = np.minimum(xv * b, CAP_TOKENS).mean()
    return t


def policy_rtp(cfg, rows, vtab, policy, n_cycles=300_000, seed=5):
    """Expected RTP of an adaptive staking policy under the three bonus-bet rules.
    policy(relics_held, spin_index_in_window) -> stake (numpy arrays).  rows: report() rows (non-vault part A per stake)."""
    rng = np.random.default_rng(seed)
    a_by_stake = np.zeros(max(STAKES) + 1)
    for r in rows:
        a_by_stake[r['stake']] = r['A']
    n = n_cycles
    k = np.zeros(n, int)
    relics = np.zeros(n, int)
    wagered = np.zeros(n)
    relic_sum = np.zeros(n)
    nonvault = np.zeros(n)
    alive = np.ones(n, bool)
    while alive.any():
        idx = np.nonzero(alive)[0]
        s = policy(relics[idx], k[idx] + 1).astype(int)
        wagered[idx] += s
        k[idx] += 1
        nonvault[idx] += s * a_by_stake[s]
        got = rng.random(len(idx)) < cfg.relic_chance
        relic_sum[idx[got]] += s[got]
        relics[idx[got]] += 1
        alive[idx[relics[idx] >= cfg.relic_count]] = False
    bet = {
        "round": np.floor(wagered / k + 0.5),
        "window": np.floor(wagered / k + 1e-9),
        "relic": np.floor(relic_sum / cfg.relic_count + 1e-9),
    }
    out = {}
    for rule, b in bet.items():
        b = np.clip(b, 1, max(STAKES)).astype(int)
        out[rule] = (nonvault.sum() + vtab[b].sum()) / wagered.sum()
    return out


POLICIES = {
    "constant stake 10": lambda r, k: np.full(len(r), 10),
    "bet 1; bet 40 with 4 relics and <=60 spins in window": lambda r, k: np.where((r == 4) & (k <= 60), 40, 1),
    "40 while spins<=20/40/60 holding 2/3/4 relics, else 1": lambda r, k: np.where(
        ((r == 2) & (k <= 20)) | ((r == 3) & (k <= 40)) | ((r == 4) & (k <= 60)), 40, 1),
    "first 45 spins of each window at 25, else 1": lambda r, k: np.where(k <= 45, 25, 1),
    "bet 40 only once 4 relics are held": lambda r, k: np.where(r == 4, 40, 1),
}


def exploit_report(cfg=CFG):
    ex, rows, extra = report(cfg, quiet=True, n_base=2_000_000, n_mini=800_000, n_vault=3_000_000, n_fs=300_000)
    vtab = vault_bet_table(cfg, extra['xv'])
    print("%-56s %10s %10s %10s" % ("staking policy (adaptive)", "round-avg", "floor-avg", "SHIPPED"))
    worst = 0.0
    for name, pol in POLICIES.items():
        r = policy_rtp(cfg, rows, vtab, pol)
        worst = max(worst, r["relic"])
        print("%-56s %10.4f %10.4f %10.4f" % (name, r["round"], r["window"], r["relic"]))
    print("shipped rule (floor of the mean stake on the relic-revealing spins): worst policy above %.4f (target %.2f)" % (worst, cfg.target))


def check_against_kotlin(cfg=CFG):
    """Fail loudly if this file's reels / pays / feature + relic parameters differ from ReaperConfig.kt (the shipped game)."""
    import os
    import re
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "reaper", "src", "main", "java", "com",
                        "tjshea", "neonreaper", "engine", "ReaperConfig.kt")
    src = open(path).read()
    ok = True

    def report_line(name, same):
        nonlocal ok
        ok &= same
        print("%-14s %s" % (name, "matches ReaperConfig.kt" if same else "DIFFERS from ReaperConfig.kt !!!"))

    def ints(pattern):
        return [int(x) for x in re.search(pattern, src).group(1).split(",")]

    report_line("COUNTS", ints(r"private val COUNTS = intArrayOf\(([^)]*)\)") == cfg.counts)
    report_line("FS_COUNTS", ints(r"private val FS_COUNTS = intArrayOf\(([^)]*)\)") == cfg.fs_counts)
    body = re.search(r"val PAYS: Array<IntArray> = arrayOf\((.*?)\n    \)", src, re.S).group(1)
    pays = [tuple(int(x) for x in r.split(",")) for r in re.findall(r"intArrayOf\(([^)]*)\)", body)]
    report_line("PAYS", pays == [tuple(p) for p in cfg.pays])
    report_line("SCATTER_PAY", ints(r"val SCATTER_PAY = intArrayOf\(([^)]*)\)")[3:] == [cfg.scatter_pay[k] for k in (3, 4, 5)])
    report_line("FS_SPINS", ints(r"val FS_SPINS = intArrayOf\(([^)]*)\)")[3:] == [cfg.fs_spins[k] for k in (3, 4, 5)])
    report_line("FS_EXTRA", ints(r"val FS_EXTRA = intArrayOf\(([^)]*)\)")[3:] == [cfg.fs_extra[k] for k in (3, 4, 5)])
    report_line("FS_RAMP_CAP", int(re.search(r"const val FS_RAMP_CAP = ([0-9]+)", src).group(1)) == cfg.fs_ramp_cap)
    f = float(re.search(r"const val FEATURE_RATE = ([0-9.]+)", src).group(1))
    ex = exact_means(cfg)
    f_exact = solve_feature_rate(cfg, ex)
    report_line("FEATURE_RATE", abs(f - f_exact) < 2e-6)
    print("                 (Kotlin %.6f, exact solution %.6f)" % (f, f_exact))
    rc = re.search(r"const val RELIC_CHANCE = ([0-9.]+) / ([0-9.]+)", src)
    report_line("RELIC_CHANCE", abs(float(rc.group(1)) / float(rc.group(2)) - cfg.relic_chance) < 1e-12)
    prices = buy_prices(cfg, ex)
    report_line("BUY_SEANCE", int(re.search(r"const val BUY_SEANCE_X = ([0-9]+)", src).group(1)) == prices['seance'])
    report_line("BUY_VAULT", int(re.search(r"const val BUY_VAULT_X = ([0-9]+)", src).group(1)) == prices['vault'])
    return ok


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "report"
    if cmd == "exact":
        ex = exact_means(CFG)
        for k, v in ex.items():
            if k not in ("vault_detail", "scat_dist", "per_type"):
                print(k, v)
        print(ex['vault_detail'])
        f = solve_feature_rate(CFG, ex)
        print("FEATURE_RATE", f, " prices", buy_prices(CFG, ex))
    elif cmd == "tune":
        tune()
    elif cmd == "exploit":
        exploit_report()
    elif cmd == "check":
        raise SystemExit(0 if check_against_kotlin() else 1)
    else:
        report()
