#!/usr/bin/env python3
"""
Combines the exact enumerations and the Monte-Carlo components (see run_audit.py) into Neon Reaper's RTP at every stake.

Per-spin return for a constant stake s (everything in multiples of the stake; the win cap is c = MAX_WIN_TOKENS / s multiples):

  RTP(s) = (1-f) * [ E min(X_plain, c)  +  sum_k Pp(k) * M_k(c) ]                      plain spins
         +  f    * [ sum_t w_t * E min(X_t, c)  +  sum_k sum_t w_t P_t(k) * M_k(c) ]    spins with a mini feature (f = FEATURE_RATE)
         +  (RELIC_CHANCE / RELIC_COUNT) * E min(V, c)                                  Soul Vault: one per 5 relics, bonus bet = stake

  X = line wins x multiplier + soul coins + coffin pay on the final grid,  M_k = THE SEANCE started by k coffins,  V = vault total.
  Exact (enumerated, zero sampling error): X_plain, P(k), Reaper's Touch, Demon's Bargain.  Monte-Carlo: Crows, Skull, M_k, V.
Standard errors combine the independent Monte-Carlo pieces; the exact pieces contribute none.
"""
import glob
import math
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from audit_lib import Dist, read_config, read_hist_xk, read_hist_y  # noqa: E402


def merged_xk(files):
    n, cells = 0, {}
    for f in files:
        m, c = read_hist_xk(f)
        n += m
        for key, v in c.items():
            cells[key] = cells.get(key, 0) + v
    return n, cells


def merged_y(files):
    n, cells, spins = 0, {}, []
    for f in files:
        m, c, s = read_hist_y(f)
        n += m
        if s is not None:
            spins.append((m, s))
        for key, v in c.items():
            cells[key] = cells.get(key, 0) + v
    sp = sum(m * s for m, s in spins) / sum(m for m, _ in spins) if spins else None
    return n, cells, sp


def mixture(dists, weights):
    """Exact mixture of exact distributions."""
    ys = sorted({float(y) for d in dists for y in d.y})
    idx = {y: i for i, y in enumerate(ys)}
    p = np.zeros(len(ys))
    for d, w in zip(dists, weights):
        for y, q in zip(d.y, d.p):
            p[idx[float(y)]] += w * q
    return Dist(ys, p * 1e18, exact=True)


def k_dist(cells, n):
    pk = np.zeros(6)
    for (x, k), c in cells.items():
        pk[k] += c
    return pk / n


def coin_sum_dist(cfg):
    """Exact distribution of the Demon's Bargain coin total (count and values drawn i.i.d. from the config weights)."""
    cnt_w = np.array(cfg["coin_count_weights"], float); cnt_w /= cnt_w.sum()
    val_w = np.array(cfg["coin_weights"], float); val_w /= val_w.sum()
    vals = cfg["coin_values"]
    one = np.zeros(max(vals) + 1)
    for v, w in zip(vals, val_w):
        one[v] += w
    total = np.zeros(max(cfg["coin_counts"]) * max(vals) + 1)
    cur = np.array([1.0])
    powers = {0: cur}
    for i in range(1, max(cfg["coin_counts"]) + 1):
        cur = np.convolve(cur, one)
        powers[i] = cur
    for c, w in zip(cfg["coin_counts"], cnt_w):
        total[: len(powers[c])] += w * powers[c]
    nz = np.nonzero(total)[0]
    return Dist(nz, total[nz] * 1e18, exact=True)


def convolve_exact(a, b):
    ai = np.zeros(int(a.y.max()) + 1); ai[a.y.astype(int)] = a.p
    bi = np.zeros(int(b.y.max()) + 1); bi[b.y.astype(int)] = b.p
    c = np.convolve(ai, bi)
    nz = np.nonzero(c)[0]
    return Dist(nz, c[nz] * 1e18, exact=True)


class Comp:
    """Everything measured, ready to be combined for any stake / cap."""


def load_components(out):
    cp = Comp()
    cfg = cp.cfg = read_config()
    cp.f = cfg["feature_rate"]
    cp.scat = cfg["scatter_pay"]
    cp.p_v = cfg["relic"][1] / cfg["relic"][0]
    mw = np.array(cfg["mini_weights"], float)
    cp.mw = mw[1:] / mw[1:].sum()                     # types 1..4 (reaper, crows, demon, skull)
    scat = cp.scat

    n_base, base = read_hist_xk(os.path.join(out, "base.hist"))
    assert n_base == 74 ** 5, "base enumeration incomplete"
    cp.n_base = n_base
    cp.Xp = Dist.from_xk(base, lambda x, k: x + scat[k], exact=True)
    cp.Pp = k_dist(base, n_base)
    cp.lines_plain = sum(x * c for (x, k), c in base.items()) / n_base
    cp.scatpay_plain = sum(scat[k] * c for (x, k), c in base.items()) / n_base

    pats = cfg["reaper_pattern"]
    pw = np.array([w for w, _ in pats]); pw /= pw.sum()
    reaper_d, cp.reaper_k, cp.reaper_lines, cp.reaper_sp = [], np.zeros(6), 0.0, 0.0
    for i, (w, reels) in enumerate(pats):
        n_i, cells_i = read_hist_xk(os.path.join(out, "reaper_p%d.hist" % i))
        reaper_d.append(Dist.from_xk(cells_i, lambda x, k: x + scat[k], exact=True))
        cp.reaper_k += pw[i] * k_dist(cells_i, n_i)
        cp.reaper_lines += pw[i] * sum(x * c for (x, k), c in cells_i.items()) / n_i
        cp.reaper_sp += pw[i] * sum(scat[k] * c for (x, k), c in cells_i.items()) / n_i
    cp.Xreaper = mixture(reaper_d, pw)
    coins = coin_sum_dist(cfg)
    cp.Xdemon = convolve_exact(cp.Xp, coins)
    cp.e_coins = float((coins.p * coins.y).sum())

    cp.mini = {}
    for t in (1, 2, 3, 4):
        files = sorted(glob.glob(os.path.join(out, "mini%d_*.hist" % t)))
        if not files:
            continue
        n_t, cells_t = merged_xk(files)
        cp.mini[t] = dict(n=n_t, dist=Dist.from_xk(cells_t, lambda y, k: y), k=k_dist(cells_t, n_t),
                          sp=sum(scat[k] * c for (y, k), c in cells_t.items()) / n_t)
    for t in (2, 4):
        if t not in cp.mini:
            raise SystemExit("missing Monte-Carlo files for mini type %d" % t)

    cp.M, cp.spins_played = {}, {}
    for k in (3, 4, 5):
        files = sorted(glob.glob(os.path.join(out, "seance%d_*.hist" % k)))
        if not files:
            raise SystemExit("missing Seance files for %d coffins" % k)
        n_k, cells_k, sp = merged_y(files)
        cp.M[k] = Dist.from_y(cells_k); cp.spins_played[k] = (sp, n_k)
    files = sorted(glob.glob(os.path.join(out, "vault_*.hist")))
    if not files:
        raise SystemExit("missing vault files")
    cp.n_v, cells_v, _ = merged_y(files)
    cp.V = Dist.from_y(cells_v)

    cp.T_dist = {1: cp.Xreaper, 2: cp.mini[2]["dist"], 3: cp.Xdemon, 4: cp.mini[4]["dist"]}
    cp.T_k = {1: cp.reaper_k, 2: cp.mini[2]["k"], 3: cp.Pp, 4: cp.mini[4]["k"]}
    cp.coef_k = np.zeros(6)                            # chance per spin that a Seance starts with k coffins
    cp.coef_k += (1 - cp.f) * cp.Pp
    for t, w in zip((1, 2, 3, 4), cp.mw):
        cp.coef_k += cp.f * w * cp.T_k[t]
    return cp


def stake_rows(cp, stakes=None):
    cfg = cp.cfg
    cap_tokens = cfg["max_win_tokens"]
    buy_s, buy_v = cfg["buy_x"]
    rows = []
    for s, claimed in zip(stakes or cfg["stakes"], cfg["rtp_by_stake_claimed"]):
        c = cap_tokens / s
        a_plain, _ = cp.Xp.capped(c)
        spin_part = (1 - cp.f) * a_plain
        var = 0.0
        for t, w in zip((1, 2, 3, 4), cp.mw):
            m_t, se_t = cp.T_dist[t].capped(c)
            spin_part += cp.f * w * m_t
            var += (cp.f * w * se_t) ** 2
        sean_part = 0.0
        for k in (3, 4, 5):
            m_k, se_k = cp.M[k].capped(c)
            sean_part += cp.coef_k[k] * m_k
            var += (cp.coef_k[k] * se_k) ** 2
        m_v, se_v = cp.V.capped(c)
        vault_part = cp.p_v * m_v
        var += (cp.p_v * se_v) ** 2
        m3, se3 = cp.M[3].capped(c)
        rows.append(dict(stake=s, spin=spin_part, seance=sean_part, vault=vault_part, rtp=spin_part + sean_part + vault_part,
                         se=math.sqrt(var), claimed=claimed, buy_seance=m3 / buy_s, buy_seance_se=se3 / buy_s,
                         buy_vault=m_v / buy_v, buy_vault_se=se_v / buy_v, vault_capped=m_v, seance3_capped=m3))
    return rows


def report(cp):
    cfg, f, scat = cp.cfg, cp.f, cp.scat
    mw, coef_k, M, V = cp.mw, cp.coef_k, cp.M, cp.V
    mini, Pp, Xp, Xreaper, Xdemon = cp.mini, cp.Pp, cp.Xp, cp.Xreaper, cp.Xdemon
    p_v = cp.p_v
    buy_s, buy_v = cfg["buy_x"]
    print("=" * 100)
    print("SELF-TESTS of the Monte-Carlo code against the exact enumerations")
    for t, label, exact_d, exact_k in ((1, "Reaper's Touch", Xreaper, cp.reaper_k), (3, "Demon's Bargain", Xdemon, Pp)):
        if t in mini:
            m, se = mini[t]["dist"].capped(1e18)
            z = (m - exact_d.mean()) / se
            pk_mc = mini[t]["k"]
            kz = max(abs(pk_mc[k] - exact_k[k]) / math.sqrt(max(exact_k[k] * (1 - exact_k[k]), 1e-30) / mini[t]["n"]) for k in (3, 4))
            print("  %-16s exact mean %.5f  MC %.5f +/- %.5f (z %+.2f)   P(3+) exact %.6f MC %.6f (max z over k=3,4: %.2f)   [N=%d]"
                  % (label, exact_d.mean(), m, se, z, exact_k[3:].sum(), pk_mc[3:].sum(), kz, mini[t]["n"]))
    print("  plain-spin exhaustive enumeration: %d stop combinations, E[X] = %.12f, P(any win) = %.6f (1 in %.3f), "
          "min positive win = %d x stake, max = %d x stake"
          % (cp.n_base, Xp.mean(), Xp.prob_gt(0), 1 / Xp.prob_gt(0), int(Xp.y[Xp.y > 0].min()), int(Xp.y.max())))

    seance_unc = sum(coef_k[k] * M[k].mean() for k in (3, 4, 5))
    lines_mix = (1 - f) * cp.lines_plain + f * (mw[0] * cp.reaper_lines + mw[1] * (mini[2]["dist"].mean() - mini[2]["sp"])
                                               + mw[2] * (cp.lines_plain + cp.e_coins) + mw[3] * (mini[4]["dist"].mean() - mini[4]["sp"]))
    sp_mix = (1 - f) * cp.scatpay_plain + f * (mw[0] * cp.reaper_sp + mw[1] * mini[2]["sp"] + mw[2] * cp.scatpay_plain + mw[3] * mini[4]["sp"])
    vault_unc = p_v * V.mean()
    total_unc = lines_mix + sp_mix + seance_unc + vault_unc
    trig = coef_k[3:].sum()
    print("=" * 100)
    print("EXACT / MEASURED BUDGET at stake 1, no cap (claimed: lines+minis 57.77 + coffin pays 1.11 + Seance 14.12 + Vault 21.00 = 94.000)")
    print("  lines + mini features (+ soul coins) %7.3f %%" % (100 * lines_mix))
    print("  coffin scatter pays                   %7.3f %%" % (100 * sp_mix))
    print("  THE SEANCE                            %7.3f %%   (one per %.1f spins, mean %.2f x stake, P(3/4/5 coffins | trigger) = %.3f/%.3f/%.5f)"
          % (100 * seance_unc, 1 / trig, seance_unc / trig, coef_k[3] / trig, coef_k[4] / trig, coef_k[5] / trig))
    print("  SOUL VAULT                            %7.3f %%   (one per %.1f spins, mean %.4f x bonus bet, SD %.2f, N=%d)"
          % (100 * vault_unc, 1 / p_v, V.mean(), math.sqrt(V.var()), cp.n_v))
    print("  TOTAL                                 %7.3f %%" % (100 * total_unc))
    hit_mix = (1 - f) * Xp.prob_gt(0) + f * sum(w * cp.T_dist[t].prob_gt(0) for t, w in zip((1, 2, 3, 4), mw))
    print("  any-win frequency of a spin (lines/coins/coffin pay): %.4f (1 in %.2f) | mini feature 1 in %.0f spins" % (hit_mix, 1 / hit_mix, 1 / f))
    for k in (3, 4, 5):
        sp, nk = cp.spins_played[k]
        print("  Seance started by %d coffins: mean %.3f x stake (SD %.2f, N=%d), spins played %.3f, P(>50x)=%.4f P(>100x)=%.4f"
              % (k, M[k].mean(), math.sqrt(M[k].var()), nk, sp, M[k].prob_gt(50), M[k].prob_gt(100)))

    rows = stake_rows(cp)
    print("=" * 100)
    print("RTP BY STAKE (constant stake, 25-book cap = %d tokens)   [95%% CI = +/- 1.96 SE]" % cfg["max_win_tokens"])
    print("stake | spin pays   Seance    Vault  |  RTP %      SE %    95% CI            | claimed %  diff (pp)  z   | buy Seance  buy Vault")
    for r in rows:
        print("%5d | %8.3f %8.3f %8.3f  | %8.4f %8.4f  [%7.3f,%7.3f]  | %8.2f  %+8.3f  %+5.1f  |  %7.3f %%  %7.3f %%"
              % (r["stake"], 100 * r["spin"], 100 * r["seance"], 100 * r["vault"], 100 * r["rtp"], 100 * r["se"],
                 100 * (r["rtp"] - 1.96 * r["se"]), 100 * (r["rtp"] + 1.96 * r["se"]),
                 100 * r["claimed"], 100 * (r["rtp"] - r["claimed"]), (r["rtp"] - r["claimed"]) / r["se"] if r["se"] > 0 else float("nan"),
                 100 * r["buy_seance"], 100 * r["buy_vault"]))
    print("(components are shares of the wager: 'spin pays' = lines + mini features + soul coins + coffin pays, capped jointly per spin)")
    worst = max(rows, key=lambda r: r["rtp"] + 3 * r["se"])
    print("highest RTP + 3 SE over all stakes: %.4f %% at stake %d (target 94 %%)" % (100 * (worst["rtp"] + 3 * worst["se"]), worst["stake"]))
    print("=" * 100)
    print("FEATURE BUYS (price %d x stake for the Seance = the 3-coffin award, %d x stake for the Vault)" % (buy_s, buy_v))
    print("  exact EV: Seance %.4f x stake -> price >= EV/0.93 = %.4f ? %s | Vault %.4f x bonus bet -> price >= EV/0.93 = %.4f ? %s"
          % (M[3].mean(), M[3].mean() / 0.93, buy_s >= M[3].mean() / 0.93, V.mean(), V.mean() / 0.93, buy_v >= V.mean() / 0.93))
    print("  buy RTP at stake 1: Seance %.3f %% (+/- %.3f), Vault %.3f %% (+/- %.3f); highest over stakes: Seance %.3f %%, Vault %.3f %%"
          % (100 * rows[0]["buy_seance"], 100 * rows[0]["buy_seance_se"], 100 * rows[0]["buy_vault"], 100 * rows[0]["buy_vault_se"],
             100 * max(r["buy_seance"] for r in rows), 100 * max(r["buy_vault"] for r in rows)))
    return rows


def main(out):
    return report(load_components(out))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "build/audit")
