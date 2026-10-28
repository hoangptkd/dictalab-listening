# Ba service trên EC2, Aurora bật khi cần

## Sử dụng

Chạy PowerShell từ thư mục dự án trên máy Windows đã có khóa SSH:

```powershell
./scripts/ec2-demo.ps1 start
./scripts/ec2-demo.ps1 status
./scripts/ec2-demo.ps1 stop
```

Khi `start` trả về `ready`, mở <https://54.254.221.170/login>.
SSH kiểm tra host key; nếu IP quản trị thay đổi, cập nhật rule cổng 22 cho đúng
IP `/32`. Có thể truyền `-HostName` và `-IdentityFile`. Không đưa khóa vào Git.

| Lệnh | Hành vi |
| --- | --- |
| `start` | Kiểm tra release/checksum/receipt/cấu hình; bật Redis, RabbitMQ, Progress, AI worker/API rồi Core. Kết nối ứng dụng đánh thức Aurora. Chỉ báo thành công sau health và heartbeat. |
| `status` | Xem service, RAM và timer. Không kết nối SQL hoặc đánh thức Aurora. |
| `stop` | Dừng bốn tiến trình nghiệp vụ; backup ba database, media, cấu hình và SQLite ledger; kiểm tra checksum/integrity rồi dừng broker/Redis. Giữ dữ liệu broker/Redis trên ổ đĩa. |
| `backup` | Backup thêm khi ứng dụng đã dừng. Có thể đánh thức Aurora vì cần kết nối SQL. |

Mỗi lần bật từ trạng thái dừng có timer **1 giờ**, sau đó tự dừng và backup.
Gọi `start` khi đang chạy không gia hạn timer. Backup lỗi không được báo thành công;
kiểm tra log root trước khi bật lại nếu nhận `DEMO_FAILED`. Không xóa receipt
hay restore đè dữ liệu để vượt kiểm tra. Các service nghiệp vụ/broker không tự bật
khi EC2 reboot; dùng `start`. EC2 phải đang chạy để nhận lệnh. Caddy giữ HTTPS,
trả 502 khi ứng dụng dừng.

Aurora cấu hình min 0 / max 1 ACU, tự pause sau 5 phút không có kết nối. Sau
`stop`, đóng các SQL client khác và tránh health/SQL polling. Kiểm tra sự kiện
pause hoặc `ServerlessDatabaseCapacity = 0` trên AWS; `Available` không chứng minh
đã pause. Lệnh dừng ứng dụng, không gọi RDS StopDBCluster. EC2, storage và các
tài nguyên khác vẫn có thể phát sinh phí khi compute Aurora pause.

Đã quan sát CloudWatch `ServerlessDatabaseCapacity` về **0 ACU lúc 14:25 UTC
ngày 28/10/2026**, sau khi dừng service và kết thúc fingerprint SQL lúc 14:19 UTC.
Sau đó lệnh `start` đã đánh thức database và đưa cả ba service về `ready`.
IP quản trị đổi trong lúc nghiệm thu; rule SSH đã cập nhật từ
`123.24.143.245/32` sang `27.67.93.232/32`, vẫn chỉ cho một IP quản trị.

## Triển khai ngày 28/10/2026

- EC2 `dictalab-ec2-2gb`, Ubuntu 24.04; RAM thực 1.905 MiB, swap 1.023 MiB.
- Ba database Aurora PostgreSQL 17: `dictalab_core`, `dictalab_ai`,
  `dictalab_progress`; IAM auth và TLS `verify-full`. Runtime không có quyền
  migration hoặc truy cập database của service khác; tách profile theo OS user.
- AI API/worker dùng ledger riêng; Redis TLS có ACL theo service, RabbitMQ
  loopback có tài khoản/vhost riêng và queue bền vững.
- Core chuyển text/jobs sang AI; dashboard đọc `remote` từ Progress.
  Model fine-tune vẫn demo local; EC2 dùng cloud inference.
- Mail, billing, S3 cleanup và worker tạo nội dung cũ giữ trạng thái tắt của demo.
  Dữ liệu là bản Aurora đã được chủ dự án chấp nhận; không đồng bộ Neon mới.
- Release ứng dụng: `50337dafbe58d320492c69846b2aab6dda21c136`.
  Bundle SHA-256: `9e1aab37ea735efca7d7056f7dfdd2e7709d6df726c99ea5e68af4c0534c1fb6`.
  Công cụ vận hành được sửa và kiểm tra riêng trong thay đổi này.
- GitHub CI/CD vẫn disabled theo yêu cầu quota; workflow chỉ bổ sung kiểm tra
  để dùng khi chủ dự án bật lại. Không có deployment CI trong lần này.
- Chưa chuyển DNS/traffic Lightsail. Đây là demo dữ liệu thật, chưa hoàn tất
  cutover production hoặc cam kết vận hành 24/7.

## Nghiệm thu và RAM

Đạt: HTTPS với chứng chỉ hợp lệ; đăng ký/đăng nhập/refresh/logout, CSRF,
cookie Secure/HttpOnly, quyền USER/ADMIN và audio. Tài khoản probe được xóa
theo đúng ID/email; Progress giữ tombstone chống hồi sinh dữ liệu probe.

Một phiên 10 câu hoàn thành qua API → outbox → RabbitMQ → Progress. Summary,
trend, byLevel và errors khớp khi đọc `remote`. Backfill xử lý 105 phiên gồm
probe; **104 phiên lịch sử còn lại khớp mọi trường projection**, outbox không còn
unsent, queue Progress/DLQ trống tại thời điểm kiểm tra.

AI thực hiện một yêu cầu text thật qua `gemini-3.5-flash-lite`. Một job Coach
nhận HTTP 202 và hoàn thành qua worker, đầu ra hợp lệ; scope khác không đọc được.
Endpoint nội bộ/metrics yêu cầu token. Chưa nghiệm thu mọi capability AI,
chất lượng model, stress inference hoặc vận hành nhiều ngày.

| Phép đo | Kết quả |
| --- | --- |
| Dashboard đọc Progress: 5 client, 100 yêu cầu đọc | 4,86 giây; tất cả HTTP 200 |
| RAM khả dụng thấp nhất, cùng lượt | 401,7 MiB |
| Swap dùng cao nhất, cùng lượt | 26,2 MiB |
| Core cgroup memory peak, cùng lượt | 668,2 MiB |
| Progress peak, cùng lượt | 226,9 MiB |
| AI API / worker peak, cùng lượt | 78,9 / 69,9 MiB |
| Lượt trước, bao gồm backfill lịch sử | RAM khả dụng tối thiểu 432,7 MiB; swap 258,6 MiB |
| Khởi động lại ngoài ý muốn trong các lượt đạt | 0 cho cả bốn tiến trình |
| Sau dừng nghiệp vụ/broker | RAM khả dụng khoảng 1.465 MiB |

Số đo từ `MemAvailable`, `/proc/meminfo` và systemd cgroup; cgroup gồm nhiều
thành phần hơn Java heap. 2 GB chạy được demo nhỏ nhưng có áp lực bộ nhớ/swap.
Đo thêm luồng ghi/AI đồng thời và tải dài trước khi tăng người dùng; cân nhắc
nâng RAM nếu swap/độ trễ tăng. Không suy ra sức tải production từ phép đo này.

## Backup và phục hồi

Đã thử `stop`, `start`, `status` từ Windows và chạy thực tế
`dictalab-demo-stop.service`, chính service timer gọi. Timer 1 giờ đã được verify
cấu hình/armed; chưa chờ đủ một giờ để kiểm tra thời gian thực. Không bật lịch
backup định kỳ gây đánh thức Aurora khi demo đang tắt.

Bản coordinated backup sau nghiệm thu:

```text
/var/lib/dictalab-service-backups/services-qqp9ik8w
manifest SHA-256:
f3d9a3bc59a8b112e7c551b73c68c2fae433d68a113e2e9f53d49cb792ffee36
```

Đã tải bản sao riêng về máy quản trị và restore cả ba dump vào Docker PostgreSQL
17 với network disabled: **85 bảng và 16 trạng thái sequence khớp fingerprint**.
SQLite integrity `ok`, job AI hoàn thành vẫn còn. Đây là rehearsal database/ledger;
chưa diễn tập dựng lại toàn host hoặc phục hồi Redis/RabbitMQ.

Evidence/log EC2: `/var/backups/dictalab-ec2-services-20261028/` (root `0700`),
gồm acceptance, RAM, parity và restore receipt. Bản sao quản trị:
`C:/Users/HP/.codex/tmp/aws-production-20261028/ec2-services-final/`;
parent ACL chỉ cho HP và SYSTEM. Không commit các file này.
Giữ riêng encryption key AI và profile AWS: dump không thay thế quản lý khóa.
Backup hiện nằm trên đĩa EC2 và bản sao quản trị; chưa có lịch offsite/retention
tự động. Gauge backup freshness của Core chưa nối với coordinated backup;
dùng manifest/restore receipt làm bằng chứng.

Rollback dashboard: đặt `DICTALAB_PROGRESS_READ_MODE="legacy"` trong file
`/etc/dictalab/app.env` được bảo vệ rồi dừng/bật có kiểm soát; giữ publishing bật.
Không rewind Flyway hoặc restore đè database để rollback ứng dụng.

## Kiểm tra lặp lại

```powershell
python -m pytest -q tools/ai-eval/test_service_launcher.py
docker run --rm --network none --mount "type=bind,source=$pwd,target=/workspace,readonly" --workdir /workspace/scripts/linux dictalab-ai-check python -m unittest test_service_release test_service_demo test_service_units
git diff --check
```

`test_service_units` cần `systemd-analyze`; đã verify unit demo trực tiếp trên EC2.
Test lifecycle giả lập systemd/backup và kiểm tra command symlink thật;
nghiệm thu EC2/restore thật được ghi riêng ở trên.
