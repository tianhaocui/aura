# Public API Gate

Aura commits a generated public API snapshot at
`api/aura-1.0.public-api.txt`. The snapshot is produced from compiled classes
with JDK `javap -public`; it covers every published Java module and excludes
generated metadata-only resources.

Run the check with:

```bash
bash scripts/verify-public-api.sh
```

Every run writes `target/aura-public-api-report.json`. The report is stable,
machine-readable release evidence with two explicit lists:

- `breakingChanges`: classes or signatures present in the reviewed baseline
  but absent from the current build.
- `additiveChanges`: classes or signatures added by the current build.

CI archives the report for 90 days even when the API gate fails. Read this
report before the raw snapshot; it is smaller and gives an AI enough ownership
and signature context to decide whether migration documentation is required.
`rawSnapshotChanged` remains an authoritative fallback: any raw baseline drift
still fails the gate even if it cannot be reduced to a classified signature.
Only externally accessible `public` or `protected` types enter the baseline;
package-private helpers, anonymous classes, and their members are implementation
details rather than compatibility promises. Generation uses C-locale ordering
and a fresh classes directory so local and CI reports are deterministic.

When a public API change is intentional, update the baseline in the same
review:

```bash
AURA_API_UPDATE=true bash scripts/verify-public-api.sh
git diff -- api/aura-1.0.public-api.txt
jq . target/aura-public-api-report.json
```

The baseline is a review artifact, not a compatibility promise for removed
pre-1.0 APIs. The change must still update `docs/migration-to-1.0.md` when it
affects the recommended AI authoring path.
