# DictaLab — cutover production và kiểm chứng login, 10/10/2026

## Kết quả

Production hoạt động tại **https://184.194.89.90/login**, dùng Neon PostgreSQL
và Redis Cloud hiện có. Lỗi “yêu cầu thất bại” lúc login là do Caddy của bản
rehearsal chặn `/api/*`; đã chuyển sang cấu hình production sau khi khóa writer
cũ và đối chiếu dữ liệu. Không sửa Java hoặc đổi mật khẩu tài khoản thật để chữa
lỗi này. Không mua thêm RAM, không mở DB/Redis/management ra Internet.

## Bằng chứng lượt cutover

- Writer cũ `160.191.244.27`: DictaLab stopped, startup Disabled; role
  `dictalab_app` có `default_transaction_read_only=on`. Đây là hàng rào vận hành,
  không phải cấm tuyệt đối một client được cấp quyền cố ý override session.
- Trang login cũ trả 302 sang IP mới; API/WebSocket cũ vẫn bị chặn. TURN cũ chạy;
  Bilingual/WebLens không bị dừng. Chưa thể xóa VPS cũ vì vẫn phụ thuộc TURN.
- Snapshot cuối sau quiesce tại
  `C:\DictaLab\backups\cloud-cutover-20261010-191213-505`, dump SHA256
  `9DB2A40DB76E6CCEE815C5C09103B4C477B48105DA38EBBB207065EFD3C194BD`.
- 24 bảng hiện có ngoài Flyway khớp row count và SHA256 với backup cloud đã
  restore, rồi đối chiếu lại trực tiếp Neon sau smoke/cleanup: **24/24 khớp**.
  Các hash không chứa sequence state; đăng ký synthetic đã tiêu thụ sequence,
  không reset sequence. Không ghi đè Neon hoặc sửa lịch sử nghiệp vụ.
- 41/41 file media/personal-content khớp byte count và SHA256. Flyway V27,
  constraint chưa validate bằng 0.
- Backup cloud quiescent gồm DB, media và JAR tại
  `/var/lib/dictalab/backups/cloud-vj_j373r`; 43 manifest entries đã kiểm tra.
  Bản off-VPS ở `target/cloud-migration-20261010/cloud-vj_j373r.tar.gz`, SHA256
  `1E5CBE5F3C2D73899B985F2611231D7E42A3325A5D18F69B9BBB5EDC3926567D`.
- Restore thật vào PostgreSQL 18 local riêng, port 15444, single transaction:
  27/27 fingerprints khớp bản backup cloud. Cluster được giữ để điều tra và
  dừng sau kiểm chứng; không phải bằng chứng restore toàn bộ S3/TURN.
- JAR mới SHA256
  `D9869DAA9BDE599120AA67932030458EB79ED680852EC45807952DCD2D2D495B`.
  Health UP, NRestarts=0 tại kiểm tra; app18082/management18183 loopback,
  Caddy443 public; metrics public401; Redis TLS PING đạt.
- Cấu hình provider/feature production được lấy từ source, giữ cloud DB,
  Linux paths, HTTPS mới; helper từ chối nếu Redis/JWT key source khác target.
  Coach/conversation không còn bị disable chỉ vì rehearsal. Queue Rabbit và
  S3 cleanup không được tự bật; không tuyên bố đã nghiệm thu chúng ở production.
- `cloud-backup.service`: Result=success, ExecMainStatus=0. Timer persistent
  chạy hằng ngày 20:15 UTC (03:15 giờ Việt Nam/Thái Lan hôm sau), trễ ngẫu nhiên
  tối đa 5 phút. Đây là **DB-only backup trên VPS**, chưa là offsite DR tự động,
  chưa có retention tự xóa; cần theo dõi dung lượng và xây retention riêng.

## Test login và tiêu chí nghiệm thu

Chạy lại bằng `./scripts/verify-cloud-auth.ps1`; tài khoản synthetic duy nhất,
mật khẩu trong bộ nhớ, logout rồi xóa đúng email/id đã tạo. Không flush Redis
hoặc reset mật khẩu người dùng. Những Redis key có TTL của probe được để hết
hạn bình thường; không tuyên bố xóa toàn bộ trace của probe.

| Kiểm tra HTTPS thực tế | Kết quả |
|---|---|
| GET login / anonymous me | 200 / 401 |
| Register / authenticated me | 201 / 200 |
| Learner gọi admin | 403 |
| Catalog `?level=A1` | 200 |
| Logout / me | 204 / 401 |
| Login / refresh / me | 200 / 204 / 200 |
| Access và refresh cookies | Secure + HttpOnly |
| Logout cuối / me / cleanup | 204 / 401 / OK |

Log: `target/cloud-migration-20261010/production-auth-smoke-final.log`.
Trình duyệt thật tải được login, không ghi nhận JS error trong lần tải ban đầu;
gửi credential synthetic không tồn tại trả thông báo “Email hoặc mật khẩu không
đúng.” thay vì lỗi request chung. Đây không phải browser success-login bằng
tài khoản thật; success-login đã kiểm tra bằng HTTP client có cookie/CSRF thật.

11/11 test Python cấu hình cloud đạt. Script PowerShell parse đạt. UTF-8 được
kiểm tra riêng khi hoàn tất. Không chạy lại toàn bộ Maven/JS trong lượt cutover;
bằng chứng regression trước đó nằm trong báo cáo migration lịch sử.

Giữ cả hai lần probe chưa đạt: lần đầu catalog thiếu `level` bị server trả500;
lần hai đọc refresh cookie ở root path nên assertion sai. Đã sửa **probe**, không
tắt bảo mật, và chạy toàn bộ flow cuối đạt. Server mapping missing query param
sang500 thay vì400 là gap API cần sửa/test ở đợt tiếp theo, không phải lỗi login.

## Giới hạn và recovery

VPS vẫn 1 GB: usable909 MiB, available khoảng180 MiB, swap dùng210 MiB tại
kiểm tra cuối. Chưa chứng minh capacity/soak P2; không dùng smoke làm bằng chứng
300 VU. Chưa chạy campaign AI/TTS thật hoặc TURN hai thiết bị trong lượt này.
Còn pending Rabbit production activation, role Neon tối thiểu/rotation secret
đã từng gửi trong chat, offsite backup tự động, Linux CI và capacity acceptance.

Sau cutover, **không bật lại DB source cũ để nhận ghi** khi Neon đã có giao dịch
mới. Recovery phải đóng ingress/đợi worker yên, backup Neon và media hiện tại,
đối chiếu hoặc reverse-sync có kiểm chứng trước khi đổi writer. JAR/config cũ
có thể lưu để phục hồi ứng dụng nhưng không được downgrade schema hoặc khôi
phục snapshot cũ đè dữ liệu mới. Bản config trước cutover được bảo vệ tại
`/var/lib/dictalab/backups/pre-cutover-config`.

Ponytail được dùng để tái sử dụng helper và chỉ thêm probe cần thiết; Vietnamese
Encoding Guard bảo vệ báo cáo UTF-8; agent-browser kiểm tra giao diện login thật.
