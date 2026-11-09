#!/usr/bin/env bash
# SessionStart hook (also fires after context compaction/resume): prints a compact RESUME BRIEF into the new Claude session
# and prepares the sandbox in the background, so a cold start needs no discovery work.  Must never fail or block.
cd "${CLAUDE_PROJECT_DIR:-$(pwd)}" 2>/dev/null || exit 0
git rev-parse --show-toplevel >/dev/null 2>&1 || exit 0
echo "== CASINO REPO RESUME BRIEF (tools/session_start.sh) =="
echo "branch: $(git branch --show-current)   HEAD: $(git rev-parse --short HEAD)   uncommitted files: $(git status --porcelain | wc -l | tr -d ' ')"
echo "last commits:"; git log --oneline -6 | sed 's/^/  /'
if [ -f HANDOFF.md ]; then
  echo "---- HANDOFF.md LIVE STATUS (top block; read it, then continue at the first unchecked box) ----"
  awk 'NR>1 && /^---$/ {exit} {print}' HANDOFF.md | head -n 70
fi
echo "---- sandbox ----"
if [ -x tools/setup_sandbox.sh ]; then
  if [ ! -f local.properties ] || ! dpkg -s libegl-mesa0 >/dev/null 2>&1 || [ ! -d "$HOME/robolectric-deps" ]; then
    ( nohup tools/setup_sandbox.sh >/tmp/setup_sandbox.log 2>&1 & ) >/dev/null 2>&1
    echo "sandbox setup started in background (tools/setup_sandbox.sh -> /tmp/setup_sandbox.log); wait for '[setup] done' before running Gradle tests"
  else
    echo "sandbox already prepared"
  fi
fi
echo "rules: never 'git commit --amend' (hook-blocked); checkpoint with tools/checkpoint.sh; ship APKs only with SHIP=1 (publishes a GitHub Release); math changes need tools/reaper_model.py + the engine tests"
exit 0
