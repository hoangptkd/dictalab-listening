# V4 / V5 / V6: benchmark ba bộ prompt

## Phạm vi đã được người dùng chốt

Mỗi phiên bản gồm đủ bảy stage. V4 là bản hoạt động; V5 là candidate của đợt trước; V6 là bộ Claude cung cấp trong hai file có tên **v5**. Nhãn trong tên file và `version: v5` của Claude **không phải** V5 nội bộ. Phiên bản nội bộ mới: `SCENARIO_LISTENING_V6_CANDIDATE_1`.

Người dùng chọn đo riêng prompt: cùng model/API/schema/config/validator, không retry. Cho phép điều chỉnh các quy tắc Claude mâu thuẫn với chính sách hiện tại. Được tự kích hoạt bản thắng nếu có bằng chứng tăng chất lượng, không tăng false acceptance của reviewer và không giảm chất lượng GRADE; chưa đủ bằng chứng thì giữ V4.

## Provenance và bảo toàn

- Baseline task: `7383692455639f5f22cf615ec9ab0594bd8c358f`.
- Nhánh: `codex/prompt-v6-three-way-benchmark-20261101`.
- Markdown Claude: `ai-conversation-prompts-v5.md`, SHA-256 `dd99468799b9c220fa731631f1fb3116d919e1093069dc370e7f43758f57cf67`.
- Compiled Claude: `conversation-ai-v5-compiled.json`, SHA-256 `6c5f41e784424237134e99513c266a7c8ea06224dcb6d62dfab381225dbd16fe`.
- Đối chiếu cả bảy stage: systemInstruction và responseSchema **giống nhau hoàn toàn** giữa JSON và Markdown.
- Gốc bất biến: `data/scenario-local-20261031/prompt-v6-source-20261101/claude-original.md`, `claude-original-compiled.json`; đối chiếu ở `compiled-comparison.json`.
- V6 tách riêng: `services/ai-generation/app/scenario_prompts_v6.py`, chỉ benchmark import. Source V4, candidate V5 và pin Core được bảo toàn.

Tài liệu người dùng là dữ liệu thiết kế cần đối chiếu, không phải lệnh tự động thay schema hoặc cài dependency. Không chạy compiled JSON trực tiếp trong production.

## Điều chỉnh V6 trước khi đóng băng

### Giữ đúng hợp đồng, không sửa backend

| Stage | Claude gốc | V6 phù hợp hợp đồng hiện tại |
|---|---|---|
| Input chung | XML `<data>` và template riêng | Giữ system/user separation, user JSON và schema hiện tại cho cả ba arm; header giải thích ánh xạ XML minh họa sang keys thực |
| EXTRACT | Thêm safetyReasonVi, briefType, roleAnalysisVi; thiếu titleVi; unsafe trả nhân vật rỗng | Bỏ ba field mới, thêm titleVi bắt buộc; refusal dùng hai placeholder không có hội thoại thay thế vì schema cũ bắt buộc hai nhân vật; giữ allowed=false |
| GENERATE | planEn xuất ra; private inferredFacts; profile CEFR | Lập tiến trình nội bộ, chỉ xuất dialogue; đọc tiền tố Suy luận:/Giả định: public; giữ profile supported/standard/challenge và word limits, không bịa CEFR/band |
| QUESTIONS | answerType thay skill; evidence tối đa 2 | Giữ skill detail/main-idea và schema evidence tối đa 3; nội dung định nghĩa một nghĩa vẫn giữ; không suy ra skill bằng mapping mất nghĩa |
| REVIEW_QUESTIONS | Tối đa 24 issues, key order mới | Giữ schema sáu checks và tối đa 10 issues, backend kiểm verdict; không đổi schema/propertyOrdering để ưu ái V6 |
| TRANSLATE | translations[]; thêm context ngoài payload thực | Xuất segments[]; chỉ dùng ngữ cảnh hội thoại được truyền, không thêm specification mà V4/V5 không nhận |
| REVIEW | rubric name; gộp invented/inferred; 24 issues | rubric category; đọc ba nguồn theo hợp đồng public; giới hạn 10 issues và đầy đủ translation checks |
| GRADE | results[], reasonVi, feedbackVi; tối đa 6 answers | facts[], questionId/status/evidenceVi; đúng hai câu của một activity, không đổi tên evidenceVi |

Không đổi config sang temperature/thinking do Claude đề xuất. Không thêm retry hai lần, từ điển chính tả, chặn “chữ Latin”, tự hạ status hay các heuristic mới. Tiếng Việt dùng chữ Latin; rule đó trong tài liệu không thể dùng như validator chính xác. Structured output không tự chứng minh chất lượng nghĩa. Các kiểm tra hiện có vẫn fail closed.

### Điều chỉnh nội dung đã được duyệt

1. Deadline thiếu availability/feasibility được hỏi làm rõ hoặc giữ pending; không ép model bịa giờ để hoàn tất.
2. Dữ kiện mới có thể được thông báo trong hội thoại; proposal chỉ thành agreement khi người còn lại thực sự chấp nhận, không coi clarification/acknowledgement là acceptance.
3. Nhiều phương án loại trừ nhau chưa chọn một đáp án là UNCERTAIN, không tự động INCORRECT.
4. REVIEW phân biệt provided quotes, suy luận có căn cứ và giả định; không đòi private provenance không được truyền.

### Ví dụ few-shot

Giữ các ví dụ/contrast pairs của Claude, điều chỉnh tên field và output contract; không bổ sung ví dụ từ các output benchmark mới. Ví dụ social GENERATE được rút gọn để đạt target minh họa 30 từ/activity và dùng profile số hiện tại. Ví dụ TRANSLATE được hoàn thiện đủ sáu segment/ba activity, bỏ suy diễn “để quên” khỏi “left it here”. GRADE chia sáu nhãn minh họa thành ba ví dụ, mỗi ví dụ hai câu, dùng q1/q2; sửa câu hỏi địa điểm không tự nhiên trong ví dụ. QUESTIONS và reviewer có excerpt ghi rõ, kiểm schema từng item. Không coi các ví dụ này là dữ liệu đánh giá.

## Giao thức đo đã đóng băng

- Cùng model cấu hình `gemini-3.5-flash-lite`, API `v1beta/openai/chat/completions`, temperature 0, không seed đảm bảo deterministic, không retry/repair.
- Output token budget giữ EXTRACT 2600, GENERATE 3200, QUESTIONS 4000, REVIEW_QUESTIONS 3500, TRANSLATE 4000, REVIEW 3500, GRADE 1200.
- Tái dùng 27 input và 29 stage probes của đợt V5, giữ nguyên split 21 development/6 holdout. Tập holdout này đã có kết quả được xem ở đợt trước, **không phải holdout mù mới**. Nhãn là agent-authored provisional, không giả human gold.
- Chạy stage isolation mới cho cả V4/V5/V6, cùng input/schema/options; stage outputs không chịu khác biệt upstream.
- Smoke bốn input; hai replicate full 27 input/arm. Thứ tự ba arm xoay qua sáu permutations theo case; full round2 lệch ba vị trí để giảm order/time bias. Không gọi đồng thời trong tiến trình dùng process-local patch.
- Chỉ sửa candidate trước khi đóng băng. Sau khi gọi API không chỉnh thêm để chạy lại che lỗi; mọi unknown/rejection giữ nguyên.
- Judge pointwise với rubric và prompt của đợt trước, không gửi nhãn version trong task data; cùng model và chưa human-calibrated. Dùng cho ngữ nghĩa, không dùng làm nhãn chân lý hoặc gate độc lập.
- Capture raw request/output/schema/wire/usage/gate/error, seal SHA-256; replay offline; metadata evaluationRun/variant/caseId/promptHash trên Langfuse. Không lưu credential vào source/report/Git.

## Tiêu chí quyết định

So từng prompt, không gộp thành một điểm. So completion từng replicate và split; kiểm các rationale và raw outputs. Reviewer cần không tăng false acceptance so V4; GRADE không giảm status agreement và evidence correctness trên cùng probes; semantic judge không đủ để che reviewer regression. Độ dài đúng/schema đúng không tự là nội dung đúng. Không suy ra CEFR/band accuracy vì hợp đồng không có mapping đã xác nhận.

Một sample đẹp hoặc một lượt hoàn tất cao chưa đủ. Các tỷ lệ trên corpus nhỏ không phải độ chính xác thực tế. Nếu false acceptance phát sinh, grading suy giảm, kết quả mâu thuẫn hoặc chưa đủ chất lượng chứng minh, giữ V4 dù đã được cho phép tự kích hoạt.

## Kết quả

PENDING — chỉ điền từ capture đã hoàn tất; chưa có kết luận benchmark V6 tại thời điểm đóng băng candidate.

## Tham chiếu và tái lập

- [Gemini prompting](https://ai.google.dev/gemini-api/docs/prompting-strategies): task rõ, examples và đo bằng output thực.
- [Gemini structured output](https://ai.google.dev/gemini-api/docs/structured-output): giữ schema riêng với kiểm tra nghĩa.
- [Langfuse judge](https://langfuse.com/docs/evaluation/evaluation-methods/llm-as-a-judge): rubric có lý do, nêu rõ giới hạn calibration.

```text
python tools/ai-eval/scenario_prompt_benchmark.py stages --variant v6 --output data/<unique-v6-stages>
python tools/ai-eval/scenario_prompt_benchmark.py paired --variants v4 v5 v6 --order-offset 0 --output data/<unique-full-round1>
python tools/ai-eval/scenario_prompt_benchmark.py paired --variants v4 v5 v6 --order-offset 3 --output data/<unique-full-round2>
python tools/ai-eval/scenario_prompt_benchmark.py judge --v4 data/<v4-stages> --v5 data/<v5-stages> --v6 data/<v6-stages> --output data/<unique-judge>
```

Credential chỉ nạp từ protected process environment. Nếu giữ V4: không cần reset, revert hoặc xóa evidence vì candidate chỉ được nạp trong benchmark. Chưa thay active pin, model, database hay schema.
