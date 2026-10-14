# Chẩn đoán dictation chậm với một người dùng — 2026-10-11

## Kết luận

Đã tái hiện một nguyên nhân chính trên production: cookie CSRF bị xóa sau request xác thực thành công. Lần gửi đáp án tiếp theo nhận `403 / X-CSRF-Error: 1`; frontend lấy CSRF mới rồi gửi lại. Một lần trả lời cần **ba HTTP request tuần tự**, mất trung bình **1007 ms** qua HTTPS từ máy Windows (tức khoảng 1,01 giây), thay vì khoảng **496 ms** cho riêng POST thành công.

Đây không phải bằng chứng VPS thiếu RAM khi chỉ có một người dùng. Trong mẫu quan sát, không có hàng chờ Hikari, timeout pool hoặc deadlock. Không gọi AI, S3 hoặc RabbitMQ trong đường kiểm tra từ này.

## Đo thực tế

Hai lượt chạy tuần tự, mỗi lượt một tài khoản synthetic có sẵn từ campaign k6; không dùng tài khoản thật. Gửi đáp án sai, gửi lại một lần sai có chủ đích, rồi gửi đáp án đúng bằng API bình thường. Không retry kết quả mạng không chắc chắn; chỉ recovery khi security trả rõ CSRF rejection, giống `app.js`.

| Phép đo | Số mẫu | Trung bình | p95 mẫu |
|---|---:|---:|---:|
| HTTPS: toàn bộ một lần trả lời, 403 + CSRF GET + POST 200 | 24 | 1007 ms | 1025 ms |
| HTTPS: riêng POST thành công | 24 | 496 ms | 514 ms |
| HTTPS: riêng POST bị CSRF từ chối | 24 | 256 ms | 258 ms |
| Nội bộ VPS: toàn bộ một lần trả lời | 12 | 231 ms | 246 ms |
| Nội bộ VPS: riêng POST thành công | 12 | 229 ms | 243 ms |
| VPS → Neon: SELECT 1 trên kết nối psql giữ mở | 20 | 14,0 ms | Không tính |

Percentile chỉ mô tả mẫu nhỏ, không phải SLO dài hạn.

36/36 lần trả lời đo được đều có chuỗi CSRF recovery và POST cuối cùng xóa cookie `XSRF-TOKEN`. Các assertion đáp án đúng/sai và `userInput` qua được. Cả hai tài khoản logout HTTP 204 sau phép đo.

Trong lượt HTTPS, hai request recovery thừa chiếm trung bình khoảng **511 ms/lần trả lời**. Chênh lệch control CSRF GET giữa Windows và localhost khoảng 254 ms cho thấy chi phí mạng mỗi vòng HTTP rất lớn; đây không phải phép đo ICMP RTT. Hai lượt dùng tài khoản/câu khác nhau nên không diễn giải chênh lệch POST là latency mạng chính xác từng request.

Frontend còn có debounce **240 ms** khi nhập đủ chiều dài từ. Cộng số đo API với debounce cho ước lượng khoảng **1,25 giây** trước khi xét hàng chờ và render; chưa đo input-to-paint trong trình duyệt. Enter/Space không luôn chịu debounce này. Nếu nhập nhiều từ nhanh, queue tuần tự có thể cộng thêm thời gian chờ.

## Bằng chứng và cơ chế

- `src/main/resources/static/js/app.js:1576`: chờ 240 ms trước auto-check.
- `app.js:1664`, `app.js:3337`: check dùng queue chung cho toàn bộ mutation luyện tập; thao tác sau chờ request trước, bao gồm cả recovery.
- `app.js:3245`: khi `403` có `X-CSRF-Error=1`, GET CSRF rồi gửi lại POST. Recovery hợp lệ nhưng đang xảy ra trên từng đáp án, không chỉ khi phiên hết hạn.
- `src/main/java/com/dictalab/config/SecurityConfig.java:67`: `NullSecurityContextRepository`; `:83`: cấu hình STATELESS; `:106`: CSRF cookie vẫn bật.
- Đã kiểm tra bytecode `SecurityConfig` trong JAR đang chạy và xác nhận các cấu hình liên quan trên; JAR chứa Spring Security **7.1.1**. Hash JS triển khai khớp source: `c3611116531d4b87856c7f11ba40a878e5a1282242ed3716f26955c3e476f677`.
- Đối chiếu nguồn Spring Security 7.1.1: `sessionCreationPolicy(...)` kích hoạt implicit session strategy; `SessionManagementFilter` gọi strategy khi repository không chứa context. Với repository null và JWT xác thực lại mỗi request, `CsrfAuthenticationStrategy` xóa token. Chuỗi này giải thích `Set-Cookie` xóa CSRF quan sát được. Nguồn chính thức: [SessionManagementConfigurer](https://github.com/spring-projects/spring-security/blob/7.1.1/config/src/main/java/org/springframework/security/config/annotation/web/configurers/SessionManagementConfigurer.java), [SessionManagementFilter](https://github.com/spring-projects/spring-security/blob/7.1.1/web/src/main/java/org/springframework/security/web/session/SessionManagementFilter.java), [CsrfAuthenticationStrategy](https://github.com/spring-projects/spring-security/blob/7.1.1/web/src/main/java/org/springframework/security/web/csrf/CsrfAuthenticationStrategy.java).

Nguồn còn cho thấy chi phí backend cần tối ưu sau:

- `JwtTokenService.java:107/167` và `AccountStateFilter.java:25` đều lookup tài khoản. Đây là hai lời gọi repository; chưa đo chính xác tổng SQL production trên từng request.
- `PracticeService.java:219/488/530` lấy proper-name metadata ở cả check và dựng response; `:501` đếm số câu mỗi lần trả lời; còn lookup session, current sentence, attempt, word, toàn bộ word attempts và ghi event. Mỗi vòng DB chịu độ trễ kết nối Neon; chưa bật SQL tracing để đếm statement.
- `PracticeService.java:212/548` giữ transaction và khóa aggregate để bảo đảm nhất quán. Không đề xuất bỏ transaction/lock cho nhanh.

## Tài nguyên và giới hạn phép đo

9 mẫu tài nguyên có khoảng đo CPU hợp lệ, chu kỳ khoảng 5 giây; CPU toàn VPS tối đa 11,73%, CPU app tối đa 8,54%; available RAM thấp nhất 163,7 MiB; JVM heap tối đa 115,8/371,25 MiB. Hikari active tối đa 1, pending 0, acquisition trung bình 3,26 ms, không tăng timeout. GC pause cộng dồn trong khoảng mẫu 5 ms. Timer server của các POST `/checks` HTTP 200 ghi 26 request trong ranh giới mẫu, trung bình 238,9 ms; không coi đây là trace riêng của đủ 36 request.

Loại mẫu CPU đầu tiên vì sampler chưa có khoảng thời gian trước đó; giá trị 100% của mẫu đó không dùng để kết luận saturation. Mẫu PostgreSQL đầu có một idle-in-transaction thoáng qua; các mẫu sau và probe cuối đều 0, lock waiter 0, không tăng deadlock. Không chứng minh không bao giờ có spike giữa các mẫu.

Redis PING TLS cuối phép đo: 1,54 ms, eviction 0; chỉ là một probe, không phải percentile Redis. Service vẫn PID 76941, NRestarts 0, health UP.

Lượt local thu được 12 mẫu hợp lệ nhưng **không đạt assertion số lượng tối thiểu 15** của harness vì các câu synthetic đã có word reveal từ campaign cũ. Runner thoát 1 và dừng sampler sớm; không báo toàn bộ runner PASS hoặc đủ 120 giây. Không tự lặp lại write để che lỗi harness. Phân tích độc lập xác nhận đầy đủ 12 chuỗi CSRF và logout; lượt HTTPS có 24 mẫu, đạt floor. Không chạy browser automation, audio hoặc test cold-start Neon trong lượt này.

## Điểm cần sửa trong cách đọc kết quả k6 trước

`scripts/load/shared.js:94` trở đi khôi phục cookies từ snapshot trước mỗi request. Snapshot có CSRF cookie; cookie bị server xóa lại được harness đưa vào ở request sau. Vì vậy campaign trước đo API với cookie được khôi phục, chưa mô phỏng đúng lifecycle cookie của browser. Số đo cũ không bị thay đổi nhưng **0% lỗi và latency cũ không chứng minh trải nghiệm browser không có CSRF recovery**. K6 cần giữ cookie jar hiện tại và cập nhật CSRF theo flow thực tế trước campaign tiếp theo.

## Thứ tự sửa đề xuất — chưa triển khai

1. **P1: sửa lifecycle CSRF cho JWT stateless.** Không chạy rotation/xóa token như một lần login mới trên mỗi request; duy trì rotation thích hợp khi thay đổi phiên đăng nhập và logout. Không tắt CSRF, không bỏ kiểm tra JWT/Redis/account state. Kiểm thử lựa chọn cấu hình với đúng Spring Security 7.1.1, không áp dụng mẹo cấu hình từ version khác.
   - Nghiệm thu: cookie jar thật xử lý `Set-Cookie`; GET xác thực và ít nhất 20 POST đáp án liên tiếp không tự làm CSRF mất hiệu lực; mỗi answer bình thường chỉ một POST. CSRF thiếu/sai vẫn 403 và không ghi dữ liệu; refresh, logout, revoke, nhiều tab và permission-denial vẫn đúng.
2. **P1: chỉnh harness k6 theo cookie lifecycle thực tế rồi đo lại một người dùng.** Giữ chống retry mù, tài khoản riêng và cleanup; báo cả thời gian logical action, không chỉ POST thành công. So sánh trước/sau cùng workload và mạng. Chưa cam kết latency sau sửa; POST 200 hiện tại khoảng 496 ms qua HTTPS là baseline tham chiếu.
3. **P2: giảm query lặp trong một request.** Tái sử dụng account/metadata đã đọc trong phạm vi request hoặc transaction, tránh cache phân tán mới nếu chưa cần; giữ enabled/authVersion/role checks và locking. Thêm query-count test và concurrency/rollback regression trước rollout.
4. **P2: đánh giá UX queue/debounce sau khi API ổn.** Đo input-to-paint thực tế và thời gian queue trước; không bỏ tuần tự hóa write khi chưa bảo đảm thứ tự và chống stale response. Không tăng RAM/pool chỉ dựa vào triệu chứng này.

## Evidence và trạng thái thay đổi

Evidence ở `target/single-user-diagnosis/`: `probe.py`, `run.ps1`, `public.jsonl` (attempt đầu dừng ở CSRF 403 và logout), `public-v2.jsonl`, `local-v2.jsonl`, `server-v2.jsonl`, `analysis.json`, `summarize.cjs`, `neon-timing.py`, `final-check.txt`. Credential chỉ truyền stdin từ fixture bảo vệ, không nằm trong evidence/output/argv.

Python diagnostic compile được; summarizer có assertions tái chạy bằng `node target/single-user-diagnosis/summarize.cjs`. Chạy lại 11 test JS auth hiện có: 11/11 PASS. Các test hiện tại kiểm tra recovery nhưng chưa giữ cookie jar xuyên nhiều request để phát hiện loop này.

Chỉ thêm script chẩn đoán tạm và báo cáo; không sửa application code, JAR, cấu hình, không restart hay xóa dữ liệu. Có các lượt trả lời synthetic được giữ lại; không cleanup bằng DELETE. Hash app.env/JAR/Caddy cuối phép đo khớp baseline campaign trước. Logging Patterns dùng để đối chiếu request ID và dữ liệu quan sát; JPA Query Reviewer giúp phân biệt query lặp từ source với SQL count chưa đo. Vietnamese Encoding Guard kiểm tra UTF-8 báo cáo.
