#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

echo "[1/10] Maven reactor"
mvn -q verify --fail-at-end

echo "[2/10] Aura contracts"
bash scripts/verify-aura-contracts.sh

echo "[3/10] AI evaluations"
bash scripts/verify-ai-evals.sh

echo "[4/10] Documentation code"
bash scripts/verify-docs-code.sh

echo "[5/10] Public API baseline"
bash scripts/verify-public-api.sh

echo "[6/10] Maven archetype and executable JAR smoke"
bash scripts/verify-maven-archetype.sh

echo "[7/10] SBOM"
bash scripts/verify-sbom.sh

echo "[8/10] Release artifact shape"
bash scripts/verify-release-artifacts.sh

echo "[9/10] Release signatures and checksums"
bash scripts/verify-release-signatures.sh

echo "[10/10] MCP npm smoke"
node scripts/verify-mcp-npm.mjs

git diff --check
echo "Aura local 1.0 readiness gates passed"
