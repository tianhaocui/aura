#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/aura-docs-code.XXXXXX")"
cleanup() { rm -rf -- "$temp_dir"; }
trap cleanup EXIT

maven=(mvn)
if test -n "${MAVEN_SETTINGS:-}"; then
  maven+=(-s "$MAVEN_SETTINGS")
fi

"${maven[@]}" -q -f "$repo_root/pom.xml" \
  -pl aura-web -am -DskipTests package
"${maven[@]}" -q -f "$repo_root/pom.xml" \
  -pl aura-web -am dependency:build-classpath \
  -Dmdep.outputFile="$temp_dir/dependencies.classpath"

classpath="$repo_root/aura-contract/target/classes:$repo_root/aura-core/target/classes:$repo_root/aura-web/target/classes"
if test -f "$temp_dir/dependencies.classpath"; then
  classpath="$classpath:$(cat "$temp_dir/dependencies.classpath")"
fi

docs=(
  "$repo_root/docs/DOCUMENTATION.md"
  "$repo_root/AI_GUIDE.md"
)
compiled=0
for doc in "${docs[@]}"; do
  while IFS=: read -r line name; do
    source="$temp_dir/$name.java"
    awk -v start="$line" '
      NR > start && /^```java$/ { inside=1; next }
      inside && /^```$/ { exit }
      inside { print }
    ' "$doc" > "$source"
    test -s "$source"
    sed -i.bak -E 's/public final class /final class /; s/public class /class /' "$source"
    rm -f "$source.bak"
    mkdir -p "$temp_dir/classes"
    javac --release 17 -parameters -cp "$classpath" -d "$temp_dir/classes" "$source"
    compiled=$((compiled + 1))
  done < <(rg -n '<!-- aura:compile name=[a-z0-9-]+ -->' "$doc" \
    | sed -E 's/^([0-9]+):.*name=([a-z0-9-]+).*/\1:\2/')
done

test "$compiled" -ge 2

canonical_docs=(
  "$repo_root/README.md" "$repo_root/README_CN.md" "$repo_root/AI_GUIDE.md"
  "$repo_root/docs/DOCUMENTATION.md" "$repo_root/docs/aura-core.md"
  "$repo_root/docs/aura-module.md" "$repo_root/docs/aura-web.md"
  "$repo_root/docs/aura-db.md" "$repo_root/docs/aura-workflow.md"
  "$repo_root/docs/aura-ai.md" "$repo_root/docs/aura-mcp.md"
  "$repo_root/docs/aura-processor.md" "$repo_root/AI_GUIDE_OPS.md"
)
if rg -n 'Aura\.create\(|AuraPlugin|\.plugin\(' "${canonical_docs[@]}"; then
  echo "legacy Aura API found in canonical documentation" >&2
  exit 1
fi

echo "Aura canonical documentation code passed: $compiled compiled fences"
