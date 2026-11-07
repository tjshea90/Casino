#!/usr/bin/env python3
"""
Exact math model for Neon Reaper (module :reaper): RTP budget, per-stake calibration, simulator.

RTP is CALCULATED, not guessed.  Every component's mean payout is computed in closed form:

  base game     20 paylines, independent weighted reels -> per-line expectation from per-reel
                symbol marginals (rows are consecutive strip positions under a uniform stop), x 20.
  mini features Reaper's Touch  (whole reels turn wild: marginal = delta(wild)),
                Raven's Flock   (k random cells wild: hypergeometric over the line's cells),
                Pumpkin's Treats(coins add E[count] x E[value] on top of the base game),
                Skull's Curse   (2 random wild cells, line wins x E[multiplier]).
  Soul Vault    Hold & Spin: a small Markov DP over (filled cells, respins left) with the exact
                expected sum of orb values (reaper orbs double the collectable sum), jackpot orbs,
                and the exact probability of the full-board GRAND.

The ONLY thing that needs simulation is the effect of the hard cap (25 books = 2000 tokens per spin
and per bonus): E[min(X*stake, 2000)] needs the *tail* of X.  We therefore compute
    RTP_component(stake) = exact_mean - E[(X - 2000/stake)^+]
where the second term is a small tail quantity measured with millions of samples (tiny absolute error).

Calibration: the chance f(stake) that a spin gets a mini feature is solved per stake so that
    (1-f) R_base + f R_mini + (relic_p / 5) R_vault  =  TARGET_RTP   exactly at every stake.

Usage:  python3 tools/reaper_model.py                # report on the config in CFG (budget, hit rate, calibration)
        python3 tools/reaper_model.py exact          # exact means only (instant)
Kotlin mirror: ReaperConfig.kt (tests: RtpExactTest checks the exact means, CalibrationTest/FullGameTest simulate).
"""
import sys
from math import comb
from types import SimpleNamespace

import numpy as np

W, HOOD, SKULL, RAVEN, PUMP, GHOST, A, K, Q, J, TEN = range(11)
NSYM = 11
REELS, ROWS = 5, 3
NAMES = ["Reaper(wild)", "Hooded", "Skull", "Raven", "Pumpkin", "Ghost", "A", "K", "Q", "J", "10"]
PAYLINES = np.array([
    [1, 1, 1, 1, 1], [0, 0, 0, 0, 0], [2, 2, 2, 2, 2], [0, 1, 2, 1, 0], [2, 1, 0, 1, 2], [0, 0, 1, 2, 2],
    [2, 2, 1, 0, 0], [1, 0, 0, 0, 1], [1, 2, 2, 2, 1], [1, 0, 1, 2, 1], [1, 2, 1, 0, 1], [0, 1, 1, 1, 0],
    [2, 1, 1, 1, 2], [0, 1, 0, 1, 0], [2, 1, 2, 1, 2], [1, 1, 0, 1, 1], [1, 1, 2, 1, 1], [0, 0, 2, 0, 0],
    [2, 2, 0, 2, 2], [0, 2, 2, 2, 0]])
NLINES = len(PAYLINES)
CAP_TOKENS = 2000            # 25 books x 80 tokens
STAKES = [1, 2, 3, 4, 5, 8, 10, 15, 20, 25, 30, 40]

# ----------------------------------------------------------------------------------------------
# CONFIG -- mirror of ReaperConfig.kt (keep in sync; RtpExactTest checks the Kotlin side)
# ----------------------------------------------------------------------------------------------
CFG = SimpleNamespace(
    pays=[(6, 20, 100), (3, 10, 40), (3, 6, 25), (2, 5, 15), (2, 4, 10), (2, 3, 8),
          (1, 2, 6), (1, 2, 5), (1, 2, 4), (1, 2, 4), (1, 2, 3)],          # x stake for 3/4/5 of a kind
    counts=[1, 3, 3, 4, 5, 5, 7, 7, 8, 8, 9],                              # symbols per reel strip (same on all 5 reels)
    mini_weights=[0.25, 0.30, 0.30, 0.15],                                 # reaper, raven, pumpkin, skull
    reaper_patterns=[[1], [2], [3], [1, 3], [1, 2, 3]], reaper_weights=[0.45, 0.25, 0.15, 0.08, 0.07],
    raven_wilds=[3, 4, 5, 6], raven_weights=[0.5, 0.3, 0.15, 0.05],
    coin_counts=[3, 4, 5, 6], coin_count_weights=[0.45, 0.3, 0.17, 0.08],
    coin_values=[1, 2, 3, 5, 10, 20, 50], coin_weights=[30, 25, 18, 13, 9, 4, 1],
    skull_mults=[2, 3, 5], skull_mult_weights=[0.6, 0.3, 0.1], skull_wilds=2,
    relic_count=5, relic_chance=5 / 140,
    vault_cols=5, vault_rows=4, vault_start=5, vault_respins=3, vault_orb_chance=0.03,
    orb_values=[1, 2, 3, 4, 5, 8, 10, 15, 25], orb_weights=[36, 24, 14, 9, 6, 5, 3, 2, 1],
    p_major=0.003, p_minor=0.012, p_mini=0.03, p_reaper=0.02, v_mini=15, v_minor=40, v_major=150, grand=1000,
    target=0.87,
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


def strips(cfg):
    return [np.array(build_strip(cfg.counts, r)) for r in range(REELS)]


# ----------------------------------------------------------------------------------------------
# exact line expectation
# ----------------------------------------------------------------------------------------------
def line_ev(p, pays):
    """Exact expected pay (x stake) and win probability of ONE payline whose reel r shows symbol s
    with probability p[r][s].  Left-to-right; w leading wilds then first symbol t; run continues over
    t/wild; pay = max(wild pay, symbol pay) (same rule as ReaperEngine.evaluateLine)."""
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


def marginals(cfg, wild_reels=()):
    base = [c / sum(cfg.counts) for c in cfg.counts]
    p = []
    for r in range(REELS):
        if r in wild_reels:
            row = [0.0] * NSYM
            row[W] = 1.0
            p.append(row)
        else:
            p.append(base[:])
    return p


def exact_means(cfg=CFG):
    """Exact mean payout (x stake, uncapped) of every component + the vault DP."""
    pays = cfg.pays
    ev0, hit0 = line_ev(marginals(cfg), pays)
    mu_base = NLINES * ev0

    # Reaper's Touch: whole reels wild (every payline crosses every reel)
    mu_reaper = sum(wt * NLINES * line_ev(marginals(cfg, pat), pays)[0]
                    for pat, wt in zip(cfg.reaper_patterns, cfg.reaper_weights))

    # random wild cells: for one payline, S = which of its 5 cells are wild.
    ev_sub = {}
    for mask in range(1 << REELS):
        reels_w = [r for r in range(REELS) if mask >> r & 1]
        ev_sub[mask] = line_ev(marginals(cfg, reels_w), pays)[0]

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
    mu_pump = mu_base + e_coins * e_coin_val
    e_mult = sum(m * w for m, w in zip(cfg.skull_mults, cfg.skull_mult_weights)) / sum(cfg.skull_mult_weights)
    mu_skull = e_mult * NLINES * random_wild_line_ev(cfg.skull_wilds)
    mw = cfg.mini_weights
    mu_mini = (mw[0] * mu_reaper + mw[1] * mu_raven + mw[2] * mu_pump + mw[3] * mu_skull) / sum(mw)
    vault = vault_exact(cfg)
    return dict(base=mu_base, hit_line=hit0, reaper=mu_reaper, raven=mu_raven, pumpkin=mu_pump, skull=mu_skull,
                mini=mu_mini, vault=vault['mean'], vault_full=vault['p_full'], vault_detail=vault,
                e_coins=e_coins, e_coin_val=e_coin_val)


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
    # state: (k, r) -> [P, E[S*1], E[J*1]]
    states = {(cfg.vault_start, cfg.vault_respins): [1.0, cfg.vault_start * mu_s, 0.0]}
    e_s = e_j = p_full = 0.0
    p_steps = 0.0
    order = sorted(states.keys())
    visited = set()
    queue = [(cfg.vault_start, cfg.vault_respins)]
    # process in an order where k never decreases and, for equal k, r decreases
    pending = dict(states)
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


def sample_grid(cfg, n, rng, strp=None):
    strp = strp or strips(cfg)
    grid = np.empty((n, 5, 3), dtype=np.int8)
    for r in range(5):
        st = strp[r]
        stops = rng.integers(0, len(st), n)
        for row in range(3):
            grid[:, r, row] = st[(stops + row) % len(st)]
    return grid


def sample_base(cfg, n, rng, chunk=1_000_000):
    out = []
    strp = strips(cfg)
    for i in range(0, n, chunk):
        m = min(chunk, n - i)
        out.append(eval_lines(sample_grid(cfg, m, rng, strp), cfg.pays).sum(axis=1))
    return np.concatenate(out)


def _pick(rng, weights, n):
    w = np.array(weights, dtype=float)
    return rng.choice(len(w), size=n, p=w / w.sum())


def sample_mini(cfg, n, rng, chunk=500_000):
    """Multiplier (x stake) of n mini-feature spins (all four types mixed by their weights)."""
    out = []
    strp = strips(cfg)
    for i in range(0, n, chunk):
        m = min(chunk, n - i)
        grid = sample_grid(cfg, m, rng, strp)
        kind = _pick(rng, cfg.mini_weights, m)
        coins = np.zeros(m)
        mult = np.ones(m)
        # reaper: wild reels
        pat = _pick(rng, cfg.reaper_weights, m)
        for pi, reels_w in enumerate(cfg.reaper_patterns):
            sel = (kind == 0) & (pat == pi)
            for r in reels_w:
                grid[sel, r, :] = W
        # raven / skull: random wild cells
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
        # pumpkin: coins
        ncoin = np.array(cfg.coin_counts)[_pick(rng, cfg.coin_count_weights, m)]
        ncoin = np.where(kind == 2, ncoin, 0)
        vals = np.array(cfg.coin_values)
        for c in range(max(cfg.coin_counts)):
            has = ncoin > c
            coins[has] += vals[_pick(rng, cfg.coin_weights, int(has.sum()))]
        sk = kind == 3
        mult[sk] = np.array(cfg.skull_mults)[_pick(rng, cfg.skull_mult_weights, int(sk.sum()))]
        lines = eval_lines(grid, cfg.pays).sum(axis=1)
        out.append(lines * mult + coins)
    return np.concatenate(out)


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


def capped_mean(x, stake, cap=CAP_TOKENS):
    """E[min(X*stake, cap)] / stake via exact-mean control variate: mean(X) - mean((X - cap/stake)^+)."""
    thr = cap / stake
    return x.mean() - np.maximum(x - thr, 0.0).mean()


# ----------------------------------------------------------------------------------------------
def report(cfg=CFG, n_base=8_000_000, n_mini=2_000_000, n_vault=4_000_000, seed=1, stakes=STAKES, quiet=False):
    rng = np.random.default_rng(seed)
    ex = exact_means(cfg)
    trig = cfg.relic_chance / cfg.relic_count
    xb = sample_base(cfg, n_base, rng)
    xm = sample_mini(cfg, n_mini, rng)
    xv = sample_vault(cfg, n_vault, rng)
    rows = []
    for s in stakes:
        rb = ex['base'] - np.maximum(xb - CAP_TOKENS / s, 0).mean()
        rm = ex['mini'] - np.maximum(xm - CAP_TOKENS / s, 0).mean()
        rv = ex['vault'] - np.maximum(xv - CAP_TOKENS / s, 0).mean()
        f = (cfg.target - trig * rv - rb) / (rm - rb)
        rows.append(dict(stake=s, base=rb, mini=rm, vault=rv, f=f, rtp=(1 - f) * rb + f * rm + trig * rv))
    hit_base = (xb > 0).mean()
    if not quiet:
        print("EXACT mean multipliers (x stake, uncapped):")
        print("  base game          %.4f   (per-line EV %.5f, per-line hit %.4f)" % (ex['base'], ex['base'] / NLINES, ex['hit_line']))
        print("  Reaper's Touch     %.3f | Raven's Flock %.3f | Pumpkin's Treats %.3f | Skull's Curse %.3f  -> mini mix %.3f"
              % (ex['reaper'], ex['raven'], ex['pumpkin'], ex['skull'], ex['mini']))
        vd = ex['vault_detail']
        print("  Soul Vault         %.3f  (soul orbs %.2f + jackpot orbs %.2f + GRAND %.2f; P(full board)=1/%.0f; MC mean %.3f)"
              % (ex['vault'], vd['soul'], vd['jackpots'], vd['grand'], 1 / vd['p_full'] if vd['p_full'] else 0, xv.mean()))
        print("  relics: bonus every %.0f spins (p_relic %.4f)" % (1 / trig, cfg.relic_chance))
        print("  base-game hit rate (any win, all >= stake): %.4f  (1 in %.2f)" % (hit_base, 1 / hit_base))
        print("  MC check of exact means: base %.4f vs %.4f | mini %.3f vs %.3f | vault %.3f vs %.3f"
              % (xb.mean(), ex['base'], xm.mean(), ex['mini'], xv.mean(), ex['vault']))
        print("\nstake | base RTP  mini mult  vault mult | f (1 in)   | RTP at f")
        for r in rows:
            print("%5d | %.4f   %8.3f   %9.3f | %.5f (%4.0f) | %.5f" %
                  (r['stake'], r['base'], r['mini'], r['vault'], r['f'], 1 / r['f'] if r['f'] > 0 else 0, r['rtp']))
        print("FEATURE_RATE = [" + ", ".join("%.4f" % r['f'] for r in rows) + "]")
    return ex, rows, dict(hit_base=hit_base, xb=xb, xm=xm, xv=xv)


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "report"
    if cmd == "exact":
        ex = exact_means(CFG)
        for k, v in ex.items():
            if k != "vault_detail":
                print(k, v)
        print(ex['vault_detail'])
    else:
        report()
