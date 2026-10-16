# DictaLab — nghiệm thu runtime Java 17 local, 09/10/2026

Tiếp nối [nghiệm thu local 08/10](reliability-evidence-2026-10-08-final-local.md). Mục tiêu lượt này là chạy Maven, Surefire và child workers bằng JDK 17 thật, không chỉ compile release 17 trên JDK 21. Không deploy, không sửa production code, migrations hoặc dependencies.

Chốt local ngày 09/10/2026 (UTC+7): cả PG17/18 đạt 165/165 mandatory trên fixture mới; default verify đạt 250 ca thực chạy / 55 skip theo profile; JavaScript 116/116. Đóng khoảng trống Java 17 **local**, không đóng CI/VPS/full S3 recovery.

## Phạm vi và tiêu chí

- Runtime local: Oracle JDK 17.0.12, Maven 3.9.12, Windows HP, Docker Desktop. `runtime.log` và properties `java.version`/`java.home` trong JUnit XML xác minh runtime của fork test. Đây không phải nâng cấp bản vá JDK hay nghiệm thu runtime VPS/CI.
- Hai gate dùng PostgreSQL 17.11 / 18.4, Redis 8.10.2 và RabbitMQ 4.3.6 trong disposable Testcontainers. Mỗi gate phải có 17 suite / 165 ca, không failure/error/skip và verifier PASS. Giữ deadline/assertions; không dùng BUILD SUCCESS thay XML verifier.
- Default regression chạy lệnh `verify` chuẩn, không override H2 context cache/minimum-idle. Skipped của profile mặc định không thay gate mandatory.
- AI/TTS/S3 trong các ca tương ứng vẫn là fixtures/mocks. Kill worker, Rabbit delivery, PostgreSQL transactions, dump/restore và Redis memory loss là thật; không có paid provider hoặc production mutation.

## Lỗi tái hiện và thay đổi fixture

Trước thay đổi, cả 350 file source/config/scripts khớp snapshot ship ngày 08/10. PG17 đạt 165/165. PG18 có 165 ca, 0 failure, **1 error**, 0 skip: `liveBrokerNetworkIsolationLeavesPendingWorkRecoverable()` timeout khi đọc queue sau reconnect, tại dòng 376 của snapshot cũ. Queue/state assertions không được bỏ qua.

Log cho thấy AMQP chưa phục hồi sau Docker reconnect, không chứng minh lỗi Java 17 hoặc production state machine. Fixture cũ cho phép IP của broker được cấp lại trên bridge dùng chung; chưa có IP-before/after của lượt lỗi để kết luận đây là nguyên nhân duy nhất. Sửa fixture để giới hạn biến số: tạo network riêng, reconnect với IP và aliases ban đầu, assert chỉ một network/IP không đổi và ghi marker `RABBIT_NETWORK_RECOVERY`. Manual disposable network probe xác nhận Docker host hỗ trợ reconnect cùng IP; probe được dọn sau khi chạy.

Chỉ sửa `AiRabbitQueueIntegrationTest`; không đổi timeout 15s, budgets, ACK, retry hoặc logic production. Network và broker thuộc try-with-resources; outer finally phục hồi connection factory về broker chung. Đây là single-broker network isolation với endpoint ổn định, không phải thử quorum partition, DNS/IP failover hoặc mọi silent/asymmetric partition.

## Bằng chứng

Raw root: `target/reliability-java17-20261009/`. Lượt lỗi và subset giữ nguyên, không ghi đè.

| Lượt | Tests | Failures | Errors | Skipped | Trạng thái |
|---|---:|---:|---:|---:|---|
| `tc17` | 165 | 0 | 0 | 0 | Source trước sửa fixture; verify và verifier PASS |
| `tc18` | 165 | 0 | 1 | 0 | Không nghiệm thu: network reconnect timeout |
| `network-stable-ip` | 1 | 0 | 0 | 0 | Ca mạng sau sửa; IP trước/sau giống nhau, recovery assertions PASS |
| `tc18-final` | 165 | 0 | 0 | 0 | Verify/JAR và verifier PASS, fixture mới |
| `tc17-final` | 165 | 0 | 0 | 0 | Verify/JAR và verifier PASS, fixture mới |
| `default` | 305 | 0 | 0 | 55 | Verify/JAR PASS; 250 ca thực chạy |
| `js.xml` | 116 | 0 | 0 | 0 | JavaScript PASS, gồm verifier và script contracts |

Snapshot `ship-source.zip`: SHA256 `63D9AAA08F7878E3F9D4BEDB636B5F7B54DA9BA3874E8FAF4EB20228AE1DC26D`. Giữ cùng 350 đường dẫn; so với snapshot ngày 08/10 chỉ `src/test/java/com/dictalab/service/AiRabbitQueueIntegrationTest.java` thay đổi. Docs/raw lưu riêng; không chứa `.env` thật. Workspace vẫn dirty, không lấy HEAD thay source đã chạy.

JAR Java 17 `default/english-dictation-0.1.0-SNAPSHOT.jar`: SHA256 `2522E1E217812957D968B4BBD45136A36F8DE37097E016BB320AB027AC480D9A`, trùng JAR lượt `tc17` trước sửa fixture và cả `tc17-final`/`tc18-final`. Khác hash JAR build bằng JDK 21 hôm trước; không gán bằng chứng runtime/artifact của lượt này cho bản đã deploy.

`runtime-gates.log` xác nhận verifier PASS, đủ 17 XML mỗi gate, toàn bộ fork dùng `java.version=17.0.12` và `java.home=C:\Program Files\Java\jdk-17`, ba JAR cuối trùng hash. Source snapshot 350 file được so hash lại sau gate cuối: không khác workspace. Hai gate kết thúc lần lượt lúc 11:49:09 (PG18) và 11:50:56 (PG17), UTC+7. Các lượt `tc17`/`tc18` đầu tiên không đại diện fixture cuối.

Sau nghiệm thu không còn Java test process hoặc container test chạy; các network thử được dọn, network có trước và `codex-lb` được giữ nguyên. Không thay `JAVA_HOME`/PATH cấp máy hoặc service. Gói bàn giao nội bộ `target/reliability-java17-20261009/handoff-bundle.zip` giữ snapshot, JAR, báo cáo/runbook, raw logs và XML/child logs, gồm lượt lỗi. Raw có test credentials/dữ liệu synthetic; rà soát bản sao trước khi công bố, không sửa raw gốc. `target` bị Git ignore: sao lưu bằng chứng trước khi dọn.

## Tái hiện

Chọn build directory mới. Cấu hình `JAVA_HOME`/PATH chỉ trong process thử, không thay runtime hệ thống hay service.

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-17'
$env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
mvn --version
mvn --batch-mode --no-transfer-progress -Preliability '-Ddictalab.build.directory=target/java17-replay17' verify
if ($LASTEXITCODE -ne 0) { throw 'PG17 reliability failed' }
node scripts/verify-reliability-tests.cjs target/java17-replay17/surefire-reports
if ($LASTEXITCODE -ne 0) { throw 'PG17 reports rejected' }
mvn --batch-mode --no-transfer-progress -Preliability '-Ddictalab.testcontainers.postgres-image=postgres:18.4-alpine' '-Ddictalab.build.directory=target/java17-replay18' verify
if ($LASTEXITCODE -ne 0) { throw 'PG18 reliability failed' }
node scripts/verify-reliability-tests.cjs target/java17-replay18/surefire-reports
if ($LASTEXITCODE -ne 0) { throw 'PG18 reports rejected' }
mvn --batch-mode --no-transfer-progress '-Ddictalab.build.directory=target/java17-replay-default' verify
if ($LASTEXITCODE -ne 0) { throw 'Default verify failed' }
```

## Còn mở

P0 full S3 recovery cần backup/inventory/version/IAM và target test được operator xác nhận. P1 staging/VPS cần Redis riêng/rỗng, identity DPAPI, compatibility JAR, service-stop/cutover acceptance. P2 tải dài cần tài nguyên tách biệt. Không bật cleanup/queue production, không deploy hoặc dừng service để vượt gate. Không tự đánh dấu toàn Giai đoạn 1 hoàn tất hay tắt HP khi còn các điểm chặn này.

Ponytail giữ diff ở fixture hiện hữu. Spring testing skills giữ assertions trên DB thật và không bọc test trong transaction rollback. UTF-8 và whitespace checks cho fixture/báo cáo/runbook đều PASS. Không thêm dependencies, wrappers hoặc microservices.
