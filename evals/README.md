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
staged record stays inside that task's `maxTokens` budget -- a total
input-plus-output budget the manifest derives from the declared context rather than
a number anyone types (see "The budget, restated with its derivation"). Both arms
repair at most `maxRepairRounds` rounds from
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

Its records now live in `evals/results/archive/pilot-20261103.jsonl`, bytes and
run ids unchanged: a `staged-guide-v1` arm may not be paired with a
`staged-guide-v2` baseline cohort in one median, because the two recipes are two
experiments.

## Second measurement: `pilot-20261104` (n = 1 per arm, `claude-opus-4-5`, v2 recipes)

Same two tasks, both arms, same runner and conditions, with two changes: the
recipes are the post-fix documents (`all-canonical-docs-v2`, `staged-guide-v2`),
and the repair loop is capped at the task set's declared `maxRepairRounds = 3`
instead of the pilot's one, so a staged arm that needed two more rounds to compile
would have been recorded as what it is rather than as a failure of the claim. The
answers were compiled against this reactor at `28d8fc1`, after the reflection door
was removed.

Every claim leg is green at this size:

| task | arm | input | output | first pass | final | rounds |
|------|-----|-------:|-------:|:----------:|:-----:|:------:|
| `crud-module` | baseline | 51,679 | 1,453 | yes | yes | 0 |
| `crud-module` | staged | 8,185 | 1,456 | yes | yes | 0 |
| `route-authorization` | baseline | 51,662 | 423 | yes | yes | 0 |
| `route-authorization` | staged | 8,168 | 568 | yes | yes | 0 |

Prompt tokens fell 84.2% on both tasks, no arm needed a repair round, and no arm
lost first-pass or final success. The cause the pilot pointed at is now closed with
mechanism visible in the evidence: both staged answers open with
`import io.aura.web.TestClient;` and contribute `(BaseHandler) ctx -> ...` through
`Route.of(...)`, which is the fence `28d8fc1` added to the staged corpus, where the
v1 answers had invented `io.aura.test.TestClient`.

Two legs stay red, and both are the point. The `maxTokens` budget is not met:
`crud-module`'s staged run cost 9,641 tokens against a 4,000 budget and
`route-authorization` 8,736 against 3,000 -- and it cannot be met by any recipe of
this shape, because the staged arm's *first* call is already about 8,100 prompt
tokens, so a single successful answer overruns every declared budget before a
repair round is even considered. `everyMeasuredTaskCarriesEnoughSamplesPerArm` is
red because n = 1 is one sample: four runs on one model at `temperature: 0` cannot
distinguish the fix working from a favorable draw, which is exactly the difference
the sample floor exists to refuse to pretend about.

So the honest reading is: the refuted half of the claim was a documentation hole,
not a property of small context, and the remaining disagreement is with a budget
number nobody measured when it was written.

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

### The budget, restated with its derivation

`maxTokens` was restated on 2026-11-04, before the cohort that is graded against
it, because the number it carried was never derived from anything. It described
the budget the staged recipe is supposed to fit inside, so the honest answer to
"the staged arm spent 18,679 against 4,000" was to check whether 4,000 could be
reached at all: the staged arm's first call alone is about 8,100 prompt tokens, so
no recipe that ships `AI_GUIDE.md` -- 18,826 bytes, and 13,695 more in the module
guide it selects -- fits any budget in the 2,500-to-5,000 range the task set used
to declare. A criterion no candidate can meet is not a criterion, and it graded
nothing: it reported the same red for a wasteful recipe and a working one.

The restated budget is the worst declared cost of the experiment, not an
aspiration. Per task:

```
calls        = 1 + maxRepairRounds                       (the declared repair allowance)
prompt_tokens = ceil((staged_context_bytes + prompt_bytes) x tokensPerByte)
maxTokens    = calls x (prompt_tokens + maxOutputTokensPerCall)
```

Every input is declared in `evals/tasks.json` under `budget` rather than remembered
in prose: `stagedContext` is the recipe's files, `tokensPerByte` is 0.2586 --
measured from the pilot's own usage counts (50,317 prompt tokens over 194,564
context bytes), which makes the derived budget 3% conservative against the staged
arm's measured 8,185 tokens for 32,717 bytes -- and `maxOutputTokensPerCall` is
3,000, the output ceiling the pilot runner already passes. That puts every task's
budget at about 45,800 tokens, `crud-module` at 45,844.

The budgets are now nearly identical to each other, and that is the finding: under
a recipe whose fixed prompt is 8,461 tokens, a per-task number differing by 1,000
was measuring nothing but the author's guess about task difficulty.

What the number refuses has changed, and `example.AiEvaluationManifestTest` now
computes it from the tree instead of trusting the file: it adds up the bytes of
`budget.stagedContext`, applies the declared ratio and ceiling, and fails any task
whose `maxTokens` is not exactly that product. So a budget cannot be lowered to
grade a cohort, and it moves on its own only when the recipe or the declared repair
allowance moves -- which is the point of deriving it. What it now guards is a
runaway: a staged record inside budget means the run stayed within the calls the
task set declared, at the context the task set declared. Efficiency is graded by
the paired comparison against the baseline arm, which is where a relative claim
belongs; an absolute budget cannot express one.

Restating it also changed `taskSetHash`, which is what marks a record as belonging
to the task set it was measured against, so `pilot-20261104` is stranded: it stays
in `evals/results/` until the cohort recorded after this change lands, and the
consumer reports it as stale rather than comparing it to a budget it never faced.

The staged corpus is renamed, not reused, when its content changes. `contextId`
names a recipe but the record carries no hash of the bytes behind that name, so
editing `AI_GUIDE.md` or the module guide while keeping `staged-guide-v1` would
let two different recipes be paired as one. Until a recipe hash can be added to
`result.schema.json`, a recipe whose files changed gets a new id, and both arms
of a task are recorded in the same sitting. A `schemaVersion` bump would mark the
existing records invalid, and those records are the only current evidence that
the claim fails, so the bump ships with the next experiment rather than
silencing this one.

The experiment after the pilot was a documentation fix, not more sampling, because
the failure had a nameable cause. Both staged
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

That is what `pilot-20261104` did, and the fix held. What is left is the two red
legs, and they have opposite directions: the sample floor wants more runs, the
budget leg wants a smaller context and an honest number. The next cohort is these
same four runs repeated three times per arm -- twelve runs, with the baseline arm
costing about 51,700 prompt tokens each -- and the `maxTokens` derivation above
goes in front of it, not after it.

