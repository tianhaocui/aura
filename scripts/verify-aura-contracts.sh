#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
pom="$repo_root/pom.xml"
root_version="$(sed -n 's:.*<version>\([^<]*\)</version>.*:\1:p' "$pom" | head -1)"
test -n "$root_version"
rg -q '<undertow.version>2\.3\.(2[1-9]|[3-9][0-9])\.Final</undertow.version>' "$pom"
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
bash "$repo_root/scripts/verify-public-api-classifier.sh"
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
test -f "$repo_root/evals/tasks.json"
test -f "$repo_root/evals/result.schema.json"
test -x "$repo_root/scripts/verify-ai-evals.sh"
test -x "$repo_root/scripts/record-ai-eval.sh"
test -f "$repo_root/docs/DOCUMENTATION.md"
test -f "$repo_root/docs/legacy-documentation-0.6.md"
test -f "$repo_root/aura-processor/src/main/java/io/aura/processor/AuraRouteProcessor.java"
test -f "$repo_root/aura-processor/src/main/resources/META-INF/services/javax.annotation.processing.Processor"
if rg -n '<jacoco.skip>true</jacoco.skip>' "$repo_root/aura-"*/pom.xml; then
  echo "published modules must not bypass the coverage gate" >&2
  exit 1
fi
test -f "$repo_root/examples/aura-composed/src/main/java/example/ComposedApp.java"
test -f "$repo_root/examples/aura-composed/src/test/java/example/ComposedAppTest.java"
test -f "$repo_root/examples/aura-generated/pom.xml"
test -f "$repo_root/examples/aura-generated/src/test/java/example/generated/GeneratedAppTest.java"
test -f "$repo_root/aura-web/src/main/java/io/aura/web/RouteContract.java"
test -f "$repo_root/aura-contract/src/main/java/io/aura/contract/ExecutorMetrics.java"
test -f "$repo_root/aura-web/src/main/java/io/aura/web/SnapshotMetrics.java"
for removed in \
  "$repo_root/aura-core/src/main/java/io/aura/Aura.java" \
  "$repo_root/aura-core/src/main/java/io/aura/AuraPlugin.java" \
  "$repo_root/aura-core/src/main/java/io/aura/Reloadable.java" \
  "$repo_root/aura-core/src/main/java/io/aura/ReloadState.java" \
  "$repo_root/aura-core/src/main/java/io/aura/port/BeforeBuilder.java"; do
  if test -e "$removed"; then
    echo "removed pre-1.0 API returned: $removed" >&2
    exit 1
  fi
done
if rg -n 'import io\.aura\.Aura;|Aura\.create\(|AuraPlugin|\bReloadable\b|BeforeBuilder' \
    "$repo_root/aura-"*/src/main "$repo_root/aura-"*/src/test "$repo_root/examples"; then
  echo "removed pre-1.0 API found in first-party code" >&2
  exit 1
fi
rg -q 'RouteContract\.schema\(app, compiledRoutes, webSocketContracts\(\)\)' \
  "$repo_root/aura-web/src/main/java/io/aura/web/UndertowStarter.java"
rg -q 'RouteContract\.openApi\(app, compiledRoutes, webSocketContracts\(\)\)' \
  "$repo_root/aura-web/src/main/java/io/aura/web/UndertowStarter.java"
rg -q 'RouteContract\.schema\(app, compiled, webSocketContracts\(\)\)' \
  "$repo_root/aura-web/src/main/java/io/aura/web/TestClient.java"
rg -q 'RouteContract\.openApi\(app, compiled, webSocketContracts\(\)\)' \
  "$repo_root/aura-web/src/main/java/io/aura/web/TestClient.java"
rg -q 'public Map<String, Object> schema\(\)' \
  "$repo_root/aura-web/src/main/java/io/aura/web/TestClient.java"
rg -q 'public Map<String, Object> openApi\(\)' \
  "$repo_root/aura-web/src/main/java/io/aura/web/TestClient.java"
rg -q 'tool\(io\.aura\.contract\.Route route' \
  "$repo_root/aura-mcp/src/main/java/io/aura/mcp/McpRouter.java"

if rg -n '<version>0\.3\.0</version>|<version>0\.5\.1</version>|No classpath scanning' \
    "$repo_root/README.md" "$repo_root/README_CN.md" "$repo_root/docs"; then
  echo "stale framework version or capability claim found" >&2
  exit 1
fi

# Keep the 1.0 authoring path deterministic: deprecated escape hatches must not
# leak back into first-party production code or examples.
if rg -n 'app\.runtime\(\)|app\.lifecycle\(\)' \
    "$repo_root/aura-"*/src/main "$repo_root/examples" "$repo_root/docs" \
    "$repo_root/README.md" "$repo_root/README_CN.md"; then
  echo "deprecated AuraApp runtime/lifecycle API found in first-party contracts" >&2
  exit 1
fi

if rg -n '0\.6\.1|Aura\.create\(|\.plugin\(' "$repo_root/AI_GUIDE.md"; then
  echo "legacy API found in the canonical AI 1.0 guide" >&2
  exit 1
fi
rg -q 'AuraApp\.create\(\)' "$repo_root/AI_GUIDE.md"
if rg -n 'Aura\.create\(|\.plugin\(' "$repo_root/docs/DOCUMENTATION.md"; then
  echo "legacy API found in canonical documentation" >&2
  exit 1
fi
rg -q 'AuraApp\.create\(\)' "$repo_root/docs/DOCUMENTATION.md"
if rg -n 'Aura\.create\(|\.plugin\(' "$repo_root/README.md" "$repo_root/README_CN.md"; then
  echo "legacy API found in the canonical README" >&2
  exit 1
fi
rg -q 'AuraApp\.create\(' "$repo_root/README.md"
rg -q 'AuraApp\.create\(' "$repo_root/README_CN.md"
if rg -n 'Aura\.create\(|AuraPlugin|Aura\.requireAuth' \
    "$repo_root/docs/aura-core.md" "$repo_root/docs/aura-web.md" \
    "$repo_root/docs/aura-module.md" \
    "$repo_root/.claude/skills/aura/SKILL.md"; then
  echo "legacy API found in canonical module documentation or AI skill" >&2
  exit 1
fi
if rg -n '0\.6\.1|Aura\.create\(|\.plugin\(' \
    "$repo_root/AI_GUIDE_DB.md" "$repo_root/AI_GUIDE_MCP.md" "$repo_root/AI_GUIDE_OPS.md"; then
  echo "legacy API found in canonical AI extension guides" >&2
  exit 1
fi
if rg -n 'app\.(register|onStart|onStop|set|getBean)\(|WorkflowDefinition[[:space:]]*$|\.named\("order"\)' \
    "$repo_root/AI_GUIDE_DB.md" "$repo_root/AI_GUIDE_MCP.md" "$repo_root/AI_GUIDE_OPS.md" \
    "$repo_root/README.md" "$repo_root/README_CN.md"; then
  echo "non-compiling pre-1.0 example found in canonical AI documentation" >&2
  exit 1
fi
rg -q 'requireAuth\(String permission\)' \
  "$repo_root/aura-contract/src/main/java/io/aura/contract/Route.java"
if rg -n '\b(RouteContribution|RouteEntry|RouteContributor|PackageScanner|ApiRoute)\b' --glob '*.java' \
    "$repo_root/aura-"*/src "$repo_root/examples"; then
  echo "removed route shape found in first-party code" >&2
  exit 1
fi
if ! rg -q 'RequestGuards\.standard\(app' \
    "$repo_root/aura-web/src/main/java/io/aura/web/UndertowStarter.java" \
    "$repo_root/aura-web/src/main/java/io/aura/web/TestClient.java"; then
  echo "a transport bypasses the shared request guard chain" >&2
  exit 1
fi

# Application entry points: configuration loading belongs to AuraConfig, so the
# facade must not grow a second way in.
if [ "$(rg -c 'public static AuraApp create\(' \
        "$repo_root/aura-core/src/main/java/io/aura/AuraApp.java")" != "2" ]; then
  echo "AuraApp must expose exactly create() and create(AuraConfig)" >&2
  exit 1
fi
if rg -q 'public AuraApp start\(\)' "$repo_root/aura-core/src/main/java/io/aura/AuraApp.java"; then
  echo "AuraApp.start() must stay a single varargs entry point" >&2
  exit 1
fi

if rg -n 'context\.routes\(' \
    "$repo_root/aura-"*/src/main "$repo_root/examples"; then
  echo "deprecated Web-specific ModuleContext route API found in first-party modules" >&2
  exit 1
fi

# Keep bounded-resource and cancellation invariants visible in the release gate.
rg -q 'CancellationToken\.linked' \
  "$repo_root/aura-workflow/src/main/java/io/aura/workflow/WorkflowRuntime.java"
test -f "$repo_root/aura-workflow/src/main/java/io/aura/workflow/DurableWorkflowExecutionStore.java"
rg -q 'saveLeased' \
  "$repo_root/aura-workflow/src/main/java/io/aura/workflow/DurableWorkflowExecutionStore.java"
if rg -n 'saveLeased\(WorkflowExecution[[:space:]]|void save\(WorkflowExecution' \
  "$repo_root/aura-workflow/src/main"; then
  echo "workflow persistence must accept redacted snapshots, not raw executions" >&2
  exit 1
fi
rg -q 'recoveryCandidates' \
  "$repo_root/aura-workflow/src/main/java/io/aura/workflow/WorkflowRuntime.java"
if rg -n '/__schema__/workflows' "$repo_root/aura-workflow/src/main"; then
  echo "workflow module must not bypass the unified schema exposure policy" >&2
  exit 1
fi
rg -q 'onCancel' \
  "$repo_root/aura-db/src/main/java/io/aura/db/Db.java"
rg -q 'independentTransactionMetrics' \
  "$repo_root/aura-db/src/main/java/io/aura/db/Db.java"
rg -q 'executorMetrics' \
  "$repo_root/aura-ai/src/main/java/io/aura/ai/AiClient.java"
if rg -n 'BodyHandlers\.ofString\(' \
    "$repo_root/aura-web/src/main/java/io/aura/web/SnapshotHttpClient.java"; then
  echo "unbounded external response body handler found" >&2
  exit 1
fi
rg -q 'JSON\.toJSONString\(validatedAppUrl\.toString\(\)\)' \
  "$repo_root/aura-mcp/src/main/java/io/aura/mcp/McpPackager.java"
rg -q 'x-aura-permission' \
  "$repo_root/aura-mcp/src/main/java/io/aura/mcp/McpRouter.java"
rg -q 'DEFAULT_MAX_RESPONSE_BYTES' \
  "$repo_root/aura-mcp/src/main/java/io/aura/mcp/McpHttpTransport.java"
rg -q 'MCP_RESPONSE_TOO_LARGE' \
  "$repo_root/aura-mcp/src/main/java/io/aura/mcp/McpHttpTransport.java"
if rg -n 'JSON\.toJSONString' \
    "$repo_root/aura-mcp/src/main/java/io/aura/mcp/McpHttpTransport.java" \
    "$repo_root/aura-mcp/src/main/java/io/aura/mcp/McpSseHub.java"; then
  echo "MCP transport must not allocate unbounded intermediate JSON strings" >&2
  exit 1
fi
rg -q 'BoundedJson\.encode' \
  "$repo_root/aura-mcp/src/main/java/io/aura/mcp/McpHttpTransport.java"
rg -q 'BoundedJson\.encode' \
  "$repo_root/aura-mcp/src/main/java/io/aura/mcp/McpSseHub.java"
rg -q 'annotationProcessorPaths' "$repo_root/examples/aura-generated/pom.xml"
rg -q 'Map<String, Object> responseSchema,' \
  "$repo_root/aura-contract/src/main/java/io/aura/contract/Route.java"
rg -q 'String permission,' \
  "$repo_root/aura-contract/src/main/java/io/aura/contract/Route.java"
# The schema document is a wire format: auth metadata has to survive the projection.
rg -q 'view.put\("authenticated", route.authenticated\(\)\)' \
  "$repo_root/aura-web/src/main/java/io/aura/web/RouteContract.java"
rg -q 'view.put\("permission", route.permission\(\)\)' \
  "$repo_root/aura-web/src/main/java/io/aura/web/RouteContract.java"
rg -q 'route\.permission\(\), route\.authenticated\(\)' \
  "$repo_root/aura-mcp/src/main/java/io/aura/mcp/McpRouter.java"
rg -q 'rateLimitMaxKeys' "$repo_root/aura-core/src/main/java/io/aura/AuraConfig.java"
rg -q 'trustedProxies' "$repo_root/aura-core/src/main/java/io/aura/AuraConfig.java"
rg -q 'aura.tls.keystore-path' "$repo_root/aura-core/src/main/java/io/aura/AuraConfig.java"
rg -q 'maxWebSocketMessageSize' "$repo_root/aura-core/src/main/java/io/aura/AuraConfig.java"
rg -q 'FixedWindowRateLimiter' "$repo_root/aura-web/src/main/java/io/aura/web/RequestGuards.java"
rg -q 'context.admissionPolicy' "$repo_root/docs/aura-web.md"
rg -q 'matchedRoutePattern' "$repo_root/aura-web/src/main/java/io/aura/web/UndertowStarter.java"
rg -q 'void webSocket' "$repo_root/aura-core/src/main/java/io/aura/ModuleContext.java"
# A WebSocket endpoint is a Route: one assembly door, the same auth door, and no OpenAPI operation.
rg -q 'if \(entry\.isWebSocket\(\)\) registerWebSocket' "$repo_root/aura-web/src/main/java/io/aura/web/RouteAssembler.java"
rg -q 'RouteAssembler.secure\(app, contract' "$repo_root/aura-web/src/main/java/io/aura/web/UndertowStarter.java"
rg -q 'if \(route.isWebSocket\(\)\) continue;' "$repo_root/aura-web/src/main/java/io/aura/web/OpenApiGenerator.java"
# Aura's own endpoints are gated in the shared chain, and nowhere else: a second copy in the
# transport is what let the in-memory client claim a protection it did not run.
guard_chain="$repo_root/aura-web/src/main/java/io/aura/web/RequestGuards.java"
rg -q 'chain\.add\(new BuiltinEndpointsGuard\(app\)\)' "$guard_chain"
for transport in UndertowStarter TestClient; do
  if rg -q 'private boolean (schemaAllowed|originAllowed)\(|aura\.schema\.auth-token' \
      "$repo_root/aura-web/src/main/java/io/aura/web/$transport.java"; then
    echo "built-in endpoint gate duplicated in $transport" >&2
    exit 1
  fi
done

# "required" is a promise the runtime keeps, so exactly one rule decides it and every door that
# publishes it enforces it. A second opinion here is how a tool advertised a required argument
# it then let the handler read as 0.
rg -q 'type\.isPrimitive\(\) \|\| "path"\.equals\(source\)' \
  "$repo_root/aura-web/src/main/java/io/aura/web/ParamResolver.java"
rg -q 'ParamResolver\.isRequired\(type, source\)' \
  "$repo_root/aura-web/src/main/java/io/aura/web/RouteCatalog.java"
rg -q 'throw new RequiredParameterException\(name\)' \
  "$repo_root/aura-web/src/main/java/io/aura/web/ParamResolver.java"
for door in McpRouter AuraMcpStarter McpHttpTransport McpBridge; do
  rg -q 'McpMissingArgument' "$repo_root/aura-mcp/src/main/java/io/aura/mcp/$door.java"
done
# A declaration site that hard-codes required=true is a second rule: it publishes a promise the
# binder may not keep. Ask the binder instead.
declaration="$repo_root/aura-web/src/main/java/io/aura/web/RouteCatalog.java"
if rg -q 'new ApiParameter\([^)]*\btrue\b' "$declaration" \
   && ! rg -q 'isRequired\(' "$declaration"; then
  echo "required invented outside the binder rule: $declaration" >&2
  exit 1
fi

# One Java-to-JSON table: a private switch in a consumer is how the same parameter became a
# string here and an integer there.
rg -q 'ScalarType\.jsonTypeName' "$repo_root/aura-web/src/main/java/io/aura/web/OpenApiGenerator.java"
rg -q 'ScalarType\.jsonTypeName' "$repo_root/aura-web/src/main/java/io/aura/web/RouteContract.java"
rg -q 'ScalarType\.jsonTypeName' "$repo_root/aura-mcp/src/main/java/io/aura/mcp/McpUtil.java"
if rg -q 'case "int", "Integer"' "$repo_root/aura-web/src/main" "$repo_root/aura-mcp/src/main"; then
  echo "a Java-to-JSON type table was recreated outside ScalarType" >&2
  exit 1
fi
if [ "$(rg -c 'public McpParam\(' "$repo_root/aura-mcp/src/main/java/io/aura/mcp/McpParam.java")" != "1" ]; then
  echo "McpParam grew a second way to declare an argument besides ApiParameter" >&2
  exit 1
fi
if ! rg -q 'public McpParam\(ApiParameter contract\)' \
    "$repo_root/aura-mcp/src/main/java/io/aura/mcp/McpParam.java"; then
  echo "McpParam takes something other than the shared ApiParameter contract again" >&2
  exit 1
fi

# One door publishes a tool. The builder was a second description of the same parameters, and the
# two descriptions disagreed: the same enum published "NEW(新建)" through the builder and "NEW"
# through a route, and a route handed over beside a separate handler had its own handler ignored.
for deleted_surface in McpToolBuilder McpEnumValue; do
  if test -f "$repo_root/aura-mcp/src/main/java/io/aura/mcp/$deleted_surface.java"; then
    echo "the second MCP declaration door returned: $deleted_surface" >&2
    exit 1
  fi
done
if [ "$(rg -c 'public McpRouter tool\(' "$repo_root/aura-mcp/src/main/java/io/aura/mcp/McpRouter.java")" != "1" ]; then
  echo "McpRouter grew a second tool registration door" >&2
  exit 1
fi
if ! rg -q 'AURA_MCP_HANDLER_MISMATCH' \
    "$repo_root/aura-mcp/src/main/java/io/aura/mcp/McpRouter.java"; then
  echo "McpRouter accepts a route whose handler it cannot invoke without naming the mistake" >&2
  exit 1
fi
# A parameter the shared table cannot render is refused where every tool argument is built --
# both the route door and the bridge pass through McpParam, so neither can publish a record to a
# model as a string it cannot send.
if ! rg -q 'McpUtil\.rendersWithoutSchema' "$repo_root/aura-mcp/src/main/java/io/aura/mcp/McpParam.java" \
   || ! rg -q 'AURA_MCP_PARAM_SCHEMA_MISSING' "$repo_root/aura-mcp/src/main/java/io/aura/mcp/McpParam.java"; then
  echo "an undescribable MCP parameter reaches the model as a string again" >&2
  exit 1
fi
# Enum choices and body shapes are projected, never invented: the schema the route catalog and
# the annotation processor attach is the only source of an MCP "enum" list.
if rg -q 'prop\.put\("enum"' "$repo_root/aura-mcp/src/main/java/io/aura/mcp/McpUtil.java"; then
  echo "MCP invented enum values instead of projecting the contract's schema" >&2
  exit 1
fi
if ! rg -q '\? TypeSchema\.describe\(type\) : Map\.of\(\)' \
    "$repo_root/aura-web/src/main/java/io/aura/web/RouteCatalog.java"; then
  echo "the route catalog stopped declaring the parameter schema that MCP and OpenAPI project" >&2
  exit 1
fi
if ! rg -q 'bodySchema\(parameter\.asType\(\)\)' \
    "$repo_root/aura-processor/src/main/java/io/aura/processor/AuraRouteProcessor.java"; then
  echo "the annotation processor stopped declaring the parameter schema that MCP and OpenAPI project" >&2
  exit 1
fi

# tools/list is the third projection of the same Route contract. Reading it back with an unchecked
# cast turns a renamed key into a null that the next assertion compares against something else, so
# the MCP schema tests read it through McpWire, which names the keys the document actually has. The
# one remaining suppression is an in-Java handler result, not a wire document.
for reader in McpRouterTest McpRouterIntegrationTest McpUtilTest McpOutputTest; do
  if ! rg -q 'McpWire' "$repo_root/aura-mcp/src/test/java/io/aura/mcp/$reader.java"; then
    echo "MCP schema tests read the tools/list document with unchecked casts again: $reader" >&2
    exit 1
  fi
done
unchecked="$(rg -o 'SuppressWarnings\("unchecked"\)' "$repo_root/aura-mcp/src" 2>/dev/null | wc -l | tr -d ' ')"
if [ "$unchecked" -gt 1 ]; then
  echo "the MCP module carries $unchecked unchecked casts; its wire documents are read through McpWire" >&2
  exit 1
fi
if rg -q 'toJavaObject\(Map\.class\)' "$repo_root/aura-mcp/src/main"; then
  echo "the MCP module cast a JSON object into a raw Map instead of using the Map it already is" >&2
  exit 1
fi

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

# The same table binds as it renders: the string-to-value rules live next to the JSON names they
# match, so a type that says "integer" cannot bind to a string on one transport and 0 on another.
# A parse method re-added to the adapter, or the bridge reading the binder, is that drift.
rg -q 'ScalarType\.fromString' "$repo_root/aura-web/src/main/java/io/aura/web/ParamResolver.java"
rg -q 'ScalarType\.fromString' "$repo_root/aura-mcp/src/main/java/io/aura/mcp/McpContext.java"
if rg -qn 'public static [A-Za-z]+(Boxed)? parse[A-Z]' \
    "$repo_root/aura-web/src/main/java/io/aura/web/ParamResolver.java"; then
  echo "the string-to-value table left ScalarType again: ParamResolver owns a parse method" >&2
  exit 1
fi
if rg -qn '^import io\.aura\.web\.' "$repo_root/aura-mcp/src/main"; then
  echo "aura-mcp main code reaches into the Web adapter" >&2
  exit 1
fi
if ! rg -Uqn 'aura-web</artifactId>[[:space:]]*<scope>test</scope>' "$repo_root/aura-mcp/pom.xml" \
   || rg -Uqn 'aura-web</artifactId>[[:space:]]*</dependency>' "$repo_root/aura-mcp/pom.xml"; then
  echo "aura-mcp must depend on aura-web in test scope only: the bridge is a sibling of the adapter" >&2
  exit 1
fi
if ! test -f "$repo_root/aura-mcp/src/test/java/io/aura/mcp/McpLayeringTest.java"; then
  echo "R6 has no gate that can fire: aura-mcp must run McpLayeringTest over its own classpath" >&2
  exit 1
fi
if rg -qn '\.allowEmptyShould\(true\)' "$repo_root/aura-core/src/test/java/io/aura/ArchitectureTest.java" \
    "$repo_root/aura-web/src/test/java/io/aura/web/WebLayeringTest.java" \
    "$repo_root/aura-mcp/src/test/java/io/aura/mcp/McpLayeringTest.java"; then
  echo "a layering rule was relaxed to pass with an empty subject set" >&2
  exit 1
fi

# An assembly mistake has to name itself. A bare NullPointerException from requireNonNull points
# at the constructor frame, not at the route or the module that contributed it.
rg -q 'AURA_ROUTE_HANDLER_MISSING' "$repo_root/aura-contract/src/main/java/io/aura/contract/Route.java"
if rg -q 'requireNonNull\(handler' "$repo_root/aura-contract/src/main/java/io/aura/contract/Route.java"; then
  echo "a handlerless route contribution lost the route it named" >&2
  exit 1
fi
rg -q 'AURA_ROUTE_CONTRIBUTION_INVALID' "$repo_root/aura-core/src/main/java/io/aura/ModularRuntime.java"

# Aura's own endpoint documents are read parsed and typed in tests. An unchecked cast hands a
# renamed key to the next assertion as null, and a substring of the whole document matches text
# that has nothing to do with the field its name claims.
for test_tree in "$repo_root/aura-web/src/test" "$repo_root/examples/aura-generated/src/test"; do
  if rg -q 'client\.(schema|openApi)\(\)\.get\(|\(List<Map<String, Object>>\) client\.|client\.(schema|openApi)\(\)\.toString\(\)\)\.contains\(' \
      "$test_tree"; then
    echo "a route document was read as a cast or a substring instead of SchemaWire: $test_tree" >&2
    exit 1
  fi
done
# A test with no assertions is a probe that always passes.
if [ -e "$repo_root/aura-web/src/test/java/io/aura/web/SchemaProbeTest.java" ]; then
  echo "SchemaProbeTest returned: it printed the schema document and asserted nothing" >&2
  exit 1
fi

# Layering close-out: the policy ports left the adapter's package, so io.aura.web means Undertow
# and nothing else. The split package must not come back -- while it existed, ArchUnit could not
# forbid "io.aura.web.." without banning the ports themselves, and WsHandler's callbacks were
# readable by the adapter simply because the two modules shared a package name.
ports='(BaseContext|BaseHandler|BaseRouter|BaseRouteBuilder|BaseExceptionHandler|SseEmitter|UploadedFile|RouteResponse|RequestType|WsHandler|WsConnection|AdmissionPolicy|AdmissionRequest|AdmissionDecision)'
for port in BaseContext BaseHandler BaseRouter BaseRouteBuilder BaseExceptionHandler \
            SseEmitter UploadedFile RouteResponse RequestType WsHandler WsConnection \
            AdmissionPolicy AdmissionRequest AdmissionDecision; do
  if ! test -f "$repo_root/aura-core/src/main/java/io/aura/port/$port.java"; then
    echo "policy port is not in io.aura.port: $port" >&2
    exit 1
  fi
done
for tree in aura-core/src/main/java aura-core/src/test/java; do
  if test -e "$repo_root/$tree/io/aura/web"; then
    echo "the split io.aura.web package returned in $tree; core ports live in io.aura.port" >&2
    exit 1
  fi
done
if rg -qn "io\.aura\.web\.$ports" --glob '*.java' \
    "$repo_root/aura-"*/src "$repo_root/examples"; then
  echo "a policy port was addressed through the adapter package: io.aura.web is mechanism" >&2
  exit 1
fi
if rg -n "io\.aura\.web\.$ports" \
    "$repo_root/README.md" "$repo_root/README_CN.md" "$repo_root/AI_GUIDE.md" \
    "$repo_root/docs/aura-core.md" "$repo_root/docs/aura-web.md" "$repo_root/docs/aura-module.md" \
    "$repo_root/docs/DOCUMENTATION.md" "$repo_root/.claude/skills/aura/SKILL.md"; then
  echo "canonical documentation still points a policy port at io.aura.web" >&2
  exit 1
fi
ws_handler="$repo_root/aura-core/src/main/java/io/aura/port/WsHandler.java"
if ! rg -q 'private Consumer<WsConnection> onOpen;' "$ws_handler" \
   || rg -qn '^    (Consumer|BiConsumer|CloseHandler|ErrorHandler) on(Open|Message|Close|Error);' "$ws_handler"; then
  echo "WsHandler callbacks are package-private again: the adapter reads them through accessors" >&2
  exit 1
fi
rg -q 'openHandler()' "$repo_root/aura-web/src/main/java/io/aura/web/CompiledWsRoute.java"

# Stage K: cron is one contribution door, its fire times come from the wall clock,
# and every way it can fail is coded and documented.
if [ "$(rg -c 'void cron\(' "$repo_root/aura-core/src/main/java/io/aura/ModuleContext.java")" != "1" ]; then
  echo "ModuleContext no longer offers exactly one scheduling door" >&2
  exit 1
fi
for door in AuraApp AuraRuntime; do
  if rg -q '\b(cron|schedule|scheduler)\(' "$repo_root/aura-core/src/main/java/io/aura/$door.java"; then
    echo "a second scheduling door opened in $door: cron is contributed through ModuleContext" >&2
    exit 1
  fi
done
if rg -q 'scheduleAtFixedRate|scheduleWithFixedDelay|new (java\.util\.)?Timer\(' \
    "$repo_root/aura-core/src/main/java"; then
  echo "cron was armed on a fixed-rate loop: fire times come from CronSchedule.next, so the schedule cannot drift off the wall clock" >&2
  exit 1
fi
if ! rg -q 'setDaemon\(true\)' "$repo_root/aura-core/src/main/java/io/aura/CronScheduler.java"; then
  echo "the cron pool is not on daemon threads again: a running timer would keep the JVM alive" >&2
  exit 1
fi
for code in AURA_CRON_CONTRIBUTION_INVALID AURA_CRON_EXPRESSION_INVALID \
            AURA_CRON_CONTRIBUTION_TOO_LATE AURA_CRON_TASK_FAILED; do
  if ! rg -q "\"$code\"" "$repo_root/aura-core/src/main/java/io/aura/ModularRuntime.java"; then
    echo "cron stopped failing with $code" >&2
    exit 1
  fi
  if ! rg -q "$code" "$repo_root/docs/aura-module.md"; then
    echo "cron failure $code is no longer documented" >&2
    exit 1
  fi
done
if ! rg -q 'runtime\.cron\.failure' "$repo_root/aura-core/src/main/java/io/aura/ModularRuntime.java"; then
  echo "a failed cron task is no longer observable as a runtime.cron.failure event" >&2
  exit 1
fi

echo "Aura module and documentation contracts passed (version $root_version)"
