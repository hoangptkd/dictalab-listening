# Kế hoạch bàn giao: agent tự thử–sửa chất lượng teacher

Ngày chốt: 22/10/2026. Đây là đặc tả để agent khác triển khai và vận hành vòng thử.
Việc viết tài liệu này chưa khởi chạy thí nghiệm, xóa dữ liệu hay huấn luyện.

## 1. Các quyết định chủ dự án đã chốt

| Hạng mục | Quyết định bắt buộc |
| --- | --- |
| Luồng được nghiệm thu | **Teacher: một lời gọi sinh cả content và translation** |
| Phạm vi CEFR | Conversation A1/A2/B1; chưa mở B2/C1/C2, chưa xử lý audio |
| Model sinh | DeepSeek đã tích hợp, đọc cấu hình từ provider registry |
| Model chấm | Gemini khác model sinh; chỉ dùng model gọi được trong registry và vượt kiểm định chấm |
| Mức đạt | **Ít nhất 28/30 bài mới**, mỗi mức ít nhất **9/10** |
| Điều kiện bổ sung | Hard validation 100%, không critical, ít nhất 5/6 chuỗi đạt đầy đủ |
| Quyền sửa | Prompt, few-shot, dựng context và luồng sinh/dịch khi có bằng chứng lỗi |
| Giới hạn phiên | Tối đa **10 vòng thử candidate**; hết quota lưu checkpoint rồi dừng |
| Bản không hiệu quả | Xóa bản thử và dữ liệu chi tiết của vòng mới; giữ log gọn tránh lặp sai |
| Bản được giữ | Bản tốt nhất hiện tại và bằng chứng của lần nghiệm thu cuối |
| Triển khai production/train | Chưa được phép tự deploy hoặc train từ kế hoạch này |

28/30 = 93,33%, đáp ứng yêu cầu **trên** 90%; 27/30 không đạt.
9/10 là mức sàn từng level do chủ dự án chọn, không phải yêu cầu trên 90% riêng từng level.
Tỷ lệ này là kết quả quan sát trên mẫu kiểm tra, không phải chứng minh xác suất thực tế
trên 90% với độ tin cậy thống kê 95%.

## 2. Trạng thái xuất phát và ranh giới thay đổi

- Đọc `docs/conversation-semantic-v8.md`, `docs/conversation-semantic-v9.md` và hai report tương ứng.
- V8/v9 chưa đủ chất lượng. Không gọi v9 là bản thắng hoặc mặc định coi phiên bản mới hơn tốt hơn.
- V9 pilot: hard 12/12; v8 semantic 2/6, v9 3/6; v9 còn một critical và một REVIEW_REQUIRED.
- Sai số nổi bật: vai/trạng thái thông tin, câu hỏi–slot–key lệch nhau, lặp ý,
  taskType sai và dịch tự thêm chi tiết. JSON/Unicode/độ dài hợp lệ chưa chứng minh ngữ nghĩa đúng.
- Benchmark cũ dùng AI:Codex duyệt. Runner hiện chưa có vòng tự tối ưu với Gemini judge.
- `training/benchmark_teacher.py` mặc định vẫn có gate cũ 27/30 và reviewer `AI:Codex`.
  Không dùng nguyên trạng các giá trị đó để nghiệm thu theo kế hoạch mới.
- Giữ nguyên artifact, rubric và report lịch sử v7/v8/v9. Quy tắc xóa bản thua chỉ áp dụng
  cho dữ liệu và file do campaign mới tạo ra.
- Giữ `repair/retry/fallback=false` khi đánh giá chất lượng. Mỗi bài chỉ có một lần sinh;
  thử candidate mới không phải sửa rồi chấm lại bài cũ.
- Không đổi rubric, validator, seed, model, mẫu số hoặc coverage để nâng điểm sau khi xem kết quả.
- Không xuất development/calibration/holdout sang SFT. Không sửa cờ `developmentOnly`
  hoặc ghi AI approval thành human approval.
- Dù được sửa phần tổ chức sinh/dịch, nghiệm thu vẫn là một teacher call tạo cả hai phần.
  Test dịch cô lập chỉ là chẩn đoán; chuyển tiêu chí sang luồng hai call cần chốt phạm vi mới.

### Thành phần phải tái sử dụng

| File/thành phần | Cách dùng |
| --- | --- |
| `training/generate_teacher_data.py` | `strict_json`, `batch_schema`, `production_messages`, `make_context`, `build_records`, `digest`, ghi JSON độc quyền |
| `training/benchmark_teacher.py` | Cơ chế teacher request, envelope, quota, hash, chống replay và chuỗi phụ thuộc đã review |
| `training/semantic_rubric.py` | 11 `CHECKS`, `rules_for('conversation-semantic-v3')`, `decision`, `severity` |
| `training/freeze_candidate.py` | Đóng băng artifact; từ chối ghi đè; giữ provenance development |
| `app.model_gateway.ModelGateway` | Cả gọi DeepSeek và Gemini; không tạo client né registry/quota |
| `app.gemini_pool.quota_store` | Gọi đúng `platform='deepseek'` hoặc `platform='gemini'`; không đọc quota Gemini bằng store DeepSeek |
| `app.provider_registry`, `app.config.Settings` | Registry mã hóa, tài khoản bật, model và hạn mức đã cấu hình |
| `app.conversation_context.encounter_state` | Lập chỉ mục nguồn; không giả vờ đã suy ra mọi settled fact |
| `app.validation.validate_task` | Hard validation dùng trong production; không thay bằng validator dễ hơn |
| `training/prepare_dataset.py` | Giữ nguyên rào cản development data, provenance, duplicate và split |

Tên `app.*` ở trên thuộc `services/ai-generation/app/`.
Luồng production hai lời gọi nằm ở `app.workflow`/`app.prompt_versions`; nó không phải
đối tượng nghiệm thu của kế hoạch này. Kết quả teacher không được báo thành kết quả production.

## 3. Sản phẩm cần triển khai, theo thứ tự

### Bước 0 — Khảo sát và khóa hợp đồng thí nghiệm

1. Đọc AGENTS.md, các file ở mục 2 và `git status`; xác định phần việc đang sửa của người khác.
2. Ghi campaign manifest: phạm vi, phiên bản policy, rubric/hash, validator/hash, catalogue/hash,
   model sinh/chấm, decoding, teacher wrapper/hash, phân bổ case và ngân sách.
3. Tạo policy mới, ví dụ `teacher-first-pass-28of30-v1`. Gate mới thuộc campaign mới;
   không sửa điều kiện của plan/report cũ hoặc dùng gate cũ 27/30 làm đường tắt.
4. Khóa temperature sinh 0.2, output budget 8192; judge temperature 0 khi provider hỗ trợ.
   Ghi giá trị thực tế, không giả vờ cloud có seed/digest trọng số tái lập được nếu API không cung cấp.
5. Chỉ một cấu hình prompt/context/luồng sinh được thay trong một candidate có giả thuyết rõ.
   Nếu sửa nhiều file cho cùng một lỗi gốc, ghi chung một change family và diff liên quan.
6. Candidate có ID mới theo campaign/round, parent hash và code bundle hash; tính digest
   không bao gồm chính trường sha256. Giữ artifact `approvalStatus=development`, không sao chép
   approval/metricReport thành tích cũ. Không dùng CLI freeze v9 rồi ghi đè artifact v9 lịch sử.
7. Xác minh catalogue `data/conversation-distillation/production-contract.json`, Redis,
   registry DB và cấu hình mã hóa có sẵn. Credential không in ra, không truyền qua argv/Git;
   thiếu nguồn/config thì báo thiếu, không tạo dữ liệu hoặc nhãn giả để tiếp tục.

**Đầu ra:** manifest versioned, hash kiểm tra được; chưa gọi model sinh.

### Bước 1 — Thêm runner có checkpoint; chưa tối ưu prompt

Ưu tiên một module điều phối mới `training/iterate_teacher.py` và test
`training/tests/test_iterate_teacher.py`. Tái sử dụng helper hiện hữu; chỉ tách helper dùng chung
khi cần tránh sao chép phần transport/quota/bảo vệ dữ liệu. Giữ CLI và hành vi legacy chạy được.

Runner cần các thao tác sau; đây là CLI **cần triển khai**, chưa phải lệnh đã có:

```text
python -m training.iterate_teacher prepare --campaign <directory> --max-rounds 10
python -m training.iterate_teacher calibrate --campaign <directory>
python -m training.iterate_teacher evaluate --campaign <directory> --candidate <artifact>
python -m training.iterate_teacher decide --campaign <directory>
python -m training.iterate_teacher final --campaign <directory>
python -m training.iterate_teacher resume --campaign <directory>
python -m training.iterate_teacher resume --campaign <directory> --new-session --max-rounds 10
python -m training.iterate_teacher status --campaign <directory>
```

- Agent viết code/đổi candidate theo feedback giữa các thao tác. Không xây thêm hệ thống
  để Python tự viết và thực thi source code do model API trả về.
- `evaluate` chạy pilot của candidate đang mở; `decide` chọn giữ/loại và dọn dữ liệu.
  `final` kiểm tra độc lập bản best đã khóa, không sinh thêm candidate ngầm.
- `resume` nối giai đoạn dang dở; không tự đặt round counter về 0 hoặc gửi lại request đã gửi.
- Sau khi một phiên hết 10 vòng, phiên mới chỉ bắt đầu qua thao tác tiếp tục rõ ràng;
  dùng `--new-session` khi người vận hành yêu cầu tiếp tục. Lưu cả session_id,
  round trong phiên và tổng vòng toàn campaign; resume vì quota không tự tạo phiên mới.
- Chỉ một tiến trình được sở hữu campaign. Dùng khóa loại trừ có PID/owner;
  không tự xóa khóa đang được tiến trình khác giữ.
- Không gọi đường export SFT của legacy report cho campaign này. Mọi record development,
  final và chain đều `developmentOnly=true`; `trainingReady=false` kể cả ACCEPTED.
  Sau `build_records`, gán `reviewVersion` đúng campaign thay vì giữ hằng v2 của collector cũ.

**Đầu ra:** chạy được với fake provider, có checkpoint và báo cáo, chưa cần gọi model thật.

### Bước 2 — Tích hợp Gemini judge và kiểm định trước khi cho chấm

1. Tạo Settings riêng cho teacher DeepSeek và judge Gemini từ cùng registry mã hóa.
   Chỉ đổi provider/model ở runtime của thí nghiệm, không sửa default production hoặc DB registry.
2. Chọn Gemini đang bật và gọi được; hiện registry có `gemini-3.5-flash-lite` trên ba tài khoản.
   Đây là quan sát lúc lập kế hoạch, agent phải kiểm tra lại. Không suy ra Lite đủ năng lực chấm.
3. Khóa model judge trong campaign. Có thể xoay tài khoản cùng model theo pool hiện có,
   nhưng không đổi model giữa chừng hoặc chuyển về DeepSeek tự chấm để vượt gate.
4. Chuẩn bị gold set versioned gồm tối thiểu 36 probe từ ít nhất 12 nguồn nội dung khác nhau:
   12 PASS, 12 FAIL major/minor, 8 FAIL critical, 4 REVIEW_REQUIRED.
5. Gold cần cả bài đầy đủ hard-valid và lỗi ngữ nghĩa có chủ đích. Đánh dấu nguồn/cặp mutation;
   không tính các paraphrase UI hoặc đổi tên/số là các tình huống độc lập.
6. Dùng dữ liệu đã có nhãn và các mutation chỉ thay một lỗi được quy tắc xác định chắc chắn.
   Các nhãn ở bảng dưới là hợp đồng cho nhóm lỗi; không lấy phán quyết của chính Gemini
   làm nhãn đúng để kiểm định lại Gemini. Nhãn v2 không tự chuyển thành nhãn v3.
7. Nếu chưa có đủ nguồn với nhãn chắc chắn, dừng ở `JUDGE_BLOCKED`, nêu thiếu case nào.
   Không để model code yếu tự đoán nhãn, không nhận kết quả chấm chưa kiểm định.

| Nhóm probe bắt buộc | Kỳ vọng |
| --- | --- |
| Khách gọi món, câu hỏi đổi vai người học thành khách | FAIL rolesCorrect; critical |
| “Can sell a return ticket” bị hỏi thành vé khách đã mua | FAIL informationStatusCorrect; critical |
| Hỏi chính sách chưa được trả lời bị biến thành chính sách đã xác nhận | FAIL informationStatusCorrect; critical |
| Kết luận suy luận thiếu tín hiệu, hoặc đáp án suy luận đã nói thẳng | FAIL reasoningGrounded; critical |
| Giờ tập thử được coi là lịch thuyết trình đã chốt nhưng thiếu nguồn | REVIEW_REQUIRED |
| Chỉ hỏi giờ, key bắt buộc thêm ngày/người/địa điểm riêng | FAIL questionFactsAligned |
| Một địa điểm có mốc “tủ cạnh cửa vào” | Không tự quy thành hai slot |
| Hỏi hành động sau if nhưng taskType là condition | FAIL taskTypeAccurate |
| Hỏi prerequisite thực sự | condition hợp lệ |
| Lặp ý đã hỏi, chỉ thay số/tên | FAIL newMeanings |
| Sister/brother bị thêm tuổi; time-to/past dịch sai; before/by/until sai | FAIL translationFaithful |
| Focus gist/inference nhưng chỉ có chi tiết; UI lộ đáp án | FAIL focusFulfilled hoặc noAnswerLeak |

`boundary_cases_v3()` hiện chỉ là semantic probe thiếu schema đầy đủ; 24 calibration v2
có cặp paraphrase. Không trình bày hai bộ đó như 36 bài hoàn chỉnh độc lập đã được duyệt.
Gold và các nhãn kỳ vọng không xuất sang train hoặc đưa vào input chấm benchmark.
Nguồn review riêng hiện có dưới `data/fine-tuning/semantic-v9-pilot-20261022/` và
`data/fine-tuning/semantic-v8-ab-20261022/`: đọc candidate kèm reviews.json và hash tương ứng,
không suy ra nhãn từng bài từ aggregate report. V9 có năm PASS đã duyệt, một REVIEW_REQUIRED;
đây chưa đủ gold 36. Thiếu các thư mục private khi chạy ở máy/checkout khác phải được báo rõ.

**Gate judge:** chấm gold hai lần, thứ tự khác nhau; mỗi lần ít nhất 35/36 quyết định đúng,
không bỏ lọt bất kỳ critical nào, cả bốn ca chưa chắc phải REVIEW_REQUIRED.
Không được bỏ/sửa ca khó, đổi nhãn, hoặc gọi đi gọi lại cho đến khi tình cờ vượt gate.
Chỉ thử kiểm định lại sau thay đổi có căn cứ ở cấu hình/instructions judge, với phiên bản và log mới.
Thay judge/rubric/gold sau khi tối ưu bắt đầu làm mất hiệu lực so sánh cũ và phải tái kiểm định.

**Hợp đồng kết quả chấm:**

- Đủ 11 checks, từng giá trị là boolean hoặc null; issues cụ thể, severity tách riêng.
- Mỗi false/null trích đúng trường và đoạn nguồn liên quan; kiểm tra quote là substring thật.
- Không có check thiếu, thừa hoặc sai kiểu. Verdict JSON/schema lỗi là chấm chưa hợp lệ,
  không được quy thành PASS hoặc nhờ teacher sửa phán quyết.
- Các false chắc chắn tạo FAIL; null không tạo PASS. Null ở vai/trạng thái thông tin/suy luận
  liên quan đáp án bắt buộc tạo `unresolvedCriticalRisk`, không cho nghiệm thu đến khi được giải quyết.
- Input chấm chỉ gồm context, task và rubric; ẩn tên candidate, điểm cũ, giả thuyết sửa,
  nhãn kỳ vọng và lời đề nghị “hãy cho đạt”. Rationale do teacher viết chỉ là tuyên bố cần kiểm tra.
- Lưu reviewer `AI:<model Gemini thực tế>` và hash rubric/context/task/request/response;
  không ghi thành `AI:Codex`, không đặt `humanApproved=true`.
- Không dùng nguyên `benchmark_teacher.reviewed()` rồi giả danh AI:Codex để mở khóa chain.
  Thêm đường xác minh automated judge với provenance riêng; giữ legacy verifier hoạt động như cũ.

### Bước 3 — Tạo bộ development và bộ nghiệm thu tách nhau

**Development:** 12 context cố định/campaign, 4 mỗi mức. Có lịch sử cùng encounter và lượt 4;
luân phiên focus hợp lệ để có gist, opinion và inference ở B1. Dùng cùng source input cho
incumbent và challenger. Không lặp nhược điểm pilot v9 chỉ yêu cầu hai nhóm focus.

- Đo incumbent ban đầu trên bộ này; các số v7/v8/v9 cũ khác rubric/mẫu không dùng làm điểm nền tương đương.
- Cache kết quả incumbent nếu cùng source manifest, mô hình, decoding, wrapper, rubric và judge đã khóa.
- Nếu sửa context builder, nguồn vào vẫn giống nhau nhưng effective context có thể khác;
  lưu cả hai hash và công bố biến thay đổi, không gọi đó là thử chỉ khác prompt.
- Nếu điều kiện so sánh đổi, không dùng cache cũ làm bằng chứng hơn/thua.

**Final:** tạo bộ mới sau khi đóng băng candidate, chưa dùng để chọn prompt hoặc viết few-shot.
30 bài, 10 mỗi mức, phân bố ít nhất 10 nhóm scenario/branch khác nhau mỗi mức.
Dùng lượt 2 và 4; lượt 3 được đo trong phần chain có lịch sử đã duyệt.

| Level | Focus được yêu cầu trên 10 bài final |
| --- | --- |
| A1 | 10 direct_detail |
| A2 | 2 direct_detail, 2 gist, 2 reason_purpose, 2 instruction_sequence, 1 condition, 1 change_revision |
| B1 | 1 direct_detail, 2 gist, 1 reason_purpose, 1 instruction_sequence, 1 condition, 1 change_revision, 1 opinion_attitude, 2 inference_prediction |

- Mỗi focus phải hợp lệ với allowedTaskTypes, level và encounter; change_revision cần giá trị cũ có nguồn,
  không đặt vào lượt 4 độc lập. Mỗi bài B1 tối đa một inference có ít nhất hai tín hiệu liên kết.
- Teacher vẫn dùng 2 slot ở A1, 3 ở A2/B1 như authoringRules hiện hành; không giảm số ý để tăng tỷ lệ.
- Chọn context không trùng gold/development/few-shot hoặc holdout dành cho SFT cuối đã được bảo vệ.
  Không đánh tráo việc chọn model bằng tập test của dataset train.
- Sau khi final đã chạy, bộ đó bị loại khỏi mọi lần nghiệm thu sau, kể cả final thất bại.
  Lưu hash/seed/group index chống dùng lại; không thêm nguyên bài final vào prompt/few-shot để chữa điểm.
- Khóa candidate, code hash, wrapper và model trước khi sinh bài đầu tiên của final.
  Thay bất kỳ yếu tố nào giữa chừng phải bỏ tư cách nghiệm thu của cả lượt, ghi lý do và lập lượt mới.
- Dữ liệu mới phải có ý nghĩa/ngữ cảnh mới, không chỉ đổi tên, số hay seed của case đã lộ.
  Nếu nguồn chưa dùng không đủ coverage, trả `DATA_COVERAGE_BLOCKED`; không quay vòng bộ final
  cũ để chạy cho đủ 10 lần. Có thể chuẩn bị nguồn mới có provenance và kiểm tra độc lập trước
  khi mở lượt nghiệm thu tiếp theo; không đụng tập holdout SFT đã được bảo vệ.

**Chain:** 6 nhóm mới, 2 nhóm/mức, không trùng bộ 30; mỗi nhóm gồm lượt 2→3→4.
Chỉ đưa task hard PASS và semantic PASS vào history của lượt sau.
FAIL/REVIEW_REQUIRED dừng nhóm; không sinh tiếp phần phụ thuộc, không tính lượt chưa gửi là PASS.
Whole-group PASS chỉ khi cả ba lượt đạt. Lượt 4 phải độc lập đúng quy tắc, không bám câu chuyện cũ.

### Bước 4 — Chạy vòng thử–sửa tối đa 10 lần

Mỗi candidate do agent đề xuất phải có một dòng giả thuyết: nhóm lỗi cần sửa, thay đổi,
kết quả dự kiến và kiểm tra phân biệt đúng/sai. Không nối thêm một danh sách dài các lệnh lặp lại
chỉ vì candidate trước thất bại.

```text
prepare campaign → calibrate judge → đo incumbent development nếu chưa có cache hợp lệ
while round_in_this_session < 10 and chưa ACCEPTED:
    đọc log gọn + dữ liệu current/best còn được giữ
    chọn một lỗi gốc, tạo candidate và diff thuộc nhiệm vụ
    khóa candidate/hash; tăng round counter và checkpoint
    chạy offline regression/UTF-8/schema checks
    nếu offline lỗi: ghi kết quả; hoàn tác diff của candidate; dọn candidate; sang vòng sau
    nếu thiếu quota cho giai đoạn kế tiếp: checkpoint WAITING_QUOTA; dừng tiến trình
    chạy 12 bài development một lần/bài; hard check rồi Gemini chấm
    so candidate với best trên cùng bộ và cùng điều kiện
    nếu không tốt hơn: ghi ledger bền vững; hoàn tác; xóa dữ liệu candidate; sang vòng sau
    nếu tốt hơn: xác minh và thay best; xóa bản best cũ đã bị thay thế sau khi lưu ledger
    nếu best qua cửa pilot: khóa best; chạy final 30 + 6 chain trên dữ liệu mới
    nếu toàn bộ gate đạt: lưu bằng chứng final; ACCEPTED; dừng
    nếu final không đạt: lưu log gọn, retire bộ final; dọn dữ liệu final không đạt
    tiếp tục từ best theo lỗi gốc mới; không sửa bài final rồi chấm lại
hết 10 vòng mà chưa đạt → STOPPED_MAX_ROUNDS; không báo thành công
```

**Cửa pilot để thử final:** hard 12/12; semantic ít nhất 11/12; mỗi mức ít nhất 3/4;
zero critical và không unresolvedCriticalRisk. Qua pilot chưa phải ACCEPTED.

**Quy tắc chọn best trên development:**

1. Không tăng số critical hoặc tạo nhóm critical mới; không giảm hard pass.
2. Không giảm semantic pass của bất kỳ level nào.
3. Sau hai điều kiện trên, cần ít nhất một cải thiện về critical, hard hoặc tổng semantic pass.
4. Nếu toàn bộ quyết định từng case không đổi, có thể giữ bản tốn ít prompt token hơn;
   không dùng giảm token để bù chất lượng giảm hoặc biến bài còn lỗi thành bản đạt.
5. Khác biệt một vài case development chỉ dùng dẫn hướng tìm kiếm; không tuyên bố cải thiện production.

`best` có thể vẫn chưa đạt. Manifest phải giữ `accepted=false` cho đến khi vượt toàn bộ final gate.
Không xóa best chỉ vì final thất bại, nếu nó vẫn tốt nhất trên development; dữ liệu final thất bại
được dọn riêng. Nếu hai vòng liên tiếp không cải thiện, đổi giả thuyết/nhóm lỗi thay vì lặp patch cũ;
vẫn tuân thủ giới hạn 10 vòng, không tự xin thêm quota hoặc nới tiêu chí.

### Bước 5 — Ưu tiên sửa theo bằng chứng

| Thứ tự | Hướng kiểm tra và sửa |
| --- | --- |
| 1 | Vai và trạng thái: đề nghị bán/chưa xác nhận không thành đã mua; câu hỏi không thành chính sách |
| 2 | Một ý được hỏi → đúng slot → key ngắn đúng ý; bỏ yêu cầu ngày/nơi/người/lý do chưa hỏi |
| 3 | Nhận diện tested meanings từ nguồn; thay tên/số không tạo ý mới; giữ cùng object ở lượt 2/3 |
| 4 | TaskType theo điều được hỏi; direct detail khác hành động, prerequisite, gist và suy luận |
| 5 | Dịch giữ modality, tuổi/giới tính chưa rõ, deadline, time-to/past, mã và phủ định |
| 6 | Rút bớt lệnh trùng/đối nghịch; few-shot phải schema-valid, đúng số slot, không bị copy vào output |

Thử riêng nhóm nội dung và nhóm dịch: khi đánh giá sửa dịch, dùng English content/facts cố định;
khi đánh giá sửa nội dung, giữ translationSystem cố định. Chỉ đưa các thay đổi đã kiểm chứng
vào candidate hợp nhất và chạy lại teacher end-to-end. Mọi lời gọi thử riêng vẫn tính quota/log,
không coi tỷ lệ trên test cô lập là tỷ lệ end-to-end.

## 4. Công thức nghiệm thu duy nhất

Một bài PASS khi hard check đạt và đủ 11 semantic checks đúng, issues rỗng, verdict hợp lệ.
FAIL, REVIEW_REQUIRED, lỗi schema/model/API hoặc thiếu kết quả không được cộng vào tử số PASS.
Quota dừng trước khi gửi khiến giai đoạn chưa hoàn tất; không rút nhỏ mẫu số rồi chấm đạt.

```text
ACCEPTED = final_candidate_frozen
           AND judge_calibration_valid
           AND all_30_planned_items_resolved
           AND hard_pass_30 == 30
           AND semantic_pass_30 >= 28
           AND pass_A1 >= 9 AND pass_A2 >= 9 AND pass_B1 >= 9
           AND hard_errors_in_generated_chain_tasks == 0
           AND critical_in_final_and_chains == 0
           AND unresolvedCriticalRisk == false
           AND complete_chain_groups >= 5 of 6
           AND focus_and_scenario_coverage_valid
           AND attempt == 1 for every evaluated generation
           AND repair == retry == fallback == false
           AND hashes_and_provenance_valid
```

`all_30_planned_items_resolved` nghĩa là đủ 30 case có kết cục ghi nhận, không phải
cả 30 đều semantic PASS. REVIEW_REQUIRED được ghi kết cục nhưng không tính PASS.
Phân biệt lỗi model/validation/judge/hạ tầng trong báo cáo; không xóa case lỗi khỏi mẫu số.
API lỗi không chứng minh prompt sai, nhưng lượt có lỗi không đủ điều kiện báo đã đạt toàn bộ gate.

Nếu thay rubric là cần thiết do phát hiện mâu thuẫn thật, dừng campaign và báo rõ mâu thuẫn;
không tự thay trong vòng tối ưu. Chỉ campaign mới được dùng policy/rubric được chốt lại.

## 5. Quota, chi phí và tiếp tục sau gián đoạn

Quan sát lúc lập kế hoạch, **không phải quota được bảo đảm cho lần chạy sau**:

| Provider | Hạn mức cấu hình | Đã dùng khi kiểm tra |
| --- | --- | --- |
| DeepSeek | 13 RPM, 100 RPD/account và global | 96/100 request ngày |
| Gemini | 13 RPM/account × 3; 500 RPD/account; global 39 RPM, 1350 RPD | 23/account; global 106 |

Đọc lại quota bằng đúng platform trước từng giai đoạn. Không reset counter, tăng hạn mức,
đổi project/account để né quota, mở billing hoặc mua thêm dịch vụ.
Xoay các tài khoản Gemini đã được bật chỉ theo chính sách pool hiện hữu.

| Giai đoạn | Teacher DeepSeek tối đa | Judge Gemini, một task/call |
| --- | ---: | ---: |
| Baseline development lần đầu | 12 | 12 |
| Mỗi candidate development | 12 | 12 |
| Một lượt final 30 bài | 30 | 30 |
| Sáu chain đủ ba lượt | 18 | 18 |
| Một lượt nghiệm thu đủ | **48** | **48** |

Chỉ chấm task hard-valid; chain dừng có thể giảm số call thực tế. Calibration, probe, test dịch
cô lập và mọi call không thành công cũng phải có ledger và tính ngân sách riêng.
Ưu tiên judge một task/call để kiểm toán đơn giản; nếu batch sau này, phải kiểm tra từng item,
đủ ID và không tăng điểm bằng cách bỏ item lỗi. Không dùng batch để lách giới hạn token/quota.

- Trước mỗi phase, tính cap bảo thủ và kiểm tra quota còn đủ cho phase đó;
  final có thể chia `final-30` và từng vòng chain nhưng giữ nguyên candidate/config đã khóa.
- RPD là giới hạn của cửa sổ quota thực tế từ store, không mặc định reset theo nửa đêm Việt Nam.
- Hết quota: ghi `WAITING_QUOTA`, giai đoạn/case đang dở, hash và thời điểm kiểm tra;
  kết thúc tiến trình. Không giữ một vòng sleep vô hạn, không tự tạo scheduler/automation.
- Sau khi người vận hành tiếp tục phiên, recheck quota và nạp checkpoint;
  không đổi candidate/model để lấp phần còn thiếu của một lượt final.
- Ledger ghi request intent trước khi gửi, rồi kết cục sau khi nhận. Request đã có khả năng gửi
  nhưng mất kết quả trở thành `OUTCOME_UNKNOWN`, không tự replay.
- Provider trả 429/503: ghi đúng kết quả và cooldown của pool; không retry cùng bài,
  không repair, không đổi provider. Các case chưa gửi được giữ pending khi dừng vì hạ tầng.
- Log token/call/thời gian thực tế; không tự quy thành chi phí tiền nếu thiếu đơn giá đã kiểm chứng.

Mười candidate với pilot và mỗi candidate đều vào final có thể cần tối đa
12 + 10 × (12 + 48) = **612 call DeepSeek**, chưa tính thử cô lập.
Đây là trần dự toán cho nhiều phiên/quota window, không phải quyền vượt 100 RPD.
Agent phải báo khối lượng này khi mở campaign, checkpoint đúng hạn và không hứa chạy xong trong một ngày.

## 6. Checkpoint, giữ bản tốt nhất và xóa bản thua

Campaign mới nằm dưới `data/fine-tuning/teacher-auto-<campaign-id>/`, đã được Git ignore.

```text
campaign.json             # hợp đồng/hash bất biến
state.json                # trạng thái, session/round/phase/case; ghi atomically
ledger.jsonl              # log gọn bất biến theo dòng; không chứa credential/full prompt
sent-index.jsonl          # ID/hash đã gửi hoặc outcome unknown; cần giữ để chống replay
retired-final-index.jsonl # hash/seed/group đã dùng; không có transcript
best/                     # một artifact/code patch bundle tốt nhất + development evidence
current/                  # chỉ candidate đang thử; xóa khi bị loại
final-accepted/           # chỉ bằng chứng lượt nghiệm thu được chấp nhận
```

Tài liệu/gold fixture không đổi được lưu versioned trong Git. Dữ liệu live, key và DB snapshot
không vào Git. Bản final accepted có aggregate report versioned; raw evidence giữ ngoài Git.

Mỗi dòng log vòng thử chỉ cần: ID, hash bản cha/bản thử, tóm tắt thay đổi, nhóm lỗi,
điểm theo level, hard/critical/uncertain, token/call, verdict giữ/loại và lý do.
Có thể giữ tối đa ba trích đoạn ngắn minh họa lỗi, không giữ nguyên bài/prompt/code của bản thua.
Giữ hash để tránh thử lại cùng patch; hash không thay thế bằng chứng raw của bản cuối accepted.

Thứ tự dọn bắt buộc:

1. Hoàn tất chấm và sinh feedback để agent biết lỗi trước khi xóa bài.
2. Ghi ledger/sent index/retired index và flush; không xóa trước khi log được lưu chắc chắn.
3. Candidate thua: hoàn tác **chỉ patch của candidate**, giữ best và thay đổi ngoài nhiệm vụ.
4. Xác minh working tree/hash đã trở về trạng thái best hợp lệ; rồi xóa thư mục current của vòng đó.
5. Candidate thắng: xác minh bundle mới đầy đủ, chuyển best bằng staging/rename có checkpoint;
   chỉ xóa best cũ sau khi best mới đọc lại và kiểm tra hash thành công.
6. Final thất bại: giữ best nếu còn là best; retire index và log xong mới xóa raw final thất bại.
7. Nếu crash giữa các bước, resume từ checkpoint và hoàn tất thao tác còn dở;
   không xóa cả hai bản hoặc bỏ ledger rồi coi như chưa từng gọi API.

Trước mọi recursive delete trên Windows: resolve đường dẫn tuyệt đối, kiểm tra là thư mục con
của campaign hiện tại và không chứa symlink/junction/reparse point dẫn ra ngoài; từ chối đường dẫn
workspace root, best đang dùng, final accepted hoặc dữ liệu lịch sử. Dùng `Remove-Item -LiteralPath`
hay API thư viện chuẩn với cùng kiểm tra. Không `git clean`, `git reset --hard` hoặc `git checkout -- .`.
Khôi phục diff bằng patch được sở hữu và kiểm tra trước; nếu file đã bị người khác sửa thêm, dừng
với `WORKSPACE_CONFLICT`, không ghi đè để tiếp tục tự động.

## 7. Kiểm thử agent phải để lại

Các kiểm tra sau dùng fake provider/transport; không cần API thật để phát triển runner:

| Nhóm | Tình huống tối thiểu |
| --- | --- |
| Gate | 27/30 từ chối; 28/30 nhưng một mức 8/10 từ chối; 28/30 đúng phân bố và 5/6 chain mới có thể đạt |
| An toàn ngữ nghĩa | Một critical từ chối dù 30/30 tổng; null không thành true; unresolved critical không được nghiệm thu |
| Judge | Không kiểm định thì không được duyệt; gold thất bại phải chặn; không rò expected labels vào request |
| Provenance | Reviewer khác model sinh; model/hash/rubric mismatch chặn; AI không giả danh human/Codex |
| First pass | JSON trùng key, surrogate/Unicode lỗi, truncated response, sai số slot đều không repair/retry |
| History | Turn 3 chỉ dùng turn 2 đã PASS; fail/null dừng chain; turn 4 bỏ story cũ và tránh đáp án cũ |
| Coverage | Có gist/opinion/inference thật, không đủ focus không qua gate; không dùng lại final đã lộ |
| Quota | Store tách provider; tính cả call chấm; đúng RPD/RPM; hết quota checkpoint, không gửi vượt cap |
| Resume | Crash trước/sau gửi; outcome unknown không replay; session/round không tự reset; cấu hình đổi phải chặn |
| Retention | Bản thua bị xóa sau ledger; best/final/history không bị xóa; đường dẫn ngoài root/reparse point bị chặn |
| Git/workspace | Rollback không đụng thay đổi ngoài task; không commit raw/private data hoặc bản thua |
| Legacy | CLI/report v7/v8/v9 và verifier AI:Codex cũ vẫn chạy theo hợp đồng cũ |
| Dataset | Record final/chain vẫn developmentOnly; prepare_dataset từ chối đưa vào SFT dù campaign ACCEPTED |

Lệnh kiểm tra tối thiểu sau khi tạo runner/test mới:

```powershell
python -m pytest -q training/tests/test_iterate_teacher.py training/tests/test_benchmark_teacher.py training/tests/test_generate_teacher_data.py training/tests/test_prepare_dataset.py
python -m pytest -q services/ai-generation/tests tools/ai-eval
python -m py_compile training/iterate_teacher.py
python C:/Users/HP/.codex/skills/vietnamese-encoding-guard/scripts/check_encoding.py training docs/conversation-teacher-auto-iteration-plan.md
git diff --check
```

Nếu sửa Java prompt/context/export, chạy thêm các test Java liên quan và xác minh artifact export
khớp contract mới. Không chạy mọi suite tốn kém lặp lại khi chưa có rủi ro mới cần giải quyết.
Encoding test fixtures cố ý chứa chuỗi hỏng phải giữ annotation hợp lệ, không sửa mù quáng.

## 8. Kết quả cuối và bàn giao GitHub

Các kết cục hợp lệ: `ACCEPTED`, `WAITING_QUOTA`, `STOPPED_MAX_ROUNDS`, `JUDGE_BLOCKED`,
`INFRA_BLOCKED`, `DATA_COVERAGE_BLOCKED`, `WORKSPACE_CONFLICT`. Chỉ `ACCEPTED` được nói đã đạt ngưỡng.

Khi ACCEPTED:

- Giữ artifact, code patch và raw evidence final; lưu aggregate report có policy/rubric/model/hash,
  30/30 hard, số semantic PASS từng level, critical, chain groups, API/token và các giới hạn phép đo.
- Chỉ đưa thay đổi code/prompt của bản thắng, runner/test và aggregate report phù hợp vào Git.
  Candidate vẫn chưa được tự đổi server default, deploy hay đưa benchmark vào dataset SFT.
- Theo AGENTS.md, chạy checks, review staged diff, commit/push đúng task branch và xác minh SHA remote.
  Theo dõi GitHub Actions nếu truy cập được; nếu không, báo CI chưa xác minh và nguyên nhân.
- Báo riêng: harness triển khai xong hay chưa; candidate chất lượng đã ACCEPTED hay chưa;
  GitHub delivery có thành công hay chưa. Không gộp ba trạng thái đó thành “xong”.

Khi chưa ACCEPTED: giữ runner đã kiểm tra và checkpoint/best/log theo chính sách;
báo đúng blocker/vòng đã chạy/điểm hiện tại/điều kiện tiếp tục. Runner hoàn chỉnh có thể commit/push
riêng với mô tả đúng trạng thái; không gắn nhãn prompt chưa đạt là bản phát hành.

## 9. Prompt khởi động để giao agent khác

Sao chép nguyên khối sau vào agent có quyền đọc cùng workspace:

> Đọc AGENTS.md và `docs/conversation-teacher-auto-iteration-plan.md`, rồi triển khai kế hoạch theo thứ tự.
> Phạm vi là teacher A1/A2/B1, sinh content và translation cùng một lời gọi DeepSeek.
> Dùng Gemini trong registry làm judge riêng, phải qua kiểm định trước. Không dùng chính phán quyết
> của judge làm nhãn kiểm định nó, không giả danh AI:Codex/human, không nới rubric/validator/gate.
> Xây runner và fake-provider tests trước, sau đó chủ động thực hiện vòng thử–sửa tối đa 10 candidate
> mỗi phiên khi quota cho phép. Sửa prompt/few-shot/context/luồng sinh-dịch theo bằng chứng lỗi,
> mỗi candidate có giả thuyết rõ; giữ bản tốt nhất và chỉ log gọn bản thua trước khi xóa dữ liệu chi tiết.
> Điều kiện nhận là ít nhất 28/30 bài final mới, mỗi level ít nhất 9/10, hard validation 100%,
> zero critical/không unresolvedCriticalRisk và ít nhất 5/6 chain đủ ba lượt PASS.
> Không retry/repair/fallback cùng bài, không dùng lại final đã lộ, không chạy quá quota hoặc tự reset bộ đếm.
> Hết quota thì checkpoint và dừng; lần tiếp tục nạp lại trạng thái, không replay yêu cầu đã gửi.
> Bảo toàn dữ liệu lịch sử, credential và thay đổi ngoài nhiệm vụ; không train hoặc deploy production.
> Theo AGENTS.md, commit/push phần việc hoàn chỉnh đã kiểm tra; báo rõ trạng thái harness,
> trạng thái chất lượng, branch/commit/checks và kết quả xác minh delivery/CI.
