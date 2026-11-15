# Conversation AI hiện hành

## Bản đang chỉnh sửa

Một nguồn prompt: `services/ai-generation/app/scenario-prompts.json`.
V4.15 được chủ dự án duyệt làm bản chính thức ngày 15/11/2026,
hash `f03dd35a2443d8a2ad69e9d3995de3638116b3749261f666b6f8c19f5b73ff89`.
Phê duyệt này không thay kết luận kiểm thử: vẫn có lỗi phạm vi chấm gây chấm thiếu điểm;
chưa chứng minh chất lượng tăng. V4.13/V4.14 được giữ nguyên trong kho lịch sử.
Pin mặc định Core nằm ở `scenario/workflow-v3.sha256`. Các chương trình được duyệt mới:
giao tiếp phiên bản 5, TOEIC/IELTS phiên bản 4; định nghĩa cũ giữ nguyên để đọc/chấm bài đã lưu.
Production chỉ được coi là cập nhật khi release và runtime đã xác minh, không chỉ vì đổi pin source.
Xem [kết quả và bàn giao V4.15](scenario-v415-verification-handoff.md).

`scenario_prompts.py` nạp bản hiện hành. `scenario-prompt-history.json.gz` giữ giá trị đã biên dịch của
các bản cũ, được kiểm tra SHA-256 khi đọc. Kho này chỉ phục vụ tương thích bài đã lưu, pin cũ và benchmark;
không phải nhiều bộ prompt đang cùng được phát triển. Tests đối chiếu hash prompt/schema trước và sau khi gộp.
Không xóa kho lịch sử hoặc sửa hash bài cũ để làm chúng khớp prompt mới.

## Luồng thực tế

```mermaid
flowchart TD
    UI[Conversation: mục đích và mức mục tiêu] --> Draft[EXTRACT: specification]
    Draft --> Confirm[Người học xác nhận]
    Confirm --> Generate[GENERATE: hội thoại]
    Generate --> Questions[QUESTIONS: câu hỏi, đáp án, targets, evidence]
    Questions --> RQ[REVIEW_QUESTIONS]
    RQ --> Translate[TRANSLATE]
    Translate --> Review[REVIEW]
    Review --> Save[Lưu bài và provenance]
    Save --> Listen[Nghe từng đoạn hoặc toàn hoạt động]
    Listen --> Answer[Trả lời Việt, Anh hoặc kết hợp]
    Answer --> Grade[GRADE và tổng hợp bằng code]
    Grade --> Complete[Chữa bài, hoàn thành, luyện tiếp]
```

- Frontend chính: `static/js/conversation.js`, sử dụng Scenario API khi chương trình được mở.
- Catalog, mục tiêu và profile: `ScenarioController`, `ScenarioProgramRegistry`, `ScenarioPersonalization`.
- Draft/session/audio/answer/state transitions: `ScenarioEngine`, `ScenarioOperations`, `ScenarioData`.
- Admission/version/approval: `ScenarioRelease`. Profile TOEIC/IELTS dùng scale riêng; giao tiếp dùng CEFR.
- AI API/worker: `services/ai-generation/app`; job ledger giữ idempotency và kết quả chưa rõ.
- Hợp đồng API: [Scenario engine](scenario-engine-openapi.yaml), [foundation](scenario-foundation-openapi.yaml).

V4.15 sinh bài qua năm bước, không gọi `AUDIT_MEANINGS` hoặc `AUDIT_QUESTIONS`.
EXTRACT trước đó là một lượt riêng; GRADE chạy khi người học trả lời.
Luồng không sửa cần 6 lượt cho EXTRACT + sinh bài, giảm 2 so với V4.12. Envelope sinh bài tối đa 9 lượt;
đây là số lời gọi logic, không phải số HTTP attempts hoặc hóa đơn.

## Hợp đồng cần giữ

- Ba activity; cấu trúc, ID, references, bằng chứng và provenance được validation bằng code.
- V4.15 cho phép 2–6 câu hỏi và mục tiêu chấm mỗi activity; các pin trước giữ tối thiểu 3.
- Hội thoại giữ dữ kiện/vai đã xác nhận và phân biệt đề xuất, đồng ý, giả định, điều chưa chắc chắn.
- Câu hỏi/đáp án/targets cùng phạm vi; thông tin có sẵn trong câu hỏi không thành yêu cầu chấm.
- Câu hỏi nhiều ý hợp lệ. Đúng địa điểm nhưng sai giờ trong câu hỏi hỏi cả hai → `PARTIAL`.
- Hỏi nơi nào thì chấm địa điểm; không yêu cầu lý do ngoài câu hỏi. Cho phép trả lời Việt/Anh/kết hợp.
- Review ngữ nghĩa là advisory theo chính sách đã chọn: lưu cờ và kết quả, có sửa giới hạn;
  không biến việc JSON hợp lệ hoặc `qualityPassed` thành chứng nhận nội dung đúng.
- Lỗi cấu trúc/bằng chứng không được xóa chỉ để bài dễ đạt. Không loại bài riêng vì giới hạn từ.
- Không replay công việc paid có kết quả UNKNOWN. Model fallback giữ nguyên policy hiện có.
- TTS, phân vai, continuous activity playback, tracing và quyền sở hữu dữ liệu giữ nguyên.

## Tương thích

`ConversationService` và prompt Java còn cần cho các phiên legacy đã lưu và luồng tiếp tục của chúng.
Không dùng chúng làm pipeline đánh giá mới. Java V8 trong Evaluation Studio đã nghỉ tạo/chạy mới;
phiên bản và kết quả cũ vẫn đọc, so sánh và xuất được. Không xóa bảng legacy hoặc sửa nội dung lesson.

Mặc định Core là V4.15; approval gates vẫn yêu cầu đúng hash prompt và chương trình trong runtime.
Local dùng cấu hình đã xác minh, xem [chạy local](local-development.md).

## Đánh giá chất lượng

[AI Evaluation Studio](ai-evaluation-studio.md) chỉ mở pipeline/prompt hiện hành cho lần chạy mới.
[Lịch sử và quyết định benchmark](project-history.md) ghi vì sao giữ V4.13 thay vì kích hoạt candidate V4.14.
Tests code đạt không đồng nghĩa chất lượng ngữ nghĩa AI đã tăng. Giữ nguyên raw output trước khi thử sửa prompt tiếp.

V4.15 ưu tiên phòng lỗi bằng prompt, few-shot và ngữ cảnh nguồn. Chỉ sửa hai đường dispatch cuối:
dịch có lỗi cục bộ không bị cờ tổng chặn; câu hỏi và mục tiêu chấm được sửa trong cùng phạm vi phụ thuộc.
Policy riêng `scenario-meaning-harness.v3` giữ advisory và số lượt gọi cũ.
Không có tự sửa hội thoại/specification, không tăng retry, không đổi fallback hoặc kích hoạt production.
