# DictaLab: kiểm tra chức năng và thời gian production — 11/10/2026

## Kết luận

Các luồng thông thường đã kiểm tra hoạt động: tài khoản, catalog A1–B2, hoàn thành dictation,
lịch sử/dashboard, Coach theo luật/error bank, listening một/hai lượt, hỗ trợ, audio trên VPS,
phòng học đôi và WebSocket. **AI chưa đạt:** làm rõ hội thoại và chấm trả lời trả 503;
Coach yêu cầu AI trả 200 nhưng chuyển sang `UNAVAILABLE`, không phải thành công AI.

Không sửa code ứng dụng, cấu hình, schema hay deploy. Chỉ tạo kịch bản đo và bằng chứng.
Không xóa dữ liệu thật hoặc dữ liệu thử trong database. Các phòng và phiên trình duyệt thử đã đóng.

## Cách đo và giới hạn

- Production: `https://184.194.89.90`, VPS vẫn 1 GB RAM / 2 vCPU, PostgreSQL Neon, Redis Upstash.
- Máy Windows chạy HTTP probe và Chromium; không tạo tải cao trên VPS. Chỉ probe đối chiếu
  read-only chạy loopback trên VPS. TLS luôn được kiểm tra.
- 475 request HTTP trong probe chính: 457 phản hồi 2xx, 15 từ chối dự kiến, 3 phản hồi ngoài kỳ vọng.
  Trong 3 phản hồi đó có **1 lỗi thứ tự của kịch bản phòng**, đã sửa kịch bản và kiểm chứng lại;
  **2 lỗi 503 của AI** giữ nguyên bằng chứng. Coach fallback 200 là lỗi nghiệp vụ AI riêng.
- Thêm 68 request hiệu chuẩn read-only, 10 phép đo WebSocket và 7 chuyển trang có đo trong trình duyệt.
  Các tập này không gộp vào 475 request. Số nhãn operation không phải số tính năng độc lập.
- Phần lớn luồng chạy tuần tự; hai probe read-only đối chiếu có thời gian chồng với dictation.
  Đây không phải benchmark tải, không chứng minh mức đồng thời tối đa hoặc SLO production.
- Dùng một tài khoản mới `USER` thử nghiệm và các tài khoản `@loadtest.local` đã có.
  Đổi mật khẩu/đăng xuất mọi thiết bị chỉ áp dụng tài khoản mới. Không nâng quyền tài khoản.
- HTTP đo từ lúc gửi đến khi đọc hết body, có request ID; không gồm thời gian gõ hoặc think time.
  Request đầu mỗi kết nối có thể gồm DNS/TCP/TLS. Chấm nền đo từ submit đến khi poll thấy kết quả,
  gồm độ trễ poll 2 giây và scheduler, không phải thời gian provider thuần.
- Dữ liệu nhỏ: ghi **n, trung bình và min/max**, không suy diễn p95/p99 ổn định.
  Nhiều thao tác ghi chỉ có một mẫu. Không lặp lại các lời gọi AI thất bại.
- Thời gian probe chính khoảng 11:34:57–11:46:39 giờ Việt Nam/Thái Lan (UTC+7).

## Thời gian nổi bật

| Chức năng | n | Trung bình | Min–max | Kết quả |
|---|---:|---:|---:|---|
| Đăng ký | 1 | 1.022 s | một mẫu | 201, đúng USER |
| Đăng nhập | 8 | 0.654 s | 0.633–0.667 s | Đạt |
| Catalog A1 | 5 | 0.425 s | 0.416–0.431 s | Đạt; A2/B1/B2 cũng đã đo |
| Hoàn thành câu dictation | 36 | 0.626 s | 0.545–2.903 s | Đạt; mẫu cuối hoàn thành cả bài |
| Xem kết quả dictation 36 câu | 5 | 1.568 s | 1.474–1.601 s | Đạt, nhưng chậm |
| Coach theo luật | 5 | 1.127 s | 1.100–1.156 s | Đạt, không gọi AI |
| Coach yêu cầu AI | 1 | 14.381 s | một mẫu | **Không đạt AI:** source UNAVAILABLE, HTTP 200 |
| Listening nộp recall | 3 | 0.431 s | 0.425–0.439 s | Đạt |
| Listening nộp trắc nghiệm | 3 | 0.456 s | 0.453–0.461 s | Đạt |
| Listening chấm recall có nội dung | 1 | 7.601 s | một mẫu | COMPLETED; AI thật, gồm polling |
| Listening chấm recall trống | 2 | 2.870 s | 2.869–2.871 s | COMPLETED; **không gọi AI** |
| Listening report | 10 | 0.402 s | 0.387–0.418 s | Đạt |
| Listening review/transcript | 10 | 0.444 s | 0.429–0.463 s | Đạt |
| Hội thoại tạo nhiệm vụ đầu | 1 | 12.904 s | một mẫu | Đạt, nhưng chậm |
| Hội thoại play/TTS | 2 | 1.039 s | 0.384–1.694 s | Đạt; lần sau dùng audio đã có |
| Hội thoại tải WAV 337,998 byte | 2 | 1.468 s | 1.442–1.495 s | Đạt; không phải thời gian bắt đầu nghe |
| Hội thoại làm rõ | 1 | 4.561 s | một mẫu | **503** |
| Hội thoại chấm trả lời | 1 | 60.386 s | một mẫu | **503, timeout** |
| Tạo ticket hỗ trợ | 1 | 0.463 s | một mẫu | Đạt; đọc lại đúng ticket |
| Tạo phòng / duyệt khách | 2 mỗi loại | 0.328 / 0.329 s | xem phụ lục | Đạt |
| Chọn bài chung / chuyển câu | 2 / 1 | 0.541 / 0.326 s | xem phụ lục | Đạt |
| WebSocket ping/pong | 5 | 0.261 s | 0.253–0.282 s | Đạt |
| WebSocket relay bản nháp | 1 | 0.289 s | một mẫu | Đạt; REST đọc lại đúng bản nháp |

[Bảng đầy đủ từng operation](../target/function-latency-20261011/operation-table.md)
ghi cả lỗi ban đầu, request phụ, min/max và status. Không coi HTTP 200 là đủ để kết luận AI đúng.

## Phạm vi kiểm chứng nghiệp vụ

| Nhóm | Đã kiểm tra trong lượt này | Chưa kiểm tra / giới hạn |
|---|---|---|
| Auth/account | Register, login, me, refresh, profile, password, logout-all, logout; me trả 401 sau thu hồi | Email reset/verify/change chưa test end-to-end vì SMTP và mail-from chưa cấu hình |
| Catalog | Levels, A1/A2/B1/B2, ghi preview exposure | Không kiểm từng bài hoặc từng từ phát bằng Web Speech |
| Dictation | Resume GET, replay, trả lời sai, first-letter, reveal-word/sentence, phân loại lỗi, hoàn thành bài 36 câu | Bộ đúng/sai 24 mẫu có ở báo cáo CSRF trước; lượt này không kiểm mọi biến thể/tất cả level |
| History/dashboard | Danh sách, kết quả, dashboard, ẩn kết quả của chủ khác | Deep pagination, dữ liệu hàng triệu dòng chưa test |
| Coach/error bank | RULES report, save error, đọc lại đúng item, POST AI fallback | Không có AI report thành công để test cache AI |
| Listening | ONE_PASS có recall thật; TWO_PASS recall trống; play gate chờ đúng 25.18 s/lượt, draft/submit recall và MCQ, grading, report/review, cả hai loại review mark, abandon, ownership | Không nghe bằng thiết bị người dùng; không test grading retry có side effect hoặc manual grade admin |
| Conversation | Catalog/create/get, nhiệm vụ đầu, original TTS/audio/status, transcript/translation, clarify thất bại, grade timeout, đọc lại pending, ownership | Không hoàn tất 4 nhiệm vụ, chưa test summary hoặc completed-answer idempotent replay; clarify audio/slow chưa tới được |
| Rooms | Create/mine/invite/join/reject/rejoin/approve, ICE có TURN, shared practice, ready/advance, đồng bộ câu, leave | REST/ICE không chứng minh TURN relay hoặc cuộc gọi WebRTC qua hai mạng thật |
| WebSocket | Hai user kết nối WSS, ping/pong, peer draft relay và persist, host reconnect/pong | Không thử microphone/camera hoặc chất lượng media |
| Audio | Bundled `/audio/` và local-upload `/media/`, mỗi loại 5 Range GET 64 KiB nhận 206 | S3 bật nhưng không tìm thấy preview S3 trong catalog public A1–B2; **chưa đo S3 object/redirect/CORS hoặc upload** |
| Support/admin | Tạo/đọc ticket của chính test user; USER bị 403 trên 5 route admin | Không có phiên admin được xác minh: chưa đo admin thành công, content upload/edit/publish/delete, role/state/revoke/manual grading/xóa tài khoản |
| Infrastructure | Quan sát JVM/Hikari/PG/CPU/RAM, final health, giữ nguyên artifact/config | RabbitMQ queues tắt; không có số đo broker. Không fault injection/restart/rollback ở lượt này |

## Trình duyệt và đường mạng

Chuyển trang SPA: bắt thời điểm click thật, chờ API đo bằng Resource Timing, nội dung view xuất hiện
và frame kế tiếp. Mỗi trang **n=1**, không phải INP hay đo mọi pixel hoàn tất.

| Trang | Click → nội dung |
|---|---:|
| History | 412.8 ms |
| Dashboard | 545.7 ms |
| Listening catalog | 390.7 ms |
| Conversation catalog | 385.7 ms |
| Support | 361.0 ms |
| Account | 404.2 ms |
| Rooms lobby | 376.3 ms |

Không ghi nhận JavaScript page error trong phiên trình duyệt này. Không tạo thêm bài AI từ giao diện.

Đối chiếu cùng loại endpoint đọc, n=5/điểm, hai tài khoản synthetic khác nhau:

| API | VPS loopback | Windows HTTPS |
|---|---:|---:|
| Catalog levels | 84.01 ms | 330.27 ms |
| Auth me | 112.68 ms | 360.79 ms |
| Dashboard | 176.48 ms | 420.12 ms |
| History list | 98.59 ms | 357.09 ms |
| Listening units | 100.17 ms | 354.11 ms |
| Error bank | 85.33 ms | 340.25 ms |

Chênh lệch khoảng 244–259 ms và WebSocket ping ~261 ms phù hợp với chi phí đường mạng đáng kể.
Đây là đối chiếu tham khảo, không tách chính xác từng chặng Redis/Neon/Caddy; tài khoản và thời điểm khác nhau.

## Tài nguyên và ưu tiên tiếp theo

130 snapshot trong hai cửa sổ 600 s + 60 s, có khoảng trống giữa chúng; loại CPU snapshot đầu mỗi cửa sổ:

- CPU trung bình 3.45%, cao nhất 22.81%; RAM available thấp nhất 104.86 MiB.
- JVM RSS cao nhất 489.54 MiB; heap used cao nhất 118.27 / max 371.25 MiB.
- Hikari active cao nhất 1, pending cao nhất 0 trong các snapshot; không chứng minh không có spike giữa mẫu.
- PostgreSQL lock waiter cao nhất 0; deadlock counter không tăng. Rollback counter tăng 2.
  Có snapshot idle-in-transaction = 1, không có tuổi transaction để kết luận bị kẹt.
- Provider counters tăng: ShopAIKey success 6/error 1, Cartesia success 1. Đây là số liệu toàn ứng dụng
  trong cửa sổ quan sát; success của client JSON **không đảm bảo** validation nghiệp vụ/Coach thành công.
- PID giữ 82447, automatic restart counter giữ 0; health cuối UP. Không thay JAR, app.env hoặc Caddyfile,
  đã đối chiếu SHA-256 cuối với baseline.

Ưu tiên:

1. **P1: AI grade timeout và thông báo/fallback.** `ShopAiKeyClient` có deadline 60 s cho grading;
   `ConversationService.answer` lưu pending trước I/O, giải phóng lease khi lỗi. Thời gian 60.386 s phù hợp
   deadline này, nhưng chưa xác định nguyên nhân phía provider. Đã chứng minh giữ 1 pending answer sau lỗi
   và trả 409 cho câu mới nộp chồng; không chứng minh exactly-once provider charge hay retry recovery thành công.
   Bổ sung reason-code/step latency không chứa prompt và test provider timeout/invalid response trước khi sửa.
2. **P1: Coach UNAVAILABLE và clarify 503.** `CoachService`/`ConversationService.help` bắt cả lỗi provider
   lẫn lỗi validation; dữ liệu hiện có chưa phân biệt được nguyên nhân của từng request. Không suy diễn
   API key hỏng hoặc provider sập. Kiểm provider latency/validation với mock có kiểm soát, giữ fallback an toàn.
3. **P2: Kết quả dictation/Coach đọc chậm.** Body kết quả 44,482 byte, mean headers ~1.06 s, total ~1.57 s;
   hoàn thành câu cuối mất 2.903 s. `HistoryService.buildResult` load toàn bài và gọi assessment;
   `SentenceAttemptRepository` đường detail chưa fetch sentence như query batch khác, DTO loop truy cập từng sentence.
   Đây là **ứng viên query amplification**, chưa có runtime SQL count nên chưa kết luận N+1.
   Đo Hibernate statement count ở test rồi cân nhắc fetch/projection có mục tiêu; không bật EAGER/OSIV toàn hệ thống.
4. **P2: Đường mạng/payload.** Tránh nhiều request nối tiếp không cần thiết và cân nhắc payload audio/result;
   không kết luận nâng RAM sẽ sửa độ trễ AI. Không đổi region/cloud/config trong lượt đo này.

## Bằng chứng và kiểm tra kịch bản

Thư mục: `target/function-latency-20261011/`.

- `requests.jsonl`: toàn bộ probe chính, status, milliseconds, request ID và assertion.
- `analysis.json`, `operation-table.md`, `summary.log`: tổng hợp từ dữ liệu thực, giữ cả fail.
- `readonly-local.jsonl`, `readonly-public.jsonl`: 68 request đối chiếu riêng.
- `ui-timings.json`, `browser-errors.log`: đo click của 7 trang, page errors.
- `resources.jsonl`, `resources-final.jsonl`: JVM/Hikari/PG/system snapshots.
- `runtime-flags.json`, `final-check.log`: trạng thái triển khai thực và hash/health.
- `probe.py`, `readonly-comparison.py`, `ui-observer.js`, `summarize.py`: kịch bản đo;
  Python compile và self-test của probe/summarizer đều PASS.
- Hai sai sót kịch bản đầu được giữ nguyên: giả định audio A1 là S3 (thực tế bundled);
  gọi complete trước ready ở phòng đôi. Kịch bản đúng đã chạy lại thành công, không sửa ứng dụng.
- Credential/runtime cookie chỉ lưu trong thư mục private đã hạn chế ACL, không có trong báo cáo.

Áp dụng Ponytail để không mở rộng sang sửa/deploy; Agent Browser để đo click thật;
Logging Patterns cho request ID; JPA Query Reviewer để tách nghi vấn query khỏi bằng chứng;
Vietnamese Encoding Guard cho báo cáo UTF-8. Không gọi các kiểm tra chưa chạy là đạt.
