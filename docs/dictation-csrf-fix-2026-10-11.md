# Sửa dictation chậm — 2026-10-11

## Kết quả

Đã sửa và triển khai production `https://184.194.89.90`. Nguyên nhân đã tái hiện: mỗi request JWT có xác thực bị coi như một lần đăng nhập mới, xóa cookie CSRF. Đáp án tiếp theo cần POST 403 → GET CSRF → POST 200. Sau sửa, **48/48 đáp án** trong hai lượt một người dùng chỉ cần một POST 200, không xóa CSRF, không recovery. Các assertion đáp án đúng/sai và input đã lưu đều đạt.

| Phép đo một người dùng | Trước | Sau |
|---|---:|---:|
| HTTPS: số đáp án | 24 | 24 |
| HTTPS: trung bình toàn bộ lần trả lời | 1007 ms | 543 ms |
| HTTPS: p95 mẫu | 1025 ms | 592 ms |
| HTTPS: p99 mẫu | Xem evidence baseline | 623 ms |
| HTTP request mỗi đáp án | 3 | 1 |
| CSRF recovery của đáp án | 24/24 | 0/24 |
| Nội bộ VPS: số đáp án | 12 | 24 |
| Nội bộ VPS: trung bình lần trả lời | 231 ms | 257 ms |

HTTPS giảm khoảng **46,1%** trong mẫu quan sát. Không kết luận backend xử lý nhanh hơn: POST thành công vẫn có chi phí DB/Redis/mạng; mục tiêu bản sửa là bỏ hai request recovery thừa. Cùng loại workload đáp án sai → sai lặp có chủ đích → đúng, cùng đường mạng Windows/VPS; tài khoản và câu khác nhau vì dữ liệu luyện tập có trạng thái. Đây không phải A/B trên dataset bất biến hay SLO dài hạn.

## Thay đổi nhỏ, giữ bảo vệ

- `SecurityConfig.securityContextRepository()`: dùng `RequestAttributeSecurityContextRepository` thay repository null. Chỉ giữ context trong request; vẫn STATELESS, không tạo HTTP session và vẫn xác thực JWT/Redis/account state ở request mới.
- `AuthController`: xóa CSRF sau đăng ký/login thành công và logout; refresh giữ CSRF ổn định. Không tắt CSRF, không nới permission/rate limit, không sửa transaction/locking/API/schema.
- `scripts/load/shared.js`: cập nhật snapshot cookie sau mọi response, bao gồm deletion, thay vì đưa lại cookie CSRF đã bị server xóa. Recovery tối đa một lần, chỉ khi server đánh dấu rõ CSRF rejection trước controller; không replay network/503/permission failures. Đo thêm `business_action_duration` và `csrf_recoveries`.
- K6 xử lý tên header canonical và giá trị dạng mảng/chuỗi. Đối chiếu [Response headers của Grafana k6](https://grafana.com/docs/k6/latest/javascript-api/k6-http/response/). HTTP 403 vẫn được tính là HTTP failure, không sửa expected status để che lỗi.

## Kiểm chứng

1. **Red-before-green:** test cookie jar giữ/xóa cookie đúng theo response thất bại trên bản cũ khi GET catalog có xác thực làm mất CSRF. Log: `target/csrf-before-test-2.log`.
2. **AuthFlowIntegrationTest: 8/8 PASS.** Test mới kiểm tra GET và 20 POST đáp án liên tiếp với cookie/header thật, không dùng shortcut `csrf()`. CSRF thiếu/sai vẫn 403, dữ liệu không bị ghi; login/logout đổi CSRF, refresh giữ token và không tạo session.
3. **Maven đầy đủ:** 314 test được khai báo, **258 chạy PASS, 56 skipped**, không failure/error. Chạy cả build source và bộ test trên classes lấy từ đúng artifact production đã thay hai class. Không gọi 314 test là đã PASS. Docker daemon không hoạt động; các suite PostgreSQL/Redis/RabbitMQ có điều kiện chưa chạy lại trong lượt này.
4. **JavaScript: 138/138 PASS.** Có regression cookie deletion, snapshot sau recovery, thời gian toàn action, request ID riêng, refresh/host-guest isolation và giới hạn retry. Các test frontend nhiều tab/refresh/CSRF hiện có vẫn qua.
5. **Production cookie-jar probe:** HTTPS 24/24 và localhost 24/24 đáp án, không CSRF recovery; cả hai logout 204. Probe xác minh đúng/sai và input, không retry write có kết quả mạng không chắc chắn.
6. **Production auth lifecycle:** anonymous `/me` 401; login 200 xóa CSRF cũ; GET xác thực giữ CSRF; hai POST thiếu/sai CSRF 403; refresh 204 giữ CSRF; logout 204 xóa JWT/CSRF; `/me` 401. Không có JSESSIONID hoạt động. Các POST bị từ chối không tạo bài luyện.
7. **K6 smoke 5 VU / 30 giây**, generator Windows tách khỏi VPS, HTTPS xác minh TLS. Workload 4 reader VU + 1 dictation VU, think 2 giây/playback giả lập 3 giây; không gọi AI/S3 thật. Tổng 43 started = 43 finished = 43 completed, không interrupted; 150 business checks đều thành công, 160 HTTP request kể cả cleanup, không 403/recovery; 5/5 token family logout thành công. Mọi threshold hiện có đạt, exit 0.

K6 trong đúng cửa sổ work 30 giây: **132 request, 4,4 RPS, p95 520 ms, p99 691 ms, lỗi 0%**; 30 mẫu VU đều ghi 5. Toàn bộ phase work gồm graceful completion ngoài cửa sổ: p95 577 ms, p99 740 ms. Toàn bộ logical action: trung bình 451 ms, p95 664 ms, p99 1113 ms; chỉ số này bao gồm cả connection/overhead ngoài `http_req_duration`, không được trộn với percentile raw HTTP.

Trong cửa sổ k6 có 5 mẫu tài nguyên: CPU VPS tối đa 17,11%, app 15,41%, available RAM tối thiểu 161,6 MiB, JVM heap tối đa 117,4/371,25 MiB. Hikari active tối đa 3, pending 0, timeout tăng 0; GC pause tăng 16 ms. Hai mẫu PostgreSQL ghi lock waiter 0/deadlock tăng 0; idle-in-transaction tối đa 1 thoáng qua. Không chứng minh không có spike giữa các mẫu. RabbitMQ không chạy và hai queue flags false: **không có số đo broker, không tuyên bố đã test RabbitMQ**.

## Triển khai và rollback

VPS vẫn gói **1 GB RAM / 2 vCPU**; không nâng RAM hoặc thay cấu hình. Có một restart ứng dụng chủ động; không triển khai zero-downtime. Sau kiểm chứng: service active, health UP, PID 82447, NRestarts 0 (không có auto-restart; không có nghĩa là không restart chủ động).

Không deploy toàn bộ dirty workspace. Artifact hotfix giữ mọi entry của JAR production, chỉ thay `SecurityConfig.class` và `AuthController.class`. Dependencies, migrations, frontend, resource/config trong JAR đều không đổi; kiểm tra ZIP integrity, tập entry, nội dung từng entry và bytecode Java 17. Bộ test trên classes của hotfix cũng qua. Nén ZIP giữa Windows/Linux cho container hash khác, nhưng digest nội dung toàn bộ entry trùng nhau.

- JAR trước: `d9869daa9bde599120aa67932030458eb79ed680852ec45807952dcd2d2d495b`.
- JAR đang chạy sau: `d06b9fb3ad547d91083126230026ea219781f2169b2131fd9109cd9301c995a5`.
- Entry-content digest của candidate đã test và artifact VPS: `8a7d61585f76ae4e19623909679e7b9ce4a1471eaf9a8ac3c3de29412f2734e9`.
- Backup JAR: `/opt/dictalab/releases/csrf-20261011/dictalab.jar`.
- Backup database online trước restart: `/var/lib/dictalab/backups/cloud-gnn9pk9e`, pg_restore list kiểm tra được; SHA256 dump `fe551218f707c5f3ba06ec7302cf7e4bbf1996e269f9e5ff4cf3b03aa8add7f0`. Đây là DB-only snapshot, không gọi là snapshot quiescent DB/media.
- app.env/Caddy/systemd unit hash giữ nguyên. Release chạy bằng systemd one-shot, có rollback JAR nếu activation/health thất bại. **Không kích hoạt rollback trong lượt này, chưa diễn tập rollback mới.**

Không xóa dữ liệu hoặc chạy migration mới; chỉ thêm các lần luyện synthetic có chủ đích và đóng phiên của probe. Mật khẩu/token không có trong report, source hoặc argv. Credential và auth fixture giữ trong file private có ACL; không đính kèm chúng làm evidence.

## Giới hạn và phần còn lại

Lượt HTTPS sau đầu tiên có 12 đáp án hợp lệ nhưng runner fail do floor 15 của harness; logout thành công, không có lỗi nghiệp vụ/CSRF. Giữ evidence `public-after.jsonl`, không gọi lượt này PASS hoặc giả đủ sampler. Lượt v2 mở rộng tối đa 10 bài để thu **đúng 24** đáp án, không hạ floor hay lặp write không chắc chắn; dùng hai tài khoản synthetic khác. Các mẫu trước/sau và runner thất bại được giữ nguyên.

Chưa đo input-to-paint trong browser thật, audio/provider, cold-start Neon, sustained load, hoặc chạy lại 10→20→50→100 VU sau hotfix. Smoke 5 VU không xác lập năng lực tải tối đa và không xác nhận lại campaign cũ. Backend/Neon vẫn khoảng 257 ms tại localhost; đường mạng và debounce frontend 240 ms vẫn có thể làm thao tác cảm giác chậm. Giảm query lặp/đo query-count và đánh giá queue/debounce là bước tối ưu tiếp theo; không bỏ locking/tuần tự hóa write để lấy tốc độ.

Ponytail giữ scope ở hai class và tận dụng test/harness có sẵn. Spring Security/MVC Testing giúp kiểm tra cookie-CSRF thật thay vì bỏ filter; Testing Fundamentals dùng cho assertion/mock phù hợp Boot 4.1.1. Vietnamese Encoding Guard xác minh UTF-8 các file sửa và báo cáo.

## Evidence có thể chạy lại

- Baseline: `docs/dictation-single-user-diagnosis-2026-10-11.md`, `target/single-user-diagnosis/analysis.json`.
- Tests: `target/csrf-lifecycle-test.log`, `target/csrf-package-test.log`, `target/csrf-production-tests.log`, `target/csrf-js-final.log`.
- Release/probes: `target/csrf-production-20261011/` chứa script build/release, diff bytecode, `release-evidence.log`, `public-after-v2.jsonl`, `local-after-v2.jsonl`, `server-after-v2.jsonl`, `auth-lifecycle.jsonl`, `analysis-after.json`, `final-check.log`.
- K6: `target/production-load-20261011/csrf-fixed-5.{manifest,summary,analysis,result}.json`, raw JSON gzip và server/generator samples. Không chia sẻ `.private-auth.json` hay `private-fixture.json`.
- Phân tích không phát sinh request/write: `node target/csrf-production-20261011/summarize-after.cjs`; `node scripts/load/summarize-production.cjs target/production-load-20261011/csrf-fixed-5`.
- Test local: `mvn -B -Dtest=AuthFlowIntegrationTest test`; `node --test src/test/js/*.test.cjs`.
