# Conversation AI — kết quả sửa UX ngày 05/11/2026

## Phạm vi

Triển khai theo audit 14 vấn đề trên base `2061bbe31b2219c30a03d75be34f8e39717da985`. Không đổi prompt/model, chấm điểm, database schema, quyền, CSRF, idempotency, giới hạn thử lại hoặc chính sách không tự retry UNKNOWN. Không train, deploy production hoặc tạo corpus mới.

## Đối chiếu audit

| ID | Thay đổi / kết quả |
|---|---|
| UX-01 | Thêm log theo job, bước AI, loại lỗi và HTTP status; không ghi prompt, output, raw exception hoặc credential. Lần thử thật mới dừng tại EXTRACT do HTTP 503; chưa nghiệm thu được luồng tạo bài mới. |
| UX-02 | Các nút đang bị serialize được khóa rõ ràng; Dừng nghe vẫn dùng được. Khôi phục nút và focus sau thao tác khi DOM còn nguyên. |
| UX-03 | Trạng thái theo ordinal thật và người nói; Pause nói rõ chuỗi đã dừng; native resume chỉ tiếp tục đoạn hiện tại. Cập nhật số yêu cầu phát, cleanup listener khi rời trang và giữ thông báo hoàn tất chuỗi. |
| UX-04 | Hint có vùng riêng; GET khôi phục hint đã mở từ cùng strategy, không lộ đáp án hoặc bản chép chưa được mở. |
| UX-05 | Chuyển hoạt động, chữa bài, tổng kết, preview, loading và lỗi có heading nhận focus. Replay không render lại câu trả lời. |
| UX-06 | Nháp remix lưu theo user và session; còn qua reload/review/lỗi; chỉ xóa khi EXTRACT thành công. |
| UX-07 | Lỗi tải chính có Retry GET và đường về; lỗi recommendations/nhãn mục tiêu phụ không làm mất catalog. Catalog và preferences vẫn bắt buộc hợp lệ. |
| UX-08 | Lưu brief cùng focusSkill; reload giữ kỹ năng nếu tương thích profile. Chọn gợi ý thường xóa focus cũ. |
| UX-09 | Chữa theo từng câu: câu trả lời mới nhất đã chấm, feedback, đáp án, quote và nút nghe bằng chứng cạnh nhau. Lịch sử lần thử/hỗ trợ nằm trong disclosure. UNCERTAIN giữ nghĩa chưa kết luận. |
| UX-10 | Backend trả hạn chế phát và canPlay theo đúng fingerprint cache READY. Bài cũ vẫn đọc được; cache đúng vẫn nghe được; không mở quyền tạo TTS mới. |
| UX-11 | Native audio co theo container; kiểm tra reflow ở 320, 390, 768 và 1440 px không tràn ngang. |
| UX-12 | Đổi chương trình giữ form mục tiêu mở và focus đúng thang điểm. Mục tiêu chưa lưu vẫn chặn tạo bài. |
| UX-13 | Tổng kết có Xem câu cần luyện và Luyện tương tự. Tái dùng kết quả hiện có; mở brief với focus tương thích, không tự gọi AI hoặc tự nâng band. |
| UX-14 | Nhãn mục tiêu thêm tên Giao tiếp / TOEIC Listening / IELTS Listening. |

## Kiểm tra

- Java: `mvn -q -Dtest=ScenarioEngineIntegrationTest,ScenarioContentTest,ScenarioProgramTest test` — 126 đạt (67 + 11 + 48). Bao gồm bảo toàn voice snapshot/cache, ownership, CSRF, hint không lộ đáp án, restricted cache và historical audio không hợp lệ.
- Python: `python -m pytest tests/test_scenario_workflow.py -q` tại `services/ai-generation` — 122 đạt. Có kiểm tra log chứa phase/status và không chứa nội dung exception riêng tư.
- JavaScript: `node --test src/test/js/scenario.test.cjs src/test/js/ui-regressions.test.cjs src/test/js/production-load.test.cjs src/test/js/load-contracts.test.cjs` — 97 đạt.
- Build JAR và so sánh packaged resources với source đạt; Core 18082 và AI API 8094 live/ready đạt sau restart. Dữ liệu local cũ được giữ.
- UTF-8 và `git diff --check` đạt.
- Browser thật trên tài khoản QA local: đổi chương trình, lưu mục tiêu, recommendation reload, remix reload, review, nghe bằng chứng, phát nối cache, Pause, giới hạn TTS của bài cũ và Luyện tương tự. Fixture GET 503 có nhãn riêng, chỉ tồn tại một lần trong browser; Retry phục hồi bằng GET.

Bằng chứng ngoài Git: `data/conversation-ux-fixes-20261105/screens/` và `reports/rendered-checks.json`. Ảnh có desktop/mobile, review bốn kích thước, nháp, phục hồi lỗi và audio. Helper kiểm tra chỉ dùng tài khoản QA riêng; không đưa runtime secrets hoặc dữ liệu này vào Git.

## Giới hạn còn lại

Lần gọi mới có operation `7ebd7941-e707-4d31-a57c-44ade31e3525`: `phase=EXTRACT`, `error_type=ProviderHttpError`, `http_status=503`; worker giữ UNKNOWN. Đây là lỗi HTTP thật từ upstream trong lần thử này, khác fixture tải GET. Chưa đủ bằng chứng quy lỗi GENERATE cũ cho cùng nguyên nhân, quota hay prompt. Không đổi lịch sử lỗi hoặc tự tạo lại bài để che kết quả.

Do upstream lỗi, chưa chạy lại được một bài mới từ EXTRACT tới GRADE và hoàn thành trên bản sửa. Các phép thử browser chữa bài/audio dùng bài đã lưu; test hợp đồng dùng fixture. Không thay các bằng chứng này bằng tuyên bố end-to-end thành công.

Responsive Chromium không thay thế iPhone/Android thật. Chưa chứng nhận screen reader, Bluetooth/background playback hoặc chất giọng bằng người nghe. GitHub/CI và trạng thái giao hàng được báo riêng sau push; local thành công không đồng nghĩa production đã triển khai.
