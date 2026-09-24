#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
root_version="$(sed -n 's:.*<version>\([^<]*\)</version>.*:\1:p' "$repo_root/pom.xml" | head -1)"
test -n "$root_version"
for command_name in gpg gpgconf mvn awk; do
  command -v "$command_name" >/dev/null || {
    echo "Release signature verification requires $command_name" >&2
    exit 1
  }
done

gpg_home="$(mktemp -d "${TMPDIR:-/tmp}/aura-release-gpg.XXXXXX")"
chmod 700 "$gpg_home"
cleanup() {
  GNUPGHOME="$gpg_home" gpgconf --kill gpg-agent >/dev/null 2>&1 || true
  rm -rf -- "$gpg_home"
}
trap cleanup EXIT

gpg --homedir "$gpg_home" --batch --pinentry-mode loopback --passphrase '' \
  --quick-generate-key \
  'Aura CI Release Verification <aura-ci@example.invalid>' rsa2048 sign 1d
fingerprint="$(gpg --homedir "$gpg_home" --batch --with-colons \
  --list-secret-keys | awk -F: '$1 == "fpr" { print $10; exit }')"
test -n "$fingerprint"

modules=(
  aura-contract aura-core aura-processor aura-web aura-db
  aura-db-migration-flyway aura-db-migration-liquibase
  aura-mcp aura-ai aura-workflow aura-bom aura-starter aura-archetype
)
module_list="$(IFS=,; echo "${modules[*]}")"
maven=(mvn)
if test -n "${MAVEN_SETTINGS:-}"; then
  maven+=(-s "$MAVEN_SETTINGS")
fi

GNUPGHOME="$gpg_home" "${maven[@]}" -q -f "$repo_root/pom.xml" \
  -pl "$module_list" -am -Prelease -DskipTests \
  -Dgpg.keyname="$fingerprint" verify

verified=0
artifacts=()
verify_artifact() {
  local artifact="$1"
  local signature="$artifact.asc"
  test -s "$artifact"
  test -s "$signature"
  gpg --homedir "$gpg_home" --batch --verify "$signature" "$artifact" \
    >/dev/null 2>&1
  artifacts+=("$artifact" "$signature")
  verified=$((verified + 1))
}

verify_artifact "$repo_root/target/aura-parent-$root_version.pom"
verify_artifact "$repo_root/aura-bom/target/aura-bom-$root_version.pom"
for module in "${modules[@]}"; do
  if test "$module" = aura-bom; then continue; fi
  base="$repo_root/$module/target/$module-$root_version"
  verify_artifact "$base.pom"
  verify_artifact "$base.jar"
  verify_artifact "$base-sources.jar"
  verify_artifact "$base-javadoc.jar"
done

checksum_manifest="$repo_root/target/aura-release-checksums.sha256"
mkdir -p "$(dirname "$checksum_manifest")"
checksum_file() {
  if command -v sha256sum >/dev/null; then
    sha256sum "$1"
  elif command -v shasum >/dev/null; then
    shasum -a 256 "$1"
  else
    echo "Release checksum verification requires sha256sum or shasum" >&2
    return 1
  fi
}
verify_checksum_manifest() {
  if command -v sha256sum >/dev/null; then
    sha256sum -c "$1"
  elif command -v shasum >/dev/null; then
    shasum -a 256 -c "$1"
  else
    echo "Release checksum verification requires sha256sum or shasum" >&2
    return 1
  fi
}
if ! command -v sha256sum >/dev/null && ! command -v shasum >/dev/null; then
  echo "Release checksum verification requires sha256sum or shasum" >&2
  exit 1
fi
(
  cd "$repo_root"
  for artifact in "${artifacts[@]}"; do
    relative="${artifact#"$repo_root/"}"
    checksum_file "$relative"
  done
) | LC_ALL=C sort >"$checksum_manifest"
test -s "$checksum_manifest"
(
  cd "$repo_root"
  verify_checksum_manifest "$checksum_manifest"
) >/dev/null

echo "Aura release signatures and checksums passed: $verified artifacts verified"
