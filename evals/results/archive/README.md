# Archived evaluation cohorts

`example.AiEvaluationResultsTest` scans the files directly in `evals/results/`, so a cohort moved
here stops being compared against the live one while staying in the repository. That separation is
what the single-`contextId`-per-arm rule needs: two cohorts of the same task recorded under
different recipes are two experiments, not one median, and pairing them would report a difference
between recipes as if it were a difference between arms.

Nothing here is edited. The bytes, run ids and `taskSetHash` values are exactly what the runner
recorded, and each file's own README section says what it concluded -- `pilot-20261103.jsonl` is the
cohort that refuted the staged-context claim, and `evals/README.md` keeps those numbers in prose so
the refutation survives the move.
