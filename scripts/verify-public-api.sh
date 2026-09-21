#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
snapshot="$repo_root/api/aura-1.0.public-api.txt"
temp_file="$(mktemp "${TMPDIR:-/tmp}/aura-public-api.XXXXXX")"
cleanup() { rm -f -- "$temp_file"; }
trap cleanup EXIT

modules=(
  aura-contract aura-core aura-processor aura-web aura-db
  aura-db-migration-flyway aura-db-migration-liquibase
  aura-mcp aura-ai aura-workflow aura-bom aura-starter aura-archetype
)

for module in "${modules[@]}"; do
  classes="$repo_root/$module/target/classes"
  if test ! -d "$classes"; then
    mvn -q -f "$repo_root/pom.xml" -pl "$module" -am -DskipTests package
  fi
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
    printf '### %s:%s\n' "$module" "$class_name" >> "$temp_file"
    javap -public -classpath "$classes" "$class_name" \
      | sed '/^Compiled from /d' >> "$temp_file"
  done < <(find "$classes" -type f -name '*.class' | sort)
done

if test "${AURA_API_UPDATE:-false}" = true; then
  mkdir -p "$(dirname "$snapshot")"
  cp "$temp_file" "$snapshot"
  echo "Aura public API baseline updated: $snapshot"
  exit 0
fi

test -f "$snapshot" || {
  echo "Missing API baseline: run AURA_API_UPDATE=true bash scripts/verify-public-api.sh" >&2
  exit 1
}
if ! cmp -s "$snapshot" "$temp_file"; then
  echo "Public API changed. Review the diff, then update deliberately with:" >&2
  echo "  AURA_API_UPDATE=true bash scripts/verify-public-api.sh" >&2
  diff -u "$snapshot" "$temp_file" || true
  exit 1
fi

echo "Aura public API matches the committed 1.0 baseline"
