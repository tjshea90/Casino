#!/usr/bin/env python3
"""
Exact math model for Dragon's Hoard (module :app) + tuner + simulator.

Why this exists
---------------
Slot RTP is *calculated*, not measured: every outcome has an exact probability, so
RTP = sum(probability x payout) can be computed in closed form (a casino "PAR
sheet").  This script is the reference implementation of that calculation.
`RtpTest.kt` re-derives the same numbers independently (brute-force enumeration of
all 11^5 line outcomes) and a multi-million-spin Monte-Carlo run of the real engine
confirms them, so three independent methods have to agree.

Model
-----
* 5 reels x 3 rows, 10 paylines, uniform independent reel stops on weighted
  virtual reel strips (BASE reels in the main game, FREE reels in free spins).
* Wild substitutes for everything but the scatter.  Left-to-right line pays; the
  best of (leading-wild pay, symbol pay) is paid per line.
* Scatter pays anywhere (x TOTAL bet); 3+ scatters award free spins (more for 4/5);
  free spins use the FREE reels, pay x FS_MULT and can retrigger.
* Per-line expectation only needs per-reel symbol marginals (rows are consecutive
  strip positions under a uniform stop) and linearity of expectation, so it is
  exact regardless of strip order.  Scatters are spaced >= 3 apart on every strip
  so a reel shows at most one (RtpTest asserts that): P(reel shows scatter)=3c/len.

Usage:  python3 tools/dragon_model.py                 # report on the config below
        python3 tools/dragon_model.py sim [spins]     # + Monte-Carlo cross-check
        python3 tools/dragon_model.py tune [seed]     # search for new reel weights
        python3 tools/dragon_model.py check           # verify this file still equals GameConfig.kt (reels/pays/free spins)
"""
import itertools
import random
import sys

W, S, TIG, KOI, LAN, ENV, A, K, Q, J, T = range(11)
NSYM = 11
NAMES = ["Wild", "Scatter", "Tiger", "Koi", "Lantern", "Envelope", "Ace", "King", "Queen", "Jack", "Ten"]

# ----------------------------------------------------------------------------------
# CONFIG (mirror of GameConfig.kt -- keep in sync; RtpTest checks the Kotlin side)
# ----------------------------------------------------------------------------------
# PAYS[symbol] = macks per 1-mack line bet for 2 / 3 / 4 / 5 of a kind (0 = no pay)
PAYS = {
    W:   (0, 25, 200, 1000),
    TIG: (1, 20, 75, 300), KOI: (1, 15, 50, 200), LAN: (0, 10, 40, 150), ENV: (0, 8, 25, 100),
    A:   (0, 4, 12, 50), K: (0, 4, 10, 40), Q: (0, 2, 8, 30), J: (0, 2, 6, 25), T: (0, 1, 5, 20),
}
SCATTER_PAYS = {0: 0, 1: 0, 2: 0, 3: 2, 4: 10, 5: 50}   # x total bet
FS_AWARD = {3: 10, 4: 15, 5: 25}                         # free spins by scatter count
FS_MULT = 3
LINES = 10

#            W  S TIG KOI LAN ENV A  K  Q  J  T      (== GameConfig.BASE_COUNTS / FREE_COUNTS; `check` verifies)
BASE = [
    [3, 2, 4, 3, 3, 3, 4, 3, 4, 6, 6],
    [2, 1, 3, 3, 4, 4, 3, 3, 5, 5, 7],
    [4, 1, 3, 3, 3, 3, 3, 4, 4, 4, 3],
    [5, 1, 3, 4, 4, 4, 3, 4, 5, 5, 6],
    [6, 2, 3, 3, 3, 2, 3, 4, 3, 6, 5],
]
FREE = [
    [5, 2, 4, 3, 4, 3, 4, 5, 4, 4, 8],
    [7, 2, 3, 3, 4, 3, 3, 4, 5, 7, 7],
    [6, 1, 4, 3, 4, 4, 5, 5, 6, 4, 7],
    [5, 2, 3, 2, 1, 4, 4, 6, 6, 6, 5],
    [6, 1, 2, 3, 4, 3, 2, 3, 5, 6, 4],
]


def marginals(reels):
    return [[c / sum(r) for c in r] for r in reels]


def line_stats(reels, pays=None):
    """Exact (EV, P(win)) of ONE payline per 1-mack line bet.

    Reads symbols left to right: `w` leading wilds, then the first non-wild symbol
    `t`; the run continues while the next symbol is `t` or wild.  Pay is
    max(wild pay for the w leading wilds, pay of t for the whole run)."""
    pays = pays or PAYS
    p = marginals(reels)
    ev = hit = 0.0
    p_all = 1.0
    for r in range(5):
        p_all *= p[r][W]
    ev += p_all * pays[W][3]
    hit += p_all
    lead = 1.0
    for w in range(5):                       # w leading wilds, then a non-wild at reel w
        wild_pay = pays[W][w - 2] if w >= 2 else 0
        for t in range(NSYM):
            if t == W:
                continue
            p_start = lead * p[w][t]
            if p_start == 0.0:
                continue
            cont = 1.0
            for m in range(w + 1, 6):        # run ends at first reel m that is not t / wild
                if m == 5:
                    n, pr = 5, cont
                else:
                    stay = p[m][t] + p[m][W]
                    n, pr = m, cont * (1.0 - stay)
                    cont *= stay
                if pr == 0.0:
                    continue
                sym_pay = pays[t][n - 2] if (t != S and t in pays and n >= 2) else 0
                pay = max(wild_pay, sym_pay)
                if pay:
                    ev += p_start * pr * pay
                    hit += p_start * pr
        lead *= p[w][W]
    return ev, hit


def scatter_dist(reels):
    """P(exactly k scatters on screen), k = 0..5 (<= 1 per reel by construction)."""
    dist = [1.0]
    for r in reels:
        pr = 3.0 * r[S] / sum(r)
        nd = [0.0] * (len(dist) + 1)
        for k, v in enumerate(dist):
            nd[k] += v * (1 - pr)
            nd[k + 1] += v * pr
        dist = nd
    return dist


def model(base=BASE, free=FREE, pays=None, fs_award=FS_AWARD, fs_mult=FS_MULT, verbose=False):
    el_b, hit_b = line_stats(base, pays)
    el_f, _ = line_stats(free, pays)
    db, df = scatter_dist(base), scatter_dist(free)
    scat_b = sum(db[k] * SCATTER_PAYS[k] for k in range(6))
    scat_f = sum(df[k] * SCATTER_PAYS[k] for k in range(6))
    trig_b = sum(db[k] for k in (3, 4, 5))
    aw_b = sum(db[k] * fs_award[k] for k in (3, 4, 5))            # FS awarded per base spin
    aw_f = sum(df[k] * fs_award[k] for k in (3, 4, 5))            # FS re-awarded per free spin
    if aw_f >= 1:
        return None
    n_fs = aw_b / (1 - aw_f)                                       # expected FS per base spin (incl. retriggers)
    fs_rtp = n_fs * fs_mult * (el_f + scat_f)
    rtp = el_b + scat_b + fs_rtp
    out = dict(rtp=rtp, line=el_b, scatter=scat_b, fs=fs_rtp, trig=1 / trig_b if trig_b else 1e9,
               hit_line=hit_b, fs_per_trigger=n_fs / trig_b if trig_b else 0,
               fs_win_x_bet=(n_fs / trig_b) * fs_mult * (el_f + scat_f) if trig_b else 0,
               retrig=sum(df[k] for k in (3, 4, 5)), dist_b=db, dist_f=df)
    if verbose:
        print("RTP %.5f  = lines %.5f + scatter %.5f + free spins %.5f   (FS share %.1f%%)" %
              (rtp, el_b, scat_b, fs_rtp, 100 * fs_rtp / rtp))
        print("trigger 1 in %.1f spins | avg FS per trigger %.2f | avg FS bonus win %.1f x total bet | retrigger/FS %.4f"
              % (out['trig'], out['fs_per_trigger'], out['fs_win_x_bet'], out['retrig']))
        print("per-line hit prob %.4f -> approx spin hit freq (independent lines, upper bound) %.3f" %
              (hit_b, 1 - (1 - hit_b) ** LINES))
    return out


# ----------------------------------------------------------------------------------
def brute_force_line_ev(reels, pays=None):
    """Independent check: enumerate every symbol combination (what RtpTest.kt does)."""
    pays = pays or PAYS
    p = marginals(reels)

    def pay(s):
        w = 0
        while w < 5 and s[w] == W:
            w += 1
        wp = pays[W][w - 2] if w >= 2 else 0
        if w == 5:
            return wp
        t = s[w]
        sp = 0
        if t != S:
            n = w
            while n < 5 and (s[n] == t or s[n] == W):
                n += 1
            sp = pays[t][n - 2] if n >= 2 else 0
        return max(wp, sp)

    ev = hit = 0.0
    for combo in itertools.product(range(NSYM), repeat=5):
        pr = 1.0
        for r, s in enumerate(combo):
            pr *= p[r][s]
        if pr == 0.0:
            continue
        v = pay(combo)
        if v:
            ev += pr * v
            hit += pr
    return ev, hit


# ----------------------------------------------------------------------------------
# Monte-Carlo (numpy) -- spin-level statistics the closed form cannot give (hit
# frequency across all 10 lines, volatility).  Strips are built exactly like
# GameConfig.buildStrip so the simulated reels match the app's reels.
# ----------------------------------------------------------------------------------
PAYLINES = [(1, 1, 1, 1, 1), (0, 0, 0, 0, 0), (2, 2, 2, 2, 2), (0, 1, 2, 1, 0), (2, 1, 0, 1, 2),
            (0, 0, 1, 2, 2), (2, 2, 1, 0, 0), (1, 0, 0, 0, 1), (1, 2, 2, 2, 1), (1, 2, 1, 0, 1)]


def build_strip(counts, seed):
    """Port of GameConfig.buildStrip (Kotlin)."""
    n = len(counts)
    length = sum(counts)
    strip = [-1] * length
    sc = counts[S]
    for i in range(sc):
        strip[(i * length // sc + seed) % length] = S
    placed = [0] * n
    placed[S] = sc
    order = sorted([s for s in range(n) if s != S], key=lambda s: (s * 7 + seed * 3) % n)
    filled = sc
    prev = -1
    for pos in range(length):
        if strip[pos] != -1:
            prev = strip[pos]
            continue
        filled += 1
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


def simulate(base=BASE, free=FREE, pays=None, n_spins=1_000_000, seed=7, fs_award=FS_AWARD, fs_mult=FS_MULT):
    """Full-game simulation incl. free spins + retriggers (vectorised). Spin-level stats
    in units of the TOTAL bet (10 line bets)."""
    import numpy as np
    pays = pays or PAYS
    rng = np.random.default_rng(seed)
    bstrips = [np.array(build_strip(c, i)) for i, c in enumerate(base)]
    fstrips = [np.array(build_strip(c, i + 7)) for i, c in enumerate(free)]
    pay_tab = np.zeros((NSYM, 6))
    for s, (p2, p3, p4, p5) in pays.items():
        pay_tab[s, 2], pay_tab[s, 3], pay_tab[s, 4], pay_tab[s, 5] = p2, p3, p4, p5
    sc_tab = np.array([SCATTER_PAYS[k] for k in range(6)], dtype=float)
    aw_tab = np.array([fs_award.get(k, 0) for k in range(6)])

    def play(strips, count):
        grid = np.empty((count, 5, 3), dtype=np.int8)
        for r in range(5):
            st = strips[r]
            stops = rng.integers(0, len(st), count)
            for row in range(3):
                grid[:, r, row] = st[(stops + row) % len(st)]
        total = np.zeros(count)                       # in line bets
        ar = np.arange(count)
        for ln in PAYLINES:
            s = grid[:, np.arange(5), ln].astype(np.int16)
            isw = s == W
            lead = np.zeros(count, dtype=np.int16)
            run = np.ones(count, dtype=bool)
            for r in range(5):
                run &= isw[:, r]
                lead += run
            wp = np.where(lead >= 2, pay_tab[W][np.minimum(lead, 5)], 0.0)
            tgt = np.where(lead == 5, W, s[ar, np.minimum(lead, 4)])
            n = np.zeros(count, dtype=np.int16)
            run = np.ones(count, dtype=bool)
            for r in range(5):
                run &= (s[:, r] == tgt) | isw[:, r]
                n += run
            sp = np.where((tgt != S) & (n >= 2), pay_tab[tgt, np.minimum(n, 5)], 0.0)
            total += np.maximum(wp, sp)
        scat = (grid == S).sum(axis=(1, 2))
        return total, scat

    tot, scat = play(bstrips, n_spins)
    win = tot + sc_tab[scat] * LINES                  # scatter pays are x total bet = LINES line bets
    hit = win > 0
    fs_left = aw_tab[scat].astype(np.int64)
    trig = fs_left > 0
    bonus = np.zeros(n_spins)
    owner = np.nonzero(trig)[0]                       # free spins processed in lock-step batches
    left = fs_left[owner].copy()
    acc = np.zeros(len(owner))
    while (left > 0).any():
        act = np.nonzero(left > 0)[0]
        t, sc = play(fstrips, len(act))
        acc[act] += fs_mult * (t + sc_tab[sc] * LINES)
        left[act] += aw_tab[sc] - 1
    bonus[owner] = acc
    per_bet = (win + bonus) / LINES
    return dict(rtp=per_bet.mean(), hit=hit.mean(), sd=per_bet.std(), trig=n_spins / max(1, trig.sum()),
                p_ge_1x=(per_bet >= 1).mean(),          # spins returning at least the whole stake ("true wins")
                p_ge_5x=(per_bet >= 5).mean(), p_ge_20x=(per_bet >= 20).mean(), p_ge_100x=(per_bet >= 100).mean(),
                max=per_bet.max(), base_only_rtp=(win / LINES).mean(),
                se=per_bet.std() / n_spins ** 0.5)


# ----------------------------------------------------------------------------------
def tune(seed=1, iters=80000, target=0.94, pays=None, fs_award=FS_AWARD, min_prem=3, start=None,
         hit_goal=0.075, trig_goal=100.0):
    """Hill-climb the reel weights.  Goal-based cost: exact RTP (dominant), free-spin
    frequency/size, a healthy per-line hit rate, and a mild pull towards the existing
    reels so symbol presence/look stays familiar."""
    rnd = random.Random(seed)
    B0 = [r[:] for r in (start[0] if start else BASE)]
    F0 = [r[:] for r in (start[1] if start else FREE)]
    B, F = [r[:] for r in B0], [r[:] for r in F0]

    def cost(m, tb, tf):
        if m is None:
            return 1e9
        drift = sum(abs(a - b) for ra, rb in zip(tb + tf, BASE + FREE) for a, b in zip(ra, rb))
        return ((m['rtp'] - target) * 600) ** 2 + ((m['trig'] - trig_goal) / 6) ** 2 \
            + ((m['fs'] / m['rtp'] - 0.30) * 50) ** 2 + ((m['hit_line'] - hit_goal) * 250) ** 2 \
            + ((m['fs_win_x_bet'] - 36) / 6) ** 2 + 0.05 * drift

    cur = model(B, F, pays, fs_award)
    c = cost(cur, B, F)
    for it in range(iters):
        tb, tf = [r[:] for r in B], [r[:] for r in F]
        tgt, lo = (tb, min_prem) if rnd.random() < 0.6 else (tf, max(2, min_prem - 1))
        r, s_, d = rnd.randrange(5), rnd.randrange(NSYM), rnd.choice((-1, 1))
        tgt[r][s_] += d
        row = tgt[r]
        floor = {W: 2, S: 1}.get(s_, lo)
        if row[s_] < floor or row[s_] > 12 or not 34 <= sum(row) <= 52:
            continue
        if s_ == S and not 1 <= row[s_] <= 2:
            continue
        m = model(tb, tf, pays, fs_award)
        cc = cost(m, tb, tf)
        if cc < c:
            B, F, cur, c = tb, tf, m, cc
    return B, F, cur


def polish(B, F, pays=None, fs_award=FS_AWARD, lo=0.93990, hi=0.94000, min_prem=3, trig_range=(95, 118),
           hit_min=0.068, fs_share=(0.28, 0.36), max_moves=3, limit=400000, seed=0):
    """Exhaustive/random local search for a nearby reel set whose exact RTP lies in
    [lo, hi] (never above `hi`, so the house edge is >= 1-hi) while keeping the feature
    frequency / hit rate within bounds.  Returns the candidate closest to the band centre
    with the least drift from (B, F)."""
    rnd = random.Random(seed)
    best = None

    def ok_strip(row, s_, is_base):
        floor = {W: 2, S: 1}.get(s_, min_prem if is_base else max(2, min_prem - 1))
        return row[s_] >= floor and row[s_] <= 12 and 34 <= sum(row) <= 52 and (s_ != S or 1 <= row[s_] <= 2)

    for it in range(limit):
        tb, tf = [r[:] for r in B], [r[:] for r in F]
        for _ in range(rnd.randint(1, max_moves)):
            is_base = rnd.random() < 0.6
            tgt = tb if is_base else tf
            r, s_, d = rnd.randrange(5), rnd.randrange(NSYM), rnd.choice((-1, 1))
            tgt[r][s_] += d
            if not ok_strip(tgt[r], s_, is_base):
                tgt[r][s_] -= d
        m = model(tb, tf, pays, fs_award)
        if m is None or not (lo <= m['rtp'] <= hi):
            continue
        if not (trig_range[0] <= m['trig'] <= trig_range[1]) or m['hit_line'] < hit_min:
            continue
        if not (fs_share[0] <= m['fs'] / m['rtp'] <= fs_share[1]):
            continue
        drift = sum(abs(a - b) for ra, rb in zip(tb + tf, B + F) for a, b in zip(ra, rb))
        score = drift - 40 * m['hit_line'] + abs(m['trig'] - 105) / 10
        if best is None or score < best[0]:
            best = (score, tb, tf, m)
    return best


def check_against_kotlin():
    """Fail loudly if this file's reels / pays / free-spin table differ from GameConfig.kt (the shipped game)."""
    import os
    import re
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "java", "com", "tjshea",
                        "dragonhoard", "engine", "GameConfig.kt")
    src = open(path).read()

    def rows(name):
        body = re.search(r"val %s: Array<IntArray> = arrayOf\((.*?)\n    \)" % name, src, re.S).group(1)
        return [[int(x) for x in r.split(",")] for r in re.findall(r"intArrayOf\(([^)]*)\)", body)]

    ok = True
    for name, mine in (("BASE_COUNTS", BASE), ("FREE_COUNTS", FREE)):
        same = rows(name) == mine
        ok &= same
        print("%-12s %s" % (name, "matches GameConfig.kt" if same else "DIFFERS from GameConfig.kt !!!"))
    pays_kt = rows("PAYS")
    same = all(tuple(pays_kt[s]) == p for s, p in PAYS.items())
    ok &= same
    print("%-12s %s" % ("PAYS", "matches GameConfig.kt" if same else "DIFFERS from GameConfig.kt !!!"))
    fs = [int(x) for x in re.search(r"FREE_SPINS_BY_SCATTERS = intArrayOf\(([^)]*)\)", src).group(1).split(",")]
    same = all(fs[k] == v for k, v in FS_AWARD.items())
    ok &= same
    print("%-12s %s" % ("FS_AWARD", "matches GameConfig.kt" if same else "DIFFERS from GameConfig.kt !!!"))
    return ok


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "report"
    if cmd == "check":
        raise SystemExit(0 if check_against_kotlin() else 1)
    if cmd == "polish":
        sd = int(sys.argv[2]) if len(sys.argv) > 2 else 3
        B, F, _ = tune(sd)
        best = polish(B, F, seed=sd)
        if best is None:
            print("no candidate in band")
        else:
            _, B, F, m = best
            print("BASE =", B)
            print("FREE =", F)
            model(B, F, verbose=True)
    elif cmd == "tune":
        B, F, m = tune(int(sys.argv[2]) if len(sys.argv) > 2 else 1)
        print("BASE =", B)
        print("FREE =", F)
        model(B, F, verbose=True)
    else:
        model(verbose=True)
        ev, hit = brute_force_line_ev(BASE)
        print("brute-force line EV %.6f hit %.6f   vs closed form %s" % (ev, hit, line_stats(BASE)))
        if cmd == "sim":
            n = int(sys.argv[2]) if len(sys.argv) > 2 else 2_000_000
            r = simulate(n_spins=n)
            print("MC %d spins: " % n + "  ".join("%s=%.4f" % (k, v) for k, v in r.items()))
