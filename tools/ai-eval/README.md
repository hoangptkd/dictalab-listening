# AI evaluation gate

`run.py` replays redacted synthetic outputs against 15 inputs x seeds 17/43/97. It scores transport,
schema, exact evidence substring, question/fact alignment (including answerability/atomicity), translation,
role, continuity on turns 2-3 and turn-4 independence separately. Invalid/missing/timeout judge labels
never count as passes; coverage and each denominator are retained. The release comparison fails closed
on incomplete judging, dataset/config/model mismatch, unresolved review notes and metric regressions.

Example:

```powershell
python tools/ai-eval/run.py --dataset services/ai-generation/tests/fixtures/evaluation/dataset.json --capture services/ai-generation/tests/fixtures/evaluation/r1-split-translation-v2.json --output artifacts/reports/ai-platform-baseline.json --reviews artifacts/reports/ai-platform-review.json
python tools/ai-eval/run.py --dataset services/ai-generation/tests/fixtures/evaluation/dataset.json --capture services/ai-generation/tests/fixtures/evaluation/r2-slot-contract-v2.json --output artifacts/reports/ai-platform-candidate.json --baseline artifacts/reports/ai-platform-baseline.json --reviews artifacts/reports/ai-platform-review.json
python -m pytest -q services/ai-generation/tests tools/ai-eval
```

The second replay intentionally exits **2** because this candidate is not releasable. Both reports are
reproducible with frozen capture timestamps, dataset hash, model digest, prompt hash and generation config.
Use exit 2 as a release rejection, not a tool/inference failure. Nine keys per arm (20%) have AI review
notes; they are not human approval and never rewrite the original judge flags. Continuity/transfer labels
are only counted for applicable turns. These captures are earlier R1/R2 experiments at temperature 0.7;
the registered server workflow at 0.3 still needs its own paired evaluation.

To capture the current registered workflow on the same synthetic dataset, run from the repo root:

```powershell
python tools/ai-eval/generate.py --dataset services/ai-generation/tests/fixtures/evaluation/dataset.json --output data/ai-eval-new-capture.json
```

This explicitly runs local Ollama, verifies its digest, injects the fixed seed into each offline call and
persists redacted results after each sample. It never enables cloud fallback or calls a paid judge. Use a
new output path; existing evidence is preserved. Give the independent offline judge each record's
`judgeInput` (including turnNumber and the turn-4 rules), then attach a strict boolean verdict. Review only
the deterministic 10-20% sample. No judge/optimizer is imported by production.

Capture format: `{metadata, capturedAt, records}`. Required metadata is `model`, `modelDigest`,
`promptVersion`, `promptHash`, `config`, `judgeVersion`. Records have `id`, `seed`, `task`, `verdict`, and
optional `seconds`/`transport`. The baseline approval is a separate review decision; tools do not grant it.

`export_benchmark.py SOURCE DESTINATION` reconstructs the checked-in synthetic fixture captures from the
local historical benchmark, excluding wire logs, billing logs and runtime/account data.
`smoke.py` runs the real Java client against FastAPI and a fake Ollama server, then cleans up both servers.
