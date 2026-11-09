"""
Helpers of the independent Neon Reaper audit (numpy only; does NOT import tools/reaper_model.py or the Kotlin ExactMath).

* read_config()      parses tools/audit/config_snapshot.txt (a dump of the real ReaperConfig.kt)
* read_hist_xk(...)  histogram files written by enum_grid / audit_mc  ("total N" or "N n", then "y k count" lines)
* Dist               an integer-valued payout distribution (multiples of the stake) with exact/empirical moments and the
                     capped mean E[min(Y, c)] that the 25-book win cap needs (cap c = MAX_WIN_TOKENS / stake)
"""
import os
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
SNAPSHOT = os.path.join(HERE, "config_snapshot.txt")


def read_config(path=SNAPSHOT):
    cfg = {"pay": {}, "payline": [], "strip": {}, "fsstrip": {}, "reaper_pattern": []}
    for ln in open(path):
        if not ln.strip() or ln.startswith("#"):
            continue
        key, *vals = ln.split()
        if key == "pay":
            cfg["pay"][int(vals[0])] = [int(v) for v in vals[1:]]
        elif key == "payline":
            cfg["payline"].append([int(v) for v in vals])
        elif key in ("strip", "fsstrip"):
            cfg[key][int(vals[0])] = [int(v) for v in vals[1:]]
        elif key == "reaper_pattern":
            cfg["reaper_pattern"].append((float(vals[0]), [int(v) for v in vals[1:]]))
        else:
            nums = [float(v) if ("." in v or "e" in v.lower()) else int(v) for v in vals]
            cfg[key] = nums if len(nums) != 1 else nums[0]
    return cfg


def read_hist_xk(path):
    """-> (N, {(x, k): count}) for 'total N' / 'N n' headers followed by 'x k count' lines."""
    n = None
    cells = {}
    for ln in open(path):
        p = ln.split()
        if p[0] in ("total", "N"):
            n = int(p[1])
        elif p[0] == "SPINS":
            continue
        elif len(p) == 3:
            cells[(int(p[0]), int(p[1]))] = int(p[2])
    assert n is not None and sum(cells.values()) == n, (path, n, sum(cells.values()))
    return n, cells


def read_hist_y(path):
    """-> (N, {y: count}, mean spins or None) for 'N n' (+ optional 'SPINS s') followed by 'y count' lines."""
    n, spins = None, None
    cells = {}
    for ln in open(path):
        p = ln.split()
        if p[0] == "N":
            n = int(p[1])
        elif p[0] == "SPINS":
            spins = float(p[1])
        else:
            cells[int(p[0])] = int(p[1])
    assert n is not None and sum(cells.values()) == n, (path, n, sum(cells.values()))
    return n, cells, spins


class Dist:
    """Distribution of an integer payout Y (x stake). exact=True: counts are exhaustive (no sampling error)."""

    def __init__(self, ys, counts, exact=False):
        order = np.argsort(ys)
        self.y = np.asarray(ys, dtype=np.float64)[order]
        self.c = np.asarray(counts, dtype=np.float64)[order]
        self.n = float(self.c.sum())
        self.p = self.c / self.n
        self.exact = exact

    @staticmethod
    def from_xk(cells, fn, exact=False):
        """Distribution of fn(x, k) over a (x, k) histogram."""
        acc = {}
        for (x, k), c in cells.items():
            v = fn(x, k)
            acc[v] = acc.get(v, 0) + c
        ys = sorted(acc)
        return Dist(ys, [acc[v] for v in ys], exact)

    @staticmethod
    def from_y(cells, exact=False):
        ys = sorted(cells)
        return Dist(ys, [cells[v] for v in ys], exact)

    def mean(self):
        return float((self.p * self.y).sum())

    def var(self):
        m = self.mean()
        return float((self.p * self.y * self.y).sum() - m * m)

    def capped(self, cap):
        """(E[min(Y, cap)], standard error of that mean estimate)."""
        z = np.minimum(self.y, cap)
        m = float((self.p * z).sum())
        if self.exact:
            return m, 0.0
        v = float((self.p * z * z).sum() - m * m)
        return m, (max(v, 0.0) / self.n) ** 0.5

    def prob_gt(self, t):
        return float(self.p[self.y > t].sum())
