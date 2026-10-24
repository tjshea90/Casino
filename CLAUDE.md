# Claude instructions for this repo
- **Always read `HANDOFF.md` first** — it is the resume point and work queue.
- Commit + push to `claude/slot-machine-app-3z9h9s` after every meaningful step and update HANDOFF.md.
- Keep the APK small and fast (Android 10 Go / MT8168): framework APIs only, no AndroidX.
- Never change pay tables / reel strips without re-running the RTP unit test (must stay 80 % ± 0.5 %).
