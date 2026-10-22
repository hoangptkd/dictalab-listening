# Khắc phục chất lượng sinh bài — v8

## Phạm vi

A1/A2/B1, chưa xử lý audio. B2/C1/C2 vẫn chưa bật trong sản phẩm.
Đích SFT là `Qwen/Qwen3.5-4B`. Artifact v8 là ứng viên development;
model trong artifact chỉ là model tham chiếu của hợp đồng cũ, chưa phải model được duyệt với v8.
Không thay cấu hình production, không tự chạy huấn luyện.
Java gọi Python bằng alias hợp đồng `V1` đã có; Python tiếp tục chọn artifact
được cấu hình trên server (mặc định v7), không tự nâng lên v8 theo mã Java.

## Quy tắc phân loại

| Loại | Ý nghĩa thực sự được hỏi | Không được gắn nhãn chỉ vì |
| --- | --- | --- |
| `direct_detail` | Thông tin cụ thể hoặc vật được nêu rõ | Câu chứa `if` nhưng chỉ hỏi vật thay thế |
| `gist` | Thông điệp chính của đoạn | Hỏi một lý do riêng lẻ |
| `reason_purpose` | Vì sao hoặc mục đích | Bất kỳ câu nào có `because` ở phần nền |
| `condition` | Điều kiện cần thỏa mãn | Hỏi hành động hoặc tên đồ vật trong câu điều kiện |
| `instruction_sequence` | Việc cần làm hoặc thứ tự | Đã nói thẳng bước tiếp theo nhưng gọi là suy luận |
| `change_revision` | Giá trị hoặc kế hoạch cuối sau thay đổi rõ ràng | Tự đổi đồ vật hoặc cuộc hẹn giữa hai lượt |
| `opinion_attitude` | Đánh giá, quan điểm được diễn đạt | Đoán giọng nói hoặc cảm xúc thiếu bằng chứng |
| `inference_prediction` | Kết luận chưa nói trực tiếp, suy ra từ các tín hiệu liên kết | Chép lại câu đã nói rõ, hoặc dùng kiến thức ngoài |

Phân loại theo câu hỏi và slot, rồi đối chiếu đáp án và bằng chứng. A1 chỉ hỏi
hai chi tiết trực tiếp; A2/B1 production cho phép 2–3 slot. Dữ liệu teacher nhắm
hai slot ở A1 và ba slot ở A2/B1. B1 vẫn cho phép ý chính, quan điểm và suy luận có căn cứ.

### Vai nói và trạng thái thông tin

- `partnerRole` là người nói; `learnerRole` là người nghe. Không đổi vai khi đặt câu hỏi.
- Khách có thể nêu kế hoạch, trình tự hoặc ràng buộc của chính họ.
- “Should I bring a receipt?” chỉ xác nhận khách hỏi về biên nhận. Chưa có căn cứ
  nói cửa hàng bắt buộc mang biên nhận. Không tự tạo lời đáp của người nghe.
- Lượt 2/3 giữ cùng món hàng/đơn đặt chỗ/hoạt động; thay đổi thực tế phải được báo rõ.
- `encounterState` chỉ lập chỉ mục lời nói và các ý đã hỏi. `currentObject=null`
  có nghĩa chưa có trường dữ liệu xác nhận; không giả vờ đã giải nghĩa tự động toàn bộ lịch sử.
- Lịch sử cũ thiếu người nói giữ `unknown`. Slot/taskType được giữ nếu nguồn có cung cấp.
- Lượt 4 bỏ lịch sử, journey, blueprint và recentPractice; chỉ mang chỉ mục đáp án cần tránh.

### Dịch và độ dài

Giữ nguyên khả năng/đề nghị/bắt buộc, phủ định, điều kiện, số lượng, tên và mã.
`by Friday evening` là **chậm nhất vào tối thứ Sáu**, có bao gồm hạn cuối;
`before` là **trước**, `until` là **cho đến**. `brother/sister` chưa xác định
tuổi tương đối: dùng **anh/em trai**, **chị/em gái** nếu không có dữ kiện khác.

Khoảng nháp theo profile chuẩn: A1 16–26, A2 29–43, B1 48–72 từ.
Khoảng hợp lệ vẫn lấy từ `levelProfile`, kể cả phiên cũ. Đếm từ của `utteranceEn`
theo khoảng trắng ASCII. Lịch sử ngắn không phải mẫu độ dài cho bài mới.
Không viết lại hàng loạt starter B1 trong thay đổi này.

## Kiểm định và phán quyết

Hard validation giữ schema, JSON nghiêm ngặt, Unicode, độ dài, số slot/fact,
trích dẫn và hash. Đây chưa phải chứng minh ngữ nghĩa đúng.

Rubric `conversation-semantic-v2` có 11 kiểm tra: vai, lịch sử, ý mới,
câu hỏi/slot/fact, dịch, CEFR, suy luận, lộ đáp án, trạng thái thông tin,
taskType và đáp ứng focus. Có 24 ví dụ calibration thuộc 8 nhóm lỗi.
Đây là dữ liệu development, loại khỏi train và holdout cuối.

- `PASS`: tất cả kiểm tra đúng, không có issue.
- `FAIL`: có lỗi được xác định.
- `REVIEW_REQUIRED`: thiếu hoặc chưa chắc phán quyết; không được xuất sang SFT.
- Mức độ lỗi tách riêng: critical/major/minor/none. Sai vai, đáp án không có
  căn cứ và suy luận giả là critical; không đồng nhất lỗi diễn đạt với lỗi logic.

Collector Gemini yêu cầu judge vượt calibration trước khi sinh dữ liệu. Benchmark
DeepSeek dùng AI:Codex theo ủy quyền của chủ dự án, không dùng judge tự động để duyệt.
Không khai báo AI review thành human review. Chuẩn bị dataset dùng
`--review-policy delegated-ai` và giữ reviewer/hash từng dòng.

## Chạy lại và giới hạn

```powershell
# Xuất hợp đồng Java; tạo artifact mới chỉ khi file đích chưa tồn tại.
mvn --batch-mode --no-transfer-progress '-Dtest=ConversationB1EvidenceTest#registeredCefrCandidateMatchesTheJavaGenerationContract' '-Dconversation.cefrExport=true' test
python -m training.freeze_candidate

python -m pytest -q services/ai-generation/tests tools/ai-eval training/tests/test_generate_teacher_data.py training/tests/test_benchmark_teacher.py training/tests/test_prepare_dataset.py

python -m training.benchmark_teacher --stage prepare --output data/fine-tuning/NEW-RUN
python -m training.benchmark_teacher --stage ab --output data/fine-tuning/NEW-RUN
# Duyệt review-packet-*.json, ghi reviews.json theo hash của task/context.
python -m training.benchmark_teacher --stage chain --output data/fine-tuning/NEW-RUN
# Duyệt từng vòng trước khi gọi stage chain lần tiếp theo.
python -m training.benchmark_teacher --stage report --output data/fine-tuning/NEW-RUN
```

Runner dùng registry mã hóa, ModelGateway và quota chung. Không ghi credential
vào artifact, request log hoặc đối số. Không gửi lại request đã có dấu vết gửi
nhưng thiếu kết quả. File candidate đã có sẽ được bỏ qua khi tiếp tục lượt chưa gửi.

30 context giống nhau mỗi nhánh, 10 context/mức; cùng model, temperature 0.2,
8192 output token và teacher wrapper. Biến thay đổi trong A/B là artifact prompt;
context mới được giữ giống nhau để so sánh. Điều này không đo riêng tác động
của việc chuyển từ context cũ sang context mới. Kết quả combined teacher
không tương đương benchmark hai cuộc gọi content/translation production.

Tối đa 60 generation call A/B và 18 call cho 6 chuỗi mới, không retry/repair/fallback.
Chuỗi dừng khi hard hoặc semantic review chưa đạt; không dùng bài pending làm lịch sử SFT.
Gate development: hard 30/30, semantic ít nhất 27/30 và 9/10 mỗi mức,
không critical; ít nhất 5/6 chuỗi đạt cả ba lượt. Lỗi API báo riêng, không xóa khỏi mẫu số.
Không đổi gate sau khi đã xem kết quả. Không tự chọn một nhánh thắng nếu cả hai chưa đạt.

## Chuẩn bị SFT

Giữ nguyên target/review cũ. A/B và calibration không dùng để train. Chỉ xuất
content/translation của chuỗi đủ ba lượt được duyệt. Nếu có nhiều phiên bản
cùng group thì chọn một phiên bản sau khi duyệt; không nối dữ liệu mù quáng.
Giữ nguyên kiểm tra duplicate, holdout, cặp content/translation, group split,
số lượng/coverage, checkpoint, loss mask và RAM. Benchmark development đạt
cũng chưa đồng nghĩa đã đủ dữ liệu hoặc đủ phần cứng để train.

## Kết quả thực nghiệm ngày 22/10/2026

| Nhánh | HTTP 200 | Hard pass | Semantic pass | A1 / A2 / B1 đạt |
| --- | --- | --- | --- | --- |
| v7 | 30/30 | 29/30 | 17/30 | 8 / 6 / 3 |
| v8 | 30/30 | 30/30 | 10/30 | 6 / 2 / 2 |

Cả 60 bài A/B đều có JSON/Unicode và độ dài hợp lệ. Một bài v7 có bốn
slot/fact nên không đạt schema. V8 đạt cấu trúc nhưng ngữ nghĩa thấp hơn v7
trong mẫu development này. **Không chọn v8 làm bản thắng, không cho phép release.**
Không suy ra quan hệ nhân quả chắc chắn từ mẫu nhỏ hoặc coi v7 đã đủ chất lượng.

Chuỗi: 6 nhóm, 8 lượt thực sự được gửi; hai nhóm qua lượt 2 rồi một nhóm lỗi
lượt 3, nhóm còn lại cần làm rõ lịch tập thử/thuyết trình. Bốn nhóm dừng ở lượt 2.
Không gửi 10 lượt phụ thuộc còn lại; không nhóm nào đủ điều kiện xuất SFT.

Tổng cộng 68/78 generation call, 68 HTTP 200, model trả về `deepseek-flash`.
Không retry, repair, fallback hoặc gọi judge tự động. Dùng 401.538 token;
median khoảng 2,55 giây/call. Quota nội bộ sau chạy: 84/100 request ngày,
bao gồm 16 lượt đã dùng trước benchmark.

Các nhóm lỗi còn lại nổi bật: câu hỏi/slot/fact không cùng ý hoặc gộp phần chưa
hỏi; dịch tự thêm tuổi/giới tính/AM-PM hoặc sai thời điểm; taskType không khớp;
focus chưa được đáp ứng; thông tin chỉ được hỏi bị hiểu thành quy định xác nhận.
Các số đếm lỗi là không loại trừ nhau, không cộng thành số bài lỗi.

AI:Codex là người viết rubric và cũng duyệt bài theo ủy quyền; chưa có adjudication
độc lập. Bộ 24 calibration có các cặp diễn đạt UI tương đương, chưa đại diện đầy đủ
cho mọi biến thể lỗi. Không dùng kết quả này làm chứng cứ hiệu chuẩn judge tự động.

Báo cáo tổng hợp có hash và gate: `artifacts/reports/ai-platform-semantic-v8-20261022.json`.
Request/envelope/target/review riêng được giữ tại
`data/fine-tuning/semantic-v8-ab-20261022/`, ngoài Git. Bản v7 không bị sửa.
Vòng tiếp theo cần candidate mới, thử prompt ngắn hơn; kiểm thử tách dịch thành
biến độc lập. Chưa chạy thêm API hoặc train sau khi chốt đợt này.
