# Chuyển Redis production sang Upstash — 2026-10-11

## Kết quả

DictaLab tại `https://184.194.89.90` đã chuyển từ Layerbase sang Upstash,
dùng credential chủ dự án cung cấp qua kênh nhập ẩn và SSH stdin.
Không lưu credential trong source, báo cáo hoặc tham số tiến trình.
Kết nối dùng TLS (`rediss`), cổng 6379, database 0.

Chỉ `REDIS_URL` trong `/etc/dictalab/app.env` thay đổi; đã so sánh các key/value
với bản cấu hình trước khi chuyển. File mới giữ quyền `0640`, root:dictalab.
Ứng dụng được dừng khi đổi cấu hình và đã chạy lại. Có gián đoạn ngắn trong
khoảng này; không đo chính xác thời gian gián đoạn.

Không đổi PostgreSQL, JWT signing key, AWS credentials, feature flags,
Java heap, JAR hoặc Caddy. Không mua gói Redis, nâng gói AWS, đổi IP hay xóa VPS.
Máy hiện tại vẫn là VPS 1 GB RAM; đây không phải kết quả nâng cấp RAM.

## Bằng chứng đã kiểm tra

- Trước cutover: TLS/AUTH/PING thành công; Upstash database trống (`DBSIZE=0`).
- Sau cutover: kiểm tra độc lập TLS/AUTH/PING thành công.
- Unit test helper cấu hình: **13/13 PASS**; Python compile PASS.
- UTF-8/encoding check các file thay đổi PASS.
- So sánh fingerprint PostgreSQL trước/sau: **27 bảng và metadata schema
  giống nhau** tại thời điểm lấy mẫu; schema version 27, không có constraint
  chưa validate. Kiểm tra này không so sánh trạng thái sequence.
- **41 file** media/personal-content giữ nguyên danh sách, dung lượng và SHA-256.
- SHA-256 JAR và Caddy giống trước cutover.
- `/actuator/health` và `/actuator/health/readiness`: `UP`.
- HTTPS `/login`: **200**, không bỏ qua xác minh chứng chỉ.
- Smoke test nghiệp vụ qua HTTPS: **PASS** cho đăng ký, đăng nhập, `/me`,
  catalog A1, refresh, logout và chặn user thường truy cập admin (**403**).
- Cookie access/refresh vẫn **Secure + HttpOnly**.
- Tài khoản tổng hợp do smoke test tạo đã được dọn theo predicate chính xác;
  không xóa tài khoản thật hay flush Redis.
- Kiểm tra cuối: `dictalab.service` active, PID 76941, `NRestarts=0`;
  Caddy và `cloud-backup.timer` active.

Health indicator Redis mặc định đang **tắt** trong `application.yml`.
Probe `/actuator/health/redis` trả **404**, không phải bằng chứng Redis hỏng.
Không bật indicator hoặc thay cấu hình production ngoài phạm vi chuyển Redis.
Bằng chứng Redis ở đây là probe TLS/AUTH/PING độc lập và smoke test nghiệp vụ;
không diễn giải health tổng thể `UP` thành bảo đảm Redis khỏe.

## Thay đổi source và rule

- `scripts/linux/configure-cloud.py`: thêm chế độ chỉ thay Redis, validate URL,
  yêu cầu ứng dụng inactive, backup cấu hình cũ và thay file atomically.
- `scripts/linux/test_configure_cloud.py`: kiểm tra giữ nguyên cấu hình khác,
  không mutate input và từ chối URL không TLS, thiếu password/port, database khác 0,
  query/fragment hoặc newline. Bộ test cũng kiểm tra lỗi replace không làm mất file cũ.
- `AGENTS.md`: ghi nhận quyền tự triển khai đúng phạm vi, không hỏi lại về
  credential/rủi ro đã được chủ dự án chấp nhận. Rule vẫn giữ TLS, xác thực,
  phân quyền, validation và bảo vệ dữ liệu; không cho phép bỏ mọi biện pháp bảo mật.

## Recovery và giới hạn

Backup cấu hình trước cutover trên VPS:
`/var/lib/dictalab/backups/redis-config-fd8823436dec4ef79e83487ba6c37000.env`.
Rollback phải dừng app, khôi phục file được bảo vệ này atomically với
root:dictalab/0640 rồi khởi động lại. Chưa thực hiện rollback live.
Layerbase cũ còn nguyên; rollback về đó vẫn có thể gặp lỗi database bị ngủ.

Không chuyển các phiên Redis cũ; người dùng cần đăng nhập lại.
Không chuyển các key cache/rate-limit cũ. Không flush/xóa database Redis cũ.
Không chạy load test, kiểm tra dài hạn, quota/latency Upstash hoặc thử failure injection
trong lần chuyển này. Kết quả smoke test không bảo đảm mọi nghiệp vụ AI/audio/room
đều đã được kiểm thử lại.

Log và fingerprint cục bộ: `target/upstash-cutover-20261011/` (Gitignored).
`auth-smoke.log`, `runtime-final.log`, `db-before.json`, `db-after.json`,
`media-before.json`, `media-after.json` là các bằng chứng liên quan.
`preflight.log` thuộc lần thử Node SSH thất bại trước đó, không dùng làm bằng chứng PASS.
`post-cutover.log` ghi lần probe Redis health 404 nói trên; kết quả runtime cuối
nằm trong `runtime-final.log`.
