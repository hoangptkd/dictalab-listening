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

Model thực tế: `gemini-3.5-flash-lite`, temperature 0, output budgets EXTRACT 2600 / GENERATE 3200 / QUESTIONS 4000 / REVIEW_QUESTIONS 3500 / TRANSLATE 4000 / REVIEW 3500 / GRADE 1200. Manifest ghi cấu hình thực tế, không dùng model khác để che lỗi.

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

### Kết luận

**Chưa có bằng chứng few-shot cải thiện chất lượng tổng thể. Giữ V5 + GRADE V4 đang dùng.** Đã tạo đủ 10 cặp minh họa và đưa vào candidate riêng để thử nghiệm, nhưng chưa kích hoạt candidate trong ứng dụng. Candidate bắt lỗi sai vai tốt hơn và có một số kết quả đủ độ dài hơn; đồng thời reviewer câu hỏi bỏ lọt một câu lộ đáp án, chi phí token tăng. Không sửa candidate sau khi đã xem kết quả.

Các con số dưới đây là phép đo trên bộ nhỏ với nhãn tạm của agent. Chúng không phải tỷ lệ đúng trên người dùng thật, điểm human review hoặc bằng chứng huấn luyện model thành công.

### So sánh 58 lần gọi từng bước đã hoàn tất

Hai arm đều chạy đủ 29 inputs cố định, tổng **58 HTTP 200**. So sánh cùng model, temperature, schema, validator, budget và không retry. Hai thư mục đã seal SHA inventory; đọc lại `compare` không gọi model.

| Chỉ số | Baseline V5 + GRADE V4 | Thêm 10 cặp few-shot |
|---|---:|---:|
| EXTRACT đạt schema/gate | 4/4 | 4/4 |
| GENERATE đạt schema/gate | 2/2 | 2/2 |
| QUESTIONS đạt schema/gate | 1/1 | 1/1 |
| TRANSLATE đạt schema/đủ segment | 2/2 | 2/2 |
| REVIEW_QUESTIONS phát hiện mẫu lỗi | **4/4** | **3/4** |
| REVIEW_QUESTIONS bỏ lọt mẫu lỗi | **0/4** | **1/4** |
| REVIEW phát hiện mẫu lỗi | **3/4** | **4/4** |
| REVIEW bỏ lọt mẫu lỗi | **1/4** | **0/4** |
| Hai mẫu reviewer sạch được chấp nhận | 2/2 | 2/2 |
| GRADE khớp nhãn tạm, tính từng đáp án | 18/20 | 18/20 |
| Input tokens | 29.834 | 56.941 |
| Output tokens | 12.307 | 11.936 |
| Tổng tokens | **42.141** | **68.877** |

**Tổng tokens tăng 63,4%** trên cùng 29 inputs. Đây là lượng token, chưa quy đổi thành tiền hoặc kết luận độ trễ. GRADE không thay prompt; hai lần đều sai cùng hai nhãn trong `grade-partial`, không có bằng chứng cải thiện chấm điểm. Đạt schema/coverage chưa chứng minh bản dịch chính xác hoặc hội thoại tự nhiên.

Hai khác biệt có bằng chứng trực tiếp:

- `review-source-role`: spec gán sai vai nhưng baseline trả `SOURCE_ROLES=true`, cho qua. Few-shot trả `SOURCE_ROLES=false`, nhận ra cả hai nhân vật bị gán thành người bán bánh và từ chối đúng theo nhãn tạm.
- `review-questions-leak`: câu hỏi là **“Cửa hàng mở lúc chín giờ, đúng không?”**, đáp án là **“Chín giờ”**. Baseline từ chối; few-shot đặt `noAnswerLeak=true`, giải thích đó là câu hỏi xác nhận và cho qua. Lỗi này vẫn có thể đi qua gate vì gate kiểm chính kết luận reviewer; cấu trúc đúng không bảo đảm kết luận đúng.

### Toàn luồng: chỉ có kết quả một phần

Kế hoạch ban đầu gồm hai lượt × 27 brief × hai arm và judge. **Lượt 1 bị dừng; lượt 2 và judge chưa chạy.** Không tính tỷ lệ hoàn tất cho toàn bộ 27 brief, không seal phần dang dở như một lượt hoàn chỉnh.

| Dữ liệu thực sự ghi được ở lượt 1 | Baseline | Few-shot |
|---|---:|---:|
| EXTRACT có outcome thành công | 7 | 8 |
| GENERATE toàn luồng có outcome thành công | 2 | 3 |
| GENERATE bị gate từ chối | 4 | 3 |
| GENERATE UNKNOWN với HTTP 429 | 1 | 1 |
| HTTP 200 ở tất cả bước | 26 | 36 |
| HTTP 429 | 1 | 1 |

Số lượng case hai arm chưa bằng nhau khi dừng. Dù trên phần đã có candidate hoàn tất `main-idea-01/03` mà baseline bị chặn vì thiếu từ, candidate lại bị REVIEW từ chối ở `main-idea-04` trong khi baseline qua. Các ca inference đã chạy vẫn gặp `question IDs mismatch`. Không dùng 2 so với 3 bài để kết luận candidate thắng.

Đọc thủ công `main-idea-02`, ca cả hai arm hoàn tất, cho thấy giới hạn nội dung còn tồn tại ở **cả hai**:

- Baseline viết cuộc trò chuyện quán cà phê tương đối mạch lạc, nhưng sáu câu hỏi đều thiên về chi tiết; hai câu đầu hỏi nơi gặp và không gian quán. Mục tiêu luyện nắm ý chính chưa được thể hiện tốt trong bộ câu hỏi.
- Few-shot viết cảnh đồng nghiệp bàn hội thảo và có câu hỏi về chủ đề chính. Tuy nhiên activity cuối nói thẳng “the primary topic of our discussion today…” và lặp chủ đề ở activity trước, làm nhiệm vụ nghe kém tự nhiên; câu hỏi ý chính vẫn được gắn `skill=detail`.
- Few-shot hỏi “Họ thống nhất phác thảo bao nhiêu hoạt động chính…?” trong khi số **ba** chỉ được người thứ hai đề xuất sau câu “I agree”. Người thứ nhất chưa chấp nhận số này. Đây là dấu hiệu reviewer vẫn có thể đánh đồng đồng ý chủ đề với đồng ý chi tiết mới.

Đây là nhận xét đọc output của agent, chưa phải đánh giá mù của con người. Không dùng nhãn `qualityPassed=true` để phủ nhận các hạn chế quan sát được.

### Đo lặp hai lỗi trọng yếu

Đã khai báo thêm hai replicate, giữ nguyên candidate, xoay thứ tự arm, không retry. Kết quả:

- **Sai vai:** hai lời gọi few-shot bổ sung đều từ chối; một baseline bổ sung vẫn cho qua; baseline còn lại HTTP 429. Tính cả lần gốc: few-shot phát hiện **3/3** phản hồi hợp lệ, baseline **0/2**; một UNKNOWN bị loại khỏi mẫu số. Đây là một fixture lặp lại, không phải 5 tình huống mới.
- **Lộ đáp án:** bốn replicate gặp `FileNotFoundError` ở bước lưu request, trước khi có wire/HTTP. Đường dẫn request dài 263–264 ký tự trên Windows. Không có output model để đánh giá lại lỗi này; chỉ lần gốc của mỗi arm dùng được. Các UNKNOWN này là lỗi công cụ capture, không phải model trả lời sai hoặc lỗi provider.
- Tổng replicate thực sự ghi wire: **3 HTTP 200 + 1 HTTP 429**. Các records cũ giữ nguyên; không chạy lại để thay UNKNOWN bằng kết quả đẹp hơn.

### Quota, cấu hình và tính toàn vẹn

1. Attempt A (`fewshot-v1-20261101`) chạm admission RPD nội bộ 1.350. `QuotaUnavailable` xảy ra trước HTTP; các arm không có độ phủ ngang nhau. Giữ evidence nhưng **không dùng làm phép so sánh chất lượng**.
2. Attempt B (`fewshot-v1b-unrestricted-20261101`) dùng quyền test local không giới hạn người dùng đã cấp: chỉ process benchmark đặt `operational_limits_enabled=False` cho **cả hai arm**. Không sửa service/config đang chạy, không reset bộ đếm, đổi tài khoản hoặc thay gate. Provider vẫn thực thi quota của họ. Manifest lưu flag này; comparator từ chối hai arm khác flag.
3. HTTP 429 thật xuất hiện ở lượt toàn luồng và lần đo lặp. Capture lỗi giữ `httpStatus` và hash body, không giữ nội dung lỗi đầy đủ, nên **không đủ bằng chứng xác định đó là RPM hay RPD của provider**. Không gán mọi UNKNOWN cho quota.
4. Riêng attempt B, tổng wire ghi được: **123 HTTP 200 + 3 HTTP 429** = 126 lượt HTTP (58 stage + 64 toàn luồng một phần + 4 replicate). Bốn lỗi đường dẫn không tạo HTTP. Các call counter của harness là số lần vào gateway, không thay cho số request có wire.

### Tracing và đọc lại evidence

Đã kiểm tra Langfuse bằng CLI chỉ đọc lúc `2026-11-01T16:34:00Z`: mỗi arm stage có **29 trace / 58 observation**. Replicate và lượt toàn luồng một phần cũng đã xuất hiện. `ERROR` có thể là gate chủ động từ chối mẫu lỗi, không tự động đồng nghĩa lỗi hạ tầng hoặc nội dung xấu.

- [Một trace baseline](https://jp.cloud.langfuse.com/project/cmvx6vyks000iad0j2ph4qdbu/traces/ebe1ced972fe43a3c66d26c853815d65).
- [Một trace few-shot](https://jp.cloud.langfuse.com/project/cmvx6vyks000iad0j2ph4qdbu/traces/0249d148892c434050f8065e8aa635df).

Evidence nằm ngoài Git tại `data/scenario-local-20261031/`:

- `fewshot-v1-20261101/`: attempt admission bị chặn, giữ lịch sử riêng.
- `fewshot-v1b-unrestricted-20261101/fewshot-v1b-stages-{active,fewshot}-20261101/`: captures hoàn chỉnh, `integrity.json`, instructions, cases, request/schema, raw response, wire/usage và gate.
- `fewshot-v1b-unrestricted-20261101/stage-comparison.json`: kết quả đọc offline; slot `v4/v5` là tên lịch sử của comparator, lần này tương ứng **active/fewshot**, không phải hai bản V4/V5 thuần.
- `fewshot-v1b-unrestricted-20261101/fewshot-v1b-full-r1-20261101/`: captures chưa hoàn tất; `interrupted.json` ở thư mục cha ghi trạng thái thật.
- `fewshot-v1b-unrestricted-20261101/critical-repeats/`: các lần lặp, kể cả lỗi đường dẫn và 429.
- `fewshot-langfuse-export-audit.json`: tổng hợp truy vấn tracing, không có credentials.

Có thể kiểm lại từng lỗi bằng raw đã lưu mà không sinh lại dữ liệu. Bản archive và SHA-256 được lưu thêm tại `C:/Users/HP/Desktop/English_dictition/data/scenario-evidence/`, xem receipt tương ứng của đợt few-shot này. Bản inventory bổ sung cho phần bị ngắt chỉ chứng minh file không đổi, không biến lượt đó thành benchmark hoàn tất.

### Khuyến nghị cho vòng sau

Giữ 10 cặp như **reference candidate** để đọc và thẩm định. Không thêm cả bộ vào production lúc này. Nếu tiếp tục tối ưu, đo riêng few-shot EXTRACT/GENERATE và few-shot reviewer để xác định lợi ích/thoái lui của từng bước; bổ sung minh họa reviewer phân biệt lộ đáp án và đề xuất/chấp nhận, rồi đóng băng candidate mới trước khi đo. Cần người đọc thẩm định các mẫu tham chiếu trước khi gọi là kết quả chuẩn cuối cùng. Chưa thực hiện các thay đổi/vòng gọi model này trong đợt hiện tại.

Kiểm tra code: **166 Python tests PASS**, gồm 10 expectedOutput, schema/gates, không đổi GRADE/pin và fairness của comparator. UTF-8 và `git diff --check` đạt. Không thay pipeline production hoặc train model trong task này.
