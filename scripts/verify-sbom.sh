#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
root_version="$(sed -n 's:.*<version>\([^<]*\)</version>.*:\1:p' "$repo_root/pom.xml" | head -1)"
test -n "$root_version"
command -v jq >/dev/null || {
  echo "SBOM verification requires jq" >&2
  exit 1
}
maven=(mvn)
if test -n "${MAVEN_SETTINGS:-}"; then
  maven+=(-s "$MAVEN_SETTINGS")
fi

"${maven[@]}" -q -f "$repo_root/pom.xml" \
  -Psbom -DskipTests verify

sbom="$repo_root/target/aura-sbom.json"
test -s "$sbom"
jq -e --arg version "$root_version" '
  .bomFormat == "CycloneDX" and
  .specVersion == "1.6" and
  .version == 1 and
  (.serialNumber | type == "string" and startswith("urn:uuid:")) and
  .metadata.component.group == "io.github.tianhaocui" and
  .metadata.component.name == "aura-parent" and
  .metadata.component.version == $version and
  (.metadata.component["bom-ref"] | type == "string" and length > 0) and
  (.components | type == "array") and
  (.dependencies | type == "array")
' "$sbom" >/dev/null

public_modules=(
  aura-contract aura-core aura-processor aura-web aura-db
  aura-db-migration-flyway aura-db-migration-liquibase
  aura-mcp aura-ai aura-workflow aura-bom aura-starter aura-archetype
)
expected_count="${#public_modules[@]}"
actual_count="$(jq '[.components[] | select(.group == "io.github.tianhaocui")] | length' "$sbom")"
test "$actual_count" -eq "$expected_count"

root_ref="$(jq -er '.metadata.component["bom-ref"]' "$sbom")"
for module in "${public_modules[@]}"; do
  ref="$(jq -er --arg name "$module" --arg version "$root_version" '
    [.components[] | select(
      .group == "io.github.tianhaocui" and
      .name == $name and
      .version == $version
    )] as $matches |
    if ($matches | length) == 1 then $matches[0]["bom-ref"] else empty end
  ' "$sbom")"
  test -n "$ref"
  jq -e --arg root "$root_ref" --arg ref "$ref" '
    ([.dependencies[] | select(.ref == $ref)] | length) == 1 and
    any(.dependencies[]; .ref == $root and (.dependsOn | index($ref)) != null)
  ' "$sbom" >/dev/null
done

jq -e '
  ([.metadata.component["bom-ref"]] + [.components[]["bom-ref"]]) as $refs |
  ($refs | length) == ($refs | unique | length) and
  ($refs | sort) == ([.dependencies[].ref] | sort) and
  all(.dependencies[];
    .ref as $ref |
    ($refs | index($ref)) != null and
    all(.dependsOn[]; . as $dependency | ($refs | index($dependency)) != null)
  )
' "$sbom" >/dev/null

echo "Aura aggregate SBOM passed: $expected_count public modules and a closed dependency graph"
