# V4.17 — sửa phạm vi chấm bằng hợp đồng chung

Trạng thái: candidate để kiểm thử, chưa kích hoạt. V4.15 vẫn mặc định. Không gọi AI thật trong đợt sửa này.

## Bằng chứng và phạm vi

Đợt đo trước lưu tại `C:/Users/HP/.dictalab/local/question-alignment-v416-20261115/`. V4.16 phát hiện 5/11 lỗi đã biết, sửa đúng 3/11; vẫn chấm PARTIAL cho câu trả lời đúng khi key thừa ý. Hai bản cùng chấm đúng 4/4 lượt đối chứng khi key được sửa đúng phạm vi. Không coi PASS của reviewer là bằng chứng nội dung đúng.

Đối chiếu code và output xác định:

- Schema QUESTIONS đặt assessment trước questions; QuestionDraft đặt factEn trước promptVi/answerVi. Thứ tự này không hỗ trợ hướng tạo câu hỏi → trả lời → mục tiêu mà prompt yêu cầu. Đây là giả thuyết ảnh hưởng chất lượng cần đo, không phải nguyên nhân đã được chứng minh bằng A/B.
- REPAIR cấm thêm/xóa ID nhưng không phân biệt ID mục tiêu với ID phần chấm. Trong đầu ra lỗi, AI giữ nguyên các requiredParts sai và chỉ sửa lời hỏi. Backend vốn cho phép thay nội dung requiredParts trong một meaning được cấp quyền.
- Reviewer có đáp án suy ra độc lập và partChecks; repair trước đây chỉ nhận issues với mã/lý do, không nhận đầy đủ chẩn đoán này.
- GRADE có hợp đồng trả lỗi key nhưng cả ba ví dụ đều chấm bình thường. Một ví dụ hợp lệ còn thiếu lý do được câu hỏi cung cấp trong transcript minh họa. Output thật có lúc vừa báo assessmentIssue vừa chấm điểm, bị validator từ chối.

## Thay đổi

| Thành phần | Sửa chung cho mọi tình huống |
|---|---|
| QUESTIONS | Đặt promptVi/answerVi trước factEn và assessment trong schema gửi model và ví dụ. Suy mục tiêu từ đáp án được hỏi; nếu nguồn không trả lời được câu hỏi dự kiến, chọn câu hỏi có căn cứ. Phân biệt giải thích nguyên nhân/mục đích với nhắc lại hành động. |
| REVIEW_QUESTIONS | Đặt câu trả lời suy ra và nhận xét từng part trước các cờ kết luận; yêu cầu kiểm tra xem câu trả lời đủ có bị part bắt thêm thông tin không. Cờ tổng hợp phải nhất quán với partChecks. Backend vẫn bắt part false dù các cờ khác true. |
| REPAIR_QUESTIONS | Giữ ID activity/question/meaning; cho phép thêm, bỏ, sửa và đánh lại p1..pN bên trong meaning được phép. Truyền các questionReviews thuộc nhóm được phép sửa, gồm requestedMeaningEn, answerUnitsVi và partChecks. Transcript vẫn là nguồn quyết định. |
| GRADE | Làm rõ hai kết quả loại trừ nhau, bổ sung hai ví dụ chung về key thừa/thiếu mục tiêu và sửa nguồn của ví dụ hợp lệ. Giữ nguyên validator từ chối việc vừa báo key lỗi vừa chấm; không tự bỏ phần chấm để cho PASS. |
| Phiên bản | Lưu V4.16 nguyên trạng trong kho lịch sử, thêm V4.17 vào Python evaluation và nhận diện pin của Core. Một tệp prompt được chỉnh sửa; không nhân bản prompt theo chủ đề. |

Không thêm schema dữ liệu bài học, migration, lượt AI, bộ phân loại theo từ khóa/tên nhân vật, nhánh cho Bình/marketing, cơ chế retry mới hay thư viện. Cùng prompt và schema được dùng cho mọi chủ đề/profile. Few-shot minh họa hợp đồng chung; không định tuyến theo ví dụ.

Giữ validation bằng chứng/ID, giới hạn phạm vi sửa, re-review độc lập, kết quả ngoài nhóm sửa, tổng hợp điểm bằng code, tracing, fallback, luồng học và TTS. Chính sách reviewer advisory hiện có không đổi. Bài cũ không bị sửa ngầm.

## Pin và tương thích

- Active V4.15: `f03dd35a2443d8a2ad69e9d3995de3638116b3749261f666b6f8c19f5b73ff89`.
- Frozen V4.16: `ce9b6398767cd1cdc927e0f0e442984373a05d058470e616a7d9a3a758669ab8`.
- Candidate V4.17: `6f81dd0f465c452696b0caf8df466c8603780a9db15ee3fafa47e7bdda4bd41e`.
- Harness implementation: `assessment-part-scope-review.v3`; policy và số lượt gọi giữ nguyên.
- Thứ tự schema được ghi vào fingerprint; không thay nội dung constraint của schema cũ. Thứ tự JSON là hướng dẫn cho model, không chứng minh model suy luận đúng hoặc provider luôn tuân theo thứ tự.
- Cần restart qua `scripts/run-local.ps1` trước lần evaluation tiếp theo vì backend kiểm tra fingerprint source. Không dùng worktree khác để chạy local. Không đổi active pin chỉ để test candidate.

## Kiểm chứng code và bàn giao phép đo

Các check mới kiểm tra schema truyền thực tế, không mất trường/constraint, số lượt gọi không tăng, repair nhận đúng chẩn đoán trong scope, xóa part thừa và đánh lại ID, giữ mục ngoài scope và từ chối patch vượt quyền. GRADE có issue hoặc output mâu thuẫn không tạo điểm cho người học. Tests lịch sử giữ nguyên pin cũ.

Đã đạt 84 test Python tập trung và 42 test Java; UTF-8 và `git diff --check` đạt. Python được chạy một lượt 82 test, sau đó chạy lại 22 test ở hai module có bổ sung hai kiểm tra; tổng số test khác nhau là 84. Không gọi nhà cung cấp AI trong các test này.

Lệnh chạy lại offline:

```powershell
Set-Location C:/Users/HP/Desktop/English_dictition/services/ai-generation
python -m pytest tests/test_scenario_assessment_scope.py tests/test_scenario_assessment_alignment.py tests/test_scenario_prompt_consolidation.py tests/test_scenario_assessment_prevention.py tests/test_scenario_assessment_scoped.py tests/test_scenario_evaluation.py -q
Set-Location C:/Users/HP/Desktop/English_dictition
mvn --batch-mode --no-transfer-progress -Pci-unit '-Dtest=ScenarioAssessmentTest,ScenarioHarnessTest,ScenarioContentTest' test
```

Đợt AI thật tiếp theo cần tái dùng transcript và key đã lưu, so sánh V4.15/V4.16/V4.17 bằng cùng cấu hình. Đo riêng lỗi thừa/thiếu mục tiêu, sai loại thông tin, độ chắc chắn, đáp án sai nguồn, số repair được chấp nhận và chấm oan. Có bài sạch ngoài các chủ đề đã biết; test đa ý, diễn đạt ngắn, Việt/Anh/kết hợp và RECAP. Không dùng nhận xét reviewer làm nhãn chuẩn. Giữ output/raw model/fallback/latency; tách cặp không cùng model. Không ghi đè bằng chứng cũ.

Giới hạn: test code không chứng minh tăng chất lượng AI. Model vẫn có thể bỏ sót key lỗi hoặc reviewer báo sai. Hai ví dụ từ chối và thay thứ tự không bảo đảm giải quyết hết. Chỉ cân nhắc kích hoạt sau phép đo thật và duyệt kết quả.
