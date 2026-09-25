#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

echo "[1/9] Maven reactor"
mvn -q verify --fail-at-end

echo "[2/9] Aura contracts"
bash scripts/verify-aura-contracts.sh

echo "[3/9] AI evaluations"
bash scripts/verify-ai-evals.sh

echo "[4/9] Documentation code"
bash scripts/verify-docs-code.sh

echo "[5/9] Public API baseline"
bash scripts/verify-public-api.sh

echo "[6/9] SBOM"
bash scripts/verify-sbom.sh

echo "[7/9] Release artifact shape"
bash scripts/verify-release-artifacts.sh

echo "[8/9] Release signatures and checksums"
bash scripts/verify-release-signatures.sh

echo "[9/9] MCP npm smoke"
node scripts/verify-mcp-npm.mjs

git diff --check
echo "Aura local 1.0 readiness gates passed"
