# Phục hồi bản dịch bị gắn sai mã đoạn

Tác vụ local `4935dff7-6107-49a1-b039-dc7fdbbb3a07` đã dùng bản sửa thiếu độ phủ kỹ năng, nhưng tiếp tục bị `GENERATION_REJECTED` ở lỗi khác. Trace ghi bản dịch của a3/s2 bị gắn mã a2/s2. Kết quả có hai a2/s2 và thiếu a3/s2. Validator phát hiện `translation coverage mismatch`; Harness chưa định vị lỗi này để sửa nên kết thúc trước review bản dịch. Không phải lỗi provider hoặc mô tả người dùng.

## Sửa lỗi

- Dùng danh sách mã activity/segment trong nguồn làm danh sách chuẩn. Mã nguồn bị thiếu hoặc trùng trở thành phạm vi sửa rõ ràng cho bước `REPAIR_TRANSLATIONS` hiện có.
- Yêu cầu AI dịch đúng đoạn nguồn cho từng mã bị thiếu/trùng. Không tự đoán dòng dịch thuộc hoạt động nào bằng vị trí hoặc nội dung gần giống. Patch phải trả đúng mỗi mã được cho phép một lần.
- Ghép lại bản dịch theo thứ tự nguồn; giữ nguyên các dòng có mã duy nhất ngoài phạm vi sửa. Loại các mã không tồn tại trong nguồn; khi chỉ thừa mã thì không cần gọi model sửa, nhưng vẫn review toàn bộ kết quả trước chấp nhận.
- Giữ kiểm tra cấu trúc, review ngữ nghĩa, bản dịch, giới hạn lượt sửa/deadline và dữ liệu tác vụ cũ. Không đổi prompt hash V5.2 `5ac2eb0f3d04190bdc8e4dd5ecabf05d4f6d44f3d35edd2a7a99941a4006672b`; schema patch bản dịch không thay đổi. Backend code được kiểm tra riêng qua hash runtime.

## Kiểm chứng

- Python: **75 passed** cho sparse questions, recovery, evaluation, scope và hash/schema lịch sử. Regression mới phủ mã thiếu/trùng, mã không tồn tại, chỉ thừa mã, giữ các dòng ngoài phạm vi và từ chối patch có mã ngoài phạm vi/trùng lặp.
- Phát lại đúng transcript, ngân hàng câu hỏi đã được sửa và bản dịch lỗi từ tác vụ trên; review/repair gọi AI thật. Request `147a3d4d-0937-4766-af7d-9da5f56e9b07` hoàn tất: AI trả hai dòng đúng a2/s2 và a3/s2; **10 dòng còn lại giữ nguyên**. Review AI sau sửa và cổng kiểm tra chuỗi Harness/nội dung Core đều đạt. Đây là phát lại artifact thật, không phải lượt sinh nguồn mới.
- Request `3fd85a55-22be-43a7-a254-b5d5812fdc02` sinh nguồn và các phần phụ thuộc mới với cùng specification, hoàn tất và qua cổng Core. Không chạy lại EXTRACT trong lượt này. Một lần fallback sau 503 có kết quả; lỗi cũ không bị sửa thành PASS.
- API local qua tài khoản QA 14 với cùng mô tả: EXTRACT `88648e31-77c7-41a0-bb40-0859df723866` và GENERATE `db7b80e0-52e0-436b-80d4-987af241928e` đều `SUCCEEDED`; Core nhận bài `ACTIVE`. Đã logout phiên QA. Manifest riêng `translation-coverage-core-qa.json`. Đây là một lượt kiểm chứng mới, không sửa lịch sử hoặc tạo lại job đã thất bại.

Bằng chứng và transcript riêng giữ tại `C:/Users/HP/.dictalab/local/recheck-translation-coverage-20261117.json`, không đưa vào Git. Local cập nhật phần AI sau khi xác nhận không còn operation/job đang chạy; giữ nguyên Core jar và cấu hình hiện hành. Không merge hoặc deploy production.
