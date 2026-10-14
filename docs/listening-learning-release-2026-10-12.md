# DictaLab — nghiệm thu ba chức năng học nghe

Ngày: 12/10/2026. Production: https://184.194.89.90. Máy hiện tại vẫn **1 GB RAM / 2 vCPU**.

## Đã triển khai và bật

1. **Personal Error Bank:** lưu lần trả lời đầu, bằng chứng câu/audio và tổng hợp lỗi cá nhân. Phân biệt quan sát văn bản với nguyên nhân nghe; không khẳng định weak form/contraction khi thiếu bằng chứng. Không coi câu chưa trả lời là bằng chứng không biết từ. Không backfill bài làm lịch sử.
2. **Spaced Listening Review:** lịch ôn 1/3/7/14/30 ngày; sai hoặc xem đáp án quay về ôn sau 10 phút. Ưu tiên audio khác, cùng CEFR, nội dung public/active. Audio cũ được ghi rõ và không dùng để tăng mức lịch ôn. Phải phát audio trước khi trả lời. Lịch ôn không phải chứng nhận năng lực.
3. **Reliable Attempt Processing:** receipt và kết quả bài làm cùng transaction; khóa session; Idempotency-Key trả lại kết quả đã lưu, đổi payload trả 409. Hoàn thành câu có chặn trùng tự nhiên. Outbox PostgreSQL, RabbitMQ durable/persistent, publisher confirms/returns, lease/fencing, projection transaction, ACK sau kết quả hoặc trạng thái retry bền vững. Retry có backoff/jitter, giới hạn 5 lần; FAILED/DLQ và công cụ phục hồi admin.

Giữ modular monolith, RabbitMQ; không Kafka, không thêm AI provider call. Giao diện tái sử dụng Coach/Kho lỗi; có khôi phục lượt ôn khi mất response hoặc reload.

## Release và khả năng quay lui

- JAR production: `/opt/dictalab/app/dictalab.jar`.
- SHA256 hiện tại sau sửa deadlock: `0153f361fa7fd8f003c78c5c75dc73744820db31f6d6bb2242c6af35032a115e`.
- SHA bản ban đầu đã test 50/75/100 VU: `1e5412f5fbe51597b80e877ea92f0a773c6040ba6bd54a7ab3625153bc919246`.
- Flyway V29 đã áp dụng. Hai cờ `DICTALAB_LEARNING_ENABLED` và `DICTALAB_LEARNING_QUEUE_ENABLED` đã bật.
- Backup JAR/env: `/var/lib/dictalab/backups/release-4qwc1tyn`.
- Backup DB/media/JAR khi đã dừng ghi: `/var/lib/dictalab/backups/cloud-if5l3wt7`.
- Backup env trước bật worker: `/var/lib/dictalab/backups/learning-env-sfn3t47k`.
- Log release: `logs/learning-production-release.log`, `logs/learning-production-enable.log`.
- Release sửa deadlock: `logs/learning-lock-production-release.log`; backup JAR/env `/var/lib/dictalab/backups/release-q609_tew`, backup quiescent DB/media/JAR `/var/lib/dictalab/backups/cloud-7fjm6ji2`. Không migration mới, không chỉnh connection pool/cờ tính năng. Đã xác nhận SHA production khớp và health UP.
- RabbitMQ chỉ nghe loopback, một consumer/prefetch 1; queue có giới hạn. Sửa quyền publish default exchange thành `amq.default`, xác nhận publish/projection thực tế. Broker đơn máy không phải HA; PostgreSQL outbox là nguồn phục hồi.

Nếu cần ngừng tính năng, dùng `learning-broker.py --disable` rồi restart app và kiểm tra readiness. Không xóa V29, bảng học hoặc outbox. Quay lại JAR cũ chỉ sau kiểm tra tương thích schema; không restore toàn bộ DB để quay lui code vì có thể mất bài làm mới. Khi FAILED: xem nguyên nhân, khắc phục rồi gọi `POST /api/admin/learning/jobs/{id}/retry` với admin + CSRF. Không reset DONE hay retry AI side effect.

## Bằng chứng kiểm thử

### Sửa deadlock phát hiện trong nghiệm thu

Sau run 50 VU, kiểm tra cả grace phát hiện thêm 1 deadlock. `startReview` khóa nhiều `lessons` bằng `FOR UPDATE` theo thứ tự due-card của từng user, nên hai user có thể giữ A/chờ B và giữ B/chờ A. Regression test gọi service thật với hai transaction độc lập tái hiện `ERROR: deadlock detected`, relation `lessons`: `logs/learning-lock-before.log` (1 test, 1 error trước sửa).

Sửa nhỏ trong `ListeningLearning`: PostgreSQL dùng `FOR SHARE` để nhiều người đọc publication cùng lúc; vẫn chặn UPDATE/DELETE nội dung đến commit, vẫn khóa độc quyền user/card và kiểm tra active/origin/transcript. Không thêm retry hoặc nới validation. H2 giữ cú pháp khóa cũ để không làm hỏng local compatibility. [PostgreSQL row-level locking](https://www.postgresql.org/docs/current/explicit-locking.html#LOCKING-ROWS) mô tả các chế độ xung đột này.

Test đối nghịch thứ tự đã pass sau sửa; test bảo vệ publication xác nhận writer nhận SQLSTATE `55P03` khi reader chưa commit. Lần chạy đầu assertion sai loại Spring exception (`UncategorizedSQLException` thay vì `PessimisticLockingFailureException`); đã đổi sang kiểm tra SQLException/SQLSTATE thật, không bỏ assertion chặn sửa nội dung. Hai test khóa PostgreSQL được skip có chủ đích trên H2, bắt buộc chạy không skip trong profile reliability. Full suite sau sửa đã pass; số tải dưới đây được tách rõ theo JAR trước và sau sửa.

Bộ tổng hợp load bổ sung error rate toàn run và deadlock/rollback delta toàn khoảng sampler (bao gồm grace có dữ liệu). Có regression test để không kết luận 0 lỗi từ riêng cửa sổ 120 giây; counter reset được coi là không xác định, không coi là 0.

| Kiểm chứng | Kết quả |
| --- | --- |
| Maven package mặc định sau sửa | 325 test chạy pass; 66 test infrastructure/PG skip (391 tổng) |
| Profile reliability sau sửa, PostgreSQL 18.6 + Redis + RabbitMQ Testcontainers | 247/247 pass, không skip |
| Gate reliability bắt buộc | 211 trường hợp pass; là tập con của 247, không cộng hai số |
| JavaScript sau sửa | 156/156 pass |
| Production smoke, HTTPS/TLS thật | Pass; 0 AI calls |
| Concurrent duplicate | 10 request đồng thời; receipt/evidence/card/review không nhân đôi trong integration tests |
| Rollback và recovery | Projection rollback, duplicate/lost ACK, backoff, hết retry, phục hồi FAILED, expired lease được test |
| Broker restart | SIGKILL/restart RabbitMQ container thật, message persistent xác nhận trước crash vẫn nhận/project được |
| Ownership/authorization | Production smoke: unauth 401, tài khoản khác 404, non-admin 403; CSRF/validation có integration tests |

Lệnh tái chạy:

```powershell
mvn -q '-Ddictalab.build.directory=target-learning-final-release' package
mvn -q '-Preliability' '-Ddictalab.build.directory=target-learning-final-release-pg' '-Ddictalab.testcontainers.postgres-image=postgres:18.6-alpine' test
node scripts/verify-reliability-tests.cjs target-learning-final-release-pg/surefire-reports
node --test src/test/js/*.test.cjs
pwsh -NoProfile -File scripts/verify-learning-production.ps1
```

Bằng chứng: `logs/learning-package-final.log`, `logs/learning-reliability-final.log`, `logs/learning-js-final.log`, `target/production-load-20261011/learning-smoke.json`.

Sau sửa: `logs/learning-lock-package-verified.log`, `logs/learning-lock-reliability-final.log`, `logs/learning-js-lock-final.log`; reports trong `target-learning-lock-release/surefire-reports` và `target-learning-lock-fixed/surefire-reports`. SHA JAR mới trước deploy: `0153f361fa7fd8f003c78c5c75dc73744820db31f6d6bb2242c6af35032a115e`.

Production smoke xác nhận replay/409, completion khác key vẫn cùng kết quả, outbox thành evidence, audio GET 263163 bytes, bắt buộc played, trả lời/replay/GET recovery, ownership và admin authorization. Smoke dùng audio cũ (`newContext=false`); không coi đó là kiểm chứng chuyển giao ngữ cảnh production. Ngữ cảnh mới đã có integration test.

Smoke sau deploy sửa deadlock: `target/production-load-20261011/learning-lock-smoke.json`, `logs/learning-lock-production-smoke.log`, **passed=true**, `newContext=true`, 0 AI calls. Như vậy đã có một challenge production ngữ cảnh mới đi hết played/answer/replay/recovery/ownership; không suy ra toàn bộ target đều có ngữ cảnh mới.

## Load test production

### Test lại sau sửa deadlock (JAR `0153f361…`)

| VU / cửa sổ | RPS API | HTTP p95 / p99 | HTTP/business lỗi toàn run | Deadlock mới toàn sampler | Audio ready p95 / p99 | SLO |
| --- | --- | --- | --- | --- | --- | --- |
| 50 / 120 giây | 44.775 | 875 / 1392 ms | 0% / 0% | 0 | 3893 / 6963 ms | Không đạt route catalog/start/resume/play và audio |
| 100 / 120 giây | 68.708 | 1370 / 1995 ms | 0% / 0% | 0 | 5377 / 8649 ms | Không đạt HTTP p95, nhiều route và audio |

50 VU sau sửa: pool timeout 0, không OOM/restart trong samples; CPU trung bình 52.0%, tối đa 81.4%, steal tối đa 24.3%; available RAM thấp nhất 146.6 MiB, JVM RSS tối đa 451.1 MiB, swap 502.9 MiB; pool pending tối đa 30, acquire trung bình 9.35 ms. Outbox pending tối đa 125, FAILED 0; invariants sau run đều 0 vi phạm, còn 109 jobs đang drain tại lần kiểm tra đầu. Không dùng biến động RPS/latency này để tuyên bố sửa khóa đã tăng throughput: workload/data, warmup, network và generator có khác biệt.

Evidence: `target/production-load-20261011/learning-lock-50.analysis.json`, `.manifest.json`, `.raw.json.gz`, `.server.jsonl`, `.summary.json`; `logs/learning-lock-load50.log`. 75 VU chưa chạy lại trên JAR sau sửa.

50 VU đã drain trước stage 100: 1645 jobs, pending/failed 0; invariant đều 0 vi phạm. Bằng chứng `target/production-load-20261011/learning-lock-invariants-before100.json`.

100 VU sau sửa: 8245 API requests trong cửa sổ, 491/491 journeys hoàn tất cả grace; không có HTTP/business/audio validation error toàn run. Pool 10, pending tối đa 32, acquire trung bình 122.8 ms, usage 65.2 ms; DB timeout 0, deadlock/rollback delta toàn sampler 0. Auth lookup PostgreSQL trung bình 156.7 ms, Redis 2.74 ms; catalog summary/preview khoảng 15.4 ms. CPU trung bình 41.0%, tối đa 57.2%; iowait tối đa 43.7%, steal 21.5%; RAM available thấp nhất 184.6 MiB, JVM RSS 403.1 MiB, heap dùng 158.5 MiB, swap 545.4 MiB, 16675 major page faults trong ranh giới sample. Không OOM/restart trong samples. Generator tối đa 67% CPU, k6 RSS 328.2 MiB, RAM available thấp nhất 10532 MiB; stage này ít nhiễu CPU generator hơn các stage trước nhưng không suy ra mạng không phải bottleneck.

Outbox pending tối đa 314, FAILED 0. Sau run có 217 jobs đang drain; các invariant SQL đều 0 vi phạm. Bằng chứng `target/production-load-20261011/learning-lock-100.analysis.json` và các file cùng prefix; `learning-lock-invariants-after100.json`. Theo dõi drain cuối trong `learning-lock-drain.json`.

**Drain cuối hoàn tất lúc 20:35:52 ngày 12/10/2026 (UTC+7):** campaign tích lũy 2136 jobs, pending 0, FAILED 0; 12901 evidence, 8362 cards, 1793 reviews hoàn tất. Tất cả invariant SQL trong lần kiểm tra cuối đều 0 vi phạm; idle-in-transaction và lock waiters tại thời điểm đó đều 0. Bằng chứng: `target/production-load-20261011/learning-lock-drain.json`. Đây là số campaign tích lũy, không phải số sinh riêng trong cửa sổ 100 VU/120 giây.

Kiểm tra production lúc 20:38:16 (UTC+7): health UP, app active, PID 118777, NRestarts 0; RabbitMQ queue/DLQ đều 0 ready/unacknowledged, consumer 1, không local alarm. SHA được kiểm tra lại bằng sudo và khớp release hiện tại; lệnh đọc SHA không sudo trước đó bị Permission denied, không phải thay đổi artifact. Snapshot health/SHA/broker trước đó lưu trong `logs/learning-production-final-health.log`; kiểm tra lại sau drain có trong output phiên nghiệm thu.

**Trạng thái nghiệm thu:** ba chức năng đã deploy/bật và vượt qua các kiểm thử chức năng, concurrency/recovery cùng invariant đã nêu. Nghiệm thu capacity chưa đạt: latency SLO thất bại ở các stage mở rộng, mục tiêu 100 VU/30 phút chưa được kiểm chứng. Không đánh đồng job drain thành công hoặc health UP với đạt SLO tải.

Kết luận tải: đã chạy đến **100 VU/120 giây**, không đạt SLO latency. Không có stage 50/75/100 nào đạt toàn bộ SLO của workload mở rộng mới; không có bằng chứng cho mục tiêu 100 VU/30 phút. Bản sửa deadlock được kiểm chứng về concurrency và tính nhất quán, không phải bản tối ưu throughput tổng thể.

### Ba stage trước sửa (JAR `1e5412…`)

Generator chạy trên máy Windows riêng VPS. HTTPS có kiểm tra TLS. Mỗi VU luyện chính tả, GET audio thật, cache 8 file/VU, chờ thời lượng đoạn audio, think 2 giây; kèm duplicate submissions, Error Bank, review, duplicate answer và GET recovery. Không AI thật. Các lượt hoàn tất trong grace không được cộng vào RPS của cửa sổ 120 giây.

| VU / cửa sổ | RPS API | HTTP p95 / p99 | HTTP lỗi | Audio ready p95 / p99 | Nghiệm thu SLO |
| --- | --- | --- | --- | --- | --- |
| 50 / 120 giây | 47.025 | 778 / 1171 ms | 0% | 3025 / 5059 ms | Không đạt: catalog/start/resume p95 > 1000 ms; audio vượt ngân sách |
| 75 / 120 giây | 63.608 | 919 / 1668 ms | 0% | 4455 / 7654 ms | Không đạt: play/review-start p95 > 1000 ms; audio vượt ngân sách |
| 100 / 120 giây | 73.617 | 1198 / 1489 ms | 0% | 5003 / 7245 ms | Không đạt HTTP p95 và nhiều route/audio SLO |

50 VU: 5643 API requests trong cửa sổ; CPU trung bình 49.7%, tối đa 84.2%; available RAM thấp nhất 109.9 MiB; JVM RSS tối đa 426.3 MiB; heap dùng tối đa 149.8 MiB; swap tối đa 509.4 MiB. Pool 10, pending tối đa 24, DB connection timeout 0; không deadlock mới/OOM/restart trong samples. CPU steal tối đa 27.7%, có paging đáng kể. Redis probes không lỗi. RabbitMQ không alarm, một consumer, DLQ rỗng ở các lần lấy mẫu.

**Không phải 0 lỗi toàn bộ run 50 VU:** ở giây 124.57, ngoài cửa sổ 120 giây nhưng trong grace, `review-start` của VU 1 trả 409. Toàn run HTTP error rate 1/6662 = 0.0150%, business failure 1/6062 = 0.0165%, 357/358 journeys hoàn tất (99.72%). Đều dưới 1% nhưng chưa lý tưởng 0. Các sample grace PostgreSQL tăng deadlock 0 → 1 và rollback 27 → 28 cùng khoảng thời gian. Handler conflict không log loại exception nên không có stack trace riêng của response 409. Đã tái hiện và sửa đường khóa độc quyền nhiều lesson khác thứ tự bằng regression test; không tuyên bố có stack trace chứng minh riêng response 409 đó. Các invariant sau run đều đúng; không retry mù request này.

Outbox pending tối đa 204; worker DONE tăng 100 trong ranh giới sampler cửa sổ. Sau stage đã drain: 359 jobs, pending 0/failed 0, 2082 evidence, 1819 cards, 174 reviews hoàn thành trong campaign. Tất cả invariant được kiểm tra bằng SQL đều 0 vi phạm: duplicate active solo, word events, replay/reveal/duration, source completion, revision, played/result. Đây là campaign tích lũy, không phải toàn bộ số sinh riêng trong 120 giây.

75 VU: 7633 API requests trong cửa sổ, 452/452 journeys hoàn tất kể cả grace; CPU trung bình 36.3%, tối đa 54.3%, steal tối đa 30.3%; available RAM thấp nhất 192.7 MiB, JVM RSS 382.6 MiB, heap dùng 156.8 MiB, swap 519.6 MiB. Pool pending tối đa 33, acquire trung bình 45.9 ms, timeout 0; không deadlock mới/OOM/restart. Có 1 lock waiter trong một sample, không có rollback mới. Redis probes không lỗi, broker không alarm/FAILED. Pending đạt 292 và đang drain sau stage; SQL sau stage không vi phạm invariants. Khác biệt CPU giữa các stage không chứng minh tối ưu: cùng JAR, có warmup/steal và workload state khác nhau.

75 VU đã drain trước cửa sổ test 100 VU: 811 jobs, pending/failed 0; invariant 0 vi phạm. 100 VU: 8834 API requests trong cửa sổ, 531/531 journeys hoàn tất cả grace, 0 lỗi HTTP/business toàn run. CPU trung bình 35.7%, tối đa 55.0%; available RAM thấp nhất 174.6 MiB, JVM RSS 376.5 MiB, heap dùng 162.4 MiB, swap 538.0 MiB. Pool pending tối đa 31, acquire trung bình 123.8 ms, timeout 0; deadlock counter không tăng trong samples cả grace. Pending tối đa 331, FAILED 0. Chưa coi kết quả này là đạt SLO hoặc trần tải.

Sau toàn bộ ba stage: 1342 learning jobs trong campaign, pending/failed 0; 7989 evidence, 5908 cards, 999 reviews hoàn tất. Tất cả invariant SQL vẫn 0 vi phạm. Broker queue/DLQ rỗng, consumer 1, không alarm, health UP. Đây là số campaign tích lũy trước smoke sau deploy sửa deadlock.

Máy phát tải: CPU toàn máy trung bình 62.7% ở 50 VU, 83.7% ở 75 VU, tối đa 100% ở cả hai; k6 CPU tích lũy cuối stage lần lượt 15.6/40.9 giây. Máy có ứng dụng khác chạy. Không đóng ứng dụng người dùng. Kết quả là trải nghiệm end-to-end của generator này, không phải phép chứng minh throughput trần riêng của VPS; cần generator ít nhiễu để đo capacity chính xác hơn.

Bottleneck đã quan sát: chờ pool và chi phí đi về Neon; áp lực RAM/swap/CPU steal; dispatcher chỉ lấy tối đa 5 jobs mỗi 5 giây nên throughput phụ bị giới hạn khoảng 1 job/giây ngay cả khi broker trống. Chưa đo đủ để quy toàn bộ lỗi latency cho database hoặc khẳng định tăng pool/RAM chắc chắn giải quyết.

Ưu tiên tiếp theo về tải: đo với generator ít nhiễu; giảm thời gian giữ connection và số round-trip của review-start/auth, giữ bảo vệ freshness/authorization; đo throughput worker rồi điều chỉnh batch có giới hạn để không tranh tài nguyên với bài làm. Không tăng pool hoặc consumer đồng loạt trên VPS 1 GB chỉ vì backlog. Các bước này chưa được triển khai hoặc nghiệm thu trong bản hiện tại.

## Chrome và giới hạn nghiệm thu

- Kiểm tra Chrome bằng session cách ly `dictalab-learning`; không tuyên bố đã hiện popup Allow cho Chrome cá nhân. Công cụ computer-use trước đó báo thiếu token.
- Kho lỗi hiển thị trên mobile 375×812 và desktop 1440×900; không thấy horizontal overflow ở hai kích thước đã đo. Ảnh: `artifacts/learning-bank-mobile.png`, `artifacts/learning-bank-mobile-loaded.png`, `artifacts/learning-bank-desktop.png`.
- Audio thật decode thành buffer mono 48000 Hz, 1260450 frames, 26.259375 giây; samples kiểm tra hữu hạn. Không có JS error/console error trong lần kiểm tra. Đây không phải đánh giá âm thanh bằng tai.
- Luồng ôn thực tế trên Chrome: không phát audio thì bị nhắc trước submit; phát hết audio, trả lời sai được lưu và lên lịch sau 10 phút. Challenge trong lần kiểm tra này hiển thị **audio khác/ngữ cảnh mới**. Ảnh: `artifacts/learning-review-graded-desktop.png`, `artifacts/learning-review-graded-mobile.png`. Điều này bổ sung cho smoke ban đầu dùng audio cũ; chưa chứng minh hiệu quả học dài hạn hay mọi target đều có audio khác.
- Probe tự động `artifacts/learning-layout-probe` dùng context chưa login: đo màn login, **không** dùng làm bằng chứng nghiệm thu responsive cho Kho lỗi. Các cảnh báo contrast P1–P11 của probe là màn login ngoài phạm vi thay đổi này, cần xác nhận nền/gradient bằng audit riêng; không tự bỏ qua thành đạt.
- Chưa chứng minh 100 VU liên tục 30 phút. Chưa kill worker/broker production; worker lease-expiry được mô phỏng, broker crash được test trong container dùng riêng.
- Chưa có backfill lịch sử, đánh giá hiệu quả học dài hạn hoặc kiểm thử mọi AI provider failure. Legacy client không gửi Idempotency-Key không có đầy đủ bảo vệ retry transport.
- Source index: đã xác nhận 946/946 câu đủ điều kiện public/active/audio có index khớp transcript tại lần kiểm tra, 5219 tokens. Không suy ra toàn bộ catalog/private content đã index. Audio khác chỉ có khi đủ nội dung phù hợp. `health=UP` không chứng minh worker khỏe; phải kiểm tra backlog/FAILED/DLQ/consumer.
- Không xóa dữ liệu test, không nâng gói cloud, không chỉnh pool production trong đợt nghiệm thu này. Bảo toàn các thay đổi đã có trong worktree; chưa tạo commit.
