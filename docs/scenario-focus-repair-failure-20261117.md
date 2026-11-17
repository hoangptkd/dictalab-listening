# Sửa phục hồi bài thiếu câu hỏi ý chính

Tác vụ local `1d12a7b7-7cdb-4fb5-a104-44960662c1a9` kết thúc với `GENERATION_REJECTED`. Trace xác nhận validator phát hiện thiếu `main-idea` trong ngân hàng câu hỏi. Hai patch tiếp theo thêm câu ý chính nhưng đồng thời xóa toàn bộ câu hỏi/meaning của a2 và a3, vi phạm mức tối thiểu một câu hữu ích mỗi hoạt động. Validator chặn đúng. Một lần fallback sau 503 có trả kết quả; 503 không phải nguyên nhân kết thúc tác vụ này.

Lỗi trong luồng phục hồi: khi ghép patch thất bại, lần sửa kế tiếp nhận lại artifact và chẩn đoán gốc nhưng không nhận patch đã bị từ chối cùng lý do vi phạm. Trace cho thấy hai patch lỗi giống nhau.

## Thay đổi

- Khi chẩn đoán chỉ là thiếu độ phủ kỹ năng, dùng schema patch chỉ thay thế, không cho xóa các mục hiện có. Model vẫn phải viết câu hỏi, đáp án và khóa thực sự kiểm tra kỹ năng; không tự gắn lại nhãn trong mã.
- Chuyển patch bị từ chối và lỗi hợp đồng sang `formatFeedback` của lần sửa kế tiếp, chỉ khi còn cùng artifact gốc. Xóa feedback khi patch ghép thành công.
- Giữ ngân sách, deadline, xác minh nguồn, câu trả lời, phạm vi khóa và bản dịch. Các lỗi cục bộ về câu vô ích vẫn có thể xóa câu/meaning theo phạm vi V5.2. Không đổi prompt hash `5ac2eb0f3d04190bdc8e4dd5ecabf05d4f6d44f3d35edd2a7a99941a4006672b` hoặc sửa kết quả lịch sử của tác vụ.

## Kiểm chứng

- Python: **71 passed**, gồm recovery, sparse questions, schema/hash lịch sử và evaluation. Test mới xác nhận patch thiếu độ phủ không có quyền xóa; lỗi patch trước được truyền đúng sang lần kế tiếp, artifact gốc và phần ngoài phạm vi được giữ nguyên.
- Phát lại đúng transcript/ngân hàng câu hỏi đã thất bại; review và repair dùng AI thật: request `10b37859-ec1c-4d44-bcb5-6eceed962931` hoàn tất, patch được chấp nhận, đủ ba hoạt động và mỗi hoạt động còn hai câu. Các câu ý chính được viết lại theo nội dung thực, không chỉ đổi nhãn. Đây là phát lại artifact, không phải sinh lại toàn bộ nguồn.
- Lượt sinh mới từ cùng specification đã lưu cũng hoàn tất; đây là GENERATE và các bước phụ thuộc dùng AI thật, không chạy lại EXTRACT. Bằng chứng và ID nằm trong manifest riêng.
- Cả hai kết quả AI qua các cổng kiểm tra chuỗi Harness và nội dung của Core. Đây là kiểm tra cổng Core, không gắn bài mới hoặc lưu điểm vào tài khoản người dùng.

Manifest riêng: `C:/Users/HP/.dictalab/local/recheck-generation-rejected-20261117.json`. Chẩn đoán từ trace được giữ riêng trong thư mục local; không đưa mô tả cá nhân, thông tin xác thực hoặc transcript vào Git. Tiến trình local được cập nhật sau khi xác nhận không có operation/job đang chạy; tái sử dụng nguyên Core jar đang chạy để không đưa thay đổi Java ngoài nhiệm vụ vào runtime. Không merge hoặc deploy production.
