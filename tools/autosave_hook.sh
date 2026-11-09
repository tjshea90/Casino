#!/usr/bin/env bash
# Throttled automatic checkpoint, run by the Claude Code PostToolUse hook in .claude/settings.json.
#
# Goal (user requirement): progress must reach GitHub frequently even if Claude is cut off at any moment or forgets to
# call tools/checkpoint.sh.  At most one auto-checkpoint every AUTOSAVE_MINUTES (default 8), only when something changed,
# never on main/detached HEAD, never ships APKs, never blocks or fails the tool call that triggered it.
# Auto commits are labelled "wip(auto)": they may not compile.  The last VERIFIED-GREEN commit is recorded in the
# LIVE STATUS block at the top of HANDOFF.md.
cd "${CLAUDE_PROJECT_DIR:-$(pwd)}" 2>/dev/null || exit 0
git rev-parse --show-toplevel >/dev/null 2>&1 || exit 0
gitdir="$(git rev-parse --git-dir)"
branch="$(git branch --show-current)"
[ -n "$branch" ] && [ "$branch" != "main" ] || exit 0
stamp="$gitdir/autosave.stamp"
min="${AUTOSAVE_MINUTES:-8}"
now="$(date +%s)"
last="$(cat "$stamp" 2>/dev/null || echo 0)"
[ $((now - last)) -ge $((min * 60)) ] || exit 0
[ -n "$(git status --porcelain 2>/dev/null)" ] || exit 0
echo "$now" > "$stamp"
n="$(git status --porcelain | wc -l | tr -d ' ')"
# run detached so a slow push never delays the tool call; output goes to a log for debugging
( tools/checkpoint.sh "wip(auto): $n changed files" >"$gitdir/autosave.log" 2>&1 & ) >/dev/null 2>&1
exit 0
