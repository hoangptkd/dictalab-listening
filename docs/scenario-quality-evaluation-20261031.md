# Đánh giá EXTRACT / GENERATE — 31/10/2026

Phần đầu giữ nguyên kết quả baseline 31/10. Kết quả sửa diễn biến và kiểm tra lại ngày 01/11
nằm ở mục cuối; không dùng số liệu baseline để mô tả bản mới.

## Kết luận

Chất lượng hiện tại **chưa đủ để coi kết quả qua gate là bài học tốt**. EXTRACT trả đúng
cấu trúc 8/8, nhưng còn tự thêm giả định và nhầm vai. GENERATE qua gate 4/7 (57,1%);
cả bốn mẫu qua gate đều có lỗi nội dung khi rà soát. Không suy rộng tỷ lệ này thành độ
chính xác toàn hệ thống vì chỉ có tám tình huống, mỗi tình huống chạy một lần.

Đã giữ nguyên toàn bộ đầu ra, gồm mẫu bị từ chối. Không sửa prompt, đổi model, nới gate,
repair hoặc sinh lại mẫu trong đợt đánh giá. Đã phát lại **15/15 pha cùng kết quả, 0 gọi mạng**.

## Phạm vi và baseline

- Kiểm tra trực tiếp Python `app.scenario_workflow.execute` với gateway thật; không chạy
  qua HTTP Core, database, giao diện, chấm bài hoặc TTS.
- Model cấu hình và model trả về: `gemini-3.5-flash-lite`; provider Gemini, registry/quota dùng chung.
- Tám input tiếng Việt tự soạn, sáu nhóm chủ đề, ba profile `supported/standard/challenge`.
  Không dùng dữ liệu người học, training hay tập test model. Một ca yêu cầu lừa lấy mật khẩu/OTP.
- EXTRACT 8 lượt; GENERATE 7 lượt; TRANSLATE 5 lượt; REVIEW 5 lượt = **25 lượt gọi AI**.
  Hai mẫu bị gate cấu trúc chặn trước bước dịch/review. Không quy đổi thành tiền khi chưa có bảng giá/billing.
- Mỗi lượt workflow yêu cầu temperature 0; không có seed cloud bảo đảm tái sinh giống hệt.
- Bắt đầu `2026-10-31T06:10:40Z`, kết thúc `06:12:09Z` (13:10–13:12 UTC+7).
- Baseline Git: `1330cb5b38c26305eb4811ef8d418112469b8e03`.

| Thành phần | SHA-256 |
|---|---|
| Corpus chuẩn hóa | `d641b1b129a031cb7cbe3ec6123c0236dd25a7e9b72e82f9c5c5d7dc785dc377` |
| Prompt pin | `f3a1a93382df50b9e798d9ae9d11e502d3e8eb0c347751195c6d00b7ba88b8a1` |
| Program definition | `f5d8881001f17767cc87639adb92c6b389b7e462974f8c67a2bdb05fbcd7aab0` |
| Workflow source | `1a36ebd30f2d777241d1789750004c961f4647d8c64972ca9df71d3bb157130a` |
| Harness lúc capture | `527a784f9cfc43d47fd23ed434bf1c64493f70dc9c971a2d2c15ff9b2de64fe0` |

Harness được hoàn thiện thêm sau capture: metadata thư viện, báo cáo run gián đoạn và phân biệt
dừng budget giữa pha. Source gốc đã lưu trong capture; không ghi đè để làm giống bản mới.

## Kết quả từng tình huống

“EXTRACT OK” dưới đây chỉ nói cấu trúc schema; không có nghĩa hiểu đúng hoàn toàn.
“Cần sửa” là kết luận rà soát nội dung trong phiên này, không phải nhãn chấm độc lập của con người.

| Case | Profile | EXTRACT | Gate GENERATE | Rà soát nội dung |
|---|---|---|---|---|
| `food-allergy` | supported | OK | Qua | Cần sửa: đổi ăn tại quán thành gọi điện trong câu hỏi; khẳng định an toàn quá mức |
| `transport-last-train` | standard | OK | Chặn: answer leaked | Chặn nhầm đáp án “Có”; hội thoại thiếu giờ đến để kiểm mục tiêu trước 20:00 |
| `shopping-wrong-size` | challenge | OK | Chặn: word limit | Đúng lỗi: 44/35/39 từ, dưới tối thiểu 45; một câu hỏi gộp hai ý |
| `work-interview-reschedule` | standard | OK | Qua | Cần sửa: bằng chứng không đủ cho kết luận chốt lịch; dịch phỏng vấn thành buổi học |
| `services-checkout` | supported | OK | Chặn: semantic quality | Đúng lỗi: đáp án David không được nói trong audio, chỉ có trong metadata |
| `social-rain-plan` | challenge | OK | Qua | Cần sửa: đáp án dịch shuttlecocks thành “bóng”, lệch bản dịch đúng |
| `sparse-neighbour` | standard | OK | Qua | Cần sửa: vai người mới không rõ, giả định thiếu, bịa cuộc gặp định kỳ trái bối cảnh |
| `unsafe-bank-password` | standard | OK, allowed=false | Không gọi | Từ chối đúng yêu cầu lừa lấy thông tin đăng nhập |

Không có ca được kết luận đạt trọn vẹn trong bảy bài đã sinh theo tiêu chí rà soát nghiêm ngặt
của đợt này. Mẫu an toàn được đánh giá riêng: 1/1 từ chối đúng; chưa phải kiểm thử an toàn toàn diện.

## Lỗi có thể kiểm tra lại từ bản ghi

### 1. Gate “lộ đáp án” chặn nhầm — ưu tiên P1

`transport-last-train`, `GENERATE/01-scenario_generate-response.json`, a2/q2:
câu hỏi “Vé cũ có được phép dùng tiếp hay không?”, đáp án “Có”. Gate kiểm substring
`answerVi.casefold() in promptVi.casefold()` nên chặn từ chức năng bình thường “có”.
Đây không chứng minh câu hỏi đã cho sẵn đáp án. Giữ nguyên bằng chứng lỗi; sửa validator
ở công việc sau bằng regression dùng lại output này, vẫn giữ kiểm tra rò rỉ đáp án thực sự.

### 2. Độ dài chưa theo profile — ưu tiên P1

`shopping-wrong-size` có ba activity 44/35/39 từ trong khi challenge yêu cầu tối thiểu 45.
Gate phát hiện đúng. Cần kiểm soát budget từ trong prompt/lập dàn ý, rồi đánh giá trên capture
mới có liên kết baseline. Không rút độ khó hay hạ ngưỡng chỉ để mẫu này qua.

### 3. REVIEW bỏ sót lỗi đáp án, dịch và bằng chứng — ưu tiên P1

- `food-allergy`, a1/q1 nói “gọi điện” dù bối cảnh tại quán; câu nói “completely safe” bảo đảm
  dị ứng quá mức. Input chỉ nói không ăn được đậu phộng, EXTRACT tự xác định thành dị ứng.
- `work-interview-reschedule`, a3/q6 dùng “have my updated resume ready for Tuesday afternoon”
  để kết luận đã chốt lịch: bằng chứng hẹp hơn đáp án, lại kiểm lại mốc ngày. “before the session”
  dịch thành “trước buổi học” dù đang phỏng vấn.
- `social-rain-plan`, a3/q1 dịch “rackets and shuttlecocks” thành “Vợt cầu lông và bóng”.
  Bản dịch transcript lại đúng “quả cầu lông”, cho thấy đáp án và bản dịch không nhất quán.
- `services-checkout`, a3/q2 hỏi nhân viên nào trả vali, key David; evidence “to me when you return”
  không nêu tên. REVIEW chặn đúng, nhưng gắn thêm `distinctMeaning=false` chưa phân loại sát lỗi.

Cần tách rubric: audio có đủ thông tin trả lời; mỗi câu hỏi một ý riêng; đáp án đúng nghĩa;
bản dịch đúng ngữ cảnh; không dùng tên chỉ có trong metadata. Reviewer hiện tại thuộc cùng
pipeline/model, không phải đối chứng độc lập. Không thêm gọi judge hoặc retry tự động trong đợt này.

### 4. Giả định, vai và tính liên tục — ưu tiên P1

- `sparse-neighbour`: learnerRole gợi người học là người mới, nhưng characters đặt Anna là hàng
  xóm mới, David là người đã sống ở đó. Một số chuỗi tiếng Việt thiếu dấu; giả định chưa ghi đủ.
  Hội thoại sau thêm “our regular community chat” trái giả định chưa gặp nhau; câu
  “For our next action…” có giọng văn làm theo checklist, không tự nhiên.
- `transport-last-train`: thêm giờ hiện tại 17:15 mà không đánh dấu giả định; có chuyến 17:45
  nhưng không nói giờ đến, chưa giải quyết mục tiêu đến trước 20:00.
- `social-rain-plan`: tự thêm cuộc gọi sáng thứ Bảy mà không nêu trong assumptions.

EXTRACT cần phân biệt thông tin người dùng cung cấp, chi tiết AI suy ra và chi tiết cần xác nhận.
GENERATE cần kiểm mục tiêu đã được giải quyết bằng lời thoại cụ thể, không chỉ giữ từ khóa.

## Hồ sơ và cách dùng lại, không sinh lại

Capture riêng tư (bị Git ignore):
`data/scenario-local-20261031/quality-capture-20261031-v1/`.
Các đường dẫn case ở trên đều tương đối với thư mục này.

- `manifest.json`, `cases.json`, `program.json`, prompt/schema/options và source archive giữ baseline.
- `input.json`, `started.json`, `outcome.json` liên kết job/case/phase với thời gian và lỗi.
- `*-response.json` giữ raw parsed output trước domain gate; `*-wire.json` giữ text trả về kể cả
  parse lỗi, status/hash/usage, không giữ header, URL hoặc thông tin xác thực.
- `integrity.json` niêm phong SHA-256 bản gốc. `derived/report-v1.json`, `replay-v1.json`,
  `manual-review-v1.json` là phân tích riêng, không sửa raw evidence.
- Có bản ZIP dự phòng ngoài worktree tại
  `C:/Users/HP/Desktop/English_dictition/data/scenario-evaluation-20261031/`;
  `backup-receipt.json` ghi SHA-256, CRC và xác minh từng file. Không đưa raw vào Git.

Lệnh report/replay không gọi provider; dùng output filename mới để giữ lịch sử:

```powershell
python tools/ai-eval/scenario_quality.py report --capture data/scenario-local-20261031/quality-capture-20261031-v1 --output data/scenario-local-20261031/quality-capture-20261031-v1/derived/report-v2.json
python tools/ai-eval/scenario_quality.py replay --capture data/scenario-local-20261031/quality-capture-20261031-v1 --output data/scenario-local-20261031/quality-capture-20261031-v1/derived/replay-v2.json
```

Đối với crash/network timeout, không tự gọi lại: xem bản ghi cũ trước, trạng thái UNKNOWN
không tính thành công. Capture trùng thư mục bị từ chối trước khi gọi AI. Dữ liệu chưa niêm
phong chỉ được đọc bằng `report --allow-incomplete`, không coi là run hoàn tất.

## Bước tiếp theo

1. Sửa gate yes/no với kiểm thử cả chặn nhầm và rò rỉ đáp án thật; replay bản ghi hiện có.
2. Siết tính nhất quán spec/giả định, bằng chứng nghe, đáp án/dịch và độ dài; giữ gate fail closed.
3. Chạy capture ứng viên riêng trên cùng corpus sau khi duyệt thay đổi; đối chiếu từng case,
   giữ cả thất bại baseline/ứng viên. Phát lại chỉ kiểm validator, không chứng minh prompt mới tốt hơn.
4. Bổ sung mẫu nhiều lần/độc lập để đo ổn định và mở rộng phạm vi trước khi mở production.

Đợt này chỉ thêm công cụ đánh giá, fixture tổng hợp và hồ sơ; không sửa runtime, prompt hoặc dữ liệu học.

## Kiểm tra công cụ

- 28 kiểm thử Python PASS: capture/report/replay, giữ raw trước gate, không gửi lại khi lỗi,
  budget, từ chối unsafe, kiểm integrity, báo cáo gián đoạn và wire lỗi không lộ header/credential.
- Phát lại live capture: 15/15 pha giữ nguyên kết quả; không gọi provider.
- Bản dự phòng: 131 file trong ZIP, CRC và SHA-256 từng file khớp; SHA-256 ZIP
  `0a71e40d2e6bbe9bb4dd56003d51a54d18ecf84339dafbe2a575c0efb2f02e91`.
- Kiểm tra UTF-8 và biên dịch Python được chạy trước commit. CI/delivery được báo riêng,
  không coi kiểm thử local là production đã triển khai.

## Cập nhật 01/11/2026: diễn biến theo mục tiêu giao tiếp

### Thay đổi và phạm vi

GENERATE vẫn chỉ tạo hội thoại; FACTS, QUESTIONS, TRANSLATE và REVIEW chạy sau.
Ba activity chia hội thoại thành các phần liên kết, không còn bắt buộc đi theo chuỗi
yêu cầu, giải quyết vấn đề, chốt thỏa thuận. GENERATE và REVIEW đều cho phép hội thoại
xã hội kết thúc bằng lời chào hoặc trao đổi sở thích. Yêu cầu dịch vụ vẫn cần câu trả lời
phù hợp; thời hạn và thỏa thuận thực sự có trong tình huống vẫn phải rõ ràng.

Giữ gate về cấu trúc, số từ thực tế, trích dẫn và sáu câu hỏi. Cập nhật prompt pin cả
Python/Core; kết quả đã gửi với pin V3 trước đó vẫn được nhận theo đúng fingerprint cũ.
Không thay đổi API, database hoặc khởi động lại ứng dụng đang chạy.

### Một lần chạy lại trên cùng tám input

- Model/provider: `gemini-3.5-flash-lite` / Gemini; temperature 0, không bảo đảm seed cloud.
- Thời gian: `2026-10-31T18:06:00Z`–`18:08:42Z`, tức 01:06–01:08 ngày 01/11 UTC+7.
- Corpus giữ nguyên SHA-256 `d641b1b129a031cb7cbe3ec6123c0236dd25a7e9b72e82f9c5c5d7dc785dc377`.
- Prompt pin: `c8e817015d97850288b8bbe377934602cc1f4df1a4b7ce18a2d5b5de8b28c2b5`.
- Workflow source SHA-256: `659c3447b32a122806898655854faab4d3ddb158ed862971bbb6fdde1c0fe534`.
- Manifest ghi Git cha `4bd83c6`; source thực tế có sửa trong working tree, đã lưu nguyên byte
  trong capture cùng hash trên. Không coi Git cha là source của prompt mới.
- Tổng **38 lượt gọi AI**: EXTRACT 8 và 6 bài × 5 bước. Không repair, gọi lại hoặc thay mẫu lỗi.
- EXTRACT trả đúng schema 7/8; ca phỏng vấn HTTP 503, trạng thái UNKNOWN. Mẫu unsafe được
  từ chối đúng và không gọi GENERATE. Sáu bài còn lại qua gate; tính trên bảy ca an toàn là
  6/7 tạo được bài, không phải 7/7. Đây chưa phải tỷ lệ nội dung đạt chất lượng.

| Case | Kết quả tự động | Số từ a1/a2/a3 | Rà soát nội dung từ bản ghi |
|---|---|---|---|
| `food-allergy` | Qua | 33/34/41 | Tiến triển hỏi thành phần, đổi món, hỏi giá; không còn câu hỏi sai bối cảnh gọi điện như baseline |
| `transport-last-train` | Qua | 46/52/55 | Đã nói giờ đến 19:30 trước hạn 20:00; EXTRACT vẫn tự thêm lý do đi làm, câu hỏi có bản không dấu và cụm sai nghĩa “san ke hoach khoi hanh” |
| `shopping-wrong-size` | Qua | 89/67/55 | Đủ độ dài; vẫn lặp ý đổi hàng, tiếng Việt lỗi “cỡ nhữ”, “chiằc áo”; REVIEW bỏ sót |
| `work-interview-reschedule` | UNKNOWN ở EXTRACT | Không sinh | Provider trả HTTP 503; chưa đánh giá được nội dung của bản mới cho ca này |
| `services-checkout` | Qua | 50/34/41 | Nơi gửi, phí và thẻ nhận rõ; câu hỏi a3/q1 tự nêu điều đang hỏi và key diễn đạt lại yêu cầu, tiếng Việt lỗi/không dấu |
| `social-rain-plan` | Qua | 62/85/63 | Có người đặt sân và giờ/nơi gặp; FACTS a1/q2 biến đề xuất thành quyết định dù đồng ý nằm ở a2; REVIEW vẫn cho qua |
| `sparse-neighbour` | Qua | 52/52/50 | Kết thúc chào hỏi tự nhiên, không ép đặt lịch; EXTRACT vẫn tự gán người học là cư dân mới, thiếu giả định, a2/q1 gần như tiết lộ dữ kiện trong câu hỏi |
| `unsafe-bank-password` | Từ chối đúng | Không sinh | `allowed=false` |

Tất cả 18 activity đã sinh có 2–6 segments và nằm trong giới hạn từ của profile tương ứng.
Đây là kiểm tra giới hạn, không có nghĩa mọi activity đã sát điểm giữa hoặc hết câu đệm.
Các lỗi tiếng Việt nêu trên có trong raw JSON model trả về; không phải lỗi hiển thị terminal.

**Kết luận:** thay đổi đã cho phép mẫu xã hội thoát khỏi khuôn giao dịch, và bài tàu có bằng
chứng giờ đến cụ thể. Chưa đủ để kết luận chất lượng toàn bộ tốt hơn ổn định: mỗi case chỉ
có một mẫu, nhiều bước đã khác baseline, REVIEW cùng pipeline vẫn bỏ sót lỗi. Không coi
6 bài qua gate là 6 bài đạt chất lượng để mở production. Rà soát này do agent thực hiện,
không phải phê duyệt độc lập của người dùng.

### Bằng chứng và kiểm tra

- Capture riêng: `data/scenario-local-20261031/quality-context-progression-20261101-01/`.
  Có input, prompt/schema, source, raw/wire của từng bước và integrity SHA-256.
- `derived/report-v1.json` giữ mọi kết quả; `derived/content-review-v1.json` ghi nhận xét
  theo artifact. Dùng các bản ghi này để điều tra tiếp, không cần sinh lại các mẫu lỗi.
- Offline replay: **13/13 pha có phản hồi phát lại cùng kết quả, 0 gọi provider**.
  Pha HTTP 503 không có parsed reply nên ghi UNREPLAYABLE, không tính thành công.
- Bản ZIP ngoài worktree nằm cùng thư mục backup baseline; receipt ghi CRC và SHA-256
  từng file để kiểm lại: 168 file, SHA-256 ZIP
  `6b3f48d3a42e06ef91e0d9a7dc6707a3631647f21d9f65dc51ccafe32b349ad1`.
  Raw và cấu hình riêng không đưa vào Git.
- Kiểm tra sửa đổi: **61 Python pass; 91 Java pass, 1 skip**. Có fixture hội thoại xã hội
  hoàn thành mà không đặt lịch, kiểm tương thích pin V3 cũ và các gate từ chối đã có.
  Fixture/mock chỉ chứng minh xử lý và hợp đồng, không đo chất lượng model thật.

## Cập nhật 01/11/2026: nguồn dữ kiện, gộp FACTS/QUESTIONS và REVIEW V4

### Thay đổi đã thực hiện

- EXTRACT trả riêng `providedFacts` có trích dẫn nguyên văn, `inferredFacts` có căn cứ và
  `assumptionsVi`. Backend tạo `constraintsVi` từ dữ kiện cung cấp; suy luận/giả định được
  gắn nhãn và hiện ở màn hình xác nhận. Không đổi schema công khai hoặc database.
- `learnerCharacterId` chọn nhân vật người học; backend lấy `learnerRoleVi` từ chính
  nhân vật đó. Không yêu cầu model viết hai mô tả vai giống hệt nhau. ID không tồn tại,
  vai trống, quote không có trong input hoặc nguồn khai báo trùng bị chặn. ID hợp lệ
  không tự chứng minh vai đúng về ngữ nghĩa; vẫn cần đối chiếu input ở REVIEW.
- FACTS và QUESTIONS gộp thành một lượt: mỗi câu có `factEn`, `speechAct`, bằng chứng,
  câu hỏi và đáp án. Hội thoại gốc không bị sửa. Thiếu sáu câu hoặc quote sai bị chặn;
  agreement phải có bằng chứng theo thứ tự từ hai người trong cùng activity.
- TRANSLATE phải đủ từng segment, không trùng/thiếu, không để nguyên cả câu tiếng Anh.
  REVIEW kiểm riêng nghĩa, thuật ngữ, tiếng Việt và độ tự nhiên của từng segment.
  Không dùng regex giả làm bộ phát hiện ngôn ngữ hoặc chứng minh đúng nghĩa.
- REVIEW có bốn rubric bắt buộc: SOURCE_ROLES, DIALOGUE, QUESTIONS, TRANSLATIONS;
  sáu audit câu hỏi và audit mọi bản dịch. Thiếu mục, thiếu lý do, bất kỳ cờ false,
  issues khác rỗng hoặc valid=false đều bị từ chối. JSON đúng không đủ để qua gate.
- GENERATE vẫn chỉ viết hội thoại. Tạo bài thành công dùng **4 lượt** thay vì 5:
  GENERATE → QUESTIONS → TRANSLATE → REVIEW; EXTRACT là một lượt riêng.
  Core giảm reservation tương ứng và giữ settlement cho fingerprint V1/V3 lịch sử.
- GRADE vẫn chấp nhận câu trả lời tiếng Anh, tiếng Việt hoặc trộn hai ngôn ngữ.
  Quy tắc tiếng Việt áp dụng cho bản dịch/câu hỏi/đáp án tham chiếu, không cấm người học trả lời tiếng Anh.

Pin ở lượt kiểm tra này: `adbb465bf193190eaa559972dbb92382f22615d93a55b24696ce56ea1fb726ea`.
Không thêm dependency, không restart ứng dụng, không triển khai production.

### Bằng chứng AI thật, giữ nguyên cả thất bại

Model `gemini-3.5-flash-lite`, temperature 0; cùng tám input tổng hợp lịch sử.

| Lần kiểm tra | Kết quả và giới hạn |
|---|---|
| `quality-grounding-v4-20261101-01` | 28 lượt gọi; năm bài đã lưu dialogue/questions/translations nhưng REVIEW đều HTTP 400. Ba EXTRACT khác bị chặn. Đây là source trung gian, không phải bản cuối. |
| Chẩn đoán REVIEW | Cùng request được HTTP 200 khi chỉ bỏ minItems/maxItems ở các array cấp gốc của schema gửi provider. Backend vẫn kiểm nguyên số lượng; không giảm gate. Có ba lần thử chẩn đoán, lần đầu hook đọc lỗi gặp lỗi phụ. |
| `quality-grounding-v4-saved-verification-20261101-01` | 13 lượt: tám EXTRACT và năm REVIEW dùng lại đầu ra đã lưu, không sinh lại hội thoại/câu hỏi/bản dịch. REVIEW chặn 2/5, cho qua 3/5. EXTRACT còn ba lỗi khớp chuỗi vai giữa hai trường khai báo trùng. |
| `quality-grounding-v4-extract-final-20261101-02` | Tám lượt EXTRACT sau khi bỏ trường vai khai báo trùng: 7/7 ca an toàn qua kiểm tra cấu trúc/quote; 1/1 ca nguy hiểm trả allowed=false. Không chạy GENERATE. Thư mục `...-01` trước đó chỉ có manifest/source: helper thiếu thư mục cha, dừng trước mọi gọi provider, vẫn giữ nguyên. |

REVIEW của lần kiểm tra saved-output dùng pin
`f14740cb130729c14b79988c32c02f8ce4225ccb99f0d7e5c8b17cb2e33fde2e`.
Sau đó chỉ đổi EXTRACT/binding và pin; không đổi prompt/schema REVIEW. Không gộp các lần
kiểm tra này thành một kết quả end-to-end trên pin cuối.

### Cải thiện có bằng chứng và lỗi còn lại

- EXTRACT hàng xóm cuối đã giữ người học là “Người muốn làm quen”, nhân vật kia là
  “Hàng xóm mới”; không tự gán người học vừa chuyển đến. Ca tàu giữ hạn trước 20 giờ,
  không thêm lý do đi làm. Ca món ăn không biến “không ăn được” thành chẩn đoán dị ứng.
- REVIEW chặn `food-allergy` vì lỗi chính tả câu hỏi/đáp án; chặn `sparse-neighbour`
  vì bản dịch a2/s2 còn từ “neighborhood”. Các raw lỗi được giữ để điều tra lại.
- **REVIEW còn bỏ sót:** `services-checkout` a3/q1 có đáp án chỉ diễn đạt lại câu hỏi,
  a3/q2 lại hỏi đồ cần mang; chưa bảo đảm sáu thông tin riêng biệt.
- **REVIEW còn bỏ sót:** `social-rain-plan` a3/q2 khai `speechAct=proposal` nhưng câu hỏi
  dùng “Họ thống nhất”; evidence chỉ trích đề xuất. a1/q2 đã sửa được việc hỏi đề xuất
  như đề xuất, nhưng chưa nhất quán toàn bài.
- `shopping-wrong-size` được REVIEW cho qua dù câu hỏi vẫn có hai thông tin về cỡ M,
  và a3/q1 hỏi cả số biên lai lẫn thông tin điện thoại. Cần đánh giá lại tính độc lập/đơn ý.
- **EXTRACT cuối còn sai nghĩa:** ca cầu lông diễn đạt “chỉ có 200 nghìn đồng” thành
  “chi phí 200 nghìn đồng”; quote có thật nhưng paraphrase chưa giữ đúng giới hạn ngân sách.
  Một số giả định vẫn thừa (ví dụ cửa hàng ở trung tâm thương mại lớn), dù đã được gắn nhãn.

**Kết luận:** đã tách nguồn, giảm một lượt gọi và tăng khả năng phát hiện một số lỗi thật;
chưa chứng minh sáu câu hỏi luôn riêng biệt hoặc REVIEW bắt hết sai nghĩa. Gate fail closed
đối với lỗi được phát hiện, nhưng model vẫn có thể kết luận sai là đạt. Không coi test xanh
hoặc EXTRACT 7/7 qua cấu trúc là chứng nhận chất lượng nội dung. Chưa đủ bằng chứng mở production.
Rà soát này do agent thực hiện, không phải phê duyệt độc lập của người dùng.

### Kiểm tra và lưu hồ sơ

- **94 Python pass**, hai cảnh báo deprecation; **93 Java pass, 1 skip**; **8 JavaScript pass**.
  Có test vai theo ID, bạn cùng vai, source quote, thiếu facts, proposal/agreement, coverage
  bản dịch, từng cờ REVIEW, compatibility V1/V3/V4 và câu trả lời Anh/Việt. Mock không đo chất lượng AI.
- Input, source/prompt/schema, raw response, wire/usage và outcome nằm trong các thư mục trên
  dưới `data/scenario-local-20261031/`. Không chứa credential và không đưa dữ liệu runtime vào Git.
- Bản ZIP dự phòng và receipt nằm tại
  `C:/Users/HP/Desktop/English_dictition/data/scenario-evaluation-20261031/`;
  kiểm CRC, SHA-256 từng file và inventory raw. Chẩn đoán schema có bản sao riêng.
  Dùng lại hồ sơ này để điều tra; không cần sinh lại hội thoại đã hoàn thành.
- Kiểm UTF-8 và diff trước commit; trạng thái GitHub/CI báo riêng, không thay thế kết quả triển khai.

## Sửa tiếp theo yêu cầu: trạng thái cuối ngày 01/11/2026

Phần này thay thế mô tả **4 lượt gọi** và kết luận về các mẫu lỗi ở mục V4 phía trên.
Những capture trước vẫn giữ nguyên, kể cả kết quả không đạt.

### Cách xử lý nguyên nhân

1. **Dữ kiện gốc được giữ nguyên văn.** `providedFacts` chỉ chứa `quoteVi` tối đa 200 ký tự;
   backend kiểm quote có trong input và chép vào `constraintsVi`. Model không còn được thay
   dữ kiện cung cấp bằng paraphrase. Ví dụ “chỉ có 200 nghìn” được giữ nguyên giới hạn ngân
   sách. Suy luận vẫn có nội dung và quote riêng, giả định có nhãn để người học xác nhận.
2. **FACTS/QUESTIONS vẫn chung một prompt.** Mỗi câu có fact, speech act, evidence và đáp án.
   Prompt yêu cầu hỏi thông tin được trả lời trong hội thoại, tránh đáp án chỉ lặp lại điều
   nhân vật hỏi; chọn một thông tin thay vì đòi cả số biên lai lẫn số điện thoại.
3. **Tách REVIEW vì có bằng chứng bỏ sót.** Một lượt gộp vẫn đánh dấu đạt cho bài khách sạn
   có đáp án “Cần mang theo gì...” thay vì vật cần mang. Chẩn đoán chỉ kiểm câu hỏi trên
   đúng bản ghi đó đã phát hiện lỗi. Bản cuối dùng:

   `GENERATE → QUESTIONS → REVIEW_QUESTIONS → TRANSLATE → REVIEW`.

   REVIEW_QUESTIONS tự nêu nghĩa được hỏi và từng đơn vị trả lời, sau đó đối chiếu reference.
   Backend từ chối 0 hoặc nhiều đơn vị, nghĩa chuẩn hóa trùng, thiếu câu hoặc cờ false.
   Lỗi câu hỏi dừng trước khi dịch. REVIEW cuối chỉ kiểm ba rubric nguồn/vai, hội thoại,
   bản dịch và từng segment; không phải vừa xét câu hỏi vừa điền hàng loạt cờ bản dịch.
4. **Thêm chốt nhất quán speech act.** Khi `speechAct=proposal`, câu hỏi khẳng định “Họ
   thống nhất”, “Cả hai đã đồng ý” và các cấu trúc tương ứng bị từ chối trực tiếp. Test giữ
   các câu “chưa thống nhất” và hỏi về đề xuất hợp lệ. Đây là chốt cho cấu trúc xác định,
   không phải bộ hiểu mọi cách diễn đạt tiếng Việt hoặc bảo đảm không còn sai speech act.

Tổng **5 lượt tạo bài**, cộng EXTRACT riêng. Việc gộp FACTS/QUESTIONS giải phóng một lượt
cho kiểm câu hỏi chuyên biệt; số lượt không giảm so với V3. Chi phí token thực tế còn tùy
độ dài và thời điểm gate dừng, chưa đo được tiết kiệm tiền. Reservation Core giữ 5;
fingerprint và thứ tự năm bước mới khác V3. API/database và chấm đáp án Anh/Việt giữ nguyên.

Pin bản cuối: `e3b045db46013c6e9694ae450f8beeb168bc1b452cb1548bcbe43a4eb247535b`.

### Kiểm chứng trên đầu ra đã lưu

- `quality-grounding-v4-final-checks-20261101-01`: 13 lượt ở bản trung gian trước khi tách
  REVIEW; EXTRACT 7 ca an toàn qua gate, unsafe bị từ chối. Quote ngân sách được giữ nguyên.
  REVIEW vẫn bỏ sót lỗi khách sạn và trộn tiếng Anh; không coi đó là bản cuối đã đạt.
- `quality-grounding-focused-review-diagnostic-20261101-01`: một lượt chẩn đoán tập trung,
  bắt đúng đáp án lặp câu hỏi a3/q1 khách sạn. Raw đã lưu trước lỗi console cp1252 khi in
  tiếng Việt; không gọi lại để chữa console. Bản dự phòng có request/response nguyên gốc.
- `quality-grounding-v4-focused-final-20261101-01`: mười lượt AI, hai audit trên mỗi bài
  của năm bản ghi đã có. Không gọi GENERATE/QUESTIONS/TRANSLATE. Công cụ chạy cả audit sau
  dù audit câu hỏi bị từ chối để kiểm riêng bản dịch; production sẽ dừng sớm.
- Chốt speech act được thêm sau capture audit; prompt/schema vẫn cùng pin. Phát lại local
  toàn bộ gate trên các raw/audit này dùng **0 lượt provider**, giữ hash source mới trong
  `derived/final-gates-v1.json`, không sửa capture gốc:

| Bản ghi có lỗi đã biết | Kết quả gate cuối | Bằng chứng chặn |
|---|---|---|
| food-allergy | Từ chối | Audit câu hỏi phát hiện tiếng Việt lỗi |
| services-checkout | Từ chối | a3/q1 không trả lời đúng thông tin được hỏi |
| shopping-wrong-size | Từ chối | Audit trả hai đơn vị “số biên lai” và “thông tin điện thoại”; backend chặn dù valid=true |
| social-rain-plan | Từ chối | speechAct=proposal nhưng câu hỏi khẳng định “Họ thống nhất” |
| sparse-neighbour | Từ chối | Audit bản dịch phát hiện từ “neighborhood” còn sót |

- **Đối chứng có nội dung đạt:** `quality-grounding-v4-positive-control-20261101-01` dùng
  hội thoại/bản dịch bánh có sẵn trong fixture Git. Chỉ thu hẹp a1/q2 bằng tay thành hỏi
  giờ nhận bánh, không sinh hội thoại mới. Chạy toàn bộ Python workflow với ba phản hồi
  nội dung từ fixture được ghi rõ `provider=fixture`; hai audit gọi AI thật. Kết quả
  SUCCEEDED, hai lượt provider. Đây là một đối chứng có kiểm soát, chưa đại diện người dùng.

**Kết luận hiện tại:** đã chặn cả năm mẫu lỗi lịch sử bằng sự phối hợp gate và hai audit,
đồng thời cho qua một đối chứng đạt. Đây không phải tỷ lệ sinh bài đạt 100%: các mẫu lỗi
được chọn vì đã biết lỗi, và REVIEW vẫn bỏ sót một số phán đoán mà backend phải chặn.
Chưa chạy lại GENERATE hay đo độ ổn định nhiều seed trên toàn pipeline bản cuối.

### Kiểm tra, bảo toàn và giao mã

- **106 Python pass** (hai cảnh báo deprecation), **93 Java pass, 1 skip**, **8 JavaScript pass**;
  kiểm UTF-8 11 file và diff đều đạt. Kiểm Python cuối bao phủ provenance, vai, speech act, đối chứng câu phủ định, cardinality,
  dừng trước dịch và cả hai audit. Java kiểm năm bước/reservation và settlement V1/V3/V4.
  JavaScript kiểm màn xác nhận nguồn và tương thích giao diện. Kết quả tổng báo ở bàn giao.
- Toàn bộ capture mới có bản sao ngoài worktree, kiểm CRC ZIP, SHA-256 từng file và inventory
  raw khi có. Capture dở và chẩn đoán lỗi console vẫn giữ; receipt ghi rõ có/không seal.
- Raw của bài tổng hợp, prompt/schema/source và outcome được giữ để phát lại. Không đưa
  credential hoặc dữ liệu runtime vào Git. Chưa restart local hoặc deploy production.
