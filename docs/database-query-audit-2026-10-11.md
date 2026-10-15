# Audit database queries — DictaLab, 11/10/2026

## Kết luận

Chưa tối ưu hết. Còn query trùng, query chạy nền khi không có request, transaction giữ connection qua xử lý BCrypt, và một số đường đọc tăng theo dữ liệu. Không phát hiện bằng chứng mới về sự cố P0 trong phạm vi audit này. Không sửa code ứng dụng, cấu hình, index hoặc dữ liệu production.

Thứ tự đề nghị: giảm polling rỗng → loại query trùng nghe hiểu/Coach → rút ngắn transaction auth → kiểm chứng lại tải → xử lý các index/đường đọc tăng theo dữ liệu khi có đủ bằng chứng.

## Phương pháp và trạng thái thực tế

- Đọc 8 JPA repositories, entity mappings, controller/service liên quan history, dashboard, recommendation, assessment, dictation/Room, listening, Coach, conversation/audio, admin/account/support và jobs email/AI/S3.
- Đối chiếu query với migrations và index **đang tồn tại trên PostgreSQL production**. Open Session in View=false; Spring Boot 4.1.1/Hibernate 7.4.5 trong build local.
- SSH tới VPS hiện tại; PostgreSQL kết nối qua cấu hình backup có sẵn, không in credentials. Mọi SQL audit đặt `default_transaction_read_only=on`, statement timeout 3 giây; không chạy EXPLAIN ANALYZE trên UPDATE/DELETE, không bật extension hoặc ANALYZE production.
- PostgreSQL production báo phiên bản 18.6; bộ test đợt tối ưu trước dùng PostgreSQL 18.4. Pool đo được tối đa 5. AI queue và listening Rabbit queue đều false. Điều này không có nghĩa mọi chức năng AI/listening bị tắt: fallback worker/polling vẫn tồn tại.
- Production JAR SHA-256: `eb3b0587efb9c696f08ed08f9834ee742e6dc979ccd5657f2f77eb8c53fb7d81`, khác JAR local tối ưu `836baf26220e655d1ec20633b07404c9cce2704f6661a1f445eb02b57971d419`.
- Dữ liệu đếm thực: 123 lessons, 249 practice sessions, 9 published listening units, 11.222 word attempts, 2 account tokens. Các con số `n_live_tup` từ thống kê không được coi là số đếm chính xác.

## Đo trực tiếp, không phải load test

| Phép đo | Kết quả | Giới hạn |
|---|---:|---|
| Hai snapshot Hikari cách nhau 20,054 giây | 121 acquire, khoảng 6,03/s | Bộ đếm HTTP không tăng; không có tracing để quy từng acquire cho job |
| Connection usage của cửa sổ trên | Trung bình 12,82 ms; tổng 1,551 connection-seconds | Khoảng 0,077 connection bận trung bình, chưa đủ giải thích toàn bộ nghẽn 50 VU |
| Pool timeout trong cửa sổ | 0 | Không chứng minh kết quả lúc tải cao |
| Snapshot DB activity | 0 active khác probe, 0 idle-in-transaction, 0 lock waiters | Chỉ một thời điểm |
| 10 lần SELECT 1 trên cùng kết nối psql từ VPS | Median 15,899 ms; min 13,814; max 32,244 | Gồm round-trip/proxy/client, không phải thời gian SQL/JDBC thuần |

EXPLAIN ANALYZE/BUFFERS với dữ liệu hiện tại, một lần mỗi query, không đồng thời với k6:

| Query | Execution time tại PostgreSQL | Bằng chứng |
|---|---:|---|
| History page shape | 0,387 ms | Dùng `idx_sessions_user_status_completed`, trả 15 dòng |
| Dashboard totals shape | 0,256 ms | Aggregate 15 sessions của user được chọn |
| Assessment evidence shape | 0,398 ms | Trả 10 dòng, phần anti-join lặp trên nhóm evidence |
| Listening catalog | 2,601 ms | 9 dòng; Seq Scan lesson_sentences lặp 9 lần, khoảng 3.782 dòng bị lọc/lần |
| Review marks | 0,112 ms | Seq Scan bảng hiện có 4 marks |
| Admin lesson page shape | 3,159 ms | Trả 20 dòng; count câu dùng index; tồn tại session/unit được planner xử lý riêng |

Các query có chữ “shape” được chuyển tương đương từ source để khảo sát plan, không phải SQL bắt tại request JDBC thực. Listening catalog dùng query từ source với user ID nội bộ; chỉ xuất timing/plan, không xuất dữ liệu tài khoản/bài làm. Query review marks có thêm subquery chọn fixture hiện hữu; timing gồm cả phần chọn này.

Neon chưa cài extension `pg_stat_statements`: chưa thể xếp hạng tổng thời gian SQL theo workload thật. Không diễn giải seq_scan tích lũy là số query/s hoặc lỗi thiếu index. SQL engine nhanh trong mẫu này, cộng với pool pending ở campaign trước, hỗ trợ ưu tiên giảm round-trip/connection hold; chưa tách được toàn bộ đóng góp network, Redis, auth và CPU steal.

## Findings theo ưu tiên

### P1 — Listening đọc lại dữ liệu trong cùng action và lấy cột lớn không cần thiết

Nguồn: `ListeningComprehensionService.java:127,193,240,408,909`; `static/js/listening.js:330`.

- `saveRecall`: session SELECT/lock → UPDATE → `state()` đọc session/pass/unit lần nữa: 4 SELECT + 1 UPDATE, chưa tính auth và transaction control.
- `saveAnswers`: đọc session và unit để validate → UPDATE → `state()` đọc cả ba: 5 SELECT + 1 UPDATE.
- `review`: đọc session/unit rồi gọi `report()` đọc lại hai đối tượng, sau đó đọc sentences/marks: 8 SELECT. `addMark()` gọi tiếp review trong transaction ghi, kéo dài thời gian giữ khóa.
- `state()` lấy `SELECT *` của pass/unit ngay cả khi stage không dùng content/recall-result JSON. Đây là query amplification xác định từ JDBC call path, chưa đo latency endpoint mới.
- Recall autosave debounce 800 ms, answer autosave 600 ms: giảm query/action có lợi mà không cần giảm tần suất lưu bài.

Sửa nhỏ nhất: builder nhận session/unit/pass đã đọc, cập nhật map theo thay đổi đã persist hoặc đọc lại đúng phần cần thiết; projection theo nhu cầu. Giữ lock parent, stage validation, ownership và API response. Không tái sử dụng bản cũ sau UPDATE mà quên cập nhật stage/version.

Nghiệm thu: đếm cả JDBC lẫn JPA (StatementInspector chỉ đếm JPA); saveRecall ≤3 SELECT +1 UPDATE, saveAnswers ≤3 SELECT +1 UPDATE, review ≤6 SELECT; response cũ/mới tương đương, autosave-submit race và rollback pass trên PostgreSQL.

### P1 — Coach kiểm tra cache quá muộn, kéo theo assessment không cần cho Coach

Nguồn: `CoachService.java:80`, `HistoryService.java:90`, `PracticeService.java:414`, `static/js/coach.js:67`.

`report()` gọi `history.detail()` trước SELECT cached report. Detail tải attempts, events, words, lesson và tính assessment tối đa 10 bài qua `toResult()`. Cache hit vẫn chịu toàn bộ phần dựng lịch sử này. Coach không dùng trường listeningAssessment của LessonResult. Khi queue bật, frontend poll 2 giây/lần tối đa 90 lần và có thể lặp việc dựng evidence; queue hiện tắt nên không quy phần polling đó vào tải hiện tại.

Sửa nhỏ nhất: query ownership + completed + cached report trước; nếu cache hit trả luôn. Cache miss dùng đường dựng evidence không tính assessment không sử dụng. Không trả cached report trước khi kiểm tra ownership/eligible status; không cache quyền qua request.

Nghiệm thu: cache hit ≤2 SELECT service-only, không gọi HistoryService/Assessment; owner vẫn xem được, user khác/deleted/incomplete bị chặn. Cache miss cho cùng candidates/progress như cũ; queue polling không tăng SQL theo số câu khi đã có report.

### P1 — Polling rỗng tạo tải DB đều đặn

Nguồn: `EmailOutbox.java:31`, `ListeningComprehensionService.java:632,682`, `ReadinessMetrics.java:37`, `S3MediaLifecycle.java:45`.

- Khi worker rảnh, listening pendingGrades chạy 2 recovery UPDATE +1 SELECT mỗi chu kỳ mặc định 1 giây, dù broker tắt.
- Email deliver chạy cleanup token UPDATE mỗi chu kỳ; khi SMTP có cấu hình còn thêm recovery UPDATE +1 SELECT. Tất cả hiện dùng cùng pool ứng dụng.
- ReadinessMetrics đã snapshot theo 30 giây (đúng hướng). Tuy nhiên gauge S3 cleanup vẫn query DB tại scrape; AI pending gauge cũng vậy nếu AI queue bật.
- Mẫu production 121 acquire/20 giây, không tăng bộ đếm HTTP, phù hợp chi phí polling; chưa có trace chứng minh mọi acquire đều do hai job này. Hai lần scrape của phép đo cũng có overhead gauge.

Sửa nhỏ nhất: tách quét recovery/expiry khỏi dispatch nhanh; backoff có giới hạn khi hàng đợi rỗng và wake-up sau commit khi có việc; vẫn có periodic sweep để phục hồi sau crash/missed wake-up. Gauge đọc snapshot thay vì truy vấn đồng bộ. Không tắt recovery, không tăng delay khiến người dùng chờ tùy tiện.

Nghiệm thu: quan sát idle 60 giây giảm acquire nền so với baseline cùng cấu hình; định nghĩa và giữ SLO enqueue→start; test restart/lease expired/token consumed/SMTP failure/broker outage và rollback không mất việc. Không dùng mức 6,03/s để hứa tăng tương ứng HTTP RPS.

### P1 — Auth giữ transaction qua BCrypt

Nguồn: `AccountService.java:40,63`, `AccountManagementService.java:71,153`, `SecurityConfig.java:51`.

Register/login đặt toàn bộ method trong transaction; sau SELECT, xử lý BCrypt cost 12 và authenticate nằm trong scope đó. Register còn encode rồi authenticate lại mật khẩu vừa tạo. Reset password giữ user row lock trước khi encode. Khi login/reset burst, connection/lock có thể bị giữ trong lúc CPU hash, làm request nghiệp vụ phải chờ. Đây là kết luận từ transaction path; chưa đo connection-hold của BCrypt trên VPS và không phải nguyên nhân đã chứng minh của pha work trong k6 (login chạy ở setup).

Sửa nhỏ nhất: hash/verify ngoài transaction ghi; transaction ngắn load/revalidate enabled/authVersion/password-state trước cập nhật và phát hành auth. Giữ chống race khi admin revoke/disable/reset song song; không hạ BCrypt cost để đạt benchmark.

Nghiệm thu: test concurrent login-disable/password-reset/token reuse; rollback không phát hành session hợp lệ sai; dùng latch quanh encoder kiểm tra không giữ connection/row lock qua hashing; đo riêng login burst và CPU.

### P2 — Catalog nghe hiểu có correlated COUNT và thiếu index cho audio-only lookup

Nguồn: `ListeningComprehensionService.java:82,106`, `PracticeService.java:115`, `S3MediaLifecycle.java:94`.

Catalog trả toàn bộ published units; mỗi dòng chứa ba phép đếm lịch sử. Production plan đã xác nhận lesson_sentences bị quét 9 lần cho 9 bài (3.796 sentences). Các index hiện có bắt đầu bằng lesson_id, không hỗ trợ tốt predicate chỉ audio_url. 2,601 ms hiện chưa phải query chậm nghiêm trọng, nhưng độ lớn tăng theo units × sentences.

Sửa nhỏ nhất: thử batch aggregate theo audio và benchmark với 10/100/1.000 units; thử index `lesson_sentences(audio_url,lesson_id)` trên disposable DB trước. Với start chỉ cần boolean “đã từng nghe”, cân nhắc EXISTS; không đổi trường API prior_sessions từ count thành boolean mà không xét callers. Giữ semantics một session không bị đếm nhiều lần do nhiều câu cùng audio.

Nghiệm thu: cùng kết quả count/firstExposure khi audio dùng chung nhiều bài, retired units và nhiều câu; so sánh buffers/loops/latency với dữ liệu lớn. Chỉ thêm migration sau khi đánh giá write/storage cost và rollout.

### P2 — Endpoint admin cũ còn N+1 không phân trang

Nguồn: `AdminContentService.java:57,323`, `AdminContentController.java:59`.

GET `/api/admin/lessons` đọc tất cả lessons; mỗi lesson gọi exists practice, count sentences và có thể exists listening. Source cho số truy vấn `1 + 2N + U` (0≤U≤N), chưa tính auth/transaction: với 123 bài, suy ra 247–370 query. Đây là count suy từ call path, chưa gọi endpoint production để đo.

UI hiện dùng `/api/admin/lessons/page` (`app.js:2656`), nên không quy N+1 legacy vào dashboard admin hiện tại hoặc k6 reader. Endpoint vẫn reachable cho integrations.

Sửa nhỏ nhất: giữ contract legacy nhưng gom projection/aggregate; ưu tiên page API cho caller mới, không âm thầm cắt kết quả hoặc xóa endpoint. Test 1/100 lessons có query budget cố định và đúng content_locked.

### P2 — History/assessment tăng theo dữ liệu, còn index trùng và thiếu ở đường phụ

Nguồn: `PracticeSessionRepository.java:42,65,78`, `HistoryService.java:53`, `AdminQueryService.java:120`, `AccountManagementService.java:203`.

- History list đã bounded, join-fetch to-one, sort completedAt/id và count query riêng: không thấy N+1 ở mapper. OFFSET sâu và exact count vẫn tăng chi phí. Listening history có index đúng `(user_id,started_at DESC,id DESC)`.
- Dashboard totals quét lịch sử user; assessment trả tối đa 10 roots nhưng tải tất cả attempts/events của evidence. Bounded query count không đồng nghĩa bounded rows/bytes.
- Production có các cặp index cùng key: sentence lesson/position, preview lesson/position, word sentence/index, event word/attempt (index thường + unique index); các index session-attempt và listening-history cũng có prefix chồng lặp. Gây thêm write/storage overhead, chưa định lượng.
- Thiếu index theo predicate ở `listening_review_marks(session_id,id)`, account_audit subject/id và các bộ lọc admin toàn cục. Hiện bảng marks/audit rất nhỏ nên Seq Scan hợp lý, chưa đủ căn cứ thêm ngay.

Sửa nhỏ nhất: scale test user 10k/100k sessions và events; nếu cần thêm keyset API song song API page cũ. So sánh index mới `(user_id,status,completed_at DESC,id DESC)` với index hiện có, tránh thêm trùng. Audit dependency/usage/constraint trước bất kỳ DROP INDEX; không đụng unique constraints.

Nghiệm thu: đúng thứ tự khi timestamp trùng, không trộn user/room/level; ngân sách query, row count, buffers và peak heap. Mọi index change cần riêng migration/recovery plan, không thực hiện trong audit này.

### P2 — Conversation audio vẫn đi qua DB và global Java monitor

Nguồn: `ConversationService.java:326,435`, `ConversationController.java:63`.

Audio GET load/parse toàn bộ session JSON để kiểm tra ownership rồi SELECT toàn bộ WAV; trả byte[] qua backend. 16 WAV hiện có tổng 7.458.528 bytes, lớn nhất 618.318 bytes, trung bình 466.158 bytes. Đây là chi phí transfer/heap trên VPS 1 GB, không phải N+1 hoặc lỗi SQL.

Fallback `prepareAudio()` synchronized trên toàn service và thực hiện SELECT bên trong monitor. Một query chờ pool có thể chặn các prepareAudio khác và callback cần cùng monitor. AI queue đang tắt nên đường fallback có thể được dùng khi có TTS.

Sửa nhỏ nhất: ownership projection/join cho audio; bỏ DB I/O khỏi global monitor nhưng giữ double-check/single-flight theo key. Chỉ cân nhắc S3 private đã có khi audio workload thật chứng minh cần; không mặc định chuyển storage trong đợt sửa nhỏ.

Nghiệm thu: khác user không đọc được WAV; nhiều request cùng key chỉ tạo một tác vụ; khác key không bị khóa theo DB call khác; kiểm tra DB byte transfer/RSS và payload audio, không chỉ API JSON.

## Những phần đang làm đúng, không nên viết lại

- History list phân trang và fetch lesson; history detail đã batch words/events, không query word riêng từng câu.
- Room dùng registry và tái sử dụng PracticeService; chưa thấy vòng lặp SQL theo tất cả thành viên. Readiness hiện dựng full state để giữ quy tắc canComplete; không nên bỏ validation chỉ để giảm query. Chưa load test Room.
- Aggregate lock/user lock và PostgreSQL unique partial indexes chống trùng vẫn cần giữ. Không xem mọi lock là bottleneck cần bỏ.
- Listening grading, conversation AI/TTS, email SMTP và S3 cleanup tách external I/O khỏi transaction ghi ở các call path đã đọc. AI jobs có claim/lease/fenced commit và giới hạn worker; không thêm transaction bao quanh provider call.
- Admin page đã limit 20; error bank limit 100, conversation catalog limit 12. LIMIT giúp giới hạn response nhưng không tự bảo đảm query plan rẻ.
- Lượt tối ưu trước có 60 test H2 và 78 test PostgreSQL pass; đây là bằng chứng đợt trước, không phải test mới chạy trong audit này.

## Thứ tự thực hiện và phạm vi chưa xác nhận

1. Dùng bản tối ưu local đã có làm mốc; bổ sung query counter bao phủ JDBC trước khi sửa listening/Coach/polling.
2. Xử lý P1 từng nhóm, giữ response và dữ liệu đúng; auth boundary phải có race tests riêng.
3. Deploy artifact đã nghiệm thu rồi đo cùng workload 20/50/100 VU; giữ nguyên SLO, think time và feature flags. Đo riêng auth/listening/AI/audio/Room, không trộn rồi kết luận chung.
4. Chỉ thêm index/cache sau EXPLAIN và scale test; không tăng pool/thread mù. Cache nếu cần phải có quyền truy cập, invalidation và giới hạn memory rõ ràng.

Lượt này chỉ audit source và diagnostic production read-only; không chạy load mới, không phát email/AI call, không sửa config/schema/data. Chưa có top SQL theo thời gian tích lũy, JDBC tracing cho mọi route hoặc chứng minh 100 VU/30 phút. Không cộng execution time EXPLAIN với RTT mẫu để tuyên bố latency API.

Skill JPA Query Reviewer được dùng để đối chiếu controller→service→query→mapping, tách số query khỏi số rows/bytes và yêu cầu bằng chứng index. Báo cáo được kiểm tra UTF-8 bằng Vietnamese Encoding Guard.
