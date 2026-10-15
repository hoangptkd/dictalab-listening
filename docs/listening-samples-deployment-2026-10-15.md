# Triển khai 9 bài nghe mẫu — 15/10/2026

## Kết quả

Đã xuất bản 9 bài trong thư viện và trang bài tập trên website. Có 154 đoạn, 9 WAV, 9 MP3 và 48 câu hỏi. Đây là nội dung gốc bằng giọng Mỹ tổng hợp Cartesia; IELTS/TOEIC là nhãn mục tiêu luyện chưa hiệu chuẩn, không phải đề thi đầy đủ.

Trang bài tập: [9 bài nghe mẫu](https://dictalab.hoangptkdhy.workers.dev/audio/listening-samples-20261015/index.html).

Audio tải lên vẫn yêu cầu đăng nhập. Trang mẫu công khai hiển thị hướng dẫn đăng nhập; sau đăng nhập, từ chi tiết bài trong thư viện chọn **Nghe toàn bài & làm câu hỏi**. Câu hỏi điền thông tin IELTS và các dạng TOEIC được giữ nguyên trên trang mẫu. Bài cũng có thể luyện chép chính tả và lưu tiến độ bằng luồng thư viện hiện có.

| ID | Mục / mức | Bài |
|---:|---|---|
| 126 | IELTS 4.0 | Đăng ký lớp làm gốm |
| 127 | IELTS 5.0 | Thảo luận dự án không gian học tập |
| 128 | IELTS 6.0 | Bài giảng về đánh giá trải nghiệm bảo tàng |
| 129 | TOEIC 300 | Hỏi đáp ngắn ở văn phòng |
| 130 | TOEIC 500 | Lịch họp và giao hàng |
| 131 | TOEIC 700 | Thông báo hội nghị và thay đổi dịch vụ |
| 132 | Giao tiếp A1 | Gọi món ở quán cà phê |
| 133 | Giao tiếp A2 | Đổi giờ nhận bưu kiện |
| 134 | Giao tiếp B1 | Chọn địa điểm cho buổi gặp cộng đồng |

Thư viện công khai có 32 bài: 3 IELTS, 3 TOEIC, 26 Giao tiếp. Mỗi bài mới có đúng một liên kết course/mục. Giao tiếp A1/A2/B1/B2 có 6/6/9/5 bài; các mức IELTS/TOEIC chưa tạo bài và Giao tiếp C1 tiếp tục chờ nội dung.

## Thay đổi và bảo toàn dữ liệu

- Nhập thêm bài, câu, metadata đã kiểm tra và liên kết thư viện trong một transaction; không upsert hoặc sửa bài cũ. SQL có lock timeout 3 giây, statement timeout 15 giây, kiểm tra course và chặn phân loại chéo mục.
- Sáu mức IELTS/TOEIC vừa bổ sung dùng một nhóm **Bài nghe mẫu**. Gỡ đúng sáu nhóm khung mở rộng trống sau khi xác nhận không có liên kết. Mỗi mức ghi rõ hiện chỉ có một bài mẫu, chưa bao phủ toàn bộ dạng đề. Không giả lập độ phủ bằng cách nhân liên kết cùng bài.
- Thêm trang mẫu và JavaScript ngoài để tuân thủ CSP đang có. Chi tiết chín bài có liên kết sang trang mẫu. Giữ quyền truy cập media, CSRF, cookie và bảo vệ dữ liệu hiện tại.
- Bản phát hành chỉ đổi `library.js` và thêm bốn tài nguyên tĩnh; mọi entry backend, dependency và migration giữ nguyên. Flyway vẫn V34.
- Tài liệu nghiên cứu trong bộ mẫu ghi nhận giai đoạn tạo mẫu trước xuất bản; báo cáo này ghi nhận trạng thái triển khai sau đó.

## Sao lưu và phiên bản

Trước nhập nội dung, `pg_dump` dạng custom đã được kiểm tra bằng `pg_restore --list`:

- Backup: `/var/lib/dictalab/backups/listening-samples-20261015-ywbd52w7/database.dump`
- SHA-256: `511dc409ae200552c40578ab0077803fb5c3072c15fedd06e25d8a04c3e818c4`
- Bản sao JAR/config và backup quiescent khi phát hành giao diện: `/var/lib/dictalab/backups/release-90xefhpp`
- JAR đang chạy: `d5b94024fe5d5565dec30f7bc1f4e4e023409fb220cfd0ffbe534e3727fa155b`

## Kiểm tra đạt

- Đối chiếu trực tiếp các bảng nội dung trong backup trước nhập: toàn bộ 123 bài, 3.796 câu, 23 metadata và 48 liên kết cũ giữ nguyên. Không có bài thuộc nhiều mục khác nhau.
- HTTPS: cả 18 file WAV/MP3 đúng SHA-256; chín WAV hỗ trợ Range và trả 206 cho 44 byte đầu, đúng dữ liệu nguồn. Chín bài có metadata reviewed, số đoạn và course đúng.
- Player thật sau đăng nhập: hiển thị đủ chín thời lượng, lọc 9/3/3/3 bài, chấm đúng 48 đáp án, xử lý đáp án sai, phát audio, dừng từng đoạn và tiếp tục phát toàn bài.
- Khách chưa đăng nhập thấy hướng dẫn và không tải audio; media trả 401. USER không truy cập quản trị (403); khách không truy cập quản trị (401). JavaScript ngoài được phục vụ đúng byte dưới CSP hiện có.
- Luồng chép chính tả khởi động thành công cho bài đại diện IELTS/TOEIC/Giao tiếp; audio URL, mốc đoạn đầu, số câu và word slots đúng.
- Liên kết bài tập từ chi tiết bài thư viện hoạt động. Player không tràn ngang ở màn hình 390 px; bản offline trước đó cũng đã kiểm tra 320 px.
- Self-test công cụ tạo/import SQL, cú pháp JavaScript và kiểm tra UTF-8 đạt. Không cần build lại backend vì chỉ thêm tài nguyên tĩnh.
- Tài khoản USER kiểm thử, ba phiên luyện thử và file cookie runtime riêng đã được xóa đúng mục tiêu. Không dùng tài khoản hoặc lịch sử của người học thật để thử.

Bằng chứng: `artifacts/listening-samples-deploy-20261015/verification.json`, `backup-content-verification.json`, `browser-check.json`, `player-mobile-live.png`, `catalog-live.png`, `result.json`, `ui-manifest.json`.

Giới hạn: chưa có người học thử để hiệu chuẩn band/điểm; chưa đa dạng giọng đọc. Bộ câu hỏi chạy ở trang mẫu, chưa nhập vào mô-đun Nghe hiểu hiện yêu cầu schema trắc nghiệm riêng.
