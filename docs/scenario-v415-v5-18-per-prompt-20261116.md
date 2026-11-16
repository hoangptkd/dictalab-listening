# So sánh V4.15 và V5 — 18 mẫu mỗi phiên bản

Ngày 16/11/2026, múi giờ Asia/Bangkok. Đã thực sự gửi **36 yêu cầu sinh bài khác ID**:
18 đầu vào cho V4.15 và đúng 18 đầu vào tương ứng cho V5. Không sinh lại các mẫu thất bại.

**Trong đợt này, V5 hoàn tất nhiều hơn: 16/18 so với 12/18.** Cả hai vẫn có lỗi ngữ nghĩa mà
reviewer và judge bỏ sót. Kết quả chưa chứng minh V5 chấm công bằng hơn hoặc đủ để thay mặc định.
Không sửa prompt, schema, model pool, fallback hay bộ kiểm tra trong khi chạy.

## Phạm vi

- Đợt 1: 9 mẫu Regression sẵn có; mỗi bản chạy một lần. Ba tình huống gốc được đặt trong
  9 tổ hợp chương trình/profile: giao tiếp A1/A2/B1 và ba mức nội bộ của TOEIC, IELTS.
- Đợt 2: 9 tình huống văn bản hoàn toàn mới, giữ các profile và focus `main-idea` tương ứng;
  chia đều ba mẫu cho mỗi chương trình. Hai phiên bản được luân phiên chạy trước theo từng mẫu.
- Tổng cộng 18 tổ hợp đầu vào/profile mỗi bản, 12 nội dung tình huống văn bản khác nhau.
  Các mức TOEIC/IELTS là profile luyện tập nội bộ, không phải chứng nhận năng lực người học.
- Đợt 1 đi qua Core Evaluation Studio; đợt 2 dùng endpoint đánh giá nội bộ đã có của cùng AI service.
  Cùng pipeline EXTRACT → GENERATE → QUESTIONS → REVIEW_QUESTIONS → TRANSLATE → REVIEW,
  cùng nhánh sửa có giới hạn, cùng backend và program snapshots. Không ghi trực tiếp ledger Studio.
- Judge cuối dùng cùng nguyên văn chỉ dẫn và cùng quy tắc kiểm tra bằng chứng của Studio;
  đợt 2 chỉ chấm artifact cuối, đợt 1 có thể chấm cả bản trước sửa. Đây là judge AI chưa hiệu chuẩn bằng nhãn người dùng.

## Kết quả

| Chỉ số | V4.15 | V5 |
|---|---:|---:|
| Yêu cầu đã chạy | 18 | 18 |
| Hoàn tất đợt 1 | 6/9 | 8/9 |
| Hoàn tất đợt 2 | 6/9 | 8/9 |
| Tổng bài hoàn tất | 12/18 (66,7%) | 16/18 (88,9%) |
| Bài ban đầu qua kiểm tra cấu trúc, trước sửa | 4/18 | 12/18 |
| Bị từ chối vì nội dung/kiểm tra sau sửa | 6 | 2 |
| Judge cuối cho PASS | 12 | 16 |
| Không có bài đầy đủ cho judge | 6 | 2 |
| Nhóm bước phải thử sửa | 14 | 8 |
| Patch được harness chấp nhận | 8 | 6 |
| Đáp án tham chiếu được GRADE chấp nhận đầy đủ | 12/12 | 16/16 |

PASS của judge không phải nhãn đúng do con người xác nhận. Cả 28 bài hoàn tất đều được judge cho PASS,
nhưng đọc nguồn vẫn tìm thấy lỗi bên dưới. “Trước sửa đạt cấu trúc” chỉ đo validator, không đo chất lượng sư phạm.

Đối chiếu theo cặp: **5 mẫu chỉ V5 hoàn tất, 1 mẫu chỉ V4.15 hoàn tất, 11 mẫu cả hai hoàn tất,
1 mẫu cả hai thất bại.** Không tính các bài bị từ chối là PASS hoặc xóa khỏi mẫu số 18.

## Từng mẫu

“Hoàn tất” là pipeline tạo được artifact; các ô này không khẳng định nội dung hoàn hảo.

| # | Mẫu | V4.15 | V5 |
|---:|---|---|---|
| 1 | Giao tiếp A1 — chọn hoạt động cuối tuần | Hoàn tất | Bị từ chối |
| 2 | Giao tiếp A2 — công việc chậm tiến độ | Bị từ chối | Hoàn tất |
| 3 | Giao tiếp B1 — đề án môi trường | Hoàn tất | Hoàn tất |
| 4 | TOEIC hỗ trợ — hoạt động cuối tuần | Hoàn tất | Hoàn tất |
| 5 | TOEIC tiêu chuẩn — chậm tiến độ | Bị từ chối | Hoàn tất |
| 6 | TOEIC thử thách — đề án môi trường | Hoàn tất | Hoàn tất |
| 7 | IELTS hỗ trợ — hoạt động cuối tuần | Hoàn tất | Hoàn tất |
| 8 | IELTS tiêu chuẩn — chậm tiến độ | Bị từ chối | Hoàn tất |
| 9 | IELTS thử thách — đề án môi trường | Hoàn tất | Hoàn tất |
| 10 | Giao tiếp A1 — mượn và trả sách | Hoàn tất | Hoàn tất |
| 11 | Giao tiếp A2 — đổi cỡ áo, không hoàn tiền | Bị từ chối | Hoàn tất |
| 12 | Giao tiếp B1 — giờ luyện đàn và ca đêm | Bị từ chối | Bị từ chối |
| 13 | TOEIC hỗ trợ — máy in hết giấy | Bị từ chối | Hoàn tất |
| 14 | TOEIC tiêu chuẩn — giao nhầm phòng 203/302 | Hoàn tất | Hoàn tất |
| 15 | TOEIC thử thách — bàn giao và duyệt bản cuối | Hoàn tất | Hoàn tất |
| 16 | IELTS hỗ trợ — tìm hiểu lớp vẽ | Hoàn tất | Hoàn tất |
| 17 | IELTS tiêu chuẩn — khảo sát và quyền riêng tư | Hoàn tất | Hoàn tất |
| 18 | IELTS thử thách — mẫu phỏng vấn lịch sử truyền khẩu | Hoàn tất | Hoàn tất |

## Các lỗi đã đối chiếu trực tiếp

- **V5, giao tiếp A2 cũ, a2/q1–q2:** câu kiểm tra phần mềm có phải nguyên nhân và việc cần thêm thời gian
  được gắn `main-idea`. Nội dung thực kiểm tra chi tiết/nguyên nhân. Cả reviewer và judge đều duyệt.
- **V5, giao tiếp B1 cũ, a3/q1:** nguồn `for the student council to review next week` nói hội đồng xem xét
  vào tuần tới; câu hỏi lại hỏi Nam cần *viết* gì vào tuần tới. Transcript còn nói Nam bắt đầu viết ngay.
- **V5, TOEIC tiêu chuẩn cũ, a1/q1:** “Tại sao dự án bị chậm tiến độ?” được gắn `main-idea` dù hỏi lý do.
- **V5, IELTS thử thách cũ, a1/q1:** nguồn/part chấm yêu cầu `the only convenient option`; đáp án Việt chỉ nói
  “trở nên thuận tiện”, bỏ tính duy nhất. GRADE vẫn cho CAPTURED trên đáp án tham chiếu này.
  Phạm vi part chấm so với câu hỏi mục tiêu chính cũng cần người dùng duyệt.
- **V5, IELTS thử thách cũ, a2/q1:** chính Nam đề xuất chuyển đổi theo giai đoạn, giảng viên tán thành;
  câu hỏi lại gán việc đề nghị trực tiếp đó cho giảng viên.
- **V4.15, tình huống giao nhầm phòng mới, a1/q1:** câu “Đoạn hội thảo luận về vấn đề gì…” bị thiếu/sai từ,
  cần là “Đoạn hội thoại…”. Judge vẫn cho PASS. Đây là lỗi diễn đạt, không phải lỗi sinh bài.
- **V5, bàn giao mới, a3/q2:** đáp án thái độ “Rất hoàn hảo và cảm ơn…” chưa tự nhiên; đang lặp lời đáp
  `Perfect` thay vì diễn tả rõ sự hài lòng/biết ơn. Không dùng PASS để khẳng định văn phong đã tốt.

Các quan sát này do agent đối chiếu với transcript, chưa phải bộ gold do chủ dự án duyệt; không tự sửa đầu ra đã đo.
Không có kết luận rằng danh sách trên là toàn bộ lỗi.

## Diễn giải GRADE

Mỗi bài hoàn tất được chọn câu hỏi đầu tiên của a1; gửi đúng answerVi do chính bài đó cung cấp qua
hàm GRADE thực của worker, với cùng prompt/hash/profile. Đã gọi thật 28 lần; không ghi điểm vào lịch sử học viên.
Tám mẫu không có bài hoàn chỉnh được ghi là không có probe GRADE; không tự dựng đáp án để lấp chỗ thiếu.

12/12 và 16/16 ở bảng chỉ có nghĩa model chấp nhận đáp án tham chiếu của bài mình đang chấm.
Đây **không phải độ chính xác 100%**: khóa có thể thiếu ý, part có thể vượt phạm vi câu hỏi; ví dụ từ “only” ở trên.
Các bài giữa hai phiên bản khác transcript/khóa, nên phép thử này không tách riêng tác động của prompt GRADE.
Đợt này không bao phủ đáp án sai, thiếu ý, trộn ngôn ngữ hay khóa hỏng có kiểm soát. Kết quả lỗi khóa thừa ý
đã ghi trong [báo cáo V5 trước đó](scenario-prompts-v5-verification.md) vẫn còn hiệu lực; không sửa GRADE giữa hai đợt.

## Lượt gọi và giới hạn so sánh

| Lượt gọi logic | V4.15 | V5 |
|---|---:|---:|
| Pipeline sinh bài, gồm sửa/kiểm duyệt | 111 | 115 |
| Judge | 12 | 17 |
| Probe GRADE | 12 | 16 |

Một fallback HTTP không phải một bài mới. Số lần gọi/chi phí còn chịu ảnh hưởng số bài hoàn tất và số lần judge,
nên không lấy tổng gọi để khẳng định prompt nào rẻ hơn. Không có phép đo giá tiền.

9/18 cặp có ít nhất một loại bước chung trả về model thực khác nhau. Model cấu hình là
Gemini 3.5 Flash Lite, fallback Gemini 3.1 Flash Lite vẫn bật. Không tắt fallback, thay cấu hình pool/tài khoản provider hoặc reset quota.

V5 dùng kiểm duyệt từng part và hợp đồng GRADE có transcript/assessmentIssue; V4.15 dùng các hợp đồng đã ghim của nó.
Đây là so sánh hai gói phiên bản thực tế, không phải A/B chỉ thay văn bản prompt trong cùng một schema.
Số mẫu nhỏ, tình huống hư cấu, tất cả tập trung vào main-idea; chưa thử mọi focus, mọi CEFR, TTS/audio hoặc hiệu quả học dài hạn.

## Bằng chứng và tái kiểm tra

- [Studio: so sánh 9 mẫu đợt đầu](http://localhost:18082/admin/ai-evaluation?view=compare&left=dd1ef0ce-6f1a-4c7f-bbdc-ab3247618d0c&right=d721a0a2-4739-494c-ae03-5712d3a589f2) — đăng nhập rồi nhấn So sánh nếu cần.
- Kết quả thô và probe được giữ ngoài Git trong `C:/Users/HP/.dictalab/local/`:
  `fresh-v5-regression18-20261116.json`, `fresh-v5-extra9-20261116.json`,
  `fresh-v5-regression18-grades-20261116.json`, `fresh-v5-extra9-grades-20261116.json`,
  `fresh-v5-combined36-summary-20261116.json`.
- ID của 36 yêu cầu khác nhau và hai payload trong mỗi cặp được kiểm tra khớp hoàn toàn.
- Phiên đọc tiến độ đợt 1 hết hạn; đã dùng GET lấy lại đúng run ID đã có, không tạo lại yêu cầu inference.
  Các phiên kiểm thử riêng đã logout. Không tạo tài khoản hay thu hồi phiên của người khác.
- Không đưa credential/runtime data vào Git; giữ các trường hợp bị từ chối và mọi cảnh báo. Không chỉnh ledger Studio.

Backend AI: `b65f0d4b97d956b0315058c6497c2f5009d4aec90ef0fba194dfc72627eee1c0`.

Dataset đợt 1: `7106ae82769265ddde19e16565c504a7fca12186edf6c342fdac813c6a52122e`.

Dataset đợt 2: `c75da30401cb7e3a3663410f2c97d39597d562a4dfcb5b7dfb397007d4a27174`.

V4.15: `f03dd35a2443d8a2ad69e9d3995de3638116b3749261f666b6f8c19f5b73ff89`.

V5: `b8026ffc1dabdb09393c3b419b41750ca51ece9a242363482e3a337f4a61697b`.

Judge: `07c0a55526a6ae85e554f63536cae97c59e784c9d71c88f37484216338d7f4c9`.

Nguồn ứng dụng được kiểm thử ở commit `9553b80713f71be0007f50a01fc3f09df9031379`.
Có thể chạy lại chương trình tổng hợp offline (không gọi AI) trong thư mục bằng chứng;
không chạy lại các runner inference chỉ để tái tạo báo cáo.

Chưa đổi bản mặc định, merge main hoặc triển khai production. Báo cáo được commit/push riêng; CI của thay đổi mã nguồn
trước đó đã đạt, commit chỉ thêm báo cáo không mặc nhiên có một lần CI mới.
