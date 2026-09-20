#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
work_dir="$(mktemp -d "${TMPDIR:-/tmp}/aura-archetype.XXXXXX")"

cleanup() {
  rm -rf -- "$work_dir"
}
trap cleanup EXIT

maven=(mvn)
if test -n "${MAVEN_SETTINGS:-}"; then
  maven+=(-s "$MAVEN_SETTINGS")
fi

"${maven[@]}" -q -f "$repo_root/pom.xml" \
  -pl aura-bom,aura-starter,aura-archetype -am \
  -DskipTests install

(
  cd "$work_dir"
  "${maven[@]}" -q \
    org.apache.maven.plugins:maven-archetype-plugin:3.4.1:generate \
    -B \
    -DarchetypeCatalog=local \
    -DarchetypeGroupId=io.github.tianhaocui \
    -DarchetypeArtifactId=aura-archetype \
    -DarchetypeVersion=1.0.0 \
    -DgroupId=dev.aura.generated \
    -DartifactId=aura-smoke \
    -Dversion=1.0.0-SNAPSHOT \
    -Dpackage=dev.aura.generated \
    -DauraVersion=1.0.0 \
    -DjavaVersion=17
)

generated="$work_dir/aura-smoke"
test -f "$generated/src/main/java/dev/aura/generated/App.java"
test -f "$generated/src/test/java/dev/aura/generated/AppTest.java"
"${maven[@]}" -q -f "$generated/pom.xml" verify

jar_file="$generated/target/aura-smoke-1.0.0-SNAPSHOT.jar"
test -f "$jar_file"
unzip -p "$jar_file" META-INF/MANIFEST.MF | tr -d '\r' \
  | rg -q '^Main-Class: dev\.aura\.generated\.App$'
unzip -p "$jar_file" META-INF/services/io.aura.AuraStarter \
  | rg -q '^io\.aura\.web\.UndertowStarter$'

echo "Aura BOM, starter, and generated archetype project passed"
