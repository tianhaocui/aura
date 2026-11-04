#!/usr/bin/env bash
# Runs one golden task through both context arms and records the result with scripts/record-ai-eval.sh.
# The answer is graded by compiling it against this reactor's own API, so a hallucinated type or a
# removed method is a failed run rather than a plausible-looking paragraph.
#
# Nothing here defaults the model, the endpoint, the credential, or the context ids: an experiment
# whose recipe is implied by the machine it ran on is not repeatable.
set -uo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
manifest="$repo_root/evals/tasks.json"

usage() {
  cat >&2 <<'EOF'
usage: run-ai-pilot.sh --task-id ID --class NAME --baseline-context-id ID --baseline-context FILE[,FILE...]
                       --staged-context-id ID --staged-context FILE[,FILE...]
                       --model NAME --base-url URL --api-key-file FILE
                       [--compile-target MODULE] [--max-repair-rounds N] [--max-output-tokens N]
                       [--run-id-prefix PREFIX] [--work-dir DIR] [--results FILE]
EOF
  exit 2
}

task_id="" class_name="" baseline_id="" baseline_files="" staged_id="" staged_files=""
model="" base_url="" key_file="" compile_target="examples/aura-composed"
max_rounds="" run_prefix="pilot-$(date +%Y%m%d)" work_dir="" results=""
max_output=3000

while test "$#" -gt 0; do
  case "$1" in
    --task-id) task_id="${2:-}"; shift 2 ;;
    --class) class_name="${2:-}"; shift 2 ;;
    --baseline-context-id) baseline_id="${2:-}"; shift 2 ;;
    --baseline-context) baseline_files="${2:-}"; shift 2 ;;
    --staged-context-id) staged_id="${2:-}"; shift 2 ;;
    --staged-context) staged_files="${2:-}"; shift 2 ;;
    --model) model="${2:-}"; shift 2 ;;
    --base-url) base_url="${2:-}"; shift 2 ;;
    --api-key-file) key_file="${2:-}"; shift 2 ;;
    --compile-target) compile_target="${2:-}"; shift 2 ;;
    --max-repair-rounds) max_rounds="${2:-}"; shift 2 ;;
    --max-output-tokens) max_output="${2:-}"; shift 2 ;;
    --run-id-prefix) run_prefix="${2:-}"; shift 2 ;;
    --work-dir) work_dir="${2:-}"; shift 2 ;;
    --results) results="${2:-}"; shift 2 ;;
    *) usage ;;
  esac
done

for name in task_id class_name baseline_id baseline_files staged_id staged_files model base_url key_file; do
  value="${!name}"
  test -n "$value" || { usage; echo "missing --${name//_/-}" >&2; exit 2; }
done
test -f "$manifest" || { echo "missing $manifest" >&2; exit 1; }
test -f "$key_file" || { echo "missing api key file: $key_file" >&2; exit 1; }
for command_name in curl jq mvn javac; do
  command -v "$command_name" >/dev/null || { echo "the pilot needs $command_name" >&2; exit 1; }
done

# The repair loop is capped by the task set, not by the operator, so repairRounds means the same thing
# in every run. A smaller cap is allowed; a larger one would make the arms incomparable.
manifest_rounds="$(jq -r '.maxRepairRounds' "$manifest")"
if [[ ! "$manifest_rounds" =~ ^[0-9]+$ ]]; then
  echo "evals/tasks.json must declare a numeric maxRepairRounds" >&2
  exit 1
fi
if test -z "$max_rounds"; then
  max_rounds="$manifest_rounds"
elif [[ ! "$max_rounds" =~ ^[0-9]+$ ]] || test "$max_rounds" -gt "$manifest_rounds"; then
  echo "--max-repair-rounds must be at most the task set limit ($manifest_rounds): $max_rounds" >&2
  exit 2
fi

test -n "$work_dir" || work_dir="$repo_root/$compile_target/target/aura-pilot/$run_prefix-$task_id"
test -n "$results" || results="$repo_root/evals/results/$run_prefix.jsonl"
mkdir -p "$work_dir"

task_prompt="$(jq -r --arg id "$task_id" '.tasks[] | select(.id == $id) | .prompt' "$manifest")"
test -n "$task_prompt" || { echo "unknown task id: $task_id" >&2; exit 2; }

build_context() { # $1 = comma separated repo-relative paths -> file holding them concatenated
  local out="$work_dir/$2.md" entry
  : > "$out"
  IFS=',' read -r -a files <<< "$1"
  for entry in "${files[@]}"; do
    if ! test -f "$repo_root/$entry"; then
      echo "context file does not exist: $entry" >&2
      return 1
    fi
    cat "$repo_root/$entry" >> "$out"
    printf '\n' >> "$out"
  done
  printf '%s' "$out"
}

# One invocation: the sibling modules are built in the same reactor session that asks for the
# classpath, because a clean clone has never installed any of them.
MAVEN_ARGS="${MAVEN_ARGS:-} -B" mvn -q -f "$repo_root/pom.xml" -pl "$compile_target" -am \
  -DskipTests package dependency:build-classpath -Dmdep.outputFile=target/aura-pilot-classpath.txt \
  || { echo "could not build the compile classpath" >&2; exit 1; }
classpath="$(cat "$repo_root/$compile_target/target/aura-pilot-classpath.txt")"
test -n "$classpath" || { echo "empty classpath" >&2; exit 1; }

api_key="$(head -1 "$key_file" | tr -d '\r\n"')"
test -n "$api_key" || { echo "api key file is empty" >&2; exit 1; }

extract_java() { # keep the single compilation unit, dropping prose and code fences
  awk '/^package[ \t]+/{start=NR} /^}[ \t]*$/{last=NR} {line[NR]=$0}
       END{if(!start){exit} if(!last){last=NR} for(i=start;i<=last;i++) print line[i]}' "$1"
}

# $1 variant, $2 context id, $3 context file
run_arm() {
  local variant=$1 context_id=$2 ctx=$3
  local dir="$work_dir/$variant" file="$work_dir/$variant/$class_name.java"
  local tin=0 tout=0 rounds=0 first=false final=false completed=false transport_failed=false
  rm -rf "$dir"; mkdir -p "$dir/classes"
  { cat "$ctx"; printf '\n\nYou are writing Java 17 for the Aura 1.0 runtime, using only the APIs documented above.\nTask: %s\n\nDeliver exactly one self-contained compilation unit: package evalpilot, one public top-level class named %s, no other project types, no build files, no tests, no prose.\n' "$task_prompt" "$class_name"; } > "$dir/base.txt"
  local started=$(date +%s%3N) attempt=0
  while :; do
    if test "$attempt" = 0; then
      cp "$dir/base.txt" "$dir/prompt.txt"
    else
      # A repair round re-sends the same context: dropping it would understate the expensive arm.
      cp "$dir/base.txt" "$dir/prompt.txt"
      { printf '\nYour previous answer does not compile. Compiler output:\n\n'; cat "$dir/errors.txt";
        printf '\nPrevious answer:\n\n'; cat "$file";
        printf '\nFix it. Reply with the whole corrected compilation unit and nothing else.\n'; } >> "$dir/prompt.txt"
    fi
    jq -n --arg model "$model" --argjson max "$max_output" --rawfile prompt "$dir/prompt.txt" \
      '{model:$model,temperature:0,max_tokens:$max,messages:[{role:"user",content:$prompt}]}' \
      > "$dir/request.json"
    echo "  $task_id/$variant round $attempt: $(wc -c < "$dir/prompt.txt") prompt bytes"
    local http
    http=$(curl -sS --max-time 900 -o "$dir/response.json" -w '%{http_code}' \
      -H "Authorization: Bearer $api_key" -H 'content-type: application/json' \
      --data-binary @"$dir/request.json" "$base_url/chat/completions")
    if test "$http" != 200; then
      echo "  $task_id/$variant transport failed: HTTP $http $(head -c 200 "$dir/response.json")" >&2
      transport_failed=true
      break
    fi
    tin=$(( tin + $(jq -r '.usage.prompt_tokens // 0' "$dir/response.json") ))
    tout=$(( tout + $(jq -r '.usage.completion_tokens // 0' "$dir/response.json") ))
    jq -r '.choices[0].message.content // ""' "$dir/response.json" > "$dir/answer$attempt.txt"
    extract_java "$dir/answer$attempt.txt" > "$file"
    if ! test -s "$file" || ! grep -q "class $class_name" "$file"; then
      echo "  $task_id/$variant answer held no compilation unit named $class_name" > "$dir/errors.txt"
    elif javac -nowarn -cp "$classpath" -d "$dir/classes" "$file" > "$dir/errors.txt" 2>&1; then
      test "$attempt" = 0 && first=true
      final=true; completed=true
      break
    fi
    echo "  $task_id/$variant round $attempt did not compile:"
    head -4 "$dir/errors.txt" | sed 's/^/      /' >&2
    test "$attempt" -ge "$max_rounds" && break
    attempt=$(( attempt + 1 )); rounds=$attempt
  done
  local duration=$(( $(date +%s%3N) - started ))
  if test "$transport_failed" = true; then
    echo "  $task_id/$variant not recorded: the endpoint failed, which is not a verdict about the code" >&2
    return 1
  fi
  local codes=none
  test "$final" = true || codes=javac-compile-error
  echo "  $task_id/$variant in=$tin out=$tout first=$first final=$final rounds=$rounds ms=$duration"
  bash "$repo_root/scripts/record-ai-eval.sh" --task-id "$task_id" \
    --run-id "$run_prefix-$task_id-$variant" --model "$model" --variant "$variant" \
    --context-id "$context_id" --input-tokens "$tin" --output-tokens "$tout" \
    --first-pass "$first" --final-success "$final" --repair-rounds "$rounds" \
    --error-codes "$codes" --duration-ms "$duration" --output "$results"
}

baseline_ctx="$(build_context "$baseline_files" baseline)" || exit 2
staged_ctx="$(build_context "$staged_files" staged)" || exit 2
for ctx in "$baseline_ctx" "$staged_ctx"; do
  test -s "$ctx" || { echo "context recipe produced no content: $ctx" >&2; exit 2; }
done
echo "== $task_id baseline ($baseline_id, $(wc -c < "$baseline_ctx") bytes) =="
run_arm baseline "$baseline_id" "$baseline_ctx"
echo "== $task_id staged ($staged_id, $(wc -c < "$staged_ctx") bytes) =="
run_arm treatment "$staged_id" "$staged_ctx"
echo "results: $results"
echo "evidence: $work_dir"
