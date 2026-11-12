# Đối chiếu V3/V4.9 và bàn giao kiểm thử bản sửa

Ngày: 12/11/2026. Hồ sơ bàn giao sau khi sửa source; các mục “chưa test” bên dưới mô tả thời điểm bàn giao ban đầu.

**Cập nhật kiểm chứng:** Python 411/411 và Java 134/134 đạt. Gate AI thật V4.11 **không đạt**: negative lỗi hợp đồng quote, positive bị hạ hai cờ trùng ý sai. Dừng trước giai đoạn C; giữ default V3. Xem [báo cáo kiểm chứng](conversation-v4_11-quality-verification-20261112.md) và evidence riêng; chưa sửa prompt sau phép đo.

## 1. Đúng phiên bản và đúng workspace

Đây là dòng `SCENARIO_MEANING_ASSESSMENT`, không phải bộ prompt GENERATE V4/V5 cũ.
V3 đang được chọn mặc định; V4.9 là thử nghiệm đã bị gỡ khỏi runtime sau khi không đạt gate.
V4.10 cũng là thử nghiệm chẩn đoán trong dữ liệu riêng. Không ghi đè các tên/hash lịch sử.

- Workspace làm tiếp: `C:/Users/HP/.codex/worktrees/merge-conversation-gemini-20261106/English_dictition`.
- Branch: `codex/fix-scenario-provider-503-20261106`; baseline `7499292c681d343557c11749feffc2263d6bf553`.
- Không làm tiếp trong worktree `usage-limits-production-20261112`: nhánh đó không chứa pipeline assessment thử nghiệm.
- Bản sửa mới: `SCENARIO_MEANING_ASSESSMENT_V4_11`, hash `cc88eb362c99c0af4b480ce14d1caf9c3f990d192b8c970fba0b3c2bc7f0e6cd`.
- Chỉ tính hash để đồng bộ Python/Core; chưa chạy pytest, Maven, compile, kiểm tra UTF-8, browser hoặc AI.
- Chưa restart, thay pin mặc định, commit/push hay deploy bản sửa này. Không tắt máy.

## 2. Bằng chứng cũ nói gì?

| Đối chiếu | Quan sát đã lưu | Kết luận được phép |
| --- | --- | --- |
| V3 grading, 10 ca | 6 khớp kỳ vọng, 4 sai ngữ nghĩa | V3 có lỗi cho điểm đủ dù thiếu ý; không phải baseline hoàn hảo |
| Dòng V4 chấm từng part | Trên meaning map đã sửa thủ công, ba ca bỏ từng ý đều PARTIAL; một số ca khác thiếu kết quả do 503 | Cách tổng hợp bằng code đáng giữ; chưa chứng minh map tự sinh luôn đúng |
| V4.9 + Gemini 3.5 Flash Lite | F1 bị phát hiện, F2 bị bỏ sót; mẫu tốt qua | Không đủ điều kiện kích hoạt |
| V4.9 + Gemini 3.1 Flash Lite | Phát hiện phạm vi F1/F2; mẫu tốt qua; vẫn đánh COMPLETE cho đáp án ở part đã GIVEN | Có tín hiệu tốt cho tách phạm vi, chưa chứng minh đầy đủ đáp án hoặc tổng quát hóa |
| Chẩn đoán V4.10 + Gemini 3.1 | Bỏ sót F3/F4/F5; mẫu tốt qua | Thêm lời nhắc/ba cờ tổng quát chưa giải quyết được kiểm duyệt |
| V3 tạo bài sau sửa migration | QUESTIONS trả hai meanings/hai questions mỗi activity; schema local yêu cầu ít nhất ba | Lỗi đầu ra thật, không phải quota; không hạ minimum để che lỗi |

Nguồn: `conversation-scene-audio-meaning-assessment-plan-20261106.md`,
`conversation-assessment-staged-fix-plan-20261112.md`, `conversation-full-flow-retest-20261112.md`,
`usage-limits-and-p1-verification-20261112.md`; các raw/summary riêng trong
`data/conversation-meaning-assessment-20261106/{scoped-coverage-v4_9-20261112,scoped-coverage-v4_9-model31-20261112,meaning-alignment-v4_10-20261112}/`.
Không cộng kết quả khác model thành một tỷ lệ thắng; không coi 503 là lỗi nội dung hoặc kết quả đạt.

## 3. Giữ phần nào, thay phần nào?

| Thành phần | Quyết định và lý do |
| --- | --- |
| GENERATE chỉ sinh hội thoại; QUESTIONS sau đó; TRANSLATE riêng | Giữ. Không ép hội thoại phục vụ một đáp án được lập sẵn |
| Câu hỏi nhiều chi tiết; trả lời Anh/Việt/trộn | Giữ. Độ đầy đủ phụ thuộc các ý thực sự được hỏi |
| Chấm từng requiredPart, tổng hợp CAPTURED/PARTIAL bằng code | Giữ trong candidate. Một phần đúng không được tự nâng toàn meaning thành đúng |
| Grader chỉ nhận target đã chọn và lời người học | Giữ projection V4.6; không đưa transcript/đáp án mẫu để model điền hộ ý thiếu |
| Tách audit phạm vi câu hỏi; đóng băng verdict | Giữ có điều kiện. Final review không được đổi GIVEN thành REQUESTED hoặc đổi đáp án thiếu thành đủ |
| Blind parent audit | Giữ nhưng không coi là chân lý; reviewer vẫn phải đọc trực tiếp parent/parts để chỉ ra phần dư hoặc bị gộp |
| Schema tối thiểu ba meanings/ba questions gửi provider | Giữ cơ chế V4.4, chỉ bật trên candidate. V3 từng bỏ toàn bộ array bounds khỏi wire schema |
| Rule/prompt nối nhiều lớp rồi sửa bằng chuỗi replace | Viết lại rõ các stage thay đổi trong một file candidate; không thêm một khối hướng dẫn chồng lên reviewer cũ |
| Reviewer cứ trả valid=true là đủ | Không chấp nhận. ID, quote và đối chiếu được chuẩn hóa bằng code; ngữ nghĩa vẫn cần đo bằng dữ liệu thật |
| Lỗi ngữ nghĩa advisory; hợp đồng/quote/ID fail closed | Giữ chính sách hiện có; không khôi phục blanket rejection |
| Tự đổi model mạnh hơn, tăng retry hoặc chạy lại hội thoại | Không áp dụng. Việc đổi model phải là một phép so sánh riêng |

V3 có năm lời gọi bình thường cho GENERATE; candidate có bảy, tối đa mười một theo envelope hiện có.
Giữ hai audit chỉ đáng giá nếu test cho thấy giảm lỗi đủ bù chi phí. Chưa có kết quả chi phí/chất lượng của V4.11.

## 4. Source đã sửa

1. `services/ai-generation/app/scenario_prompts_meaning_v4_11.py`:
   - EXTRACT không tự gán quyền chủ trì; role cần chọn thêm phải được ghi là giả định.
   - GENERATE phát triển đủ nội dung tự nhiên; không trả câu hỏi/đáp án hoặc chèn trivia.
   - QUESTIONS chọn điều muốn kiểm tra, viết câu hỏi/đáp án, rồi định nghĩa target từ đúng phần người học cần trả lời. Không lấy cả ngữ cảnh đề làm tiêu chí chấm.
   - Giữ certainty, speech act, điều kiện và sự đồng ý; parent là tổng các part, hai lợi ích độc lập phải tách để chấm thiếu ý.
   - AUDIT_QUESTIONS trả exact `answerQuotesVi` theo part; COMPLETE không được có evidence rỗng.
   - REVIEW_QUESTIONS kiểm tra certainty/speech act riêng; ghi `uncoveredMeaningEn` và `bundledPartIds` để không lệ thuộc hoàn toàn vào blind decomposition.
   - REPAIR chỉ sửa đúng target và câu hỏi phụ thuộc; không xóa phần chấm để làm bài dễ qua.
2. `scenario_assessment_audit.py`: thêm schema riêng, whitelist input, kiểm tra mọi cặp ID, exact quote trong đáp án, diagnostics và no-upgrade normalization. Schema và hàm chuẩn hóa lịch sử được giữ.
3. `scenario_assessment.py`, `scenario_assessment_harness.py`: dispatch/hash/projection/schema cho đúng pin mới; tái dùng retry hợp đồng, deadline, artifact hashes và repair chain hiện có.
4. `ScenarioAssessment.java`, `ScenarioEngine.java`, `ScenarioOperations.java`: nhận đúng hash/version/policy mới, dùng cùng protocol 7–11 calls. Không thay release default hoặc program definitions.
5. Đã viết `test_scenario_assessment_evidence.py`, bổ sung `ScenarioAssessmentTest`, `ScenarioHarnessTest`, và thêm pin vào `ScenarioEngineIntegrationTest`. **Chưa chạy.**

Không thay UI/audio, dataset, database schema, tổng hợp điểm, model/provider, hạn mức vừa bỏ hoặc bản production.
Quote tồn tại không chứng minh quote đủ ý: validator chỉ xác minh provenance. Việc hiểu ngữ nghĩa vẫn là giả thuyết phải test.
Đây cũng là lý do [hướng dẫn Langfuse](https://langfuse.com/docs/evaluation/evaluation-methods/llm-as-a-judge)
yêu cầu đối chiếu judge với ví dụ có nhãn trước khi dựa vào kết quả của nó.

## 5. Bàn giao model kiểm thử — làm theo thứ tự

### A. Kiểm tra source và hợp đồng, chưa gọi provider

Đọc diff trên đúng branch; không reset các sửa đổi hiện có. Chạy UTF-8, `git diff --check`, rồi:

```powershell
# Tại services/ai-generation trong workspace nêu trên
python -m pytest tests/test_scenario_assessment_evidence.py tests/test_scenario_assessment_audit.py tests/test_scenario_assessment_parts.py tests/test_scenario_assessment.py tests/test_scenario_harness.py tests/test_scenario_harness_v9.py tests/test_scenario_workflow.py tests/test_contract.py tests/test_jobs.py tests/test_gemini_model_fallback.py tests/test_model_gateway.py

# Tại repository root
mvn "-Dtest=ScenarioAssessmentTest,ScenarioHarnessTest,ScenarioEngineIntegrationTest,ScenarioContentTest,AiUsageBudgetIntegrationTest" test
```

Kiểm tra thêm hash pin Python/Core, default V3, V4.7 lịch sử; COMPLETE không có quote phải lỗi,
quote từ prompt hoặc sai activity không được chấp nhận, verdict không được nâng cờ audit thất bại,
repair phải dựng ledger mới và không đổi artifact ngoài scope. Không chạy production profile.
Test mock chỉ chứng minh wiring/normalization, không chứng minh F1–F5 đã được model hiểu đúng.

### B. Hiệu chỉnh trên đầu ra cũ, không sinh lại dữ liệu

Chỉ thực hiện khi người dùng giao bước test có gọi AI. Dùng lại `frozen-inputs.json` trong
`scoped-coverage-v4_9-20261112`: hai input negative/positive, không sửa expected labels.
Không chạy nguyên script archive vì nó tham chiếu module/helper tạm của phiên cũ.

- Lấy candidate qua `a.evidence_audit_candidate`; với mỗi input, gọi AUDIT_MEANINGS,
  AUDIT_QUESTIONS rồi REVIEW_QUESTIONS đúng `a.project_input`, schema và output token budget hiện tại.
- Validate bằng `audit.validate_meanings`, `validate_question_coverage`, rồi `to_evidence_review`.
- Chốt tối đa sáu POST (ba stage × hai input), cùng model/config; tắt retry/fallback cho phép đo riêng này.
- Lưu đầy đủ input, system prompt, response schema, raw response, normalization, pin/model,
  thời gian/token/HTTP vào thư mục evidence mới. Không đưa nhãn expected vào input model.
- F1/F2: phát hiện target đề đã cho/không hỏi; kiểm tra riêng cả requestStatus và answerStatus,
  không chỉ nhìn `valid`. F3: không nâng possibility thành certainty. F4: chỉ rõ agreement bị thiếu.
  F5: chỉ đúng part chứa hai lợi ích độc lập. Positive phải được chấp nhận.
- Nếu reviewer báo sai, đọc raw trước khi sửa. Không thêm rule khớp riêng câu mẫu; không gọi lại
  để lấy một lần PASS. 429/503/unknown phải có trạng thái unavailable riêng.

### C. Chỉ sau gate B: kiểm tra sinh bài và chấm

Tái dùng đầu vào/spec đã lưu từ trace `f3220387edfa8bae7a640c541be65c42`; không regenerate EXTRACT
nếu đang đo riêng QUESTIONS. Tạo question bank từ chính dialogue đã lưu, kiểm tra đủ số lượng,
F1–F5, độ tự nhiên và độ phủ. Kiểm tra role EXTRACT trên input cũ có quan sát gán chủ trì.

So sánh V3 với candidate trên cùng input/model và tiêu chí chung: hỗ trợ nguồn, công bằng câu hỏi,
đáp án đầy đủ, chất lượng chấm, latency/tokens. F4/F5 là tiêu chí riêng của parts, V3 không có parts;
không tính V3 sai chỉ vì không có trường đó. Không so tổng số cờ PASS giữa hai schema khác nhau.

Tái dùng bộ grade đã đóng băng: blank, topic-only, thiếu một ý, đầy đủ, mâu thuẫn, Anh/Việt/trộn.
Đặc biệt: trả lời đủ câu hỏi lý do phải được CAPTURED cho target lý do; câu hỏi hai ý mà thiếu một
phải PARTIAL. Không lấy điểm tốt trên map sửa tay để kết luận map tự sinh đạt.

Nếu cần full-flow local: QA 14, đúng DB/profile local và biến QA như AGENTS.md; kiểm tra process
trước restart, giữ pin production/V3, chỉ dùng preview được phép, đăng xuất đúng phiên test.

### D. Kết thúc

Tổng hợp số ca đạt/sai/unavailable, liệt kê từng lỗi còn lại và giữ raw evidence. Không kích hoạt
V4.11 nếu thiếu bằng chứng hoặc có false acceptance/false rejection đáng kể. Chỉ sau kiểm thử
và sửa lỗi task mới commit/push task files, xác minh remote và CI theo AGENTS.md. Không merge main,
deploy, đổi default hoặc chạy hàng loạt dữ liệu khi chưa có yêu cầu tiếp theo.
