# DictaLab cho iPhone — bản cá nhân SwiftUI

Yêu cầu iPhone chạy iOS 16 trở lên và kết nối Internet.

Tải `DictaLab-unsigned.ipa`, ký/cài bằng AltStore với tài khoản Apple của bạn. Gói chưa ký không cài trực tiếp bằng thao tác chạm vào file.
Tài khoản Apple miễn phí cần gia hạn mỗi 7 ngày. Không gửi tài khoản Apple hoặc mật khẩu vào GitHub/chat.

Chức năng: đăng nhập/đăng ký email, chọn bài A1–B2, luyện chép từng từ, phát đoạn audio với 3 tốc độ,
gợi ý/mở đáp án, phân loại lỗi, tiếp tục bài đang dở, lịch sử/kết quả và tiến bộ. Backend và dữ liệu học dùng chung với website.

Nghe hiểu, Conversation, Coach, phòng gọi thoại và quản trị chưa có giao diện iOS trong bản đầu.

Gói này được tạo sau khi XCTest trên iPhone Simulator và build Release arm64 thành công.
Chưa xác nhận việc ký/cài và phát âm thanh trên iPhone vật lý của chủ máy.

Kèm SHA-256, ảnh khởi chạy simulator, log build/test và kết quả XCTest `.xcresult` (mở bằng Xcode trên Mac).
Hướng dẫn cài chi tiết nằm trong `ios/README.md` của cùng commit.
