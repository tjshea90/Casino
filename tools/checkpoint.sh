#!/usr/bin/env bash
# Checkpoint ladder, rung 1: commit everything and push the CURRENT branch, with retries.
#
#   tools/checkpoint.sh "what just got done"          # WIP checkpoint (cheap, run it often)
#   SHIP=1 tools/checkpoint.sh "release v2.1.0"       # also stage release/*.apk (publishes a GitHub Release!)
#
# Why: Claude sessions can be cut off at any moment (usage limit, crash, timeout).
# Anything not pushed is lost.  Run this after every meaningful step, then keep the
# "LIVE STATUS" block at the top of HANDOFF.md current so a cold session can resume.
#
# Safety: release/*.apk are NOT staged unless SHIP=1, because .github/workflows/release.yml
# publishes a public GitHub Release whenever those files change on ANY branch.
set -uo pipefail
cd "$(git rev-parse --show-toplevel)"

msg="${1:-wip checkpoint}"
branch="$(git branch --show-current)"
if [ -z "$branch" ]; then echo "checkpoint: detached HEAD, refusing"; exit 1; fi
if [ "$branch" = "main" ]; then echo "checkpoint: refusing to push straight to main"; exit 1; fi

git add -A
if [ "${SHIP:-0}" != "1" ]; then
  # keep APKs out of ordinary checkpoints
  git reset -q -- 'release/*.apk' 'release/*.zip' 2>/dev/null || true
fi

if git diff --cached --quiet; then
  echo "checkpoint: nothing new to commit"
else
  trailer="${CHECKPOINT_TRAILER:-}"
  if [ -n "$trailer" ]; then
    git commit -q -m "$msg" -m "$trailer" || exit 1
  else
    git commit -q -m "$msg" || exit 1
  fi
  echo "checkpoint: committed '$msg'"
fi

for delay in 0 2 4 8 16; do
  sleep "$delay"
  if git push -q -u origin "$branch"; then
    echo "checkpoint: pushed $branch @ $(git rev-parse --short HEAD)"
    exit 0
  fi
  echo "checkpoint: push failed, retrying..."
done
echo "checkpoint: PUSH FAILED after retries (commit is saved locally on $branch)"
exit 1
