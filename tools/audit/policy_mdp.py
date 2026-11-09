#!/usr/bin/env python3
"""
Exact search for the BEST staking / play policy against Neon Reaper 3.0 (adaptive stakes, stopping, feature buys).

Why this can be solved exactly.  Spin outcomes (lines, minis, coffins, Seance) have the same distribution at every spin, whatever
happened before; only the relic meter (relics held r, sum S of the stakes on the relic-revealing spins) is a state.  A spin at
stake s returns s*a(s) on average without the vault (a(s) from the measured per-stake table) and reveals a relic with
probability p = RELIC_CHANCE whatever the stake.  The fifth relic pays the vault with bonus bet b = floor(S'/5), worth
vtab[b] = E[min(MAX_WIN, V*b)] tokens.  So:

 (1) LONG RUN.  Between relic events the state does not change, so an optimal policy repeats one stake per state; a policy is a
     sequence of five stakes (s1..s5) per vault cycle and its return per token wagered is
         RTP(s1..s5) = ( sum s_i*a(s_i) + p*vtab[floor(sum s_i / 5)] ) / sum s_i .
     We evaluate ALL 4368 multisets of five stakes: the maximum is the best long-run RTP any staking policy can reach.
 (2) FINITE HORIZON with stopping and buys.  Backward induction over (relics, S) for up to T spins:
         V_t(r,S) = max( 0 [quit], max_s [ s*(a(s)-1) + p*next + (1-p)*V_{t-1}(r,S) ], buy Seance, buy Vault )
     V_T(0,0) > 0 would be a strategy with positive expected profit from an empty meter.  The same recursion with absorption at the
     vault gives G(r,S): the value of a pre-built partial meter (what a player gains by inheriting it).
Usage: python3 tools/audit/policy_mdp.py build/audit
"""
import itertools
import math
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import audit_rtp  # noqa: E402


def main(out, horizon=3000):
    cp = audit_rtp.load_components(out)
    rows = audit_rtp.stake_rows(cp)
    cfg = cp.cfg
    stakes = cfg["stakes"]
    cap = cfg["max_win_tokens"]
    p = cfg["relic"][1]
    buy_s, buy_v = cfg["buy_x"]
    a = {r["stake"]: r["spin"] + r["seance"] for r in rows}                  # non-vault return per token wagered
    buy_seance_ret = {r["stake"]: r["seance3_capped"] * r["stake"] for r in rows}      # tokens returned by a bought Seance
    buy_vault_ret = {r["stake"]: r["vault_capped"] * r["stake"] for r in rows}
    vtab = np.zeros(41)
    for b in range(1, 41):
        vtab[b] = b * cp.V.capped(cap / b)[0]

    print("=" * 100)
    print("(1) LONG RUN: best RTP over every multiset of five stakes (4368 policies); p = %.5f, V mean %.3f, SD %.2f" % (p, cp.V.mean(), math.sqrt(cp.V.var())))
    res = []
    for combo in itertools.combinations_with_replacement(stakes, 5):
        w = sum(combo)
        ret = sum(s * a[s] for s in combo) + p * vtab[min(40, max(1, w // 5))]
        res.append((ret / w, combo))
    res.sort(reverse=True)
    print("   rank  RTP %%      stakes of the five relic-revealing stretches")
    for i, (r, c) in enumerate(res[:8]):
        print("   %4d  %.4f   %s" % (i + 1, 100 * r, c))
    mixed = [x for x in res if len(set(x[1])) > 1]
    print("   best policy that mixes stakes: %.4f %% %s | worst of all: %.4f %% %s" % (100 * mixed[0][0], mixed[0][1], 100 * res[-1][0], res[-1][1]))
    print("   constant stake RTP: " + "  ".join("%d:%.3f" % (s, 100 * r) for s in stakes for r, c in res if c == (s,) * 5))
    best_rtp = res[0][0]
    print("   => no staking policy exceeds %.4f %% (target 94 %%)" % (100 * best_rtp))

    print("=" * 100)
    print("(2) FINITE HORIZON, optimal stopping, any stake per spin, feature buys allowed")
    R, SM = 5, 40 * 4 + 1
    vprev = np.zeros((R, SM + 41))
    ev_stay = {s: s * (a[s] - 1.0) for s in stakes}
    checkpoints = {1, 5, 10, 50, 100, 500, 1000, horizon}
    for t in range(1, horizon + 1):
        vcur = np.zeros_like(vprev)
        for r in range(R):
            S = np.arange(0, 40 * r + 1)
            best = np.zeros(len(S))                                  # quitting is always possible and worth 0
            for s in stakes:
                if r < 4:
                    nxt = vprev[r + 1][S + s]
                else:
                    b = np.clip((S + s) // 5, 1, 40)
                    nxt = vtab[b] + vprev[0][0]
                cand = ev_stay[s] + p * nxt + (1 - p) * vprev[r][S]
                best = np.maximum(best, cand)
                # feature buys leave the relic meter untouched
                best = np.maximum(best, buy_seance_ret[s] - buy_s * s + vprev[r][S])
                best = np.maximum(best, buy_vault_ret[s] - buy_v * s + vprev[r][S])
            vcur[r][S] = best
        vprev = vcur
        if t in checkpoints:
            print("   horizon %5d spins: best expected profit from an empty meter = %.9f tokens" % (t, vprev[0][0]))
    v00 = vprev[0][0]
    print("   => %s" % ("no policy has positive expected profit (quitting at once is optimal)" if v00 <= 1e-9 else "!!! POSITIVE EXPECTED PROFIT %.6f tokens !!!" % v00))

    print("=" * 100)
    print("(3) stored value of a pre-built meter, G(r,S): best expected net profit of playing on until the vault pays, then quitting")
    G = np.zeros((R + 1, 40 * 5 + 1))
    for r in range(4, -1, -1):
        for S in range(0, 40 * r + 1):
            best = 0.0
            for s in stakes:
                if r == 4:
                    nxt = vtab[min(40, max(1, (S + s) // 5))]
                else:
                    nxt = G[r + 1][S + s]
                best = max(best, ev_stay[s] / p + nxt)
            G[r][S] = best
    print("   G(0,0) = %.6f  (an empty meter is worth nothing)" % G[0][0])
    for (r, S) in [(4, 4), (4, 40), (4, 80), (4, 160), (3, 120), (2, 80), (1, 40)]:
        print("   meter with %d relics collected at a stake sum of %3d (mean %.0f): worth %+9.2f tokens to whoever plays on" % (r, S, S / r, G[r][S]))
    best_state = max(((G[r][S], r, S) for r in range(5) for S in range(0, 40 * r + 1)))
    print("   largest stored value: %.1f tokens (%.1f books) at r=%d, S=%d: what a handed-over full-stake meter is worth" % (best_state[0], best_state[0] / 80, best_state[1], best_state[2]))
    print("   to build that state the original player wagered about %.0f tokens, expecting to lose %.0f of them" % (4 * 40 / p, 4 * 40 / p * (1 - a[40])))
    return best_rtp, v00


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "build/audit", int(sys.argv[2]) if len(sys.argv) > 2 else 3000)
