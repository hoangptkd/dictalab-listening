# Gemini multi-project batch: kế hoạch và bàn giao triển khai

## 1. Phạm vi

Ba tài khoản Google, mỗi tài khoản một project, dùng Gemini API free tier để sinh dữ liệu batch.
Mở rộng lên N project bằng cấu hình, không sửa thuật toán. Mục tiêu danh nghĩa nếu mỗi project
có 15 RPM là 45 RPM; khởi đầu an toàn ở 13 RPM/project, tổng 39 RPM.

Quota theo project/model, không theo số API key. RPM, input TPM và RPD là các giới hạn độc lập;
RPD reset lúc 00:00 Pacific. Hạn mức thực tế phải xem trong AI Studio và có thể thay đổi.
Nguồn: [Google rate limits](https://ai.google.dev/gemini-api/docs/rate-limits).
Model ID hiện được Google công bố là
[`gemini-3.5-flash-lite`](https://ai.google.dev/gemini-api/docs/models/gemini-3.5-flash-lite).

“Peak requests per day” trên biểu đồ là đỉnh sử dụng lịch sử, không phải cấu hình để tăng quota.
Tổng RPD khả dụng phụ thuộc quota riêng từng project, traffic ngoài hệ thống và số request lỗi.
Ví dụ ba project 500 RPD cho trần danh nghĩa 1.500 request/ngày, không phải 45 RPM suốt 24 giờ.
Gemini Pro cho người dùng và API free tier không được coi là cùng một gói hạn mức.

Đã đối chiếu trực tiếp trong Chrome/AI Studio ngày 2026-10-22: cả ba project người dùng cung cấp
đều là free tier, Flash-Lite có 15 RPM, 250K TPM và 500 RPD. Cấu hình runtime riêng trên máy chọn
13 RPM/nguồn và global 39 RPM, 1.350 RPD để có margin. Khóa và mã tài khoản không lưu trong tài liệu
Git. Việc cấu hình không phải bằng chứng batch thật đạt throughput đó hoặc đã deploy production.

## 2. Thiết kế đã triển khai

    JSONL batch / Java / Python API-worker / teacher collector
                              |
                              v
           Redis quota v2: reserve atomic trước HTTP
                              |
                              v
            round-robin các project còn quota, đúng model
                  |           |           |
                  A           B           C ... N
                              |
                              v
             validate -> checkpoint -> kết quả / lỗi

Không tự đổi model. Không chuyển sang key cũ khi pool đang bật. Không tự gửi lại request đã gửi
nhưng không biết kết quả. Không thêm SDK hay orchestration framework không cần thiết.

### Thành phần

| Thành phần | Tệp chính | Trách nhiệm |
|---|---|---|
| Cấu hình Java | `GeminiProviderProperties.java`, `application.yml` | JSON 1-N nguồn, validation, che khóa |
| Java client | `GeminiClient.java` | Giữ model yêu cầu, chọn nguồn trước HTTP |
| Java quota | `GeminiQuotaCoordinator.java` | Redis / memory test, global admission atomic |
| Lua dùng chung | `services/ai-generation/app/gemini_quota.lua` | Chính xác một protocol cho Java và Python |
| Python gateway | `app/gemini_pool.py`, `app/model_gateway.py` | Quota, cooldown, lựa chọn nguồn |
| Batch runner | `tools/ai-eval/gemini_batch.py` | JSONL, SQLite WAL, giới hạn lần gọi/thời gian |
| Teacher | `training/generate_teacher_data.py` | Pool tùy chọn, giữ model/cap/kiểm duyệt cũ |
| Vận hành | `compose.yaml`, `ops/observability/` | Redis bền vững, metrics, alert, Grafana |

## 3. Cấu hình mở rộng

Nguồn chuẩn chung là `GEMINI_PROVIDERS_JSON`, một mảng camelCase. Mẫu không có khóa thật:
[`gemini-providers.example.json`](gemini-providers.example.json).
Khai báo đầy đủ các trường để Java/Python đọc giống nhau:

- `id`: nhãn vận hành duy nhất.
- `projectId`: mã project chuẩn dùng nhất quán; chấp nhận project number.
- `apiKey`: runtime secret, không Git/log/metric/CLI argument.
- `model`, `url`: model và endpoint HTTPS; HTTP chỉ được phép trên loopback khi test.
- `rpmLimit`, `tpmLimit`, `rpdLimit`: hạn mức đã xác nhận trong AI Studio.
- `operatingRpm`: mức vận hành, không cao hơn RPM; 0 nghĩa là dùng rpmLimit.
- `enabled`, `priority`: bật nguồn và thứ tự ổn định trước round-robin.

Một project/model không được khai báo hai nguồn bật dù khác key. Không dùng hai cách viết khác
nhau cho cùng project để tránh đếm hai lần. Các biến A/B/C cũ chỉ phục vụ Java; JSON ghi đè chúng
và là cách dùng chung được khuyến nghị.

Biến chung: `GEMINI_QUOTA_ENABLED`, `GEMINI_QUOTA_BACKEND`,
`GEMINI_GLOBAL_RPM_LIMIT`, `GEMINI_GLOBAL_RPD_LIMIT`.
Python dùng `GEMINI_QUOTA_REDIS_URL`; Java dùng cấu hình Redis hiện có.
Cả hai phải trỏ cùng database. Flag mặc định tắt, không cần khóa để chạy test.

Thêm project thứ tư: thêm một entry và secret, xác nhận model/quota, tăng global budget nếu muốn,
drain job rồi khởi động lại tất cả consumer nhất quán. Không sửa router.
TPM/RPD/global = 0 nghĩa là không chặn dimension đó, không phải “quota bằng 0”; điền quota thật
trước khi bật free tier.

## 4. Quy tắc quota

1. Ước lượng bảo thủ: UTF-8 input/schema bytes + output budget + overhead.
2. Reserve RPM/TPM/RPD project và RPM/RPD global trong một Lua operation.
3. Chỉ gửi HTTP khi admission thành công.
4. Request bị từ chối trước HTTP không tiêu request quota.
5. Request đã gửi không được hoàn counter chỉ vì timeout, 429 hay 5xx.

RPM/TPM dùng cửa sổ trượt 60 giây. RPD dùng ngày Pacific, xử lý cả DST.
Redis TIME làm đồng hồ chung. Keys có prefix `{dictalab-gemini-quota}:v2:`, identity theo project/model.
Nếu Redis lỗi: dừng admission, giữ job chờ; không tự chuyển sang RAM.
Memory backend chỉ dành cho local/test một process; không dùng để cộng quota nhiều worker.

Hệ thống chỉ biết các call đi qua coordinator. Counter mới không phản ánh traffic trước khi bật
pool hoặc call từ AI Studio/công cụ khác. Đối soát usage khi kích hoạt lần đầu, không xóa counter
để “lấy lại” quota. Redis AOF/noeviction và backup vẫn cần được vận hành đúng.

## 5. Routing và lỗi

Duyệt round-robin nguồn enabled, đúng model; bỏ qua quota cạn/cooldown/circuit-open.
Priority chỉ sắp thứ tự ban đầu, không phải trọng số thích nghi.

| Sự kiện | Xử lý |
|---|---|
| Cạn RPM/TPM | Chờ capacity; thử nguồn khác chỉ trước HTTP |
| Cạn RPD | Chờ reset Pacific hoặc nguồn khác còn quota |
| 429 | Cooldown project/model theo Retry-After, mặc định 60 giây |
| 5 lỗi transport/5xx | Circuit project/model 30 giây |
| Success đến sau 429 | Không xóa cooldown của call 429 khác |
| Redis lỗi trước HTTP | Admission unavailable, không fallback |
| Redis lỗi sau HTTP | Giữ kết quả gốc; không biến thành lỗi retry-safe |
| Timeout/transport không rõ | Checkpoint UNKNOWN, không replay tự động |
| JSON/schema lỗi hoặc HTTP 429/5xx đã gửi | FAILED trong offline batch, không tự retry |

Không có tự đổi L1 sang L2/L3, adaptive latency weighting hay recovery probe riêng.
Chỉ bổ sung các cơ chế đó khi có số liệu chứng minh cần; hiện cooldown/circuit hết hạn sẽ cho
admission trở lại trong quota được cấu hình. Global concurrency guard hiện có vẫn được giữ.

## 6. Batch bền vững

Input JSONL: `id`, `messages`, tùy chọn `schema`; schema inline Draft 2020-12, không ref bên ngoài.
Model/output budget/temperature lấy từ runtime settings, không nhận khóa trong job.

SQLite WAL lưu ID, hash input/config, trạng thái, owner, timestamp, kết quả và lý do lỗi.
Claim bằng transaction để hai lượt chạy cùng ledger không cùng gọi một ID.

- DONE: có JSON hợp lệ theo schema, kèm actual model/provider/sourceId.
- PENDING: chưa gửi; có thể chờ admission trong deadline.
- RUNNING: đã claim; nếu cũ hơn 605 giây khi resume thì chuyển UNKNOWN.
- FAILED: lỗi đã đóng attempt, không tự gửi lại.
- UNKNOWN: cần đối soát, tuyệt đối không tự reset để replay.

`--max-calls` giới hạn HTTP attempts mỗi lần chạy, không thay thế quota ngày.
`--max-seconds` chặn admission/chờ mới và giới hạn deadline HTTP.
Concurrency 1-5; tạo task theo chunk hữu hạn.
Giữ input và ledger trong `data/` bị Git bỏ qua, giới hạn quyền và retention chủ động.
Chỉ hỗ trợ ledger trên một host, không dùng SQLite trên network share.

Đây là batch ứng dụng trên chat-completions, không phải Google Batch API.
Một request có thể sinh nhiều item, nhưng một workflow generation + translation có hai request.
Validation schema không chứng minh nội dung đúng: giữ review ngữ nghĩa/human gate trước khi import.

Teacher collector dùng pool tùy chọn nhưng không thay model đã phê duyệt, không tăng cap 19 calls,
không retry/repair, không bỏ calibration/human review. Plan cũ đã khóa hash mã nguồn phải được
giữ nguyên; tạo plan mới nếu đổi mã hoặc model.

## 7. Model thấp để thực hiện code

Đây là hướng dẫn bàn giao cho coding agent, không phải tự động đổi model trong app:

1. Giao mỗi lần một thay đổi nhỏ với file sở hữu, input/output và lệnh test rõ ràng.
2. Cho model thấp đọc AGENTS.md, luồng liên quan và test hiện có trước khi sửa.
3. Dùng Ollama local đang có cho giải thích, fixture, tài liệu và chỉnh sửa đơn giản nếu môi trường có.
4. Dùng Flash-Lite cho phần việc giới hạn: cấu hình, mapping, test, JSON và batch nhỏ.
5. Khi test không qua, trả lỗi thực tế cùng diff nhỏ cho agent sửa; không gửi toàn repository.
6. Đưa concurrency, security, transaction hoặc release lên người review/model mạnh khi cần quyết định.
7. Không để agent đổi quota, model teacher, billing hay trạng thái approval để làm test “xanh”.

Mẫu prompt bàn giao:

    Mục tiêu: <một thay đổi cụ thể>.
    Chỉ sửa: <file/module>. Đọc callers và test liên quan trước.
    Giữ API contract, validation, secrets, no-replay và artifact gates.
    Tái sử dụng dependency có sẵn. Không đổi model hoặc chạy API thật.
    Acceptance: <hành vi đúng + các ca lỗi>.
    Chạy: <lệnh test>. Báo file thay đổi, kết quả, phần chưa kiểm tra.

Không cần triển khai tầng L0/L1/L2/L3 tự động chỉ để hỗ trợ quy trình code này.

## 8. Kiểm thử và vận hành

Không dùng API thật trong test. Kiểm tra bao gồm admission đồng thời, quota global, RPD qua phút/
midnight Pacific/DST, cooldown 429, circuit, Redis fail-closed, duplicate/checkpoint, schema,
timeout không replay, source thứ tư và Java/Python cùng Redis protocol.

    python -m pytest -q services/ai-generation/tests tools/ai-eval training/tests/test_generate_teacher_data.py
    mvn -q "-Dtest=GeminiClientTest,GeminiProviderPropertiesTest,GeminiQuotaCoordinatorTest" test
    git diff --check

Redis integration dùng `TEST_GEMINI_REDIS_URL` trỏ Redis disposable loopback DB 15.
Test xóa DB 15 đã chỉ định; tuyệt đối không trỏ vào Redis dữ liệu thật.
CI cung cấp Redis service riêng và chạy thêm `GeminiQuotaRedisIntegrationTest`.

Synthetic admission 10/20/39/45/60 attempts xác nhận 39 được nhận ở cấu hình 3 x 13.
Đó không phải bằng chứng API Google thật ổn định ở 39 hay 45 RPM.

Metrics shared quota xuất qua Python /metrics có token. Grafana: RPM/TPM, RPD, cooldown/outcomes.
Alert: Redis quota unavailable, dưới 20% RPD, 429 trên 1%; UNKNOWN của application jobs giữ alert cũ.
UNKNOWN của offline batch xem ở SQLite/CLI summary, không giả là metric của application job.
Counter token là reservation bảo thủ, chưa có invoice reconciliation hay chi phí paid-tier tự động.

## 9. Rollout và Definition of Done

Phần triển khai: cấu hình 1-N nguồn; Java/Python/Lua chung; batch checkpoint; teacher adapter;
Compose/secrets mẫu; test tự động; metrics/alert/dashboard; runbook và rollback.

Phần vận hành cần kiểm chứng riêng: hạn mức/account thực tế, key hoạt động, traffic ngoài pool,
model readiness, canary 1 -> 2 -> 3 nguồn, rồi quan sát 24-72 giờ trước khi nâng từ 39 lên 42/45 RPM.
Không coi test mô phỏng hoặc push branch là đã triển khai production.

Rollback: hạ limit/tắt nguồn mới, drain calls đang chạy, giữ counters/ledger/UNKNOWN.
Muốn tắt pool về legacy: dừng batch trước; không để traffic legacy ngoài coordinator chạy song song
mà vẫn coi số đếm pool là tổng usage.

Hướng dẫn chạy từng lệnh, secret runtime, URL Redis host/container và recovery:
[`ai-platform-runbook.md`](ai-platform-runbook.md), mục “Shared Gemini project quotas and offline batch”.

