#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
root_version="$(sed -n 's:.*<version>\([^<]*\)</version>.*:\1:p' "$repo_root/pom.xml" | head -1)"
test -n "$root_version"
maven=(mvn)
if test -n "${MAVEN_SETTINGS:-}"; then
  maven+=(-s "$MAVEN_SETTINGS")
fi

modules=(
  aura-contract aura-core aura-processor aura-web aura-db
  aura-db-migration-flyway aura-db-migration-liquibase
  aura-mcp aura-ai aura-workflow aura-starter aura-archetype
)
module_list="$(IFS=,; echo "${modules[*]}")"

"${maven[@]}" -q -f "$repo_root/pom.xml" \
  -pl "$module_list" -am \
  -Prelease -Dgpg.skip=true -DskipTests package

for module in "${modules[@]}"; do
  base="$repo_root/$module/target/$module-$root_version"
  test -f "$base.jar"
  test -f "$base-sources.jar"
  test -f "$base-javadoc.jar"
done

jar tf "$repo_root/aura-starter/target/aura-starter-$root_version-javadoc.jar" \
  | rg -q '^README\.md$'
jar tf "$repo_root/aura-archetype/target/aura-archetype-$root_version-javadoc.jar" \
  | rg -q '^README\.md$'

rg -q '<maven.deploy.skip>true</maven.deploy.skip>' \
  "$repo_root/examples/aura-composed/pom.xml"
rg -q '<maven.deploy.skip>true</maven.deploy.skip>' \
  "$repo_root/examples/aura-generated/pom.xml"

echo "Aura release artifact shape passed"
