# Bàn giao chạy benchmark Conversation bằng model thật

## Mục tiêu và phạm vi

Chạy đúng pipeline đã triển khai với Ollama `qwen3.5:9b`, giữ kết quả của cả lần sinh đầu và lần repair, rồi đánh giá riêng cấu trúc, độ dài và chất lượng CEFR. Đây là kế hoạch giao việc; chưa có kết quả benchmark mới.

- Code nền: commit `ac4dada74e075168e976b86b50cf1b99defdac10`, nhánh `codex/practice-drafts-audio-20261017`. Ghi lại HEAD thực tế khi chạy; commit tài liệu sau mốc này không làm thay đổi hợp đồng sinh bài.
- Chạy offline, dữ liệu synthetic, một workflow mỗi lần. Không cần khởi động Java, FastAPI, RabbitMQ, PostgreSQL hay Docker để chạy CLI capture.
- Giữ nguyên prompt, bundle, dataset, seed, schema, validator và số lần repair trong lần đo đầu tiên. Không chỉnh prompt giữa chừng để cứu kết quả.
- Audio/TTS, triển khai production và mở mức cho người học nằm ngoài đợt benchmark. C2 giữ PLANNED, không sinh bài C2.

## Checklist thực hiện

- [ ] Kiểm tra checkout, môi trường Python/Ollama, model digest và hash hai bundle/dataset.
- [ ] Chuẩn bị thư mục run riêng và trace hai attempt; kiểm tra phần quan sát không đổi hành vi pipeline.
- [ ] Chạy đủ 30 mẫu A1/A2/B1 và giữ mọi mẫu lỗi trong mẫu số.
- [ ] Chạy đủ 60 mẫu shadow B2/C1 với cùng cấu hình đã khóa.
- [ ] Chấm deterministic từ capture và trace; tách first attempt, repair và kết quả cuối.
- [ ] Review semantic độc lập; chuẩn bị mẫu cho chủ dự án review thủ công.
- [ ] Tổng hợp số liệu, ví dụ lỗi, giới hạn và lệnh tái hiện; không tự cấp quyền release.
- [ ] Kiểm tra thay đổi thuộc nhiệm vụ, commit/push theo AGENTS.md và xác minh remote nếu có sửa code/tài liệu.

## 1. Hai bộ chạy chính xác

| Đợt | Dataset trong `services/ai-generation/tests/fixtures/evaluation/` | Bundle trong `artifacts/prompts/` | Số mẫu |
|---|---|---|---:|
| A1/A2/B1 | `cefr-b1-v7-10x3.json` | `conversation-cefr-b1-v7-20261021.json` | A1: 12, A2: 9, B1: 9 |
| B2/C1 shadow | `cefr-shadow-v1-10x3.json` | `conversation-cefr-shadow-v1-20261021.json` | B2: 30, C1: 30 |

Tổng cộng **90 workflow**. Mỗi dataset dùng seed **17, 43, 71**. Workflow bình thường gồm sinh tiếng Anh và dịch; khi validation lỗi chỉ được repair một lần theo code hiện có. Không gọi model thêm để chọn đáp án đẹp hơn.

**Bộ đầu không phải 30 mẫu riêng B1.** B1 chỉ có ba tình huống × ba seed = chín mẫu. Đây là kiểm tra hồi quy ban đầu, chưa đủ để khẳng định chất lượng B1 nói chung. Nếu sau này cần 30 mẫu riêng B1, tạo dataset và bundle phiên bản mới sau lần đo này; không sửa dataset hiện tại.

Khoảng từ: A1 8–35, A2 18–55, B1 30–90; shadow B2/C1 tạm dùng 30–90 để so sánh format ngắn với B1. Kết quả shadow không chứng minh năng lực theo dõi bài nói dài B2/C1. Hai dataset này không thay thế kiểm thử phiên legacy 20–65 hoặc warm-up 8–55.

### Các giá trị phải đối chiếu

Model digest của cả hai bundle:

```text
56671c2ab9385f9cfcb404638e32cd62d88e3501d44822208363c010179a3c90
```

| Bundle | SHA-256 canonical của bundle | Hash dataset canonical |
|---|---|---|
| B1 v7 | `09274af4b93c9562aa188ed920bc24cea3cb2016c74680083fcdddfacc3f1ae5` | `cb7e7cd336f6fcb52e445132ba65ea4ac1208e998e917c1a860a72de7ef08d41` |
| Shadow v1 | `23a4e2adc68842a01aa19b0e686fa4f031f2e2ca87bea02189762293e1dbb97b` | `ff108ce35f6d088c1d763fdcf7d748cbb428d9f6e7e77c051fae1c2a44d3b6b9` |

Bundle hash loại trường `sha256` trước khi hash; dataset hash dùng JSON canonical. Dùng `app.registry.artifact_hash`, `load_artifact`, `verify_model` và `tools/ai-eval/report.py:digest`; `Get-FileHash` trên JSON có indent không phải các hash canonical này.

## 2. Preflight và khóa cấu hình

1. Đọc `AGENTS.md`, tài liệu này, `docs/conversation-cefr-classification-plan-2026-10-21.md` và `tools/ai-eval/README.md`. Phân biệt Conversation với thư viện Listening/Dictation khác.
2. Kiểm tra `git status`, HEAD, branch. Bảo toàn thay đổi ngoài nhiệm vụ, đặc biệt `scripts/run-production-load.ps1` và các file untracked. Không reset checkout hoặc dùng `git add .`.
3. Dùng Python hiện có nếu import được `httpx`, `pydantic`, `pydantic_settings`. Nếu thiếu, cài vào môi trường ảo từ `services/ai-generation/requirements.lock`; không nâng phiên bản phụ thuộc ngoài nhiệm vụ. Ghi phiên bản Python/Ollama và gói thực tế.
4. Kiểm tra `http://127.0.0.1:11434/api/tags` và model digest. CLI cũng xác minh digest trước capture. Model thiếu hoặc khác digest là blocker hạ tầng; không tự pull tag mới hoặc sửa digest trong bundle.
5. Ghi CPU/GPU/RAM, `ollama ps` khi model đã được nạp, thời điểm bắt đầu/kết thúc và có tải model khác hay không. Không chạy hai đợt đồng thời hoặc benchmark tải khác cùng lúc.
6. Lưu manifest chỉ gồm trường cấu hình cho phép; không dump toàn bộ environment, Settings hoặc `.env` vì có thể chứa credential.

Cấu hình sinh bắt buộc cho cả hai đợt:

```text
primary_provider=ollama
production_mode=false
fallback_enabled=false
model=qwen3.5:9b
temperature=0.3
translation_temperature=0.1
num_ctx=8192
num_predict=1000
max_concurrency=1
```

Chọn **timeout 600 giây/workflow** cho lần đo chất lượng offline này vì smoke trước chạy CPU đã chậm. Ghi timeout vào manifest và báo cáo; timeout không có trong `generationConfig` hiện tại. Không tăng giảm giữa đợt. Kết quả với timeout 600 giây không chứng minh đáp ứng timeout mặc định 180 giây hoặc SLA production. Không tự chỉnh token/context budget khi gặp output bị cắt.

## 3. Lưu trace trước khi chạy hàng loạt

### Giới hạn công cụ hiện tại

- `generate.py` giữ task cuối được chấp nhận. Khi repair vẫn lỗi, record có `task=null`, `transport=error`; lỗi cuối không tự được lưu đầy đủ trong record.
- Vì vậy, tỷ lệ độ dài của task cuối có thể đẹp chỉ vì validator đã loại bài sai. Không dùng chỉ số đó thay cho tỷ lệ model sinh đúng ngay lần đầu.
- `run.py`/`metrics.py` có schema, exact evidence và các semantic flag cũ; **chưa kiểm tra đủ số từ, reasoningEvidence hoặc mức CEFR mới**.
- `shadow_report.py` có các kiểm tra shadow và mẫu số đầy đủ, nhưng chưa tổng hợp trace từng attempt hoặc latency percentile.

### Công việc nhỏ model nhận nhiệm vụ cần làm trước

Bổ sung cơ chế quan sát **chỉ trong runner offline**, đủ lưu sidecar JSONL theo `(caseId, seed, attempt)`; dùng lại `generate.capture`, `run_workflow`, `run_shadow_workflow`, `SeededClient` và validator đang có. Có thể bọc node validation hiện tại để ghi state trước/sau khi gọi hàm gốc. Không viết lại pipeline hoặc sửa luật production để lấy số liệu.

Sidecar tối thiểu gồm: request ID, level, turn, seed, attempt 1/2, merged task synthetic khi có, danh sách validation error, số từ, thời lượng/phần gọi model quan sát được, trạng thái timeout/provider/translation failure. Với lỗi xảy ra trước validation, ghi phase và loại lỗi; không gán nhầm là lỗi độ dài. Không ghi header Authorization, token hoặc toàn bộ HTTP request.

Thêm kiểm tra nhỏ chứng minh wrapper gọi hàm gốc đúng một lần, giữ nguyên seed/prompt/schema/options/temperature, không thêm repair, không nuốt exception và vẫn lưu attempt bị chặn. Chỉ đo observer overhead nếu có ảnh hưởng đáng kể. Hiện chưa có flag `--trace-attempts`; không gọi một flag chưa được triển khai.

Không cần chạy model thật chỉ để test wrapper. Nếu có pilot sinh thật, lưu riêng và ghi rõ không thuộc 90 mẫu chính; không chọn lại seed hoặc chọn kết quả pilot làm kết quả chính.

## 4. Thứ tự chạy và lệnh có sẵn

Các lệnh dưới là CLI hiện có, chưa có sidecar tự động. Sau khi bổ sung observer offline ở bước 3, giữ nguyên các tham số và ngữ nghĩa capture. Chạy tại thư mục gốc repo bằng PowerShell; cấu hình environment chỉ có hiệu lực trong process đang chạy. Nếu mỗi tool call mở process mới, phải lặp lại phần cấu hình và đúng đường dẫn run đã tạo.

### 4.1. Khởi tạo một run

```powershell
Set-Location -LiteralPath 'C:\Users\HP\Desktop\English_dictition'
$benchRun = Join-Path (Get-Location) ('data/cefr-benchmark/' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $benchRun -ErrorAction Stop | Out-Null
$env:PYTHONUNBUFFERED = '1'
$env:DICTALAB_AI_PRODUCTION_MODE = 'false'
$env:DICTALAB_AI_PRIMARY_PROVIDER = 'ollama'
$env:DICTALAB_AI_FALLBACK_ENABLED = 'false'
$env:DICTALAB_AI_OLLAMA_URL = 'http://127.0.0.1:11434'
$env:DICTALAB_AI_MODEL = 'qwen3.5:9b'
$env:DICTALAB_AI_MODEL_DIGEST = '56671c2ab9385f9cfcb404638e32cd62d88e3501d44822208363c010179a3c90'
$env:DICTALAB_AI_TEMPERATURE = '0.3'
$env:DICTALAB_AI_TRANSLATION_TEMPERATURE = '0.1'
$env:DICTALAB_AI_NUM_CTX = '8192'
$env:DICTALAB_AI_NUM_PREDICT = '1000'
$env:DICTALAB_AI_MAX_CONCURRENCY = '1'
$env:DICTALAB_AI_TIMEOUT_SECONDS = '600'
$env:DICTALAB_AI_REGISTRY_DIR = (Join-Path (Get-Location) 'artifacts/prompts')
```

Lưu tên thư mục tuyệt đối vào ghi chú bàn giao để tiếp tục đúng run. Không dùng đường dẫn output đã tồn tại. Với máy khác, chỉ đổi workspace/Ollama URL theo môi trường được giao, giữ hợp đồng model/config/dataset.

### 4.2. Chạy 30 mẫu A1/A2/B1 trước

```powershell
$env:DICTALAB_AI_ARTIFACT_VERSION = 'conversation-cefr-b1-v7-20261021'
$env:DICTALAB_AI_ARTIFACT_SHA256 = '09274af4b93c9562aa188ed920bc24cea3cb2016c74680083fcdddfacc3f1ae5'
python tools/ai-eval/generate.py --dataset services/ai-generation/tests/fixtures/evaluation/cefr-b1-v7-10x3.json --output "$benchRun/supported-raw.json"
if ($LASTEXITCODE -ne 0) { throw 'Supported capture failed; inspect evidence before continuing.' }
```

Sau capture, kiểm tra đủ 30 key duy nhất, không chỉ dựa vào exit code: CLI có thể kết thúc thành công dù nhiều record có `transport=error`. Đánh dấu lỗi model/quality nhưng vẫn chạy đủ các mẫu dự kiến. Lỗi hạ tầng hệ thống như Ollama chết, sai digest hoặc hết dung lượng cần khắc phục trước khi tiếp tục.

B1 smoke trước có bài lệch vai khách/nhân viên, bài 26 từ và gắn nhãn inference cho chỉ dẫn trực tiếp. Đây là nhóm lỗi cần kiểm tra, không phải kết quả đo của run mới. Không dùng dữ liệu cũ thay cho lần chạy mới.

### 4.3. Chạy 60 mẫu B2/C1 shadow

```powershell
$env:DICTALAB_AI_ARTIFACT_VERSION = 'conversation-cefr-shadow-v1-20261021'
$env:DICTALAB_AI_ARTIFACT_SHA256 = '23a4e2adc68842a01aa19b0e686fa4f031f2e2ca87bea02189762293e1dbb97b'
python tools/ai-eval/generate.py --shadow --dataset services/ai-generation/tests/fixtures/evaluation/cefr-shadow-v1-10x3.json --output "$benchRun/shadow-raw.json"
if ($LASTEXITCODE -ne 0) { throw 'Shadow capture failed; inspect evidence.' }
```

Không truyền B2/C1 vào HTTP endpoint hoặc đặt header `X-AI-Mode: shadow` để thay cho CLI này. Nếu B1 chưa đạt quality gate, vẫn có thể hoàn tất 60 mẫu B2/C1 để thu thập bằng chứng theo nhiệm vụ này, nhưng kết quả là thăm dò, chưa có baseline B1 đạt chuẩn.

### Khi gián đoạn

CLI hiện không có resume. Nó ghi checkpoint sau mỗi mẫu; không ghi đè file cũ hoặc chạy lại toàn bộ rồi chọn bản tốt hơn. Observer/helper offline có thể chạy phần key còn thiếu với đúng seed/config/bundle và lưu file mới, sau đó hợp nhất có kiểm tra trùng/thiếu/hash. Nếu thử lại key đã có kết quả, lưu như lần chạy bổ sung riêng; giữ kết quả chính kể cả lỗi. Không biến hết thời gian chờ của tool thành lý do hủy tiến trình model còn hoạt động.

## 5. Chỉ số deterministic phải có

Mỗi dòng báo cáo dùng key `caseId:seed`, kèm level, turn, task type dự kiến/thực tế, số từ, lỗi và tham chiếu capture/sidecar.

| Nhóm chỉ số | Cách tính và mẫu số |
|---|---|
| Coverage | Đã hoàn tất / dự kiến: A1 12, A2 9, B1 9, B2 30, C1 30; ghi riêng thiếu/trùng |
| First-attempt length | Số mẫu có attempt 1 hợp lệ về độ dài / toàn bộ mẫu dự kiến của mức; mẫu chưa sinh được không tính pass |
| First-attempt structure/evidence | Gọi validator thực theo level/profile; tách word count, grounding, slot/fact, taskType và reasoningEvidence |
| Repair | Số mẫu cần repair, repair thành công, repair thất bại; mẫu số repair và mẫu số toàn bộ phải được ghi rõ |
| Final output | Số workflow trả task hợp lệ / toàn bộ mẫu dự kiến; ghi riêng provider error, timeout, dịch lỗi, validation rejection |
| Final length | Báo cả tỷ lệ trên toàn bộ mẫu dự kiến và trên số task cuối thực sự có; không gọi hai mẫu số là cùng một tỷ lệ |
| Latency | p50/p95/p99 và max theo mức, số mẫu có thời gian; ghi cold/warm và timeout 600 giây |

Đếm từ giống validator Java/Python hiện tại bằng whitespace ASCII. Mọi quote phải là substring chính xác; ít nhất một reasoning segment chứa `evidenceEn`. Không đòi kết luận suy luận xuất hiện nguyên văn; không bắt hai segment khi một segment đã có đủ dấu hiệu. Validator cấu trúc không tự chứng minh quan hệ suy luận đúng hoặc mức CEFR phù hợp.

Dùng `app.validation.validate_task` cho supported với level/profile của case; cho shadow thêm frozen `shadow_profile` tương ứng. Tổng hợp số từ của attempt bị chặn từ sidecar, không bịa transcript khi capture có `task=null`. Nếu chưa có trace cho một metric, ghi **không đo được**, không suy ra first-pass từ task cuối.

## 6. Review semantic độc lập

Cho reviewer đọc `judgeInput`, transcript, câu hỏi, answers, context và rubric. Người/model review phải đánh giá độc lập với model sinh Qwen; rationale sinh ra chỉ là một claim cần kiểm tra. Ghi rõ reviewer/model/version, ngày và giải thích cho flag fail. Model nhận bàn giao có thể trực tiếp làm AI review nếu có đủ năng lực; không tự gọi một dịch vụ trả phí chưa được cho phép. Nếu thiếu reviewer phù hợp, bàn giao review packet và để trạng thái PENDING, không tự điền `true`.

Review toàn bộ task có output, không chỉ mẫu đẹp. Các record không sinh được/thiếu review không được tính pass toàn đợt. Phân biệt không áp dụng: continuity cho lượt 2/3, transfer cho lượt 4; không phạt lượt 4 vì không giữ sắp xếp cũ.

Các flag boolean hiện có bắt buộc:

```text
questionFactsAligned, answerable, atomicFacts, translationAccurate,
rolesConsistent, continuity, transfer
```

Ghi thêm các flag CEFR sau cho cả supported và shadow, nhưng lưu ý `run.py` chỉ đọc nhóm cũ:

```text
cefrLevelAppropriate, taskTypeAccurate, cuesSupportConclusion,
noExternalKnowledge, textOnly
```

- **B1:** gist, quan điểm/thái độ thể hiện rõ và suy luận/dự đoán một bước có căn cứ đều hợp lệ. Không giới hạn B1 vào dữ kiện trực tiếp. Loại suy luận giả do transcript đã nói thẳng hành động; loại sai vai khách/nhân viên.
- **B2:** xem lập luận, hỗ trợ/phản đối, concession/contrast, agreement, quan điểm có điều kiện; không gọi B2 chỉ vì dùng từ khó.
- **C1:** xem quan hệ ít báo hiệu trực tiếp, ý định/lập trường ngầm, ngữ cảnh, hedging và phạm vi phát biểu; phải tích hợp dấu hiệu và phân biệt cách hiểu gần nhau.
- Chi tiết trực tiếp vẫn có thể xuất hiện ở B2/C1. Đánh giá cả độ phức tạp transcript lẫn yêu cầu câu hỏi; yêu cầu bao phủ kỹ năng đặc trưng ở cấp dataset, không bắt mọi task chứa inference.
- Không dùng tone audio, nhiều người nói hoặc kiến thức văn hóa ngoài bài làm điều kiện đạt trong đợt này.

Giữ `*-raw.json` nguyên trạng; tạo `supported-reviewed.json` và `shadow-reviewed.json`. Giữ nguyên metadata sinh, cập nhật trường review riêng như `judgeVersion`/review reference và ghi method trong manifest; không sửa text hoặc cấu hình để hợp thức hóa verdict.

Chuẩn bị review thủ công cho chủ dự án: toàn bộ chín mẫu B1, tối thiểu sáu mẫu B2 và sáu mẫu C1, phủ từng `expectedTaskTypes`, đồng thời kèm mọi lỗi nghiêm trọng hoặc bất đồng. AI review phải ghi nhãn AI; **không tự tạo `manualReview.status=approved`**. Chỉ thêm `manualReview: {status: "approved", reference: "..."}` sau review thủ công thực sự. Nếu chưa có, giữ thiếu coverage theo đúng báo cáo.

## 7. Xuất báo cáo và đọc gate đúng cách

### Báo cáo chung A1/A2/B1

```powershell
python tools/ai-eval/run.py --dataset services/ai-generation/tests/fixtures/evaluation/cefr-b1-v7-10x3.json --capture "$benchRun/supported-reviewed.json" --output "$benchRun/supported-base-report.json"
```

Báo cáo này phải đi kèm bảng deterministic/CEFR bổ sung ở bước 5–6. Không dùng `--baseline` với báo cáo R1/R2 cũ vì khác dataset/config; không đổi `approvalStatus` để làm gate pass. Hiện chưa có baseline được duyệt cho run này.

### Báo cáo B2/C1

Giữ artifact version/SHA environment ở shadow như bước 4.3:

```powershell
python tools/ai-eval/shadow_report.py --dataset services/ai-generation/tests/fixtures/evaluation/cefr-shadow-v1-10x3.json --capture "$benchRun/shadow-reviewed.json" --output "$benchRun/shadow-report.json"
```

Tool kiểm tra hash dataset/bundle, model và generation config. Nó luôn trả `releaseAllowed=false`. `candidateGatePassed` còn phụ thuộc coverage thủ công; exit code 0 chỉ cho biết đã tạo được report, không có nghĩa benchmark đạt hoặc được release.

### Ngưỡng đọc kết quả

- Theo kế hoạch sản phẩm: 100% task được chấp nhận phải qua schema và exact grounding; tối thiểu 95% mẫu dự kiến trong khoảng từ; tối thiểu 90% mẫu dự kiến qua semantic review độc lập. Báo first-pass riêng, kể cả khi final sau repair đạt ngưỡng.
- Với 30 mẫu/mức B2/C1: ngưỡng độ dài 95% tương đương ít nhất **29/30**, semantic 90% tương đương **27/30**. Shadow tool còn yêu cầu transport/structure/focus đạt 100% và đủ manual coverage; không bỏ các điều kiện này để công bố pass.
- Với chín mẫu B1: hai ngưỡng trên đều cần **9/9**, nhưng cỡ mẫu nhỏ nên không kết luận chắc chắn về chất lượng tổng quát. Ghi kết quả A1/A2 riêng, không gộp để che lỗi B1.
- Nếu chưa có reviewer hoặc human review, ghi **PENDING REVIEW** cho phần tương ứng. Không chạy C2, không bật SUPPORTED và không sửa bundle development thành approved.

## 8. Đầu ra cần bàn giao

Trong thư mục run đã tạo, cần có:

1. Manifest: run ID, HEAD/branch, phiên bản runtime, hardware, thời gian, timeout, cấu hình cho phép, bundle/dataset/model hashes.
2. Hai capture raw, sidecar attempts và log chẩn đoán không có credential; giữ đủ 90 key hoặc danh sách thiếu cùng nguyên nhân.
3. Hai capture đã review, reviewer references và review packet thủ công; chưa review phải đánh dấu rõ.
4. Báo cáo supported, shadow và bảng bổ sung first-pass/repair/length/CEFR/latency theo từng mức.
5. Bảng lỗi có key, loại lỗi, đoạn bằng chứng, expected/actual và lệnh tái hiện. Tách lỗi model, prompt/rubric, validator và hạ tầng.
6. Kết luận ngắn: mức nào đạt/chưa đạt/chờ review, lỗi độ dài còn bao nhiêu ở attempt 1 và sau repair, độ phân biệt B1/B2/C1, giới hạn format ngắn và đề xuất sửa tiếp.

`data/` đã được Git ignore. Chỉ đưa báo cáo synthetic đã rà soát vào Git nếu cần, dùng tên `artifacts/reports/ai-platform-cefr-real-benchmark-<runId>.json`; không stage credential, environment hoặc toàn bộ log. Nếu có code observer/tests hoặc tài liệu mới, chạy kiểm tra liên quan và UTF-8, rồi commit/push đúng nhánh theo AGENTS.md. Xác minh remote SHA; báo riêng nếu GitHub Actions không đọc được. Không triển khai production.

Khi quality fail, hoàn tất báo cáo trước. Việc sửa prompt/model là vòng thử nghiệm tiếp theo với bundle/run ID mới và cùng nguyên tắc paired-seed; không sửa kết quả đã đo.

## Prompt ngắn để giao model khác

> Đọc và thực hiện `docs/conversation-cefr-real-model-benchmark-handoff-2026-10-21.md` trên nhánh hiện tại. Chạy benchmark model thật Ollama qwen3.5:9b: 30 mẫu A1/A2/B1 và 60 mẫu B2/C1 shadow. Bổ sung quan sát offline tối thiểu để giữ cả attempt đầu và repair; giữ nguyên prompt, bundle, seed, schema, validator và cấu hình sinh. Lưu đủ raw evidence, số liệu theo từng mức, review packet và báo cáo. Không tự điền manual approval, không sửa prompt để cứu baseline, không chạy C2 hoặc sửa audio, không deploy. Theo dõi tiến trình dài đến khi hoàn tất hoặc có blocker hạ tầng cụ thể; ghi rõ phần chưa review và không tuyên bố chất lượng đạt khi thiếu bằng chứng.
