# Kiểm thử V5 trên 66 lượt local — 16/11/2026

## Phạm vi và cách đọc

Kiểm thử thực tế với V5, prompt hash `b8026ffc1dabdb09393c3b419b41750ca51ece9a242363482e3a337f4a61697b`, Gemini 3.5 Flash Lite và fallback Gemini 3.1 Flash Lite theo cấu hình hiện có. Bộ Full gồm 12 đầu vào, mỗi đầu vào chạy ba lần (36 lượt); bộ đa dạng gồm 30 tình huống khác nhau, chia đều giao tiếp, TOEIC và IELTS. Tổng cộng có 66 mã yêu cầu sinh bài khác nhau. Các mẫu không phải mẫu ngẫu nhiên đại diện cho toàn bộ người học.

Studio dừng sau 16 lượt do lỗi lưu trữ; lần tiếp tục mất luồng lưu Studio khi xử lý lượt 17. Bằng chứng phía AI cho thấy lượt 17 hoàn tất, nhưng Studio vẫn ghi RUNNING và không có kết quả chấm độc lập. Các mẫu chưa gửi được chạy qua API đánh giá nội bộ cùng V5, Harness và cấu hình fallback. Một lần chấm độc lập trả 503 sau khi bài được tạo xong; giữ kết quả chấm thiếu và tiếp tục chỉ với các bài chưa gửi. Không chạy lại các bài đã gửi. Vì các đường chạy và điều kiện thực tế khác nhau, kết quả không phải thử nghiệm đối chứng hay ước lượng độ tin cậy sản xuất.

## Kết quả

| Thước đo | Full | Đa dạng | Tổng |
|---|---:|---:|---:|
| Lượt đã gửi / kế hoạch | 36/36 | 30/30 | 66/66 |
| Bài hoàn tất | 34 | 29 | 63/66 (95,5%) |
| Bị từ chối chất lượng | 1 | 1 | 2 |
| Lỗi PermissionError phía AI | 1 | 0 | 1 |
| Bài hoàn tất có `qualityPassed=true` | 31 | 28 | 59/63 (93,7%) |
| Judge độc lập PASS | 31 | 28 | 59 |
| Judge cần xem lại | 1 | 1 | 2 |
| Thiếu kết quả judge | 4 | 1 | 5 |

Mẫu số tỷ lệ hoàn tất gồm cả ba lỗi. Trong năm kết quả judge thiếu có ba bài lỗi, một bài hoàn tất nhưng Studio mất luồng lưu và một bài hoàn tất nhưng judge trả 503. Judge là AI chưa được hiệu chuẩn với bộ đáp án do chuyên gia gán nhãn; PASS không chứng minh bài đúng về sư phạm. Với hai kết quả cần xem lại, không tự chuyển thành PASS hoặc FAIL.

## Fallback và Harness

- 18 bài có tổng cộng 18 lần chuyển model được ghi nhận; cả 18 lần đều sau HTTP 503 từ model chính, và mỗi lần model dự phòng đều trả thành công. Cả 18 bài có fallback đều hoàn tất. Đây là 18 trường hợp quan sát được, không phải thử nghiệm chủ động bao phủ mọi kiểu lỗi hay phép so sánh nhân quả với các bài không fallback.
- Trước sửa, 45/66 bản ghi có `before.hardPass=true`; chỉ số này kiểm tra cấu trúc ban đầu, không đo độ đúng nội dung.
- Harness thử sửa câu hỏi 22 lần, chấp nhận 20; thử sửa bản dịch 5 lần, chấp nhận 5. “Chấp nhận” có thể kèm cảnh báo, không đồng nghĩa mọi vấn đề đã được sửa.
- Trong 63 bài hoàn tất, bốn bài có `qualityPassed=false` dù vẫn được trả về. Ví dụ kiểm tra lại đã cảnh báo dẫn chứng câu hỏi sai đoạn hoặc bản dịch chưa tự nhiên; một bài được judge PASS dù Harness vẫn lưu cảnh báo. Hai bài bị `QualityRejected` sau khi sửa câu hỏi không được chấp nhận.
- Một kết quả judge cần xem lại nêu lo ngại có thể nhầm tên riêng với từ “bình tưới”; đây là nhận xét tự động cần người kiểm tra ngữ cảnh, không coi là lỗi đã xác minh.

Kết quả này đo pipeline sinh và đánh giá bài, không phải kiểm thử đầy đủ chức năng chấm câu trả lời của người học (`grade`) hay mọi kịch bản lỗi fallback. Chưa xác định nguyên nhân cụ thể của `PermissionError` và lỗi lưu Studio; không quy chúng cho prompt. Không triển khai hoặc kiểm chứng môi trường production trong đợt này.

## Bằng chứng local

Ledger Studio gốc `f124939b-a718-4445-813d-23a31dfea58b`, continuation `a1249be6-e524-4830-9e61-8881ccd05851`; các manifest tiếp tục và thống kê chi tiết nằm trong thư mục dữ liệu local, không đưa transcript hoặc thông tin xác thực vào Git. Tập 30 tình huống đóng băng có hash `df8faa16d4268baced2f8e0af986fb5568fe4a21ff86c9110b14920baa9edf8c`.
