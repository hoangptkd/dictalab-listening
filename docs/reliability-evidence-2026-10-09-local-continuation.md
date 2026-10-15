# DictaLab — tiếp tục reliability local, 09/10/2026

Theo yêu cầu mới, **bỏ qua kiểm tra VPS** và tiếp tục source/kiểm chứng local.
Không SSH, cleanup WebLens, copy file production, deploy, push hoặc dispatch CI.
Không gọi AWS/AI thật. Không đóng nghiệm thu production hoặc tắt HP.

## Thay đổi có bằng chứng lỗi

### P1 — retry S3 bị ẩn trong AWS SDK

`S3MediaConfig` trước sửa chỉ đặt timeout 45s/15s, không chọn retry strategy.
`S3MediaLifecycle` đã tránh replay PUT ở tầng nghiệp vụ nhưng không kiểm soát
retry bên trong client. Probe bằng real SDK 2.55.11 tới HTTP loopback ghi nhận
**4 PUT** cho một lời gọi khi endpoint nhận body rồi trả 503 hoặc đóng response.
DELETE cũng gửi 4 lần trước khi trả lỗi, làm số attempt/backoff trong catalog
không phản ánh số lần gửi thực tế.

Sửa trong `src/main/java/com/dictalab/config/S3MediaConfig.java`: dùng
`StandardRetryStrategy.maxAttempts(1)`, giữ timeout và credential chain hiện có.
PUT uncertain vẫn để durable intent STAGED; không fallback local. Retry DELETE
thuộc lease/retention/backoff hiện có. Không thêm dependency, sửa API/migration,
bật queue/cleanup hoặc thay kiến trúc modular monolith.

Trade-off: mất khả năng SDK tự che lỗi transient, có thể trả 503 sớm hơn; cần recovery
theo catalog. Một HTTP request người dùng gửi lại vẫn có thể tạo UUID mới. Đây không
phải exactly-once, upload idempotency hoặc chứng minh object/version recovery thật.

`S3MediaConfigTest` có bốn ca: deadline configuration, accepted PUT + 503,
accepted PUT + response loss + request tiếp theo phục hồi, và DELETE retry explicit.
Thêm suite vào Maven reliability profile và verifier; JS contract bắt buộc giữ suite.
Không mock S3 client trong bốn ca này; endpoint/credential được thay bằng loopback/giả.

### P2 — local smoke kẹt tại native daemon startup

Runner gọi trực tiếp `pg_ctl start` qua native pipeline. Lượt đầu in “server started”,
DB nhận được `SELECT current_database()` nhưng không tiến tới createdb/app startup.
Không còn pg_ctl launcher của lượt smoke, trong khi pipeline vẫn chờ. Sau khi dừng
**đúng cluster synthetic** thì runner tiếp tục và báo database creation failed.
Không dừng PostgreSQL của công việc khác hoặc xóa cluster/log.

Dùng lại pattern từ `test-postgres.ps1`: `Start-Process -WindowStyle Hidden`, redirect
stdout/stderr, giữ process handle, startup deadline 60s, stop deadline 30s, báo failure
nếu stop không đạt. Đánh dấu startup đã được thử để finally xử lý cả partial startup.
Runner cũng save/restore và ép tắt S3, S3 cleanup, AI queue, tránh kế thừa cấu hình
ngoài phạm vi. Giữ rate limit, SLO, isolation, provider budget và workload.

JS contract và lượt smoke thật kiểm chứng runner; không coi regex contract thay cho
chạy thực tế. Chỉ dọn container Redis disposable có ID/ownership label của lượt này.

## Kết quả kiểm thử

| Lượt | Kết quả | Phạm vi |
|---|---|---|
| `red` | 4 ca, 3 fail | Tái hiện HTTP attempts 4 thay vì 1; deadline configuration đã xanh |
| `green` | 25 ca, 1 fail | Retry đã đúng; fixture chưa bỏ framing aws-chunked khi so audio bytes |
| `green-final` | 25/25, 0 fail/error/skip | Sửa decoder cho fixture bốn bytes, không tắt SDK signing/checksum |
| `tc17` | **18 suite / 170/170**, không fail/error/skip | PG17.11 + Redis8.10.2 + RabbitMQ4.3.6, Java17; verifier PASS |
| `tc18` | **18 suite / 170/170**, không fail/error/skip | PG18.4 + Redis8.10.2 + RabbitMQ4.3.6, Java17; verifier PASS |
| `default` | **310 tổng / 254 thực chạy / 56 skip**, không fail/error | Profile mặc định; skip không thay thế mandatory gate |
| `js-final` | 121/122 | Snapshot thiếu `.env.example`, không phải regression application |
| `js-replay` | 122/122 | Bổ sung sample env, không copy `.env` thật |
| `js-ship` | **123/123**, không fail/skip/cancel | Gồm contract runner sửa và S3 mandatory suite |

Mỗi PG có **88 dòng race CSV**, bao gồm 2/5/10/20 contenders, barrier và PostgreSQL
lock witness. Verifier kiểm tra đủ suite/case, không dùng BUILD SUCCESS thay evidence
gate. Hai gate cùng kết thúc 21:18:08 UTC+7; default 21:21:17 UTC+7.

Smoke cuối **PASS**, cấu hình 20 VU/60s, HP app + DB + generator, PostgreSQL18.4
native và Redis container riêng/rỗng/loopback. 176/176 hành trình hoàn tất, 1.326 HTTP
requests, không HTTP failure; p95 work **112,96ms**, p99 **493,82ms**, giữ SLO
500/1.500ms và journey success ≥99,9%. Window quan sát 101,088s gồm thời gian drain,
không gọi đó là đúng 60s chạy hoặc bài tải dài. Provider giả nhận **7 calls**, budget 200;
không gọi AWS/AI thật. Mix quan sát: 93 reads, 73 Dictation, 7 Listening, 3 Room,
không gán tỷ lệ cấu hình 60/30/5/5 cho cửa sổ hữu hạn này.

Invariant PASS; dump restore sang DB mới đối soát count/hash **27 public tables**,
fingerprint trước/sau khớp. Dataset sau chạy: 41 accounts, 319 history sessions,
7 Listening sessions, 11.072 sentence attempts, 57.468 word attempts,
57.169 word events. Dump SHA256
`FA7B13F8112102555285626354B9AB7D05A419087DB3B2A364601E007961CC2D`.
Đây là SQL restore, không phục hồi Redis state, S3 object hoặc media/JAR end-to-end.

App/provider/cluster smoke đã dừng, bốn port 15437/18282/18283/18380 không còn listen;
Redis fixture được xóa sau kiểm tra ownership label. Giữ cluster/log/dump test local.
Không dừng container `codex-lb` và `dictalab-redis` có trước. Docker Desktop được
khởi động lại để chạy tests; không install/update Docker hoặc dừng daemon sau đó.

## Source, artifacts và tái hiện

Raw root: `target/reliability-local-continuation-20261009/` (Git ignored, nội bộ).
Giữ cả lượt đỏ, fixture error, snapshot error, startup stall và lượt kiểm chứng sau sửa.

- `java-verified-source.zip`: **418 input files**, SHA256
  `A8BE16C57A7C98919706FFA2E9880C8A250B39F1C38E077AE7E399E4E71DDBDF`;
  source của các gate Java/default.
- `ship-source.zip`: **418 input files**, SHA256
  `27D86F93E7AEBDEADC88EA98C771F3AA3824FAEB833B6234C9B3FCEF5BC6A8E4`;
  sau gate chỉ đổi smoke runner và JS contract tương ứng, toàn bộ Java/test Java,
  resources, POM, workflow và verifier giữ nguyên bytes. JS cuối và smoke dùng bản này.
- JAR `tc17`, `tc18`, `default` cùng SHA256
  **`D9869DAA9BDE599120AA67932030458EB79ED680852EC45807952DCD2D2D495B`**.
  Không gán JAR này cho artifact production đang chạy.
- JAR của smoke cũng khớp hash trên; 418 final input khớp workspace sau kiểm chứng.
- Manifest và checks xác minh tất cả file bên trong từng ZIP khớp SHA-256 input;
  không đóng gói cluster/database/profile cá nhân vào source ZIP.

Tái hiện các gate bằng lệnh trong [portfolio](reliability-portfolio.md). Giải nén source
ZIP vào thư mục mới, dùng JDK17/Maven/Docker/Node và output directory mới. Smoke cần
PostgreSQL binary 18.4, k6, Python và Redis riêng/rỗng/loopback; dùng runner hiện có,
không nhận endpoint production. Không tải/install dependency mới trong batch này.

Raw có test credentials và dữ liệu synthetic; rà soát bản sao trước công bố, không
sửa raw gốc. Các source ZIP không chứa `.env` thật hoặc production dump. Giữ backup
evidence trước khi dọn `target`.

`handoff-bundle-final.zip` giữ hai source ZIP, JAR, docs, XML/CSV, child/restore logs,
các lượt lỗi và smoke raw/accounting/metric series, dump synthetic cùng fingerprints.
Không chứa cluster directory hoặc auth/profile cá nhân. `manifest.json` trong bundle
ghi kích thước/SHA-256 từng file; checksum bundle nằm trong `handoff-final-check.log`
bên ngoài để tránh self-reference. PowerShell 5.1 parser, UTF-8 12 file và whitespace
check đều đạt. Không sửa raw hoặc ghi đè bundle đầu tiên.

## Trạng thái kế hoạch hiện hành

- Đã xử lý local: các F1–F8 có bằng chứng trong các batch trước, gate mandatory và
  retry S3 SDK/runner local của batch này. Có [hồ sơ phỏng vấn](reliability-portfolio.md).
- **P0 còn mở:** full S3 object recovery/backup/version/IAM/retention, cross-store
  recovery point. Loopback và mock không thay thế AWS thật; release gate vẫn giữ.
- **P1 chưa nghiệm thu vận hành:** CI remote, runtime/release/rollback production.
  Bỏ qua VPS theo yêu cầu, không tự chấp nhận các mục này.
- **P2 còn mở:** load/soak/stress dài trên topology mục tiêu. Smoke ngắn local không
  thay thế; không hạ resource guards hoặc SLO để pass.

Ponytail giữ thay đổi nhỏ trên client/runner có sẵn. Testing skill giữ bốn ca transport
độc lập và assertions về side effects; các transaction tests dùng commit/SQL thật.
Encoding Guard kiểm tra UTF-8 tài liệu/scripts. Không tuyên bố toàn Giai đoạn 1 đã
production-ready hoặc kích hoạt shutdown khi operational gates còn mở.
