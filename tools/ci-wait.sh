#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Waits for the GitHub Actions jobs of a commit and prints what failed.
#
#   tools/ci-wait.sh            the jobs of HEAD (push it first)
#   tools/ci-wait.sh <sha>      the jobs of another commit
#
# Needs the GitHub CLI (gh), signed in with read access to the repository. Job logs are saved to
# ${CI_LOG_DIR:-/tmp/ci}/<job>.log. Exit code: 0 when every job succeeded, 1 otherwise, 2 when no
# job finished within the waiting time.
set -u
repo=$(git remote get-url origin | sed -E 's#(\.git)?$##; s#^.*[:/]([^/]+/[^/]+)$#\1#')
sha=${1:-$(git rev-parse HEAD)}
out=${CI_LOG_DIR:-/tmp/ci}
mkdir -p "$out"

runs=""
for _ in $(seq 1 "${CI_WAIT_ROUNDS:-70}"); do
  sleep 15
  runs=$(gh api "repos/$repo/commits/$sha/check-runs" \
    --jq '[.check_runs[] | .name + ":" + .status + ":" + (.conclusion // "-") + ":" + (.id|tostring)] | join(" ")' 2>/dev/null | tail -1)
  case "$runs" in
    "" | *:queued:* | *:in_progress:* | *:pending:* | *:waiting:*) ;;
    *) break ;;
  esac
done
case "$runs" in
  "" | *:queued:* | *:in_progress:* | *:pending:* | *:waiting:*)
    echo "CI: not finished: ${runs:-no jobs found for $sha}"
    exit 2
    ;;
esac

status=0
for run in $runs; do
  name=${run%%:*}
  id=${run##*:}
  conclusion=$(echo "$run" | cut -d: -f3)
  gh api "repos/$repo/actions/jobs/$id/logs" > "$out/$name.log" 2>/dev/null
  echo "CI: $name: $conclusion"
  if [ "$conclusion" != "success" ]; then
    status=1
    # Compiler errors, Gradle's summary, failed tests (the emulator job prints them from its result files).
    grep -E "e: file|error:|What went wrong|^.{29}> .*|FAILED|<failure|AssertionError|Exception|SPECTRA" "$out/$name.log" \
      | grep -v -E "sh -c|script:|--stacktrace" | cut -c30-400 | head -n "${CI_FAIL_LINES:-40}"
  else
    grep -E "SPECTRA" "$out/$name.log" | grep -v -E "sh -c|script:|grep" | cut -c30-300 | head -n 10
  fi
done
exit $status
