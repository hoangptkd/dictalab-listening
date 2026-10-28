# DictaLab production trên EC2

Địa chỉ ứng dụng: <https://dictalab.hoangptkdhy.workers.dev>.
Cloudflare Worker chuyển tiếp HTTPS/API/audio/WebSocket sang EC2
`54.254.221.170`, qua `dictalab-origin-54-254-221-170.sslip.io`.
Core dùng địa chỉ Workers làm public URL và chấp nhận chính xác origin này.

## Bật/tắt database thủ công

### Bảng điều khiển trên Windows

Mở `scripts/aurora-control-panel.cmd` bằng cách nhấp đúp. Máy cần PowerShell 7
và khóa SSH đang dùng cho EC2. Bảng có nút **Bật Aurora**, **Tắt Aurora**,
**Làm mới** và **Mở ứng dụng**; tự đọc trạng thái mỗi 20 giây, hiển thị bảo trì,
bốn tiến trình và RAM EC2. Có thể tạo shortcut đến launcher trên Desktop.

Nút tắt yêu cầu xác nhận vì website sẽ vào bảo trì và thực hiện backup trước
khi dừng database. Giữ cửa sổ và kết nối mạng trong suốt lệnh; bảng chặn đóng
cửa sổ và vô hiệu hóa lệnh cạnh tranh khi đang bật/tắt. Chỉ báo thành công khi
CLI trên EC2 trả kết quả hoàn tất; lỗi SSH hoặc trạng thái quá 60 giây sẽ khóa
nút thao tác cho đến khi đọc lại thành công. Mất kết nối giữa lệnh có thể khiến
quy trình trên EC2 tiếp tục; đọc trạng thái và kiểm tra log trước khi thử lại.

```powershell
pwsh -NoProfile -STA -File ./scripts/aurora-control-panel.ps1
# Nếu đổi máy/khóa:
pwsh -NoProfile -STA -File ./scripts/aurora-control-panel.ps1 -HostName 54.254.221.170 -IdentityFile C:/path/to/key.pem
# Kiểm tra bằng fixture, không kết nối hoặc bật/tắt AWS:
pwsh -NoProfile -STA -File ./scripts/aurora-control-panel.ps1 -SelfTest
```

Bảng dùng lại `ec2-services.ps1` qua SSH kiểm tra host key; không tạo endpoint
quản trị công khai, không giữ AWS credential trên máy local. Khi IP quản trị
đổi, cần cập nhật rule SSH `/32` đúng IP; bảng không tự thay quyền AWS.

### Dòng lệnh

Từ PowerShell tại thư mục dự án, với khóa SSH đã có trên máy quản trị:

```powershell
./scripts/ec2-services.ps1 start
./scripts/ec2-services.ps1 status
./scripts/ec2-services.ps1 stop
```

| Lệnh | Hành vi |
| --- | --- |
| `start` | Kiểm tra release/checksum/receipt/cấu hình; gọi RDS StartDBCluster nếu cần, chờ available; bật Redis, RabbitMQ, Progress, AI worker/API rồi Core. Chỉ bỏ bảo trì sau readiness và heartbeat. |
| `status` | Đọc trạng thái systemd, RAM, bảo trì và RDS. Không kết nối SQL hoặc đánh thức compute đang pause. |
| `stop` | Bật bảo trì trước; dừng bốn tiến trình nghiệp vụ; backup ba database/media/cấu hình/SQLite; kiểm tra checksum/integrity; dừng broker/Redis rồi gọi RDS StopDBCluster và chờ stopped. |
| `backup` | Backup thêm khi ứng dụng dừng và Aurora đang available. Không tự bật cụm đã stopped. |

Lệnh có thể chờ vài phút khi AWS đang chuyển trạng thái. Timeout hoặc backup lỗi
không được báo thành công; bảo trì được giữ. Nếu backup lỗi, cụm chưa được lệnh
đóng; kiểm tra trạng thái và log root trước khi tiếp tục. Không xóa receipt hay
restore đè dữ liệu để vượt kiểm tra.

Khi database tắt, HTTPS vẫn hoạt động: trang web hiển thị bảo trì tiếng Việt;
API trả JSON HTTP 503, `Retry-After: 300`, `Cache-Control: no-store`.
Khách truy cập không thể tự bật database. Relay telemetry Studio đã có được
giữ riêng, không chuyển thành endpoint quản trị database công khai.

Không có timer tự dừng sau một giờ. `dictalab-production.service` khôi phục
trạng thái đã chọn khi EC2 reboot: đang mở thì khởi động lại có kiểm tra;
đã tắt thì giữ bảo trì. Các unit nghiệp vụ/broker không được enable độc lập.
Trạng thái nằm ở `/etc/caddy/dictalab-maintenance`; quản lý bằng lệnh, không sửa tay.

AWS tự khởi động lại cụm bị stop sau tối đa 7 ngày. Ứng dụng vẫn giữ bảo trì
cho đến khi quản trị viên chạy `start`; cấu hình min 0 ACU và idle pause vẫn
giúp compute trở về 0 khi không có kết nối. Storage, backup và EC2 vẫn tính phí.
Xem [quy định stop/start Aurora](https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/aurora-cluster-stop-start.html).

SSH kiểm tra host key. Nếu IP quản trị đổi, cập nhật cổng 22 cho đúng IP `/32`.
Có thể truyền `-HostName` và `-IdentityFile`. Không đưa khóa vào Git.

## Cấu hình và quyền

- EC2 `dictalab-ec2-2gb`, Ubuntu 24.04; RAM thực 1.905 MiB, swap 1.023 MiB.
- Core, AI, Progress dùng ba database Aurora PostgreSQL 17 riêng;
  IAM auth và TLS `verify-full`. Runtime không có quyền migration hoặc quyền
  điều khiển RDS. AI API/worker dùng ledger bền vững riêng.
- Bản ứng dụng đang chạy: `50337dafbe58d320492c69846b2aab6dda21c136`.
  Thay đổi này cập nhật công cụ vận hành, proxy và cấu hình production.
- Redis TLS loopback, ACL theo service; RabbitMQ loopback, tài khoản/vhost riêng.
  Core chuyển text/jobs sang AI; dashboard đọc `remote` từ Progress.
- Model fine-tune chạy local; EC2 dùng cloud inference. Mail, billing và S3
  cleanup hiện chưa bật. Không suy ra các tính năng này đã nghiệm thu.
- Tên cụm AWS lịch sử `dictalab-aurora-demo` được giữ để bảo toàn tài nguyên
  và dữ liệu. Tên này không phải nhãn môi trường hoặc nội dung sản phẩm.
- User IAM `dictalab-ec2-backup` có inline policy `DictalabAuroraOperations`,
  theo `ops/aws/aurora-operations-policy.json`: Describe/Start/Stop chỉ đúng ARN
  cụm hiện tại. Giữ nguyên policy SQL backup riêng. Profile backup chỉ root đọc;
  không tạo access key mới và không thêm quyền cho Core/AI/Progress.
  Policy vận hành được quản lý riêng với stack database-access hiện có.
- GitHub CI/CD vẫn tạm dừng theo yêu cầu quota. Triển khai này thực hiện qua SSH
  và dashboard Cloudflare; không phải một lần deploy bằng GitHub Actions.
  Trước khi bật lại deploy workflow, cần kiểm tra host/known-host secrets và
  chuyển bước kích hoạt một JAR cũ sang quy trình release ba service có backup
  và readiness. Không chỉ đổi IP rồi chạy lại bước deploy một JAR.
- AWS đã xác nhận Lightsail `ubuntu-vps-1` (`184.194.89.90`) bị xóa ngày
  28/10/2026. IP tĩnh `ubuntu-vps-ip` đã được giải phóng. Phí đã phát sinh trước
  khi xóa không được coi là đã hoàn lại. Snapshot Lightsail cũ
  `dictalab-1gb-before-upsize-20261011` cũng đã xóa sau khi kiểm tra các backup
  được giữ trên EC2 và máy quản trị; không còn instance/IP/snapshot Lightsail
  trong danh sách đã kiểm tra, Storage không có disk hoặc bucket riêng.

## Dữ liệu, backup và giới hạn đo tải

Dữ liệu gốc là bản Aurora từ snapshot Neon đã được chủ dự án chấp nhận.
Không đồng bộ hoặc ghi đè từ Neon trong thay đổi này.

Nghiệm thu trước cutover: HTTPS/auth/CSRF/cookie/quyền/audio; luồng practice
10 câu → outbox → RabbitMQ → Progress; 104 phiên lịch sử khớp projection.
AI đã chạy text thật và Coach job nền; scope khác không đọc được job.

Phép đo trước cutover, cùng host: 5 client / 100 yêu cầu dashboard, tất cả
HTTP 200 trong 4,86 giây; RAM khả dụng tối thiểu 401,7 MiB; swap cao nhất
26,2 MiB. Lượt gồm backfill dùng swap đến 258,6 MiB. Không có restart ngoài
ý muốn trong các lượt đạt. Đây là tải nhỏ; chưa đo tải ghi/AI đồng thời dài hạn.

Backup coordinated nằm ở `/var/lib/dictalab-service-backups/`; mỗi lần `stop`
thành công có manifest checksum riêng. Bản trước cutover `services-qqp9ik8w`
đã được restore vào PostgreSQL 17 cô lập: 85 bảng và 16 trạng thái sequence
khớp, SQLite integrity `ok`. Evidence riêng trên EC2 tại
`/var/backups/dictalab-ec2-services-20261028/`, chỉ root đọc.
Chưa có lịch offsite/retention tự động hoặc diễn tập dựng lại toàn host.

Khi dọn Lightsail, instance biến mất trước lúc tải xong archive retirement mới;
archive tải dở không phải backup hợp lệ. Bản migration cũ 411.642.824 byte vẫn
còn trên EC2 và đã sao chép đầy đủ về máy quản trị, đối chiếu SHA-256 và đọc
kiểm tra 303 thành viên archive. Snapshot production `services-5xak1j3m` cũng
được kiểm tra lại checksum/archive/SQLite; lần kiểm tra này không restore SQL.
Các lịch sử release/backup chỉ có trên Lightsail chưa được chứng minh đã sao
chép đầy đủ; không suy ra chúng đều được giữ từ backup migration.

Nghiệm thu cutover ngày 28/10/2026:

- Worker active `08948d38` thay origin cũ bằng EC2 mới; phản hồi công khai
  có `X-Dictalab-Origin: ec2-54.254.221.170`.
- `stop` đã backup thành công rồi chờ RDS xác nhận `stopped`; lần `stop`
  tiếp theo không đánh thức database. Luồng boot systemd giữ bảo trì khi tắt.
  Sau đó `start` đưa cụm về `available` và tất cả tiến trình về ready.
- Bảo trì HTML/JSON qua Workers: HTTP 503, Retry-After và no-store đạt.
- Qua hostname công khai: auth/cookie/CSRF, catalog/history/dashboard,
  invite URL, WebSocket hai thành viên, chặn sai Origin/thiếu đăng nhập,
  login/refresh/logout đạt. Hai tài khoản thử được dọn theo đúng ID/email/role.
- Sau khi bật lại cụm, AI nhận job HTTP 202, worker gọi cloud model và hoàn
  thành Coach với đầu ra hợp lệ; scope khác bị chặn 404.
- RAM đo sau lượt kiểm tra và AI trên cùng EC2: khoảng 424 MiB khả dụng, swap 30 MiB;
  bốn tiến trình nghiệp vụ đều active, NRestarts = 0 tại thời điểm kiểm tra.
- Backup trước khi dừng cụm: `services-5xak1j3m`, manifest SHA-256
  `ea7a1f25616f014543074d344bba1ee9ea2c1fe2af3e84e13b5b59442f6be9c8`.

Kiểm tra boot đã chạy qua unit thật trên host, chưa reboot toàn EC2 vì host
còn relay Studio đang được sử dụng.

## Kiểm tra lặp lại

```powershell
node scripts/cloudflare/check-worker.mjs
docker run --rm --network none --mount "type=bind,source=$pwd,target=/workspace,readonly" --workdir /workspace/scripts/linux dictalab-ai-check python -m unittest test_service_release test_service_lifecycle test_service_units test_aws_database_access
git diff --check
```

Unit production đã có kiểm tra bằng `systemd-analyze` trên EC2. Các test lifecycle
giả lập systemd/RDS/backup; phải kiểm tra riêng stop/start thực tế và phản hồi
Workers trước khi báo nghiệm thu. Source proxy trong `scripts/cloudflare/`;
khi đổi origin, chỉ thay hostname và giữ forwarding header, cookie, CSRF,
POST/query, Range, redirect và WebSocket.
