#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
settings_args=()
if test -n "${MAVEN_SETTINGS:-}"; then
  settings_args=(-s "$MAVEN_SETTINGS")
fi

mvn "${settings_args[@]}" -q -f "$repo_root/pom.xml" \
  -Psbom -DskipTests verify

sbom="$repo_root/target/aura-sbom.json"
test -s "$sbom"
rg -q '"bomFormat"[[:space:]]*:[[:space:]]*"CycloneDX"' "$sbom"
rg -q '"specVersion"[[:space:]]*:[[:space:]]*"1\.6"' "$sbom"
rg -q '"components"[[:space:]]*:[[:space:]]*\[' "$sbom"
rg -q 'io.github.tianhaocui' "$sbom"
rg -q 'aura-web' "$sbom"
rg -q 'aura-mcp' "$sbom"

echo "Aura aggregate SBOM passed: $sbom"
