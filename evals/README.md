# Aura AI Regression Evaluations

`tasks.json` is the stable task set for measuring whether an AI can produce
compiling, verifiable Aura 1.0 code without learning removed APIs. Each task
maps to checked-in golden tests, but an actual model run must start from the
task prompt in a clean project and record a result matching
`result.schema.json`.

Required metrics are schema version, task set hash, arm, context recipe, model,
input and output tokens, first-pass success, final success, repair rounds,
stable error codes encountered, duration, and commit.

## The claim, stated so it can fail

Reducing context while preserving stability is a hypothesis under test, not an
established result. `example.AiEvaluationResultsTest` scores it and the gate
stays red until `evals/results/` holds real records; adding sample data to make
it pass would defeat the point.

Two arms, same model, same task prompt, same verification loop:

- `baseline`, `contextId=all-canonical-docs-v1`: every canonical Aura document is
  placed in the context at once.
- `treatment`, `contextId=staged-guide-v1`: `AI_GUIDE.md` plus only the one module
  guide `.claude/skills/aura/SKILL.md` selects for the task.

The claim holds only if, per task, the staged arm's median `inputTokens` is at
most 90% of the baseline median, its median `repairRounds` is not higher, its
final success rate is not lower, its first-pass compile rate is not lower, no
task that succeeds under full docs fails to ever succeed staged, and every
staged record stays inside that task's `maxTokens` budget, which is a total
input-plus-output budget. Both arms repair at most `maxRepairRounds` rounds from
`tasks.json`; an uncapped loop would make `repairRounds` incomparable across
runners, and a run capped *below* that number has not tested the claim the task
set declares, so it is a different experiment and must say so in its conditions.

Three further legs hold the measurement up rather than the claim. Each measured
task needs at least three records per arm before any reduction leg may be read as
support, because a median of one sample is that sample: it can show a difference
but cannot size one, and the gate is meant to stay red on plumbing evidence. No
record may contradict itself (`firstPassSuccess` with a nonzero
`repairRounds`, a first-pass success that never succeeded, or a zero prompt
charge meaning no call was made), since the recorder accepts all three and only
the consumer can refuse them. And each arm is pinned to exactly one model and one
`contextId`, so the cheapest arm cannot simply be a cheaper model.

`maxTokens` is graded against the staged arm only. If the experiment fails the
claim, withdraw the claim or change the design — do not edit the data, and do
not lower a threshold in the same change that declares victory.

## First measurement: `pilot-20261103` (n = 1 per arm, `claude-opus-4-5`)

Two tasks (`crud-module`, `route-authorization`), both arms, verification by
compiling the answer against the installed 1.0 API at `5d793bb`, at most one
repair round. Conditions, so a rerun is comparable: one `user` message holding the
arm's context followed by the task prompt and a demand for exactly one
compilation unit, `temperature: 0`, `max_tokens: 3000`, repair prompt = the same
context plus the compiler output and the previous answer, and `inputTokens` /
`outputTokens` are the sums of the API's own usage counts over all calls of the
run. The staged recipe cut prompt tokens from about 50,300 to about 16,100
(-68%), so the input-cost leg holds. The other legs did not: both staged
arms needed a repair round where both baseline arms passed first try, and
`route-authorization` never compiled under the staged recipe (baseline final
success 1.00, staged 0.00). The staged arm also cost 18,679 tokens against the
4,000 `maxTokens` recorded for `crud-module`, so no recipe built from these
documents fits the budget the task set declares.

Read as: staged context is cheaper per call and, at this sample size, cost one
extra repair round on both tasks and one unrecovered failure on the other. The
claim forbids exactly that trade, whatever causes it, so it is not supported. Changing it requires either a staged recipe that keeps first-pass
reliability, or dropping the "preserving stability" half of the claim; neither
may be done by editing these numbers or the thresholds. n = 1 computes no real
median, so this is plumbing evidence, not a verdict on the effect size.

Run the golden behavioral baseline:

```bash
bash scripts/verify-ai-evals.sh
```

It grades every golden task by requiring a surefire report that only that run
could have written, so a task whose test was deleted, renamed, disabled, or left
out of the build fails the gate instead of passing silently.

Reproduce a run with the pilot runner, which takes the endpoint, the model, and
both context recipes as arguments and never defaults them:

```bash
bash scripts/run-ai-pilot.sh \
  --task-id crud-module --class CrudOrdersModule \
  --baseline-context-id all-canonical-docs-v1 \
  --baseline-context AI_GUIDE.md,docs/aura-core.md,docs/aura-web.md,docs/aura-db.md,docs/aura-workflow.md,docs/aura-ai.md,docs/aura-mcp.md,docs/aura-processor.md,docs/aura-contract.md,docs/aura-module.md,docs/DOCUMENTATION.md,docs/migration-to-1.0.md,docs/REFACTOR_LOG.md \
  --staged-context-id staged-guide-v1 \
  --staged-context AI_GUIDE.md,docs/aura-web.md \
  --model model-name --base-url https://host/v1 --api-key-file ~/.config/key
```

It grades each answer by compiling it against this reactor's own classes, caps
repair rounds at `maxRepairRounds` from `tasks.json`, and records through
`scripts/record-ai-eval.sh`, which derives `schemaVersion` and `taskSetHash` from
the files rather than accepting them as input, rejects unknown task ids and
over-budget repair rounds, and appends to `evals/results/<run-prefix>.jsonl`. A
run whose endpoint call failed is not recorded at all: transport trouble is not
a verdict about the code. Result files are experiment evidence — commit them with
the run ids they came from, never fabricate them.

To record a run that was driven by other tooling, call the recorder directly:

```bash
bash scripts/record-ai-eval.sh \
  --task-id crud-module --run-id run-20260917 --model model-name \
  --variant treatment --context-id staged-guide-v1 \
  --input-tokens 3100 --output-tokens 850 \
  --first-pass true --final-success true \
  --repair-rounds 0 --error-codes none --duration-ms 42000
```

## Decisions taken from the pilot

`maxTokens` stays as written. It describes the budget the staged recipe is
supposed to fit inside, so the answer to "the staged arm spent 18,679 against
4,000" is to shrink the recipe, not to raise the number; raising it would delete
the only check that measures this project's second design red line.

The staged corpus is renamed, not reused, when its content changes. `contextId`
names a recipe but the record carries no hash of the bytes behind that name, so
editing `AI_GUIDE.md` or the module guide while keeping `staged-guide-v1` would
let two different recipes be paired as one. Until a recipe hash can be added to
`result.schema.json`, a recipe whose files changed gets a new id, and both arms
of a task are recorded in the same sitting. A `schemaVersion` bump would mark the
existing records invalid, and those records are the only current evidence that
the claim fails, so the bump ships with the next experiment rather than
silencing this one.

The next experiment is a documentation fix, not more sampling. Both staged
failures invented `io.aura.test.TestClient`, and the staged corpus names
`TestClient` thirteen times (counted in `AI_GUIDE.md` plus `docs/aura-web.md` at
`5d793bb` and again at HEAD) without ever giving its package — the qualified name
`io.aura.web.TestClient` appears zero times there and lives only in
`docs/DOCUMENTATION.md` and `docs/REFACTOR_LOG.md`, neither of which the staged
arm reads. That is a plain coverage hole in the guide. The second failure is
weaker evidence: the staged docs show response reads only inside unmarked
sketches, yet the model still called a `bodyString()` that exists in no document,
which one repair round did not fix.

The hole is now closed in the shape a model can actually copy. Both `TestClient`
examples are marked `aura:compile` units (`ai-test-client-usage`,
`web-test-client-usage`) that carry their own `package`, their
`import io.aura.web.TestClient;` and a call that reads a body, and the prose
beside them names the nested `TestClient.Request` / `TestClient.Response` and the
`status()` / `body()` / `json(Class)` / `bodyContains(String)` accessors. The
documentation gate compiles fifteen fences instead of thirteen, so a reader
copying the staged guide gets an import line that was checked against this tree.
Marking the sketch also fixed the deeper defect: the fences a model imitates were
the ones no gate verified.

Because those two files sit in both recipes, the next run pairs
`all-canonical-docs-v2` against `staged-guide-v2`; recording it under the v1 ids
would pair two different recipes as one. When v2 records land, the v1 file moves
to `evals/results/archive/` with its bytes and run ids untouched — the consumer
scans one directory, so an archived cohort stops being compared against a live one
while staying in the history that shows the claim was refuted once. Both arms of a
task must be recorded in the same sitting, or the consumer reads the mixed recipes
as an invalid comparison rather than a result.

