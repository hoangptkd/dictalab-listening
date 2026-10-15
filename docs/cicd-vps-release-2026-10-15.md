# GitHub Actions → Linux VPS — 2026-10-15

## Phạm vi

- Phát hành các sửa giao diện ở `a2d1310` qua CI/CD hiện có.
- Đồng bộ source với các thay đổi đã chạy trên VPS: âm thanh S3, migration V34/V35,
  phân loại thư viện và 90 bài B1. Giữ nguyên 186 tài nguyên bài nghe đang phục vụ;
  đã đối chiếu SHA-256 từng file với JAR production trước phát hành.
- Profile Maven `production-s3` loại audio khỏi JAR, giữ HTML/JS/transcript.
  Gate `scripts/verify-production-jar.py` chặn việc đưa audio local trở lại hoặc
  làm thiếu trang bài học đã publish.
- Di chuyển V33/V34 PostgreSQL sang `db/vendor/postgresql`, giữ nguyên toàn bộ bytes
  và checksum đã áp dụng. H2 dùng cùng các câu lệnh dữ liệu, bỏ riêng hai thiết lập
  timeout PostgreSQL; regression kiểm tra hai phiên bản luôn tương đương phần dữ liệu.
- Sửa test xác thực để kiểm tra endpoint riêng tư `/api/auth/me` thay vì catalog
  đã công khai. Test thư viện chỉ đối chiếu các bài seed thuộc phạm vi migration,
  không coi các fixture được tạo bởi test khác là bài đã duyệt.
- Cấu hình bốn GitHub Secrets qua Chrome bằng Jev. Khóa SSH dành riêng cho CI;
  host key lấy từ cấu hình đã xác minh. Không lưu giá trị secret trong Git.
- Health công khai kiểm tra bằng HTTPS tại tên miền Worker; SSH vẫn tới VPS trực tiếp.
  Rollout dùng `release.py`, backup quiescent và tự khôi phục JAR trước nếu lỗi.

## Trước phát hành

VPS health UP. JAR: `4a2556e5a4e4db6bc10e63ed976b8206efa52046b98f4ee2e5038d4dc4127f0f`.
Database: 222 bài, 5.283 câu, 317 alias S3, 17 audio hội thoại, Flyway V35;
0 WAV trong database và 0 tham chiếu audio thiếu.

Kiểm tra cục bộ:

- Maven `-Pproduction-s3 clean verify`: đạt, 431 test, 0 lỗi, 66 test điều kiện được
  dành cho PostgreSQL/Testcontainers hoặc môi trường riêng.
- JavaScript: 239/239 đạt; 90 script bài B1 qua kiểm tra cú pháp.
- Python: 29 test, 0 lỗi, 1 kiểm tra POSIX chỉ chạy trên Linux.
- Generator B1: đạt; 215 thư viện runtime không có cảnh báo OSV đã biết.
- JAR production qua gate; UTF-8 và `git diff --check` đạt.

## Trạng thái

Đang chuẩn bị PR và chạy các gate CI PostgreSQL 17/18, concurrency, messaging,
backup/restore. Chưa xác nhận rollout production tại thời điểm ghi phần này.
Bằng chứng runtime cục bộ nằm trong `artifacts/cicd-release-20261015/`, không đưa
phiên trình duyệt, cấu hình thử hoặc thông tin xác thực vào Git.
