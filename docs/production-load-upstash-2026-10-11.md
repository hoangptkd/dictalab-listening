# k6 production sau chuyển Upstash — 11/10/2026

## Kết luận

Đã hoàn thành **9 bài**, gồm **10 → 20 → 50 → 100 VU**, bổ sung 30/40 VU
và duy trì 30/20/10 VU trong 5 phút. Mức cao nhất đã chạy: **100 VU**, nhưng
mục tiêu 100 VU **không đạt SLO latency**, dù HTTP lỗi 0% và journey success 100%.
Mức cao nhất đạt đầy đủ trong bài 120 giây: **30 VU**.
Mức cao nhất đạt đầy đủ khi duy trì 5 phút: **10 VU** (9,22 RPS).
20/30 VU duy trì không đạt riêng hint; không diễn giải thành mọi API chỉ chịu 10 VU.
Recovery cuối đạt, cấu hình production giữ nguyên, không còn generator chạy.
Không coi 100 VU là tải trần tuyệt đối hoặc kết quả nghiệm thu mọi chức năng của website.

## Số đo thực tế

Các số bên dưới lấy từ raw k6 points trong cửa sổ tải; không gồm setup, logout
hoặc graceful drain. Threshold của k6 tính trên scope đã khai báo, được giữ riêng
trong summary. Không bỏ đoạn chậm để làm PASS.

| VU | Thời gian tải | RPS | p95 | p99 | HTTP lỗi | Nghiệm thu |
|---:|---:|---:|---:|---:|---:|---|
| 10 | 120 s | 8,98 | 559 ms | 778 ms | 0% | Đạt |
| 20 | 120 s | 17,43 | 625 ms | 920 ms | 0% | Đạt |
| 50 | 120 s | 34,68 | 1.127 ms | 1.456 ms | 0% | Không đạt latency |
| 100 | 120 s | 35,46 | 2.862 ms | 3.208 ms | 0% | Không đạt latency |
| 30, bổ sung | 120 s | 26,35 | 678 ms | 861 ms | 0% | Đạt |
| 40, bổ sung | 120 s | 32,98 | 834 ms | 1.140 ms | 0% | Không đạt p95 của resume/hint |
| 30, duy trì | 300 s | 26,83 | 606 ms | 838 ms | 0% | Không đạt p95 hint: 1.020 ms |
| 20, duy trì | 300 s | 18,16 | 528 ms | 696 ms | 0% | Không đạt p95 hint: 1.025 ms |
| 10, duy trì | 300 s | 9,22 | 495 ms | 632 ms | 0% | Đạt; hint p95 947 ms |

SLO của harness: p95 work <1 s; p99 work <2 s; HTTP/business lỗi <1%;
journey success và checks >99%; mỗi route được khai báo có p95 <1 s.
Đây là tiêu chí kiểm thử, không phải SLA đã cam kết. 40 VU đạt percentile tổng
nhưng không đạt từng API; không được gộp thành PASS.

Sáu bài 120 giây đều có đủ 120 raw VU samples, min/max bằng VU mục tiêu;
journey success 100%, started=finished, không có journey bị ngắt.
Số journey lần lượt: 323/619/1.234/1.268/935/1.166.
HTTP logout thành công đủ 250 phiên của sáu bài; kiểm tra độc lập Redis xác nhận
cả 250 family key đã biến mất, dùng 30 family đang active làm đối chứng đúng user.
Bài 30 VU/300 s: 2.352/2.352 journey thành công, 300 VU samples đủ tải;
30 refresh trả 204; logout đủ 30. Probe sau bài này xác nhận tổng 280 family bị revoke.
Không thay ngưỡng hint dù mức vượt chỉ khoảng 20 ms.
Bài 20 VU/300 s: 1.589/1.589 journey thành công, đủ 300 VU samples;
20 refresh trả 204, logout đủ 20; probe sau bài xác nhận tổng 300 family bị revoke.
Bài 10 VU/300 s: 807/807 journey thành công, đủ 300 VU samples, 10 refresh
trả 204, logout đủ 10. **Cả 310 family** trong chiến dịch được xác nhận không còn
key trong Redis sau logout. Các probe có positive controls active đúng user trước đó.

## Tài nguyên và bottleneck

Số đo VPS/JVM/DB chỉ lấy các sample trong cửa sổ work, khoảng 5 giây/sample;
PostgreSQL SQL probe khoảng 15 giây/sample. Cumulative deltas chỉ bao phủ
các ranh giới sample; có thể bỏ sót peak ngắn.

| VU | CPU VPS avg/peak | RAM available min | App RSS peak | Hikari pending peak | Acquire avg |
|---:|---:|---:|---:|---:|---:|
| 10 | 21,14 / 37,42% | 149,1 MiB | 451,7 MiB | 0 | 1,36 ms |
| 20 | 19,35 / 47,71% | 135,8 MiB | 475,1 MiB | 3 | 10,55 ms |
| 50 | 22,16 / 46,88% | 97,3 MiB | 501,4 MiB | 30 | 110,57 ms |
| 100 | 18,12 / 42,33% | 132,6 MiB | 449,5 MiB | 37 | 326,27 ms |
| 30 | 12,98 / 22,59% | 144,3 MiB | 444,4 MiB | 7 | 14,99 ms |
| 40 | 15,88 / 36,54% | 143,1 MiB | 445,1 MiB | 18 | 38,06 ms |
| 30, 300 s | 12,47 / 27,55% | 134,0 MiB | 446,3 MiB | 9 | 8,73 ms |
| 20, 300 s | 8,89 / 22,91% | 137,2 MiB | 450,5 MiB | 3 | 3,60 ms |
| 10, 300 s | 5,59 / 23,57% | 129,7 MiB | 452,8 MiB | 0 | 1,15 ms |

Runtime: 2 vCPU, RAM usable 909 MiB, swap 1 GiB, **40 HTTP threads**, 512 max
connections; Hikari max 5/min idle 0/connection timeout 3.000 ms. Không nâng RAM
hoặc thay những giới hạn này trong chiến dịch. CPU tổng của collector bao gồm
steal time; app CPU chuẩn hóa theo 2 vCPU được lưu riêng.

**Nghẽn đo được:** pool active chạm 5, pending và acquire latency tăng; nâng
50 lên 100 VU chỉ tăng RPS khoảng 2,2%, nhưng p95 tăng mạnh. Request queue/HTTP
thread limit và pool là các giới hạn cần đo/tối ưu tiếp, không phải kết luận
PostgreSQL hết CPU. Không chứng minh tăng pool đơn thuần sẽ giải quyết vấn đề.

JVM toàn chiến dịch: heap used peak khoảng 121,3/371,25 MiB, threads peak 83;
GC pause delta của bài 20 VU là 2,4 s, các bài khác thấp hơn.
VPS có CPU steal peak khoảng 28,8% và iowait peak khoảng 14,4%; đây là yếu tố
nhiễu/tail latency cần theo dõi, chưa chứng minh Lightsail CPU credit bị cạn.
Swap used peak khoảng 333,7 MiB là toàn VPS, không phải riêng heap Java.
RAM headroom nhỏ nhưng chưa có bằng chứng OOM hoặc restart gây lỗi trong chiến dịch.

PostgreSQL: sessions peak 5; không có deadlock delta, lock waiter hoặc Hikari
timeout trong sample; idle-in-transaction peak 5 khi tải chạy, về 0 ở probe sau
100 VU. Neon connect + read-only query + client launch peak khoảng 440,5 ms;
không diễn giải thành latency riêng của một SQL nghiệp vụ.

Upstash: hai probe trong chiến dịch trả PONG khoảng 1,59/1,67 ms;
INFO quan sát used_memory khoảng 9.900/49.522 bytes, maxmemory 64 MiB,
evicted_keys=0. Đây là hai snapshot, không phải CPU/RAM máy chủ Redis hoặc
percentile latency toàn bài. Không quan sát lại lỗi Free-plan sleep của Layerbase.
Neon CPU/RAM/compute credits và Upstash CPU/quota/billing chưa có telemetry.

RabbitMQ: AI/listening queue runtime đều false; không bật broker/queue để benchmark.
Không có kết quả RabbitMQ capacity, confirm/ack hoặc backlog; không ghi thành 0.

## Phạm vi và các điểm chưa test

Generator k6 v2.2.0 chạy trên máy HP Windows, **khác VPS**, tới public HTTPS
`184.194.89.90`, xác minh TLS bật. Generator CPU peak trong sáu bài 39%, RSS peak
118,9 MiB, available RAM tối thiểu khoảng 15.430 MiB; chưa thấy nghẽn CPU/RAM generator.
Đây là peak generator tính cả lifecycle của sáu bài đầu, không phải chỉ work.
Ba bài duy trì: generator work CPU avg lần lượt 3,23/6,98/59,62%; work peak
10/38/93%. Bài 10 VU chạy khi Windows có tải nền cao; RAM available toàn lifecycle
tối thiểu 8.786 MiB. CPU riêng k6 peak, chuẩn hóa theo 16 logical CPUs, dưới 0,5%
trong cả chiến dịch. Chưa thấy k6 process bị CPU/RAM saturation, nhưng máy không
hoàn toàn isolated: tải nền/network vẫn là nguồn nhiễu cần kiểm soát khi benchmark lại.
Latency client bao gồm đường mạng, không phải service time nội bộ;
`http_req_duration` không gồm thời gian DNS/establish connection/TLS/blocked.
[Định nghĩa metric của Grafana](https://grafana.com/docs/k6/latest/using-k6/metrics/reference/).

80% VU đọc catalog A1/A2/B1/B2, history pagination, dashboard; 20% VU dictation:
start/resume, replay, word check, reveal hint, replay complete, sentence complete,
history. Tỷ lệ theo **VU**, không theo requests. Think 2 s, playback mô phỏng 3 s
hai lần; không tải audio. Mỗi VU một tài khoản riêng; fixture có sẵn được tái sử dụng,
dữ liệu học tăng dần giữa các stage. Không giả định dataset lạnh hoặc cố định.

Chưa test >100 VU, arrival-rate/open-loop, burst login đồng thời, soak nhiều giờ,
mọi người cùng ghi một entity, AI/TTS, S3/audio thật, Listening, Room/WebRTC hoặc
RabbitMQ. Constant VUs có think time không đồng nghĩa 100 RPS hoặc 100 HTTP requests
đang xử lý đồng thời. Đây không phải phép đo giới hạn gây crash tuyệt đối.

## Thay đổi và kiểm chứng bộ test

Không sửa code/config backend production. Chỉ chỉnh harness cho stage lớn:
teardown timeout `3 × VU + 30 s`, runner có bound tương ứng, threshold yêu cầu
logout đủ family và cleanup lỗi bằng 0. Không đổi work duration, workload hoặc
threshold latency/error để làm PASS. Ba stage đầu dùng harness cũ; từ 100 VU
dùng bound/cleanup threshold mới. Manifest giữ hash riêng từng stage.
Lý do: [teardown mặc định của k6 là 60 s](https://grafana.com/docs/k6/latest/using-k6/k6-options/reference/#teardown-timeout),
không đủ cho logout tuần tự 100 phiên có rate limiting và RTT mạng.

JS checks 13/13 PASS; PowerShell parse và Python compile PASS. Helper kiểm tra
session Redis chỉ đọc, validate input và TLS, có runnable input check PASS.
Ponytail giới hạn thay đổi vào harness; Logging Patterns dùng timing/request ID
không payload; JPA Query Reviewer phân biệt bằng chứng pool với giả thuyết query;
Encoding Guard dùng kiểm tra UTF-8 báo cáo.

## Dữ liệu và recovery cuối

- App active, PID 76941, NRestarts 0 từ đầu tới cuối; Caddy/backup timer active.
- Health/readiness UP, probe TLS/AUTH/PING Redis PASS, public HTTPS login 200;
  không restart hoặc đổi cấu hình để tạo recovery. Redis health indicator mặc định
  vẫn tắt; health tổng UP không được dùng riêng để chứng minh Redis khỏe.
- Hash app.env, JAR, Caddy và AWS credentials trước/sau khớp;
  41 file media/personal-content giữ nguyên danh sách, dung lượng và SHA-256.
- Fixture cuối: 100 tài khoản, 182 practice sessions, 843 sentence attempts,
  3.650 word attempts, 664 word events. Bao gồm dữ liệu chiến dịch cũ; không phải
  toàn bộ là dữ liệu tạo mới trong lượt này. Giữ lại tất cả, không DELETE/flush.
- Sáu invariant scoped campaign bằng 0: duplicate active solo, word-event mismatch,
  replay/reveal counter mismatch, completed duration mismatch, incomplete completed
  lesson. Idle-in-transaction và lock waiter cuối bằng 0. Không tuyên bố kiểm chứng
  mọi dữ liệu production hoặc mọi race trên cùng entity.
- Validator raw/summary/result/accounting/session/invariant **PASS cho 9 bài**;
  không có sample metrics/SQL probe unavailable trong các cửa sổ đo.
  File `upstash-campaign-validation.json` giữ accounting và thông tin generator.

Bài duy trì 5 phút chưa thay thế soak nhiều giờ, nhiều lần lặp hoặc capacity SLA.

## Hướng tối ưu tiếp, chưa triển khai

1. Đo query/transaction count mỗi request. Source có `findById` trong cả
   `JwtTokenService.currentUser` và `AccountStateFilter`; OSIV tắt. Kiểm tra khả năng
   đọc lặp, không coi source call count là SQL count đã đo trên runtime.
   Test query budget phải giữ nguyên kiểm tra enabled/authVersion/role và quyền truy cập.
2. **Ưu tiên hint:** `PracticeController.hint` gọi `PracticeService.useHint` trong
   write transaction có aggregate lock. `revealSentence` (dòng 322–334) gọi
   `getOrCreateWordAttempt` theo từng từ; helper (dòng 582–585) gọi repository
   `findBySentenceAttemptIdAndWordIndex` rồi save khi thiếu. Source chứng minh số
   lần gọi repository tăng theo số từ; chưa đo số SQL thực tế trên runtime.
   20 VU/300 s có acquire avg 3,6 ms nhưng hint p95 vẫn 1.025 ms: không được quy
   toàn bộ lỗi hint cho pool. Giải pháp nhỏ cần thử: fetch WordAttempt một lần bằng
   repository list sẵn có, dùng map theo index, tạo phần thiếu và giữ nguyên lock,
   unique constraint, transaction, proper names, counters và yêu cầu replay.
   Regression: fixture câu ngắn/dài, reveal lặp/concurrent, kết quả và invariant
   không đổi; SELECT budget không tăng theo số từ (INSERT/UPDATE vẫn có thể tăng).
   Không triển khai thay đổi này trong chiến dịch load.
3. A/B thread/pool limit và RAM sau khi có quyền thay cấu hình; đo memory, acquire,
   usage, PostgreSQL activity và latency. Không cam kết nâng RAM sẽ tự tăng RPS.
4. Soak dài hơn và workload riêng cho audio/AI/room/queue trước khi tuyên bố capacity
   cho toàn website.

Evidence: `target/production-load-20261011/upstash-*` gồm manifest, compressed raw,
summary, analysis, result, server/generator samples; file `private-*` có ACL và
nằm trong target Gitignored. Không đưa credential vào báo cáo. Dữ liệu synthetic
được giữ lại; không xóa tài khoản, practice data hoặc flush Redis.
