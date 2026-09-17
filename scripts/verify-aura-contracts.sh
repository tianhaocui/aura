#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
pom="$repo_root/pom.xml"
root_version="$(sed -n 's:.*<version>\([^<]*\)</version>.*:\1:p' "$pom" | head -1)"
test -n "$root_version"
rg -q '<undertow.version>2\.3\.(2[1-9]|[3-9][0-9])\.Final</undertow.version>' "$pom"
rg -q '<parameters>true</parameters>' "$pom"
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

test -f "$repo_root/aura-ai/src/main/java/io/aura/ai/AiClient.java"
test -f "$repo_root/aura-workflow/src/main/java/io/aura/workflow/WorkflowModule.java"
test -f "$repo_root/AI_GUIDE.md"
test -f "$repo_root/docs/legacy-ai-guide-0.6.md"
test -f "$repo_root/docs/aura-module.md"
test -f "$repo_root/docs/aura-workflow.md"
test -f "$repo_root/docs/migration-to-1.0.md"
test -f "$repo_root/docs/DOCUMENTATION.md"
test -f "$repo_root/docs/legacy-documentation-0.6.md"
test -f "$repo_root/aura-processor/src/main/java/io/aura/processor/AuraRouteProcessor.java"
test -f "$repo_root/aura-processor/src/main/resources/META-INF/services/javax.annotation.processing.Processor"
test -f "$repo_root/examples/aura-composed/src/main/java/example/ComposedApp.java"
test -f "$repo_root/examples/aura-composed/src/test/java/example/ComposedAppTest.java"
test -f "$repo_root/examples/aura-generated/pom.xml"
test -f "$repo_root/examples/aura-generated/src/test/java/example/generated/GeneratedAppTest.java"
test -f "$repo_root/aura-web/src/main/java/io/aura/web/RouteContract.java"
for removed in \
  "$repo_root/aura-core/src/main/java/io/aura/Aura.java" \
  "$repo_root/aura-core/src/main/java/io/aura/AuraPlugin.java" \
  "$repo_root/aura-core/src/main/java/io/aura/Reloadable.java" \
  "$repo_root/aura-core/src/main/java/io/aura/ReloadState.java" \
  "$repo_root/aura-core/src/main/java/io/aura/web/BeforeBuilder.java"; do
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
rg -q 'RouteContract\.schema\(app, compiledRoutes\)' \
  "$repo_root/aura-web/src/main/java/io/aura/web/UndertowStarter.java"
rg -q 'RouteContract\.openApi\(app, compiledRoutes\)' \
  "$repo_root/aura-web/src/main/java/io/aura/web/UndertowStarter.java"
rg -q 'public Map<String, Object> schema\(\)' \
  "$repo_root/aura-web/src/main/java/io/aura/web/TestClient.java"
rg -q 'public Map<String, Object> openApi\(\)' \
  "$repo_root/aura-web/src/main/java/io/aura/web/TestClient.java"
rg -q 'tool\(io\.aura\.contract\.ApiRoute route' \
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
    "$repo_root/.claude/skills/aura/SKILL.md"; then
  echo "legacy API found in canonical module documentation or AI skill" >&2
  exit 1
fi
if rg -n '0\.6\.1|Aura\.create\(|\.plugin\(' \
    "$repo_root/AI_GUIDE_DB.md" "$repo_root/AI_GUIDE_MCP.md" "$repo_root/AI_GUIDE_OPS.md"; then
  echo "legacy API found in canonical AI extension guides" >&2
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
rg -q 'onCancel' \
  "$repo_root/aura-db/src/main/java/io/aura/db/Db.java"
if rg -n 'BodyHandlers\.ofString\(' \
    "$repo_root/aura-web/src/main/java/io/aura/web/SnapshotHttpClient.java"; then
  echo "unbounded external response body handler found" >&2
  exit 1
fi
rg -q 'JSON\.toJSONString\(validatedAppUrl\.toString\(\)\)' \
  "$repo_root/aura-mcp/src/main/java/io/aura/mcp/McpPackager.java"
rg -q 'x-aura-permission' \
  "$repo_root/aura-mcp/src/main/java/io/aura/mcp/McpRouter.java"
rg -q 'permission\(String permission\)' \
  "$repo_root/aura-mcp/src/main/java/io/aura/mcp/McpToolBuilder.java"
rg -q 'DEFAULT_MAX_RESPONSE_BYTES' \
  "$repo_root/aura-mcp/src/main/java/io/aura/mcp/McpHttpTransport.java"
rg -q 'MCP_RESPONSE_TOO_LARGE' \
  "$repo_root/aura-mcp/src/main/java/io/aura/mcp/McpHttpTransport.java"
rg -q 'annotationProcessorPaths' "$repo_root/examples/aura-generated/pom.xml"
rg -q 'responseSchema' "$repo_root/aura-contract/src/main/java/io/aura/contract/ApiRoute.java"
rg -q 'rateLimitMaxKeys' "$repo_root/aura-core/src/main/java/io/aura/AuraConfig.java"
rg -q 'trustedProxies' "$repo_root/aura-core/src/main/java/io/aura/AuraConfig.java"
rg -q 'aura.tls.keystore-path' "$repo_root/aura-core/src/main/java/io/aura/AuraConfig.java"
rg -q 'FixedWindowRateLimiter' "$repo_root/aura-web/src/main/java/io/aura/web/UndertowStarter.java"
rg -q 'void webSocket' "$repo_root/aura-core/src/main/java/io/aura/ModuleContext.java"
rg -q 'webSocketContributions' "$repo_root/aura-web/src/main/java/io/aura/web/RouteAssembler.java"

echo "Aura module and documentation contracts passed (version $root_version)"
