#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
manifest="$repo_root/evals/tasks.json"
schema="$repo_root/evals/result.schema.json"
test -f "$manifest"
test -f "$schema"
command -v jq >/dev/null || { echo "Recording an evaluation requires jq" >&2; exit 1; }

usage() {
  echo "usage: $0 --task-id ID --run-id ID --model NAME --variant baseline|treatment --context-id ID" \
    "--input-tokens N --output-tokens N --first-pass BOOL --final-success BOOL --repair-rounds N" \
    "--error-codes a,b|none --duration-ms N [--commit SHA] [--output FILE]" >&2
}

task_id="" run_id="" model="" variant="" context_id=""
input_tokens="" output_tokens="" first_pass="" final_success="" repair_rounds=""
error_codes="" duration_ms="" commit="" output="$repo_root/evals/results/results.jsonl"

while test "$#" -gt 0; do
  case "$1" in
    --task-id) task_id="${2:-}"; shift 2 ;;
    --run-id) run_id="${2:-}"; shift 2 ;;
    --model) model="${2:-}"; shift 2 ;;
    --variant) variant="${2:-}"; shift 2 ;;
    --context-id) context_id="${2:-}"; shift 2 ;;
    --input-tokens) input_tokens="${2:-}"; shift 2 ;;
    --output-tokens) output_tokens="${2:-}"; shift 2 ;;
    --first-pass) first_pass="${2:-}"; shift 2 ;;
    --final-success) final_success="${2:-}"; shift 2 ;;
    --repair-rounds) repair_rounds="${2:-}"; shift 2 ;;
    --error-codes) error_codes="${2:-}"; shift 2 ;;
    --duration-ms) duration_ms="${2:-}"; shift 2 ;;
    --commit) commit="${2:-}"; shift 2 ;;
    --output) output="${2:-}"; shift 2 ;;
    *) usage; exit 2 ;;
  esac
done

for name in task_id run_id model variant context_id input_tokens output_tokens \
            first_pass final_success repair_rounds error_codes duration_ms; do
  value="${!name}"
  test -n "$value" || { usage; echo "missing --${name//_/-}" >&2; exit 2; }
done

if test -z "$commit"; then
  command -v git >/dev/null || { echo "Deriving the commit requires git, or pass --commit" >&2; exit 1; }
  commit="$(git -C "$repo_root" rev-parse HEAD)"
fi

for value in "$task_id" "$run_id" "$model" "$context_id" "$commit"; do
  if [[ ! "$value" =~ ^[A-Za-z0-9._:/-]+$ ]]; then
    echo "identifier contains unsupported characters: $value" >&2
    exit 2
  fi
done
for value in "$input_tokens" "$output_tokens" "$repair_rounds" "$duration_ms"; do
  if [[ ! "$value" =~ ^[0-9]+$ ]]; then
    echo "numeric metric is invalid: $value" >&2
    exit 2
  fi
done
for value in "$first_pass" "$final_success"; do
  if test "$value" != true && test "$value" != false; then
    echo "boolean metric must be true or false: $value" >&2
    exit 2
  fi
done
if test "$variant" != baseline && test "$variant" != treatment; then
  echo "--variant must be baseline or treatment: $variant" >&2
  exit 2
fi
if [[ ! "$error_codes" =~ ^[A-Za-z0-9_.,:-]+$ ]]; then
  echo "--error-codes must be a comma-separated safe identifier list or none" >&2
  exit 2
fi
if [[ ! "$output" =~ \.jsonl$ ]]; then
  echo "--output must name a .jsonl file: $output" >&2
  exit 2
fi

# The record has to say which task set and which protocol it was produced under, or a result from an
# older manifest could be scored against a newer one. Both are read from the files, never passed in.
jq -e --arg id "$task_id" 'any(.tasks[]; .id == $id)' "$manifest" >/dev/null || {
  echo "unknown task id: $task_id" >&2
  exit 2
}
max_rounds="$(jq -r '.maxRepairRounds' "$manifest")"
if test "$repair_rounds" -gt "$max_rounds"; then
  echo "repairRounds $repair_rounds exceeds the task set limit $max_rounds; the arms would stop being comparable" >&2
  exit 2
fi
schema_version="$(jq -r '.properties.schemaVersion.enum[0]' "$schema")"

if command -v sha256sum >/dev/null; then
  task_set_hash="$(sha256sum "$manifest" | cut -d' ' -f1)"
elif command -v shasum >/dev/null; then
  task_set_hash="$(shasum -a 256 "$manifest" | cut -d' ' -f1)"
else
  echo "Recording an evaluation requires sha256sum or shasum" >&2
  exit 1
fi

mkdir -p "$(dirname "$output")"
jq -cn \
  --argjson schemaVersion "$schema_version" \
  --arg taskId "$task_id" --arg runId "$run_id" --arg model "$model" \
  --arg variant "$variant" --arg contextId "$context_id" \
  --argjson inputTokens "$input_tokens" --argjson outputTokens "$output_tokens" \
  --argjson firstPassSuccess "$first_pass" --argjson finalSuccess "$final_success" \
  --argjson repairRounds "$repair_rounds" \
  --argjson errorCodes "$(test "$error_codes" = none && echo '[]' || jq -R 'split(",")' <<<"$error_codes")" \
  --argjson durationMs "$duration_ms" --arg commit "$commit" --arg taskSetHash "$task_set_hash" \
  '{schemaVersion:$schemaVersion,taskId:$taskId,runId:$runId,model:$model,variant:$variant,
    contextId:$contextId,inputTokens:$inputTokens,outputTokens:$outputTokens,
    firstPassSuccess:$firstPassSuccess,finalSuccess:$finalSuccess,repairRounds:$repairRounds,
    errorCodes:$errorCodes,durationMs:$durationMs,commit:$commit,taskSetHash:$taskSetHash}' >> "$output"
