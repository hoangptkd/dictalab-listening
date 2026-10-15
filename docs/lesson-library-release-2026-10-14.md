# Tổ chức lại thư viện DictaLab — 14/10/2026

## Phạm vi triển khai

**Đã deploy và kiểm tra bản cuối trên VPS.** JAR SHA-256: `c56ff1f8b7814479ca2fa172f17b2afba65b01dfd9545ff0b9a84eb94748ea1f`; service health `UP`, schema32. Hai bản backup/rollback: `/var/lib/dictalab/backups/release-riqld1gw` và `/var/lib/dictalab/backups/release-x0883705`. Lần cập nhật cuối chỉ thay CSS sửa nhãn chủ đề trên mobile; HTTPS/browser smoke chạy lại và đạt.

Theo kế hoạch `lesson-library-reorganization-plan-2026-10-14.md` và bản cập nhật Catalog B từ Stitch, dự án dùng cấu trúc **mục tiêu → khóa → nhóm → bài nghe chép chính tả**. Giữ bố cục danh sách và chi tiết, màu/font hiện có và khung/nút bo tròn.

| Phần | Kết quả |
|---|---|
| Điều hướng | IELTS, TOEIC, Giao tiếp, Tất cả bài; chọn khóa và nhóm; thứ tự giáo trình ổn định |
| Tìm bài | Chủ đề, trọng tâm, thời lượng ước tính, trạng thái, độ khó trong khóa; tìm tên/tình huống; phân trang |
| Chi tiết | Mục đích, chủ đề, nguồn, số câu, thời gian; giữ nghe từ vựng, bắt đầu/tiếp tục và học cùng bạn |
| CEFR | Thuộc tính tham khảo trong chi tiết; không dùng làm điều hướng chính, không quy đổi sang điểm thi |
| Tiến độ | Số bài gốc đã hoàn thành / tổng bài hiện có; giữ hoàn thành 100% khi luyện lại; lượt mới hiển thị riêng |
| Mobile | Danh sách/chi tiết hai bước; Back giữ bộ lọc, trang, bài chọn và vị trí cuộn; sửa chủ đề dài bị ép cột |
| Quản trị | Thêm/sửa khóa, nhóm, metadata, liên kết bài, mục đích/độ khó/thứ tự; bỏ liên kết giữ bài và lịch sử |
| Quyền truy cập | Khách xem trước; bắt đầu học cần đăng nhập; API quản trị cần ADMIN và CSRF; nội dung riêng tư được giữ riêng |

## Nội dung thực tế

Đã đọc transcript và kiểm tra kỹ thuật audio gốc của **23 bài công khai**, **82 file audio**: giải mã được, tổng thời lượng 2.813,83 giây. Không coi kiểm tra giải mã là xác minh đầy đủ giọng vùng miền, số người nói hoặc nối âm; các thuộc tính chưa xác minh để trống, không hiện bộ lọc tương ứng.

| Khóa đã mở | Số bài gốc |
|---|---:|
| IELTS nền tảng | 23 |
| TOEIC nền tảng | 16 |
| Giao tiếp cơ bản | 5 |
| Giao tiếp đời sống | 6 |
| Giao tiếp công việc | 6 |
| Giao tiếp tự nhiên | 8 |

Có **64 liên kết**, dùng chung 23 bài gốc. Tám khóa theo mục tiêu điểm IELTS/TOEIC hiển thị **Đang bổ sung nội dung**, chưa được công bố là giáo trình đủ nội dung. Nhãn điểm là mục tiêu học, không phải dự đoán hoặc chứng nhận. TOEIC là mục tiêu tổng Listening & Reading, thư viện này hỗ trợ phần nghe.

Quản trị phải xác nhận transcript/audio trước khi xếp bài. Khi nội dung bài thay đổi, metadata cũ hết hiệu lực; bài rời bộ lọc/khóa công khai cho đến khi được kiểm tra lại. Ghi chú kiểm tra nội bộ chỉ trả về cho ADMIN.

## Kết quả bài Đổi trả đơn hàng

Trước và sau deploy, đối soát DB xác nhận:

- Phiên hoàn thành `3e9e2dba-093d-4716-bc54-cf399b57366f` vẫn tồn tại.
- Phiên solo `cbe045a0-cc9b-415b-97cf-eb3939a79993` của bài gốc 122 đang ở câu 1.
- Catalog mới giữ `completed=true`; tiến độ lượt solo mới là 0%, tách khỏi nhãn hoàn thành 100%.
- Phiên phòng cũ không được dùng làm phiên tiếp tục của thư viện solo. Không xóa phiên phòng.
- 123 bài gốc, 1.237 phiên luyện, 68.352 lượt trả lời và 111 tài khoản giữ nguyên sau lần deploy đầu và dọn đúng tài khoản smoke test.

Không đăng nhập hoặc tạo phiên dưới danh tính của người dùng thật để kiểm chứng; dữ liệu thật đối soát bằng truy vấn chỉ đọc, logic và UI kiểm tra bằng các tài khoản/fixture riêng.

## Kiểm thử

- **41/41 backend trên PostgreSQL**: LessonLibraryIntegrationTest, PracticeServiceIntegrationTest, AuthFlowIntegrationTest, RoomControllerIntegrationTest. Migrations V31/V32 chạy trên PostgreSQL 18.4 local; VPS dùng 18.6.
- **56/56 JS liên quan**: catalog-detail và practice-input-races. Riêng **42/42 kiểm thử nhập liệu chạy trực tiếp với app.js đóng gói trong JAR deploy**.
- **9 kịch bản trình duyệt trên website thật**: thư viện, mục tiêu/khóa/nhóm, phân trang, tìm kiếm và metadata, khóa chưa đủ nội dung, khách bắt đầu học, mobile Back, browser Back, không lỗi JS chưa xử lý. Có kiểm tra độ rộng nhãn chủ đề trên mobile.
- **6 kịch bản fixture trình duyệt**: hoàn thành/luyện lại/tiến độ khóa, URL bài ở trang sau, sửa khóa, liên kết bài và metadata quản trị, không lỗi JS. Fixture không thay thế kiểm tra quyền/persistence backend.
- HTTPS smoke xác minh byte của `app.js`, `library.js`, `library.css` trùng JAR; 23 bài, 14 khóa, 6 khóa đủ nội dung, 64 liên kết; khách không nhận tiến độ cá nhân/ghi chú nội bộ; lịch sử/API quản trị và transcript nguồn được bảo vệ; Google login cấu hình enabled.
- Đăng ký → đăng xuất → đăng nhập → refresh → đăng xuất đạt trên HTTPS. Cookie Secure/HttpOnly đạt; tài khoản thử được dọn đúng ID/email sau khi thu hồi phiên.
- UTF-8 đạt cho code/migration/HTML/CSS tiếng Việt.

Không chạy lại tải 50 VU trong đợt tổ chức thư viện này. Không xác nhận pixel 100% hay thực hiện đăng nhập Google tương tác dưới tài khoản thật.

## Migration và phục hồi

Quyết định: **safe with conditions**. V31 chỉ thêm bốn bảng liên kết và index trên bảng mới; V32 chỉ thêm metadata/khóa/nhóm/liên kết đã kiểm kê. Không cập nhật/xóa lesson, transcript, session hoặc kết quả cũ.

Trước deploy: PostgreSQL 18.6, bảng lessons 139.264 byte/123 bản ghi, DB khoảng 62,9 MB, Flyway v30; không có idle transaction trên 30 giây. Flyway runtime 12.4.0. Không triển khai rolling song song: dừng service, backup quiescent, thay JAR nguyên tử rồi kiểm tra readiness. Không hứa migration không khóa; FK/index có thể lấy khóa và được giới hạn bởi timeout. Tham khảo [PostgreSQL 18 locking](https://www.postgresql.org/docs/18/explicit-locking.html).

Bản ứng dụng cũ đã thử khởi động trên schema32 local, login HTTP200. Schema bổ sung được giữ khi rollback ứng dụng; không chạy down migration phá dữ liệu. Flyway có quy tắc [ignore future migrations](https://documentation.red-gate.com/flyway/reference/configuration/flyway-namespace/flyway-ignore-migration-patterns-setting); bằng chứng khởi động nằm trong `application-rollback-check.json`.

## Bằng chứng

Thư mục: `artifacts/library-reorganization-20261014/`.

- `content-inventory.json`, `audio-audit.json`: kiểm kê nội dung/audio.
- `stitch-desktop.html`: nguồn màn B trước edit; `stitch-edit-evidence.json`: phản hồi và DOM operations Stitch đề xuất cho desktop/mobile. HTML tải từ get_screen vẫn là bản gốc; không coi đó là ảnh render mới của edit. Số liệu mẫu/nhóm CEFR trong mockup được thay bằng dữ liệu và IA đã duyệt.
- `manifest.json`, `first-deploy-manifest.json`: phạm vi thay JAR và checksum; giữ mọi entry ngoài phạm vi và toàn bộ dependency.
- `backend-pg-tests.log`, `candidate-input-regressions.log`: kiểm thử.
- `browser-results.json`, `browser-auth-fixtures.json`: kiểm tra UI; ảnh desktop/mobile/admin trong cùng thư mục.
- `predeploy.json`, `postdeploy.json`, `postdeploy-mobile.json`: nội dung gốc và dữ liệu học trước/sau cả hai lần deploy; số bản ghi gốc, phiên, lượt trả lời, tài khoản đều giữ nguyên.
- `deploy.log`, `deploy-mobile.log`, `deploy-smoke.json`, `deploy-auth-smoke.log`: release, backup/rollback và HTTPS smoke.

URL: [DictaLab](https://dictalab.hoangptkdhy.workers.dev/catalog).
