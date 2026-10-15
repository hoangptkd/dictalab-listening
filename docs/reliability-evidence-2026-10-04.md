# DictaLab: bằng chứng load, concurrency và recovery — 04/10/2026

## Kết luận

Đã triển khai bộ kiểm thử tái hiện, chạy race/recovery trên PostgreSQL thật và Redis test riêng, smoke backend ngắn và restore SQL. **Chưa xác minh mục tiêu 300 VU/30–60 phút, soak ≥2 giờ, spike, stress hoặc phục hồi sau stress.** VPS bị guard hiện có từ chối; người dùng xác nhận không có máy chủ thử riêng. Không nới ngưỡng, không chạy tải vào production, không đóng ứng dụng khác.

Kế hoạch, lệnh tái hiện, guard và giới hạn workload: [runbook](reliability-evidence-runbook.md).

## Kết quả đã đo

| Kiểm tra | Kết quả | Phạm vi chứng minh |
| --- | --- | --- |
| Regression tập trung | 105/105, 0 lỗi, 0 skip | PostgreSQL thật, Redis test riêng; race/recovery/auth/provider/migration |
| Regression mặc định | 229 tổng, 0 lỗi, 31 skip | H2/unit và integration mặc định; không phải 229 bài PostgreSQL |
| Contract generator | 8/8 | Cookie isolation, phân phối cấu hình, phát hiện raw thiếu/gián đoạn |
| Smoke cục bộ | 20 VU, cấu hình 60 giây; 178/178 hành trình thành công | App/DB/generator cùng máy HP; không chứng nhận sức chịu tải VPS |
| Latency smoke | Work API p95 82,54 ms; p99 334,35 ms | Tổng hợp trong bài ngắn, fake provider; không ngoại suy lên 300 VU |
| Invariant/restore | 9 invariant không vi phạm; fingerprint mọi public table khớp | Database nguồn và DB restore mới; không bao gồm media/Redis/app restore |
| VPS preflight | Không đạt; không khởi chạy tải | 9 mẫu, RAM/paging không đạt guard |

Smoke có work window 100.222 ms, bao gồm hoàn thành hành trình đang chạy sau cửa sổ cấu hình 60 giây. Quan sát 94 đọc, 74 Dictation, 7 Listening, 3 Room: **52,81% / 41,57% / 3,93% / 1,69%**, không phải 60/30/10. Mỗi khối 20 vòng của từng VU được cấu hình 12/6/1/1; bài ngắn cắt khối và các hành trình có thời lượng khác nhau. Đây là smoke xác minh đủ bốn đường đi, không phải bài đạt workload mục tiêu. Tỷ lệ hành trình không phải tỷ lệ HTTP request.

Smoke dùng 40 account độc lập, think time 2–5 giây. Fake provider nhận 7 call trong budget 200; AI thật nhận 0 call. Cleanup gia đình refresh token qua. Cardinality sau chạy: 41 account gồm bootstrap, 320 practice, 11.074 sentence, 57.473 word, 57.170 event, 7 Listening. Fixture lịch sử là dữ liệu tổng hợp về cardinality, không đại diện phân bố năng lực/điểm thực tế.

## Concurrency và recovery

Ma trận dùng 2/5/10/20 thao tác, hai lần lặp hữu hạn mỗi cấu hình. Tổng 32 cấu hình tham số, 64 lượt scenario; CSV có 88 bản ghi do một số scenario gồm nhiều điểm tranh chấp. Không cộng các lượt chạy lại vào 105 kiểm thử cuối.

- Đăng ký: barrier sau đọc email, trước insert; kiểm tra uniqueness trên PostgreSQL.
- Start/resume, check/hint/complete, admin stale mutation, token consume: coordinator giữ đúng hàng tranh chấp; quan sát contender bằng `pg_stat_activity`, kiểm tra invariant sau commit.
- Grading claim và stale worker: giữ hàng `listening_passes` tới khi đủ contender CAS; provider latch và lease hết hạn có kiểm soát để kiểm tra fencing.
- RequestId: cạnh tranh N-way, một provider call/attempt; retry sau bỏ response trả kết quả cũ; đổi payload trả 409, sai owner trả 404.
- Room: barrier/khóa theo thiết kế in-memory; kiểm tra trạng thái và ownership. Không phải bài cross-node.
- Email outbox: tiến trình Java con bị kết thúc thật sau SMTP acceptance giả lập; restart trước lease expiry không gửi tiếp, sau expiry cho phép reclaim. SMTP at-least-once có thể gửi trùng trong cửa sổ acceptance/crash theo nghiệp vụ.
- Provider: HTTP timeout 30 giây thật, 429/Retry-After, 500, JSON sai/body quá lớn; permit được trả và client phục hồi, không retry ngầm.
- Migration: upgrade V22 → V25 trên PostgreSQL thật. Restore SQL tạo DB đích mới, không ghi đè nguồn; kiểm tra count/hash từng public table và invariant cả hai phía.

Các kết quả trên là chứng cứ cho scenario đã chạy, không phải chứng minh không thể có mọi loại race hoặc mất dữ liệu.

## Trước/sau và bottleneck

Ba smoke đầu không hoàn thành hành trình vì API nghiệp vụ trả 401. Redis chỉ còn gia đình token của account setup cuối. Nguyên nhân nằm trong **generator**, không phải kết luận bottleneck production: `clear()` của k6 không giữ Path gốc khi xóa cookie; refresh cookie cũ còn lại khiến lần login tiếp theo thu hồi gia đình trước.

Sửa tối thiểu tại helper dùng chung: expiry tường minh với đúng Path cho cookie access/refresh/CSRF/session; giữ cookie xoay theo account, không retry refresh mơ hồ; kiểm tra cookie sau setup và logout ở teardown. Regression contract qua, smoke tiếp theo hoàn thành 178/178. Latency của response 401 trước sửa không dùng làm baseline hiệu năng; **không có tuyên bố cải thiện capacity trước/sau**.

Các lỗi harness trước đó (spy cleanup của JPA và tên biến Java che tên package) cũng được lưu artifact, sửa rồi chạy lại hữu hạn. Không sửa production business logic để làm xanh race. Rate-limit/CSRF/JWT/ownership vẫn được giữ. Shared-IP auth refresh qua SSH có thể là giới hạn ở bài dài; chưa đo nên chỉ là rủi ro cần quan sát, không phải bottleneck đã xác nhận.

## Vì sao chưa chạy tải dài

Preflight lúc 16:42 ngày 04/10/2026, UTC+7: CPU trung bình 10,67%, peak 17%; RAM trống thấp nhất **1.577 MiB**, yêu cầu ≥1.800; paging peak **52.644/s**, yêu cầu ≤100; disk queue 2, disk trống 15,13 GiB. Thu được 9 mẫu, yêu cầu ≥10. Guard từ chối và ghi `LoadStarted=false`; không tự retry.

Kiểm tra chỉ đọc lúc 17:46:18 UTC+7 xác nhận DictaLab health `UP`; DictaLab, DictaLabPostgres, WebLensBackend, BilingualBackend đều `Running`, MoreLogin có 8 tiến trình. Đây là snapshot sức khỏe dịch vụ, không phải số đo latency của các ứng dụng khác. Không push/deploy hoặc shutdown máy trong nhiệm vụ này.

## Artifact và định danh

Đường dẫn dưới đây tương đối với repository. Giữ cả lượt thất bại; `target/` bị Git ignore nên cần sao lưu trước khi dọn workspace. Artifact gốc chỉ dùng nội bộ: k6 summary có `setup_data` chứa cookie/CSRF của account test; phải loại bỏ phần này trước khi chia sẻ. Không đưa token, password, dump dữ liệu hay URL credential lên GitHub/CV.

- JUnit cuối: `target/evidence-final-20261004/surefire-reports/`; race: `target/evidence-final-20261004/concurrency-matrix.csv`.
- Regression mặc định: `target/evidence-default-regression-20261004/surefire-reports/`.
- Smoke thành công: prefix `target/capacity/local-smoke-evidence-reads-20-20261004-172615`, gồm summary, raw gzip, manifest, result, accounting, generator CSV và Prometheus series.
- Runtime smoke: `target/evidence-smoke-80ee2fabc1bd46709efe9c675b67379a/`.
- Restore: prefix `target/capacity/db-dictalab_capacity_370bccbbe3564a109e6679c18c83bb37-20261004-172859`.
- Preflight: `target/capacity/evidence-20261004-preflight-20261004-164136.csv` và `.csv.result.json`.
- Lượt lỗi giữ lại: `target/evidence-20261004/`, các thư mục rerun/provider/recovery ngày 04/10; smoke prefix kết thúc `170405`, `171153`, `171847`. Không coi các lượt này là kết quả đạt.

Git HEAD lúc kiểm thử: `9b175864c40732d81f56223425fa8bd1adad6d9e`, workspace dirty, không phải một commit tái hiện đầy đủ. Các chỉnh sửa ngoài nhiệm vụ đang có trong workspace được giữ nguyên, không revert/commit chung. Manifest chứa hash script/JAR/runtime. Java 21.0.12.1, PostgreSQL 18.4, Redis 8.0.5, k6 2.2.0; Redis local dùng runtime thử có sẵn, không cài vào production.

Đã dừng các tiến trình thử thuộc nhiệm vụ; các cổng 18282/18283/15435/15437/16480 không còn listener. Redis test dừng bằng `shutdown save`, giữ snapshot tại `target/evidence-local-redis-20261004-pathfix/dump.rdb`; không flush hoặc xóa artifact. Báo cáo/runbook qua kiểm tra UTF-8, `git diff --check` không có lỗi whitespace.

SHA256:

```text
Smoke JAR: FAF3EF2DEDE7D192512C727DCA4E02F1CE4EB4DCD63D0633EF58991CFA872652
Smoke raw gzip: 8169FAC9C14A32D6FD0287918958A8665419C1F9336219F741DDEA4431346067
Race CSV: A9645083B031CCB5D51F96DCB828E7C0B90A7B9ED504283E32ECD052542F69DA
SQL dump: 7A0A322098ED3F7EBF4C54AC800F3D880F938D6D3DCCF267ACA679575897BAFA
evidence.js: 8A21A6FF2D9ED2D4DB0594AFCED7840FA0F9F6FC2932B410D5FCCA8C9B44063C
shared.js: 1E1DCBC38EA9A5D85A8B00EEBFE19208415BE7DDECB168414A3901BF056EFFD2
run-capacity-check.ps1: BFCF942CB598509D8129CD19FAC5ED83393408A30B03A9471FEA84B63E571F10
```

## Chưa xác minh và bước tiếp theo

Chưa chạy load 300 VU dài, soak, spike/stress và recovery sau stress. Chưa có full N-way reclaim matrix ngoài claim/fencing hiện tại; chưa kill/restart Listening worker thật hoặc kiểm tra Rabbit reconnect. Lost-response đang mô phỏng bỏ response, chưa cắt TCP sau commit. Chưa restore media/Redis hoặc boot app trên DB restore. Chưa có browser/audio/WebRTC workload, dataset năng lực đại diện, độ chính xác/latency AI thật và financial budget thực thi. 178/178 smoke không đủ để suy ra SLA 99,9% ở production.

Chỉ tiếp tục campaign khi có app/DB/Redis test tách biệt và preflight đạt, generator ngoài VPS, AI giả có budget. Nếu không có tài nguyên, giữ trạng thái chưa kiểm chứng. Không thay mục tiêu bằng kết quả bài ngắn hoặc nới ngưỡng.

Câu CV có thể dùng với phạm vi hiện tại: “Xây dựng bộ kiểm thử concurrency/recovery cho DictaLab trên PostgreSQL và Redis thật, kiểm tra race 2–20 thao tác, retry/idempotency, worker crash, provider fault và SQL restore; lưu raw artifact và hướng dẫn tái hiện.” Không viết “chịu tải 300 người một giờ” hoặc “soak hai giờ đạt SLA”.
