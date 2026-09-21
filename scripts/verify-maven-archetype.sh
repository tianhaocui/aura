#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
preserve_work_dir=false
if test -n "${AURA_ARCHETYPE_WORK_DIR:-}"; then
  work_dir="$AURA_ARCHETYPE_WORK_DIR"
  mkdir "$work_dir"
  preserve_work_dir=true
else
  work_dir="$(mktemp -d "${TMPDIR:-/tmp}/aura-archetype.XXXXXX")"
fi
app_pid=""
root_version="$(sed -n 's:.*<version>\([^<]*\)</version>.*:\1:p' "$repo_root/pom.xml" | head -1)"
local_repo="$work_dir/m2"

terminate_process() {
  local pid="$1"
  if ! kill -0 "$pid" 2>/dev/null; then return; fi
  kill -TERM "$pid" 2>/dev/null || true
  for _ in {1..40}; do
    if ! kill -0 "$pid" 2>/dev/null; then
      wait "$pid" 2>/dev/null || true
      return
    fi
    sleep 0.25
  done
  kill -KILL "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
}

cleanup() {
  if test -n "$app_pid" && kill -0 "$app_pid" 2>/dev/null; then
    terminate_process "$app_pid"
  fi
  if test "$preserve_work_dir" != true; then
    rm -rf -- "$work_dir"
  fi
}
trap cleanup EXIT

maven=(mvn "-Dmaven.repo.local=$local_repo")
if test -n "${MAVEN_SETTINGS:-}"; then
  maven+=(-s "$MAVEN_SETTINGS")
fi

"${maven[@]}" -q -f "$repo_root/pom.xml" \
  -pl aura-bom,aura-starter,aura-archetype,aura-contract,aura-core,aura-web,aura-db,\
aura-db-migration-flyway,aura-db-migration-liquibase,aura-workflow,aura-ai,aura-mcp,aura-processor -am \
  -DskipTests install

consumer="$work_dir/consumer"
mkdir -p "$consumer"
cat > "$consumer/pom.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>dev.aura.consumer</groupId>
  <artifactId>aura-consumer</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <dependencyManagement><dependencies>
    <dependency>
      <groupId>io.github.tianhaocui</groupId><artifactId>aura-bom</artifactId>
      <version>$root_version</version><type>pom</type><scope>import</scope>
    </dependency>
  </dependencies></dependencyManagement>
  <dependencies>
    <dependency><groupId>io.github.tianhaocui</groupId><artifactId>aura-contract</artifactId></dependency>
    <dependency><groupId>io.github.tianhaocui</groupId><artifactId>aura-core</artifactId></dependency>
    <dependency><groupId>io.github.tianhaocui</groupId><artifactId>aura-web</artifactId></dependency>
    <dependency><groupId>io.github.tianhaocui</groupId><artifactId>aura-db</artifactId></dependency>
    <dependency><groupId>io.github.tianhaocui</groupId><artifactId>aura-db-migration-flyway</artifactId></dependency>
    <dependency><groupId>io.github.tianhaocui</groupId><artifactId>aura-db-migration-liquibase</artifactId></dependency>
    <dependency><groupId>io.github.tianhaocui</groupId><artifactId>aura-workflow</artifactId></dependency>
    <dependency><groupId>io.github.tianhaocui</groupId><artifactId>aura-ai</artifactId></dependency>
    <dependency><groupId>io.github.tianhaocui</groupId><artifactId>aura-mcp</artifactId></dependency>
    <dependency><groupId>io.github.tianhaocui</groupId><artifactId>aura-processor</artifactId></dependency>
    <dependency><groupId>io.github.tianhaocui</groupId><artifactId>aura-starter</artifactId></dependency>
  </dependencies>
</project>
EOF
"${maven[@]}" -q -f "$consumer/pom.xml" dependency:tree -DoutputFile="$consumer/tree.txt"
for artifact in aura-contract aura-core aura-web aura-db aura-db-migration-flyway \
  aura-db-migration-liquibase aura-workflow aura-ai aura-mcp aura-processor aura-starter; do
  rg -q "io.github.tianhaocui:$artifact:.*:$root_version" "$consumer/tree.txt"
done

bom_only="$work_dir/bom-only"
mkdir -p "$bom_only"
cat > "$bom_only/pom.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>dev.aura.consumer</groupId><artifactId>aura-bom-only</artifactId><version>1.0.0-SNAPSHOT</version>
  <dependencyManagement><dependencies><dependency>
    <groupId>io.github.tianhaocui</groupId><artifactId>aura-bom</artifactId><version>$root_version</version>
    <type>pom</type><scope>import</scope>
  </dependency></dependencies></dependencyManagement>
</project>
EOF
"${maven[@]}" -q -f "$bom_only/pom.xml" dependency:tree -DoutputFile="$bom_only/tree.txt"
if rg -q 'io.github.tianhaocui:aura-' "$bom_only/tree.txt"; then
  echo "importing aura-bom must not install runtime dependencies" >&2
  exit 1
fi

starter_tree="$work_dir/starter-tree"
mkdir -p "$starter_tree"
cat > "$starter_tree/pom.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>dev.aura.consumer</groupId><artifactId>aura-starter-only</artifactId><version>1.0.0-SNAPSHOT</version>
  <dependencyManagement><dependencies><dependency>
    <groupId>io.github.tianhaocui</groupId><artifactId>aura-bom</artifactId><version>$root_version</version>
    <type>pom</type><scope>import</scope>
  </dependency></dependencies></dependencyManagement>
  <dependencies><dependency><groupId>io.github.tianhaocui</groupId><artifactId>aura-starter</artifactId></dependency></dependencies>
</project>
EOF
"${maven[@]}" -q -f "$starter_tree/pom.xml" dependency:tree -DoutputFile="$starter_tree/tree.txt"
if rg -n 'aura-(db|ai|mcp|workflow)' "$starter_tree/tree.txt"; then
  echo "aura-starter must not install optional capabilities" >&2
  exit 1
fi

(
  cd "$work_dir"
  "${maven[@]}" -q \
    org.apache.maven.plugins:maven-archetype-plugin:3.4.1:generate \
    -B \
    -DarchetypeCatalog=local \
    -DarchetypeGroupId=io.github.tianhaocui \
    -DarchetypeArtifactId=aura-archetype \
    -DarchetypeVersion="$root_version" \
    -DgroupId=dev.aura.generated \
    -DartifactId=Aura_Smoke \
    -Dversion=1.0.0-SNAPSHOT \
    -Dpackage=dev.aura.generated \
    -DauraVersion="$root_version" \
    -DjavaVersion=17 \
    -DdeploymentName=aura-smoke
)

generated="$work_dir/Aura_Smoke"
test -f "$generated/src/main/java/dev/aura/generated/App.java"
test -f "$generated/src/test/java/dev/aura/generated/AppTest.java"
test -f "$generated/Dockerfile"
test -f "$generated/.dockerignore"
test -f "$generated/k8s/aura.yaml"
if rg '^  name: ' "$generated/k8s/aura.yaml" \
    | awk -F': ' '{print $2}' \
    | rg -v '^[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?$'; then
  echo "generated Kubernetes names are not normalized" >&2
  exit 1
fi
rg -q 'runAsNonRoot: true' "$generated/k8s/aura.yaml"
rg -q 'path: /health/live' "$generated/k8s/aura.yaml"
rg -q 'path: /health/ready' "$generated/k8s/aura.yaml"
rg -q 'terminationGracePeriodSeconds: 40' "$generated/k8s/aura.yaml"
rg -q '^USER 10001:10001$' "$generated/Dockerfile"
rg -q 'type=secret,id=maven_settings' "$generated/Dockerfile"
"${maven[@]}" -q -f "$generated/pom.xml" verify

jar_file="$generated/target/Aura_Smoke-1.0.0-SNAPSHOT.jar"
test -f "$jar_file"
unzip -p "$jar_file" META-INF/MANIFEST.MF | tr -d '\r' \
  | rg -q '^Main-Class: dev\.aura\.generated\.App$'
unzip -p "$jar_file" META-INF/services/io.aura.AuraStarter \
  | rg -q '^io\.aura\.web\.UndertowStarter$'

smoke_port=$((20000 + ($$ % 10000)))
server_log="$work_dir/server.log"
AURA_PORT="$smoke_port" \
AURA_ENV=production \
AURA_PUBLIC_ORIGIN="http://127.0.0.1:$smoke_port" \
AURA_ALLOWED_HOSTS="127.0.0.1,localhost" \
  java -jar "$jar_file" >"$server_log" 2>&1 &
app_pid=$!

started=false
for _ in {1..60}; do
  if curl --fail --silent --show-error --max-time 1 \
      "http://127.0.0.1:$smoke_port/health/ready" >"$work_dir/ready.json" 2>/dev/null; then
    started=true
    break
  fi
  if ! kill -0 "$app_pid" 2>/dev/null; then
    cat "$server_log" >&2
    echo "Generated Aura application exited before becoming ready" >&2
    exit 1
  fi
  sleep 0.25
done
if test "$started" != true; then
  cat "$server_log" >&2
  echo "Generated Aura application did not become ready" >&2
  exit 1
fi

test "$(curl --fail --silent --show-error --max-time 2 \
  "http://127.0.0.1:$smoke_port/hello")" = "hello"
rg -q '"status":"UP"' "$work_dir/ready.json"

kill -TERM "$app_pid"
for _ in {1..40}; do
  if ! kill -0 "$app_pid" 2>/dev/null; then break; fi
  sleep 0.25
done
if kill -0 "$app_pid" 2>/dev/null; then
  cat "$server_log" >&2
  echo "Generated Aura application exceeded its shutdown grace period" >&2
  kill -KILL "$app_pid" 2>/dev/null || true
  exit 1
fi
wait "$app_pid" 2>/dev/null || true
app_pid=""

echo "Aura BOM, starter, and generated archetype project passed"
if test "$preserve_work_dir" = true; then
  echo "Preserved archetype smoke workspace: $work_dir"
fi
