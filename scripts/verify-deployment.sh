#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
root_version="$(sed -n 's:.*<version>\([^<]*\)</version>.*:\1:p' "$repo_root/pom.xml" | head -1)"
test -n "$root_version"
for command_name in docker kind kubectl python3 curl java mvn rg unzip; do
  command -v "$command_name" >/dev/null || {
    echo "Deployment smoke requires $command_name" >&2
    exit 1
  }
done

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/aura-deployment.XXXXXX")"
archetype_work="$work_dir/archetype"
container_name="aura-smoke-$$"
cluster_name="${AURA_KIND_CLUSTER:-aura-smoke-$$}"
kube_context="kind-$cluster_name"
repo_port=$((22000 + ($$ % 10000)))
container_port=$((32000 + ($$ % 10000)))
kube_port=$((42000 + ($$ % 10000)))
repo_pid=""
port_forward_pid=""
owns_cluster=false

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
  if test -n "$port_forward_pid" && kill -0 "$port_forward_pid" 2>/dev/null; then
    terminate_process "$port_forward_pid"
  fi
  if docker inspect "$container_name" >/dev/null 2>&1; then
    docker logs "$container_name" >"$work_dir/container.log" 2>&1 || true
    docker rm -f "$container_name" >/dev/null 2>&1 || true
  fi
  if test "$owns_cluster" = true; then
    kind delete cluster --name "$cluster_name" >/dev/null 2>&1 || true
  fi
  if test -n "$repo_pid" && kill -0 "$repo_pid" 2>/dev/null; then
    terminate_process "$repo_pid"
  fi
  rm -rf -- "$work_dir"
}
trap cleanup EXIT

AURA_ARCHETYPE_WORK_DIR="$archetype_work" \
  bash "$repo_root/scripts/verify-maven-archetype.sh"

generated="$archetype_work/Aura_Smoke"
local_repo="$archetype_work/m2"
settings="$work_dir/settings.xml"
cat >"$settings" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">
  <mirrors>
    <mirror>
      <id>aura-ci-repository</id>
      <name>Read-only Aura deployment smoke repository</name>
      <url>http://host.docker.internal:$repo_port/</url>
      <mirrorOf>*</mirrorOf>
    </mirror>
  </mirrors>
</settings>
EOF

python3 -m http.server "$repo_port" --bind 0.0.0.0 --directory "$local_repo" \
  >"$work_dir/repository.log" 2>&1 &
repo_pid=$!
for _ in {1..40}; do
  if curl --fail --silent --max-time 1 \
      "http://127.0.0.1:$repo_port/io/github/tianhaocui/aura-parent/$root_version/aura-parent-$root_version.pom" \
      >/dev/null 2>&1; then
    break
  fi
  if ! kill -0 "$repo_pid" 2>/dev/null; then
    cat "$work_dir/repository.log" >&2
    echo "Temporary Maven repository server exited" >&2
    exit 1
  fi
  sleep 0.25
done
curl --fail --silent --max-time 1 \
  "http://127.0.0.1:$repo_port/io/github/tianhaocui/aura-parent/$root_version/aura-parent-$root_version.pom" \
  >/dev/null

DOCKER_BUILDKIT=1 docker build \
  --add-host host.docker.internal:host-gateway \
  --secret "id=maven_settings,src=$settings" \
  --tag aura-smoke:1.0.0-SNAPSHOT \
  "$generated"

test "$(docker image inspect aura-smoke:1.0.0-SNAPSHOT --format '{{.Config.User}}')" = "10001:10001"
docker run --detach --name "$container_name" \
  --read-only --tmpfs /tmp:rw,noexec,nosuid,size=64m \
  --publish "127.0.0.1:$container_port:8080" \
  --env AURA_ENV=production \
  --env AURA_PUBLIC_ORIGIN=https://aura-smoke.example.com \
  --env AURA_ALLOWED_HOSTS=aura-smoke.example.com \
  aura-smoke:1.0.0-SNAPSHOT >/dev/null

container_ready=false
for _ in {1..80}; do
  if curl --fail --silent --max-time 1 -H 'Host: aura-smoke.example.com' \
      "http://127.0.0.1:$container_port/health/ready" >"$work_dir/container-ready.json" 2>/dev/null; then
    container_ready=true
    break
  fi
  if test "$(docker inspect "$container_name" --format '{{.State.Running}}')" != true; then
    docker logs "$container_name" >&2
    echo "Generated Aura container exited before becoming ready" >&2
    exit 1
  fi
  sleep 0.25
done
if test "$container_ready" != true; then
  docker logs "$container_name" >&2
  echo "Generated Aura container did not become ready" >&2
  exit 1
fi
rg -q '"status":"UP"' "$work_dir/container-ready.json"
test "$(curl --fail --silent --max-time 2 -H 'Host: aura-smoke.example.com' \
  "http://127.0.0.1:$container_port/hello")" = "hello"
docker stop --time 40 "$container_name" >/dev/null
docker rm "$container_name" >/dev/null

if ! kind get clusters | rg -qx "$cluster_name"; then
  kind create cluster --name "$cluster_name" --wait 120s
  owns_cluster=true
fi
kind load docker-image aura-smoke:1.0.0-SNAPSHOT --name "$cluster_name"
kubectl --context "$kube_context" apply --dry-run=server \
  -f "$generated/k8s/aura.yaml" >/dev/null
kubectl --context "$kube_context" apply -f "$generated/k8s/aura.yaml" >/dev/null
kubectl --context "$kube_context" rollout status deployment/aura-smoke --timeout=180s

kubectl --context "$kube_context" port-forward service/aura-smoke "$kube_port:80" \
  >"$work_dir/port-forward.log" 2>&1 &
port_forward_pid=$!
kubernetes_ready=false
for _ in {1..40}; do
  if curl --fail --silent --max-time 1 -H 'Host: aura-smoke.example.com' \
      "http://127.0.0.1:$kube_port/health/ready" >"$work_dir/kubernetes-ready.json" 2>/dev/null; then
    kubernetes_ready=true
    break
  fi
  if ! kill -0 "$port_forward_pid" 2>/dev/null; then
    cat "$work_dir/port-forward.log" >&2
    echo "kubectl port-forward exited before the application became ready" >&2
    exit 1
  fi
  sleep 0.25
done
if test "$kubernetes_ready" != true; then
  cat "$work_dir/port-forward.log" >&2
  kubectl --context "$kube_context" get pods -o wide >&2 || true
  kubectl --context "$kube_context" describe pods >&2 || true
  kubectl --context "$kube_context" logs deployment/aura-smoke \
    --all-containers --tail=200 >&2 || true
  echo "Kubernetes Aura application did not become ready through the service" >&2
  exit 1
fi
rg -q '"status":"UP"' "$work_dir/kubernetes-ready.json"
test "$(curl --fail --silent --max-time 2 -H 'Host: aura-smoke.example.com' \
  "http://127.0.0.1:$kube_port/hello")" = "hello"

echo "Aura Docker and Kubernetes deployment smoke passed"
