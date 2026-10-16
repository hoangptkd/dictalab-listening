# DictaLab — Reliability: fencing, media recovery và shutdown

Ngày kiểm chứng: 2026-10-08, Windows local. Tiếp nối [bước 1–3](reliability-evidence-2026-10-08.md). **F3/F4/F6/F7/F8 đã triển khai và kiểm chứng local; F9 mới hoàn thành một phần. Chưa deploy, chưa chứng nhận toàn bộ backend production-ready.**

## 1. Phạm vi và trạng thái

| Hạng mục audit | Mức | Kết quả batch này |
|---|---|---|
| F3 — worker hết/mất lease vẫn persist AI result | P1 | Result và terminal job cùng transaction, kiểm tra lease sau khi lấy khóa |
| F4 — S3 I/O trong DB lock; cleanup chỉ dùng callback | P1 | Upload ngoài transaction; catalog/cleanup intent bền vững, lease và tombstone |
| F6 — Listening retry bỏ qua `retrySafe()` | P2 | Chỉ retry admission failure được đánh dấu an toàn; giữ backoff/budget |
| F7 — worker shutdown ngắt công việc quá sớm | P2 | Drain hữu hạn local executor/Rabbit listener; commit trước ACK |
| F8 — không có runtime SQL/lock deadline | P2 | Hikari áp dụng statement 15s, lock 3s, idle transaction 60s trên PG |
| F9 — PostgreSQL/Redis/S3 restore consistency | P2 | Smoke tắt remote jobs/cleanup; runbook recovery bổ sung; full restore chưa nghiệm thu |

Giữ modular monolith, URL/API upload và cơ chế ownership/CSRF/revision hiện có. Không thêm runtime dependency hoặc framework resilience. Queue AI/Listening vẫn mặc định **tắt** trong source; không tự bật flag production. Legacy local workers vẫn không có mọi bảo đảm crash recovery của durable queue. Trạng thái VPS/bucket hiện tại không được kiểm tra ở batch này; tài liệu production cũ chỉ là lịch sử, không phải bằng chứng triển khai các sửa đổi mới.

Cleanup S3 cũng mặc định **tắt** (`DICTALAB_MEDIA_S3_CLEANUP_ENABLED=false`). Intent/reference lifecycle vẫn được ghi khi S3 bật; physical deletion chỉ chạy khi operator opt-in sau backup/retention gate. Không tự xóa object chỉ vì deploy code mới.

## 2. F3 — kết quả AI phải thuộc đúng lease

Bằng chứng source:

- `AiBackgroundJobs.consume()` và `withLease()`: claim riêng; gọi provider không giữ transaction; hoàn tất theo thứ tự khóa **parent → job**. Kiểm tra `RUNNING`, đúng `lease_id` và chưa hết hạn sau khi lấy cả hai khóa.
- `ConversationService.generateQueuedAudio()` / `generateAudioWrite()` trả persistence action; `saveAudio()` vẫn kiểm tra input hiện tại. Đường legacy tái sử dụng cùng action.
- `CoachService.generateQueuedReport()` trả action ghi report đã validate, không persist trong lúc generation. Baseline không-AI không được đánh dấu thành công AI.

Terminal state và action ghi result nằm trong cùng transaction ngắn. Nếu INSERT/validation thất bại thì DONE rollback, job vẫn RUNNING; dispatcher chuyển sang UNKNOWN sau hết lease, không tự gọi lại paid provider. Worker stale không ghi result và không thay terminal state của lease mới. AUTO ACK chỉ sau khi phương thức hoàn tất; DB failure có thể redeliver, nhưng redelivery không reclaim một RUNNING/UNKNOWN job để gọi provider lại.

PostgreSQL dùng `clock_timestamp()`, không dùng `CURRENT_TIMESTAMP` của thời điểm bắt đầu transaction để xác minh deadline sau lock wait. H2 chỉ là compatibility regression, không chứng nhận semantics thời gian PG.

Nghiệm thu, `AiBackgroundJobsIntegrationTest` (16 ca, thêm 6 ca):

- Expired audio lease; replaced audio/Coach lease: **không persist result**.
- Input thay đổi giữa provider response và persistence: không để DONE thiếu audio.
- Constraint injection khi ghi Coach: DONE và report cùng rollback; sau expiry thành UNKNOWN; không tự gọi lại provider.
- Hoàn tất chờ parent lock qua thời điểm lease hết hạn: có witness `pg_stat_activity` và chờ PG thật; result bị từ chối.

Trade-off: có thể bỏ kết quả provider đã trả về nếu lease hết hạn/DB không commit được. Đây là lựa chọn bảo toàn dữ liệu, không hứa exactly-once hoặc tránh mọi chi phí trùng. Explicit retry của người dùng vẫn có thể phát sinh thêm phí.

## 3. F4 — S3 lifecycle bền vững

Migration mới `V27__s3_media_lifecycle.sql` tạo catalog/outbox gồm stable URL, bucket/prefix identity, trạng thái, upload deadline, cleanup deadline, lease, attempts và error code. Không sửa migration cũ, không backfill/rewrite bảng content, không thêm FK vào bảng hiện hữu.

Luồng upload:

1. Transaction ngắn kiểm tra lesson/revision/eligibility.
2. `MediaStorageService.storeAudio()` commit STAGED intent bằng transaction riêng **trước PUT**, key UUID bất biến, attach deadline 10 phút; upload ngoài transaction.
3. Transaction ngắn kiểm tra lại revision/eligibility, retain catalog, gắn reference và audit event cùng commit.
4. PUT outcome không rõ hoặc attach thất bại: reference cũ giữ nguyên; STAGED intent tồn tại để cleanup sau retention.

`AdminContentController.uploadAudio()` và service đều `NOT_SUPPORTED`: chỉ sửa service mà giữ transaction ở controller sẽ khiến transaction mới chờ khóa do chính caller đang giữ. Integration test đã bắt lỗi trung gian này. Revision check và event trước đây ở controller nay nằm trong transaction attach, không bị bỏ.

Reference writers ở Admin copy/attach/delete và Listening create/copy cùng tham gia catalog. Retain/release yêu cầu transaction hiện hữu. Cleanup khóa catalog, kiểm tra reference từ `lesson_sentences` và `listening_units`, rồi claim DELETING. Không giữ transaction xuyên S3 DELETE; completion có điều kiện lease. Reference còn tồn tại thì hủy cleanup. Late attachment vào STAGED hết hạn/DELETING/DELETED bị từ chối.

- Retention mặc định **7 ngày**, cleanup tối đa 10 object/lượt.
- Delete lease 120s; recovery reclaim sau deadline. DELETE cùng immutable UUID có thể lặp khi response mất; backoff tối đa 1h. Không có application-level blind replay PUT; AWS SDK retry policy hiện hữu không được viết lại trong batch này.
- Tombstone giữ lại để fence late attachment. Attempts counter cap 20 nhưng cleanup tiếp tục retry DELETE; không nhầm cap counter với giới hạn tổng số lần retry.
- Stored bucket/prefix identity ngăn worker dùng config mới xóa object thuộc backend khác. Không tự migrate object khi đổi bucket/prefix.
- URL legacy được đăng ký lazy khi reference writer đi qua; không có bulk inventory/backfill mọi orphan cũ. Không tự xóa object ngoài catalog.
- Local-disk cleanup giữ đường callback cũ; batch này không cung cấp durable catalog cho disk/private-media.

Nghiệm thu: `S3MediaLifecycleIntegrationTest` **10 ca** và `S3MediaIntegrationTest` **1 ca**, cộng storage unit tests hiện có. Bao phủ intent trước PUT/không giữ transaction, uncertain PUT, upload-before-attach crash state, DELETE response loss/backoff/tombstone, shared reference, transaction rollback, deadline/identity guard, competing cleanup/late attach, expired delete lease recovery, revision thay đổi trong lúc upload. Ca cuối kiểm tra shipped cleanup default false và disabled worker không claim/delete intent đã due. Những ca cleanup còn lại opt-in rõ ràng để vẫn kiểm thử nhánh chạy thật. HTTP suite giữ checks role/CSRF/private signing và reference rollback.

**S3 client được mock; crash S3 là dựng durable state, không phải kill process hay cloud drill thật.** Không có bằng chứng bucket versioning, IAM, lifecycle policy, inventory hoặc media restore thật ở batch này.

Trade-off vận hành: chậm giải phóng dung lượng để giữ khả năng restore. Retention phải **dài hơn cửa sổ backup/PITR thực tế**; 7 ngày không đủ nếu còn phục hồi backup 30 ngày. Retention không phục hồi object đã bị xóa nếu không có versioned/offsite backup. Theo dõi `dictalab.media.cleanup.pending/completed/failed`, tuổi intent, attempts và tăng trưởng tombstone; cảnh báo phải được đấu nối trước rollout cleanup. Chưa thay chính sách alert production.

## 4. F6/F7/F8 — retry, drain và deadline

### Retry có kiểm soát

`ListeningComprehensionService.gradeWork()` chỉ auto retry khi `ProviderGuard.Unavailable.retrySafe()` trả true. Public exception mặc định (provider có thể đã nhận request) chuyển FAILED, không tự replay. Safe admission failure giữ backoff 30/60/... giây, jitter và attempt budget 3. Hai ca mới trong `ListeningGradingQueueIntegrationTest` xác minh cả unsafe và safe path; suite hiện có vẫn kiểm chứng worker lost/uncertain và explicit retry.

`ProductionReliabilityTest` được sửa expectation cũ: một `Unavailable` không tự động đồng nghĩa với safe rejection. Các timeout/circuit/bulkhead/limiter đã có được giữ lại, không thêm retry chung cho mọi side effect.

### Graceful shutdown

- `WorkerShutdown.drain()` ngừng admission, chờ grace hữu hạn, rồi interrupt nếu hết hạn; bảo toàn interrupt của caller. Local Conversation/Listening dùng 70s.
- Conversation không giữ synchronized monitor trong lúc await: finishing worker cần chính monitor đó để hoàn tất future.
- Boot native listener customizer đặt Rabbit `shutdownTimeout=70s`, `forceStop=true`: hoàn thành delivery đang chạy, không bắt đầu phần prefetched còn lại khi dừng. `spring.lifecycle.timeout-per-shutdown-phase=75s`.
- Test helper 3 ca; customizer 1 ca; broker drain giữ provider hơn deadline cũ 5s rồi xác minh DONE/result/duplicate delivery không gọi provider lại.

**External service manager kill deadline chưa thay/chưa kiểm tra.** Budget ngoài JVM nên tối thiểu 240s cho cấu hình hiện tại, và phải đo lại thời gian stop thật vì local bean drains và HTTP/Rabbit phases có thể tuần tự. Deadline per phase không phải deadline toàn bộ shutdown. Forced kill vẫn cần durable recovery; local legacy work không được xem là crash-safe nhờ graceful drain.

### PostgreSQL deadlines

`application.yml` Hikari init SQL mặc định: statement 15s; lock 3s; idle-in-transaction 60s, có override `DB_SESSION_INIT_SQL`. `DatabaseDeadlineIntegrationTest` đọc setting **đang được ship**, kiểm tra SHOW, blocked UPDATE/rollback của write trước đó, `pg_sleep(30)` bị cancel sau khoảng 15s và DB còn dùng được. Launcher đưa setting này vào mọi context của reliability gate.

Nghiệm thu 3 ca trên PG17/18 thật. Không thêm blind transaction retry. Underlying lock timeout SQLState `55P03` hiện được driver/Spring dịch thành `UncategorizedSQLException`; API handler chưa có mapping riêng cho loại này, có thể trả 500 hiện hữu. UX overload mapping là hạng mục tiếp theo, không coi DB timeout đã đồng nghĩa 503 trên mọi endpoint.

Deadlines cũng ảnh hưởng connection Flyway: migration dài phải review và override rõ ràng cho phiên migration cần thiết; không tắt deadline thường trực để che slow query. Batch này chưa đo production query durations hoặc idle-transaction termination thật.

## 5. Bằng chứng red/green và hồi quy

Raw nằm trong `target/reliability-next-20261008/`; Java XML/TXT ở `surefire-reports/`. Counts `tests` bao gồm skipped.

| Lượt | Tests | Passed | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|---:|
| `red-fencing-retry`, trước sửa F3/F6 | 21 | 18 | 3 | 0 | 0 |
| `green-fencing-retry`, cùng ca sau sửa | 21 | 21 | 0 | 0 | 0 |
| `storage-corrected-postgres`, storage subset trung gian | 26 | 26 | 0 | 0 | 0 |
| `red-cleanup-confirmed`, trước đổi cleanup default | 1 | 0 | 1 | 0 | 0 |
| `tc17-optin`, source cuối, PG17.11/Redis/Rabbit | 144 | 144 | 0 | 0 | 0 |
| `tc18-optin`, source cuối, PG18.4/Redis/Rabbit | 144 | 144 | 0 | 0 | 0 |
| `default-optin`, H2 full `verify` + executable JAR | 290 | 246 | 0 | 0 | 44 |
| `native-optin`, PG18.4 full regression | 290 | 247 | 0 | 0 | 43 |
| JavaScript full cuối batch | 114 | 114 | 0 | 0 | 0 |

Red: expired audio lease và replaced Coach lease vẫn ghi result; unsafe Listening failure vẫn về PENDING. Cốt lõi F4 không có lượt red trên baseline chưa sửa production; thay vào đó bổ sung fault injection/rollback/concurrency acceptance và lưu các failure trung gian. Ca cleanup default cuối có red riêng: shipped setting vẫn true trước sửa, sau sửa cả nhánh disabled và cleanup opt-in qua trong mọi lượt cuối. Không gán tất cả tests mới là từng thất bại trên baseline.

15 suite mandatory: infrastructure 1, Conversation 30, Listening 17, matrix 28, email 8, JWT/Redis 11, limiter 2, Listening broker 8, AI broker 5, AI jobs 16, S3 lifecycle 10, S3 web 1, worker drain 3, DB deadline 3, listener config 1. Verifier/POM được mở rộng tương ứng; gate PG17/18 qua với 0 skip. CI deploy đã phụ thuộc gate từ batch trước, **chưa push/chạy remote**. Local JDK21 compile target17, chưa chứng nhận Java17 runtime CI.

`AiRabbitQueueIntegrationTest.confirmedPersistentJobSurvivesAbruptBrokerCrashAndReconnect()` kill SIGKILL rồi start **cùng container/data**, xác minh confirmed persistent ID-only message tồn tại sau reconnect, result một lần/paid call một lần trong test. Test chỉ tác động container được launcher tạo. Recovery cập nhật actual remapped Docker port và `CachingConnectionFactory.setAddresses()`; `setPort()` riêng không thay address resolver Boot đã cấu hình. Đây là broker restart đơn node, không phải node/disk loss hoặc quorum failover.

Email child process crash test vẫn được chạy trong gate: acceptance SMTP giả lập, kill process thật, restart trước/sau lease expiry. SMTP có thể trùng khi acceptance trước crash; không exactly-once. Redis outage/replay tests có sẵn chạy thật, nhưng chưa kill/restart Redis container hoặc kiểm tra persistence Redis sau crash.

Lượt lỗi trung gian được giữ nguyên: `storage-h2` (parameter interval cần CAST), `storage-h2-cast`/`storage-postgres` (caller giữ lock xuyên transaction suspend), `deadlines` (expectation exception class sai), `tc17-full` (Rabbit CLI stale PID sau SIGKILL), `broker-restart` (address resolver giữ port cũ), `tc18-full`/`broker-remap`/`native-full` (overload method reference compile), `red-cleanup-default` (test fixture dùng try-with-resources cho registry không AutoCloseable; sửa fixture trước chạy red-confirmed). Không lấy các lượt đó làm acceptance; không xóa report để che lỗi. `tc17-current`/`tc18-final` đã 143 xanh và full `default-current`/`native-current` đã 289 xanh trước cleanup opt-in; nghiệm thu source cuối dùng các thư mục `*-optin` ở bảng trên.

Môi trường: Spring Boot 4.1.1, Maven 3.9.12, JDK21.0.12.1/release17; Testcontainers2.0.5; PG17.11/18.4, Redis8.10.2, Rabbit4.3.6. AI/TTS/S3/SMTP ngoài hệ thống mock/giả lập. Không gọi paid provider hoặc AWS thật.

## 6. F9 — quy trình restore nhất quán (chưa chạy live)

Thay đổi source `restore-drill-windows-vps.ps1`: command line smoke buộc S3/cleanup/AI queue/Listening queue **false**, xóa AI/TTS API key theo property. Regression JS kiểm tra các flag, signing key mới và Redis DB15. Đây là static script test, không phải full restore acceptance.

Điều kiện bắt buộc khi drill hoặc restore thật, không tự thực thi thao tác xóa/chuyển production:

1. Dừng ingress và tất cả writer/worker/dispatcher; xác minh không còn instance cũ. Chọn recovery point chung và giữ checksum DB, media inventory/versions, artifact/config. Giữ bản trạng thái hiện tại để có đường quay lại; không restore đè khi chưa xác minh target.
2. Restore PostgreSQL vào DB mới, kiểm tra migrations/checksums/FK/invariants. Không đổi nội dung/checksum migration đã áp dụng. Xác minh RUNNING/PENDING rows cùng thời điểm snapshot, không coi trạng thái DB rollback là bằng chứng provider chưa nhận request.
3. Dùng Redis auth **mới/rỗng/tách biệt**, namespace `{dictalab-auth}:v1:`; không phục hồi refresh families đã rollback vì token xoay/revocation có thể sống lại. Rotate JWT signing key và **bỏ previous signing key**, bắt buộc đăng nhập lại. Script hiện tạo key mới nhưng chưa tự xóa `JWT_PREVIOUS_SIGNING_KEY` được kế thừa: operator phải bỏ giá trị đó trước drill. Empty Redis chặn family cũ nhưng không thay điều kiện key isolation.
4. DB15 trong script chỉ là phân vùng logical, không chứng nhận hạ tầng riêng/rỗng. Phải kiểm tra trước hoặc dùng endpoint dedicated. Không FLUSHALL/FLUSHDB Redis production. Không công bố Redis URL/credential trong manifest/log.
5. Giữ Rabbit jobs tắt hoặc dùng vhost/queue mới biệt lập; không consume backlog production vào DB restored. So sánh jobs/outbox với recovery point; billable uncertain attempts chuyển về quy trình UNKNOWN/manual reconciliation, không bulk reset RUNNING/UNKNOWN thành PENDING. SMTP outbox cũng có thể đã được gửi ngoài DB snapshot.
6. Giữ S3 cleanup tắt. Kiểm tra từng stable URL trong DB restored có đúng bucket/prefix/version/bytes; khôi phục phiên bản/object từ backup nếu thiếu. S3 live mới hơn DB cũ có thể chứa orphan, nhưng không được xóa trước khi inventory/recovery point xác nhận. Disk snapshot không bao gồm object `/media/s3/`.
7. Chạy smoke CRUD/auth/replay/audio trên isolated app không có quyền remote mutation; xác nhận JWT cũ bị từ chối, reference không thiếu, uncertain jobs không tự gọi paid provider. Chỉ cutover khi media/JWT/queue reconciliation cùng đạt; bật cleanup cuối cùng sau retention/backup gate.

**Full PostgreSQL/Redis/Rabbit/S3 restore chưa thực hiện.** Tài liệu S3 ngày 04/10 ghi versioning tắt tại thời điểm đó; chưa xác minh trạng thái hiện tại, chưa bật versioning/IAM/bucket lifecycle. Nếu không có object backup/version thì không hứa khôi phục reference tới object đã xóa.

## 7. Chạy lại và rollout có điều kiện

Từ repository root, Docker hoạt động, thư mục output mới:

```powershell
mvn --batch-mode --no-transfer-progress -Preliability -Ddictalab.build.directory=target/reliability-replay verify
if ($LASTEXITCODE -ne 0) { throw 'Reliability tests failed' }
node scripts/verify-reliability-tests.cjs target/reliability-replay/surefire-reports
if ($LASTEXITCODE -ne 0) { throw 'Reliability gate rejected reports' }
```

PG18 thêm `-Ddictalab.testcontainers.postgres-image=postgres:18.4-alpine`; hai lượt gate local batch này dùng goal `test`. Full H2 đã dùng `verify` để kiểm tra packaging. Native regression dùng `scripts/test-postgres.ps1` với binary PG18.4, tạo cluster riêng và dừng đúng cluster do runner tạo. Logs/data cuối giữ tại `target/postgres-test-97c241b33150417cab5a9675752714fd`.

Trước rollout:

- Java17 CI và staging gate xanh, source/artifact hashes trùng; không tự bật queue flags để lấy thành tích.
- Review production DB version/size/write load/long transactions/backup trước V27. V27 additive trên bảng mới, đã chạy clean migrations và upgrade regression PG; không bảo đảm zero-lock/zero-downtime production chưa biết context. Giữ schema khi rollback code, không drop catalog/tombstone. V26/checksum cũ giữ nguyên.
- Drain/cutover đồng bộ reference writers: instance cũ không retain catalog có thể tạo reference trong lúc cleanup xóa. **Cleanup phải tắt trong mixed-version rollout**; chỉ bật sau khi mọi writer mới đã hoạt động, backup window và S3 inventory được xác minh.
- Kiểm tra service stop deadline thực tế, override migration timeout nếu có review, latency/error mapping, dashboard/alerts. Không retry HTTP mutation tự động chỉ vì có Retry-After.
- Rollback code có thể đưa lại race/unsafe retry; tắt cleanup trước rollback, giữ V27, không reset UNKNOWN. Không coi rollback JAR là rollback dữ liệu.

## 8. Snapshot và giới hạn công bố

Workspace có nhiều thay đổi có trước, HEAD `9b175864c40732d81f56223425fa8bd1adad6d9e` không đại diện source đã test. Snapshot không chứa `.env`/production secrets. ZIP cuối gồm `src`, POM, `.env.example`, workflow, verifier và restore script; tài liệu nằm riêng trong `docs`.

```text
baseline-source.zip  80C8FE25CFA00869D63A9363310FCE968A24E291DF950117FBC045FD6862BB1E
verified-source-optin.zip  1A5DF54C1BD4FCE5F3610FBE48A24D6DDD4E246B4680DE863FD69CB1C708FCBF
executable JAR            06A811050FFAFB1ABA6BE562D9C81E42CC92D24BB8F295BC48DF86C984560710
```

ZIP/JAR/raw trong `target` bị Git ignore: sao lưu trước khi dọn workspace. Hash ZIP cuối gắn với source dùng `tc17-optin`/`tc18-optin`/`default-optin`/`native-optin`; JAR ở `default-optin`, không gán hash JAR cho production. Giữ `verified-source.zip` trước cleanup opt-in để truy vết, không dùng nó làm snapshot cuối. Không commit/push/deploy hoặc thay infrastructure live ở batch này.

Ponytail giữ shared flow và dependency hiện hữu; testing skill giữ transaction từng thread và assertion DB sau commit/rollback, không dùng cache JPA để chứng minh dữ liệu. Migration review giới hạn V27 vào additive catalog; encoding guard kiểm tra UTF-8. Không suy luận test mock thành cloud acceptance.

Kiểm tra cuối: encoding 31 file và lượt bổ sung 6 file sau cleanup opt-in đạt; diff whitespace đạt; PowerShell restore script parse đạt. Verifier kiểm tra XML cuối của cả hai PG, không chỉ nhìn Maven BUILD SUCCESS. Containers/cluster thử được dọn/dừng bởi runner; không dừng container/dịch vụ có sẵn của người dùng.

Câu mô tả portfolio phù hợp: “Thiết kế và kiểm thử lease fencing/atomic result commit, durable media cleanup, safe retry và broker crash recovery bằng PostgreSQL/Redis/RabbitMQ Testcontainers; lưu red/green evidence và recovery runbook.” Không viết “exactly-once”, “mọi crash không mất dữ liệu”, “full disaster recovery đạt” hoặc “đã production-ready”. Network partition, full S3 restore, Redis restart, external service shutdown và tải production vẫn chưa nghiệm thu.
