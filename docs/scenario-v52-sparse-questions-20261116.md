# Câu hỏi theo nội dung và suy luận tùy chọn

Đã cập nhật Harness V5.1 thành bản có pin riêng V5.2 để giữ khả năng xử lý bài/job V5.1 đã lưu. Hash V5.2: `5ac2eb0f3d04190bdc8e4dd5ecabf05d4f6d44f3d35edd2a7a99941a4006672b`. Hash và schema V5.1 cũ được kiểm tra giữ nguyên. Không merge main hoặc triển khai production.

## Thay đổi

- Một hoạt động ngắn được phép có **một câu hỏi và một meaning**. Không tạo câu lặp hoặc câu có đáp án chỉ diễn lại lời hỏi để đủ số lượng. Giới hạn tối đa và cấu trúc ba hoạt động giữ nguyên.
- Patch câu hỏi được phép xóa đúng question/meaning trong phạm vi lỗi đã xác minh. Meaning còn được câu khác tham chiếu không được xóa. Giữ ID của các câu đúng, kể cả khi xóa làm ID không còn liên tiếp. Không ép câu thay thế.
- `supported-inference` là tùy chọn: không bắt độ phủ, không sửa/từ chối câu trực tiếp hợp lệ chỉ vì nhãn suy luận khác với cách phân loại hỗ trợ. Vẫn kiểm tra câu trả lời thực tế, premise của suy luận, bằng chứng, người nói, thời gian, điều kiện, requiredParts và bản dịch.
- Review có thêm kiểm tra bắt buộc `addsInformationBeyondQuestion`. Đáp án chỉ lặp nội dung đã có trong câu hỏi tạo finding về giá trị câu hỏi, kể cả khi các cờ so sánh khác PASS. Đây là nhận xét ngữ nghĩa từ model, không phải quy tắc regex/từ khóa.
- Đồng bộ schema và validator Python/Core, đường xóa có phạm vi, đọc/chấm hoạt động một câu và catalog đánh giá. Bài đã được học không được xóa/đổi câu hỏi trong lúc GRADE; chỉ sửa khóa như trước.
- Chương trình mới: giao tiếp phiên bản 9, TOEIC/IELTS phiên bản 8. Ngân sách vẫn tối đa 17 lượt khi tạo, 7 lượt GRADE và deadline 240 giây. Không thay model pool, provider fallback, quota hoặc chính sách usage limits. Số phiên bản bỏ qua bản thử đã lưu trong local DB để giữ nguyên định nghĩa bất biến; không ghi đè hoặc xóa dữ liệu cũ.

## Kiểm chứng bằng dữ liệu đã lưu

| Ca | Kết quả kiểm tra |
|---|---|
| `toeic-basic-boxes` | AI trả patch xóa đúng `a2/q2` và meaning `m2`, không thêm câu thay thế. Áp patch lên artifact gốc và kiểm lại Python/Core: số câu **2/1/2**, `a2/q1`, các hoạt động khác, transcript và bản dịch giữ nguyên. |
| `cefr-b2-handover` | Bản thử chạy thật đã hoàn tất và được nhận dù không tạo câu suy luận mới: câu hỏi, đáp án và transcript bằng nguyên bản, không có patch để ép nhãn/độ phủ. Nội dung này cũng qua validator Core. |

Phân biệt phạm vi bằng chứng:

- Bước patch boxes dùng lỗi đã được chủ dự án xác định làm đầu vào, gọi AI sửa thật. Model đã trả đúng danh sách xóa; review AI sau patch gặp HTTP 503 sau fallback. **Không tính đây là một job Harness hoàn tất.** Kiểm chứng sau đó dùng patch thực đó qua validator và đối chiếu phạm vi trên dữ liệu gốc.
- Các lượt thử đầu vẫn có trường hợp reviewer bỏ sót câu tự lặp; vì vậy đã bổ sung trường kiểm tra thông tin mới nói trên. Bản có trường kiểm tra mới đã thử AI thật nhưng bị chặn ở review bởi 503 trước khi đi hết hai ca. Bản chốt làm rõ thêm ví dụ để không đánh đồng câu hỏi trực tiếp có đáp án cụ thể với câu tự lặp; bản này được kiểm tra lại bằng test, chưa có lượt AI thật hoàn tất trên đúng hash chốt. Kết quả handover chạy thật thuộc bản thử trước trường kiểm tra này; không trình bày như một lượt suy luận mới trên hash cuối.
- Không chạy lại cùng request đã lỗi/không rõ để thay kết quả. Các bản thử trong lúc phát triển và lỗi provider được lưu riêng, không xóa hoặc đổi thành PASS. Không có nhãn chuyên gia hay tỷ lệ chất lượng tổng quát được suy ra từ các ca này.

## Kiểm tra mã

Kết quả local: toàn bộ Python **982 passed, 27 skipped** trước lần làm rõ ví dụ cuối; chạy lại **69 passed** cho schema/pin lịch sử, recovery, câu hỏi thưa và luồng evaluation trên bản chốt. Core **223 tests, 0 failures, 0 errors, 1 skipped**, có đọc cả hai artifact đã lưu qua cổng kiểm tra nội dung thực tế. Test bỏ qua còn lại cần cờ migration riêng. Kiểm tra UTF-8 và `git diff --check` đạt.

Regression có: một câu/hoạt động; xóa câu vô ích cùng khóa; giữ ID và các câu đúng; chặn xóa ngoài phạm vi hoặc tạo meaning mồ côi; nhận toàn bộ câu trực tiếp khi focus là supported-inference; chấm hoạt động còn một câu; giữ gate bằng chứng; cờ thông tin mới không được che bởi PASS; và kiểm tra schema/hash lịch sử. Có test gọi đúng gate Core trên hai artifact local riêng, không đưa transcript vào Git.

Lệnh từ thư mục dự án:

```powershell
python -m pytest services/ai-generation/tests -q --disable-warnings
mvn -B -q '-Dtest=Scenario*Test,AiEvaluationStudioTest' test
```

Manifest và dữ liệu riêng giữ tại `C:/Users/HP/.dictalab/local/`, gồm `v52-boxes-patch-only-20261116.json`, `v52-handover-unsubmitted-20261116.json`, `v52-final-two-cases-20261116.json`, `v52-information-two-cases-20261116.json` và `v52-boxes-actual-patch-check.json`. Lượt AI chưa kiểm chứng được do provider lỗi vẫn được báo là chưa kiểm chứng. Không dùng tài khoản cá nhân, tạo tài khoản mới, thay cấu hình bảo mật hoặc đặt lại quota trong nhiệm vụ này.
