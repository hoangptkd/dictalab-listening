# Đánh giá EXTRACT / GENERATE — 31/10/2026

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
