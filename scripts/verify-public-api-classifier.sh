#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
work_dir="$(mktemp -d "${TMPDIR:-/tmp}/aura-api-classifier.XXXXXX")"
cleanup() { rm -rf -- "$work_dir"; }
trap cleanup EXIT

cat >"$work_dir/baseline.txt" <<'EOF'
### sample:a.PublicApi
public class a.PublicApi {
  public void retained();
  public void removed();
}
### sample:a.InternalHelper
class a.InternalHelper {
  public void hiddenBefore();
}
EOF

cat >"$work_dir/current.txt" <<'EOF'
### sample:a.PublicApi
public class a.PublicApi {
  public void retained();
  public void added();
}
### sample:a.InternalHelper
class a.InternalHelper {
  public void hiddenAfter();
}
EOF

for side in baseline current; do
  awk -f "$repo_root/scripts/public-api-symbols.awk" "$work_dir/$side.txt" \
    | LC_ALL=C sort -u >"$work_dir/$side.tsv"
done
LC_ALL=C comm -23 "$work_dir/baseline.tsv" "$work_dir/current.tsv" >"$work_dir/removed.tsv"
LC_ALL=C comm -13 "$work_dir/baseline.tsv" "$work_dir/current.tsv" >"$work_dir/added.tsv"

test "$(wc -l <"$work_dir/removed.tsv" | tr -d ' ')" -eq 1
test "$(wc -l <"$work_dir/added.tsv" | tr -d ' ')" -eq 1
rg -q $'^public-signature\tsample:a\.PublicApi\t  public void removed\(\);$' \
  "$work_dir/removed.tsv"
rg -q $'^public-signature\tsample:a\.PublicApi\t  public void added\(\);$' \
  "$work_dir/added.tsv"
if rg -q 'InternalHelper|hiddenBefore|hiddenAfter' \
    "$work_dir/baseline.tsv" "$work_dir/current.tsv"; then
  echo "Inaccessible implementation types must not enter the public API report" >&2
  exit 1
fi

cat >"$work_dir/types.tsv" <<'EOF'
sample:a.PublicApi	-
sample:a.PublicApi$Nested	sample:a.PublicApi
sample:a.HiddenOuter$Nested	sample:a.HiddenOuter
sample:a.HiddenOuter$Middle$Nested	sample:a.HiddenOuter$Middle
sample:a.HiddenOuter$Middle	sample:a.HiddenOuter
sample:a.Top$Level	-
EOF
awk -f "$repo_root/scripts/public-api-visible-types.awk" "$work_dir/types.tsv" \
  | LC_ALL=C sort >"$work_dir/visible.txt"
test "$(wc -l <"$work_dir/visible.txt" | tr -d ' ')" -eq 3
rg -Fxq 'sample:a.PublicApi' "$work_dir/visible.txt"
rg -Fxq 'sample:a.PublicApi$Nested' "$work_dir/visible.txt"
rg -Fxq 'sample:a.Top$Level' "$work_dir/visible.txt"
if rg -q 'HiddenOuter' "$work_dir/visible.txt"; then
  echo "Nested types require an accessible enclosing type chain" >&2
  exit 1
fi

echo "Aura public API classifier passed"
