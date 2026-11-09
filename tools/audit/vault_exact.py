#!/usr/bin/env python3
"""
EXACT distribution of the Soul Vault total (x bonus bet), independent of the author's mean-only DP in tools/reaper_model.py.

The hold-and-spin is a Markov chain on (filled cells k, respins left r, collectable soul sum S, jackpot sum J).  Within one respin the
empty cells that fill are Binomial(20-k, p_orb) and each landing is an independent "orb event" (soul orb: S += v; reaper orb: S -> 2S
[it collects the whole soul sum, which then counts twice]; mini / minor / major jackpot orb: J += 15 / 40 / 150), so a respin with L
landings is the event operator applied L times.  The state (S, J-class) is propagated as a full probability array, giving the whole
distribution of V = S + J (+ 1000 if the board fills), not just its mean, and therefore E[min(V, c)] for the 25-book cap at any stake.
Truncation: S above 2000 is merged into one overflow bucket (V >= 2000, which the cap makes irrelevant for every stake); the jackpot
classes allow up to 10 minis, 6 minors and 6 majors (the mass beyond that is reported, ~1e-14).

    python3 tools/audit/vault_exact.py            # prints the check and writes build/audit/vault_exact.npy
"""
import itertools
import math
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from audit_lib import read_config  # noqa: E402

SMAX = 2000


def build(cfg):
    cells = cfg["vault_dims"][0] * cfg["vault_dims"][1]
    start, respins, rho = int(cfg["vault_dims"][2]), int(cfg["vault_dims"][3]), cfg["vault_dims"][4]
    ov, ow = cfg["orb_values"], cfg["orb_weights"]
    p_major, p_minor, p_mini, p_reaper = cfg["vault_special"]
    v_mini, v_minor, v_major, grand = cfg["vault_values"]
    q_s = 1.0 - p_major - p_minor - p_mini - p_reaper
    sw = float(sum(ow))

    classes = [(a, b, c) for a in range(11) for b in range(7) for c in range(7)]
    idx = {cl: i for i, cl in enumerate(classes)}
    jval = np.array([a * v_mini + b * v_minor + c * v_major for a, b, c in classes])
    nxt = {}
    for name, delta in (("mini", (1, 0, 0)), ("minor", (0, 1, 0)), ("major", (0, 0, 1))):
        m = np.full(len(classes), -1)
        for i, (a, b, c) in enumerate(classes):
            t = (a + delta[0], b + delta[1], c + delta[2])
            if t in idx:
                m[i] = idx[t]
        nxt[name] = m

    def event(A):
        """One orb lands. Returns (new array, mass that left the modelled region)."""
        new = np.zeros_like(A)
        lost = 0.0
        for v, w in zip(ov, ow):
            pv = q_s * w / sw
            new[v:] += pv * A[: SMAX + 1 - v]
            lost += pv * A[SMAX + 1 - v:].sum()
        half = SMAX // 2
        new[0: 2 * half + 1: 2] += p_reaper * A[: half + 1]
        lost += p_reaper * A[half + 1:].sum()
        for name, p in (("mini", p_mini), ("minor", p_minor), ("major", p_major)):
            m = nxt[name]
            ok = m >= 0
            new[:, m[ok]] += p * A[:, ok]
            lost += p * A[:, ~ok].sum()
        return new, lost

    # five starting soul orbs: S = sum of 5 i.i.d. soul values
    one = np.zeros(max(ov) + 1)
    for v, w in zip(ov, ow):
        one[v] += w / sw
    s5 = np.array([1.0])
    for _ in range(start):
        s5 = np.convolve(s5, one)
    A0 = np.zeros((SMAX + 1, len(classes)))
    A0[: len(s5), 0] = s5

    pending = {(start, respins): A0}
    hist = np.zeros(SMAX + 1)                       # V histogram, last bin = V >= SMAX
    full_mass = 0.0
    lost_total = 0.0

    def collect(A, weight, board_full):
        nonlocal full_mass
        base = grand if board_full else 0
        for c in range(len(classes)):
            col = A[:, c]
            if not col.any():
                continue
            vv = np.arange(SMAX + 1) + jval[c] + base
            np.add.at(hist, np.minimum(vv, SMAX), weight * col)
        if board_full:
            full_mass += weight * A.sum()

    for k in range(start, cells):
        for r in range(respins, 0, -1):
            A = pending.pop((k, r), None)
            if A is None:
                continue
            e = cells - k
            cur = A
            lost_cum = 0.0
            for L in range(0, e + 1):
                pl = math.comb(e, L) * rho ** L * (1 - rho) ** (e - L)
                if L > 0:
                    cur, lost = event(cur)
                    lost_cum += lost
                lost_total += pl * lost_cum
                if pl < 1e-18:
                    continue
                k2 = k + L
                if k2 >= cells:
                    collect(cur, pl, True)
                else:
                    r2 = respins if L > 0 else r - 1
                    if r2 <= 0:
                        collect(cur, pl, False)
                    else:
                        key = (k2, r2)
                        pending[key] = pending.get(key, 0.0) + pl * cur
    return hist, full_mass, lost_total


def main():
    cfg = read_config()
    hist, full_mass, lost = build(cfg)
    ys = np.arange(SMAX + 1)
    mean_below = float((hist[:SMAX] * ys[:SMAX]).sum())
    print("exact vault distribution: mass in bins below %d: %.15f, mass of V >= %d: %.3e (+ %.3e that left the modelled region, all with S > %d)"
          % (SMAX, hist[:SMAX].sum(), SMAX, hist[SMAX], lost, SMAX))
    hist[SMAX] += lost                                # the cap makes every V >= 2000 equal for every stake
    print("  total probability %.15f" % hist.sum())
    print("  P(full board / GRAND) = %.6e (1 in %.0f)" % (full_mass, 1 / full_mass))
    print("  E[V] (bins below %d) = %.6f, plus the V >= %d tail (< %.1e x 1500) -> E[V] = %.6f ... %.6f"
          % (SMAX, mean_below, SMAX, hist[SMAX], mean_below, mean_below + hist[SMAX] * 4000))
    for c in (50, 100, 200, 400, 666.6667, 1000, 2000):
        print("  E[min(V,%g)] = %.6f   P(V > %g) = %.6g" % (c, float((hist * np.minimum(ys, c)).sum()), c, float(hist[ys > c].sum())))
    out = sys.argv[1] if len(sys.argv) > 1 else "build/audit"
    os.makedirs(out, exist_ok=True)
    np.save(os.path.join(out, "vault_exact.npy"), hist)
    return hist


if __name__ == "__main__":
    main()
