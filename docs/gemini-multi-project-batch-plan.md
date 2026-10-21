# Kế hoạch mở rộng Gemini API cho batch generation

## 1. Bối cảnh đã xác nhận

- Có 3 tài khoản Google khác nhau.
- Mỗi tài khoản gắn với một Google Cloud project khác nhau.
- API hiện dùng Gemini API free tier.
- Tải chính là batch sinh dữ liệu.
- Mục tiêu ban đầu là khai thác quota gần 45 requests/phút từ 3 project.
- Thiết kế phải mở rộng được lên N project mà không đổi thuật toán routing.

Google áp quota theo project, không theo API key. Ba project riêng có thể có quota độc lập; ba key trong cùng project không cộng quota. RPM, TPM và RPD phải được theo dõi riêng; RPD reset lúc 00:00 Pacific. Xem [Gemini API rate limits](https://ai.google.dev/gemini-api/docs/rate-limits).

## 2. Mục tiêu

1. Gửi batch qua một quota coordinator dùng chung.
2. Phân bổ request theo project còn quota.
3. Không vượt RPM, TPM hoặc RPD của từng project.
4. Giữ safety margin để tránh burst và counter lệch.
5. Dùng model thấp cho phần lớn code và tác vụ đơn giản.
6. Chỉ dùng model mạnh khi task thất bại hoặc cần reasoning dài.
7. Không mất job khi worker restart hoặc provider trả lỗi không rõ.
8. Thêm project mới chỉ bằng configuration và secret.

## 3. Capacity mục tiêu

Giả sử mỗi project hiển thị 15 RPM:

| Nguồn | Quota danh nghĩa | Giới hạn vận hành ban đầu |
|---|---:|---:|
| Project A | 15 RPM | 13–14 RPM |
| Project B | 15 RPM | 13–14 RPM |
| Project C | 15 RPM | 13–14 RPM |
| Tổng | 45 RPM | 39–42 RPM |

Không đặt hệ thống cố định ở đúng 45 RPM. Safety margin 10–15% giúp tránh burst, retry và quota counter cập nhật trễ. Chỉ tăng dần sau khi có số liệu thật.

Công thức mở rộng:

    global_operating_rpm = sum(provider.operating_rpm for enabled healthy providers)

## 4. Kiến trúc

    Batch producer
        |
        v
    Job queue / database outbox
        |
        v
    Quota coordinator (Redis)
        |
        v
    Provider router
        |------ Project A / API key A
        |------ Project B / API key B
        |------ Project C / API key C
        |------ Project N / API key N
        |
        v
    Gemini API

Spring Boot và Python AI service phải dùng cùng quota coordinator. Không đếm RPM riêng trong RAM ở từng process.

## 5. Cấu hình provider

Mỗi provider cần:

    providerId
    projectId
    apiKey                 # chỉ runtime secret
    model
    rpmLimit
    tpmLimit
    rpdLimit
    operatingRpm
    enabled
    priority

Đặt trong environment hoặc Secret Manager. Không lưu API key trong PostgreSQL, file tracked, log, exception message hoặc metric label.

Tên biến gợi ý:

    GEMINI_PROVIDER_A_PROJECT_ID
    GEMINI_PROVIDER_A_API_KEY
    GEMINI_PROVIDER_A_MODEL
    GEMINI_PROVIDER_A_RPM_LIMIT
    GEMINI_PROVIDER_A_TPM_LIMIT
    GEMINI_PROVIDER_A_RPD_LIMIT

Provider mới chỉ cần thêm một nhóm biến tương tự.

## 6. Quota coordinator bằng Redis

Redis đã có trong repository, nên tái sử dụng.

Counter cần có:

    gemini:{provider}:rpm:{model}
    gemini:{provider}:tpm:{model}
    gemini:{provider}:rpd:{model}
    gemini:global:rpm
    gemini:global:rpd

Mỗi request:

1. Ước lượng input tokens và output token budget.
2. Atomically reserve RPM, TPM, RPD của provider.
3. Reserve global RPM/RPD.
4. Chỉ gửi HTTP sau khi reservation thành công.
5. Ghi usage thực tế sau response để đo sai lệch.

Request đã gửi không được hoàn quota chỉ vì response lỗi. Timeout sau khi đã gửi được đánh dấu UNKNOWN và không tự replay.

Window:

- RPM: sliding window hoặc token bucket 60 giây.
- TPM: token bucket theo model.
- RPD: counter theo ngày Pacific, không theo timezone máy chủ.
- Global budget: thấp hơn tổng provider quota.

Redis script phải cập nhật các counter atomically. Nếu Redis không sẵn sàng, batch phải dừng admission; không quay về counter cục bộ.

## 7. Routing policy

1. Bỏ qua provider disabled, circuit-open, cooldown hoặc hết RPD.
2. Bỏ qua provider không đủ TPM cho request.
3. Chọn provider có tỷ lệ quota đã dùng thấp nhất.
4. Nếu bằng nhau, dùng round-robin.
5. Giảm weight provider có latency hoặc 429 cao.
6. Khi hồi phục, chạy recovery probe nhỏ trước khi mở lại đầy đủ.

Không chuyển provider sau timeout không rõ kết quả nếu chưa có idempotency ở tầng batch. Chuyển provider chỉ dành cho request chưa được gửi hoặc lỗi admission trước HTTP.

## 8. Retry và lỗi

| Tình huống | Hành động |
|---|---|
| 429 có Retry-After | Cooldown provider, không retry ngay |
| 429 không có Retry-After | Backoff 30–60 giây, giảm weight |
| 5xx trước khi nhận body | Retry hữu hạn |
| Timeout trước khi gửi | Chọn lại provider |
| Timeout sau khi gửi | UNKNOWN, kiểm tra hoặc retry thủ công |
| JSON/schema lỗi | Đóng attempt, không replay vô hạn |
| RPD cạn | Hoãn batch hoặc dùng provider còn quota |
| Redis lỗi | Dừng admission, giữ job pending |

Giữ circuit breaker trong src/main/java/com/dictalab/service/ProviderGuard.java, nhưng chuyển state từ Gemini chung sang từng provider.

## 9. Phân tầng model cho code và batch

### L0 — Ollama local

Dùng qwen3.5:9b hiện có cho autocomplete, giải thích code, refactor nhỏ, unit test đơn giản, fixture và tài liệu. L0 không tiêu Gemini quota.

### L1 — Gemini Flash-Lite

Dùng gemini-3.5-flash-lite cho phần lớn batch:

- JSON theo schema;
- CRUD code;
- test case thông thường;
- mapping DTO;
- migration nháp cần review;
- dịch và chuẩn hóa dữ liệu;
- tạo nội dung hàng loạt.

Giới hạn context và output token. Không gửi toàn bộ repository nếu chỉ cần vài file.

### L2 — Flash mạnh hơn

Chỉ dùng cho bug liên module, trace dài, query khó, integration test hoặc task L1 thất bại sau validation.

### L3 — Pro

Chỉ dùng cho kiến trúc, migration nguy hiểm, review bảo mật, concurrency/transaction và quyết định release.

Model ID phải được xác minh trong AI Studio trước khi cấu hình production.

## 10. Batch pipeline

Batch producer không gọi Gemini trực tiếp. Mỗi job có:

    jobId
    idempotencyKey
    payloadHash
    taskType
    requestedModelTier
    estimatedInputTokens
    maxOutputTokens
    deadline
    attempt

Flow:

1. Tạo job và ghi outbox.
2. Worker claim job bằng lease.
3. Quota coordinator reserve capacity.
4. Router chọn provider.
5. Gọi Gemini.
6. Validate JSON, schema và content.
7. Lưu result idempotently.
8. Xóa payload nhạy cảm sau retention period.
9. Ghi metadata provider/model/usage/latency.

Batch nên chạy đều, không dồn 45 request vào đầu mỗi phút. Bắt đầu khoảng 39 request/phút rồi tăng dần.

## 11. Vị trí tích hợp

### Java

- src/main/java/com/dictalab/service/GeminiClient.java: nhận provider pool thay vì một key/model/url.
- src/main/java/com/dictalab/service/ProviderGuard.java: admission, circuit breaker và cooldown theo provider.
- src/main/resources/application.yml: pool, global limit và feature flag.
- AiBackgroundJobs và worker hiện có: nối job idempotency với quota reservation.

### Python

- services/ai-generation/app/model_gateway.py: routing provider.
- services/ai-generation/app/providers/gemini.py: credential/model theo provider entry.
- services/ai-generation/app/config.py: validate pool 1–N provider.
- services/ai-generation/tests/: fake provider, routing và quota tests.

### Hạ tầng

- compose.yaml: Redis, provider config và runtime secret.
- docs/ai-platform-runbook.md: quota, cooldown và rollback.
- ops/observability/: dashboard/alert RPM, TPM, RPD, 429 và UNKNOWN.

## 12. Metrics và cảnh báo

Theo dõi theo provider/model:

- requests theo outcome;
- input/output tokens;
- quota reserved và quota còn lại;
- 429, 5xx, timeout;
- circuit state;
- p50/p95/p99 latency;
- job pending, running, unknown, dead-letter;
- chi phí ước tính nếu chuyển paid tier.

Cảnh báo khi provider còn dưới 20% RPD, 429 vượt 1% trong 15 phút, có UNKNOWN mới, Redis không healthy, queue tăng liên tục hoặc p95 vượt SLA.

## 13. Kiểm thử

Không gọi API thật trong unit test.

    python -m pytest -q services/ai-generation/tests
    mvn -q "-Dtest=GeminiClientTest" test
    python C:\Users\HP\.codex\skills\vietnamese-encoding-guard\scripts\check_encoding.py docs/gemini-multi-project-batch-plan.md
    git diff --check

Bắt buộc kiểm thử:

1. Ba provider reserve quota đồng thời.
2. Hai worker tranh cùng token bucket.
3. Reset RPD theo Pacific time.
4. Provider trả 429 và bị cooldown.
5. Redis restart làm batch dừng admission.
6. Duplicate job không tạo hai kết quả.
7. Timeout sau HTTP request không tự replay.
8. Synthetic load 10, 20, 39, 45 và 60 request/phút.
9. Thêm provider thứ tư chỉ bằng config.
10. L0/L1/L2/L3 chọn đúng theo task policy.

## 14. Rollout

### Phase 0 — Quan sát

Giữ một provider, chạy quota coordinator ở shadow mode, đo quota và token estimate.

### Phase 1 — Hai provider

Bật A và B, giới hạn 26–28 RPM, chạy batch nội bộ, kiểm tra duplicate/retry.

### Phase 2 — Ba provider

Bật A, B, C, giới hạn 39–42 RPM, theo dõi ít nhất 24–72 giờ.

### Phase 3 — Tăng tải

Tăng từng bước 3–5 RPM. Dừng nếu 429, timeout, queue wait hoặc quality vượt ngưỡng. Không cố 45 RPM nếu AI Studio không cấp đủ quota thực tế.

### Phase 4 — N provider

Thêm provider entry và secret, chạy health probe, bật weight nhỏ, rồi tăng weight sau khi ổn định.

## 15. Rollback

1. Tắt provider mới bằng feature flag.
2. Hạ global RPM về mức provider cũ.
3. Dừng admission job mới nếu Redis quota không tin cậy.
4. Giữ job pending và UNKNOWN để kiểm tra.
5. Không xóa counters, job ledger hoặc result.
6. Không replay UNKNOWN tự động.

## 16. Definition of Done

- Ba project được khai báo bằng runtime secret, không có key trong Git.
- Java và Python dùng cùng Redis quota coordinator.
- RPM, TPM, RPD và global budget được reserve atomic.
- Routing có cooldown, circuit breaker và idempotency.
- Batch chạy ổn định ở 39–42 RPM trước khi thử 45 RPM.
- Model thấp được dùng mặc định cho code và batch đơn giản.
- Có test concurrency, timezone reset, 429, timeout và duplicate job.
- Có dashboard/alert và runbook rollback.
- Thêm provider thứ tư không cần sửa thuật toán routing.

