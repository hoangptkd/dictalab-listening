# Kế hoạch tổ chức lại thư viện bài luyện DictaLab

Ngày lập: 14/10/2026  
Trạng thái: Đã deploy và kiểm tra production ngày 14/10/2026 theo các mốc chủ dự án duyệt: IELTS 3.0/4.0/5.0/6.0/7.0/8.0+, TOEIC 300/500/700/900+, Giao tiếp A1/A2/B1/B2/C1. 15 mức được công bố, 11 mức có nội dung và 4 mức đang bổ sung. Chi tiết trong `lesson-difficulty-release-2026-10-14.md`; bản đầu trong `lesson-library-release-2026-10-14.md` là lịch sử.

## Mục tiêu

Tổ chức phòng luyện theo cấu trúc **hướng luyện → độ khó bài → nhóm bài → bài nghe chép chính tả**. Người học chọn mức phù hợp hoặc luyện tự do. Chủ đề và tình huống là phân nhóm bên trong từng mức.

Ba mục tiêu chính:

- IELTS.
- TOEIC.
- Giao tiếp.

Tất cả bài luyện trong phạm vi này vẫn là **nghe và chép chính tả**. Không thêm mô tả tranh, trắc nghiệm, nói, thi thử hoặc dạng bài nghiệp vụ khác.

## Nguyên tắc bắt buộc

1. IELTS và TOEIC được phân theo các mốc độ khó tham khảo đã duyệt; không dùng B1 làm nhóm chính và không coi nhãn là điểm thi thật.
2. Giao tiếp dùng A1/A2/B1/B2/C1 làm điều hướng chính; với IELTS/TOEIC, CEFR vẫn là metadata bổ sung trong chi tiết bài.
3. Không tự động đổi B1 thành IELTS 6.0 hoặc TOEIC 650+.
4. Chỉ xếp bài vào khóa sau khi kiểm tra transcript và audio thực tế.
5. Nhãn IELTS/TOEIC là độ khó luyện tập tham khảo, không phải điểm dự đoán hoặc chứng nhận từ kết quả chép chính tả. Phân loại ban đầu dựa trên nội dung đã rà, chưa hiệu chuẩn theo đề thi hoặc người học.
6. Một bài có thể thuộc nhiều khóa nhưng chỉ có một bản ghi gốc, một lịch sử và một kết quả học.
7. Giữ nguyên ID bài, phiên luyện, lịch sử và quyền truy cập nội dung hiện có.
8. Giữ bố cục danh sách và khung chi tiết; các khung và nút tiếp tục dùng kiểu bo tròn hiện tại.

## Cấu trúc khóa học đề xuất

| Mục tiêu | Các khóa dự kiến | Cách ghi nhãn |
|---|---|---|
| IELTS | Band 3.0 → 4.0 → 5.0 → 6.0 → 7.0 → 8.0+ | Độ khó luyện nghe tham khảo |
| TOEIC | 300 → 500 → 700 → 900+ | Độ khó luyện nghe tham khảo |
| Giao tiếp | A1 → A2 → B1 → B2 → C1 | Theo CEFR tham khảo của bài gốc |

Các mốc TOEIC là mục tiêu tổng điểm Listening & Reading. DictaLab chỉ hỗ trợ phát triển phần nghe trong phạm vi thư viện này.

Ví dụ cấu trúc một khóa:

```text
IELTS · Mục tiêu Listening 6.0
├── 01. Nghe chính xác thông tin cụ thể
│   └── Số, ngày tháng, thời gian và tên riêng
├── 02. Tình huống đời sống và dịch vụ
├── 03. Bối cảnh học tập và thảo luận
├── 04. Câu dài và lời nói tự nhiên
└── 05. Ôn tập tổng hợp
```

Trong mỗi nhóm, bài được xếp từ ít đến nhiều thách thức dựa trên độ dài câu, từ vựng, ngữ pháp, nối âm, tốc độ nói tự nhiên và mật độ thông tin. Không dùng riêng tốc độ phát để quyết định độ khó.

## Hai cách tìm bài

### 1. Học theo lộ trình

Luồng chính:

```text
Chọn mục tiêu → Chọn khóa → Chọn nhóm bài → Bắt đầu hoặc tiếp tục bài
```

Thứ tự bài trong khóa ổn định để người học biết mình đang ở đâu. Gợi ý cá nhân có thể chỉ ra bài nên học tiếp nhưng không liên tục thay đổi thứ tự giáo trình.

### 2. Luyện tự do

Cho phép lọc theo:

- Chủ đề: du lịch, mua sắm, công việc, học tập và các chủ đề thực tế khác.
- Thời gian luyện ước tính.
- Trạng thái: chưa học, đang học, đã hoàn thành.
- Độ khó trong khóa.
- Giọng đọc và số người nói.
- Trọng tâm nghe: số liệu, nối âm, câu dài, thông tin cụ thể hoặc từ người học thường nghe lỡ.

Chỉ hiển thị bộ lọc khi metadata tương ứng đã được kiểm tra và có dữ liệu đáng tin cậy.

## Kế hoạch thực hiện

### Giai đoạn 1 — Kiểm kê nội dung (High)

- Kiểm tra transcript và audio của từng bài đang hoạt động.
- Ghi nhận chủ đề, tình huống, độ dài câu, từ vựng, ngữ pháp, nối âm, giọng đọc, số người nói và thời gian luyện.
- Đánh giá bài phù hợp với IELTS, TOEIC, giao tiếp hoặc nhiều mục tiêu.
- Lập danh sách nội dung còn thiếu trước khi công bố từng khóa.
- Tách rõ nguồn nội dung, chủ đề và trọng tâm ngôn ngữ; không dùng “ELLLO Grammar” làm chủ đề chung cho phần lớn thư viện.

Đầu ra: bảng phân loại từng bài và danh sách khóa đủ hoặc chưa đủ nội dung.

### Giai đoạn 2 — Chốt quy tắc xếp khóa (High)

- Viết tiêu chí cụ thể cho từng mức IELTS, TOEIC và giao tiếp.
- Chỉ dùng nhãn mục tiêu điểm khi nội dung đáp ứng tiêu chí đã chốt.
- Giữ CEFR làm thuộc tính phụ để tham khảo.
- Cho phép một bài thuộc nhiều khóa thông qua liên kết, không sao chép bài.
- Quy định rõ cách xử lý bài chưa phân loại: vẫn xuất hiện trong “Tất cả bài”.

Đầu ra: ma trận khóa học, nhóm bài và tiêu chí xếp bài.

### Giai đoạn 3 — Thiết kế phòng luyện (High)

Thứ tự nội dung đề xuất trên màn hình:

1. Tiếp tục học: bài đang làm và khóa tương ứng.
2. Chọn mục tiêu: IELTS, TOEIC, Giao tiếp, Tất cả bài.
3. Chọn khóa: mục tiêu, số bài và tiến độ.
4. Danh sách nhóm bài và bài học theo thứ tự.
5. Khung chi tiết: mục đích bài, chủ đề, thời lượng, số câu, nghe thử và nút bắt đầu hoặc tiếp tục.

Trên điện thoại, danh sách và chi tiết tách thành hai bước. Khi quay lại phải giữ nguyên bộ lọc, vị trí cuộn và bài đã chọn.

Đầu ra để duyệt trước khi sửa code:

- Trang chọn mục tiêu và khóa.
- Trang chi tiết khóa với nhóm bài.
- Thư viện bài có bộ lọc và khung xem trước.
- Trạng thái chưa học, đang học, hoàn thành, không có kết quả.
- Bản desktop và mobile.

### Giai đoạn 4 — Chuẩn hóa tiến độ (High)

- Hiển thị tiến độ khóa bằng số bài đã hoàn thành trên tổng số bài.
- Tách trạng thái hoàn thành, kết quả nghe chính xác và tiến độ của lần luyện lại.
- Dùng chung lịch sử khi một bài thuộc nhiều khóa.
- Giữ lối vào lịch sử, từ nghe lỡ và từ chưa biết dễ tìm nhưng không làm chật danh sách bài.
- Giải thích khi số bài của khóa thay đổi làm tỷ lệ hoàn thành thay đổi.

### Giai đoạn 5 — Triển khai dữ liệu và code (High)

- Bổ sung cấu trúc khóa → nhóm bài → liên kết bài bằng migration an toàn.
- Tái sử dụng bảng và dữ liệu bài hiện có; không thay ID bài.
- Bổ sung API danh mục theo mục tiêu và khóa.
- Bổ sung quản lý khóa, nhóm, thứ tự và metadata trong trang quản trị.
- Cập nhật giao diện HTML, CSS và JavaScript hiện tại sau khi phương án thiết kế được duyệt.
- Có phương án quay lui migration và sao lưu trước khi triển khai production.

### Giai đoạn 6 — Kiểm thử và triển khai (High)

Kiểm tra tối thiểu:

- Tiếp tục đúng phiên cũ sau khi đổi cấu trúc danh mục.
- Một bài thuộc nhiều khóa không tạo kết quả trùng.
- Luyện lại không làm mất trạng thái hoàn thành.
- Bài bị ẩn, khóa thiếu bài và bộ lọc không có kết quả.
- Nội dung riêng tư không xuất hiện trong khóa công khai.
- Bổ sung bài mới không làm mất dữ liệu tiến độ cũ.
- Điều hướng bằng bàn phím và trình đọc màn hình.
- Giao diện desktop và mobile.
- Phòng luyện cùng bạn vẫn mở đúng bài.

Sau khi vượt kiểm thử: sao lưu production, triển khai lên VPS, kiểm tra health, đăng nhập, danh mục, bắt đầu/tiếp tục bài và lịch sử thực tế.

## Thứ tự ưu tiên

1. **High:** kiểm kê nội dung và tiêu chí xếp khóa.
2. **High:** cấu trúc khóa, nhóm bài và luồng chọn bài.
3. **High:** tiến độ và tính toàn vẹn lịch sử.
4. **Medium:** bộ lọc luyện tự do và cá nhân hóa hiển thị.
5. **Low:** tinh chỉnh nội dung mô tả, nhãn phụ và hiệu ứng giao diện.

## Dữ liệu hiện đã biết

- Thư viện công khai được kiểm kê gần nhất có 23 bài hoạt động: A1 có 5, A2 có 5, B1 có 8 và B2 có 5.
- Phần lớn bài bundled đang mang topic “ELLLO Grammar”; đây là metadata chưa đủ tốt để tổ chức theo nhu cầu học.
- Số lượng hiện tại chưa đủ cơ sở để tuyên bố có đầy đủ mọi khóa IELTS và TOEIC.
- Trước khi triển khai cần rà transcript và audio từng bài; không suy luận nội dung chỉ từ tiêu đề hoặc CEFR.

## Điều kiện hoàn thành

Việc tổ chức lại được coi là hoàn thành khi:

- Người học chọn được IELTS, TOEIC hoặc giao tiếp ngay từ phòng luyện.
- Mỗi khóa có mục tiêu, nhóm bài, thứ tự và tiêu chí rõ ràng.
- Mọi bài vẫn luyện bằng nghe chép chính tả.
- IELTS/TOEIC dùng các mốc độ khó tham khảo; Giao tiếp dùng CEFR làm điều hướng chính. Mức chưa có bài phải hiển thị đang bổ sung.
- Lịch sử, kết quả, phiên đang học và quyền truy cập không bị mất hoặc nhân đôi.
- Giao diện đã được duyệt trước khi sửa code và được kiểm tra trên desktop lẫn mobile.

## Hướng dẫn cho chat tiếp theo

Đọc file này cùng `docs/current-system-reference.md`, sau đó kiểm tra code và dữ liệu hiện tại trước khi sửa. Bắt đầu từ **Giai đoạn 1 — Kiểm kê nội dung**. Không triển khai nhãn điểm IELTS/TOEIC bằng cách ánh xạ trực tiếp từ CEFR và không thêm dạng bài ngoài nghe chép chính tả.
