# Conversation v9 — thử prompt ngắn và làm rõ rubric

## Phạm vi đã chốt trước pilot

A1/A2/B1; audio và B2/C1/C2 chưa nằm trong vòng này. Candidate v9 chỉ dùng development,
không đổi server production hay mặc định collector. V7/v8, rubric v2 và báo cáo cũ giữ nguyên.

So sánh v8 với v9 trên cùng sáu context: một nhóm mỗi mức, lượt 2 và lượt 4.
Tối đa **12 generation call**, không chain, judge tự động, retry, repair hoặc fallback.
Quota kiểm tra trước chuẩn bị: 84/100 request ngày; giới hạn account/global 13 RPM, 100 RPD.
Không tăng hoặc reset quota. Đầu ra lỗi vẫn tính trong mẫu số và không gửi lại.

Biến thay đổi là `instruction`: từ 8.160 xuống 3.421 ký tự (giảm khoảng 58%).
Giữ nguyên contentSystem, few-shot, schema, teacher wrapper, translationSystem, model,
temperature 0.2 và output budget 8.192. Wrapper vẫn sinh content và translation cùng một call;
pilot này không đo riêng kiến trúc hai call của production hoặc hiệu quả sửa prompt dịch.

## Rubric v3: ranh giới để người duyệt áp dụng nhất quán

| Trường hợp | Quyết định trước khi xem pilot |
| --- | --- |
| “Lúc mấy giờ?” nhưng đáp án bắt buộc cả thứ Hai | FAIL alignment: ngày là ý riêng chưa hỏi |
| “Khi nào?” → chín giờ sáng thứ Hai | Có thể PASS như một thời điểm |
| “Tủ cạnh cửa vào” | Một địa điểm; mốc định vị không tự tạo thêm slot |
| Hỏi nơi để áo nhưng key thêm người thu áo | FAIL alignment |
| Hỏi vật cần mang hoặc sớm bao lâu | direct_detail dù lời nói ở dạng mệnh lệnh |
| Hỏi cần làm gì hoặc thứ tự | instruction_sequence |
| Hỏi điều kiện nào phải thỏa mãn | condition; hành động sau if không tự thành condition |
| Hỏi quan điểm | Key nêu đối tượng và đánh giá; lý do riêng cần câu hỏi “vì sao” |
| Khách hỏi có cần biên nhận hay không | Có thể hỏi khách nhắc giấy tờ nào; chưa chứng minh quy định cửa hàng |
| Kế hoạch “may visit Tuesday” | Chỉ được hỏi/đáp như khả năng, không biến thành ngày đã chốt |
| Giờ tập thử và thuyết trình bằng nhau | Không tự là mâu thuẫn; “vẫn thuyết trình lúc...” thiếu lịch đã xác nhận → REVIEW_REQUIRED |
| Giới tính/tuổi anh chị em chưa rõ | Không gán thêm trong textEn hoặc bản dịch; xưng hô trung tính tôi/bạn được chấp nhận |

Ví dụ nguồn đã xem: blind-026, 029, 031, 043; chain-1-t3 và chain-2-t3 của v8.
Không chấm lại hay thay điểm v8. Các kết luận trên chỉ áp dụng cho lượt review mới.
Rubric v3 thêm quy tắc ranh giới và giữ nguyên 11 checks, ba quyết định cùng phân loại severity.
Thiếu chứng cứ phải REVIEW_REQUIRED; không tự duyệt để đủ số lượng.

`boundary_cases_v3()` gồm 12 semantic probe theo sáu cặp đúng/sai, tập trung vào ranh giới.
Các probe cố ý không phải bài hoàn chỉnh/schema hợp lệ; không dùng để train, làm holdout
hoặc tuyên bố đã hiệu chuẩn judge. Rubric và review vẫn do AI:Codex thực hiện theo ủy quyền;
chưa có adjudication độc lập và không ghi thành human approval.

## Lệnh tái lập

```powershell
python -m training.freeze_candidate --version v9
python -m pytest -q training/tests/test_benchmark_teacher.py training/tests/test_generate_teacher_data.py training/tests/test_prepare_dataset.py
python -m training.benchmark_teacher --stage prepare --pilot --rubric conversation-semantic-v3 --baseline artifacts/prompts/conversation-cefr-v8-20261022.json --candidate artifacts/prompts/conversation-cefr-v9-20261022.json --output data/fine-tuning/semantic-v9-pilot-20261022
python -m training.benchmark_teacher --stage ab --output data/fine-tuning/semantic-v9-pilot-20261022
# Duyệt review-packet theo rubric đi kèm; lưu reviewer và hash task/context vào reviews.json.
python -m training.benchmark_teacher --stage report --output data/fine-tuning/semantic-v9-pilot-20261022
```

Plan/artifact từ chối ghi đè. Không chạy lại lệnh freeze/prepare lên file/thư mục đã tồn tại.
Request đã gửi nhưng mất kết quả không được replay. Record A/B đều developmentOnly;
pilot không xuất SFT và không thể đạt gate release dù cả 12 bài đạt.

## Gate và bước sau

Gate đầy đủ giữ nguyên: hard 30/30; semantic ít nhất 27/30, mỗi mức ít nhất 9/10;
zero critical; ít nhất 5/6 chain đủ ba lượt. Pilot chỉ giúp quyết định có đáng chạy tiếp.
Benchmark mới 78 call chỉ chuẩn bị khi quota đủ; chưa có lịch tự động gọi thêm.

Thử sửa dịch phải dùng transcript/English facts cố định, hai prompt dịch và cùng model;
không thay prompt nội dung trong cùng phép so sánh. Đặc biệt kiểm tra time-to/past,
by/before/until, AM/PM, tuổi anh chị em, giới tính và modality. Chỉ chốt prompt dịch
sau khi thử riêng, rồi kiểm tra flow production hai call.

Chỉ thu dữ liệu SFT sau khi vượt gate ngữ nghĩa/chain. Sau đó kiểm tra số nhóm tối thiểu,
coverage, split/holdout, duplicate, review provenance và chạy lại hardware preflight.
Chưa đủ căn cứ để train Qwen3.5-4B trên máy này.

## Kết quả pilot ngày 22/10/2026

| Nhánh | Hard | Semantic PASS | FAIL | REVIEW_REQUIRED | Critical | A1 / A2 / B1 PASS |
| --- | --- | --- | --- | --- | --- | --- |
| v8 | 6/6 | 2/6 | 4 | 0 | 0 | 1 / 1 / 0 |
| v9 | 6/6 | 3/6 | 2 | 1 | 1 | 2 / 1 / 0 |

12/12 HTTP 200, strict JSON/Unicode và độ dài đều hợp lệ. Model trả `deepseek-flash`.
Không gửi lại hoặc sửa kết quả. Tổng 65.470 token; median 2,475 giây/call.
Prompt token: v8 33.476, v9 27.080 (giảm khoảng 19% cho cùng sáu context).
Đây là chi phí đầu vào của combined teacher wrapper, không phải chi phí production.

Lỗi còn thấy: lặp lý do đối chiếu tên hoặc nơi nhận vé; gộp “hôm nay” vào key chỉ hỏi giờ;
dịch sister thành chị gái; gắn condition cho câu hỏi deadline cụ thể. V9 có lỗi critical:
“I can sell you a return ticket” bị hỏi thành loại vé khách **đã mua**.
Một bài v9 REVIEW_REQUIRED do lời “Thanks for coming on Sunday” chưa rõ cảm ơn trước
hay nhắc việc đã đến; bản dịch chọn nghĩa quá khứ. Không tự chốt thành PASS hoặc FAIL.

**Không chọn v9 làm bản thắng, không promote production, không xuất SFT.** Chênh một bài
trong sáu mẫu không chứng minh cải thiện; B1 chưa có bài đạt. Cả hai nhánh được duyệt mới
theo rubric v3; không so trực tiếp tỷ lệ này với điểm v8 cũ theo rubric v2.
Pilot chỉ có ba nhóm tình huống, không chain; focus thực tế là direct_detail (10 lượt)
và reason_purpose (2 lượt), chưa đo gist/opinion/inference. Chưa thử thay prompt dịch riêng.

Quota sau pilot **96/100** cho cả account và global; chưa đủ chuẩn bị benchmark 78 call.
Giữ nguyên hạn mức và kiểm tra lại trước bất kỳ lượt gọi mới nào.
Bước tiếp theo là giải quyết lỗi lặp ý và đề nghị/chưa xác nhận, đồng thời thử dịch với
English cố định trong thí nghiệm riêng. Chưa cần mở rộng lô dữ liệu hoặc train.

Báo cáo tổng hợp: `artifacts/reports/ai-platform-semantic-v9-pilot-20261022.json`.
Request/envelope/target/review hash được giữ ngoài Git tại
`data/fine-tuning/semantic-v9-pilot-20261022/`. Review là AI:Codex được ủy quyền,
không phải human approval và chưa có người/model khác adjudicate độc lập.
