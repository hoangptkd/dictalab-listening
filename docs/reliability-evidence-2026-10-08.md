# DictaLab — Reliability & Concurrency: bước 1–3

Ngày kiểm chứng: 2026-10-08, máy Windows local. Phạm vi: baseline, Testcontainers/CI gate, sửa F1/F2 của audit. **Đây không phải chứng nhận toàn bộ backend đã production-ready.**

Cập nhật tiếp theo: [F3/F4/F6/F7/F8 và recovery runbook](reliability-evidence-2026-10-08-next.md). Các trạng thái “chưa sửa” và counts bên dưới là kết quả lịch sử của riêng bước 1–3; xem báo cáo tiếp theo để biết source hiện tại.

## 1. Baseline và phạm vi thay đổi

- Git HEAD: `9b175864c40732d81f56223425fa8bd1adad6d9e`.
- Workspace đã có nhiều thay đổi tracked/untracked trước lượt này, gồm AI background jobs, V26, JWT/Redis, S3 và các test. HEAD không đại diện đầy đủ source được kiểm chứng.
- Snapshot trước sửa: `target/reliability-20261008/baseline-source.zip`, gồm `src`, `pom.xml`, workflow deploy; không chứa `.env` hoặc dữ liệu production.
- Snapshot cuối: `target/reliability-20261008/verified-source.zip`, thêm script xác minh reliability. Các snapshot và báo cáo Surefire nằm trong `target`, không được Git lưu tự động.
- So sánh hash toàn bộ file `src` có trong baseline: chỉ 2 file production và 7 file test được sửa; không có file cũ bị mất. Phần còn lại giữ nguyên. Ngoài ra có bổ sung harness, verifier, tài liệu và chỉnh POM/workflow.
- Không đổi API, schema, migrations, queue flags production hay kiến trúc modular monolith. Không commit, push, deploy hoặc gọi AI/S3 thật.

SHA-256 snapshot:

```text
baseline-source.zip  667C2E36554068D540435811A6ABE8346108E19C55856FC2C0E5575371D1A187
verified-source.zip  0E025107460E8DF6467C39F90DD50F532E670501E4B0CEFB881470A6373F53E1
```

Môi trường: Maven 3.9.12; JDK 21.0.12.1, compile target Java 17; Spring Boot 4.1.1; Testcontainers 2.0.5 do BOM sẵn có quản lý; Docker Engine 29.5.3. Chạy PostgreSQL 17.11/18.4, Redis 8.10.2 và RabbitMQ 4.3.6. Các phiên bản container này là mục tiêu kiểm thử, không phải xác nhận phiên bản đang chạy trên VPS.

## 2. Những lỗi đã sửa và tiêu chí nghiệm thu

### F1 — P1: stale reader ghi đè kết quả Conversation đã commit

Bằng chứng source: `ConversationService.commit()` tại dòng 515. Trước sửa, claim tăng version nhưng commit kết quả không tăng version. Request B đọc trong lúc A đang gọi provider có thể giữ version hợp lệ sau khi A commit và giải phóng lease, rồi ghi đè JSON kết quả của A.

Sửa nhỏ nhất: tăng `version` trong cùng UPDATE commit kết quả; giữ nguyên điều kiện lease và thời hạn. Không mở transaction/khóa DB xuyên qua lời gọi AI. Các đường `next`, `answer`, `help`, `play` cùng dùng commit này.

Test: `ConversationIntegrationTest.staleReaderCannotOverwriteAResultCommittedAfterItsRead()` tại dòng 416. Test chặn B sau khi đọc snapshot trong lúc A đang xử lý, cho A commit trước, rồi mới cho B claim.

Nghiệm thu: B nhận conflict 409; JSON đã commit của A giữ nguyên; provider chấm đúng một lần trong interleaving được kiểm thử. Đây không phải bảo đảm exactly-once cho mọi tình huống provider.

Trade-off: request dùng snapshot cũ phải tải lại. Không thay contract conflict hiện có, không cần migration.

### F2 — P1: Listening chuyển trạng thái/ghi child không nhất quán

Bằng chứng source: `ListeningComprehensionService.startSecondPass()` dòng 273, `abandon()` dòng 325, `session()` dòng 897. Trước sửa có đọc không khóa, UPDATE parent bỏ qua row count hoặc ghi không ràng buộc stage. Hệ quả: pass 2 thừa khi request finish thắng; COMPLETE bị đổi thành ABANDONED; submit ghi đè ABANDONED.

Sửa:

- Trong transaction ghi, đọc owned session bằng `FOR UPDATE`, rồi mới xử lý pass/mark. Request read-only vẫn đọc thường. Cách này tái sử dụng quy tắc khóa aggregate đã có ở Practice.
- Kiểm tra số dòng chuyển sang pass 2; thất bại phải rollback INSERT child.
- Submit chỉ chuyển từ QUESTIONS; abandon dùng stage đã đọc làm điều kiện và giữ replay ABANDONED không đổi dữ liệu.
- Manual grade lấy khóa parent trước pass để giữ cùng thứ tự khóa cho đường admin.

Test và nghiệm thu:

| Test | Kết quả phải chứng minh |
|---|---|
| `losingTransitionCannotChangeWinnerOrLeaveAnExtraPass`, 5 interleaving, dòng 293 | SECOND/FINISH; ABANDON/FINISH; SUBMIT/ABANDON; FINISH/SECOND; ABANDON/SUBMIT pass 2: loser không làm thay đổi trạng thái winner, không thêm pass thừa |
| `failedSecondPassTransitionRollsBackTheInsertedPass`, dòng 365 | Inject UPDATE trả 0: transaction thất bại, pass 2 không tồn tại, stage vẫn PASS1_RESULT |
| `mutationWaitsForParentLockWhileReadOnlyStateRemainsAvailable`, dòng 334 | Có lock wait thật trong `pg_stat_activity`; mutation chỉ tiếp tục sau khi khóa được nhả; đọc state ở thread khác vẫn trả trong deadline 5 giây |
| `concurrentWriteCannotOverwriteSubmittedResponse`, 4 ca có sẵn | Draft/submit muộn không ghi đè câu trả lời đã nộp; giữ các assertion dữ liệu cũ |

Harness latch của Listening dừng sau plain read để tái hiện stale snapshot trước sửa, nhưng dừng trước locking read sau sửa để không giữ khóa trong lúc chờ request thắng. Test lock-witness độc lập kiểm chứng khóa thật, không chỉ dựa vào Mockito. Các test concurrency không có transaction bao quanh toàn bộ test; thread mutation có transaction riêng, assertion đọc DB sau commit/rollback.

Trade-off: mutation cùng một session được tuần tự hóa và có thể chờ khóa. Không có I/O provider trong các transaction này. Đây không phải kiểm chứng không-deadlock cho toàn bộ hệ thống; SQL/lock deadline production vẫn thuộc bước sau.

## 3. Testcontainers và gate CI

`pom.xml` có profile `reliability`. `ReliabilityContainers` được JUnit launcher gọi trước discovery, tạo ba container dùng port động và DB `dictalab_test`, không dùng DB/Redis/broker có sẵn. Không bật reusable containers hoặc `disabledWithoutDocker`; không khởi động được hạ tầng thì lượt chạy thất bại. Resource reaper dọn container sau JVM; container có sẵn của người dùng không bị tác động.

Chỉ thêm dependency test-scope cho JUnit launcher và module Testcontainers PostgreSQL/RabbitMQ; Redis dùng GenericContainer đi kèm. Không thêm framework resilience/runtime dependency. Module names được đối chiếu với [PostgreSQL module](https://java.testcontainers.org/modules/databases/postgres/) và [RabbitMQ module](https://java.testcontainers.org/modules/rabbitmq/); lifecycle dựa trên [JUnit launcher listeners](https://docs.junit.org/6.1.2/running-tests/using-listeners-and-interceptors.html).

Tái sử dụng test messaging/race sẵn có thay vì viết lại suite. Các test vẫn hỗ trợ runner Compose cũ qua environment; harness mới cung cấp host/port qua system properties. Queue/exchange test vẫn có tên ngẫu nhiên. Test hạ tầng kiểm tra container identities, PostgreSQL thật, 26 migrations, Redis PING và RabbitMQ connection.

Trong profile reliability, `minimum-idle=0` và context cache tối đa 4 chỉ dành cho test: tránh nhiều Spring context giữ tổng số kết nối vượt `max_connections=100`. Matrix concurrency vẫn dùng pool 30 của chính test. Cùng options được thêm vào bước PostgreSQL regression của CI; không chỉnh cấu hình pool production.

Workflow deploy có job reliability trên cả PostgreSQL 17.11 và 18.4. Deploy phụ thuộc cả `test-build` lẫn `reliability`. Script `scripts/verify-reliability-tests.cjs` yêu cầu đủ 10 suite, đủ số ca tối thiểu, không failure/error/skip và report không bị cắt cụt. Hai unit test của verifier kiểm tra missing/duplicate suite, thiếu ca, failure/error/skip và XML không hoàn chỉnh. Kiểm tra âm bằng report H2 thật đã bị từ chối với exit 1 do skip test hạ tầng, đúng kỳ vọng.

**Trạng thái:** gate đã có trong source, chạy local thành công; chưa push/chạy GitHub Actions. Java 17 runtime trong CI chưa được kiểm chứng ở lượt local này.

## 4. Kết quả red/green và hồi quy

Mỗi thư mục sau nằm dưới `target/reliability-20261008/`; XML và TXT của Java test nằm trong `surefire-reports/`. Tổng `tests` của Surefire bao gồm ca skipped, không được tính tất cả là passed.

| Lượt chạy | Tests | Passed | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|---:|
| `red-confirmed`, native PostgreSQL 18.4, trước sửa production code | 7 | 1 | 6 | 0 | 0 |
| `green-postgres`, Conversation/Listening sau sửa | 46 | 46 | 0 | 0 | 0 |
| `tc17-final`, đủ 10 suite với PostgreSQL 17.11 + Redis + RabbitMQ | 116 | 116 | 0 | 0 | 0 |
| `tc18-final`, đủ 10 suite với PostgreSQL 18.4 + Redis + RabbitMQ | 116 | 116 | 0 | 0 | 0 |
| `default-final`, hồi quy đầy đủ H2 | 263 | 227 | 0 | 0 | 36 |
| `full-postgres-final`, hồi quy đầy đủ native PostgreSQL 18.4 | 263 | 228 | 0 | 0 | 35 |
| JavaScript cuối lượt | 113 | 113 | 0 | 0 | 0 |

Red-confirmed: F1 thất bại; 4/5 interleaving F2 thất bại; rollback injection thất bại. FINISH thua SECOND đã có conditional UPDATE đúng nên ca này xanh ngay từ baseline. Không còn lỗi fixture ở lượt red-confirmed. Test lock-witness được thêm sau lượt green-postgres và đã qua trong cả hai lượt Testcontainers cuối.

10 suite reliability gồm: Conversation 30, Listening 17, concurrency matrix 28, email outbox recovery 8, JWT/Redis 11, Redis limiter 2, Listening broker 6, AI broker 3, AI background jobs 10, infrastructure 1. Matrix dùng n=2/5/10/20, mặc định 2 lần lặp. Đây là 116 ca được thực thi trên mỗi phiên bản PG, không phải 232 ca khác nhau.

Suite có sẵn được bật và chạy thật bao phủ duplicate delivery, concurrent claim, publisher return/unroutable recovery, poison-message DLQ, rollback không publish, stale revision, lease expiry, billable uncertain outcome và Redis outage/replay. AI/TTS/S3/SMTP bên ngoài được mock hoặc giả lập; PostgreSQL/Redis/RabbitMQ của reliability là thật.

Email child-process recovery có log dưới `tc17-final/worker-recovery-*` và `tc18-final/worker-recovery-*`: `crashed.log` ghi một `SIMULATED_SMTP_ACCEPTED`, `before-expiry.log` không ghi lần gửi nào, `after-expiry.log` ghi một lần. Test giết child process sau simulated acceptance, chạy lại trước/sau lease expiry và kiểm tra dữ liệu committed. Điều này minh họa at-least-once/uncertain delivery, không chứng minh email exactly-once.

Các skip trong hồi quy mặc định gồm những ca cần opt-in hạ tầng/race/benchmark. Chúng không được dùng làm bằng chứng concurrency; 10 suite reliability có gate riêng và 0 skip. Benchmark/capacity seed vẫn không nằm trong gate này.

Các lượt trung gian không đạt được giữ lại, không che giấu:

- `red`: một fixture recall chưa được khởi tạo đúng; sửa fixture rồi chạy `red-confirmed` trước khi sửa production code.
- `testcontainers`, `tc-postgres18`: lỗi tài khoản RabbitMQ do dùng environment thay vì `withAdminUser/withAdminPassword`, cùng pool exhaustion do cached contexts; đã sửa harness và chạy lại đủ suite.
- `full-postgres`: S3 test ép URL H2 trong khi profile dùng driver PG. Bỏ override datasource trong **test** để test dùng đúng profile; `default-final` và `full-postgres-final` đều qua. S3 vẫn mock, không chỉnh logic storage production.

## 5. Chạy lại

Chạy từ root repository, có Docker hoạt động. Dùng output directory mới hoặc clean trước khi bắt đầu để không đọc report cũ; không clean `target` trong khi runner native PostgreSQL còn chạy.

```powershell
mvn --batch-mode --no-transfer-progress -Preliability -Ddictalab.build.directory=target/reliability-local verify
if ($LASTEXITCODE -ne 0) { throw 'Reliability tests failed' }
node scripts/verify-reliability-tests.cjs target/reliability-local/surefire-reports
if ($LASTEXITCODE -ne 0) { throw 'Reliability reports rejected' }
```

Để chạy PG 18.4, thêm `-Ddictalab.testcontainers.postgres-image=postgres:18.4-alpine`. Lượt local của báo cáo dùng goal `test`; workflow dùng `verify`. Đường test và gate đã chạy local, workflow/packaging CI chưa chạy remote.

Các lệnh hồi quy đã chạy:

```powershell
./scripts/test-postgres.ps1 -MavenArgs @('test','-Dspring.datasource.hikari.minimum-idle=0','-Dspring.test.context.cache.maxSize=4','-Ddictalab.build.directory=target/reliability-20261008/full-postgres-final')
mvn --batch-mode --no-transfer-progress -Ddictalab.build.directory=target/reliability-20261008/default-final test
node --test src/test/js/*.cjs
```

SHA-256 hai file production sau sửa:

```text
ConversationService.java            B338035D6660BBC30BCD197F951CF55A06BB3CE8469D1AC9F0B86D6E28AECB58
ListeningComprehensionService.java  D595DCD240F24F63DD74F4BD0DB3FBCE17DFF979D984E6AC1958C54920755AFC
```

## 6. Phần chưa hoàn tất và rollout

| Audit item | Priority | Trạng thái tiếp theo |
|---|---|---|
| F1/F2 | P1 | Đã sửa và kiểm chứng local; chưa deployed |
| F5: critical tests bị skip trong CI | P1 | Source gate đã bổ sung; phải có CI xanh sau push mới xác nhận vận hành |
| F3: generator persist result thiếu fencing theo job lease | P1 | Chưa sửa; tiếp theo cần red test và persist result + terminal job dưới cùng lease/transaction |
| F4: S3 I/O trong DB lock, cleanup thiếu durable intent | P1 | Chưa sửa; cần intent/reconciliation và crash-window tests, không chỉ callback rollback |
| F6: retry bỏ qua `retrySafe()` | P2 | Chưa sửa; phân biệt admission failure và provider response/uncertain side effect |
| F7/F8/F9 | P2 | Chưa sửa graceful drain, runtime SQL/lock deadlines và full PostgreSQL/Redis/S3 restore consistency |

Chưa thử kill/restart RabbitMQ hoặc Redis process/container, network partition qua proxy, full VPS restore, tải production hay paid-provider timeout thực tế trong batch này. Test hiện tại không phủ hết crash window của F3/F4. Không tuyên bố exactly-once delivery.

Trước rollout: CI xanh trên Java 17/cả hai PG, chạy staging gate, giữ quy trình backup/rollback hiện có; không tự bật Rabbit flags. Tránh mixed old/new Conversation instances khi cutover vì instance cũ vẫn commit mà không tăng version. Không cần data migration cho F1/F2. Rollback code không yêu cầu rollback schema nhưng sẽ đưa race cũ trở lại.

Skill testing được dùng để giữ transaction theo từng request/thread và đọc DB thực sau commit/rollback; Ponytail giữ bản sửa tại shared flow, không thêm framework hoặc viết lại module; encoding guard kiểm tra UTF-8 của các file có tiếng Việt. Diff/encoding checks và các giới hạn xác minh được ghi rõ, không suy luận source-local thành deployed production.
