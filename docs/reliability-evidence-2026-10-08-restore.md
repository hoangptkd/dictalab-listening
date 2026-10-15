# DictaLab — Restore isolation và Redis recovery, 08/10/2026

Bổ sung cho [batch trước](reliability-evidence-2026-10-08-next.md), tập trung F9. Giữ modular monolith, API, migrations và dependencies hiện hữu. Đây là bằng chứng local, **không phải triển khai production hoặc chứng nhận full disaster recovery**.

## 1. Rủi ro và thay đổi

| Ưu tiên | Bằng chứng trước sửa | Thay đổi và trade-off |
|---|---|---|
| P0, auth restore isolation | Script restore lấy URL Redis production rồi đổi logical DB15; không chứng minh store riêng/rỗng. Key mới nhưng previous key được kế thừa. | Bắt buộc `RESTORE_REDIS_URL` hoặc secret DPAPI riêng `restore-redis-url.xml`; helper kiểm tra endpoint và DBSIZE chỉ đọc. Rotate key và buộc previous key rỗng. Operator phải cấp endpoint riêng; không tự provision hoặc flush. |
| P0, sai target/config kế thừa | Chỉ đặt `DB_URL`/`REDIS_URL` không chặn direct `SPRING_DATASOURCE_URL`, Hikari JDBC URL hoặc Spring Redis URL. JVM option có thể đặt system property JSON. | Builder `New-RestoreSmokeConfiguration` tạo JSON cho DB/Hikari/Redis/keys; clear và phục hồi ba biến JVM options. Secrets nằm trong môi trường process, không trong tham số JVM. Không bảo vệ khỏi operator cố tình sửa script hoặc quyền quản trị máy. |
| P0, background mutation | `SecurityConfig` bật `@EnableScheduling` ở mọi profile. Tắt Rabbit flags không tắt legacy Listening, email expiration hoặc lease recovery. | Chỉ profile `restore-smoke` không đăng ký scheduler; profile thường giữ nguyên. Queue, AI/TTS keys, SMTP from/host, S3/cleanup, personal import và remote telemetry/health probes tắt trong smoke. Không chạy worker recovery trong drill. |
| P1, release gate đến quá muộn | Restore Redis chưa được xác minh trước `Stop-AppService`. | Updater gọi `-CheckRedisOnly` trước dừng service. Deploy source biên dịch/stage helper Java17; workflow upload thêm raw restore evidence. Release sẽ bị chặn nếu endpoint thiếu hoặc không rỗng. Chưa chạy updater trên VPS. |
| P1, Redis URL hợp lệ bị từ chối | Helper gửi `AUTH "" password` với URL `redis://:password@...`, đồng thời URLDecoder có thể biến `+` thành khoảng trắng. | Password-only dùng AUTH một đối số; ACL username dùng hai đối số; bảo toàn `+`. Test Redis có requirepass thật. Không thêm Redis client dependency cho helper. |

Source chính: `scripts/restore-drill-windows-vps.ps1` (hai function builder/preflight, launch và environment finally); `scripts/load/RedisIsolationCheck.java` (URI/TLS/AUTH/DBSIZE); `src/main/java/com/dictalab/config/SecurityConfig.java` (`BackgroundScheduling`); updater/deploy/workflow là callers. Không đổi checksum migration V1–V27.

Endpoint comparison dùng host+port, bỏ qua logical DB; chuẩn hóa loopback aliases. Remote Redis bắt buộc TLS có hostname verification, timeout connect/read 5s. URL có query/fragment hoặc sai scheme bị từ chối. Output chỉ trạng thái hoặc tên exception, không URL/password. Helper không gửi FLUSH, DEL hoặc sửa dữ liệu.

**Giới hạn preflight:** DBSIZE chỉ chứng minh logical DB được chọn rỗng tại thời điểm kiểm tra. So sánh hostname không phát hiện mọi DNS alias, proxy hoặc hai endpoint tới cùng backend. Nó không khóa store trước các client khác. Operator phải xác nhận ownership, instance riêng, quyền truy cập và độc quyền writer; DB15 trên production không được dùng làm thay thế.

## 2. Nghiệm thu restore/recovery thật

`RestoreIsolationIntegrationTest` có 6 ca, bắt buộc trong reliability profile và verifier; không có test-level transaction bao quanh drill.

1. Empty Redis preflight chỉ đọc; dirty store bị từ chối và marker vẫn còn.
2. Endpoint nguồn bị từ chối dù khác logical DB. URI không hợp lệ hoặc remote plaintext không làm lộ credential.
3. Password-only và ACL username đều xác thực được với mật khẩu chứa literal/encoded `+`; self-test capacity helper còn hoạt động.
4. Tạo user/lesson/session/job thuộc riêng test; dùng `pg_dump -Fc` và `pg_restore --exit-on-error` thật sang DB `dictalab_restore_<uuid>` trong PostgreSQL Testcontainer. Snapshot có auth_version cũ hơn nguồn, S3 stable URL/catalog và expired RUNNING job.
5. Khởi động process ứng dụng thật bằng **builder PowerShell đang ship**. Cố tình kế thừa DB/Hikari/Redis URL nguồn, key cũ/previous key và các remote flags. Kiểm tra actual database, scheduler vắng mặt, security vẫn hoạt động; HTTP access/refresh cũ trả 401, login mới và tạo practice trả 200 đúng API hiện hữu.
6. Dừng child; SIGKILL/start **cùng Redis container**, persistence cố tình tắt để gây mất token memory. Đọc mapped port mới; khởi động app lại với cùng key restore. Giữ browser cookies: access/refresh của lần login trước trả 401; login lại thành công. Restored RUNNING job và LIVE catalog không bị scheduler chuyển trạng thái. Auth version/practice count và refresh-family metadata của nguồn không bị smoke thay đổi.

Normal-profile scheduler cũng được assert có mặt. Read assertions dùng SQL tới DB thực, không dùng JPA L1 cache để chứng minh restore. Dump và temporary DB thuộc container thử; cleanup chỉ xóa fixture riêng, không chấp nhận arbitrary target. Raw marker và child logs ở `restore-isolation/<uuid>/`; không gọi provider, SMTP hoặc S3 ngoài hệ thống.

Đây là **memory-loss recovery/fail-closed auth**, không phải chứng nhận AOF/RDB durability hoặc failover của Redis production. Drill dùng main application classes + dependencies đã compile, không dùng deployed JAR hoặc service manager của VPS. Full H2 verify kiểm tra packaging riêng.

## 3. Bằng chứng red/green và hồi quy

Raw root: `target/reliability-restore-20261008/`. Giữ nguyên failed runs, không dùng chúng làm acceptance.

| Lượt | Tests | Failures | Errors | Skipped | Ý nghĩa |
|---|---:|---:|---:|---:|---|
| `red-isolation.xml` | 3 | 3 | 0 | 0 | Regression script trước sửa isolation |
| `green-isolation.xml` | 3 | 0 | 0 | 0 | Cùng ba regression sau sửa ban đầu |
| `red-credentials` | 1 | 1 | 0 | 0 | Redis requirepass thật từ chối AUTH với empty username |
| `restore-final-subset` | 6 | 0 | 0 | 0 | Dump/restore + app restart + Redis memory loss thật |
| `js-final.xml` | 116 | 0 | 0 | 0 | JavaScript toàn bộ, gồm builder PowerShell thực thi |
| `tc17-final` | 150 | 0 | 0 | 0 | PG17.11/Redis/Rabbit, verifier 16 suite đạt |
| `tc18-final` | 150 | 0 | 0 | 0 | PG18.4/Redis/Rabbit, verifier 16 suite đạt |
| `default-canonical` | 296 | 0 | 0 | 50 | H2 toàn bộ, `verify` và executable JAR đạt |
| `native-final` | 296 | 0 | 0 | 49 | Hồi quy toàn bộ trên native PG18.4 riêng |

Hai mandatory gate có 16 suite: 15 suite/144 ca của batch trước và restore isolation/recovery 6 ca. Verifier từ chối thiếu suite/case, failure, error hoặc skip; không chỉ dựa vào Maven BUILD SUCCESS.

Counts `tests` bao gồm skipped: default thực chạy 246, native thực chạy 247. Native runner tạo/dừng đúng cluster riêng `target/postgres-test-817fb8102e494222a3e46c7ca282f882`, port15436; không dùng DB hoặc process có sẵn. Sau hai TC gate, containers do runner tạo được dọn; container `codex-lb` có trước được giữ nguyên.

Failure trung gian: `restore-first` cleanup sai thứ tự FK che nguyên nhân gốc, log startup chưa có ready marker; `restore-green` fixture dùng lesson đã bị migration ẩn và kỳ vọng 201 thay vì API 200; `restore-corrected` fixture dùng tên column không tồn tại; `restore-drill` IN_PROGRESS fixture thiếu sentence-attempt khiến API resume đúng trả 409. Các fixture được sửa, không sửa API hoặc bỏ assertions để ép xanh. `tc17` compile trước fixture cuối nên còn cùng lỗi 409.

Gate `tc17` và `ws-repeat` tái hiện timeout của test WebSocket idle revocation. Client nhận upgrade response trước server `afterConnectionEstablished` đăng ký session, nên lần check trực tiếp có thể bỏ qua session. Test nay chờ frame đầu tiên từ server (gửi sau đăng ký) rồi revoke/check; giữ nguyên các budget 10s và yêu cầu close code 1008. Không thay production WebSocket code hoặc làm lỏng tiêu chí. Các gate cuối kiểm tra lại thay đổi này.

`default-final` thử H2 với context cache giới hạn 4 và minimum-idle 0: 296 tests, 5 failures/139 errors/50 skipped, gồm lỗi H2 `The database has been closed [90098-240]` từ CHECK constraint. Cùng source chạy **lệnh build chuẩn, không hai override này**, đạt `default-canonical`. Chưa chứng minh riêng override nào kích hoạt lỗi H2; không sửa production SQL/dependency để che lỗi, không nhận variant này là được hỗ trợ. Reliability/PostgreSQL dùng cache4 vẫn được kiểm tra độc lập. Các skip của default suite là profile-gated tests, không được dùng thay mandatory gate có 0 skip.

## 4. Runbook trước release/restore thật

1. Cấp Redis restore **riêng, rỗng, độc quyền**, không dùng logical DB khác của instance production. Cung cấp `RESTORE_REDIS_URL` trong environment bảo vệ, hoặc SecureString DPAPI ở `%ProgramData%/DictaLab/auth/restore-redis-url.xml`, được đọc bởi đúng Windows identity chạy deploy. Không đặt credential vào Git, command history, manifest hoặc báo cáo.
2. Stage `RedisIsolationCheck.class` compile `javac --release 17` cạnh restore script. Có thể gọi `restore-drill-windows-vps.ps1 -CheckRedisOnly`; lệnh này không tạo DB hay dừng service. Script vẫn dùng Java/PostgreSQL path hiện hữu trên VPS; chưa xác minh runtime/path trên host live.
3. Missing class/config, dirty store, endpoint nguồn hoặc lỗi TLS/AUTH làm release fail closed **trước Stop-AppService**. Không tự flush để vượt gate. Logout dọn family của smoke; rate-limit keys có TTL có thể còn. Chờ TTL hoặc dùng endpoint thử mới; chỉ reset disposable restore store sau khi xác minh ownership ngoài script.
4. Normal restore tiếp tục kiểm tra checksum manifest, tạo DB/media tạm, smoke các artifact tuần tự và giữ logs. Preflight rỗng chạy một lần trước smokes; không yêu cầu store vẫn rỗng sau smoke đầu vì các key có TTL là hợp lệ. Environment được phục hồi cả khi cleanup lỗi; PGPASSWORD giữ đến dropdb xong.
5. Artifact mới hiểu profile `restore-smoke`. **Artifact compatibility cũ có thể chưa có scheduler guard**: key/API/queue/S3/mail flags vẫn bị tắt, nhưng không chứng nhận scheduler cũ không sửa snapshot. Review artifact cũ trước compatibility smoke; không diễn giải smoke cũ thành recovery acceptance của source mới.
6. Khi restore thật cần quiesce mọi writer, chọn recovery point chung, giữ snapshot trạng thái hiện tại, rotate auth và reconcile UNKNOWN/outbox/provider outcomes. Không bulk reset RUNNING/UNKNOWN thành PENDING, không tự bật queue sau restore. Không bảo đảm exactly-once.

## 5. Phần còn thiếu và thứ tự tiếp theo

- **P0: full S3 recovery gate.** Drill chỉ chứng minh URL/catalog được restore và cleanup không chạy. Chưa kiểm tra object bytes/version/IAM hoặc khôi phục object đã mất. Giữ cleanup mặc định false; cần inventory/backup/versioning được operator xác minh trước opt-in hoặc cutover.
- **P1: staging/VPS restore và shutdown.** Chưa chạy full script, DPAPI secret access, Java17 runtime, compatibility JAR, service-stop budgets và cutover trên VPS. Không tự deploy hoặc đổi service manager.
- **P1: network partition/uncertain outcomes.** Rabbit restart đã có bằng chứng batch trước, nhưng chưa chứng nhận quorum/disk-loss/partition; cần fault campaign tách biệt và manual reconciliation cho paid attempts.
- **P2: production load/soak và overload HTTP mapping.** Giữ guard tài nguyên và ngưỡng đã chốt; không đổi ngưỡng để làm xanh.

Ponytail giữ helper/dependencies và luồng hiện hữu; Spring testing skills giữ HTTP security/CSRF thật và committed DB assertions; encoding guard bảo toàn UTF-8. Không thêm framework, microservices hoặc tự thao tác production.

## 6. Tái hiện local

Chạy từ repository root, JDK17+, Maven, Docker và PowerShell (`powershell.exe` trên Windows; `pwsh` trên Linux). Không có cơ chế skip khi chọn reliability profile mà Docker/JDK/PowerShell thiếu. Mỗi lượt dùng thư mục output mới để giữ nguyên bằng chứng lỗi.

```powershell
mvn --batch-mode --no-transfer-progress -Preliability '-Ddictalab.build.directory=target/restore-replay17' test
if ($LASTEXITCODE -ne 0) { throw 'Reliability tests failed' }
node scripts/verify-reliability-tests.cjs target/restore-replay17/surefire-reports
if ($LASTEXITCODE -ne 0) { throw 'Reliability gate rejected reports' }

mvn --batch-mode --no-transfer-progress -Preliability '-Ddictalab.testcontainers.postgres-image=postgres:18.4-alpine' '-Ddictalab.build.directory=target/restore-replay18' test
if ($LASTEXITCODE -ne 0) { throw 'Reliability tests failed' }
node scripts/verify-reliability-tests.cjs target/restore-replay18/surefire-reports
if ($LASTEXITCODE -ne 0) { throw 'Reliability gate rejected reports' }

mvn --batch-mode --no-transfer-progress '-Ddictalab.build.directory=target/restore-replay-default' verify
if ($LASTEXITCODE -ne 0) { throw 'Default build failed' }
node --test src/test/js/*.cjs
if ($LASTEXITCODE -ne 0) { throw 'JavaScript tests failed' }
```

Local dùng JDK21.0.12.1 compile release17, Spring Boot4.1.1/Testcontainers2.0.5, PostgreSQL17.11/18.4, Redis8.10.2 và Rabbit4.3.6. Java17 runtime CI chưa chạy từ workspace này. Không dùng kết quả local để suy luận deploy, Java17 runtime, cloud durability hoặc sức chịu tải production.

## 7. Snapshot và artifact

```text
baseline-source.zip  3FA92DBB882A18DEC9B020ED4DA99AA04CC600A56148441B76D7A4D09AB21467
verified-source.zip  960EDFC9AFC07FD050AE8F239024BAAD9E879E82E6B52A19427CCB0D518B02BC
executable JAR       4ECFEC662580AE4C1F4645F3BE2A8A86239E800D9F33C9F5BE0C5B339220D74C
```

Tất cả ở `target/reliability-restore-20261008/`; JAR nằm trong `default-canonical/`. Baseline chỉ chụp các file restore/deploy/helper/POM/verifier trước batch, không phải toàn repo. Snapshot verified giữ cấu trúc đường dẫn của `src`, POM, `.env.example`, workflow, ba script restore/deploy/update, helper/verifier và native PostgreSQL test runner; docs/evidence ngoài ZIP. Không chứa `.env` hoặc secret production. Workspace có nhiều thay đổi có trước; không lấy HEAD làm source đã test. `target` bị Git ignore: sao lưu ZIP/JAR/raw trước khi dọn. Không commit/push/deploy trong batch này.

Kiểm tra cuối: UTF-8 của 12 file source/config/test/callers và hai tài liệu đạt, không cần repair; ba script PowerShell parse đạt; diff whitespace đạt. Source snapshot được đối chiếu byte hash với workspace sau test. Câu portfolio có thể dùng: “Kiểm thử snapshot PostgreSQL cũ bằng dump/restore thật, cô lập keys/Redis/workers, chứng minh token không sống lại sau Redis memory loss và đăng nhập lại thành công; lưu bằng chứng PG17/18 cùng failure runs.” Không tuyên bố exactly-once, full S3 recovery hoặc đã production-ready.
