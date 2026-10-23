# Bàn giao sửa teacher/judge v3

Ngày 23/10/2026. Sửa theo lô v2 nhận cuối 12/30, không đổi rubric
`conversation-semantic-v3`, giới hạn từ, schema bài, ngưỡng nhận hay quyền duyệt cuối.
Chưa gọi model thật với v3; kết quả offline không chứng minh tỷ lệ đạt tăng.

## Đã sửa

| Nhóm lỗi | Thay đổi |
| --- | --- |
| Slot, câu hỏi, đáp án lệch nhau | Teacher chốt ý hỏi và đáp án tối thiểu trước; so cùng đối tượng, thuộc tính, hai phương án so sánh và trạng thái lời nói ở cả bốn trường. |
| Nhầm taskType | Nhắc phân loại theo câu hỏi: tên phố/thời điểm vẫn là chi tiết; hành động dưới if vẫn là hành động; điều kiện phải hỏi tiền đề. A1 chọn ý được phép, không đổi nhãn giả. |
| Hỏi lý do nhưng chỉ lặp lựa chọn | Đáp án phải chứa nguyên nhân thực sự, không mượn nguyên nhân chỉ có trong evidenceEn. |
| Lặp lịch sử | So thuộc tính đã CAPTURED, không chỉ so từ/người/số mới; vẫn cho phép thuộc tính mới của cùng đối tượng. |
| Deadline và từ đa nghĩa | Dịch từ đáp án EN cuối cùng; kiểm tra by/before trong toàn bài lẫn từng fact; teacher làm rõ nghĩa khi nó đổi đáp án. |
| Thiếu trường/JSON lồng sai | Nhắc hoàn tất đủ sáu trường content và đóng reasoningEvidence trong đúng fact trước khi dịch. Validator tiếp tục loại lỗi, không sửa phản hồi. |
| B2 thiếu mẫu | Thêm ví dụ có so sánh chi phí–lợi ích, điều kiện mượn và nơi trả; dùng được cho turn 2/3 và turn 4. Ví dụ nằm ngoài dữ liệu được thu thập. |
| Web chép sai trích dẫn | Wire schema mới dùng sourceRef chọn đường dẫn trường; code lấy nguyên văn đúng Unicode/case. Không cần model tự viết lại câu trích. |
| Web nhầm evidence với đáp án | Chỉ đánh giá phạm vi đáp án trên textEn/textVi; evidenceEn có thể chứa ngữ cảnh rộng hơn. |
| Web tự tạo lỗi/lan lỗi sang check khác | Tách classification, alignment, translation và focus; điều kiện hạn chế rõ trong nguồn hỗ trợ cách diễn đạt tương đương. |
| Audit và cờ tự mâu thuẫn | factAudit xuất trước các cờ chung; taskTypeAccurate/newMeanings phải khớp tổng hợp audit cả hai chiều. |

Artifact mới: `artifacts/prompts/conversation-reviewed-collection-v3-20261023.json`.
Artifact v1/v2 và toàn bộ biên bản cũ giữ nguyên. Luồng sửa là teacher/collection offline;
không sửa prompt production Java, không deploy hoặc train.

## Hợp đồng judge mới

Campaign mới có `useIssueReferences=true`. Mỗi issue gửi qua Web chứa
`check`, `sourceRef`, `explanation`, `severity`; sourceRef phải thuộc enum các đường
dẫn đến chuỗi trong đúng `task` hoặc `context`, ví dụ `/task/slots/1/askVi`.
Không chấp nhận đường dẫn ngoài input, trích câu ví dụ rubric, hoặc field sourceQuote
theo wire format cũ trong campaign mới.

Response gốc được lưu trước kiểm định. Adapter tạo review nội bộ có sourceQuote
bằng nguyên văn field được tham chiếu, kèm `issueReferences`; responseHash vẫn là
hash response gốc. Không đổi cờ chấm, explanation, severity hoặc sửa nội dung model.
FactAudit vẫn phải có trích nguồn EN chính xác và các kiểm tra nhất quán cũ.
Trỏ đúng field chưa chứng minh lập luận đúng; Codex vẫn cần duyệt từng bài.

Hai cờ tổng hợp: có fact sai → false; không có sai nhưng có chưa chắc → null;
còn lại → true. Với taskType, so expectedTaskType với nhãn thật của từng fact.
Đây là kiểm tra tính nhất quán, không phải code tự xác định ý nghĩa câu hỏi.
Không dùng regex trên lời giải thích để tự sửa cờ hoặc chấp nhận verdict.

Ranh giới “địa điểm A, không phải B” ở 0027 vẫn cần review về rubric. V3 hướng teacher
tránh cấu trúc mập mờ: hoặc nêu rõ thay đổi thật, hoặc hỏi chi tiết không có tương phản
thay thế. Không hồi tố chuyển bài cũ thành đạt, không nới quy tắc để tăng điểm.

## Kiểm tra và chạy sau này

Kết quả offline: **121 passed**; kiểm tra UTF-8 và `git diff --check` đạt.

Lệnh kiểm tra:

```powershell
python -m pytest training/tests/test_collect_reviewed.py training/tests/test_iterate_teacher.py training/tests/test_generate_teacher_data.py -q --disable-warnings
```

HTTP trong test được mock; có kiểm tra path/case/Unicode, giữ nguyên envelope, không
gửi lại request, cờ false thiếu audit hỗ trợ, giữ artifact cũ và schema/hard validation
của ví dụ B2. Không kiểm định được năng lực hiểu nội dung của model bằng các test này.

Khi được giao chạy model thật: dùng registry DeepSeek hiện có, bridge Web một tab,
campaign diagnostic mới, không sửa manifest cũ để vượt hash. Không bật shutdown.

```powershell
python -m training.collect_reviewed prepare --campaign data/fine-tuning/diagnostic-v3-next --diagnostic --retain-diagnostic-evidence
python -m training.collect_reviewed collect --campaign data/fine-tuning/diagnostic-v3-next --limit 4
python -m training.collect_reviewed status --campaign data/fine-tuning/diagnostic-v3-next
```

Đây là lệnh bàn giao, chưa chạy trong lần sửa. Duyệt độc lập và lưu hash/quyết định
bằng adjudicate hiện có trước khi chạy tiếp. Chỉ mở rộng nếu phạm vi được giao cho phép.
Báo riêng: số lần sinh, JSON/schema/độ dài, Web hợp lệ, Web PASS, duyệt nội dung và
nhận cuối; lỗi giả, bỏ sót, lỗi hợp đồng và trường hợp chưa chắc. Không dùng lô này
làm train hoặc bằng chứng holdout độc lập; không repair/retry/sinh bù để đổi mẫu số.
