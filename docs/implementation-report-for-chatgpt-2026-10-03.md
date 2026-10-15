# Báo cáo triển khai DictaLab theo kế hoạch R01–R18

Cập nhật tiếp theo: [Checkpoint hardening](hardening-checkpoint-2026-10-03.md) ghi source snapshot,
candidate tái lập, test/fix bổ sung, smoke từ HP và monitoring thường trực. Các con số dưới đây
là bằng chứng của đợt trước, không tự chuyển thành kết quả nghiệm thu candidate mới.

Ngày tổng hợp: 03/10/2026, giờ Việt Nam (UTC+7).
Mục đích: cung cấp cho ChatGPT một bản tổng hợp để đánh giá kết quả, thiếu sót và bước tiếp theo.
Đây là báo cáo triển khai và bằng chứng hiện có, **không phải chứng nhận hoàn tất toàn bộ R01–R18**.

## 1. Mục tiêu và phạm vi

Chủ website yêu cầu tăng tính đúng đắn, khả năng phục hồi và sức tải của DictaLab trên VPS hiện tại.
Không áp đặt mục tiêu hoặc giới hạn 10.000 người; cần đo năng lực thực tế và tối ưu theo bằng chứng.

Các quyết định của chủ website đã được giữ:

- Không dừng VPS hoặc WebLens để phục vụ thử tải.
- Giữ BilingualBackend chạy. Chỉ tạm đóng MoreLogin và tool_auto_register-hoang sau khi được cho phép.
- Kiểm thử có thời hạn, có kết quả và cleanup; không có job thử tải lặp vô hạn.
- Khi kiểm thử lỗi phải phân tích/sửa và chạy lại hữu hạn; không nới tiêu chí để ép đạt.
- Giữ cấu hình cảnh báo, chưa chốt nơi nhận. Không tuyên bố đã gửi cảnh báo thật.
- Không xóa dữ liệu trùng để migration chạy qua, không restore đè production để thử.

Stack: Java 17, Spring Boot 4.1.1, PostgreSQL production 17.11; PostgreSQL local/CI 18.4.
VPS Windows Server 2019, 8 vCPU, khoảng 7 GiB RAM, dùng chung với các ứng dụng khác.

## 2. Tóm tắt kết quả

Đã triển khai và phát hành nền tảng ràng buộc DB, chống race ở các luồng chính, bảo vệ transaction/IO,
Listening lease fencing và retry, email outbox, provider bulkhead/circuit/deadline, rate limiting,
metrics/logging, phân trang và tối ưu một số query. Đã bổ sung công cụ thử tải, backup/restore và cổng phát hành.

Bản production được xác minh lúc **19:47 ngày 03/10/2026**:

- Service Running, health UP; schema Flyway V24.
- JAR SHA256: `E39A42108C942B192F9736BBFCEB104CF57DDEEF5028B9205FAB55C0A86EB61C`.
- DictaLab, WebLens, BilingualBackend, PostgreSQL và Windows Defender giữ chạy.
- Không còn process hoặc task thử tải của đợt đo; lịch DB backup vẫn giữ.

**Công suất tối đa chưa xác định.** Lượt tối mới nhất hoàn tất 300 tài khoản giả lập hỗn hợp trong 120 giây,
không lỗi. Mức 500 bị bộ bảo vệ CPU dừng; không kết luận website chỉ chịu được 300 hoặc không chịu được 500.

Workspace có thay đổi chưa commit và cả thay đổi giao diện/chức năng từ công việc khác. Số test là tổng bộ
kiểm thử của dự án, không phải toàn bộ test mới do đợt này tạo. Dùng SHA256 để phân biệt artifact; chưa có
một commit sạch duy nhất tái lập tất cả kết quả và không nhận mọi thay đổi trong workspace là của đợt này.

## 3. Đối chiếu từng mục R01–R18

“Đã triển khai” dưới đây nghĩa là có code/cấu hình và đã nằm trong bản phát hành tương ứng; không tự đồng
nghĩa mọi tiêu chí nghiệm thu trong kế hoạch gốc đều đã được kiểm tra.

| Mục | Đã thực hiện | Bằng chứng và phần còn thiếu |
|---|---|---|
| **R01 — Constraints/invariants** | Có ma trận 23 bảng nghiệp vụ sau V23, cộng bảng Flyway; V23 thêm CHECK cho email chuẩn hóa, version/counter/score/state, lease fields và outbox. V24 thêm PostgreSQL partial unique cho active solo, user/room và Listening draft/published đang hoạt động. Giữ reorder có vị trí âm tạm; không thêm FK giả cho audit polymorphic. | Preflight 16 nhóm vi phạm bằng 0; migration chạy trên PG17/18; test email/auth_version trái constraint bị chặn. Không có test trực tiếp cho mọi CHECK/FK/cascade trong ma trận. Migration hiện dùng maintenance window cho DB nhỏ, chưa zero-downtime DB lớn. |
| **R02 — Transaction boundaries** | Lập inventory phase DB/IO. Token+outbox commit cùng transaction; SMTP ngoài transaction. Listening snapshot/claim và commit ngắn, AI ngoài transaction. Bảo toàn Conversation pending answer/claim trước provider. Practice/admin khóa theo phạm vi nghiệp vụ. | Test rollback không còn outbox; mock SMTP xác nhận không có transaction lúc gửi; Conversation test không giữ DB lock qua AI. Chưa đo hold-time của mọi service dưới provider chậm; filesystem có thể còn orphan khi process chết. |
| **R03 — Query/index/pagination** | Batch query Catalog/History, dùng aggregate/projection ở luồng đọc phù hợp, giữ History/Dashboard hiệu quả; Listening history có page endpoint, thứ tự ổn định và ownership. Legacy history giới hạn 100 mục thay vì tải vô hạn. Thêm index Listening history, không thêm index History trùng. | Test pagination, backend read, browser history trang 2; SELECT History dùng index với incremental sort trên PG test, 10 rows/12 buffer hits, 1,207 ms. Chưa có benchmark before/after kiểm soát hoàn toàn trên dataset lớn cho mọi màn hình/admin/assessment. |
| **R04 — Concurrency** | Test đồng thời đăng ký email, consume token, start solo, check từ, hint/complete và giữ một ADMIN enabled; Listening stale lease; Conversation answer/lease; Room creation/capacity/membership. Có latch và timeout. | Bộ integration đã chạy PG17/18. Một số test Room/provider là unit hoặc mock, không phải race DB. Chưa phủ đủ mọi race admin publish/reorder/ticket, account revoke/email/role và mọi tổ hợp worker claim/manual retry; latch khởi chạy chung chưa bảo đảm luôn chạm đúng điểm tranh chấp. |
| **R05 — Idempotency/retry safety** | Start/resume trả phiên hiện có; complete không cộng tiến độ hai lần; token consume một lần. Conversation cùng requestId/body replay, mismatch/expectedTurn/state/ownership được bảo vệ; pending answer được lưu trước IO. Check/replay hợp lệ vẫn là event riêng. Sửa so sánh JSON replay theo ngữ nghĩa, không phụ thuộc thứ tự object keys. | Test race/duplicate/ownership và một hành trình Conversation thật có replay. Không xây framework idempotency toàn cục. Chưa nghiệm thu đầy đủ lost-response + replay sau restart process thật cho mọi endpoint. Không hứa exactly-once với SMTP/AI bên ngoài. |
| **R06 — k6 journeys** | Suite Core, Listening, Conversation, Room REST/WS; helper auth/CSRF/cookies dùng riêng từng VU; kiểm status và state nghiệp vụ; think/playback waits. Có load/arrival/spike/soak, mixed 80/20, completion thresholds và budget/guard. | Đã chạy các smoke và các lượt tải có raw output. Listening empty-recall là path không AI; Room signaling không phải media. Chưa có fake-provider load với latency/capacity đại diện mọi AI path; không xem core capacity là AI capacity. |
| **R07 — Baseline/capacity** | Lưu artifact hashes, topology, dataset, p95/p99/errors/completions, CPU/RAM/pool/GC và generator snapshots; giữ cả lượt fail/abort. Có so sánh artifact gốc và bản sau tối ưu trên cùng VPS/settings. | Shared-host background không được kiểm soát hoàn toàn; một số baseline bị cắt. Không công bố hệ số tăng tốc chính xác. Có reads 300/10 phút ở artifact buổi chiều và mixed 300/2 phút ở artifact tối; chưa có breakpoint bền vững, soak mixed dài hoặc generator độc lập ổn định. |
| **R08 — Rate limiting** | Token bucket theo nhóm auth/AI/upload và actor account hoặc remote IP; 429/Retry-After, metric rejected. Entries có cap và cleanup; không trực tiếp tin forwarded header do client khai. Bảo toàn các quota theo module. | Test cap/expiry và header spoofing. In-memory một JVM, reset khi restart, không phải limiter toàn cluster. Chưa đủ nghiệm thu NAT đông người, boundary/concurrency cho mọi endpoint và burst login production; capacity app tắt limiter riêng. |
| **R09 — Timeout/retry/backoff** | Connect/request/body deadline, bounded response bytes, cancel khi timeout; phân biệt 429/5xx khi đánh dấu provider failure. Outbox retry hữu hạn với exponential delay. Listening/Conversation có attempt/lease budgets. Schema repair một lần không được gọi là retry mạng. | Có provider/schema/failure tests và budget runtime. Không retry mạng AI/TTS mù vì có thể đã tính phí. **Chưa triển khai/kiểm chứng đầy đủ backoff+jitter+Retry-After cho mọi loại IO**; outbox hiện không có jitter. Cần review deadline/lease và retry policy theo từng workflow, không đánh dấu R09 hoàn tất tuyệt đối. |
| **R10 — Circuit/bulkhead** | ProviderGuard tách ShopAIKey/Cartesia, semaphore hữu hạn, closed/open/recovery probe, threshold/recovery config, permits được giải phóng. Listening workers/queue hữu hạn, giữ Coach fallback và pending/recall; không tạo grade giả. | Test saturation, recovery một probe và permit release đạt. Không thêm framework/broker chỉ để có kiến trúc lớn. Chưa có stress đồng thời mọi provider hoặc graceful shutdown certification dưới tất cả tải. |
| **R11 — Shared durable jobs** | **Hoãn có chủ ý**: hoàn thiện Listening worker và SMTP dispatcher riêng, không ép Conversation interactive vào batch queue hoặc dựng một queue framework chung. | Có giải thích trong readiness contracts: payload/expiry/recovery semantics khác nhau. Đúng nhánh điều kiện “không generalize nếu chưa cần”; chưa có ADR riêng và full fairness/multi-worker certification cho framework chung vì chưa xây framework đó. |
| **R12 — Recovery/dead-letter** | Tận dụng FAILED, giữ recall, lastError được lọc; Listening claim/lease fencing, retry owner/admin có cap, ngăn stale worker commit. Có runbook queue/provider/lease. | Test stale lease và failure observation giữ recall; smoke Listening đạt. Chưa diễn tập đủ kill/restart worker thực, exhausted retry và manual retry cạnh tranh trên process độc lập. Chưa nghiệm thu mọi recovery scenario chỉ từ việc code có lease. |
| **R13 — Outbox** | Email token và outbox cùng commit; conditional claim, lease UUID, retry/reclaim, terminal state; tối đa 5 attempts. Xóa link nhạy cảm sau gửi/hết hạn. SMTP ngoài transaction; delivery at-least-once. | Test rollback/after-commit bằng mock mail đạt. Production chưa có SMTP config được nghiệm thu, chưa gửi thư thật. Chưa đủ fault injection crash sau SMTP accept/trước DB commit hoặc mọi multi-dispatcher race. Email có thể gửi trùng; token một lần không bảo đảm email chỉ đến một lần. |
| **R14 — Metrics** | Actuator/Prometheus, HTTP/JVM/GC/Hikari, queue/provider/room/rate-limit metrics; finite labels; management loopback riêng. Phân biệt provider/task latency với HTTP nhận job. | Private scrape 200, app-port metrics chưa đăng nhập 401; runtime scrape trong test/load đạt. Không mở Actuator công khai. Chưa triển khai đầy đủ PostgreSQL exporter/DB lock metrics thường trực; histogram/coverage mọi tác vụ còn cần rà soát. |
| **R15 — Grafana/alerts** | Provisioning, dashboard 12 panels và 7 alert rules trong repo, có runbook và firing/resolved tests; core SlowRequests loại AI budget khác. | Grafana OSS 13.2.3 + Prometheus thật: import/datasource/queries/gauges đạt, promtool tests đạt. Tiến trình kiểm thử đã dừng; **chưa có stack giám sát liên tục trên VPS**, chưa gửi notification thật theo quyết định hoãn nơi nhận. |
| **R16 — Tracing** | Micrometer Observation/provider spans, sampling configurable 10%, export tắt mặc định; queue 256/batch 64, exporter timeout 2s. Listening failure có observation được lọc. Sửa property Boot không hợp lệ và test metadata dependency thật. | OTLP HTTP test receiver nhận provider child span cùng trace/parent; receiver unavailable không làm business throw và không giữ permit. **Chưa nghiệm thu HTTP→JDBC→durable job xuyên restart, collector storage hoặc trace tìm được từ mọi errorId**. Không gọi provider/exporter test là full end-to-end tracing. |
| **R17 — Logging/correlation** | ECS JSON logs, validate X-Request-ID tối đa 64 ký tự, MDC cleanup, errorId. Log cảnh báo DB/provider failure dùng thông tin lọc; config rotation 10 MB/file, cap 100 MB, history 7 ngày. | RequestId response và filter tests đạt; lỗi DB trước controller fail-closed, không viết lại response committed. Chưa chứng minh async propagation toàn bộ hoặc audit redaction tất cả log paths/retention thực chạy dài. Không xem config rotation là đã nghiệm thu dung lượng log nhiều ngày. |
| **R18 — Backup/restore** | DB backup có checksum/manifest/success marker; daily 02:30, 15 phút/no-overlap, retention 30 ngày và ít nhất 7 nhóm. Release gate quiescent DB/media snapshot, restore DB tạm + media checksum/FK/schema/app smoke trước đổi JAR. Giữ artifact cũ; JAR rollback không rollback schema. Có hai snapshot DB/media/JAR trên HP ngoài Git. | Restore thật 24 bảng/7 users/36 sessions/3 media và app smoke đạt; lượt gần nhất 84,91s. Retention test thư mục tạm đạt. Daily hiện DB-only; **chưa automatic off-VPS/media backup và full-machine DR**, chưa SLA RPO/RTO. Không sao chép credentials/Chrome profile/cookies vào báo cáo. |

## 4. Những lỗi cụ thể đã được phát hiện và sửa

1. Runner Windows đọc ExitCode null dù Maven thành công: giữ process handle, kiểm null và lưu stdout/stderr.
2. Hai build dùng chung output làm ClassNotFound: tạo build directory riêng cho mỗi lượt, không clean bằng chứng cũ.
3. Restore smoke serialize lessonId thành mảng: sửa script; đồng thời JSON đầu vào sai kiểu trước đó trả 500,
   nay handler trả 400, có ba ca regression malformed JSON.
4. Conversation replay so bằng JSON string gây fail khi object key đổi thứ tự: sửa recursive semantic comparison,
   giữ chính xác value/type/array order, bổ sung test. Hành trình AI thật sau sửa đạt.
5. Property `management.logging.export.enabled` không hợp lệ với Boot đang dùng: sửa sang
   `management.logging.export.otlp.enabled=false`, bỏ property không được hỗ trợ và test metadata thật.
6. Listening bắt exception nhưng observation không phản ánh failure: bổ sung sanitized observation error,
   giữ recall và trạng thái retry, có regression.
7. DB connection/transaction unavailable trả 500, nhất là account validation trước MVC: bổ sung 503,
   `Retry-After: 3`, thông báo cố định/errorId, không bỏ qua quyền, không nuốt lỗi khác, phục hồi MDC.
   Đây là cải thiện failure contract, **không phải đã giải quyết nguồn nghẽn CPU/DB**.
8. Controller từng có thể tiếp tục khi threshold lỗi vẫn dưới 1%: nay dừng escalation khi bất kỳ HTTP/business
   error được quan sát; thêm low-load preflight, stage markers và budget steps. Không có automatic retry.
9. Windows ScheduledTask launch ban đầu dùng sai parameter: sửa bằng ScheduledTaskPrincipal; lượt thực chạy
   có execution limit 30 phút, không trigger lặp, đã gỡ sau đo.

## 5. Bằng chứng kiểm thử và phát hành

| Bộ/hoạt động | Kết quả đã ghi nhận | Phạm vi cần hiểu đúng |
|---|---|---|
| H2 final package | 173 test cases, 0 failures/errors, 1 skip | Có 172 ca thực chạy; skip là benchmark opt-in. |
| PostgreSQL 18.4 final | 173 test cases, 0 failures/errors, 1 skip | Cùng đợt sửa DB overload/filter cuối. |
| PostgreSQL 17.11 gần nhất | 166 test cases, 0 failures/errors, 1 skip | Trước 7 ca MVC/filter cuối; không gọi PG17 final là 173. |
| JavaScript full | 88/88 đạt | Tổng suite; load-contract tests 3/3 là một phần, không cộng thành 91. |
| Authenticated browser | Login/catalog/history trang 2/dashboard/practice/audio activation đạt, không JS error trong probe | Tài khoản synthetic trên app riêng; không full-browser load hay toàn bộ device coverage. |
| Listening smoke | 1 journey, 15 checks đạt | Empty recall, deterministic không AI. |
| Room smoke | 1 room journey, 17 checks đạt | REST/WS/reconnect/draft, không đo băng thông thoại. |
| Real Conversation | 1 journey 4 turns, 28/28 checks; p95 35,55s, p99 36,85s, khoảng 156s/journey | ShopAIKey/Cartesia thật, có hỗ trợ translation; không AI concurrency capacity hoặc bằng chứng trình độ người học. |
| Observability | 12 panels, 7 rules firing/resolved, provider OTLP receiver/unavailable test đạt | Không có permanent monitoring/collector certification. |

Final release đã qua preflight → DB/media backup → restore/app smoke của artifact trước → thay JAR → health.
Restore dùng DB độc lập, không ghi đè production; bản mới được kiểm tra checksum/health sau phát hành.
Không coi smoke artifact cũ trên schema mới là đã test mọi write contract hoặc rollback schema.

- Release backup: `dictalab-20261003-135358-301.dump`.
- Dump SHA256: `56FDBE85D7AB72DEE6B39FFCEBA391EF3DD5549022AE639FB02CCB4196C27477`.
- Restore gần nhất: 24 bảng, 7 users, 36 practice sessions, 3 media; 0 invalid FK/0 unvalidated constraints;
  registration/catalog/start practice smoke đạt, **84,91 giây**.
- Hai bản sao một lần sang HP ngoài repo/target; dump/3 media/JAR checksum được đối chiếu.
  Không tự suy ra RTO phục hồi toàn VPS hoặc RPO media/off-host từ số liệu này.

## 6. Kết quả tải thực tế

### Lượt tối, artifact khớp production

VPS vẫn chạy production và các dịch vụ cần giữ; app thử riêng loopback/DB capacity. Heap thử 512 MiB,
pool 10, HTTP workers 100. k6 chạy cùng VPS; binary checksum đã xác minh.
Dataset trước đo: 1.001 accounts/24.027 sessions. Phần lớn fixtures ngắn một câu/bốn từ.
Limiter chỉ tắt trên app capacity. Không đo TLS/CDN/browser/media hoặc AI trong kịch bản core này.

Tỷ lệ theo **tài khoản**: 80% reader, 20% luyện tập có ghi DB, không phải 80/20 request.
Mỗi account có cookie/CSRF riêng, setup login tuần tự có pacing; work có think time và playback state.

| Accounts / 120 giây work | Chu kỳ hoàn tất | Work p95 | Work p99 | Kết quả |
|---:|---:|---:|---:|---|
| 100 | 4.986 | 32,93 ms | 186,68 ms | 0 HTTP/business error, 100% checks; đạt |
| 200 | 10.019 | 27,19 ms | 108,66 ms | 0 HTTP/business error, 100% checks; đạt |
| 300 | 14.916 | 46,91 ms | 124,90 ms | 0 HTTP/business error, 100% checks; đạt |
| 500, bị cắt | 7.924 | 351,08 ms | 589,09 ms | Không đủ 120 giây; CPU guard dừng; không nghiệm thu |

Mức 300 có một request khoảng 4,12s dù p95 tốt. VU/account có thời gian chờ nên không phải cùng lúc có
300 request đang xử lý. Không tính `http_reqs/s` gồm login là throughput steady-state.

Watchdog toàn batch: 174 samples, CPU đỉnh 100%, free RAM thấp nhất 1.368 MiB, app RSS cao nhất 568 MiB.
Ở 500, sáu mẫu CPU liên tiếp 100/90/100/100/94/95% kích hoạt guard lúc 19:35:18. RAM không chạm sàn 500 MiB.
Generator có RSS peak 956,72 MiB; CPU process snapshots cho thấy app thử và generator đều tiêu thụ đáng kể.
Chưa có profile để quy nghẽn cho một hàm/query hoặc cho riêng một ứng dụng khác.

Sau guard, k6 có 432 transport failures/28.784 HTTP = 1,50%; lỗi kết nối đầu tiên lúc 19:35:19.
Đây **không phải 432 HTTP 500**. Run logs không có application ERROR/DB unavailable; timeout pool snapshot 0,
pool pending có mẫu 11. Không dùng capture bị cắt để nói 500 đạt hoặc xác định website ceiling.
700/1.000 không chạy sau failure. Task và app thử kết thúc trong thời hạn; 36 raw files đã giữ.

### Kết quả trước đó phải tách riêng

- Artifact buổi chiều `0C5BF67446954F744BAFE091CAD1B166334ED6DE01C7E948A0084EDF6266D832`:
  reads warm 300 VUs/600s đạt 85.962 cycles, p95 74,02 ms/p99 315,37 ms, không lỗi;
  spike 200/120s đạt 7.942 cycles, p95 22,34 ms/p99 30,22 ms.
- Không gán các số đó cho JAR tối, không gọi read soak là mixed soak.
- Các cold/500/generator-transport/RAM-abort trước đó được giữ, không xóa hay đổi thành pass.
- Mixed 100 buổi chiều trên JAR 031C có 28 HTTP 500/11.927 requests và watchdog CPU abort, không nghiệm thu.
  Lượt tối điều kiện nền và bản build đã khác; chưa có benchmark kiểm soát để công bố hệ số cải thiện.

## 7. Dọn RAM và bảo vệ ứng dụng khác

Điều tra RAM cho thấy nhiều service cùng dùng VPS, không phải chỉ DictaLab. Production DictaLab khoảng
0,5 GiB RSS tại thời điểm điều tra. Không cộng RSS của mọi PostgreSQL process vì shared pages có thể bị đếm trùng;
committed memory của Bilingual cũng không được xem toàn bộ là RAM vật lý đang dùng.

Đã tạm đóng đúng MoreLogin và tool_auto_register-hoang theo lựa chọn của chủ website; không uninstall/xóa dữ liệu.
Free RAM tăng khoảng 819 MiB ngay sau thao tác, từ 1.801 lên 2.620 MiB. Đây là ảnh chụp trước/sau, không bảo đảm
mọi biến động RAM do riêng hai ứng dụng. Không tắt Windows Defender, WebLens hoặc BilingualBackend.

Sau toàn lượt: production UP, các dịch vụ được giữ đều Running; 0 test processes/0 load tasks,
free RAM 2.844 MiB ở kiểm tra 19:47. Production sau đo có 7 users/37 sessions; không có pre-count tối để quy
thay đổi so với count 36 buổi chiều cho lượt tải. k6 chỉ dùng DB capacity.

## 8. Những phần chưa hoàn thành hoặc cần quyết định

1. Công suất tối đa/breakpoint bền vững; mixed soak dài, dataset lớn hơn và generator ngoài VPS ổn định.
2. Profile CPU/DB trước tối ưu tiếp; chưa đủ căn cứ tăng pool hoặc tắt bảo vệ CPU để đạt 500.
3. SMTP production và gửi email thật; outbox crash/retry/multi-dispatcher fault injection đầy đủ.
4. Monitoring/collector thường trực và nơi nhận alert; nơi nhận được chủ website chủ động hoãn.
5. Trace HTTP/JDBC/job/provider đầy đủ, durable link qua restart, async propagation và overhead thực.
6. Kill/restart jobs thực và đầy đủ race matrix R04/R05/R12; mọi CHECK/FK/reorder path cần coverage riêng.
7. Jitter/Retry-After/backoff policy theo từng IO, có deadline/lease budget và side-effect safety rõ.
8. Automatic off-VPS backup, media backup định kỳ nhất quán, restore toàn máy/secret/TLS/proxy và RPO/RTO.
9. WebRTC thật giữa hai thiết bị Wi-Fi/4G/5G, bandwidth/TURN load; forced relay UDP/TCP và credential
   invalid/expired từng đạt, nhưng không thay cho nghiệm thu cuộc gọi thật. TURN/TLS 443 chưa có.
10. Nhiều app instances: Room và rate limiter còn in-memory, chưa có phương án production cho shared sessions,
    room routing/state, limiter cluster, media shared và phân phối workers. Không tự tăng replicas.
11. Source snapshot/commit sạch để tái lập đúng final artifact; toàn bộ security/UX/accessibility/SEO của site
    không được xem là đã audit chỉ vì readiness tests đạt.

Ưu tiên tiếp theo dựa trên số đo: tách generator sang HP sau khi kiểm chứng transport → mixed soak/profile
CPU/query → tối ưu đúng điểm nghẽn → hoàn thiện các nghiệm thu vận hành/provider. Không bắt buộc chuyển
sang cluster, broker hoặc mua VPS mới khi chưa có bằng chứng cần thiết.

## 9. Tài liệu và file nên gửi thêm khi ChatGPT yêu cầu

Các đường dẫn sau là trong repository; **ChatGPT không tự truy cập được file local chỉ từ đường dẫn**.
Chỉ nên gửi code/config/log đã lọc; không gửi `.env`, DPAPI secrets, cookies, token, DB dump hoặc private media.

- Kế hoạch R01–R18 gốc, `docs/readiness-contracts.md`, `docs/production-readiness-progress.md`.
- `docs/capacity-report-2026-10-03-evening.md`, `docs/capacity-report-2026-10-03.md`, `docs/operations-runbook.md`.
- `src/main/resources/db/migration/V23__reliability_and_delivery.sql`,
  `src/main/java/db/migration/V24__Postgres_uniqueness.java`, `scripts/readiness-preflight.sql`.
- Các service/repository liên quan, đặc biệt `EmailOutbox`, `ListeningComprehensionService`,
  `ConversationService`, `ProviderGuard`, `ShopAiKeyClient`, `CartesiaClient`.
- `RequestRateLimitFilter`, `RequestCorrelationFilter`, `ApiExceptionHandler`, `SecurityConfig`, `application.yml`.
- `ProductionReliabilityTest`, `ConversationIntegrationTest`, `ProviderGuardTest`,
  `RequestRateLimitFilterTest`, `RequestCorrelationFilterTest`, `TracingExportTest`, `ApiExceptionHandlerTest`.
- `scripts/load/`, controller/monitor, backup/restore/update scripts và `ops/observability/`.
- Raw final suites: `target/readiness/H2-20261003-134939-479.stdout.log`,
  `Postgres18-20261003-135137-497.stdout.log`, `Postgres17-20261003-131532-429.stdout.log`,
  `JavaScript-20261003-140000-814.stdout.log`.
- Runtime evidence: `target/capacity/evidence-20261003-evening/`,
  `target/readiness/release-database-overload-20261003.log`, observability result JSON.

## 10. Đề nghị ChatGPT kiểm tra độc lập

Hãy đối chiếu báo cáo này với kế hoạch R01–R18 gốc. Phân biệt có implementation, có test và đủ nghiệm thu.
Không coi “173 tests” là phủ mọi requirement hoặc “300 VUs/2 phút” là capacity ceiling/SLA.

Hãy trả lời:

1. Mục nào đáp ứng phần chính, mục nào còn thiếu bằng chứng, mục nào bị hoãn hợp lý?
2. Có tuyên bố nào vượt quá dữ liệu, mâu thuẫn giữa versions/topology hoặc thiếu điều kiện an toàn không?
3. Risk/bug đáng kiểm tra theo P0/P1/P2, đặc biệt outbox/lease/transaction/idempotency/rate limiter/tracing.
4. Những file hoặc raw output tối thiểu cần gửi thêm để xác minh từng vấn đề; không giả định đã đọc repo.
5. Bước tiếp theo nào tạo giá trị cao nhất trên VPS hiện tại, giữ ứng dụng khác chạy và không dựng kiến trúc
   phức tạp khi chưa cần? Nêu cách nghiệm thu và stop condition cho từng bước.

Các kết quả trong tài liệu được tổng hợp từ code, test logs và nhật ký triển khai hiện có. Lần viết báo cáo
này không chạy lại load, không chỉnh production và không khẳng định mọi luồng đã qua kiểm toán độc lập.
