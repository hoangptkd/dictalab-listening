# Prompt/model artifacts

The server reads only the bundle selected by `DICTALAB_AI_ARTIFACT_VERSION`, verifies its canonical SHA-256,
and maps client aliases through that bundle's allowlist. Prompt text, examples, both schemas, generation
configuration, model/digest, dataset hash, timestamp and evaluation metadata belong to one immutable version.

The included `conversation-platform-r1-20261020` bundle is **development**, with `releaseAllowed=false`.
The `conversation-cefr-b1-v7-20261021` development candidate adds B1 opinion/attitude, contextual inference,
structured reasoning evidence and level profiles. It is the default for development; its release gate remains
closed pending the independent `cefr-b1-v7-10x3.json` generation benchmark and semantic review. The previous
bundle is retained unchanged. Explicit production pins still require an approved, evaluated artifact.
Java contract tests compare the new bundle's instruction, schemas, examples and profiles with the current code.
The historical baseline/candidate reports describe earlier offline experiments, not this registered workflow.
Do not relabel this bundle approved or reuse its identifier after editing. Capture and judge the exact new
workflow, resolve invalid verdicts and review disagreements, compare all metrics to an approved baseline,
then create a new version with its evaluation evidence and approval status.

Production requires an explicit `DICTALAB_AI_ARTIFACT_SHA256` pin, approved status, passing gate and evaluated
generation configuration. Missing files, corrupt hashes and Ollama digest drift fail readiness. A model tag
alone is insufficient. To roll back without rebuilding Java, mount the reviewed artifact directory read-only,
change the configured version/model/hash together, and restart Python.

Do not store credentials or real learner transcripts here. `sha256` is SHA-256 of the UTF-8 canonical JSON
object excluding `sha256`, with sorted keys, compact separators and unescaped Unicode.
