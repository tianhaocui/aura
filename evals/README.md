# Aura AI Regression Evaluations

`tasks.json` is the stable task set for measuring whether an AI can produce
compiling, verifiable Aura 1.0 code without learning removed APIs. Each task
maps to checked-in golden tests, but an actual model run must start from the
task prompt in a clean project and record a result matching
`result.schema.json`.

Required metrics are input/output tokens, first-pass success, repair rounds,
stable error codes encountered, duration, model, and commit. Do not compare
models using only final success; the second design red line is reducing context
and repair cost while preserving stability.

Run the golden behavioral baseline:

```bash
bash scripts/verify-ai-evals.sh
```

Record one real model run as JSONL:

```bash
bash scripts/record-ai-eval.sh results.jsonl \
  crud-module run-20260917 model-name 1200 850 true 0 none 42000 HEAD_SHA
```

Generated result files are evidence artifacts and should be uploaded by CI or
an evaluation runner, not treated as framework source.
