#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
test -f "$repo_root/evals/tasks.json"
test -f "$repo_root/evals/result.schema.json"

mvn -q -f "$repo_root/pom.xml" \
  -pl aura-web,aura-db,aura-mcp,aura-workflow,aura-ai,examples/aura-generated -am \
  -Dtest='AiEvaluationManifestTest,ServiceTest,GeneratedAppTest,PermissionAuthorizationTest,AuthTest,BodyOrThrowTest,BeanValidatorTest,DbIntegrationTest,DatabaseModuleTest,FormParsingSecurityTest,ContextTest,TestClientTest,WebSocketTest,McpHttpTransportTest,McpSecurityTest,WorkflowTest,AiClientTest,AiBudgetEnforcementTest,StructuredOutputTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test

echo "Aura AI golden evaluation tasks passed"
