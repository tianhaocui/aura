#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
test -f "$repo_root/examples/aura-composed/src/main/resources/manifest.json"
test -f "$repo_root/examples/aura-composed/src/test/java/example/ManifestDriftTest.java"
command -v mvn >/dev/null || {
  echo "Aura manifest verification requires mvn" >&2
  exit 1
}

# The gate verifies the committed declaration; update mode would rewrite it instead of checking
# it, so a leaked AURA_MANIFEST_UPDATE must fail the run rather than silently bless the drift.
if test "${AURA_MANIFEST_UPDATE:-false}" = true; then
  echo "verify-manifest.sh verifies the committed manifest; unset AURA_MANIFEST_UPDATE" >&2
  exit 1
fi

# Same proof-of-execution doctrine as verify-ai-evals.sh: the committed JSON existing proves
# nothing. Only a surefire report written by this invocation proves the drift test reconciled the
# composition against it, so stale reports are deleted before the run and required after it.
required=(example.ManifestDriftTest)
report_glob='*/target/surefire-reports'
for fqcn in "${required[@]}"; do
  find "$repo_root" -type f -path "$report_glob/TEST-$fqcn.xml" -delete
done

mvn -q -f "$repo_root/pom.xml" \
  -Dtest="$(IFS=,; echo "${required[*]}")" \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test

for fqcn in "${required[@]}"; do
  report="$(find "$repo_root" -type f -path "$report_glob/TEST-$fqcn.xml" -print -quit)"
  if test -z "$report"; then
    echo "Manifest drift was not checked: no surefire report for $fqcn" >&2
    exit 1
  fi
  suite_line="$(grep -m1 '<testsuite ' "$report")"
  if ! grep -Eq ' tests="[1-9][0-9]*"' <<<"$suite_line" ||
     ! grep -Eq ' skipped="0"' <<<"$suite_line"; then
    echo "Manifest drift test was not executed: $fqcn reported $suite_line" >&2
    exit 1
  fi
done

echo "Aura application manifest passed: committed declaration reconciled against the composition and the MCP projection, proven by a report written by this run"