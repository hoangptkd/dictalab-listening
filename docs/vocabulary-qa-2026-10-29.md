# Kiểm tra luồng từ vựng — 29/10/2026

## Môi trường và kết luận

- Chạy local tại `http://localhost:18081/vocabulary`, Java/Spring Boot và PostgreSQL thật.
- UI dùng tài khoản QA riêng; không sửa dữ liệu học của người dùng.
- Database chạy ứng dụng: `dictalab_vocabulary_local` trên PostgreSQL 18.
- Test backend dùng database riêng `dictalab_vocabulary_qa` trên PostgreSQL 17;
  không chạy test xóa dữ liệu trên database ứng dụng.
- Phát hiện và sửa 2 lỗi có thể tái hiện. Các luồng trong bảng dưới đã thông sau sửa.
- Đây là kiểm tra chức năng local theo các kịch bản đã liệt kê, không phải bảo đảm
  không còn lỗi trong mọi điều kiện hoặc xác nhận triển khai production.

## Lỗi đã sửa

### 1. Chọn bộ khác nhưng mở lại phiên của bộ cũ

**Tái hiện:** bắt đầu ôn bộ A, để phiên dở; tạo bộ B có từ `galaxy`, bấm
“Ôn bộ này” trong B. Ứng dụng mở lại URL và câu hỏi của A.

**Nguyên nhân:** backend tìm phiên active chỉ theo user và mode, bỏ qua `deck_id`.

**Sửa:** tiếp tục phiên khi cùng phạm vi bộ. Khi chọn phạm vi khác có nội dung,
kết thúc phiên cũ cùng chế độ rồi mở đúng bộ mới. Lượt đã chấm và lịch thẻ được giữ.
Nếu bộ mới không có nội dung phù hợp, phiên cũ vẫn active để tiếp tục từ Hôm nay.

**Kiểm chứng:** test hồi quy thất bại trước sửa, đạt sau sửa; UI bộ B hiển thị
`thiên hà` và URL phiên khác. Test bao gồm cùng bộ, bộ rỗng, đổi bộ, đổi sang toàn
thư viện và bảo toàn lượt đã lưu.

### 2. CSV lỗi vẫn giữ nút xác nhận của file hợp lệ trước đó

**Tái hiện:** xem trước file A hợp lệ, đổi file B sai header, xem trước B.
Lỗi xuất hiện nhưng nút xác nhận A vẫn còn, có thể nhập nhầm dữ liệu A.

**Sửa:** xóa preview khi đổi file và trước mỗi lần xem trước; bỏ phản hồi đến muộn
của file đã bị thay thế. Giải mã UTF-8 lỗi có hướng dẫn lưu lại CSV UTF-8.

**Kiểm chứng:** UI không còn nút xác nhận ngay khi đổi file; lỗi parser không làm
nút cũ xuất hiện lại. Ba test JavaScript kiểm tra lỗi mới, phản hồi đến muộn và
byte UTF-8 không hợp lệ.

## Ma trận kiểm tra

| Chức năng | Kiểm tra đã thực hiện | Kết quả |
|---|---|---|
| Truy cập | Chuyển tới đăng nhập khi chưa có tài khoản; đăng ký QA và quay lại trang từ vựng | Đạt UI |
| Tổng quan | Thư viện rỗng, phiên không có thẻ, số lượt và lịch sử sau chấm | Đạt UI |
| Bộ cá nhân | Tạo, đổi tên/mô tả/mức B1, mở bộ và nội dung | Đạt UI |
| Mục từ | Thêm/sửa từ, nghĩa, từ loại, ví dụ, bản dịch, đáp án thay thế và nhãn tiếng Việt | Đạt UI |
| Hai chiều nhớ | Tạo nhớ từ/nhớ nghĩa, lịch độc lập; tạm ngưng và tiếp tục | Đạt UI + backend |
| Thư viện có sẵn | Xem bộ, sao chép vào bộ cá nhân, giữ nguồn; dùng chung note khi thêm nhiều bộ | Đạt UI + backend |
| Bỏ từ khỏi bộ | Gỡ membership vẫn giữ note và lịch dùng chung | Đạt backend |
| Active recall | Ẩn đáp án trước khi thử, nhập đúng, sai, không nhớ/xem đáp án | Đạt UI + backend |
| Đánh giá | Quên/Khó/Nhớ/Dễ; đáp án sai hoặc đã xem chỉ được chọn Quên | Đạt UI + backend |
| Nhập đáp án | Chuẩn hóa hoa/thường và khoảng trắng; giữ phủ định, dấu tiếng Việt, cụm từ | Đạt UI + thuật toán |
| Phiên học | Draft còn sau reload, tiếp tục, đổi bộ, hoàn thành, kết thúc phiên | Đạt UI + backend |
| Luyện thêm | Chấm và chuyển câu; hash toàn bộ hàng thẻ không đổi trước/sau; log `schedule_changed=false` | Đạt UI + SQL + backend |
| SRS | Đúng liên tiếp, học lại sau quên, giới hạn ease/interval, due từ thời điểm học thật | Đạt thuật toán |
| Giới hạn/ngày | Tối đa 10 thẻ mới; reset qua nửa đêm UTC+7; không lộ sibling trong ngày | Đạt backend |
| Câu kết hợp | Câu hai từ, dịch đúng/khác đáp án, đánh giá từng từ riêng, ưu tiên từ quên | Đạt UI + backend |
| Ví dụ cá nhân | Fallback một từ khi không có tổ hợp phù hợp, hiển thị rõ nguồn và tự đánh giá | Đạt UI + backend |
| Lịch khi dịch câu | Lưu đánh giá câu/từng từ, không tăng revision hay lịch SRS của các từ | Đạt backend |
| Từ bài đã học | Phân biệt đánh dấu không biết từ với lỗi nghe chưa xác nhận; lưu nghĩa và snapshot nguồn | Đạt UI + backend, dùng fixture bài cũ |
| Nguồn/nghĩa | Tái dùng cùng nghĩa, không ghép nghĩa khác; snapshot còn khi nguồn practice bị xóa | Đạt backend |
| CSV | Xem trước/xác nhận/xuất; hai nghĩa `bank`; tiếng Việt, BOM, dấu nháy, dấu phẩy, ô nhiều dòng | Đạt UI + backend/thuật toán |
| CSV trùng/lỗi | Nhập lại 55 dòng vẫn 55 membership; 1.000 dòng nguyên tử, rollback khi bộ không còn hợp lệ, chống công thức khi xuất | Đạt UI + backend/thuật toán |
| Phân trang từ | 55 từ: trang 1 có 50, trang 2 có 5, hợp hai trang đủ 55 từ riêng biệt; quay lại trang trước | Đạt UI |
| Phân trang bộ | 24 bộ: trang 1 có 20, trang 2 có 4, không trùng hoặc thiếu | Đạt UI, 20 bộ rỗng tạo bằng fixture SQL |
| Quản trị | Tạo bộ, thêm/sửa từ, chặn xuất bản bộ rỗng, xuất bản/ẩn và kiểm tra thư viện | Đạt UI + backend |
| Biên soạn câu | Kiểm tra surface không có trong câu bị từ chối; lưu câu đúng, nhận bộ và luyện đúng câu vừa tạo | Đạt UI + backend |
| Phân quyền | API cần đăng nhập/CSRF, chặn user thường vào quản trị, chặn truy cập dữ liệu người khác | Đạt backend |
| Toàn vẹn | 8 yêu cầu grade đồng thời cùng key chỉ ghi một log và đổi lịch một lần; từ chối key đổi payload | Đạt backend |
| Phiên cũ | Snapshot bất biến, hết hạn 24 giờ, revision lỗi sau suspend/đổi lịch | Đạt backend |
| Đổi tài khoản | Phản hồi của tài khoản/trang trước không ghi đè UI mới | Đạt JavaScript |

## Kiểm tra chạy được và bằng chứng

- Backend từ vựng: **21/21**, không lỗi, không bỏ qua.
- JavaScript toàn dự án: **348/348**, gồm **7** test từ vựng.
- Backend chọn nhóm: `VocabularyAlgorithmsTest,VocabularyIntegrationTest,VocabularyRequestFilterTest`.
- Lệnh JavaScript: `node --test src/test/js/*.cjs`.
- Lệnh backend (phải đặt `TEST_DB_URL`, `TEST_DB_USERNAME`, `TEST_DB_PASSWORD`
  tới database test riêng trước):

```powershell
mvn --batch-mode --no-transfer-progress '-Ddictalab.build.directory=target/vocabulary-qa' '-Dspring.profiles.active=postgres-test' '-Dtest=VocabularyAlgorithmsTest,VocabularyIntegrationTest,VocabularyRequestFilterTest' test
```

Log và ảnh cục bộ, không đưa runtime data vào Git:

- `target/vocabulary-qa-tests-fixed.log`
- `target/vocabulary-qa-js-all.log`
- `target/vocabulary-audit/wrong-deck-session-before.png`
- `target/vocabulary-audit/correct-deck-session-after.png`
- `target/vocabulary-audit/stale-import-before.png`
- `target/vocabulary-audit/stale-import-after.png`
- `target/vocabulary-audit/source-snapshot.png`

Sau QA, bộ/câu quản trị thử nghiệm đã ẩn khỏi thư viện công khai local; tài khoản
QA trở về USER và 20 bộ rỗng dùng cho phân trang đã được dọn. Bốn bộ có nội dung
QA được giữ riêng trong tài khoản thử nghiệm. Không sửa tài khoản người dùng.

## Phạm vi còn lại

- Chưa kiểm thử trên production, điện thoại thật, nhiều trình duyệt hoặc tải lớn.
- Bài học cũ được chuẩn bị bằng fixture; chưa chạy lại toàn bộ luồng nghe có audio
  để sinh lỗi từ mới trong đợt này.
- UI quản trị hiện cho tạo câu; chưa có màn danh sách/sửa câu đã lưu. Form tạo câu
  chỉ chọn từ đang hiển thị trên trang bộ hiện tại. Đây là giới hạn tính năng hiện có.
- AI chưa tham gia chấm dịch; cách diễn đạt khác đáp án dùng tự đánh giá. Không
  xem tự đánh giá là chứng minh bản dịch đúng về ngữ nghĩa.
- SRS được kiểm tra bằng đồng hồ cố định và trạng thái PostgreSQL, chưa theo dõi
  hiệu quả ghi nhớ thực tế qua nhiều tuần.
