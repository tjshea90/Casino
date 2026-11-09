#!/usr/bin/env python3
"""
One command to re-run the whole independent RTP audit of Neon Reaper 3.0 (C + numpy, ~10-15 minutes on one core at --scale 1).

    python3 tools/audit/run_audit.py --out build/audit [--scale 1.0]      # simulate everything, then print the report
    python3 tools/audit/run_audit.py --out build/audit --report-only       # just re-print the report from existing files

Steps (strictly sequential, one process at a time):
  1. gcc -O3 enum_grid.c audit_mc.c
  2. enum_grid base / fs                 exhaustive enumeration of 74^5 and 58^5 reel-stop combinations (exact pmf of (line multiple, coffins))
  3. enum_grid base <wild reels>         exhaustive enumeration for each Reaper's Touch pattern (exact)
  4. audit_mc mini 1..4                  Monte-Carlo of the four mini features (types 1 and 3 are also known exactly: self-test of the MC)
  5. audit_mc seance 3/4/5               THE SEANCE, free spins drawn from the exact free-spin pmf
  6. audit_mc vault                      Soul Vault Monte-Carlo (4x10^8 vaults; a cross-check of step 7)
  7. vault_exact.py                      the EXACT distribution of the vault total (no sampling at all, 15 s)
  8. audit_rtp.py                        combines everything into the per-stake RTP table with confidence intervals
The reel strips, pay table, paylines and feature weights are read from tools/audit/config_snapshot.txt, a dump of the REAL
ReaperConfig.kt (kept current by AuditTest.configSnapshotIsCurrent).
"""
import argparse
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
CFG = os.path.join(HERE, "config_snapshot.txt")


def sh(cmd, out=None):
    t0 = time.time()
    print("  $ " + " ".join(cmd) + ("  > " + out if out else ""), flush=True)
    if out:
        with open(out + ".part", "w") as f:
            subprocess.run(cmd, check=True, stdout=f)
        os.replace(out + ".part", out)
    else:
        subprocess.run(cmd, check=True)
    print("    (%.1f s)" % (time.time() - t0), flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="build/audit")
    ap.add_argument("--scale", type=float, default=1.0, help="multiplies every Monte-Carlo sample count")
    ap.add_argument("--report-only", action="store_true")
    a = ap.parse_args()
    out = os.path.abspath(a.out)
    os.makedirs(out, exist_ok=True)
    if not a.report_only:
        enum_bin, mc_bin = os.path.join(out, "enum_grid"), os.path.join(out, "audit_mc")
        sh(["gcc", "-O3", "-march=native", "-o", enum_bin, os.path.join(HERE, "enum_grid.c")])
        sh(["gcc", "-O3", "-march=native", "-o", mc_bin, os.path.join(HERE, "audit_mc.c"), "-lm"])
        def once(name, cmd):
            f = os.path.join(out, name)
            if os.path.exists(f):
                print("  (have %s)" % name)
            else:
                sh(cmd, f)
        once("base.hist", [enum_bin, CFG, "base"])
        once("fs.hist", [enum_bin, CFG, "fs"])
        patterns = [[1], [2], [3], [1, 3], [1, 2, 3]]          # == ReaperConfig.FEATURE_PATTERNS (checked by audit_rtp.py)
        for i, pat in enumerate(patterns):
            once("reaper_p%d.hist" % i, [enum_bin, CFG, "base"] + [str(r) for r in pat])
        n = lambda x: str(int(x * a.scale))
        for chunk in range(2):
            once("mini1_%d.hist" % chunk, [mc_bin, CFG, "mini", "1", n(5e7), str(100 + chunk)])
            once("mini2_%d.hist" % chunk, [mc_bin, CFG, "mini", "2", n(2e8), str(200 + chunk)])
            once("mini3_%d.hist" % chunk, [mc_bin, CFG, "mini", "3", n(5e7), str(300 + chunk)])
            once("mini4_%d.hist" % chunk, [mc_bin, CFG, "mini", "4", n(2e8), str(400 + chunk)])
        fs = os.path.join(out, "fs.hist")
        for chunk in range(2):
            once("seance3_%d.hist" % chunk, [mc_bin, CFG, "seance", fs, "3", n(5e8), str(500 + chunk)])
            once("seance4_%d.hist" % chunk, [mc_bin, CFG, "seance", fs, "4", n(3e8), str(600 + chunk)])
            once("seance5_%d.hist" % chunk, [mc_bin, CFG, "seance", fs, "5", n(2e8), str(700 + chunk)])
        for chunk in range(4):
            once("vault_%d.hist" % chunk, [mc_bin, CFG, "vault", n(1e8), str(800 + chunk)])
    if not os.path.exists(os.path.join(out, "vault_exact.npy")):
        sh([sys.executable, os.path.join(HERE, "vault_exact.py"), out])      # exact Soul Vault distribution (15 s)
    sh([sys.executable, os.path.join(HERE, "audit_rtp.py"), out])


if __name__ == "__main__":
    main()
