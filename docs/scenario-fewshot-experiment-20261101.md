# Few-shot V5 + GRADE V4: 10 ví dụ và phép so sánh

## Phạm vi

Theo yêu cầu người dùng, tạo **10 cặp input → expectedOutput theo từng bước**, đưa vào candidate prompt rồi đo với cấu hình đang dùng. Đây là few-shot prompting: thêm minh họa trong ngữ cảnh; **không huấn luyện trọng số, không train LoRA**.

Các ví dụ do agent tự soạn từ tình huống hư cấu, chưa được con người thẩm định; không gọi là human gold. Chín ví dụ cho kết quả hợp lệ và một ví dụ reviewer phát hiện sai vai. Ví dụ sai vai nhằm kiểm chất lượng nội dung, không phải dữ liệu tấn công hay kiểm thử bảo mật.

10 cặp không phải 10 bài full pipeline. Mỗi bước chỉ nhận minh họa đúng hợp đồng của bước đó, tránh nhét toàn bộ lesson vào mọi prompt. Các đoạn hội thoại được dùng lại giữa một số cặp để minh họa sự nhất quán xuyên bước; không coi chúng là 10 cảnh độc lập.

## Danh sách ví dụ

[JSON đầy đủ gồm input và output từng ví dụ](../services/ai-generation/app/scenario_fewshot_examples_v1.json).

| # | ID | Bước | Hành vi minh họa |
|---|---|---|---|
| 1 | extract-indirect-invitation | EXTRACT | Mục tiêu nhận biết lời từ chối dẫn đến cảnh mời bạn đi triển lãm; bối cảnh/vai tự chọn nằm trong assumptions, không biến thành lớp giảng kỹ năng |
| 2 | extract-repair-staff | EXTRACT | Người học là nhân viên sửa đồng hồ; yêu cầu khách nhận trước trưa thứ Năm không chứng minh đã sửa xong |
| 3 | generate-plant-workshop | GENERATE | Hai người bạn bàn dự buổi chăm cây: lời mời, chuẩn bị, hẹn gặp; ba activity đủ từ, nhiều dữ kiện riêng, không viết câu hỏi |
| 4 | generate-camera-pending | GENERATE | Khách có chuyến đi nhưng tiệm chưa biết tiến độ; trao đổi phương án có điều kiện, không bịa đặt chỗ/cam kết |
| 5 | questions-art-supplies | QUESTIONS | Sáu nghĩa riêng từ lời mua cọ vẽ: loại cọ, kệ hàng, giá, cách rửa, phụ phí túi và quầy thanh toán |
| 6 | questions-workshop-agreement | QUESTIONS | Tách mục đích, vấn đề, giờ bắt đầu, vật cần mang, giờ gặp và mục đích thu phí; agreement có lời đề xuất và chấp nhận cùng chi tiết |
| 7 | review-questions-art-supplies | REVIEW_QUESTIONS | Tự tìm nghĩa/đáp án từng câu; vị trí kệ hàng và vị trí trả tiền là hai quan hệ khác nhau |
| 8 | translate-ferry-conditional | TRANSLATE | Đủ sáu segment; giữ điều kiện gió mạnh, khả năng hoãn và vé dùng trong ngày, không thêm sáng/tối vào giờ chưa xác định |
| 9 | review-studio-consistent | REVIEW | Duyệt ví dụ thuê phòng tập nhạc sạch: đúng vai, giờ, giá, số phòng, lời xác nhận và bản dịch |
| 10 | review-studio-role-mismatch | REVIEW | Cùng hội thoại/bản dịch tự nhiên nhưng spec gán khách thành nhân viên: SOURCE_ROLES=false, valid=false; không sửa nội dung để duyệt |

## Baseline và candidate đóng băng

- Baseline: `SCENARIO_LISTENING_V5_GRADE_V4_1`, hash `d43a3e228449dc64d7b6c103c2013558ac6e9415feabad8ff50e083900b0ae21`.
- Candidate: `SCENARIO_LISTENING_V5_FEWSHOT_1_GRADE_V4`, hash `3dbd141b3dc9f8652f3797a4cd8487b8145d167eb2ee83727913faa6610888f1`.
- Candidate chỉ thay phần EXAMPLES của sáu prompt V5. ROLE/TASK/CONTEXT/CONSTRAINTS/OUTPUT CONTRACT/INPUT được giữ nguyên.
- **GRADE giữ nguyên văn V4 ở cả hai arm**, không có ví dụ GRADE. Schemas, validators, output budgets, model, temperature và chính sách không retry giữ nguyên.
- Source: `services/ai-generation/app/scenario_prompts_fewshot.py`; benchmark mới import candidate. Active workflow/pin không đổi.

## Giao thức đo trước khi xem kết quả

1. Kiểm schema và gate cho 10 expectedOutput. Với ví dụ reviewer từ chối sai vai, schema phải đạt nhưng acceptance gate phải từ chối. Kiểm vai, quotes, số từ và bằng chứng qua validator hiện có; đây chưa thay human review.
2. Tách ví dụ minh họa khỏi 27 brief và 29 stage probes đã có. Không lấy output của bộ test làm few-shot. Bộ test cũ đã được xem nên không gọi là holdout mù mới.
3. Chạy fresh stage isolation cho `active` và `fewshot` trên cùng 29 inputs, gồm GRADE không đổi để quan sát biến thiên model.
4. Hai lượt full × 27 brief × hai arm, xoay thứ tự active/fewshot theo case; lượt hai đảo thứ tự đầu. Không retry hoặc sửa output tại chỗ; không chỉnh candidate sau khi bắt đầu gọi API.
5. Dùng lại judge pointwise cùng rubric trên các output stage có thể đánh giá. Judge dùng cùng model, chưa được người hiệu chuẩn; không dùng làm bằng chứng duy nhất hoặc release gate.
6. Lưu nguyên request/schema/output/wire/usage/error/gate và SHA inventory; replay offline. Mỗi lần gọi có evaluationRun, variant, caseId và promptHash trên tracing hiện có. Không cần tạo live Langfuse dataset hoặc thay SDK để làm phép đo này.
7. Báo riêng completion, lỗi cấu trúc/evidence, reviewer false acceptance/rejection, GRADE agreement, nội dung, tokens và UNKNOWN. Các reviewer/GRADE labels là nhãn tạm của agent, không phải accuracy thực tế.
8. Đọc lại các ca cả hai arm hoàn tất, ca candidate mới hoàn tất và reviewer bỏ lọt; không chỉ chọn sample đẹp. Các nhận xét do agent đọc được gắn nhãn tương ứng.
9. Giữ baseline đang dùng trong lúc benchmark. Không tự kích hoạt candidate chỉ vì completion cao hơn; cần xem nội dung và rủi ro reviewer/GRADE trước.

Model dự kiến theo cấu hình hiện có: `gemini-3.5-flash-lite`, temperature 0, output budgets EXTRACT 2600 / GENERATE 3200 / QUESTIONS 4000 / REVIEW_QUESTIONS 3500 / TRANSLATE 4000 / REVIEW 3500 / GRADE 1200. Manifest ghi cấu hình thực tế, không dùng model khác để che lỗi.

## Cách kiểm tra/chạy

```text
python -m pytest tools/ai-eval/test_scenario_fewshot.py tools/ai-eval/test_scenario_prompt_benchmark.py tools/ai-eval/test_scenario_quality.py services/ai-generation/tests/test_scenario_workflow.py services/ai-generation/tests/test_scenario_contract.py -q
python tools/ai-eval/scenario_prompt_benchmark.py stages --variant active --output data/<unique-active-stages>
python tools/ai-eval/scenario_prompt_benchmark.py stages --variant fewshot --output data/<unique-fewshot-stages>
python tools/ai-eval/scenario_prompt_benchmark.py paired --variants active fewshot --order-offset 0 --output data/<unique-fewshot-round1>
python tools/ai-eval/scenario_prompt_benchmark.py paired --variants active fewshot --order-offset 1 --output data/<unique-fewshot-round2>
```

Credentials chỉ nạp qua protected environment. Các lệnh `stages/paired/judge` gọi model thật; không chạy lại để đọc kết quả cũ. `compare/summarize`, validator và replay đọc capture không gọi mạng. Flags `--v4/--v5` ở compare/summarize/judge là tên slot lịch sử; manifest của arm mới ghi đúng `active/fewshot`.

## Tham chiếu thiết kế

- [Gemini prompting](https://ai.google.dev/gemini-api/docs/prompting-strategies): minh họa nhất quán và kiểm bằng thí nghiệm thực.
- [Langfuse datasets](https://langfuse.com/academy/datasets): tách input, expected output và metadata; đánh giá cả từng bước và toàn luồng.

## Kết quả

Chưa có ở thời điểm đóng băng candidate. Sẽ điền sau phép đo, giữ nguyên candidate trong suốt đợt chạy.
