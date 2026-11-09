#!/usr/bin/env python3
"""Neon Reaper audio QA: analyse the rendered WAV set and draw spectrograms.

Usage
-----
    # 1. render the assets (plain JUnit, ~10 s):
    gradle :reaper:testDebugUnitTest --tests 'com.tjshea.neonreaper.audio.AudioQaTest'   # writes reaper/build/audio/*.wav
    # 2. analyse:
    python3 tools/audio_report.py [WAV_DIR] [--out DIR] [--md FILE] [--no-png] [--only SUBSTRING]

Needs numpy (and matplotlib for the PNGs).  Output:
    * a markdown table (stdout, and --md FILE): duration, peak, RMS, crest, DC, first/last sample, spectral centroid,
      band energy split, max 200 ms loudness, clipped samples; loop-seam numbers for music_*.wav layers
    * spectrogram contact sheets in --out (default reaper/build/audio_report): sfx_<group>.png, music_<track>.png,
      escalation.png (win and jackpot stingers: loudness over time)
Open the PNGs to check structure: risers rise, drum patterns are periodic, wins escalate, nothing harsh above ~8 kHz.
"""
import argparse
import math
import os
import sys
import wave

import numpy as np

SR_EXPECTED = 22050

GROUPS = [
    ("ui", ["CLICK", "PRESS", "UI_OPEN", "UI_CLOSE", "STAKE_CHANGE", "TICK", "WIN_TICK", "COUNT_END", "ERROR", "SPIN", "SPIN_START", "STOP", "REEL_THUD"]),
    ("wins", ["WIN_SMALL", "WIN", "WIN_MED", "WIN_BIG", "BIG_WIN", "WIN_MEGA", "WIN_EPIC", "WIN_APOCALYPSE", "FANFARE", "COINS", "COIN_SHOWER"]),
    ("jackpots", ["JACKPOT", "JACKPOT_MINI", "JACKPOT_MINOR", "JACKPOT_MAJOR", "JACKPOT_GRAND", "BUY_CONFIRM", "RANK_UP", "ACHIEVEMENT", "REALITY_CHECK", "RELIC", "RELIC_FLY", "BELL"]),
    ("seance", ["SCATTER_LAND", "COFFIN_OPEN", "SEANCE_START", "SEANCE_MULT_UP", "SEANCE_RETRIGGER", "SEANCE_END", "ANTICIPATE", "ANTICIPATE_HIT", "ANTICIPATE_MISS", "VAULT", "VAULT_DOOR", "ORB", "ORB_LAND"]),
    ("voices", ["REAPER_WHISPER", "REAPER_LAUGH", "LAUGH", "CACKLE", "GIGGLE", "DOCTOR_RASP", "DEMON_ROAR", "GROWL", "WRAITH_WAIL", "CAW", "RAVEN_FLOCK", "SKULL_RATTLE", "CHATTER"]),
    ("world", ["THUNDER", "LIGHTNING_CRACK", "DOOR_SLAM", "WIND_GUST", "CANDLE_WHOOSH", "SLASH", "WHOOSH", "BONE_CRACK", "CHAIN_RATTLE", "HEARTBEAT", "WILD_LAND", "MINI_INTRO"]),
]

BANDS = [("<150", 0, 150), ("150-800", 150, 800), ("0.8-3k", 800, 3000), ("3-8k", 3000, 8000), (">8k", 8000, 1e9)]


def read_wav(path):
    with wave.open(path, "rb") as w:
        ch, sw, sr, n = w.getnchannels(), w.getsampwidth(), w.getframerate(), w.getnframes()
        raw = w.readframes(n)
    if sw != 2:
        raise ValueError(f"{path}: expected 16-bit PCM")
    a = np.frombuffer(raw, dtype="<i2").astype(np.float64) / 32768.0
    return a.reshape(-1, ch), sr


def db(x):
    return 20 * math.log10(max(x, 1e-9))


def stft_mag(x, n_fft, hop):
    if len(x) < n_fft:
        x = np.pad(x, (0, n_fft - len(x)))
    win = np.hanning(n_fft)
    frames = 1 + (len(x) - n_fft) // hop
    idx = np.arange(n_fft)[None, :] + hop * np.arange(frames)[:, None]
    return np.abs(np.fft.rfft(x[idx] * win, axis=1)).T  # (bins, frames)


def analyse(path, is_music):
    a, sr = read_wav(path)
    mono = a.mean(axis=1)
    n = len(mono)
    dur = n / sr
    peak = float(np.abs(a).max())
    rms = float(np.sqrt((a ** 2).mean()))
    dc = float(a.mean())
    spec = np.abs(np.fft.rfft(mono * np.hanning(n))) ** 2
    freqs = np.fft.rfftfreq(n, 1 / sr)
    tot = spec.sum() + 1e-18
    centroid = float((spec * freqs).sum() / tot)
    band = [float(spec[(freqs >= lo) & (freqs < hi)].sum() / tot * 100) for _, lo, hi in BANDS]
    w = max(1, int(0.2 * sr))
    if n > w:
        cs = np.concatenate([[0], np.cumsum(mono ** 2)])
        wr = np.sqrt((cs[w:] - cs[:-w]) / w)
        loud = db(float(wr.max()))
    else:
        loud = db(rms)
    clipped = int((np.abs(a) >= 32760 / 32768.0).sum())
    r = dict(name=os.path.splitext(os.path.basename(path))[0], dur=dur, ch=a.shape[1], sr=sr, peak=db(peak), rms=db(rms),
             crest=db(peak) - db(rms), dc=dc, first=float(np.abs(a[0]).max()), last=float(np.abs(a[-1]).max()),
             centroid=centroid, band=band, loud=loud, clipped=clipped, mono=mono)
    if is_music:
        step = float(np.abs(a[0] - a[-1]).max())
        k = int(0.05 * sr)
        local = np.abs(np.diff(a[:k], axis=0)).max() if n > k else 1e-9
        local = max(local, np.abs(np.diff(a[-k:], axis=0)).max())
        w2 = int(0.25 * sr)
        before = np.sqrt((a[-w2:] ** 2).mean())
        after = np.sqrt((a[:w2] ** 2).mean())
        w3 = int(6.0 * sr)            # ~2 bars: rhythmic layers are compared over a phrase, not a single pulse
        r["seam_step"] = step
        r["seam_local"] = float(local)
        r["seam_rms_db"] = db(float(after)) - db(float(before))
        r["seam_rms6_db"] = db(float(np.sqrt((a[:w3] ** 2).mean()))) - db(float(np.sqrt((a[-w3:] ** 2).mean())))
    return r


def md_table(rows, music):
    out = []
    out.append("| file | sec | ch | peak dB | RMS dB | crest | loud200 dB | DC | first | last | centroid Hz | " + " | ".join(b[0] + " %" for b in BANDS) + " | clip |")
    out.append("|---|---|---|---|---|---|---|---|---|---|---|" + "---|" * len(BANDS) + "---|")
    for r in rows:
        out.append("| %s | %.2f | %d | %.1f | %.1f | %.1f | %.1f | %.4f | %.4f | %.4f | %.0f | %s | %d |" % (
            r["name"], r["dur"], r["ch"], r["peak"], r["rms"], r["crest"], r["loud"], r["dc"], r["first"], r["last"], r["centroid"],
            " | ".join("%.1f" % v for v in r["band"]), r["clipped"]))
    if music:
        out.append("")
        out.append("| loop layer | sec | seam step | local max step | step/local | RMS after/before 250 ms (dB) | 6 s (dB) |")
        out.append("|---|---|---|---|---|---|---|")
        for r in music:
            out.append("| %s | %.2f | %.5f | %.5f | %.2f | %+.2f | %+.2f |" % (r["name"], r["dur"], r["seam_step"], r["seam_local"],
                                                                             r["seam_step"] / max(r["seam_local"], 1e-9), r["seam_rms_db"], r["seam_rms6_db"]))
    return "\n".join(out)


def sheet(rows, title, path, plt, cols=2, ymax=11025.0):
    if not rows:
        return
    n = len(rows)
    rws = math.ceil(n / cols)
    fig, axes = plt.subplots(rws, cols, figsize=(7.2 * cols, 2.35 * rws), squeeze=False)
    for ax in axes.ravel():
        ax.axis("off")
    for ax, r in zip(axes.ravel(), rows):
        ax.axis("on")
        x = r["mono"]
        dur = r["dur"]
        n_fft = 512 if dur < 0.4 else 1024 if dur < 2 else 2048
        hop = max(16, n_fft // 4)
        m = stft_mag(x, n_fft, hop)
        db_m = 20 * np.log10(m + 1e-7)
        vmax = db_m.max()
        ax.imshow(db_m, origin="lower", aspect="auto", cmap="magma", vmin=vmax - 80, vmax=vmax,
                  extent=[0, dur, 0, SR_EXPECTED / 2 / 1000])
        ax.set_ylim(0, ymax / 1000)
        ax.set_title("%s  %.2fs  rms %.1f  pk %.1f" % (r["name"], dur, r["rms"], r["peak"]), fontsize=9, loc="left")
        ax.tick_params(labelsize=7)
        ax.set_ylabel("kHz", fontsize=7)
    fig.suptitle(title, fontsize=11)
    fig.tight_layout(rect=(0, 0, 1, 0.985))
    fig.savefig(path, dpi=80)
    plt.close(fig)


def env_db(x, sr, win=0.05):
    w = max(1, int(win * sr))
    n = len(x) // w
    if n == 0:
        return np.array([db(float(np.sqrt((x ** 2).mean())))]), win
    e = np.sqrt((x[: n * w].reshape(n, w) ** 2).mean(axis=1))
    return 20 * np.log10(e + 1e-9), win


def escalation(rows_by_name, path, plt):
    names = ["WIN_SMALL", "WIN", "WIN_MED", "WIN_BIG", "WIN_MEGA", "WIN_EPIC", "WIN_APOCALYPSE"]
    names = [n for n in names if n in rows_by_name]
    if not names:
        return
    fig, ax = plt.subplots(2, 1, figsize=(12, 7))
    cmap = plt.get_cmap("viridis")
    for i, nme in enumerate(names):
        r = rows_by_name[nme]
        e, w = env_db(r["mono"], SR_EXPECTED)
        t = np.arange(len(e)) * w
        ax[0].plot(t, e, color=cmap(i / max(1, len(names) - 1)), label="%s %.1fs" % (nme, r["dur"]))
    ax[0].set_ylim(-60, 0)
    ax[0].set_ylabel("RMS dBFS (50 ms)")
    ax[0].legend(fontsize=8, ncol=2)
    ax[0].set_title("Win stinger escalation (loudness envelope)")
    ax[0].grid(alpha=0.3)
    j_names = [n for n in ["JACKPOT_MINI", "JACKPOT_MINOR", "JACKPOT_MAJOR", "JACKPOT_GRAND"] if n in rows_by_name]
    for i, nme in enumerate(j_names):
        r = rows_by_name[nme]
        e, w = env_db(r["mono"], SR_EXPECTED)
        t = np.arange(len(e)) * w
        ax[1].plot(t, e, color=cmap(i / max(1, len(j_names) - 1)), label="%s %.1fs" % (nme, r["dur"]))
    ax[1].set_ylim(-60, 0)
    ax[1].set_xlabel("seconds")
    ax[1].set_ylabel("RMS dBFS (50 ms)")
    ax[1].legend(fontsize=8)
    ax[1].set_title("Jackpot escalation")
    ax[1].grid(alpha=0.3)
    fig.tight_layout()
    fig.savefig(path, dpi=80)
    plt.close(fig)


def music_sheets(music_rows, outdir, plt):
    tracks = {}
    for r in music_rows:
        parts = r["name"].split("_")  # music_BASE_L0
        tracks.setdefault(parts[1], []).append(r)
    for tr, rows in tracks.items():
        rows.sort(key=lambda r: r["name"])
        fig, axes = plt.subplots(len(rows), 2, figsize=(16, 2.6 * len(rows)), squeeze=False,
                                 gridspec_kw={"width_ratios": [4, 1.3]})
        for (a1, a2), r in zip(axes, rows):
            x = r["mono"]
            dur = r["dur"]
            m = stft_mag(x, 4096, 2048)
            db_m = 20 * np.log10(m + 1e-7)
            a1.imshow(db_m, origin="lower", aspect="auto", cmap="magma", vmin=db_m.max() - 90, vmax=db_m.max(),
                      extent=[0, dur, 0, SR_EXPECTED / 2 / 1000])
            a1.set_ylim(0, 8)
            a1.set_title("%s  %.1fs  rms %.1f  pk %.1f  centroid %.0f Hz" % (r["name"], dur, r["rms"], r["peak"], r["centroid"]), fontsize=9, loc="left")
            a1.set_ylabel("kHz", fontsize=8)
            e, w = env_db(x, SR_EXPECTED, 0.25)
            a2.plot(np.arange(len(e)) * w, e, lw=0.8)
            a2.set_ylim(-70, -5)
            a2.grid(alpha=0.3)
            a2.set_title("RMS (250 ms)", fontsize=8)
        fig.tight_layout()
        fig.savefig(os.path.join(outdir, "music_%s.png" % tr), dpi=70)
        plt.close(fig)


def pitch_peaks(x, sr, lo=30, hi=1200, k=6):
    """Strongest spectral peaks of a (mono) signal: handy to confirm the tonal centre (D minor)."""
    n = len(x)
    spec = np.abs(np.fft.rfft(x * np.hanning(n)))
    f = np.fft.rfftfreq(n, 1 / sr)
    sel = (f >= lo) & (f <= hi)
    ff, ss = f[sel], spec[sel]
    peaks = []
    for _ in range(k):
        i = int(ss.argmax())
        peaks.append((float(ff[i]), 20 * math.log10(max(ss[i], 1e-12) / (spec.max() + 1e-12))))
        ss[max(0, i - 40): i + 40] = 0
    return peaks


NOTE = ["C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B"]


def note_name(f):
    m = 69 + 12 * math.log2(f / 440.0)
    r = int(round(m))
    return "%s%d%+.0fc" % (NOTE[r % 12], r // 12 - 1, (m - r) * 100)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("wav_dir", nargs="?", default="reaper/build/audio")
    ap.add_argument("--out", default="reaper/build/audio_report")
    ap.add_argument("--md", default=None, help="write the markdown report to this file as well")
    ap.add_argument("--no-png", action="store_true")
    ap.add_argument("--only", default=None, help="only files whose name contains this text")
    ap.add_argument("--pitch", action="store_true", help="print the strongest spectral peaks (note names) of every file")
    args = ap.parse_args()

    files = sorted(f for f in os.listdir(args.wav_dir) if f.lower().endswith(".wav"))
    if args.only:
        files = [f for f in files if args.only in f]
    if not files:
        print("no wav files in", args.wav_dir)
        return 1
    sfx, music = [], []
    for f in files:
        is_music = f.startswith("music_")
        r = analyse(os.path.join(args.wav_dir, f), is_music)
        (music if is_music else sfx).append(r)
    os.makedirs(args.out, exist_ok=True)

    text = ["# Audio report", "", "## One-shots", "", md_table(sfx, None), ""]
    if music:
        text += ["## Music loops", "", md_table(music, music), ""]
    report = "\n".join(text)
    print(report)
    if args.md:
        with open(args.md, "w") as fh:
            fh.write(report)

    if args.pitch:
        for r in sfx + music:
            pk = pitch_peaks(r["mono"], SR_EXPECTED)
            print("%-22s %s" % (r["name"], "  ".join("%s(%.0fdB)" % (note_name(f), d) for f, d in pk)))

    if not args.no_png:
        try:
            import matplotlib
            matplotlib.use("Agg")
            import matplotlib.pyplot as plt
        except ImportError:
            print("matplotlib missing: skipping PNGs (pip install matplotlib)", file=sys.stderr)
            return 0
        by = {r["name"]: r for r in sfx}
        used = set()
        for g, names in GROUPS:
            rows = [by[n] for n in names if n in by]
            used.update(r["name"] for r in rows)
            sheet(rows, "SFX: " + g, os.path.join(args.out, "sfx_%s.png" % g), plt)
        rest = [r for r in sfx if r["name"] not in used]
        sheet(rest, "SFX: other", os.path.join(args.out, "sfx_other.png"), plt)
        escalation(by, os.path.join(args.out, "escalation.png"), plt)
        if music:
            music_sheets(music, args.out, plt)
        print("\nPNGs written to", args.out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
