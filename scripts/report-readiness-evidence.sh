#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

local_sha="$(git rev-parse HEAD)"
remote_tracking_ref="$(git symbolic-ref --short refs/remotes/origin/HEAD 2>/dev/null || true)"
remote_branch="${remote_tracking_ref#origin/}"
if test -z "$remote_branch" || test "$remote_branch" = "$remote_tracking_ref"; then
  remote_branch="master"
fi
remote_sha="$(git ls-remote origin "refs/heads/$remote_branch" 2>/dev/null \
  | awk 'NR == 1 { print $1 }' || true)"

echo "local HEAD:  $local_sha"
if test -n "$remote_sha"; then
  echo "origin/$remote_branch: $remote_sha"
else
  echo "origin/$remote_branch: unavailable"
fi

if command -v gh >/dev/null 2>&1; then
  echo "GitHub Actions runs for local HEAD:"
  if ! gh run list --commit "$local_sha" --limit 50 \
      --json databaseId,status,conclusion,event,name,createdAt; then
    echo "unavailable (gh is not authenticated or the repository cannot be queried)"
  fi
else
  echo "GitHub Actions runs: unavailable (gh is not installed)"
fi

echo "Unchecked readiness items:"
rg -n '^[-] \[ \]' docs/1.0-readiness.md || true
