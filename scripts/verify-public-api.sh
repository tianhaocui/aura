#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
snapshot="$repo_root/api/aura-1.0.public-api.txt"
report="$repo_root/target/aura-public-api-report.json"
temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/aura-public-api.XXXXXX")"
lock_file="$repo_root/target/.aura-public-api.lock"
lock_candidate="$repo_root/target/.aura-public-api.lock.$$"
lock_acquired=false
temp_file="$temp_dir/current.txt"
candidate_snapshot="$temp_dir/candidate-snapshot.txt"
candidate_types="$temp_dir/candidate-types.tsv"
visible_types="$temp_dir/visible-types.txt"
baseline_symbols="$temp_dir/baseline-symbols.tsv"
current_symbols="$temp_dir/current-symbols.tsv"
removed_symbols="$temp_dir/removed-symbols.tsv"
added_symbols="$temp_dir/added-symbols.tsv"
cleanup() {
  rm -rf -- "$temp_dir"
  if test "$lock_acquired" = true; then
    lock_owner="$(sed -n '1p' "$lock_file" 2>/dev/null || true)"
    if test "$lock_owner" = "$$"; then rm -f -- "$lock_file"; fi
  fi
  rm -f -- "$lock_candidate"
}
trap cleanup EXIT
for command_name in jq mvn javap rg ps ln; do
  command -v "$command_name" >/dev/null || {
    echo "Public API verification requires $command_name" >&2
    exit 1
  }
done

mkdir -p "$repo_root/target"
printf '%s\n' "$$" >"$lock_candidate"
for _ in 1 2 3; do
  if ln "$lock_candidate" "$lock_file" 2>/dev/null; then
    lock_acquired=true
    break
  fi
  observed_lock="$temp_dir/observed-lock"
  rm -f -- "$observed_lock"
  if ! ln "$lock_file" "$observed_lock" 2>/dev/null; then continue; fi
  lock_pid="$(sed -n '1p' "$observed_lock" 2>/dev/null || true)"
  case "$lock_pid" in
    ''|*[!0-9]*) lock_pid="" ;;
  esac
  if test -n "$lock_pid" && kill -0 "$lock_pid" 2>/dev/null; then
    lock_command="$(ps -p "$lock_pid" -o command= 2>/dev/null || true)"
    case "$lock_command" in
      *verify-public-api.sh*)
        echo "Public API verification is already running (pid $lock_pid)" >&2
        exit 1
        ;;
    esac
  fi
  if test "$observed_lock" -ef "$lock_file"; then rm -f -- "$lock_file"; fi
done
if test "$lock_acquired" != true; then
  echo "Could not acquire public API verification lock: $lock_file" >&2
  exit 1
fi

modules=(
  aura-contract aura-core aura-processor aura-web aura-db
  aura-db-migration-flyway aura-db-migration-liquibase
  aura-mcp aura-ai aura-workflow aura-bom aura-starter aura-archetype
)
module_list="$(IFS=,; echo "${modules[*]}")"
maven=(mvn)
if test -n "${MAVEN_SETTINGS:-}"; then
  maven+=(-s "$MAVEN_SETTINGS")
fi
# Maven's incremental compiler does not delete classes whose source was removed.
# Clear only the fixed public-module outputs, then rebuild from current sources.
for module in "${modules[@]}"; do
  module_dir="$repo_root/$module"
  test -d "$module_dir"
  rm -rf -- "$module_dir/target/classes" "$module_dir/target/generated-sources"
done
"${maven[@]}" -q -f "$repo_root/pom.xml" \
  -pl "$module_list" -am -DskipTests package

for module in "${modules[@]}"; do
  classes="$repo_root/$module/target/classes"
  # POM-only and resource-only artifacts intentionally expose no Java classes.
  if test ! -d "$classes"; then
    continue
  fi
  while IFS= read -r class_file; do
    class_name="${class_file#"$classes"/}"
    class_name="${class_name%.class}"
    class_name="${class_name//\//.}"
    case "$class_name" in
      module-info|*module-info|META-INF.*) continue ;;
    esac
    class_api="$temp_dir/class-api.txt"
    javap -public -classpath "$classes" "$class_name" \
      | sed '/^Compiled from /d' >"$class_api"
    if rg -q '^(public|protected)[[:space:]]' "$class_api"; then
      owner="$module:$class_name"
      outer_owner="-"
      if [[ "$class_name" == *'$'* ]]; then
        class_verbose="$temp_dir/class-verbose.txt"
        javap -verbose -classpath "$classes" "$class_name" >"$class_verbose"
        class_internal="${class_name//./\/}"
        outer_internal="$(awk -v current="$class_internal" '
          {
            marker = "=class " current " of class "
            position = index($0, marker)
            if (position > 0) {
              value = substr($0, position + length(marker))
              sub(/[[:space:]].*$/, "", value)
              print value
              exit
            }
          }
        ' "$class_verbose")"
        if test -n "$outer_internal"; then
          outer_owner="$module:${outer_internal//\//.}"
        fi
      fi
      printf '%s\t%s\n' "$owner" "$outer_owner" >>"$candidate_types"
      printf '### %s\n' "$owner" >>"$candidate_snapshot"
      cat "$class_api" >>"$candidate_snapshot"
    fi
  done < <(find "$classes" -type f -name '*.class' | LC_ALL=C sort)
done

awk -f "$repo_root/scripts/public-api-visible-types.awk" "$candidate_types" \
  | LC_ALL=C sort >"$visible_types"
awk '
  NR == FNR { visible[$0] = 1; next }
  /^### / {
    owner = substr($0, 5)
    include = owner in visible
  }
  include { print }
' "$visible_types" "$candidate_snapshot" >"$temp_file"

extract_symbols() {
  local source="$1"
  local destination="$2"
  awk -f "$repo_root/scripts/public-api-symbols.awk" "$source" \
    | LC_ALL=C sort -u >"$destination"
}

extract_symbols "$temp_file" "$current_symbols"
if test -f "$snapshot"; then
  extract_symbols "$snapshot" "$baseline_symbols"
else
  : >"$baseline_symbols"
fi
LC_ALL=C comm -23 "$baseline_symbols" "$current_symbols" >"$removed_symbols"
LC_ALL=C comm -13 "$baseline_symbols" "$current_symbols" >"$added_symbols"

symbols_json() {
  jq -Rn '[
    inputs |
    select(length > 0) |
    split("\t") |
    {kind: .[0], owner: .[1], signature: .[2]}
  ]' "$1"
}

breaking_json="$temp_dir/breaking.json"
additive_json="$temp_dir/additive.json"
symbols_json "$removed_symbols" >"$breaking_json"
symbols_json "$added_symbols" >"$additive_json"
breaking_count="$(jq 'length' "$breaking_json")"
additive_count="$(jq 'length' "$additive_json")"
raw_changed=true
if test -f "$snapshot" && cmp -s "$snapshot" "$temp_file"; then
  raw_changed=false
fi

status=unchanged
if test ! -f "$snapshot"; then
  status=missing-baseline
elif test "$raw_changed" = true; then
  status=changed
fi
if test "${AURA_API_UPDATE:-false}" = true; then
  status=baseline-updated
fi

mkdir -p "$(dirname "$report")"
jq -n \
  --arg status "$status" \
  --arg baseline "api/aura-1.0.public-api.txt" \
  --argjson rawSnapshotChanged "$raw_changed" \
  --slurpfile breaking "$breaking_json" \
  --slurpfile additive "$additive_json" '
  {
    schemaVersion: 1,
    status: $status,
    baseline: $baseline,
    rawSnapshotChanged: $rawSnapshotChanged,
    counts: {
      breaking: ($breaking[0] | length),
      additive: ($additive[0] | length)
    },
    breakingChanges: $breaking[0],
    additiveChanges: $additive[0]
  }
' >"$report"

test -f "$snapshot" || {
  if test "${AURA_API_UPDATE:-false}" = true; then
    mkdir -p "$(dirname "$snapshot")"
    cp "$temp_file" "$snapshot"
    echo "Aura public API baseline created; report: $report"
    exit 0
  fi
  echo "Missing API baseline: run AURA_API_UPDATE=true bash scripts/verify-public-api.sh" >&2
  exit 1
}

if test "${AURA_API_UPDATE:-false}" = true; then
  cp "$temp_file" "$snapshot"
  echo "Aura public API baseline updated; report: $report"
  exit 0
fi

if test "$status" = changed; then
  echo "Public API changed: $breaking_count breaking, $additive_count additive." >&2
  echo "Review $report, then update deliberately with:" >&2
  echo "  AURA_API_UPDATE=true bash scripts/verify-public-api.sh" >&2
  exit 1
fi

echo "Aura public API matches the committed 1.0 baseline; report: $report"
