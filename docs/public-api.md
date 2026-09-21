# Public API Gate

Aura commits a generated public API snapshot at
`api/aura-1.0.public-api.txt`. The snapshot is produced from compiled classes
with JDK `javap -public`; it covers every published Java module and excludes
generated metadata-only resources.

Run the check with:

```bash
bash scripts/verify-public-api.sh
```

When a public API change is intentional, update the baseline in the same
review:

```bash
AURA_API_UPDATE=true bash scripts/verify-public-api.sh
git diff -- api/aura-1.0.public-api.txt
```

The baseline is a review artifact, not a compatibility promise for removed
pre-1.0 APIs. The change must still update `docs/migration-to-1.0.md` when it
affects the recommended AI authoring path.
