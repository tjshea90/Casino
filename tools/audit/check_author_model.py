#!/usr/bin/env python3
"""
Completeness check of tools/reaper_model.py against the REAL ReaperConfig (via tools/audit/config_snapshot.txt).

`python3 tools/reaper_model.py check` only compares reel COUNTS, pays, scatter / free-spin tables, FEATURE_RATE, RELIC_CHANCE and the
buy prices. This script also compares everything else the exact model silently depends on: the paylines, the actual reel strip ORDER
(the author's Python port of buildStrip vs the strips Kotlin really builds), the mini-feature weights, the coin / raven / skull tables
and every Soul Vault parameter. Exit status 1 if anything differs.

    python3 tools/audit/check_author_model.py
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, ".."))
from audit_lib import read_config  # noqa: E402
import reaper_model as rm  # noqa: E402


def main():
    snap = read_config()
    cfg = rm.CFG
    bad = 0

    def same(name, a, b):
        nonlocal bad
        ok = a == b
        bad += not ok
        print("%-26s %s" % (name, "identical" if ok else "DIFFERS: model=%r kotlin=%r" % (a, b)))

    same("paylines", [list(map(int, l)) for l in rm.PAYLINES], snap["payline"])
    same("base strips (order)", [list(map(int, s)) for s in rm.strips(cfg.counts)], [snap["strip"][r] for r in range(5)])
    same("free-spin strips (order)", [list(map(int, s)) for s in rm.strips(cfg.fs_counts)], [snap["fsstrip"][r] for r in range(5)])
    same("pays", [list(p) for p in cfg.pays], [snap["pay"][s] for s in range(12)])
    same("mini weights (types 1..4)", list(cfg.mini_weights), snap["mini_weights"][1:])
    same("reaper patterns", [(w, p) for p, w in zip(cfg.reaper_patterns, cfg.reaper_weights)], [(w, p) for w, p in snap["reaper_pattern"]])
    same("raven wilds / weights", (cfg.raven_wilds, cfg.raven_weights), (snap["raven_wilds"], snap["raven_weights"]))
    same("coin counts / weights", (cfg.coin_counts, cfg.coin_count_weights), (snap["coin_counts"], snap["coin_count_weights"]))
    same("coin values / weights", (cfg.coin_values, [float(w) for w in cfg.coin_weights]), (snap["coin_values"], snap["coin_weights"]))
    same("skull", (cfg.skull_mults, cfg.skull_mult_weights, cfg.skull_wilds), (snap["skull_mults"], snap["skull_weights"], snap["skull_wilds"]))
    same("relic count / chance", (cfg.relic_count, cfg.relic_chance), tuple(snap["relic"]))
    same("vault dims", (cfg.vault_cols, cfg.vault_rows, cfg.vault_start, cfg.vault_respins, cfg.vault_orb_chance), tuple(snap["vault_dims"]))
    same("orb values / weights", (cfg.orb_values, cfg.orb_weights), (snap["orb_values"], snap["orb_weights"]))
    same("vault specials", (cfg.p_major, cfg.p_minor, cfg.p_mini, cfg.p_reaper), tuple(snap["vault_special"]))
    same("vault jackpot values", (cfg.v_mini, cfg.v_minor, cfg.v_major, cfg.grand), tuple(snap["vault_values"]))
    same("fs ramp / extra / spins", (cfg.fs_ramp_cap, [cfg.fs_extra[k] for k in (3, 4, 5)], [cfg.fs_spins[k] for k in (3, 4, 5)]),
         (snap["fs_ramp_cap"], snap["fs_extra"][3:], snap["fs_spins"][3:]))
    same("scatter pay", [cfg.scatter_pay[k] for k in (3, 4, 5)], snap["scatter_pay"][3:])
    same("win cap / max seance spins", (rm.CAP_TOKENS, rm.MAX_FS_SPINS), (snap["max_win_tokens"], snap["max_seance_spins"]))
    same("stakes", rm.STAKES, snap["stakes"])
    print("\n%d difference(s)" % bad)
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(main())
