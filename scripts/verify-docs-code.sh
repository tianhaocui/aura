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

# The modules a fence may name, and the subset whose dependencies have to be resolved to build
# that classpath. Compiling them is what makes a moved or renamed type fail here instead of
# shipping as prose.
doc_modules=(aura-contract aura-core aura-web aura-ai aura-workflow)
compile_modules=(aura-web aura-ai aura-workflow)
selected="$(IFS=,; echo "${compile_modules[*]}")"

"${maven[@]}" -q -f "$repo_root/pom.xml" \
  -pl "$selected" -am -DskipTests package
# A relative outputFile keeps each module's dependency list in its own target directory; an
# absolute path would let the last module in the reactor overwrite every earlier one.
"${maven[@]}" -q -f "$repo_root/pom.xml" \
  -pl "$selected" -am dependency:build-classpath \
  -Dmdep.outputFile=target/aura-docs-classpath.txt

classpath=""
for module in "${doc_modules[@]}"; do
  classpath="${classpath:+$classpath:}$repo_root/$module/target/classes"
done
for module in "${compile_modules[@]}"; do
  if test -f "$repo_root/$module/target/aura-docs-classpath.txt"; then
    # Third-party jars only. The installed io.github.tianhaocui aura jars are dropped by file
    # name: the aura-core 1.0.0 jar in ~/.m2 still carries io.aura.HealthModule, the type this
    # branch moved to io.aura.web, so a fence importing the old location would compile against
    # a jar instead of the tree it ships with.
    external="$(tr ':' '\n' < "$repo_root/$module/target/aura-docs-classpath.txt" \
      | { grep -v '/aura-[^/]*\.jar$' || true; } | paste -sd: -)"
    classpath="$classpath:$external"
  fi
done

docs=(
  "$repo_root/docs/DOCUMENTATION.md"
  "$repo_root/AI_GUIDE.md"
  "$repo_root/docs/aura-core.md"
  "$repo_root/docs/aura-web.md"
  "$repo_root/docs/aura-ai.md"
  "$repo_root/docs/aura-workflow.md"
)
compiled=0
for doc in "${docs[@]}"; do
  doc_compiled=0
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
    doc_compiled=$((doc_compiled + 1))
  done < <(rg -n '<!-- aura:compile name=[a-z0-9-]+ -->' "$doc" \
    | sed -E 's/^([0-9]+):.*name=([a-z0-9-]+).*/\1:\2/' || true)
  # Counted per document, not in total: a single floor over the whole list falls by the same
  # amount a deleted or typo'd marker lowers it, so removing a fence would keep the gate green.
  if test "$doc_compiled" -lt 1; then
    echo "no compilable fence marked in $doc" >&2
    exit 1
  fi
  compiled=$((compiled + doc_compiled))
done

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

# The prose around a fence can still point a reader at a type that no longer exists. The fences
# compile, so prose that names the pre-refactor home of HealthModule would be the one thing a
# reader copies.
if rg -n '\bio\.aura\.HealthModule\b' "${canonical_docs[@]}"; then
  echo "documentation names the removed io.aura.HealthModule location" >&2
  exit 1
fi

echo "Aura canonical documentation code passed: $compiled compiled fences"
