# DictaLab — k6 production load, 11/10/2026

## Kết luận ngắn

**Mức cao nhất thực chạy: 10 VU. Chưa xác định tải trần của VPS.** Warm-up
30 giây đạt; bài 120 giây không đạt vì Redis Cloud bị buộc ngủ theo Free plan
ngay trong cửa sổ tải. Chưa chạy 20/50/100 VU: không có Redis hoạt động để tạo
phiên hợp lệ và thực thi nghiệp vụ. Mục tiêu chiến dịch 100 VU **chưa đạt**.
Không đổi cấu hình, nâng gói cloud, restart ứng dụng hoặc xóa dữ liệu để vượt
blocker. Production cuối lượt vẫn health DOWN, liveness/readiness UP, app active;
xác thực chưa hồi phục. Không được dùng kết quả này để kết luận RAM 1 GB chỉ
chịu được 10 người dùng, hoặc throughput tối đa là 8,8 RPS.

## Kết quả đo thực tế

RPS/p95/p99/error dưới đây tính từ raw k6 points trong đúng cửa sổ tải, loại
logout và graceful drain. Threshold toàn bài và accounting được giữ riêng.

| Mức tải | Cửa sổ | Requests | RPS | p95 | p99 | HTTP lỗi | Kết luận |
|---|---:|---:|---:|---:|---:|---:|---|
| 10 VU warm-up | 30 s | 264 | 8,80 | 578 ms | 732 ms | 0% | Đạt, chỉ smoke |
| 10 VU load | 120 s | 936 | 7,80 | 2.292 ms | 2.312 ms | 5,24% | Không đạt |
| 20 / 50 / 100 VU | — | — | — | — | — | — | Chưa test; Redis AUTH bị chặn |

SLO đề xuất của probe: p95 <1 giây, p99 <2 giây, HTTP/business errors <1%,
business checks và journey success >99%. Đây không phải cam kết SLA đã nghiệm
thu. Có đủ 120 raw VU samples trong cửa sổ 120 giây, min/max VUs bằng10.

Bài 120 giây có 887 HTTP200 và49 HTTP503 trong cửa sổ. Toàn journey, gồm drain:
313 started =313 finished, 256 success, **81,79% journey success**, 0 interrupted.
Toàn k6 lifecycle gồm logout: 67/964 HTTP thất bại (6,95%); không đánh đồng số
này với 5,24% của cửa sổ tải. K6 exit99; không sửa threshold để làm PASS.

Lỗi HTTP đầu tiên ở giây100,213. Trước lỗi có887 requests, 8,85 RPS, p95≈539 ms,
p99≈715 ms. Đây là đoạn đo trước outage được tách sau khi quan sát lỗi, **không
phải bài nghiệm thu độc lập**, không thay thế kết quả FAIL của đủ120 giây.

## Topology và nghiệp vụ

- Generator: máy HP Windows riêng, 32 GB/16 logical CPUs; k6 v2.2.0; public HTTPS
  trực tiếp tới `184.194.89.90`, kiểm tra chứng chỉ bật. Không đặt k6 trên VPS.
- VPS: 2 vCPU, RAM usable909 MiB, swap1 GiB; JAR SHA256
  `D9869DAA9BDE599120AA67932030458EB79ED680852EC45807952DCD2D2D495B`.
- Neon PostgreSQL18.6, Redis Cloud; không coi chúng là tài nguyên DB chạy trong
  VPS. RabbitMQ không chạy trên VPS; AI/listening queue đều false ở runtime.
- 80% **VU** đọc catalog A1/A2/B1/B2, history có pagination, dashboard; 20% VU
  luyện một câu: start/resume, replay, word check, reveal hint, replay complete,
  sentence complete và history. Tỷ lệ này không phải80/20 HTTP requests/journeys.
- Think2 giây, playback mô phỏng3 giây mỗi lượt; không tải audio thật. Mỗi VU
  có tài khoản/session riêng. Không gọi AI/TTS, upload, Listening, Room/WebRTC
  hoặc S3 media trong workload. Không retry write có kết quả không chắc chắn.
- Constant VUs là tải closed-loop có think time, không phải100 requests/s hoặc
 100 người cùng gửi request ngay tức thì. [Định nghĩa executor của Grafana](https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/constant-vus/).

## Tài nguyên và bottleneck

Các số dưới đây thuộc **23 sample trong cửa sổ120 giây**, khoảng5 giây/sample;
không lấy recovery nhàn rỗi để làm giảm CPU trung bình.

| Hạng mục | Quan sát |
|---|---|
| CPU VPS | avg10,73%, peak18,45%; app CPU peak16,75%, chuẩn hóa theo2 vCPU |
| RAM | available thấp nhất149,4 MiB; app RSS peak448,8 MiB; swap used peak256,9 MiB |
| JVM | heap used peak112,5/371,25 MiB; threads55; delta GC9 lần/82 ms; NRestarts0→0 |
| PostgreSQL/Hikari | max5 active, pending1; timeout delta0; acquire avg1,36 ms, usage avg42,24 ms |
| PostgreSQL samples | 8 samples; sessions max5; deadlock delta0, lock waiter0; idle-in-transaction peak3 rồi về0 |
| Neon probe | connect + read-only SQL + client launch max230 ms; không phải query-only latency |
| Generator | CPU peak18%; k6 RSS peak49,6 MiB; available RAM tối thiểu17.816 MiB |
| RabbitMQ | Không có broker/queue production đang bật để đo; không ghi backlog=0 như bằng chứng capacity |

Neon CPU/RAM/compute credits và Redis Cloud CPU/RAM không được đo: chưa có
platform telemetry trong phiên này. PostgreSQL wait/activity được đọc qua SQL,
không thay thế những số liệu đó. Sample5/15 giây có thể bỏ sót peak ngắn.

**Blocker đã chứng minh:** probe TLS độc lập tới Redis đi qua DNS/TCP/TLS rồi
AUTH trả lỗi provider: `This database is held asleep under the Free-plan sleep
requirement`. Kết quả được lưu trong `redis-failure-diagnostic.log`, không có
credential. Đây không phải bằng chứng Redis quá tải CPU hay VPS hết RAM; không
chứng minh chính load test gây điều kiện ngủ. App phụ thuộc Redis để kiểm tra
JWT; khi Redis không sẵn sàng, authentication fail closed bằng503.

CPU/heap, Hikari timeout và deadlock chưa cho thấy nút thắt compute/DB ở tải đã
chạy. RAM headroom nhỏ, nhưng chưa có bằng chứng RAM gây lỗi lần này. Không
thể suy ra capacity cao hơn từ CPU thấp khi request bị chặn trước nghiệp vụ.

## Dữ liệu, recovery và bằng chứng

Giữ lại10 tài khoản synthetic `Production k6 probe`,16 practice sessions,
36 sentence attempts,130 word attempts,22 word events. Sáu invariant scoped
đúng campaign đều0: duplicate active solo, word-event mismatch, replay/reveal
counter mismatch, completed duration mismatch và incomplete completed lesson.
Idle-in-transaction/lock waiter cuối0. Đây không phải kiểm chứng mọi dữ liệu
production hoặc mọi race condition cùng một entity.

Logout10 session của warm-up đạt. Logout10 session cuối load thất bại vì Redis
ngủ; **không tuyên bố đã revoke hết**, không flush Redis. Cookies/password test
được giữ trong file local `private-*` với ACL user hiện tại/SYSTEM, nằm trong
`target/` Gitignored; không đưa vào báo cáo. Sau khi Redis phục hồi phải xử lý
logout bằng API và kiểm tra lại; không tự xóa DB để che dữ liệu test.

Hash env/Caddy/JAR trước và sau khớp; không thay flags, heap/pool/rate limit/TLS.
Không còn generator chạy. Không tự restart hoặc mua gói để gọi đó là recovery;
Redis vẫn trả lỗi AUTH khi kiểm tra độc lập sau tải.

Evidence ở `target/production-load-20261011/`:

- `warmup-10.*`, `load-10.*`: manifest, summary, compressed raw, stdout/stderr,
  server samples, generator samples, analysis, result.
- `redis-failure-diagnostic.log`, `post-load-invariants.json`, `final-state.log`.
- Reproduce: `scripts/run-production-load.ps1`; workload dùng lại core/shared;
  analyzer `scripts/load/summarize-production.cjs`;12 JS checks đạt, Python
  compile và PowerShell parse đạt. Collector và invariants đã chạy trên VPS.
- Bảng metrics theo định nghĩa [k6 built-in metrics](https://grafana.com/docs/k6/latest/using-k6/metrics/reference/);
  báo cáo không lấy VU allocation làm số request đang xử lý đồng thời.

## Thứ tự tối ưu đề xuất, chưa triển khai

1. Giải quyết Redis Free-plan sleep/availability bằng lựa chọn dịch vụ/gói đã
   duyệt. Không bỏ Redis session validation hoặc fail-open để tăng benchmark.
2. Nghiệm thu lại auth + Redis outage/recovery; readiness phải phản ánh dependency
   cần cho request thực tế (ở lần này readiness UP trong khi auth503).
3. Chạy lại đúng10→20→50→100 VU, rồi soak mức đạt; chỉ khi đã có telemetry mới
   quyết định tối ưu query/cache, pool hoặc nâng RAM. Đừng tăng pool trên1 GB
   chỉ vì nhìn thấy max active5.
4. Đo riêng audio/AI/TTS/Listening/Room và Rabbit khi thực sự bật; có ngân sách
   provider và backlog/confirm/ack metrics. Chưa nghiệm thu những luồng này.

Ponytail giữ nguyên code/runtime production và tái sử dụng core; Logging
Patterns dùng cho chẩn đoán đã lọc secret; báo cáo được kiểm tra UTF-8.
