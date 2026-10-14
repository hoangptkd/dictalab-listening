# DictaLab — Worker death, network isolation và HTTP overload, 08/10/2026

Tiếp nối [Rabbit failure windows](reliability-evidence-2026-10-08-rabbit-windows.md). Đây là nghiệm thu code/local cho Giai đoạn 1, **không phải xác nhận đã production-ready, full disaster recovery hoặc đã deploy**. Giữ modular monolith, API JSON contract, migrations và dependencies hiện hữu.

Chốt local lúc 19:05 ngày 08/10/2026 (UTC+7): PG17 và PG18 mỗi lượt 165/165 mandatory; default verify 305 tổng / 250 thực chạy / 55 skip theo profile, không lỗi; JavaScript 116/116. Full production acceptance còn các gate vận hành ở mục 5.

## 1. Thay đổi và bằng chứng trước sửa

| Ưu tiên | Phát hiện / khoảng trống | Giải pháp và trade-off |
|---|---|---|
| P1 | Worker death trước đây mới có SQL fixture RUNNING/expired; chưa kill JVM AI consumer thật. | Bổ sung case vào suite Rabbit hiện hữu: child Spring app xử lý thật, kill đúng child sau provider acceptance giả lập, rồi thay bằng consumer của parent. Không thay production logic khi test đã xanh. |
| P1 | Broker pause/crash không chứng minh mất mạng khi broker vẫn sống. | Disconnect network của đúng Rabbit Testcontainer, kiểm tra broker ping nội bộ vẫn thành công, luôn nối lại trong finally. Không tạo proxy/dependency mới, không đổi network của container khác. Đây là single-broker network isolation, không phải quorum partition. |
| P2 | `QueryTimeoutException` và `TransactionTimedOutException` rơi vào generic 500. | Thêm hai exception vào handler DB 503 hiện hữu; giữ JSON/errorId/Retry-After 3s và log chỉ exception type. Không tự retry HTTP/SQL. Lock conflict/deadlock vẫn 409, không Retry-After. |

Source: `ApiExceptionHandler.databaseUnavailable()` và `unexpected()`; `AiBackgroundJobs.dispatch()`, `consume()` và `withLease()`; `AiRabbitQueueIntegrationTest`; `DatabaseDeadlineIntegrationTest`; `ApiExceptionHandlerTest`. Chỉ hai dòng annotation production được thêm. Không thay transaction boundaries, job state machine hoặc timeout production để làm test xanh.

Điểm đối chiếu source của snapshot ship: [mapping HTTP tại dòng 30–43](../src/main/java/com/dictalab/web/ApiExceptionHandler.java), [worker death tại dòng 248 và network isolation tại dòng 337](../src/test/java/com/dictalab/service/AiRabbitQueueIntegrationTest.java), [HTTP timeout/rollback tại dòng 66](../src/test/java/com/dictalab/support/DatabaseDeadlineIntegrationTest.java), [17 suite bắt buộc và rejection rules](../scripts/verify-reliability-tests.cjs). Line numbers áp dụng cho snapshot này, không cho HEAD lịch sử.

Callers/gate được cập nhật đồng bộ: POM reliability thêm suite HTTP advice; verifier yêu cầu Rabbit 9 ca, DB deadlines 4 ca, HTTP advice 10 ca; workflow giữ raw `ai-worker-recovery/`. Không thêm test framework, migration, technology hoặc microservice.

## 2. Tiêu chí nghiệm thu

### Worker JVM bị kết thúc thật

- Parent tạo user/conversation/job và queue riêng trên PostgreSQL/Rabbit Testcontainers. Child nhận datasource/broker trong environment JSON, không nhận secret qua command line; loại bỏ inherited JVM options và external config locations.
- Child chỉ được tạo bởi guarded disposable launcher. Trước Spring startup, kiểm tra DB `dictalab_test`. Profile `restore-smoke` tắt schedulers; profile fixture riêng bật provider mock. Child dùng queue names/worker limits của parent, không chọn queue production; SMTP, S3/cleanup, personal import, telemetry và real-provider keys bị tắt/rỗng. Media root thuộc thư mục evidence riêng.
- TTS fixture ghi marker `SIMULATED_TTS_ACCEPTED` rồi giữ response. Claim/RUNNING/attempts=1 đã commit thật; chưa có audio result. Parent kill đúng Process nó tạo, không kill PID/service tùy ý. Latch có deadline; finally dọn child còn sống.
- Rabbit phải giao lại unacknowledged message cho replacement consumer thật. Trước expiry: RUNNING, attempts không tăng và replacement không gọi TTS. Làm deadline **job fixture riêng** hết hạn rồi dispatch: UNKNOWN/OUTCOME_UNKNOWN, chưa có audio và không tự gửi lại provider.
- Một yêu cầu playback rõ ràng mới rotate job ID. Old ID bị bỏ qua; new ID DONE, attempts=2, đúng một audio row và một TTS call từ replacement. Child log có đúng một marker acceptance trước kill. Tổng hai attempt giả lập là do explicit retry, không phải exactly-once hoặc automatic safe retry.

Provider là mock có checkpoint, không phải HTTP/billing thật. Real Spring consumer, PostgreSQL transactions, Rabbit delivery và JVM termination là thật. Đây là death/replacement recovery; không tuyên bố restart cùng PID hoặc provider response recovery đã được vendor xác nhận.

### Broker còn sống nhưng mất mạng

- Guard yêu cầu disposable reliability launcher; ca mạng tạo một Rabbit Testcontainer riêng trong try-with-resources, không dùng broker chung của suite. Chỉ chuyển connection factory khi listener đã dừng. Kiểm tra broker riêng có đúng một network, lưu network ID từ inspect, warm publisher, đóng kết nối khi broker còn truy cập được rồi disconnect **container này** khỏi network hiện hữu; không xóa network. Kiểm tra network map rỗng và kết nối AMQP mới thực sự thất bại, không dựa vào việc Docker command trả thành công.
- `rabbitmq-diagnostics ping` bên trong container vẫn thành công. Dispatcher có budget test 15s, giữ job PENDING/attempts=0, tăng publish failure và không gọi TTS.
- Finally nối lại cùng network và giải phóng publisher executor. `StartedAt` không đổi: broker không restart. Đọc mapped port mới từ Docker inspect, reset connection factory, làm publication deadline của fixture hết hạn rồi publish lại. Outer finally dừng listener và phục hồi đúng địa chỉ broker chung; container fault riêng được đóng/dọn. Không đổi system property port của broker chung.
- Consumer xử lý thành DONE, attempts=1, một audio row và một TTS call. Publication outcome lúc mất mạng có thể chưa rõ; existing duplicate-delivery/late-confirm tests vẫn bắt buộc, không giả định publication hoặc delivery exactly-once.

Không chứng nhận mọi loại silent/asymmetric partition, broker cluster split-brain, quorum failover hoặc disk loss. Không sửa toàn bộ networking của Docker host và không tác động `codex-lb` có trước.

### DB timeout qua HTTP và transaction rollback

- Unit advice test có 7 exception scenarios: transaction creation/connection/statement timeout/transaction timeout trả 503; constraint/lock timeout/deadlock trả 409. Giữ ba malformed-JSON cases trả 400. Response không chứa `private-db-detail`.
- Real PostgreSQL test dùng shipped Hikari initialization: statement 15s, lock 3s, idle transaction 60s. Controller **fixture**, không phải endpoint mới của app, bắt đầu transaction, ghi một hàng riêng rồi chạy `pg_sleep(30)`.
- Phải bị statement timeout thật sau 13–24s, trả 503/Retry-After 3/errorId; không lộ SQL/table name. Controller chỉ chạy một lần. Ghi trước timeout bị rollback, connection tiếp tục dùng được.
- Không có transaction bao ngoài test và không đọc JPA cache. Standalone MVC chỉ chứng minh advice/DB contract, không thay cho security integration; các security/CSRF suites hiện hữu vẫn giữ nguyên trong regression. Controller fixture có profile riêng để không bị child app scan ngoài mục đích kiểm thử.

Retry-After là tín hiệu cho client/operator, không là quyền replay mutation có side effect. Một 503 từ lỗi connection khi commit vẫn có thể là uncertain outcome; cần đọc trạng thái/idempotency key đúng nghiệp vụ trước retry.

## 3. Raw evidence

Root: `target/reliability-final-20261008/`. Mỗi lượt có build directory/log riêng; giữ failed runs, không ghi đè bằng chứng cũ.

| Lượt | Tests | Failures | Errors | Skipped | Ý nghĩa |
|---|---:|---:|---:|---:|---|
| `red-http` | 10 | 2 | 0 | 0 | Hai timeout thực tế rơi vào 500 |
| `red-db-http` | 1 | 1 | 0 | 0 | PG statement timeout thật: expected 503, actual 500 |
| `green-http` | 14 | 0 | 0 | 0 | Advice + DB deadlines/rollback sau sửa |
| `worker-first` | 1 | 0 | 0 | 0 | Child process death/replacement với production job logic hiện hữu |
| `network-first` | 2 | 0 | 0 | 0 | Network isolation và child death, fixture ban đầu |
| `rabbit-recovery2` | 9 | 0 | 0 | 0 | Rabbit suite sau sửa reset/cleanup, trước broker fault riêng |
| `tc17-isolated` | 165 | 0 | 0 | 0 | 17 suite mandatory, fixture ship broker riêng; verifier PASS |
| `tc18-isolated` | 165 | 0 | 0 | 0 | 17 suite mandatory, fixture ship broker riêng; verifier PASS |
| `default` | 305 | 0 | 0 | 55 | H2 regression + verify/JAR; trước bước di chuyển reset connection cuối |
| `default-ship` | 305 | 0 | 0 | 55 | 47 suite, latest source, verify/JAR PASS; 250 ca thực chạy |
| `native` | 305 | 0 | 0 | 54 | Toàn bộ regression trên native PG18.4 riêng; fault mạng profile-gated |
| `js-final.xml` | 116 | 0 | 0 | 0 | JavaScript toàn bộ, gồm verifier |
| `js-ship.xml` | 116 | 0 | 0 | 0 | JavaScript chạy lại với snapshot ship; exit 0 |

Mandatory PG17/18 kết thúc lúc 19:03 ngày 08/10/2026 (UTC+7), mỗi lượt đạt 165/165 và verifier PASS, không failure/error/skip. Không lấy subset hoặc Maven BUILD SUCCESS thay verifier. Child logs nằm dưới `ai-worker-recovery/<job-id>/killed-worker.log`. Counts default/native bao gồm skipped: tương ứng 250/251 ca thực chạy; không dùng các skip thay gate mandatory. Native runner tạo/dừng đúng cluster `target/postgres-test-22dc8a8524e146c0b79269558e18c571`, loopback port15437; đã kiểm tra port không còn lắng nghe. Không chạy trên DB có sẵn.

`gate-negative.log` xác nhận gate mới từ chối bộ báo cáo 152 ca cũ. `js-ship.xml` kiểm thử verifier reject thiếu suite, thiếu case, duplicate, failure/error/skip và XML không đầy đủ. Không có skip được chấp nhận bởi gate mandatory.

Lượt `tc17` trước fixture mạng cuối đạt 165/165; `tc18` có 1 failure/6 errors: Docker detach không cắt ngay established forwarding, nên metric failure không tăng. Cleanup nối network nhưng không cập nhật địa chỉ trong finally, gây lỗi setup dây chuyền khi Docker remap cổng. Lượt `rabbit-recovery` dừng đúng fork Surefire sau khi thread dump chứng minh `channel.close` đợi phản hồi broker vì reset connection được gọi **sau** khi detach. Đã nối lại đúng disposable container trước khi dừng fork, không dừng process/container người dùng.

Sau sửa reset/cleanup, `rabbit-recovery2` đạt 9/9 và `tc18-final` đạt 165/165, nhưng `tc17-final` còn 1 failure/1 error: drain timeout và mất redelivery sau fault mạng trên broker dùng chung. Lượt lỗi được giữ nguyên. Không kết luận Docker bridge detach/reconnect tương đương transparent network healing của mọi socket/listener. Fixture ship tạo broker fault **riêng** để trạng thái Docker forwarding không làm nhiễu các ca crash/drain trên broker chung; không đổi production timeout/ACK semantics hoặc làm lỏng assertion. Mandatory gate được chạy lại trên cả PG17/18 với fixture riêng.

## 4. Tái hiện

Java17+, Maven, Docker và PowerShell; local thực chạy JDK21 compile release17. Chọn thư mục output mới và giữ raw khi lỗi.

```powershell
mvn --batch-mode --no-transfer-progress -Preliability '-Ddictalab.build.directory=target/final-local-replay17' test
if ($LASTEXITCODE -ne 0) { throw 'Reliability tests failed' }
node scripts/verify-reliability-tests.cjs target/final-local-replay17/surefire-reports
if ($LASTEXITCODE -ne 0) { throw 'Reports rejected' }

mvn --batch-mode --no-transfer-progress -Preliability '-Ddictalab.testcontainers.postgres-image=postgres:18.4-alpine' '-Ddictalab.build.directory=target/final-local-replay18' test
if ($LASTEXITCODE -ne 0) { throw 'Reliability tests failed' }
node scripts/verify-reliability-tests.cjs target/final-local-replay18/surefire-reports
if ($LASTEXITCODE -ne 0) { throw 'Reports rejected' }

mvn --batch-mode --no-transfer-progress '-Ddictalab.build.directory=target/final-local-default' verify
if ($LASTEXITCODE -ne 0) { throw 'Default verify failed' }
node --test src/test/js/*.cjs
if ($LASTEXITCODE -ne 0) { throw 'JavaScript tests failed' }
```

## 5. Trạng thái Giai đoạn 1 và điểm chặn vận hành

| Hạng mục | Bằng chứng code/local | Chưa được chứng minh ở production |
|---|---|---|
| Transactions/concurrency | Race matrix, unique constraints, lock order, lease fencing và rollback trên PG17/18; xem các batch trước | Actual workload/long transactions/capacity trên host live |
| Async jobs | Durable DB outbox, confirms/returns, DLQ/poison, duplicates, limits/drain; bổ sung JVM death và network isolation | Queue flags đang bật hay tắt ở VPS; topology/durability của broker live |
| Distributed consistency | Atomic local result+DONE; UNKNOWN không blind paid retry; isolated DB/Redis restore, durable S3 catalog/tombstone | Full cross-store recovery point, S3 object bytes/version/IAM/backup |
| Resilience | Bounded provider calls/body/permits/circuit, SQL deadlines và 503/409 mapping | Vendor billing/idempotency guarantees, full provider uncertain-outcome reconciliation |
| Release/recovery | Scripts/gates/runbook và local dump/restore/Redis memory loss | Java17 runtime CI/VPS, DPAPI, old compatibility JAR, service manager/stop deadlines/cutover |
| Load/soak | Bộ tải/guards/invariants đã có, không đổi SLO để pass | Load/soak dài trên tài nguyên tách biệt; không lấy unit tests thay benchmark |

P0 S3 full restore là release blocker. Phải có inventory/backup/versioning và quyền thử trên target riêng trước khi restore object; giữ cleanup default false. Không tự bật versioning/IAM, xóa object, dừng service hoặc deploy. P1 staging/VPS cần scope/approval riêng. Những phần này không thể được đóng chỉ bằng sửa code hoặc mock.

AI và Listening queue có implementation nhưng mặc định false trong main config. Các test bật queue riêng; không xác nhận runtime production hiện tại. Các báo cáo deployment cũ ghi sự kiện tại ngày tương ứng, không làm bằng chứng rollout cho source mới.

Yêu cầu tắt máy: chỉ máy HP local, không VPS; không force-close ứng dụng. “Sau khi hoàn thành” không được hiểu thành “tắt khi bị chặn hoặc test đang chạy”. Cần xác nhận nếu người dùng muốn chốt phần local và tắt trong khi operational gates vẫn còn; không tự đánh dấu toàn Giai đoạn 1 hoàn tất để kích hoạt shutdown.

## 6. Snapshot và phạm vi

Baseline source ZIP lấy từ snapshot batch Rabbit trước, SHA256 `C78ECC0808EB6DC1F46AA2A391B0F66D16B32CBF3B74765DD0F92D5ED369EF17`. Snapshot intermediate `verified-source.zip`: `6535749A76B0FAD8CA1B0B2ED332D02CCBA5C448BDACE86D6EC911B54E3D30EF`; `acceptance-source.zip`: `42ED9B561628A67BC99E34DDD4B5394B890F122AF026630CB718FBCA6A7ABF63`. Hai bản này không đại diện fixture network broker riêng. Snapshot cuối `ship-source.zip`: `922EB875F8C9EFC10D1D3C2AB3300F80CD1F354384138F324961EBC1F9A8C71E`.

JAR cuối `default-ship/english-dictation-0.1.0-SNAPSHOT.jar`: `DDB0C3725AA8B84DF009FC9C628C1BD0CE7901D039942D1B801C676EFC23F4C4`, trùng JAR của lượt `default` trước đó. Production bytes thay đổi từ batch trước vì bổ sung mapping timeout; không gán hash này cho artifact đã deploy. Toàn bộ 350 file của thư mục snapshot `ship-source/` đã được so hash với workspace sau hai gate cuối, không có khác biệt.

Snapshot giữ cùng 350 đường dẫn source/config/scripts; docs và raw nằm riêng, không chứa `.env` hoặc production secrets. Workspace có thay đổi có trước: không lấy HEAD làm source đã test. Không commit/push/deploy. `target` bị Git ignore; sao lưu bằng chứng trước khi dọn.

Gói bàn giao nội bộ `target/reliability-final-20261008/handoff-bundle.zip` chứa snapshot ship, JAR cuối, các báo cáo/runbook, JUnit XML và child-worker logs của gate cuối, các log lượt lỗi và lượt thành công. Không chứa database dump, `.env` thật hoặc toàn bộ thư mục build. Raw evidence có test credentials và dữ liệu synthetic; phải rà soát/loại bỏ thông tin nội bộ trên bản sao trước khi công bố portfolio, không sửa raw gốc. Encoding check cho các file batch này và whitespace check đều PASS. Sau kiểm thử, không còn container test chạy; `codex-lb` có trước được giữ nguyên.

Ponytail giữ production diff ở hai dòng annotation và reuse test infrastructure. Spring testing skills yêu cầu committed DB/rollback thật, bounded fault injection và HTTP contract assertions; encoding guard kiểm tra UTF-8. Không dùng mock provider làm chứng nhận AI thật. Portfolio có thể trình bày các cửa sổ lỗi và trạng thái phục hồi, thay vì tuyên bố exactly-once hoặc “mọi crash không mất dữ liệu”.
