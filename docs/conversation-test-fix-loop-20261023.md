# Vòng test và sửa, mục tiêu 80%

Ngày 23/10/2026. **Đạt mục tiêu ở vòng 7: 24/30 (80%)** được Codex duyệt độc lập.
Đã dừng thử API. Chủ dự án đã sửa ChatGPT Web và cho tiếp tục thử.
Không chuyển Web PASS thành duyệt cuối, không nới rubric hoặc sinh bù bài lỗi.

## Kết quả thật

| Vòng | Prompt | Lượt sinh đã gửi | Hard đạt | Web hợp lệ | Web PASS | Codex duyệt cuối |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| 1 | v3 | 30 | 28 | 25 | 24 | 10/30 (33,3%) |
| 2 | v4, harness lỗi | 8 | Không dùng để kết luận chất lượng | 0 | 0 | Hủy vòng |
| 3 | v4, harness đã sửa | 30 | 29 | 27 | 25 | **15/30 (50%)** |
| 4 | v5 | 24/30 dự kiến | 22 | 19 | 15 | 10/24 (41,7%); 6 chưa chạy |
| 5 | v6 | 30 | 27 | 26 | 24 | **16/30 (53,3%)** |
| 6 | v7, teacher thinking | 30 | 27 | 27 | 26 | **23/30 (76,7%)** |
| 7 | v8, context/guard/judge | 30 | 27 | 27 | 23 | **24/30 (80%)** |

Vòng 2: bảy phản hồi đã nhận bị loại do bộ kiểm tra SFT còn dựng prompt theo footer
cũ, lệch prompt generation mới. Lượt thứ tám đã gửi khi dừng worker; kết quả chưa xác
định. Đã sửa bằng một hàm dựng system prompt dùng chung và kiểm tra `build_records`.
Tám lời gọi này vẫn tính vào chi phí, không replay hoặc tái phân loại thành đạt.

Tổng: **182 lời gọi DeepSeek, 157 yêu cầu bridge Web**, kể cả lỗi vận hành. Envelope
DeepSeek đã lưu ghi nhận **1.246.757 token**; chưa bao gồm lượt thứ tám chưa rõ kết quả
ở vòng 2. Không có số tiền thực thu hoặc tổng token Web đáng tin để báo cáo.

Phân bổ vòng đầy đủ: A1/A2/B1/B2 = 8/8/7/7. Vòng 3 duyệt tương ứng 6/3/3/3.
Vòng 4 đã gửi 6 bài/mức, duyệt tương ứng 2/5/2/1; chưa thể so như hai lô đầy đủ.
Vòng 4 có 11 bài phù hợp nội dung, nhưng bài 24 không có kết quả Web hợp lệ nên
không được duyệt. Trong 15 Web PASS của vòng này, Codex loại 5 bài; Web vẫn bỏ sót.
Vòng 5 duyệt A1/A2/B1/B2 tương ứng **4/8, 4/8, 6/7, 2/7**. Còn lỗi lặp thuộc tính,
thêm số lượng/nơi chốn ngoài câu hỏi, revision không có thay đổi thật, suy luận đã
nói trực tiếp và tham chiếu mơ hồ. Ba lỗi hard gồm thiếu trường bắt buộc và sai schema.
Vòng 6 duyệt tương ứng **7/8, 6/8, 6/7, 4/7**. Bảy bài loại: hai phản hồi bị cắt
ở trần token; một đáp án dịch quá 160 ký tự; một nghĩa mới chưa phân biệt rõ với
thuộc tính cũ; một taskVi gọi nhân viên phục vụ là “máy chủ”; một taskVi nâng đề xuất
thành thỏa thuận; một cặp câu hỏi tiết lộ đáp án cho nhau. Web bắt được lỗi thỏa thuận,
nhưng bỏ sót ba bài khác Codex loại. Không dùng Web PASS làm tỷ lệ duyệt cuối.
V6/V7 giữ cùng prompt/model/schema; bật thinking đưa số token DeepSeek từ **154.446**
lên **277.848** trên 30 bài, khoảng **1,80 lần**, đồng thời có hai lỗi bị cắt phản hồi.
Đây là so sánh hai lô mới cùng ma trận, không phải bằng chứng nhân quả hoặc kiểm định thống kê.

### Chi tiết vòng đạt 80%

| Mức | Được duyệt | Tỷ lệ |
| --- | ---: | ---: |
| A1 | 8/8 | 100% |
| A2 | 7/8 | 87,5% |
| B1 | 4/7 | 57,1% |
| B2 | 5/7 | 71,4% |

**80% là tỷ lệ chung của lô này; B1/B2 chưa đạt 80% riêng.** B2 dùng hợp đồng compact
thử nghiệm 30–90 từ, chưa chứng minh đầy đủ năng lực nghe B2 theo CEFR hoặc chất lượng audio.
Sáu bài loại nguyên trạng:

- 0008, 0020: phản hồi teacher bị cắt ở trần 8.192 token.
- 0018: evidence không khớp nguyên văn nguồn.
- 0011: gist hỏi lại phân công đã kiểm tra trong cùng lần gặp.
- 0023: câu hỏi chỉ hỏi giờ nhưng đáp án thêm ngày thứ Bảy.
- 0027: taskVi tiết lộ chức năng xác nhận lịch hẹn của đáp án gist.

Web có 23 PASS. Codex loại hai Web PASS (0023, 0027), giữ bài 0011 bị Web báo lỗi,
và bác lỗi giả có căn cứ để duyệt ba bài (0004, 0024, 0028): **23 − 2 + 3 = 24**.
Các căn cứ bác lỗi được ghi riêng cho từng check và gắn hash kết quả Web:

- 0004/0024/0028: Web tự đòi nhiều hơn một đáp án xử lý quan hệ, trong khi levelDemand
  đã khóa chỉ yêu cầu **ít nhất một**; đáp án so sánh/lập luận thực tế đã đáp ứng.
- 0028: câu hỏi đã nêu quan hệ điều kiện; đáp án ngắn “Để lại thẻ căn cước tại quầy”
  trả đúng điều kiện cần. Không cần lặp nguyên câu only-if trong đáp án. Bản dịch toàn
  bài vẫn giữ quan hệ điều kiện. Không sửa rubric để bác lỗi này.

Không sửa, gọi lại hoặc thay các bài lỗi. Vòng 7 dùng **290.118 token DeepSeek**;
không có chi phí thực thu hoặc tổng token Web để kết luận về tiền. Mẫu 30 bài nhỏ,
không phải bảo đảm độ tin cậy chung hoặc một tập đủ để train.

## Nguyên nhân và sửa đổi

- **Lệch slot/câu hỏi/đáp án:** v4 thêm kế hoạch loại câu hỏi theo từng vị trí. V5
  thử wire `targets`: câu hỏi, đáp án và evidence nằm trong một đối tượng. Runner
  kiểm định JSON gốc trước rồi chiếu nguyên văn thành slots/facts và nối askVi.
  Đây là định dạng có phiên bản, không repair; schema cuối và validator giữ nguyên.
- **Lặp nghĩa:** model vẫn thay số, vật hoặc nơi cất để hỏi lại cùng thuộc tính đã
  kiểm tra. V6 yêu cầu đối chiếu thuộc tính với cả history và previouslyTestedDetails.
- **Sai taskType:** tiền đề mượn bị gán direct_detail; hành động bị gán condition;
  trách nhiệm không đổi bị gán change_revision. Prompt yêu cầu phân loại từ câu hỏi
  cuối, và revision phải có giá trị cũ khác giá trị mới thật.
- **Sai phạm vi/ngữ nghĩa:** đáp án quan điểm thêm nguyên nhân chưa hỏi; câu hỏi về
  sân ga thật chỉ được trả lời bằng điều khách chưa biết; câu suy luận lặp kế hoạch
  đã nói thẳng. Bổ sung ranh giới chung về thuộc tính, trạng thái và kết luận suy ra.
- **Dịch từ đa nghĩa/tham chiếu:** folder trên giá bị dịch thành thư mục máy tính;
  it sau hai danh từ không rõ đối tượng. V6 yêu cầu dùng tên rõ và dịch theo ngữ cảnh.
- **Quá dài:** hai askVi v5 vượt 55 ký tự. Giữ nguyên validator; V6 hướng tới 20–40
  ký tự và dùng mẫu hỏi so sánh ngắn. Không cắt chuỗi phản hồi để làm bài đạt.
- **Thiếu bước đối chiếu trước khi trả lời:** V7 thử bật DeepSeek thinking riêng cho
  teacher, giữ nguyên prompt, schema, model, temperature và trần 8.192 token của V6.
  Chế độ được khóa vào artifact/manifest và kiểm tra trên HTTP request thật. Production
  mặc định vẫn tắt thinking. Đây là thay đổi cấu hình sinh cần đo bằng lô mới.
- **Context lượt 4 mất nghĩa đã hỏi:** chỉ còn đáp án như “Tám giờ” thay vì nguồn
  “The library opens at eight”. V8 giữ nguyên fact và nguồn của lần gặp trước để
  phân biệt thuộc tính; không mang sắp xếp cũ vào lần gặp mới. Bộ kiểm tra Web nhận
  trích dẫn chính xác từ nguồn này và vẫn loại trích dẫn tự tạo. V8 đã chạy đủ 30 bài.
- **Bỏ sót ngoài facts:** judge mới duyệt cả taskVi và các câu hỏi, áp dụng vai/nghĩa
  từ theo tình huống và kiểm tra câu hỏi này có tiết lộ đáp án câu khác hay không.
  Teacher nhận cùng nguyên tắc và giới hạn cả textEn/textVi ở 160 ký tự, hướng tới
  120 ký tự. Judge mới chỉ dùng trong campaign vòng 7; không sửa bằng chứng vòng cũ.

**Collection mới mặc định dùng V8**, bản đã đo đạt 24/30. V7 đạt 23/30. Đây là mốc
phát triển cho luồng teacher sinh chung nội dung + bản dịch, chưa được nhận production.
Thinking chỉ bật theo artifact/manifest teacher. Không hồi tố sửa artifact/manifest cũ;
metricReport trong artifact là trạng thái tại lúc đóng băng, bằng chứng live nằm ở báo cáo này.
Production Java, rubric `conversation-semantic-v3`, giới hạn từ, không repair/retry
và quyền duyệt độc lập giữ nguyên. Không train/deploy trong lượt thử này.

## Biên bản local

Trong `data/fine-tuning/`, các thư mục sau có `session-report.json`,
`review-details.html`, `independent-decisions/` và `evidence-integrity.json`:

- `diagnostic-v3-loop-r1-20261023`
- `diagnostic-v4-loop-r3-20261023`
- `diagnostic-v5-loop-r4-20261023`
- `diagnostic-v6-loop-r5-20261023`
- `diagnostic-v7-loop-r6-20261023`
- `diagnostic-v8-loop-r7-20261023`

Vòng 2 có checkpoint `ABORTED_HARNESS_BUG`. Vòng 4 có `interruption.json` và
`WAITING_WEB_QUOTA`. Biên bản bài loại được giữ trong `rejected-evidence/` theo
quyền giữ bằng chứng diagnostic; không vào dataset SFT. Chi tiết không đưa lên Git.
Bridge đã dùng một tab Chrome và mở hội thoại mới cho mỗi yêu cầu. Không tắt máy.

## Kết thúc phiên và tái lập

Giới hạn phiên đã thống nhất: tối đa 10 vòng, đã dùng 7 vòng gồm vòng lỗi harness.
Đã đạt 80%, không chạy vòng 8–10 hoặc train/deploy. Biên bản toàn bộ 30 bài vòng 7
đã lưu local để chủ dự án xem, kể cả bài loại và các trường hợp bác lỗi Web.
Không gọi lại các yêu cầu cũ. Vòng 4 không thể đạt 24/30 dù sáu bài còn lại đều đạt,
nên không cần tiêu thêm API vào phần đuôi chỉ để tìm bản thắng.
Các lệnh dưới mô tả lượt đã hoàn tất, **không chạy lại campaign cũ**:

```powershell
python -X utf8 -m training.collect_reviewed prepare --campaign data/fine-tuning/diagnostic-v8-loop-r7-20261023 --artifact artifacts/prompts/conversation-reviewed-collection-v8-20261023.json --diagnostic --retain-diagnostic-evidence
python -X utf8 -m training.collect_reviewed collect --campaign data/fine-tuning/diagnostic-v8-loop-r7-20261023 --limit 30
```

Muốn đánh giá lại phải dùng campaign mới. Codex đọc nguồn/context từng bài trước khi
so Web, lưu quyết định có hash/evidence; không tự động nhận theo PASS. Không dùng
test cũ để chứng minh một ứng viên mới đã đạt.

Kiểm tra offline cuối cùng: **210 passed, 1 skipped**; kiểm tra UTF-8 đạt trên 11 file,
diff của tác vụ không có lỗi khoảng trắng. Mock xác nhận hợp đồng,
projection/SFT/no-replay, chế độ thinking và nguồn lịch sử; không chứng minh năng
lực ngữ nghĩa hoặc tỷ lệ live 80%.
