# DictaLab — bằng chứng Reliability & Concurrency để trình bày phỏng vấn

Đây là modular monolith Java 17/Spring Boot với PostgreSQL, Redis và RabbitMQ.
Phân biệt **source đã kiểm chứng local** với **tính năng đang bật/đã deploy**.
Không dùng kết quả local để khẳng định production đã đạt SLA.

## Những điều có thể giải thích và tái hiện

| Nội dung | Cơ chế trong source | Bằng chứng và giới hạn |
|---|---|---|
| Lost update và duplicate requests | Optimistic revision/CAS cho Conversation; ràng buộc uniqueness và khóa aggregate tại các luồng ghi | `ConcurrencyMatrixTest`, `ConversationIntegrationTest`; chạy các race 2/5/10/20 worker, có SQL lock witness và CSV; không coi mọi API là idempotent |
| Transaction boundaries | State transition, child writes và kết quả terminal trong transaction; external I/O ngoài DB lock | `ListeningComprehensionIntegrationTest`, `AiBackgroundJobsIntegrationTest`; rollback injection và interleaving; không có distributed transaction với provider |
| Durable asynchronous processing | PostgreSQL job/outbox là nguồn trạng thái, publisher confirm/return, ACK sau commit, lease và fencing | `AiRabbitQueueIntegrationTest`, `ListeningGradingQueueIntegrationTest`, `EmailOutboxRecoveryTest`; có duplicate delivery, poison/DLQ, broker restart và worker JVM death |
| Uncertain outcomes | Không blind retry thao tác paid AI; trạng thái UNKNOWN cần recovery theo nghiệp vụ; S3 SDK chỉ một attempt | `AiBackgroundJobsIntegrationTest`, `S3MediaConfigTest`; real SDK gọi HTTP loopback, không phải AWS/provider thật |
| S3 và PostgreSQL | Catalog STAGED/LIVE/DELETING/DELETED, retention, lease, tombstone; reference attach/release theo transaction | `S3MediaLifecycleIntegrationTest`; S3 I/O được mock, không chứng minh object backup/restore hoặc IAM thật |
| Redis failure/recovery | Auth fail-closed, refresh replay protection, endpoint restore riêng, key isolation và rebuild rate-limit khi Redis mất dữ liệu | `JwtRedisIntegrationTest`, `RedisRateLimitIntegrationTest`, `RestoreIsolationIntegrationTest`; không giả định Redis là nguồn dữ liệu durable |
| Timeouts và graceful shutdown | SQL/lock deadlines, provider bulkhead/circuit, drain hữu hạn và permit release | `DatabaseDeadlineIntegrationTest`, `WorkerShutdownTest`, các provider tests; không chứng nhận mọi network partition hay process crash |

Delivery là **at-least-once**, không phải exactly-once. Ví dụ email có thể trùng nếu
SMTP đã nhận nhưng worker chết trước DB commit. Idempotency/fencing bảo vệ trạng thái
trong DB; chúng không biến một side effect bên ngoài thành giao dịch atomic.

## Tái hiện gate local

Yêu cầu Docker local hoạt động, Maven, Node.js và JDK 17. Không nhận database/VPS
production làm target test. Dùng thư mục output mới để không đọc report cũ.

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-17'
$env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
mvn --batch-mode --no-transfer-progress -Preliability '-Ddictalab.build.directory=target/portfolio-pg17' '-Ddictalab.race-evidence=target/portfolio-pg17/concurrency-matrix.csv' verify
if ($LASTEXITCODE) { throw 'PG17 failed' }
node scripts/verify-reliability-tests.cjs target/portfolio-pg17/surefire-reports
if ($LASTEXITCODE) { throw 'PG17 mandatory evidence gate failed' }
mvn --batch-mode --no-transfer-progress -Preliability '-Ddictalab.testcontainers.postgres-image=postgres:18.4-alpine' '-Ddictalab.build.directory=target/portfolio-pg18' '-Ddictalab.race-evidence=target/portfolio-pg18/concurrency-matrix.csv' verify
if ($LASTEXITCODE) { throw 'PG18 failed' }
node scripts/verify-reliability-tests.cjs target/portfolio-pg18/surefire-reports
if ($LASTEXITCODE) { throw 'PG18 mandatory evidence gate failed' }
node --test src/test/js/*.test.cjs
if ($LASTEXITCODE) { throw 'JavaScript failed' }
```

Verifier hiện yêu cầu **18 suite / ít nhất 170 ca**, đủ suite và không failure/error/skip.
Maven BUILD SUCCESS không thay cho verifier. Lưu XML, race CSV, worker crash logs,
restore evidence, source manifest và JAR SHA-256 cùng kết quả.

[Lượt kiểm chứng mới nhất](reliability-evidence-2026-10-09-local-continuation.md):
170/170 trên mỗi PG17/18, JS 123/123; smoke local 176/176 hành trình và SQL restore 27
bảng. Đây là bằng chứng local trên snapshot cụ thể, không phải acceptance production.

[Accounting gate bổ sung](reliability-evidence-2026-10-09-accounting.md) chặn sáu
trường hợp raw bị báo PASS sai; JavaScript 131/131 và replay smoke cũ đạt đối soát.
Đây là kiểm chứng công cụ evidence, không phải lượt tải mới hay chạy lại Java gates.

## Cách mô tả trong CV

“Xây dựng bộ integration test concurrency và failure recovery bằng Testcontainers
cho PostgreSQL/Redis/RabbitMQ; kiểm chứng transaction rollback, duplicate delivery,
worker lease fencing, publisher confirms và recovery sau worker/broker crash.”

Khi phỏng vấn, chọn một lỗi đã tái hiện đỏ/xanh, chỉ ra transaction boundary,
interleaving, invariant sau commit và trade-off. Xem các báo cáo
[audit và F1/F2](reliability-evidence-2026-10-08.md),
[fencing/S3/shutdown](reliability-evidence-2026-10-08-next.md),
[worker/broker crash](reliability-evidence-2026-10-08-final-local.md) và
[runbook](reliability-evidence-runbook.md).

## Những điều chưa được khẳng định

- Queue AI/Listening và cleanup S3 trong source vẫn mặc định tắt; không tự bật khi chạy production.
- Full S3 recovery, CI remote, rollout/rollback production và capacity/soak dài chưa được đóng bằng test local.
- Theo yêu cầu hiện hành, bỏ qua kiểm tra VPS; đây không phải bỏ release safety gate.
- Không viết “chịu tải 300 người thật”, “zero data loss trong mọi crash” hoặc “exactly-once”.
- Raw test artifacts có thể chứa token/credential giả và dữ liệu synthetic. Chỉ công bố bản sao đã rà soát; giữ raw gốc nội bộ.
