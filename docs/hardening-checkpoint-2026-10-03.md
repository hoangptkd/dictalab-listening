# Checkpoint hoàn thiện DictaLab — 03/10/2026

Phạm vi: thực hiện yêu cầu năm bước sau báo cáo R01–R18. Mọi giờ trong báo cáo là UTC+7.
Không coi tài liệu này là chứng nhận hoàn tất toàn bộ kế hoạch hoặc nghiệm thu R26–R28.

## 1. Trạng thái tổng thể

| Nhóm | Trạng thái | Bằng chứng / điều kiện còn thiếu |
|---|---|---|
| Snapshot source của bản đang chạy | DONE về nội dung thực thi | Build độc lập từ snapshot: **611/611 entries trong JAR khớp**, bao gồm classes/resources/dependencies; JAR cũ chưa tái lập nguyên byte. |
| Build tái lập cho bản sửa mới | DONE, chưa phát hành | Hai build độc lập cùng SHA256 `2DE5253C15BF8A865B879C532A0B0DE1E8FEEA195A486F6A5A1E90A770FA2C2E`. |
| Test concurrency/idempotency/outbox/lease bổ sung | DONE trong phạm vi các ca bên dưới | H2/PG18 và test fault injection có DB thật; không gọi tạo lại service là kill/restart JVM thật. |
| Nghiệm thu R26–R28 | BLOCKED | Tài liệu đính kèm chỉ có R01–R18; chưa có định nghĩa và tiêu chí nghiệm thu R26–R28. Không tự gán số hiệu. |
| Retry/deadline/lease | DONE phần sửa đã kiểm thử; DEFERRED phần nghiệm thu IO toàn diện | Có safe-retry, Retry-After, jitter và kiểm tra lease còn hạn. SMTP hiện là socket timeout, chưa phải deadline cứng cho toàn phiên; chưa chứng minh mọi IO. |
| k6 trên HP | DONE về kết nối và một smoke; BLOCKED về tăng tải/soak | Smoke 5 VU/30s vượt catalog p95. Không bỏ ngưỡng, không tăng tải sau fail. |
| Dataset đại diện, mixed soak, CPU/query profiling | DEFERRED sau gate smoke | Chưa sinh dataset đại diện mới; chưa có JFR/query trace cho request 4,12s. |
| Monitoring thường trực | DONE: Prometheus; DEFERRED: Grafana service/receiver | Service tự khởi động, loopback 19190, scrape UP, 7 rules health OK. Receiver vẫn hoãn theo quyết định của chủ website. |
| Backup DB/media ngoài VPS | DONE: công cụ sync có kiểm tra và một bản sao được xác minh; BLOCKED: lịch tự động đầy đủ | Snapshot nguồn 13:53 được copy sang HP, không phải backup mới. Chưa chốt đích luôn bật và cách tạo DB/media nhất quán định kỳ. |
| Phát hành bản sửa mới | BLOCKED tại nghiệm thu tổng thể | Production vẫn giữ artifact cũ. Không tự coi candidate đạt test là đã phát hành hoặc đạt R26–R28. |

## 2. Source snapshot và tính tái lập

### Bản đang chạy

Production được kiểm tra lại qua SSH: SHA256
`E39A42108C942B192F9736BBFCEB104CF57DDEEF5028B9205FAB55C0A86EB61C`.

Snapshot riêng, ngoài workspace và Maven target:
`C:/Users/HP/.codex/backups/DictaLab/source-audit-20261003`.

Có source allowlist, manifest SHA256 từng file, Git baseline, phiên bản Java/Maven,
reference.jar và log build/tests. Không copy `.env`, database, cookies, private logs hoặc browser profile.
Script kiểm tra file nguồn trước/sau copy; không reset/commit các thay đổi của công việc khác.

Build từ snapshot cho JAR SHA256
`C164AA2C62C9230CDBE82CA17F0ADBE7604BD485F297EF8CDB71182BE7F81BBA`.
So sánh **mọi entry**, không loại manifest/classes/dependencies: 611/611 khớp SHA256 nội dung.
Archive SHA256 khác; không tuyên bố bản cũ tái lập từng byte. Bằng chứng:
`source-audit-20261003/artifact-comparison.json`.

### Candidate của lượt này

Đặt `project.build.outputTimestamp` cố định trong `pom.xml`, dùng cùng Java/Maven được ghi trong snapshot.
Compiler local JDK 21, target Java 17; không đánh đồng compiler version với runtime Java 17 trên VPS.

Snapshot: `C:/Users/HP/.codex/backups/DictaLab/source-candidate-20261003`.
Hai lần build ở hai thư mục độc lập, chạy H2 suite, cho **cùng SHA256 toàn JAR**:
`2DE5253C15BF8A865B879C532A0B0DE1E8FEEA195A486F6A5A1E90A770FA2C2E`.
`artifact-comparison.json`: `ByteIdentical=true`, 611 entries, 0 differences.

Đây là source snapshot tái lập, không phải một commit sạch. Dependency cache/toolchain vẫn phải có
đúng phiên bản; chưa đóng gói một bộ build hermetic/offline hoàn chỉnh.

## 3. Chứng minh vấn đề → sửa tối thiểu → kiểm thử

### Email outbox

Lượt RED: 7 ca, 2 fail, 0 error (`target/outbox-red-20261003.log`):

- Lease bị cho hết hạn trong lúc SMTP đang gửi, worker vẫn ghi `DELIVERED`.
- `MailAuthenticationException` vẫn đưa job về `PENDING`, lặp lại một lỗi cấu hình không tự hết.

Sửa:

- Kiểm tra `status`, lease ID và `locked_until > now` trước gửi và khi cập nhật thành công/thất bại.
- Claim đã mất không làm dispatcher ném lỗi do query yêu cầu đúng một dòng.
- Lỗi authentication/parse/preparation là terminal, xóa body chứa link nhạy cảm.
- Retry SMTP còn lại tối đa 5 attempts, equal jitter: lần đầu 15–30s, backoff tăng theo attempts.
- Không giữ transaction/DB lock qua SMTP; không thay đổi at-least-once thành lời hứa exactly-once.

7 ca mới kiểm tra: hai dispatcher cạnh tranh tại điểm gửi, crash giả lập sau SMTP accept,
tạo lại worker và reclaim, lease hết hạn không có owner mới, stale failure sau replacement delivery,
permanent authentication failure, bounded backoff, exhausted/consumed token.
Một ca ghép exhausted/consumed; tổng vẫn là **7 ca**, không cộng từng assertion thành test.

Lượt GREEN đầu: 19 ca outbox + reliability, 0 fail/error (`target/outbox-green-20261003.log`).
Full PG18 từng lộ interference do các Spring contexts khác chạy scheduler với mail mock;
test profile nay đặt dispatcher delay 600s và initial delay tương ứng. Các ca chủ động gọi dispatcher,
không tắt kiểm tra DB/lease. Full suite sau sửa đã đạt.

### Listening và Conversation

- RED `target/lease-red-20261003.log`: Listening vẫn ghi `COMPLETED` khi lease đã hết hạn.
- Success/failure Listening và commit Conversation nay phải có lease còn hạn, ngoài lease ID đúng.
- Listening có recall không rỗng và worker mất quá 120s: `FAILED / OUTCOME_UNKNOWN`, giữ recall,
  không tự gửi thêm một yêu cầu AI có thể tính phí lần nữa. Người dùng dùng đường manual retry có cap hiện có.
- Listening chỉ tự retry khi `ProviderGuard.Unavailable`: bị chặn trước IO hoặc HTTP 429 được phân loại rõ.
  Lỗi mạng/5xx/schema không tự retry; lưu lỗi đã lọc và bài làm để xử lý.
- ShopAIKey 429 đọc `Retry-After` dạng số giây hoặc HTTP-date. Không retry sớm hơn thời điểm đó;
  giá trị lỗi/quá 300s không bị ép xuống một delay ngắn hơn, chuyển sang retry thủ công.
- Conversation generation/grade/TTS không thêm network retry. JSON repair một lượt vẫn là sửa output,
  không phải retry mạng.

Test bổ sung: expired lease Listening/Conversation; giữ recall khi lỗi; stale billable worker không
gọi AI lại; safe rejection tôn trọng Retry-After; replay cùng requestId/body sau tạo lại service không
gọi provider, body khác bị 409 và không tạo thêm attempt.
Đây là restart đối tượng service/fault injection, **chưa là restart tiến trình thật**.

### Migration

Không sửa checksum/content V23/V24 đã phát hành; không có migration mới trong candidate.
`MigrationUpgradeTest` chỉ chạy trên PostgreSQL, trong schema UUID của DB có tên test được kiểm tra:

1. Migrate đến V22, thêm legacy row email không chuẩn hóa.
2. Upgrade bị từ chối, row vẫn còn, V23 rollback, chưa có email_outbox, version vẫn V22.
3. Sửa riêng fixture test rồi upgrade/validate V24 thành công.
4. INSERT active solo trùng bị DB từ chối; chỉ còn một session và không có invalid index.

Không dùng H2 làm bằng chứng transactional DDL/partial index. Đây chưa là toàn bộ ma trận CHECK/FK,
lock contention/large backfill hoặc zero-downtime migration. V23/V24 vẫn theo maintenance gate hiện có.

## 4. Ngân sách IO hiện được xác minh bằng code/test

| IO | Ngân sách hiện tại | Chính sách retry / giới hạn |
|---|---|---|
| DB acquire | Hikari wait 3s, validation 1s | Không retry mù write transaction; unavailable trả 503/Retry-After như bản trước. |
| ShopAI generation | Connect 10s; một call 30s, bounded body 100 KB | Network không tự retry; JSON repair tối đa thêm một call. |
| ShopAI grade | Một call 60s | Listening chỉ retry pre-dispatch rejection/429 hợp lệ; tối đa 3 claims. |
| Listening | Lease 120s | Commit cần lease còn hạn; worker chết với recall cần manual retry. |
| Conversation | Lease 210s | Bao 60s grade + tối đa 2×30s generation và dư cho persistence; commit có expiry fence. |
| Cartesia | Connect 5s, request/body wait 45s, body tối đa 3 MB | Không network retry tự động; không hứa cancellation ngăn provider tính phí. |
| SMTP | Connect 5s, socket read/write 10s; lease 90s | At-least-once, tối đa 5 attempts; socket timeout **không phải** hard deadline toàn SMTP conversation. |
| Off-host copy | SSH connect 10s, keepalive 10s×3, copy tối đa 5 phút | Không có loop retry vô hạn; partial copy không có success marker. |

Các giá trị là cấu hình/default đã đọc và test liên quan, không phải đo latency mọi IO trong production.
DEFERRED: kiểm thử slow-drip/stall/DNS cho toàn bộ HTTP/SMTP, hard deadline SMTP tổng thể,
đa process restart, provider billing/idempotency certification và lease/clock skew giữa nhiều instance.

## 5. k6 HP và điều tra chậm

Không dùng generator VPS trong lượt này. App capacity vẫn chạy artifact production E39 trên loopback
18282/18283 và DB `dictalab_capacity`, heap 512 MiB, pool 10. Chưa deploy candidate 2DE để đo.
HP kết nối qua SSH forward 17282/17283; không mở firewall/public Actuator.

Preflight 10 samples: CPU trung bình 1,7%, đỉnh 6%, free RAM thấp nhất 2.637 MiB,
paging 0, disk queue 0. Đạt đúng ngưỡng cũ.

Smoke 5 VU mixed / 30s:

- 56 cycles, 207 HTTP/checks (bao gồm setup); 207/207 checks thành công.
- HTTP/business errors = 0.
- Work p95 = 447,18 ms; p99 = 1.324,34 ms; max = 1.326,95 ms.
- **Catalog p95 = 1.324,34 ms > 1.000 ms: FAIL, k6 exit 99.**
- Năm request catalog đầu ghi cùng thời điểm, khoảng 1,324s, chủ yếu nằm ở waiting.
  Cold initialization/shared wait là giả thuyết, chưa có JFR/query trace để chứng minh nguyên nhân.

Watchdog toàn vòng đời app: 39 samples, CPU max 48%, RAM min 2.015 MiB, app RSS max 539,96 MiB,
pool pending được sample max 0; paging peak 142 pages/s sau startup. Không phải resource-guard abort.
Các samples không bảo đảm bắt mọi peak. Không dùng paging peak sau startup để sửa kết quả preflight trước đó.

Sau fail đã dừng app/test generator/tunnel, gỡ `DictaLab-HP-Transport-OneShot`, giữ bằng chứng.
Không tăng VU, không chạy soak, không chuyển ngược generator VPS để ép đạt.
HP runner nay cũng có CPU >=85% sáu samples và free RAM <500 MiB guard; VPS guard giữ nguyên.

Đối chiếu raw JSON lượt mixed 300 trước đó:

- Work max 4.123,18 ms.
- Catalog max 681,23 ms; Dashboard max 476,66 ms; History max 449,42 ms.
- Do đó request 4,12s không nằm trong ba route đọc đã có submetric; chưa xác định thao tác ghi cụ thể.
  Không kết luận do DB/GC/CPU chỉ từ max này.

Thêm slow-request diagnostics: route name, request ID, blocked/connect/wait/receive; tối đa 5 dòng/VU.
Không ghi URL chứa ID, body, cookie, token hay đáp án. Test JS xác minh redaction.
Chưa có dataset đại diện mới; bộ fixture cũ một câu/bốn từ **không được đổi nhãn thành representative**.

Bằng chứng: `target/capacity/hp-transport-20261003-core-mixed-5-20261003-210844.*`
và `target/capacity/hp-transport-evidence-20261003/`.

## 6. Monitoring và backup ngoài VPS

### Monitoring đang chạy thật

Đã cài `DictaLabPrometheus`, StartMode Auto, tài khoản `NT AUTHORITY\LocalService`.
Bind **127.0.0.1:19190**. Cổng 19090 thuộc node.exe của WebLens; không thay đổi/dừng process đó.
Config lưu tại `C:/DictaLab/monitoring`; scrape app private 18183 mỗi 15s.
Retention TSDB 3 ngày/256 MB là tham số retention, không phải hard cap toàn bộ WAL/log/process memory.
`GOMEMLIMIT=256MiB` là soft memory target; không gọi là giới hạn RAM cứng.

Kiểm tra 21:20:51: up=1, 7 rules health=ok, service Running/Auto, RSS 74,36 MiB,
toàn VPS free RAM 2.612 MiB. Bộ promtool config/rule firing-resolved tests đạt trước cài.
Binary SHA256: `C19135130C94E17AA02520BA40C3C8A35727653CD2C667B6A12F9A18313DF7AC`.
Không có API/metrics mới public; không cài receiver hoặc tuyên bố đã gửi cảnh báo.
Grafana dashboard/config cũ được giữ, **chưa cài Grafana service thường trực**.
Raw check: `target/readiness/permanent-monitoring-20261003.json`.

### Backup

`sync-backup-from-vps.ps1` chọn snapshot quiescent DB/media mới nhất, copy qua SSH,
kiểm tra tên/path manifest, checksum dump, checksum/size mỗi media, ACL riêng cho người dùng/admin/SYSTEM.
Chỉ ghi success marker sau kiểm tra; chạy lại snapshot đã có thì verify, không overwrite hoặc xóa backup.
5 test fixture: hợp lệ, media hỏng, traversal, DB-only giả làm full snapshot, dump checksum sai đều đạt.

Đã sync và verify thật:
`C:/Users/HP/.codex/backups/DictaLab/synced-dictalab-20261003-135358-301`.
Nguồn là backup DB/media lúc **13:53 ngày 03/10**, 3 media; không tạo snapshot mới trong lượt này.
Không gọi xác minh checksum là đã chạy một restore drill mới. Restore drill của snapshot này vẫn là
bằng chứng 84,91s trong báo cáo trước.

Lịch 02:30 DB-only trên VPS không thay đổi. Chưa tự đặt lịch dựa vào HP luôn bật,
vì chủ máy trước đó có yêu cầu tắt HP khi hoàn tất. Cần chốt đích off-host hoạt động khi HP tắt
và phương án snapshot media nhất quán định kỳ (maintenance ngắn hoặc cơ chế snapshot khác).
Không sao chép secrets/DPAPI credentials vào backup gửi cho ChatGPT.

## 7. Kiểm thử và ranh giới nghiệm thu

| Bộ | Kết quả |
|---|---|
| H2 final workspace | 187 cases, 0 fail/error, 2 skip: benchmark opt-in + migration PostgreSQL-only. |
| PostgreSQL 18.4 final | 187 cases, 0 fail/error, 1 skip: benchmark opt-in. |
| PostgreSQL 17.11 trên VPS, DB test riêng | 52/52 ca trọng tâm đạt; gồm outbox/reliability/conversation/migration/provider. Không gọi là full suite 187. DB test đã được dọn. |
| Snapshot candidate rebuild H2 | Đạt suite và trùng SHA256 toàn JAR. |
| JavaScript full | 89/89; load-contract 4/4 đã nằm trong 89, không cộng thêm. |
| Backup verifier | 5 fixture cases đạt; copy thật và idempotent reverify đạt. |
| Prometheus | Config + 7 alert tests đạt; service private scrape/rules kiểm tra thật đạt. |
| HP load smoke | FAIL latency catalog; không có business/HTTP error. |

Log final workspace:
`target/readiness/H2-20261003-211734-552.stdout.log`,
`target/readiness/Postgres18-20261003-211745-273.stdout.log`,
`target/readiness/JavaScript-20261003-211347-782.stdout.log`.
PG17: `target/readiness/pg17-hardening-20261003.log`, kết thúc 21:24:06.
Encoding UTF-8 đạt 8 file kiểm tra; 6 script PowerShell parse đạt.
`git diff --check` toàn workspace vẫn báo whitespace ở `scripts/chatgpt-web-compat.patch` có sẵn;
không sửa patch lịch sử ngoài phạm vi lượt này.
Các log RED, lỗi test compile và assertion quá rộng về `ai.configured()` cũng được giữ; đã sửa test
để kiểm tra không có **provider call**, không cấm kiểm tra cấu hình local vô hại.

Security: đã đọc luồng auth/session revocation/management-port/media và chạy lại regression hiện có;
pattern scan giới hạn source/scripts/ops/CI không thấy high-confidence key/private-key marker.
Git chỉ track `.env.example`, không phải `.env`. Đây **không phải** audit toàn Git history, dependency
CVE scan hiện hành, pentest hay nghiệm thu R26–R28. Chưa có căn cứ ghi security toàn hệ thống DONE.

## 8. Bước tiếp theo, không tự động chạy nền

1. Nhận nội dung R26–R28, lập acceptance matrix đúng yêu cầu và hoàn tất gate còn thiếu.
2. Profile cold catalog bằng JFR + query timing trên app/DB capacity, không sửa ngưỡng.
3. Sau khi smoke đạt, tạo dữ liệu lịch sử đủ câu/từ/phân bố bài, ghi distribution rồi mixed soak hữu hạn từ HP.
4. Chốt đích backup ngoài VPS luôn bật và consistency/RPO, triển khai lịch có timeout/no-overlap rồi restore thật.
5. Chốt Grafana service/alert receiver theo nhu cầu; receiver hiện vẫn DEFERRED như đã thống nhất.
6. Phát hành candidate chỉ sau gate; giữ snapshot/artifact trước và restore smoke. Không tự áp dụng Redis/Kafka/microservices.

Không có commit/push/reset, không thay JAR production trong lượt này. WebLens, BilingualBackend,
DictaLab, hai PostgreSQL và Defender giữ chạy. Không còn lịch/job thử tải lặp; monitoring thường trực
có đầu ra scrape/rules là dịch vụ vận hành được yêu cầu, không phải job load interval vô hạn.

Lưu ý vận hành còn thiếu: NSSM đã bật rotate online 10 MB cho log Prometheus, nhưng chưa có
retention tự dọn các file log đã xoay; cần bổ sung trước khi gọi storage monitoring được chặn đầy đủ.
Không tắt HP ở checkpoint này vì toàn bộ quy trình chưa hoàn tất và còn quyết định cần chủ máy chốt.
