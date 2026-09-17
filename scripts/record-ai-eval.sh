#!/usr/bin/env bash
set -euo pipefail

if test "$#" -ne 11; then
  echo "usage: $0 OUTPUT TASK_ID RUN_ID MODEL INPUT_TOKENS OUTPUT_TOKENS FIRST_PASS REPAIR_ROUNDS ERROR_CODES DURATION_MS COMMIT" >&2
  exit 2
fi

output=$1
task_id=$2
run_id=$3
model=$4
input_tokens=$5
output_tokens=$6
first_pass=$7
repair_rounds=$8
error_codes=$9
duration_ms=${10}
commit=${11}

for value in "$task_id" "$run_id" "$model" "$commit"; do
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
if test "$first_pass" != true && test "$first_pass" != false; then
  echo "FIRST_PASS must be true or false" >&2
  exit 2
fi
if [[ ! "$error_codes" =~ ^[A-Za-z0-9_.,:-]+$ ]]; then
  echo "ERROR_CODES must be a comma-separated safe identifier list or none" >&2
  exit 2
fi

errors_json=""
if test "$error_codes" != none; then
  IFS=',' read -r -a errors <<< "$error_codes"
  for error in "${errors[@]}"; do
    if test -n "$errors_json"; then errors_json+=","; fi
    errors_json+="\"$error\""
  done
fi

mkdir -p "$(dirname "$output")"
printf '{"taskId":"%s","runId":"%s","model":"%s","inputTokens":%s,"outputTokens":%s,"firstPassSuccess":%s,"repairRounds":%s,"errorCodes":[%s],"durationMs":%s,"commit":"%s"}\n' \
  "$task_id" "$run_id" "$model" "$input_tokens" "$output_tokens" "$first_pass" \
  "$repair_rounds" "$errors_json" "$duration_ms" "$commit" >> "$output"
