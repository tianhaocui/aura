#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
test -f "$repo_root/evals/tasks.json"
test -f "$repo_root/evals/result.schema.json"
for command_name in jq mvn; do
  command -v "$command_name" >/dev/null || {
    echo "Aura AI evaluation verification requires $command_name" >&2
    exit 1
  }
done

# A golden task is graded only by a test that ran in this invocation. The manifest is the single
# list, read here in fully qualified form so the same names drive the surefire selection and the
# report check; there is no module list to keep in sync, because the whole reactor is built.
# Selecting by name and tolerating no match used to hide a deleted golden test behind a green gate,
# so absence of a report is what fails the run now. Stale reports are deleted first: target/ is warm
# from earlier steps, and evidence that predates this run would otherwise prove nothing.
# The results consumer is in the required set alongside the goldens: enabling it here and then
# requiring a report it executed into is what stops anyone from deleting the measurement and
# keeping the gate green.
required=(example.AiEvaluationManifestTest example.AiEvaluationResultsTest)
while IFS= read -r fqcn; do
  required+=("$fqcn")
done < <(jq -r '.tasks[].verificationTests[]' "$repo_root/evals/tasks.json" | sort -u)
test "${#required[@]}" -gt 1

# Surefire 3.2.5 is not reconfigured anywhere in the reactor, so every module writes its reports
# here, with one <testsuite> element whose attributes are all on a single line.
report_glob='*/target/surefire-reports'
for fqcn in "${required[@]}"; do
  find "$repo_root" -type f -path "$report_glob/TEST-$fqcn.xml" -delete
done

mvn -q -f "$repo_root/pom.xml" \
  -Dtest="$(IFS=,; echo "${required[*]}")" \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Daura.eval.results=true test

for fqcn in "${required[@]}"; do
  report="$(find "$repo_root" -type f -path "$report_glob/TEST-$fqcn.xml" -print -quit)"
  if test -z "$report"; then
    echo "Golden evaluation task was not graded: no surefire report for $fqcn" >&2
    exit 1
  fi
  suite_line="$(grep -m1 '<testsuite ' "$report")"
  # tests counts skipped tests too, so a class disabled as a whole still reports tests="1".
  if ! grep -Eq ' tests="[1-9][0-9]*"' <<<"$suite_line" ||
     ! grep -Eq ' skipped="0"' <<<"$suite_line"; then
    echo "Golden evaluation task was not executed: $fqcn reported $suite_line" >&2
    exit 1
  fi
done

echo "Aura AI golden evaluation tasks passed: $((${#required[@]} - 2)) golden classes, the manifest guard, and the results consumer, each proven by a report written by this run"
