# Conversation SFT/LoRA tools

This directory contains offline preparation and checkpoint inspection for the
Conversation SFT/LoRA work. It is deliberately separate from the production
Python service so CUDA, PyTorch, Transformers and PEFT dependencies never enter
the service image.

## Steps 0-4: implementation and execution status

| Step | Delivered code | Execution gate |
| --- | --- | --- |
| 0: freeze scope | A1/A2/B1, content + translation, first attempt only; no repair or fallback | No production model/config change |
| 1: isolated tooling | Offline dependency lock, ignored output directories, CPU CI | GPU/CUDA training lock still needs the selected training machine |
| 2: dataset contract | Artifact/schema/prompt/input/output hashes, exact production message order, review references | Current-contract export and human review required |
| 3: preparation | Production hard validator, dedup, group splits, provenance and coverage manifest | Near-duplicate/holdout audit and P0 coverage approval still required |
| 4: model inspection + P0 config | Pinned checkpoint revision, text module map, chat template smoke, last-assistant loss policy | Real checkpoint/tokenizer inspection and GPU forward/backward/mask smoke not run |

This stage stops before training. `prepare_dataset.py` never repairs an output
or calls a model. `datasetReady=true` means the implemented data gates passed.
`trainingReady` remains **false** until the pending gates in the manifest have
been completed by a later preflight step. Do not manually flip that flag.

The deterministic validator checks structure, length, exact evidence, fact/slot
count, supported task types and literal answer leakage. It cannot certify role,
translation meaning, inference quality or semantic leakage. Those remain the
responsibility of independent semantic review and human review.

The existing `data/conversation-distillation/*.provisional.jsonl` files use an
older V2 contract and have blank human decisions. They are expected to fail the
strict preparation command until a current-contract export is reviewed. Do not
change the old files to make the command pass.

## Preparation command

Run from the repository root. Offline preparation uses the existing JSON Schema
library, pinned with its dependencies; it does not install CUDA or model libraries:

```powershell
python -m pip install -r training/requirements-prepare.lock
python -m pytest -q training/tests
```

After a reviewed current-contract export exists:

```powershell
python training/prepare_dataset.py `
  --artifact artifacts/prompts/conversation-cefr-b1-v7-20261021.json `
  --source data/fine-tuning/approved-export.jsonl `
  --decisions data/fine-tuning/human-review-decisions.csv `
  --holdout-group-file data/fine-tuning/benchmark-holdout-groups.txt `
  --output data/fine-tuning/approved-p0
```

The decisions CSV must contain `id,decision,reviewer,inputHash,outputHash`.
Accepted rows require a non-empty reviewer reference and matching hashes.
`approved`, `accepted`, `pass` (and their singular verbs) are accepted;
`rejected`/`failed` are excluded; pending or missing decisions stop the export.
Review references must identify real review evidence; AI-generated approvals
are not human review. The command refuses an existing output
directory and writes `train.jsonl`, `validation.jsonl`, `test.jsonl` and
`manifest.json` only after validation. The manifest is written last. A write
failure can leave partial files without a manifest; use a new run directory.

### Record contract

See `tests/test_prepare_dataset.py::_row` for an executable synthetic fixture.
Never use that fixture's fake review references for real training data.

- Required metadata: `id`, `groupId`, `scenario`, `branchId`, `level`,
  `turnNumber`, `purpose`, `source`, `reviewVersion`, `semanticReviewReference`,
  `artifactVersion`, `promptHash`, `schemaHash`, `inputHash`, `outputHash`,
  `targetMessageIndex=2`, `lossMaskPolicy=last-assistant-only`,
  `repaired=false`, `attempt=1`.
- `messages`: exactly system, user, assistant strings from the frozen current
  production request. User content is a JSON object; assistant content is the
  JSON target. Few-shot examples are in the production system message.
  Older five-message exports must be re-exported and reviewed, not relabeled.
- `validationTask`: complete reviewed task with English content and Vietnamese
  translation, keeping both `textEn` and `textVi` on each fact. The target must
  equal its content or translation projection. This enables the shared production
  validator to check both purposes without fabricating a translation.
- Content context difficulty/turn/profile must match metadata. Translation
  context utterance/facts must match the English source of `validationTask`.
- `levelProfile`: use the actual production word bounds, including legacy bounds;
  when omitted the artifact's level profile applies.
- Hash algorithm: SHA-256 of UTF-8 `json.dumps(value, ensure_ascii=False,
  sort_keys=True, separators=(",", ":"), allow_nan=False)`. Use `digest()`.
  `inputHash` hashes the first two messages, `outputHash` hashes parsed target
  JSON, `promptHash` hashes the system string, `schemaHash` hashes the artifact
  schema selected for purpose/level. Content and translation hashes differ.

### Split and holdout rules

All turns and both purposes in a scenario branch/level use the same `groupId`.
Do not use row IDs as group IDs. Seed 17 assigns groups to 70/15/15 splits;
the percentages are hash buckets, not exact row quotas. Empty splits fail.

The required holdout file lists the canonical benchmark and adversarial group
IDs, one per line. Those groups always go to test. Benchmark targets never go
to train. The operator must verify this list includes the complete canonical
benchmark before freezing a dataset; the tool cannot infer missing groups.

Exact input and normalized output duplicates inside a group are excluded.
Cross-group duplicates and conflicting targets fail for adjudication instead of
silently changing a holdout. A rejected row excludes its entire group, including
dependent turns and translation pairs. Changing source file order does not
change the prepared dataset hash. Near-duplicate and answer-pattern review is
still a pending gate recorded in every manifest.

The heuristic credential/email scan is a rejection check, not a complete PII
anonymizer. Only reviewed synthetic/redacted sources belong in P0.

## Model inspection command

Use an isolated Python 3.11/3.12 environment with PyTorch and a Transformers
version that exports `Qwen3_5ForCausalLM`. Pin the GPU/CUDA/Transformers/TRL/PEFT
environment on the selected training host and record its full dependency lock
before any training. This repository currently pins **offline preparation only**.

```powershell
python training/inspect_model.py `
  --model Qwen/Qwen3.5-9B `
  --revision <immutable-40-character-Hugging-Face-commit-SHA> `
  --output data/fine-tuning/runs/conversation-sft-p0/module-inspection.json
```

Inspection downloads config/tokenizer at the requested revision and creates the
text model on the meta device; it does not download the 9B weights. It renders
three test messages with thinking disabled. The report records full text module
paths, unselected linear layers, library versions and chat template hash. It
refuses to overwrite an existing report.

The proposed attention targets cover conventional q/k/v/o projections only;
hybrid DeltaNet layers remain listed as unselected. Review that coverage before
choosing a LoRA target set. No vision module is selected. Config-only inspection
does not prove weight loading, assistant masks, forward/backward, or adapter
save/load. These require the next preflight stage. The YAML is a proposal with
`status: requires_preflight`, not an executable trainer.

The base model must be the Transformers checkpoint. The local Ollama GGUF is an
inference artifact, not a training checkpoint. Training, merge, quantization and
Ollama export are separate later steps.

Architecture reference: [Transformers Qwen3.5 documentation](https://huggingface.co/docs/transformers/en/model_doc/qwen3_5).
Full implementation plan: [SFT/LoRA plan](../docs/conversation-sft-lora-integration-plan-2026-10-21.md).

Rollback for this stage: stop using the offline tools and retain run directories
for audit. There is no serving model switch to undo.
