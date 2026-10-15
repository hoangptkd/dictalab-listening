# DictaLab — invariant, transaction và retry contracts

03/10/2026. Đây là thiết kế triển khai, không phải chứng nhận mọi kiểm thử đã đạt. Bằng chứng thực chạy nằm trong `production-readiness-progress.md` và `target/readiness/`.

## Ma trận schema

23 bảng nghiệp vụ sau V23 (21 trước V23), cộng `flyway_schema_history`. PK và các trường bắt buộc giữ nguyên; không sửa migration V1–V22.

| Bảng | Bảo vệ chính và hành vi xóa |
|---|---|
| app_users | email unique + lowercase/trim, role USER/ADMIN, version/auth_version không âm |
| lessons | slug unique; CEFR, độ khó, origin hợp lệ; xóa nguồn đã có lịch sử bị chặn bởi FK/service |
| lesson_sentences | FK lesson cascade, unique lesson/position; **không** CHECK position dương vì reorder có vị trí âm tạm |
| lesson_preview_words | FK lesson cascade, unique lesson/position, khoảng audio hợp lệ |
| practice_sessions | FK user/lesson; status, điểm 0–100, counters không âm; PG partial unique active solo và user/room |
| sentence_attempts | FK session cascade, FK sentence restrict; unique session/sentence, counters không âm |
| word_attempts | FK sentence attempt cascade, unique attempt/index; index và count không âm; error/reveal enums |
| word_attempt_events | FK word cascade, unique word/attempt number; number >0, response time >=0 |
| listening_units | FK lesson/supersedes restrict; duration >0; PG một draft và một published đang hoạt động/audio |
| listening_sessions | FK user/unit restrict; mode, pass, stage hợp lệ; index user/startedAt/id phục vụ pagination |
| listening_passes | PK session/pass, FK session cascade; status/counters/điểm; lease UUID fencing kết quả |
| listening_review_marks | FK session cascade, FK sentence restrict; difficulty enum; **không** unique câu vì người học có thể ghi nhiều nhận xét |
| audio_exposure_events | FK user; mỗi lượt exposure là event riêng, không dedup các lượt nghe hợp lệ |
| account_tokens | hash PK, FK user restrict, purpose enum, expires > created; consume bằng UPDATE có used_at/expiry predicate |
| account_audit | actor/subject FK restrict; không tự xóa audit để xóa tài khoản |
| support_tickets | FK user restrict; status enum; edit_version bảo vệ sửa ticket |
| admin_events | FK actor SET NULL; object_type/id **polymorphic**, không FK giả; giữ audit khi nội dung bị xóa |
| dictation_coach_reports | session PK/FK cascade: một report/session |
| dictation_error_bank | FK user/session cascade; unique user/session/item |
| ai_conversation_sessions | FK user cascade; scenario/level/version; optimistic claim + lease; quota khóa user trước count/insert |
| ai_conversation_audio | PK session/turn/variant, FK session cascade; turn 1–4, original/clarify; audio riêng tư |
| business_locks | PK khóa chính sách admin; chỉ tuần tự hóa thao tác thay admin, không khóa mọi request |
| email_outbox | token unique/FK cascade; trạng thái/attempt budget; lease fenced, xóa link sau gửi/hết hạn |

User/room unique phù hợp luồng hiện tại: mỗi phòng chỉ chọn một bài, mỗi người giữ một practice session kể cả sau hoàn thành. Solo active unique không áp dụng cho shared. H2 không mô phỏng partial index; PostgreSQL thật là cổng nghiệm thu.

## Migration an toàn

- Chạy `scripts/readiness-preflight.sql` read-only trên DB V22; mọi `violations=0` trước V23/V24. Có dữ liệu lỗi thì dừng, lưu bằng chứng, xử lý nghiệp vụ riêng; không xóa trùng tự động.
- V23 thêm CHECK có validate ngay và index thường; có thể lấy khóa DDL và scan bảng. Với DB production khoảng 17 MB và chưa có người dùng, triển khai trong maintenance window. Không tuyên bố zero-downtime cho DB lớn.
- V24 giới hạn lock wait 5s và statement 30s, dùng index partial thường trong transaction. Build thêm chi phí ghi; không thêm các index trùng hiện có. DB lớn phải chuyển sang expand/validate và concurrent index bằng migration riêng phù hợp.
- Rollback JAR không rollback schema. Các cột/bảng mới additive, nhưng CHECK/unique còn hiệu lực với JAR cũ. Phải kiểm thử artifact cũ trên bản sao đã migrate trước khi coi rollback có thể sử dụng.

## Transaction / IO inventory

| Luồng | Phase và lỗi |
|---|---|
| Practice check/hint/complete | transaction DB ngắn, khóa session cha trước child writes; rollback toàn thao tác; read-only không khóa |
| Solo/shared start | khóa user → tìm phiên hiện có → insert; invariant DB ngăn insert cạnh tranh ngoài service |
| Admin role/enabled | khóa business policy → đọc/count → ghi/audit cùng transaction |
| Email request | khóa user và quota → token + outbox trong cùng commit; rollback không phát email |
| SMTP dispatcher | conditional claim autocommit → SMTP ngoài transaction (connect 5s, read/write 10s) → fenced completion; tối đa 5 attempts, lease 90s |
| Listening create | short source snapshot → AI ngoài transaction → khóa/revalidate source/draft → save; source thay đổi thì conflict, không xuất bản nội dung sai |
| Listening grade | atomic claim + lease/attempt → provider ngoài transaction, deadline 60s → score/fenced update; lease 120s, tối đa 3 attempts mỗi lượt và 2 manual retry |
| Conversation | DB optimistic claim lưu pending answer → AI grading/generation → fenced commit; lease 210s; không transaction qua provider |
| Conversation TTS | bounded workers/queue → TTS tối đa 45s → so sánh utterance/state và insert audio; không giữ session lease qua TTS |
| Admin media | filesystem IO + DB transaction; callback xóa file cũ khi commit hoặc file mới khi rollback. Process chết có thể để orphan: reconcile manifest/DB trước xóa; không coi DB rollback là rollback filesystem |
| Coach | giữ fallback quy tắc hiện có; provider failure không tạo grade giả. Giới hạn bridge hiện có vẫn được bảo toàn |

## Mutation/retry inventory

- `start`/resume: cùng user/lesson đang học trả cùng phiên; shared trả phiên user/room hiện có.
- Sentence complete: đã complete không được tăng counter hay nhảy lại. Check/replay là event thật, mỗi lần hợp lệ vẫn được đếm; không áp dụng global idempotency.
- Conversation answer: actor/session/requestId/expectedTurn/body. Replay cùng payload không gọi lại provider; mismatch trả conflict; pending được lưu trước IO. Quyền đọc hiện tại vẫn kiểm tra. Claim concurrency/stale lease không ghi đè.
- Conversation next: expectedTurn và state bảo vệ tạo trùng. Create giữ daily quota; **không** dedup hai thao tác create có chủ ý.
- Token consume: một conditional update thắng, request sau bị từ chối; không hứa exactly-once cho email/provider ngoài hệ thống.
- Email: at-least-once, có thể trùng nếu crash sau SMTP accept trước DB commit; link dùng một lần bảo vệ thay đổi tài khoản, không bảo đảm người dùng chỉ nhận một email.
- Listening retry: quyền owner/admin, FAILED và retry_count <2; concurrent retry chỉ một UPDATE thắng. FAILED là dead letter giữ recall để hỗ trợ người học.
- Admin sửa: edit version; Room: membership lock/sequence. Không retry mù mutation khi chưa biết request trước thành công.

## Giới hạn có chủ ý

- Room và rate limiter chỉ một JVM; restart mất room/buckets. Mở rộng nhiều instance cần shared state hoặc sticky routing có kế hoạch, không chỉ tăng số instance.
- Không generalize shared durable queue: Listening grading và SMTP có payload/expiry/failure semantics khác nhau; hai dispatcher cụ thể nhỏ hơn một framework. R11 deferred theo điều kiện trong kế hoạch gốc.
- Tracing HTTP và provider dùng Observation; job nền là trace riêng. Chưa có liên kết durable trace qua restart; không export prompt, answer hoặc secrets. Không tuyên bố đã trace từng JDBC statement nếu chưa instrument JDBC.
- `/api/v1/listening/history/page` là contract mới; `/history` legacy chỉ trả 100 mục đầu, không còn tải vô hạn. UI mới dùng page endpoint.
