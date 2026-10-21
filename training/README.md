# Conversation SFT/LoRA tools

This directory contains offline preparation and checkpoint inspection for the
Conversation SFT/LoRA work. It is deliberately separate from the production
Python service so CUDA, PyTorch, Transformers and PEFT dependencies never enter
the service image.

## Steps 0-4: implementation and execution status

| Step | Delivered code | Execution gate |
| --- | --- | --- |
| 0: freeze scope | A1/A2/B1, content + translation, first attempt only; no repair or fallback | No production model/config change |
| 1: isolated tooling | Offline dependency lock, ignored output directories, CPU CI and tested local CPU lock | CUDA lock needed only for a future GPU run |
| 2: dataset contract | Artifact/schema/prompt/input/output hashes, exact production message order, review references | Current-contract export and human review required |
| 3: preparation | Production hard validator, dedup, group splits, provenance and coverage manifest | Near-duplicate/holdout audit and P0 coverage approval still required |
| 4: model inspection + P0 config | Qwen3.5-4B pinned revision, inspected text modules/tokenizer, last-assistant loss policy | Real pretrained weight loading and forward/backward remain pending |

`train_sft_lora.py` now implements preflight and training; `smoke_cpu.py` tests
the runtime locally on a tiny random model. `prepare_dataset.py` never repairs an output
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
before any training. The repository pins offline preparation and a separate
Windows/Python 3.12 CPU runtime. The CUDA environment is still pending.

```powershell
python training/inspect_model.py `
  --model Qwen/Qwen3.5-4B `
  --revision 851bf6e806efd8d0a36b00ddf55e13ccb7b8cd0a `
  --output data/fine-tuning/inspections/conversation-sft-p0-4b.json
```

Inspection downloads config/tokenizer at the requested revision and creates the
text model on the meta device; it does not download checkpoint weights. It renders
three test messages with thinking disabled. The report records full text module
paths, unselected linear layers, library versions and chat template hash. It
refuses to overwrite an existing report.

The proposed attention targets cover conventional q/k/v/o projections only;
hybrid DeltaNet layers remain listed as unselected. Review that coverage before
choosing a LoRA target set. No vision module is selected. Config-only inspection
does not prove weight loading, assistant masks, forward/backward, or adapter
save/load. The trainer performs those checks separately. The YAML remains a
proposal with `status: requires_preflight`; retain the pinned revision and
provide the reviewed inputs before invoking it. Inspection inputs must be stored
outside the training output directory, which must not already exist.

The base model must be the Transformers checkpoint. The local Ollama GGUF is an
inference artifact, not a training checkpoint. Training, merge, quantization and
Ollama export are separate later steps.

Architecture reference: [Transformers Qwen3.5 documentation](https://huggingface.co/docs/transformers/en/model_doc/qwen3_5).
Full implementation plan: [SFT/LoRA plan](../docs/conversation-sft-lora-integration-plan-2026-10-21.md).

Rollback for this stage: stop using the offline tools and retain run directories
for audit. There is no serving model switch to undo.

## Local CPU runtime on the current PC

Verified 2026-10-22: Ryzen 7 PRO 6850HS, 31.3 GiB RAM, Radeon integrated graphics
with 0.5 GiB reported dedicated memory, no CUDA GPU. The active P0 target is
[Qwen/Qwen3.5-4B](https://huggingface.co/Qwen/Qwen3.5-4B), pinned to revision
`851bf6e806efd8d0a36b00ddf55e13ccb7b8cd0a`. It uses CPU LoRA, float32,
batch size 1 and gradient checkpointing with the existing CPU lock. CPU 4-bit
QLoRA and half precision are rejected. No pretrained weights were downloaded
or trained. This changes training configuration only; production serving stays
under its existing release gates.

Config inspection counts 4,205,751,296 text parameters: about 15.7 GiB for the
float32 base alone. Before loading, the runner requires that amount plus an
8 GiB workspace estimate (23.7 GiB available RAM). This is an admission estimate,
not a guarantee that a 4,096-token run fits. The observed 14.7 GiB free on this
PC fails that gate. Close memory-heavy applications before attempting a real
smoke; CPU throughput and peak memory still need measurement. Do not shorten
production prompts or silently truncate targets to get around memory limits.

The local runtime is isolated at `data/fine-tuning/.venv-cpu`; nothing is added
to the production image. To recreate it using the installed `uv`:

```powershell
uv venv --python 3.12 data/fine-tuning/.venv-cpu
uv pip install --python data/fine-tuning/.venv-cpu/Scripts/python.exe `
  --index-url https://download.pytorch.org/whl/cpu torch==2.10.0+cpu
uv pip install --python data/fine-tuning/.venv-cpu/Scripts/python.exe `
  -r training/requirements-cpu.lock
```

Use a new output path for each run:

```powershell
data/fine-tuning/.venv-cpu/Scripts/python.exe training/smoke_cpu.py `
  --output data/fine-tuning/runs/local-cpu-smoke-new-run
$env:SFT_RUN_RUNTIME_TESTS = '1'
data/fine-tuning/.venv-cpu/Scripts/python.exe -m pytest -q training/tests
```

The smoke uses a random two-layer Qwen3.5 text model (linear + full attention) and a tiny character
tokenizer with synthetic JSON fixtures. It verifies finite forward/backward,
nonzero LoRA gradients, frozen base weights, one optimizer update, padding/loss
mask preservation, adapter safetensors reload on a fresh model, and an epoch
loop with validation and best-checkpoint reload, with gradient checkpointing
enabled. It writes
`run-manifest.json` with `trainingReady=false`, `realCheckpointLoaded=false`,
and `releaseAllowed=false`. These artifacts are test fixtures, not a candidate
Conversation model. The synthetic smoke validation set deliberately reuses
fixtures; its loss is not a model quality measurement.

Real 4B config/tokenizer inspection passed on this PC at the pinned revision
above. Its tokenizer and final-assistant mask were checked on
production-format content and translation fixtures: 2,331/248 total tokens,
150/44 supervised target tokens. These checks load no checkpoint weights.

If the default Hugging Face cache is inaccessible, use a task-local cache:

```powershell
$env:HF_HOME = Join-Path (Get-Location) 'data/fine-tuning/hf-cache'
```

## Trainer preflight and execution

The trainer reads `dataset_manifest`, `dataset_approval`, `environment_lock`,
and the module inspection path from the YAML. Config paths are relative to the
repository root (absolute paths are also accepted). It verifies split counts,
dataset/input/output hashes, review evidence, validator hash, group leakage,
forced test groups, P0 size and coverage, pinned checkpoint and environment.
The validator hash uses `digest()` on UTF-8 source text with normalized line
endings, so Windows/Linux checkouts agree. Reprepare older exports that used
the raw-byte validator hash; do not edit their manifests manually.
The test split is read for integrity checks and never tokenized for training,
validation loss or checkpoint selection.

Create the following review JSON only after the actual audits are complete:

```json
{
  "approved": false,
  "reviewerReference": "",
  "datasetHash": "copy from the frozen dataset manifest",
  "datasetManifestHash": "SHA-256 of the manifest file bytes",
  "coverageApproved": false,
  "nearDuplicateAuditApproved": false,
  "holdoutAuditApproved": false
}
```

All decisions must be true, with a real reviewer reference and matching hashes.
P0 requires at least 200 retained target records; train and validation must each
contain A1/A2/B1 and both purposes; train must cover turns 2/3/4. Further balance
and semantic quality checks remain part of coverage approval. The runner does
not edit the prepared manifest or fabricate review decisions.

`environment_lock` must contain the actual host's exact `package==version`
pins, including torch, transformers, trl, peft, datasets, accelerate, safetensors
and PyYAML. The CPU lock is tested; create a separate CUDA lock with the same
HF library versions and the host's compatible PyTorch/bitsandbytes builds.
The active YAML uses `training/requirements-cpu.lock`. Neither the pretrained
4B CPU load path nor CUDA/QLoRA has been executed on the current PC.

```powershell
data/fine-tuning/.venv-cpu/Scripts/python.exe training/train_sft_lora.py --config training/configs/conversation-sft-p0.yaml --check
data/fine-tuning/.venv-cpu/Scripts/python.exe training/train_sft_lora.py --config training/configs/conversation-sft-p0.yaml --smoke
data/fine-tuning/.venv-cpu/Scripts/python.exe training/train_sft_lora.py --config training/configs/conversation-sft-p0.yaml
```

`--check` performs offline gates only. `--smoke` additionally loads the real base
and runs one optimizer update on approved data. Use a new run/output directory
for full training; weights are loaded fresh. The real trainer supports one CPU
or CUDA process/device. CPU uses float32 LoRA with a RAM admission check;
CUDA supports LoRA or QLoRA with explicit bf16/fp16 capability checks.
The adapter preflight uses the longest training sequence with checkpointing
enabled when configured, before starting the optimizer loop.

Loss labels are computed before TRL: system/user tokens are `-100`, final target
and its template terminator are supervised, padding stays `-100` even if pad
equals EOS. Template/prefix ambiguity, inserted hidden reasoning and overlength
sequences stop the run. No silent truncation or packing is allowed. TRL receives
pre-tokenized labels and `skip_prepare_dataset=true`; its automatic mask flags
are disabled because the runner has already built the exact mask.

A successful full run saves adapter/tokenizer, trainer checkpoints/state,
train/validation metrics and a manifest with config/dataset/review/environment/
inspection/adapter hashes, Git SHA, device/dtype, CPU memory admission estimate,
GPU/CUDA and library versions. CPU GPU-memory fields are null. It can set
`trainingReady=true` in the new **run** manifest after preflight and training;
the dataset manifest stays unchanged. `releaseAllowed` remains false until the
separate base/candidate first-pass benchmark and release gates pass.

TRL source for pre-tokenized labels and preparation bypass:
[SFTTrainer dataset support](https://huggingface.co/docs/trl/en/sft_trainer).
