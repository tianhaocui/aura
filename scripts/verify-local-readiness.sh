#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

echo "[1/12] Maven reactor"
mvn -q verify --fail-at-end

echo "[2/12] Aura packaging and wiring contracts"
bash scripts/verify-aura-contracts.sh

echo "[3/12] Architecture boundary contracts"
bash scripts/verify-architecture.sh

echo "[4/12] AI evaluations"
bash scripts/verify-ai-evals.sh

echo "[5/12] Application manifest drift"
bash scripts/verify-manifest.sh

echo "[6/12] Documentation code"
bash scripts/verify-docs-code.sh

echo "[7/12] Public API baseline"
bash scripts/verify-public-api.sh

echo "[8/12] Maven archetype and executable JAR smoke"
bash scripts/verify-maven-archetype.sh

echo "[9/12] SBOM"
bash scripts/verify-sbom.sh

echo "[10/12] Release artifact shape"
bash scripts/verify-release-artifacts.sh

echo "[11/12] Release signatures and checksums"
bash scripts/verify-release-signatures.sh

echo "[12/12] MCP npm smoke"
node scripts/verify-mcp-npm.mjs

git diff --check
echo "Aura local 1.0 readiness gates passed"
