#!/usr/bin/env bash
# Checkpoint ladder, rung 1: commit everything and push the CURRENT branch, with retries.
#
#   tools/checkpoint.sh "what just got done"          # WIP checkpoint (cheap, run it often)
#   SHIP=1 tools/checkpoint.sh "release v3.0.0"       # also stage release/*.apk (publishes a GitHub Release!)
#
# Why: Claude sessions can be cut off at any moment (usage limit, crash, timeout).
# Anything not pushed is lost.  Run this after every meaningful step, then keep the
# "LIVE STATUS" block at the top of HANDOFF.md current so a cold session can resume.
# (tools/autosave_hook.sh also runs this automatically, throttled, from a Claude Code hook.)
#
# Safety 1: release/*.apk are NOT staged unless SHIP=1.
# Safety 2 (release trap): .github/workflows/release.yml publishes a public GitHub Release whenever a push changes
#   release/*.apk (or release.yml) relative to the branch's previous remote tip -- also the FIRST push after a merge that
#   brings those files in.  If this push would do that without SHIP=1, the commit message gets " [skip ci]" appended
#   (GitHub then runs no workflow for the push).  Never use `git commit --amend` (a repo hook blocks it): make a new commit.
set -uo pipefail
cd "$(git rev-parse --show-toplevel)"

msg="${1:-wip checkpoint}"
branch="$(git branch --show-current)"
if [ -z "$branch" ]; then echo "checkpoint: detached HEAD, refusing"; exit 1; fi
if [ "$branch" = "main" ]; then echo "checkpoint: refusing to push straight to main"; exit 1; fi

# another git command (or the autosave hook) may hold the index for a moment
for _ in 1 2 3 4 5 6; do [ -e "$(git rev-parse --git-dir)/index.lock" ] || break; sleep 1; done

git add -A
if [ "${SHIP:-0}" != "1" ]; then
  # keep APKs out of ordinary checkpoints
  git reset -q -- 'release/*.apk' 'release/*.zip' 2>/dev/null || true
fi

# ---- release-trap guard
RELEASE_PATHS=('release/*.apk' '.github/workflows/release.yml')
base="$(git rev-parse --verify -q "origin/$branch" 2>/dev/null || git rev-parse --verify -q origin/main 2>/dev/null || true)"
trap_hit=0
if [ "${SHIP:-0}" != "1" ] && [ -n "$base" ]; then
  if [ -n "$(git diff --name-only "$base" HEAD -- "${RELEASE_PATHS[@]}"; git diff --cached --name-only -- "${RELEASE_PATHS[@]}")" ]; then
    trap_hit=1
  fi
fi
if [ "$trap_hit" = "1" ] && ! git log -1 --format=%B | grep -q '\[skip ci\]' && git diff --cached --quiet; then
  if [ "${ALLOW_RELEASE_TRAP:-0}" != "1" ]; then
    echo "checkpoint: REFUSING to push -- this push would change release/*.apk vs origin and publish a stray public release."
    echo "            Make any small real change (e.g. a HANDOFF.md status line) and re-run: the new commit is tagged [skip ci]."
    echo "            (or SHIP=1 if publishing is intended, or ALLOW_RELEASE_TRAP=1 to push anyway)"
    exit 2
  fi
fi

if git diff --cached --quiet; then
  echo "checkpoint: nothing new to commit"
else
  [ "$trap_hit" = "1" ] && msg="$msg [skip ci]"
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
