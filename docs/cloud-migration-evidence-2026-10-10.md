# DictaLab: migration cloud và bằng chứng reliability — 10/10/2026

> Cập nhật sau: production đã cutover sang `184.194.89.90`, login smoke đạt.
> Xem [báo cáo cutover](cloud-cutover-2026-10-10.md). Các mục bên dưới là
> snapshot lịch sử trước cutover, không phải trạng thái production hiện tại.

## Kết luận

**Chưa hoàn tất migration production hoặc P2.** Bản mới đã chạy thành công trên
VPS `184.194.89.90` với Neon PostgreSQL, Redis Cloud và HTTPS. Đây vẫn là bản
rehearsal: Caddy trả 503 cho `/api/*` và `/ws/*`; không có người dùng ghi vào DB
rehearsal. Production cũ `160.191.244.27` vẫn là writer chính. Không dừng hoặc
đổi JAR trên production cũ trong lượt tiếp tục này.

Không mở PostgreSQL, Redis hoặc cổng quản trị ra Internet. Không flush Redis,
không tạo tài khoản rehearsal trên Redis chung và không sửa lịch sử người dùng.
Kiểm tra HTTPS cuối trên production cũ trả health UP. Probe SSH đọc trạng thái
cuối không trả kết quả đầy đủ nên đã ngắt; không dùng probe đó để chứng nhận lại
SHA256 hiện tại của host cũ. SHA256 cũ bên dưới là baseline đã xác minh trước đó.

## Đã thực hiện và kiểm chứng

| Hạng mục | Kết quả và giới hạn |
|---|---|
| PostgreSQL 17.11 Testcontainers | 18 suite bắt buộc, 170/170 ca đạt; không skip |
| PostgreSQL 18.4 Testcontainers | 18 suite bắt buộc, 170/170 ca đạt; không skip |
| Redis/RabbitMQ trong các suite | Redis 8.10.2, RabbitMQ 4.3.6; kiểm tra bằng verifier Surefire, không chỉ dựa vào BUILD SUCCESS |
| Regression cuối sau sửa cấu hình | 312 ca tổng, 256 ca thực chạy, 56 skip theo profile; 0 failure/error |
| JavaScript | 132/132 ca đạt trong lượt migration trước sửa cấu hình Python/PowerShell; không có JS thay đổi sau đó |
| Script cấu hình cloud | 9/9 test Python; PowerShell parse và UTF-8 đạt |
| Artifact | JAR Java 17 cùng SHA256 trên các build PG17/PG18/default; không sửa main Java để chữa lỗi startup |
| HTTPS | TCP 443 IPv4 đã mở theo xác nhận của người dùng; chứng chỉ IP hợp lệ; health public UP |
| Neon | PostgreSQL 18.6; Flyway V26/V27 áp dụng thành công; 0 constraint chưa validate |
| Dữ liệu | 23 bảng nghiệp vụ trước migration giữ nguyên row count và SHA256; Flyway history thay đổi đúng từ 25 lên 27 |
| Media | 41/41 file media/personal-content giữ nguyên số byte và SHA256 |
| Redis Cloud | TLS/auth/PING chỉ đọc đạt; dùng DB0 production hiện có, không load-test |
| S3 | Bucket private; origin mới được thêm, origin cũ giữ nguyên; GET/HEAD + Range |
| S3 recovery | Synthetic PUT/GET, signed Range 206 + CORS, unsigned 403, kiểm tra quyền giới hạn prefix, backup/delete/restore bytes đạt |
| Startup/restart | Sau sửa cấu hình: service UP, SHA256 đúng, restart có thời hạn và health UP; NRestarts=0 tại kiểm tra |
| Isolation | API và WebSocket public 503; metrics public 401; app18082/management18183 chỉ loopback |

Artifact đã triển khai trên rehearsal:

```text
D9869DAA9BDE599120AA67932030458EB79ED680852EC45807952DCD2D2D495B
```

JAR production cũ, không được thay trong lượt này:

```text
DFAC955B6FC915DEF78AB5563DA12D24311AD9E5BF0AAE7B027E9C182453CC2F
```

Snapshot lấy khi production cũ vẫn chạy. So sánh trên chứng minh bản sao rehearsal
đúng với snapshot, **không chứng minh hai DB hiện tại đồng bộ**. Phải có final sync
sau khi dừng writer trước cutover.

## Lỗi đã tái hiện và sửa

Lượt deploy JAR mới đầu tiên thất bại khi bind
`spring.datasource.hikari.connection-init-sql`: Hikari báo pool đã sealed.
Script tự phục hồi JAR/config cũ. Đã xác nhận rollback health UP, JAR đúng và Neon
vẫn V25 trước retry; không rollback schema bằng thao tác phá dữ liệu.

Nguyên nhân được chứng minh bằng Binder thật, SystemEnvironmentPropertySource,
HikariDataSource và H2: tên `SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT` trở thành
property lồng `connection.timeout`. Binder truy cập connection, khởi động pool
trước khi bind SQL khởi tạo. Tên chuẩn `…CONNECTIONTIMEOUT` không gây lỗi.
Quy tắc chuyển tên được đối chiếu với
[tài liệu Spring Boot](https://docs.spring.io/spring-boot/reference/features/external-config.html).

Sửa nhỏ tại `scripts/linux/configure-cloud.py` và `scripts/migrate-cloud-vps.ps1`:
dùng tên compound chuẩn cho pool size, minimum idle và connection timeout;
normalization bỏ tên sai; cấu hình xung đột bị từ chối. Giữ nguyên statement,
lock và idle-transaction timeout. Hai test Java gồm tái hiện lỗi cũ và kiểm tra
cấu hình đúng/connection hoạt động. Các test Python bảo vệ quoting, validation,
atomic replacement và normalization. Không thêm dependency.

Redis helper cũng đã sửa trường hợp URI kết thúc bằng `/`: đó là DB0, không phải
chuỗi số lỗi. Self-test và PING thực tế đạt. S3 helper có deadline, một transport
attempt và cleanup khóa synthetic đã biết, kể cả PUT có kết quả không chắc chắn.

## Load smoke và các failure được giữ lại

- Smoke local đầu tiên 20 VU/120 giây: 300 hành trình hoàn tất, 0 lỗi HTTP/nghiệp
  vụ, nhưng **FAIL latency SLO**: catalog p95 khoảng 542 ms, start khoảng 669 ms.
- Recheck local: 304/304 hành trình hoàn tất; 2.084/2.084 checks;
  0/2.138 HTTP thất bại; 40 auth families được cleanup; catalog p95 345,38 ms,
  start p95 348,21 ms. Accounting, SQL invariants và isolated restore đạt.
- Mix recheck thực tế: reads165/dictation106/listening20/room13. Có fake provider
  trong fixture. Đây là functional smoke local, không phải capacity VPS, tải
  AI thật, soak hoặc bằng chứng 300 VU.
- Lượt Java21 đầu tiên bị timeout khi Docker cleanup sau 77 ca. Giữ log failure;
  chạy lại bằng Java17 đạt. Không kết luận Java21 là nguyên nhân đã chứng minh.
- Test Binder đầu tiên dùng tên PropertySource không mô phỏng environment chuẩn,
  nên fixture thất bại. Sửa fixture sang tên chuẩn; hai ca đạt. Không dùng lỗi
  fixture làm bằng chứng lỗi production.

Snapshot production có 190 word rows với 237 attempts không có telemetry cũ.
Tất cả session liên quan hoàn tất trước V9 tạo bảng event. Post-V9 mismatch bằng
0, tám invariant còn lại bằng 0. Raw strict checker vẫn ghi FAIL; không backfill
event giả hoặc thay counters để khiến checker đạt.

## Phân biệt source, rehearsal và production

- Source và test đã có durable jobs, lease/fencing, publisher confirms,
  duplicate-delivery handling, recovery và S3 lifecycle. Kết quả test không đồng
  nghĩa các tính năng đó đã bật production.
- Rehearsal chạy schema V27; credentials AI được xóa khỏi cấu hình rehearsal;
  Coach/Conversation, AI/Listening queue và S3 cleanup tắt, dispatch trì hoãn.
- RabbitMQ chưa được bật hoặc triển khai trên VPS mới. Không có bằng chứng
  broker crash/recovery production trên VPS mới.
- TURN vẫn trỏ production cũ. Chưa chứng minh hoạt động hai thiết bị qua IP mới.
- CI/deploy, capacity và backup hiện có chủ yếu cho Windows/NSSM; không đổi host
  secret sang Linux rồi coi các workflow đó đã tương thích.
- S3 recovery chỉ dùng đối tượng synthetic, đã xóa cả hai khóa thử nghiệm; không
  xóa audio người dùng. Bản backup synthetic đã sao chép khỏi VPS và kiểm tra hash.

## Gate còn thiếu và thứ tự tiếp theo

### P0 — trước khi chuyển writer

1. Có resource budget phù hợp trên VPS. Hiện máy 1 GB RAM, khả dụng quan sát
   khoảng 188–220 MiB và đã dùng swap. Không đủ điều kiện chạy chiến dịch P2 hoặc
   thêm broker tại chỗ; không giảm guard để lấy PASS.
2. Nghiệm thu backup/restore Linux cho Neon V27 + media + artifact; backup phải
   giữ bí mật, có off-VPS copy và khôi phục vào môi trường tách biệt. Client
   PostgreSQL16 hiện trên VPS không phù hợp để pg_dump server18; cần client đúng
   phiên bản hoặc executor phù hợp trước lập lịch backup.
3. Final cutover có một writer: chặn ingress cũ, drain rooms/jobs và dừng mọi
   writer; snapshot cuối; backup target rehearsal; restore/sync có checksum;
   kiểm tra FK/constraints/row fingerprints/media; cấu hình provider chính thức;
   mở ingress mới; smoke auth/media/AI/room và định nghĩa rollback. Không được
   rerun script rehearsal hiện tại trên Neon đã có dữ liệu.
4. Rollback sau khi IP mới nhận writes phải có kế hoạch đồng bộ ngược; đơn thuần
   bật DB cũ sẽ mất dữ liệu mới. Không drop hoặc overwrite target thiếu backup.

### P1 — vận hành và security

5. Runtime Neon dùng tài khoản quyền tối thiểu, tách migration owner; phối hợp
   rotation password đã xuất hiện trong chat. Không ghi credential vào report,
   command arguments hoặc Git.
6. Hoàn thiện Linux release/rollback, timer backup, kiểm tra tuổi backup,
   alert/metrics và chứng chỉ renewal. Kiểm tra TLS Redis/S3/provider timeout.
7. Broker có tài nguyên phù hợp; kiểm chứng durability, confirms, ack/retry/DLQ,
   poison/duplicate delivery, fencing, crash và graceful shutdown tại runtime
   triển khai. Các test Testcontainers hiện có là đầu vào, không thay thế bước này.
8. Nghiệm thu TURN, audio và WebSocket hai thiết bị; giữ phụ thuộc host cũ rõ ràng
   cho tới khi thay thế được kiểm chứng.

### P2 — nghiệm thu tải đầy đủ

9. Chạy mixed 300 VU/30 phút, soak160 VU/2 giờ, spike/stress với generator tách
   biệt và guard. Ghi concurrency thực tế, journey started/finished/completed,
   latency từng endpoint, business errors, DB invariants, pool/heap/GC và backlog.
10. Failure injection và recovery theo campaign; không retry thao tác AI có side
    effect một cách mù quáng. Chứng minh at-least-once + idempotency/fencing,
    không tuyên bố exactly-once.

Không tự mua VPS lớn hơn hoặc broker mới chỉ để vượt gate. Các mục trên chưa
đạt; không tắt máy hoặc tuyên bố toàn bộ P2 hoàn thành.

## Vị trí bằng chứng riêng tư

Trong `target/cloud-migration-20261010/`: logs PG17/PG18/default, startup thất bại,
deploy recheck, restart recovery, fingerprints, legacy audit và S3 synthetic backup.
Đặc biệt: `cloud-binding-recheck.log`, `default-regression-after-binding.log`,
`rehearsal-release-recheck.log`, `rehearsal-restart-recovery.log`.

Trong `target/capacity/`: raw/summary/accounting của smoke recheck
`local-smoke-evidence-reads-20-20261010-174848.*`, SQL/restore evidence
`db-dictalab_capacity_efa07d73e78143d5a129786d2b52b2b6-20261010-175216.*`.

Thư mục migration có dump và media thật, được hạn chế ACL và Git-ignore. Không
đưa nguyên thư mục này lên portfolio công khai. Chỉ chia sẻ báo cáo đã lọc,
source tests và dữ liệu synthetic. Cluster restore local cổng15444 đã dừng đúng
đường dẫn; giữ dump/datafiles và toàn bộ failure evidence.
