# AI Platform — Baseline đóng băng (T00)

Tài liệu này đóng băng trạng thái hiện tại của boundary sinh conversation trước khi tách
thành AI Platform Python độc lập. Nó là mốc so sánh để đo regression ở các task sau (T01–T09).

## 1. Git state lúc đóng băng

- Branch: `codex/practice-drafts-audio-20261017`
- Upstream: `origin/codex/practice-drafts-audio-20261017`
- HEAD: `0cfb8dc r1: English-first generation with separate translation call`
- Quy tắc: chỉ stage file thuộc task đang làm. Không stage các file dirty không liên quan
  (vd. `scripts/run-production-load.ps1`) và các file untracked ngoài phạm vi.

## 2. Provider hiện tại

| Mục | Giá trị |
| --- | --- |
| Provider mặc định (Java) | `shopaikey` (cloud fallback); `ollama` khi bật local; `python` khi bật AI Platform |
| Local model | `qwen3.5:9b` |
| Local base URL | `http://127.0.0.1:11434` |
| Model digest | `56671c2ab9385f9cfcb404638e32cd62d88e3501d44822208363c010179a3c90` |
| Prompt version | `CONVERSATION_GENERATION_V6_SLOTS` |

Digest lấy từ `data/conversation-offline-refinement-20261020/model.json`, khớp với
`GET http://127.0.0.1:11434/api/tags` (modified_at 2026-10-19, Q4_K_M, 9.0B, context 262144).

## 3. Pipeline sinh (Java `ConversationLocalClient`, đã commit ở 0cfb8dc)

Hai bước tách biệt:

1. **Sinh English trước** — schema `SCHEMA_ENGLISH`:
   `slots[]`, `utteranceEn`, `facts[{textEn,evidenceEn}]`, `questionVi`, `taskVi`, `skill`.
   `temperature = 0.3`, `num_ctx = 8192`, `num_predict = 1000`.
2. **Dịch riêng** — schema `SCHEMA_TRANSLATE`: `translationVi`, `facts[{textVi}]`.
   `temperature = 0.1`, `num_ctx = 4096`, `num_predict = 800`.
3. **Merge** (`combine`) hợp nhất về public contract cũ: giữ `slots`, `utteranceEn`,
   `questionVi`, `taskVi`, `skill`; facts trả về `{textVi,evidenceEn}` (bỏ `textEn`);
   thêm `translationVi`. `normalizeOutput` gỡ `slots` khỏi public response và gán
   `facts[].id = f1..fn`.

Timeout: `localTimeoutSeconds = 180` (min 1, max 600). `localFlowTimeoutSeconds = 2*180 + 90 = 450`.

## 4. Python service hiện tại (`services/ai-generation`)

- Endpoint: `GET /health/live`, `GET /health/ready`, `POST /v1/conversation/tasks` (port 8091).
- `Settings` (prefix `DICTALAB_AI_`): `model=qwen3.5:9b`, `temperature=0.3`,
  `num_ctx=8192`, `num_predict=1000`, `timeout_seconds=180`, `max_concurrency=1`,
  `prompt_version=CONVERSATION_GENERATION_V6_SLOTS`.
- **Trạng thái R1 đang dở** (`ollama.py`, dirty, chưa commit): `SCHEMA` đã được đổi tên
  thành `CONTENT_SCHEMA` + thêm `TRANSLATION_SCHEMA`, nhưng `generate()` vẫn single-pass,
  còn tham chiếu `SYSTEM_PROMPT` đã bị xóa, và `CONTENT_SCHEMA` vẫn yêu cầu `textVi` mà
  không còn định nghĩa. T01 phải hoàn tất pipeline hai bước này cho khớp Java.

## 5. Canonical request (Java → Python)

`ConversationPythonClient.requestBody(level, input, requestId)` tạo body:

```json
{
  "requestId": "<uuid>",
  "idempotencyKey": "<uuid>",
  "level": "A1|A2|B1",
  "turnNumber": 1..4,
  "promptVersion": "CONVERSATION_GENERATION_V6_SLOTS",
  "instruction": "<ConversationGenerationPrompt.instruction(level,turn) + turnBrief(context)>",
  "context": { ... }
}
```

- Header bắt buộc: `X-Request-Id` = `requestId`, `Idempotency-Key` = `idempotencyKey`,
  `X-Service-Token` (chỉ khi `pythonServiceToken` khác rỗng).
- Giới hạn body: 64_000 bytes ở Java; instruction tối đa 50.000 ký tự ở Python.

## 6. Benchmark regression (15 input × 3 seed)

Nguồn dữ liệu đã có (không chạy live inference trong phase này):

- Seeds: `[17, 43, 97]`.
- Manifest: `data/conversation-offline-refinement-20261020/manifest.json` (15 case, hash input có sẵn).
- Model: `data/conversation-offline-refinement-20261020/model.json`.
- Request/verdicts: `data/conversation-offline-refinement-20261020/r1-split-translation-v2-requests.json`
  và `...-outputs.jsonl`, `...-verdicts-*.jsonl`.

### 6.1. Baseline metric (stage `r1-split-translation-v2` — English-first + translation)

Nguồn: `data/conversation-offline-refinement-20261020/summary.json`. 45 outputs, 43 judged.
Nhãn do judge máy, chưa human-review — không phải gold thật.

| Nhóm | Tiêu chí | Kết quả |
| --- | --- | --- |
| transport | median tổng / English / translation | 118.43 s / 92.74 s / 25.29 s |
| schema (hard) | length:word-count | 9 |
| schema (hard) | grounding:evidence-not-substring | 2 |
| content | questionFactsAligned | 7 lỗi |
| content | atomicFacts | 5 lỗi |
| content | answerable | 2 lỗi |
| role | rolesConsistent | 10 lỗi |
| continuity | continuity | 7 lỗi |
| continuity | transfer | 3 lỗi |
| translation | translationAccurate | 1 lỗi |
| tổng hợp | contentPass | 29/43 |
| tổng hợp | semanticPass | 23/43 |

### 6.2. Edge cases bổ sung (dùng cho regression gate T07)

- timeout (Ollama không phản hồi);
- response không phải JSON;
- response bị truncate (`done_reason != stop`);
- fact count mismatch giữa English và translation;
- turn 4 (transfer — không kế thừa raw story cũ);
- role mismatch;
- continuity mismatch;
- translation mismatch.

## 7. Ràng buộc an toàn

- Không ghi credential, email thật, raw token vào fixture.
- Judge chưa hiệu chỉnh không phải nguồn sự thật duy nhất.
- Fixture phải mang seed + model version để tái chạy được.
- Không cho AI Platform đọc trực tiếp PostgreSQL nghiệp vụ.
