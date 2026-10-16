# Sửa hợp đồng dữ liệu và đánh giá học tập — 15/10/2026

## Phạm vi đã sửa

| Audit | Thay đổi |
|---|---|
| D01 | `SentenceState` che transcript nếu có audio, kể cả khi đọc lại receipt cũ. Giữ TTS cho bài thiếu audio, ghi rõ luyện có hỗ trợ và không tính thành evidence độc lập. Review sau hoàn thành vẫn có transcript. |
| D02 | Rate limit nhóm thao tác học theo người dùng, 600 request/phút với burst ban đầu 600; Redis/memory dùng cơ chế hiện có. Không ghi event mới cho đáp án không đổi, từ đã đúng hoặc đã reveal. Tối đa 50 lần thử khác nhau mỗi từ; vẫn dùng được hint để tiếp tục. Receipt giới hạn 500 key mới cho mỗi action trong phiên. Key đã nhận vẫn retry được khi chạm giới hạn. |
| D03 | Nhận diện audio trùng giữa các lesson ID khi bắt đầu practice. Ôn tập kiểm tra thêm phiên practice/listening trước khi coi là ngữ cảnh mới. Exposure từ trang bài mẫu dùng cùng đường ghi với preview. |
| D04 | Phiên không có từ hợp lệ bị loại khỏi evidence; không còn dữ liệu thì trả trạng thái chưa đủ dữ liệu, không gán A1/25. |
| D05 | Thời gian báo từ client bị chặn bởi thời gian thực đã trôi ở server và trần 30 phút/câu. Giao diện/export gọi đây là thời gian ước tính. Giá trị phản hồi thiếu/0 không kéo trung bình xuống 0; khi không có mẫu hợp lệ, UI hiển thị chưa có dữ liệu. |
| D06 | Review, Coach, ô phân loại và thống kê hoàn thành chỉ dùng `learner_reported_error_type` cho lời tự báo cáo. `classificationAvailable` giữ khả năng tìm và chọn phân loại dù chưa chọn loại nào. Không suy diễn dữ liệu cũ thành lời khai người học. |
| D07 | Cả hai nhánh recommendation lọc nguồn nội dung riêng tư. Gợi ý đổi mức cần ba phiên mới trong ba phiên gần đây; lặp lại bài không tự kích hoạt tăng/giảm mức. |
| D08 | Cấp/proxy managed S3 audio kiểm tra bài public, quyền admin hoặc lịch sử thuộc người yêu cầu. Nội dung personal tiếp tục chỉ dành cho admin. File dùng chung với bài public vẫn dùng được. |
| D09 | 91 trang player có thông báo luyện tự do, kết quả câu hỏi chưa lưu; ghi nhận exposure khi phát bằng endpoint có CSRF. Kiểm tra audio thuộc bài được phép xem và chống ghi trùng exposure. Chưa chuyển quiz tự học thành bài kiểm tra kín đáp án. |
| D10 | Snapshot thư viện dùng một catalog summary và một batch preview cho tất cả cấp, giảm từ tối đa 13 xuống 7 lượt SELECT nghiệp vụ. Giữ response và lọc client hiện có; không thêm cache public cho dữ liệu cá nhân. |
| D11 | Công thức v2 chuẩn hóa hint theo số từ, reveal/replay theo số câu. Điểm cũ giữ nguyên; `scoring_version` và response/export/UI nêu phiên bản. Bài không có từ không nhận điểm độc lập mặc định. |
| D12 | Thay nhãn phần trăm “Tin cậy” bằng “Mức đủ dữ liệu”; ghi rõ ước lượng nội bộ và điểm luyện tập không phải điểm thi chuẩn hóa. |

## Tính tương thích và dữ liệu cũ

- Không sửa checksum migration đã chạy. V36 thêm `scoring_version INTEGER NOT NULL DEFAULT 1`; chỉ lần hoàn thành sau nâng cấp được gắn v2. Không tính lại điểm lịch sử.
- Receipt tiếp tục là JSON theo định dạng cũ; không nén, xóa hoặc hết hạn key đã chấp nhận, để giữ retry và khả năng đọc của bản cũ. Trần ghi và giới hạn tốc độ xử lý nguy cơ tăng dữ liệu vô hạn theo action; chưa triển khai chính sách archive toàn bộ lịch sử tài khoản.
- Bộ giải mã record che `speechText` trong trạng thái có audio kể cả receipt được tạo bởi phiên bản cũ. Cấu trúc `LessonResult` thêm `scoringVersion`; `WordSlot` thêm `classificationAvailable`; frontend xử lý giá trị thiếu từ dữ liệu cũ.
- Thời gian, phân loại và first-exposure cũ không được sửa bằng phỏng đoán. Các số tổng hợp lịch sử đã lưu có thể giữ giới hạn đo lường trước đây.
- Mức khó khóa học và band mục tiêu không được đổi thành chứng nhận trình độ. Chưa có benchmark sư phạm/hiệu chuẩn tâm trắc mới.

## Kiểm tra

- Build production đầy đủ: 446 ca Java, 380 đạt, 66 bỏ qua do cần PostgreSQL/Testcontainers hoặc môi trường riêng; 0 lỗi.
- Chạy bổ sung trên bản cuối: 16 regression dữ liệu và 5 kiểm tra rate limiter đều đạt. Có kiểm tra receipt cũ che transcript, giới hạn thử vẫn dùng hint để hoàn thành, điểm v1 không bị tính lại và thư viện chỉ có 7 SELECT trong bộ query test.
- JavaScript: 247/247 đạt; script 90 bài B1 đạt gate.
- Python: 29 ca, 28 đạt, 1 bỏ qua do chỉ chạy POSIX.
- UTF-8 các file thay đổi và `git diff --check`: đạt.
- Build/profile `production-s3` và gate JAR đạt: audio S3, schema và các trang bài đã xuất bản được giữ đủ.
- Đã push bản sửa `8ccd08f` và tạo [PR #3](https://github.com/hoangptkd/dictalab-listening/pull/3). CI PostgreSQL 17/18 và reliability được kích hoạt bởi PR; xem kết quả trực tiếp trong Checks của PR. Không coi kiểm tra local là CI PostgreSQL.
- Lượt CI đầu phát hiện ba giả định kiểm thử cũ: số migration, tham số EXPLAIN và đáp án lặp trong race test. Đã cập nhật; sáu ca hồi quy với PostgreSQL/Testcontainers thật đạt, không bỏ qua. Race test kiểm tra riêng cả chống ghi trùng và không mất các đáp án khác nhau; thời gian hoàn thành dùng mốc bắt đầu ở server.

## Migration và phát hành

**Quyết định migration: safe with conditions.** V36 là thay đổi thêm cột với default hằng số, không xóa hoặc tính lại dữ liệu. CI phải kiểm chứng PostgreSQL 17/18 và H2. Chưa đo kích thước bảng/write rate production ở lượt sửa này nên không cam kết không chặn hoặc không có thời gian chờ khóa.

Trước triển khai dùng quy trình backup/release có sẵn; dừng nếu migration chờ khóa quá timeout hoặc health thất bại. Sau triển khai kiểm tra Flyway V36, health và version điểm mới. Rollback ứng dụng không cần xóa cột: bản cũ bỏ qua cột thêm; dữ liệu mới đã mang version 2 phải được giữ lại, không tự đổi về 1.

Nhánh task chỉ chạy CI khi có PR hoặc workflow dispatch theo cấu hình hiện tại; push nhánh không đồng nghĩa đã deploy. Deploy production chỉ từ `main` sau các gate bắt buộc.

## Giới hạn còn lại

- Chưa chạy lại 50 VU hoặc cuộc gọi hai người dùng trên production.
- Chưa hoàn tất kiểm thử đăng nhập production bằng tài khoản thật; phiên Chrome đã hết đăng nhập.
- Bài mẫu tiếp tục là luyện tự do chưa lưu điểm; thông báo và exposure đã được bổ sung. Tích hợp quiz vào lịch sử cần hợp đồng phiên/chấm riêng cho từng định dạng, không tự thay đổi nghiệp vụ trong đợt này.
- Thư viện giữ payload hiện có. Chưa thêm pagination server hoặc tải preview theo yêu cầu vì bộ lọc hiện đang dùng đầy đủ dữ liệu này.
