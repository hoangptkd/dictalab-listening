# V4 / V5 / V6: benchmark ba bộ prompt

> Cập nhật sau benchmark: người dùng đã yêu cầu dùng V5. Code chuyển sang đúng bản đã đo `SCENARIO_LISTENING_V5_CANDIDATE_1`, hash `6a3a61aa0762dce1e0898bec5f1cf46b5ae6189dddbe3015828a35667ebe6f8f`. Quyết định giữ V4 dưới đây là kết luận tại thời điểm benchmark; yêu cầu mới thay thế quyết định kích hoạt đó. Các số đo và lỗi đã ghi được giữ nguyên, gate không thay đổi.

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
- Candidate đóng băng tại commit `868f78e`; V6 prompt hash `42f7d5e6cca9b6adfd5cbd277b9d8df129d582166ece011d34247de3d26d86a4`. Không chỉnh candidate sau khi bắt đầu gọi API.

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

### Stage isolation mới, cùng input cho cả ba bộ

87 call thật, mỗi arm 29; tất cả HTTP 200. Đối chiếu bằng harness xác minh cùng model/API/config/schema/user input/token budget/retry=0. Các số khớp nhãn chỉ là agreement với nhãn tạm của agent, không phải độ chính xác do người đánh giá.

| Metric | V4 | V5 | V6 |
|---|---:|---:|---:|
| EXTRACT schema / gate | 4/4 / 3/4 | 4/4 / 4/4 | 4/4 / 2/4 |
| GENERATE schema / gate | 2/2 / 2/2 | 2/2 / 2/2 | 2/2 / 2/2 |
| QUESTIONS schema / grounding gate | 1/1 / 1/1 | 1/1 / 1/1 | 1/1 / 1/1 |
| REVIEW_QUESTIONS phát hiện defect | 3/4 | 4/4 | 3/4 |
| REVIEW_QUESTIONS false acceptance | 1/4 | 0/4 | 1/4 |
| REVIEW_QUESTIONS false rejection clean | 0/1 | 0/1 | 0/1 |
| TRANSLATE schema / coverage | 2/2 / 2/2 | 2/2 / 2/2 | 2/2 / 2/2 |
| REVIEW phát hiện defect | 4/4 | 3/4 | 4/4 |
| REVIEW false acceptance | 0/4 | 1/4 | 0/4 |
| REVIEW false rejection clean | 0/1 | 0/1 | 0/1 |
| GRADE status khớp nhãn tạm | 18/20 | 18/20 | **19/20** |
| Tổng input + output tokens | 39.290 | 46.301 | **74.495** |
| Tổng giây chờ response | 65,114 | 65,642 | 93,239 |

Schema và gate không thay thế semantic quality. Timing là quan sát từng lượt, không phải latency experiment có kiểm soát. Số mẫu đặc biệt QUESTIONS còn ít; không suy ra accuracy thực tế hoặc CEFR compliance.

V6 cải thiện probe `grade-partial/q1`: `Tomorrow` cho câu hỏi cần buổi nhận bánh được chấm PARTIAL thay vì CAPTURED. `It opens.` khi hỏi mấy giờ vẫn bị chấm PARTIAL, trong khi nhãn tạm MISSED. English/Việt/mixed/paraphrase/typo/incorrect/blank/acknowledgement/uncertain probes còn lại khớp nhãn; evidence qua validator nguyên văn. Chưa có human adjudication để coi 19/20 là accuracy thật.

V6 bắt được `review-source-role` mà V5 lại bỏ sót: user là khách đặt bánh nhưng specification gán cả hai nhân vật là người bán. Tuy nhiên V6 và V4 cùng bỏ sót `review-questions-leak`: câu hỏi “Cửa hàng mở lúc chín giờ, đúng không?” đã lộ đáp án nhưng vẫn được valid=true/noAnswerLeak=true. Few-shot có ví dụ answer leakage chưa đủ bảo đảm phát hiện lỗi thực.

Hai EXTRACT V6 bị chặn vì quote không tồn tại nguyên văn. Ở `extract-inference-03`, model tự viết “luyện tập kỹ năng suy ra điều người nói cần” vào quote thay vì trích đúng input. Ở `extract-food-allergy`, model trích “nhân viên phục vụ” không có trong nguồn và đặt titleVi “Gọi món ăn và dị ứng đậu phộng”, dù nguồn chỉ nói không ăn được đậu phộng. Raw lỗi được giữ, không sửa quote, không nới validator và không retry.

Đối chiếu title cùng probe: V4 dùng “Hỏi về món ăn và đổi món tại quán ăn”; V5 dùng “Gọi món ăn và hỏi về thành phần dị ứng”. V5 cũng đưa khái niệm dị ứng vào title, nên rủi ro diễn giải quá mức không chỉ xuất hiện ở V6; gate provenance đạt chưa chứng minh mọi diễn giải trong title đều đúng.

### Pipeline smoke/full và judge

Hai lượt full, replay và judge đã hoàn tất; quyết định giữ V4 ở cuối báo cáo.

Smoke mới: cả ba arm **1/4** completion. Đây không phải cùng bài thành công: V4 hoàn tất food-allergy, V5 main-idea-01, V6 inference-03. V6 dùng 71.968 tokens so V4 22.126 và V5 23.998; số call lần lượt 22/14/15, nên chưa thể diễn giải tỷ lệ tổng token như chi phí của cùng nội dung. Replay smoke V4 7/7, V5 8/8, V6 8/8 phase outcomes khớp, zero network.

Một V6 smoke `food-allergy` dừng ở REVIEW schema vì `translationChecks[8].reasonVi` quá 240 ký tự, không phải HTTP error; raw JSON vẫn được lưu để kiểm bằng Pydantic offline. Không cắt reasonVi để ép đạt.

#### Full round 1: 27 input/arm

| Metric | V4 | V5 | V6 |
|---|---:|---:|---:|
| EXTRACT qua gate, kể cả allowed=false | 20/27 | 27/27 | 27/27 |
| EXTRACT cho phép chuyển sang authoring | 18/27 | 27/27 | 27/27 |
| Pipeline hoàn tất | **8/27** | **13/27** | **9/27** |
| Development hoàn tất | 5/21 | 11/21 | 6/21 |
| Reused holdout hoàn tất | 3/6 | 2/6 | 3/6 |
| Model calls, tất cả HTTP 200 | 96 | 124 | 124 |
| Input + output tokens | 159.989 | 221.607 | 393.379 |
| Tổng giây chờ response | 315,814 | 388,699 | 438,307 |
| Replay phase outcomes khớp | 45/45 | 54/54 | 54/54 |

Replay không gọi mạng. V4 có bảy lỗi missing provided facts và hai allowed=false trên các brief học kỹ năng; không nhầm allowed=false thành một bài được phép sinh. V5/V6 khắc phục được lớp đầu vào này ở lượt full này, dù EXTRACT V6 stage isolation vẫn có hai quote sai nguồn. Đây là hai phép đo khác nhau, không bỏ kết quả bất lợi.

V5 dừng sáu bài vì question IDs mismatch, hai unanchored fact evidence, ba question quality rejected, hai activity word limit và một semantic quality rejected. V6 dừng chín bài tại question quality review, hai activity word limit, hai lỗi schema response, một agreement thiếu ordered evidence hai người nói, một unanchored fact evidence và ba semantic quality rejected. Các lỗi được giữ nguyên, không repair.

Hai schema response V6 là QUESTIONS gán `segmentId=a2_s4` và `a2_s3` cho câu hỏi activity a3: vừa sai ID contract, vừa viện dẫn nội dung activity khác. Không đổi dấu gạch hay sửa ID để ép qua. Hai schema lỗi V4 là reasonVi dài hơn giới hạn tại REVIEW/REVIEW_QUESTIONS; V5 không có schema lỗi trong lượt full 1.

Chênh lệch completion V6−V4 là +3,7 điểm phần trăm; paired case-bootstrap 95% [−18,5; +25,9]. V6−V5 là −14,8 điểm; khoảng [−40,7; +11,1]. Khoảng rộng đi qua 0; đây chỉ là bootstrap có điều kiện trên một sample/case, không phải chứng minh ưu thế nội dung hay full sampling uncertainty. Token V6 ở lượt này gấp khoảng 2,46 V4 và 1,78 V5; do số call và nội dung downstream khác nhau, không diễn giải thành chi phí cố định mỗi bài đạt chất lượng.

#### Full round 2: cùng 27 input, đảo vòng thứ tự

| Metric | V4 | V5 | V6 |
|---|---:|---:|---:|
| EXTRACT qua gate, kể cả allowed=false | 19/27 | 27/27 | 27/27 |
| EXTRACT cho phép chuyển sang authoring | 16/27 | 27/27 | 27/27 |
| Pipeline hoàn tất | **6/27** | **13/27** | **12/27** |
| Development hoàn tất | 4/21 | 11/21 | 8/21 |
| Reused holdout hoàn tất | 2/6 | 2/6 | 4/6 |
| Model calls | 80 | 115 | 133 |
| HTTP 200 / 503 | 80 / 0 | 114 / **1** | 133 / 0 |
| Input + output tokens được báo | 124.486 | 195.647 | 424.165 |
| Tổng giây chờ response thành công | 202,191 | 308,081 | 409,613 |
| Replay phase outcomes khớp | 43/43 | **53/54** | 54/54 |

V5 `transport-last-train` gặp HTTP503 tại QUESTIONS. Lưu UNKNOWN; không retry, không gán thành content defect. Replay phase này là UNREPLAYABLE vì không có parsed response, không phải prompt replay sai; error/wire/HTTP503 và hash gốc vẫn được lưu. Các phase còn lại khớp, toàn bộ replay zero network. UNKNOWN được tính là chưa hoàn tất trong tỷ lệ vận hành, tách riêng khỏi chất lượng. Token/timing bảng chỉ có usage/response thành công được báo; không suy đoán billing của request503.

V4 có tám provenance lỗi, ba allowed=false, hai activity word limit, hai question quality rejected, ba agreement thiếu evidence, hai answer leakage và một semantic rejection. V5 có năm word limit, năm question IDs mismatch, hai unanchored evidence, một agreement evidence và một HTTP503. V6 có một word limit, năm question quality rejected, ba agreement evidence, một question IDs mismatch, một unanchored evidence và bốn semantic rejection. Không có schema response lỗi trong full round2.

Chênh lệch completion V6−V4 là +22,2 điểm; paired-bootstrap [−3,7; +44,4]. V6−V5 là −3,7 điểm; [−33,3; +25,9]. V6 dùng khoảng 3,41 lần token V4 và 2,17 lần V5 trong lượt này. Các khoảng vẫn rộng; lỗi reviewer có thể phản ánh nội dung sai hoặc reviewer quá chặt. Không coi các verdict này là human adjudication.

#### Tóm tắt hai lượt full

| | V4 | V5 | V6 |
|---|---:|---:|---:|
| Completion round1 / round2 | 8 / 6 | **13 / 13** | 9 / 12 |
| Tổng completion trên 54 lần thử | 14 | **26** | 21 |
| Calls hai lượt | 176 | 239 | 257 |
| Reported tokens hai lượt | 284.475 | 417.254 | **817.544** |

Đây là **27 case lặp hai lần**, không phải 54 case độc lập; không gộp thành một phép kiểm định 54 mẫu. V5/V6 cải thiện khả năng đi từ brief chỉ nêu mục tiêu học sang authoring; V5 nhiều completion hơn cả hai lượt, V6 cải thiện ít hơn và tốn token hơn. Completion không đủ vượt các điều kiện reviewer/grading/chất lượng nghĩa đã chốt.

### Judge ngữ nghĩa trên output stage cố định

27 call judge, tất cả HTTP200; chấm chín output/arm theo 48 tiêu chí/arm. Cùng Gemini, pointwise, không gửi nhãn phiên bản trong task data, chưa human-calibrated. Không cộng các tiêu chí khác nhau thành một điểm chất lượng chung.

| Tiêu chí PASS / tổng được chấm | V4 | V5 | V6 |
|---|---:|---:|---:|
| EXTRACT: 4 output × 5 tiêu chí | 20/20 | 20/20 | 20/20 |
| GENERATE: 2 output × 6 tiêu chí | 12/12 | 12/12 | 12/12 |
| QUESTIONS: 1 output × 6 tiêu chí | 6/6 | 6/6 | 6/6 |
| TRANSLATE: 2 output × 5 tiêu chí | **8/10** | **10/10** | **10/10** |

Judge bắt hai lỗi của V4 `translate-bread`: semantic_preservation và terminology, cùng do dịch “nuts” thành “cà hạt”. Đọc raw xác nhận lỗi này; V4 còn có literal HTML entity `Xin ch&#xE0;o` và “Tất nhiân”. Đây là text AI sinh trong raw JSON, không phải sửa encoding của prompt. V5 dùng “các loại hạt”, V6 dùng “hạt” trong cùng segment. Đây là bằng chứng cải thiện dịch trên mẫu cố định này; không chứng minh mọi bản dịch của candidate tốt hơn.

Giới hạn quan trọng: judge vẫn PASS toàn bộ EXTRACT V6 dù validator đã phát hiện hai quote không nguyên văn. Nó cũng cho vietnamese_naturalness của bản V4 PASS dù có các lỗi chữ nêu trên. Vì vậy 48/48 của candidate không thể ghi đè gate hoặc được xem là độ chính xác thật. Judge này chưa đủ nhạy để xác nhận điều kiện không tăng false acceptance trong sử dụng thực.

### Đọc output thực: điều mà completion chưa phản ánh

Agent đọc lại V6 smoke `inference-03` (không coi đây là human evaluation): output qua mọi gate nhưng chọn vai “Người học/Người hướng dẫn”, tạo bài giảng về suy luận ở nhà hàng thay vì đưa người học vào một tình huống giao tiếp cần suy luận. Câu “Do you have any vegetarian options?” được giải nghĩa trực tiếp trong hội thoại; hai câu hỏi sau đó hỏi “món chay” và “không có thịt”, rất gần nghĩa. Ngoài ra “a restaurant situation” là bối cảnh giả lập trong lớp nhưng câu hỏi hỏi buổi luyện tập ở đâu và đáp án “Ở nhà hàng”, dù vị trí thật của buổi luyện chưa được xác nhận.

Đây là dấu hiệu lệch mục tiêu học và cần đánh giá sư phạm riêng, dù schema/evidence substring hợp lệ và `qualityPassed=true`. Hợp đồng hiện tại chỉ có skill detail/main-idea và numeric profiles; không được giả rằng nó chứng nhận năng lực suy luận hoặc TOEIC. Giữ nguyên output để người dùng đọc, không tự chỉnh scene hoặc gán nhãn human gold.

Đọc thêm hai input full round1 mà **cả ba bản đều hoàn tất**, `social-rain-plan` và `toeic-02`, để tránh chỉ nhìn một sample V6. Đây là lựa chọn có điều kiện theo completion, không phải mẫu đánh giá ngẫu nhiên hay gold labels:

- `social-rain-plan`: cả ba giữ Chủ nhật, giờ bảy và ngân sách tổng cho hai người. V5 nói rõ chưa kiểm tra chỗ trống, giao người gọi và giữ việc đặt sân có điều kiện; đây là cách xử lý pending hữu ích theo chính sách đã chốt. V4 chuyển sang “the court is booked”; V6 chọn giá 150 nghìn/giờ và đề nghị Lan đặt qua tài khoản. Không coi việc tự chọn một giá hoặc bối cảnh hư cấu được phép là lỗi chỉ vì không có trong input.
- `toeic-02`: V4 và V6 tạo hội thoại công việc thực về lịch/phòng họp; V6 có thời gian, địa điểm và số bản báo cáo để nghe chi tiết. V5 lại tạo hội thoại về **soạn tài liệu luyện nghe**, bàn cách viết đoạn hội thoại và câu hỏi, thay vì trực tiếp đưa người học vào tình huống mục tiêu. Completion V5 không bảo đảm mục tiêu sư phạm.
- Ở cùng bài V6, câu hỏi trong thoại nói phòng họp thường dùng ở **tầng bốn**, nhưng câu trả lời chuyển sang **phòng bốn** đang sửa. Việc đồng nhất số tầng với số phòng chưa được hội thoại xác nhận. Đây là một điểm cần người đọc phân xử, không tự gán nhãn lỗi human gold.

Như vậy V6 có sample thực tế khá hơn V5 về chọn scene, nhưng cũng có sample lệch mục tiêu và điểm thiếu nhất quán. Không chọn sample đẹp để kết luận cả bộ đã thắng.

## Quyết định: giữ nguyên bộ V4 đang hoạt động

V6 có cải thiện đo được ở xử lý goal-only trong full runs, một nhãn chấm thiếu thông tin và mẫu dịch bánh mì. V5 có completion tốt nhất hai lượt và bắt answer leakage ở probe mà V4/V6 bỏ sót. Các cải thiện này có giá trị, nhưng chưa đủ để tuyên bố một bộ thắng toàn diện:

- **V5 chưa đạt điều kiện reviewer:** bỏ sót sai vai `review-source-role`, lặp lại regression đã thấy ở đợt trước. Completion cao hơn không bù cho điều kiện này.
- **V6 chưa chứng minh ưu thế nội dung đủ chắc:** EXTRACT stage có quote sai nguồn, full có bằng chứng sai activity, mẫu inference qua gate vẫn lệch sang bài giảng về kỹ năng. REVIEW_QUESTIONS tiếp tục bỏ sót answer leakage. Judge chưa đủ nhạy để bảo đảm không tăng false acceptance; corpus nhỏ, nhãn tạm và reused holdout làm bằng chứng còn hạn chế.
- GRADE V6 là 19/20 so 18/20, cải thiện một probe nhưng vẫn sai một nhãn; chưa có human adjudication. Không nâng kết quả này thành bằng chứng chất lượng chấm điểm chung.
- Hai lượt V6 dùng 817.544 reported tokens so V4 284.475 và V5 417.254; chưa có căn cứ cho chi phí tăng này khi thay toàn bộ bộ prompt. Không quy đổi sang USD hoặc suy đoán billing request503.

Theo điều kiện người dùng đã chọn, **giữ cả bảy prompt V4**, `SCENARIO_LISTENING_V4`, hash `e3b045db46013c6e9694ae450f8beeb168bc1b452cb1548bcbe43a4eb247535b`. Candidate V5/V6 chỉ phục vụ benchmark; source V4 và pin Core giữ nguyên. Không cần rollback vì benchmark không kích hoạt candidate trong ứng dụng. Không deploy hoặc đổi model/schema/database. Đợt này kết thúc ở đo và quyết định; không tự sửa prompt thêm sau kết quả.

## Evidence, tracing và kiểm tra

Tất cả **837 model calls** đã kết thúc: 836 HTTP200, một HTTP503. Có 13 capture đã seal: ba stage arms, ba smoke arms, sáu full arms và một judge. Raw requests/schema/output/wire/error/outcome cùng SHA inventory được giữ trong ignored `data/`; report tổng hợp có thể tính lại offline, không cần sinh dữ liệu đánh giá mới.

Các thư mục trong `data/scenario-local-20261031/`:

- `prompt-v6-source-20261101/`: hai bản Claude gốc và đối chiếu.
- `prompt-v6-stages-{v4,v5,v6}-20261101/`: stage isolation, cùng input đã xác minh.
- `prompt-v6-three-way-{smoke,full-r1,full-r2}-20261101/{v4,v5,v6}/`: raw pipeline và `derived/replay.json`.
- `prompt-v6-three-way-judge-20261101/`: prompt/rubric, từng phán quyết và rationale.
- `prompt-v6-three-way-summary.json`: tổng hợp dẫn xuất, không phải raw bất biến.
- `prompt-v6-langfuse-export-audit.json`: audit chỉ đọc bằng Langfuse CLI chính thức.

Audit Langfuse lúc `2026-11-01T11:55:56Z` đọc 1.278 observations, 441 traces trong bảy evaluation runs; các tổng số khớp số call và phase của local captures. Stage mỗi arm 58 observations/29 traces; smoke 74/23; full1 497/153; full2 479/151; judge 54/27. Các arm benchmark có variant metadata riêng. Judge dùng caseId tiền tố v4/v5/v6, không có field variant riêng; không dùng filter variant để đọc judge. ERROR trên trace có thể là gate rejection, không chỉ lỗi dịch vụ. Trace presence không thay thế đánh giá chất lượng.

- [Ví dụ trace V6 stage](https://jp.cloud.langfuse.com/project/cmvx6vyks000iad0j2ph4qdbu/traces/b94ce3199b97fb08d08cb4f72ea64f8e)
- [Ví dụ trace full round2](https://jp.cloud.langfuse.com/project/cmvx6vyks000iad0j2ph4qdbu/traces/d604dbd059440538b8b4234b8ec9517b)
- [Ví dụ trace judge](https://jp.cloud.langfuse.com/project/cmvx6vyks000iad0j2ph4qdbu/traces/bd54b11b09b797fb73a3f153eacdeaae)

160 tests liên quan PASS tại thời điểm đóng băng candidate, có hai warning deprecation thư viện. Runtime Python3.14 cũng phát warning Pydantic-v1 từ Langfuse; audit remote ở trên đã xác minh trace, không thay dependency giữa các arm. Kiểm tra UTF-8 bốn file và `git diff --check` PASS; raw/runtime credentials giữ ngoài Git. Trạng thái push/CI được báo trong bước bàn giao; không xem kết quả local là CI hoặc production deployment.

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
