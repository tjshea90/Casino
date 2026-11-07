# Claude instructions for this repo
- **Always read the "LIVE STATUS" block at the top of `HANDOFF.md` first** — it is the resume point and work queue.
- **Branch:** work on the branch your session was assigned (`git branch --show-current`); never push elsewhere without the
  user's OK. Older sessions used `claude/slot-machine-app-3z9h9s`; the 94 %-RTP task uses `ccr-5cf48990-4m3710`.
- **Checkpoint constantly** (sessions get cut off): `tools/checkpoint.sh "msg"` after every meaningful step, and keep the
  HANDOFF LIVE STATUS block true. Details of the checkpoint ladder are in HANDOFF.md.
- Keep the APK small and fast (Android 10 Go / MT8168): framework APIs only, no AndroidX.
- Never change pay tables / reel strips without re-running the RTP tests. Both games target **94.00 % RTP**:
  Dragon's Hoard `RtpTest` (exact, ±0.02 %) and Neon Reaper `RtpExactTest` + `CalibrationTest` + `FullGameTest`
  (94 % at every stake). Python references: `tools/dragon_model.py`, `tools/reaper_model.py`.
- To ship: copy release APKs into `release/` and bump versionName/versionCode, then `SHIP=1 tools/checkpoint.sh "release vX"`.
  **Pushing `release/*.apk` on any branch publishes a public GitHub Release** (release.yml) — do it deliberately.
- **Release trap (happened once):** `release.yml` fires on any push whose diff against the branch's PREVIOUS remote tip touches
  `release/*.apk` — including the first push after merging another branch that already contains the APKs. That published a
  stray "Casino build 5" (unchanged APKs). Before a first push of a branch, check `git diff --stat origin/<branch>..HEAD -- release/`.
- **Math changes are not done until adversarially checked:** an independent audit found a staking exploit (101–104 % RTP) that
  the author's own tests missed. When touching bonus/relic/stake logic run `python3 tools/reaper_model.py exploit` and
  `NoExploitTest`, and have a separate agent try to beat the house with adaptive policies.

