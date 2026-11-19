#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
pom="$repo_root/pom.xml"
root_version="$(sed -n 's:.*<version>\([^<]*\)</version>.*:\1:p' "$pom" | head -1)"
test -n "$root_version"
rg -q '<undertow.version>2\.3\.(2[5-9]|[3-9][0-9])\.Final</undertow.version>' "$pom"
rg -q '<parameters>true</parameters>' "$pom"
rg -q '<artifactId>maven-enforcer-plugin</artifactId>' "$pom"
rg -q '<jacoco.line.minimum>0\.55</jacoco.line.minimum>' "$pom"
rg -q '<jacoco.branch.minimum>0\.45</jacoco.branch.minimum>' "$pom"
if rg -q '<module>aura-dev</module>' "$pom"; then
  echo "legacy aura-dev module must not re-enter the 1.0 reactor" >&2
  exit 1
fi

while IFS= read -r module; do
  test -d "$repo_root/$module"
  test -f "$repo_root/$module/pom.xml"
  parent_version="$(sed -n '/<parent>/,/<\/parent>/s:.*<version>\([^<]*\)</version>.*:\1:p' "$repo_root/$module/pom.xml" | head -1)"
  test "$parent_version" = "$root_version"
done < <(sed -n 's:.*<module>\([^<]*\)</module>.*:\1:p' "$pom")

npm_version="$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' \
  "$repo_root/aura-mcp-npm/package.json" | head -1)"
test "$npm_version" = "$root_version"
rg -q "version: packageMetadata\.version" "$repo_root/aura-mcp-npm/index.js"
rg -q "version: packageMetadata\.version" \
  "$repo_root/aura-mcp/src/main/resources/mcp-bridge-template.js"
rg -q "String version = McpProtocolConstants\.SERVER_VERSION" \
  "$repo_root/aura-mcp/src/main/java/io/aura/mcp/McpPackager.java"

test -f "$repo_root/aura-ai/src/main/java/io/aura/ai/AiClient.java"
test -f "$repo_root/aura-workflow/src/main/java/io/aura/workflow/WorkflowModule.java"
test -f "$repo_root/aura-bom/pom.xml"
test -f "$repo_root/aura-starter/pom.xml"
test -f "$repo_root/aura-archetype/src/main/resources/META-INF/maven/archetype-metadata.xml"
test -x "$repo_root/scripts/verify-maven-archetype.sh"
test -x "$repo_root/scripts/verify-release-artifacts.sh"
test -x "$repo_root/scripts/verify-release-signatures.sh"
test -x "$repo_root/scripts/verify-sbom.sh"
test -x "$repo_root/scripts/verify-public-api.sh"
test -x "$repo_root/scripts/verify-public-api-classifier.sh"
test -f "$repo_root/scripts/public-api-symbols.awk"
test -f "$repo_root/scripts/public-api-visible-types.awk"
test -x "$repo_root/scripts/verify-docs-code.sh"
test -x "$repo_root/scripts/verify-mcp-npm.mjs"
test -x "$repo_root/scripts/verify-deployment.sh"
test -f "$repo_root/aura-db-migration-flyway/src/test/java/io/aura/db/flyway/FlywayExternalDatabaseTest.java"
test -f "$repo_root/aura-db-migration-liquibase/src/test/java/io/aura/db/liquibase/LiquibaseExternalDatabaseTest.java"
test -f "$repo_root/api/aura-1.0.public-api.txt"
test -f "$repo_root/docs/mcp-compatibility.md"
test -f "$repo_root/docs/1.0-readiness.md"
rg -q 'docs/1\.0-readiness\.md' "$repo_root/README.md" "$repo_root/README_CN.md"
rg -q 'Aura 1.0 is not release-complete' "$repo_root/docs/1.0-readiness.md"
rg -q 'Central Portal staging' "$repo_root/docs/1.0-readiness.md"
rg -q 'GitHub-hosted Docker/kind' "$repo_root/docs/1.0-readiness.md"
rg -q '2025-03-26' "$repo_root/docs/mcp-compatibility.md"
rg -q 'MCP_PERMISSION_DENIED' "$repo_root/docs/mcp-compatibility.md"
rg -q 'MCP_RESPONSE_TOO_LARGE' "$repo_root/docs/mcp-compatibility.md"
rg -q 'McpRouterSpecIntegrationTest' "$repo_root/docs/mcp-compatibility.md"
rg -q 'google/osv-scanner-action/osv-scanner-action@v2\.0\.2' \
  "$repo_root/.github/workflows/ci.yml"
if rg -n "osv-full-scan:[[:space:]]*$" "$repo_root/.github/workflows/ci.yml" >/dev/null \
    && rg -n "github\.event_name != 'pull_request'" "$repo_root/.github/workflows/ci.yml"; then
  echo "full OSV scan must run before pull requests are merged" >&2
  exit 1
fi
rg -q 'bash scripts/verify-release-signatures\.sh' \
  "$repo_root/.github/workflows/ci.yml"
rg -q 'path: target/aura-release-checksums\.sha256' \
  "$repo_root/.github/workflows/ci.yml"
rg -q -- '-Prelease -DskipTests' \
  "$repo_root/scripts/verify-release-signatures.sh"
if rg -n '(^|[[:space:]])deploy([[:space:]]|$)' \
    "$repo_root/scripts/verify-release-signatures.sh"; then
  echo "release signature verification must never invoke Maven deploy" >&2
  exit 1
fi
rg -q 'path: target/aura-sbom\.json' \
  "$repo_root/.github/workflows/ci.yml"
rg -q 'path: target/aura-public-api-report\.json' \
  "$repo_root/.github/workflows/ci.yml"
rg -q 'breakingChanges' "$repo_root/scripts/verify-public-api.sh"
# The three review gates are independent CI jobs: each runs its own script under its
# own name, so a failure names the gate that failed instead of one step in a long lane.
rg -q 'bash scripts/verify-architecture\.sh' "$repo_root/.github/workflows/ci.yml"
rg -q 'bash scripts/verify-public-api\.sh' "$repo_root/.github/workflows/ci.yml"
rg -q 'bash scripts/verify-manifest\.sh' "$repo_root/.github/workflows/ci.yml"
rg -q 'cron: .17 3 \* \* 1.' "$repo_root/.github/workflows/ci.yml"
rg -q '^  workflow_dispatch:' "$repo_root/.github/workflows/ci.yml"
rg -q 'run-name:' "$repo_root/.github/workflows/ci.yml"
for artifact in \
  aura-contract aura-core aura-web aura-db aura-db-migration-flyway \
  aura-db-migration-liquibase aura-workflow aura-ai aura-mcp aura-processor \
  aura-starter; do
  rg -q "<artifactId>$artifact</artifactId>" "$repo_root/aura-bom/pom.xml"
done
rg -q '<artifactId>aura-web</artifactId>' "$repo_root/aura-starter/pom.xml"
rg -q '<artifactId>aura-bom</artifactId>' \
  "$repo_root/aura-archetype/src/main/resources/archetype-resources/pom.xml"
rg -q 'TestClient\.of\(app\)' \
  "$repo_root/aura-archetype/src/main/resources/archetype-resources/src/test/java/AppTest.java"
rg -q 'ServicesResourceTransformer' \
  "$repo_root/aura-archetype/src/main/resources/archetype-resources/pom.xml"
rg -q 'HealthModule\.create\(\)' \
  "$repo_root/aura-archetype/src/main/resources/archetype-resources/src/main/java/App.java"
test -f "$repo_root/aura-archetype/src/main/resources/archetype-resources/Dockerfile"
test -f "$repo_root/aura-archetype/src/main/resources/archetype-resources/k8s/aura.yaml"
rg -q 'readOnlyRootFilesystem: true' \
  "$repo_root/aura-archetype/src/main/resources/archetype-resources/k8s/aura.yaml"
rg -q 'type=secret,id=maven_settings' \
  "$repo_root/aura-archetype/src/main/resources/archetype-resources/Dockerfile"
rg -q 'deploymentName' \
  "$repo_root/aura-archetype/src/main/resources/META-INF/maven/archetype-metadata.xml"
rg -q 'validationRegex' \
  "$repo_root/aura-archetype/src/main/resources/META-INF/maven/archetype-metadata.xml"
rg -q 'AURA_PORT' \
  "$repo_root/aura-archetype/src/main/resources/archetype-resources/k8s/aura.yaml"
rg -q '<maven.deploy.skip>true</maven.deploy.skip>' \
  "$repo_root/examples/aura-composed/pom.xml"
rg -q '<maven.deploy.skip>true</maven.deploy.skip>' \
  "$repo_root/examples/aura-generated/pom.xml"
test -f "$repo_root/AI_GUIDE.md"
test -f "$repo_root/docs/legacy-ai-guide-0.6.md"
test -f "$repo_root/docs/aura-module.md"
test -f "$repo_root/docs/aura-workflow.md"
test -f "$repo_root/docs/migration-to-1.0.md"
test -f "$repo_root/docs/DOCUMENTATION.md"
test -f "$repo_root/docs/legacy-documentation-0.6.md"

# The compiling list is the whole of the documentation gate's reach: a document dropped from
# `docs=(...)` stops being compiled and nothing else notices, and its prose can name a type that
# no longer exists. The four module guides that gained fences are checked for being in that list.
for guide in aura-module aura-db aura-mcp aura-processor; do
  if ! rg -qU "(?m)^docs=\\([^)]*docs/$guide\\.md[^)]*\\)" \
      "$repo_root/scripts/verify-docs-code.sh"; then
    echo "documentation stopped compiling a module guide: docs/$guide.md is not in the compiling list" >&2
    exit 1
  fi
done

echo "Aura module packaging and CI wiring contracts passed (version $root_version)"
