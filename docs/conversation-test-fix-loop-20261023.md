# Vòng test và sửa, mục tiêu 80%

Ngày 23/10/2026. **Chưa đạt mục tiêu.** Kết quả đầy đủ tốt nhất: **15/30 (50%)**.
Vòng mới dừng sau 24 ứng viên vì ChatGPT Web báo giới hạn tài khoản, yêu cầu chờ
27 phút. Không chuyển Web PASS thành duyệt cuối, không nới rubric hoặc sinh bù bài lỗi.

## Kết quả thật

| Vòng | Prompt | Lượt sinh đã gửi | Hard đạt | Web hợp lệ | Web PASS | Codex duyệt cuối |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| 1 | v3 | 30 | 28 | 25 | 24 | 10/30 (33,3%) |
| 2 | v4, harness lỗi | 8 | Không dùng để kết luận chất lượng | 0 | 0 | Hủy vòng |
| 3 | v4, harness đã sửa | 30 | 29 | 27 | 25 | **15/30 (50%)** |
| 4 | v5 | 24/30 dự kiến | 22 | 19 | 15 | 10/24 (41,7%); 6 chưa chạy |

Vòng 2: bảy phản hồi đã nhận bị loại do bộ kiểm tra SFT còn dựng prompt theo footer
cũ, lệch prompt generation mới. Lượt thứ tám đã gửi khi dừng worker; kết quả chưa xác
định. Đã sửa bằng một hàm dựng system prompt dùng chung và kiểm tra `build_records`.
Tám lời gọi này vẫn tính vào chi phí, không replay hoặc tái phân loại thành đạt.

Tổng: **92 lời gọi DeepSeek, 77 yêu cầu bridge Web**, kể cả lỗi vận hành. Envelope
DeepSeek đã lưu ghi nhận **524.345 token**; chưa bao gồm lượt thứ tám chưa rõ kết quả
ở vòng 2. Không có số tiền thực thu hoặc tổng token Web đáng tin để báo cáo.

Phân bổ vòng đầy đủ: A1/A2/B1/B2 = 8/8/7/7. Vòng 3 duyệt tương ứng 6/3/3/3.
Vòng 4 đã gửi 6 bài/mức, duyệt tương ứng 2/5/2/1; chưa thể so như hai lô đầy đủ.
Vòng 4 có 11 bài phù hợp nội dung, nhưng bài 24 không có kết quả Web hợp lệ nên
không được duyệt. Trong 15 Web PASS của vòng này, Codex loại 5 bài; Web vẫn bỏ sót.

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

V5 chưa cho kết quả tốt hơn v4. **Mặc định collection giữ v4 làm mốc phát triển**,
không phải bản được nhận production. V6 là ứng viên chưa đánh giá live, chỉ chọn
qua `--artifact` khi tạo campaign mới. Không hồi tố sửa artifact/manifest cũ.
Production Java, rubric `conversation-semantic-v3`, giới hạn từ, không repair/retry
và quyền duyệt độc lập giữ nguyên. Không train/deploy trong lượt thử này.

## Biên bản local

Trong `data/fine-tuning/`, các thư mục sau có `session-report.json`,
`review-details.html`, `independent-decisions/` và `evidence-integrity.json`:

- `diagnostic-v3-loop-r1-20261023`
- `diagnostic-v4-loop-r3-20261023`
- `diagnostic-v5-loop-r4-20261023`

Vòng 2 có checkpoint `ABORTED_HARNESS_BUG`. Vòng 4 có `interruption.json` và
`WAITING_WEB_QUOTA`. Biên bản bài loại được giữ trong `rejected-evidence/` theo
quyền giữ bằng chứng diagnostic; không vào dataset SFT. Chi tiết không đưa lên Git.
Bridge đã dùng một tab Chrome và mở hội thoại mới cho mỗi yêu cầu. Không tắt máy.

## Tiếp tục sau giới hạn Web

Giới hạn phiên đã thống nhất: tối đa 10 vòng, đã dùng 4 vòng gồm vòng lỗi harness.
Không gọi lại các yêu cầu cũ. Vòng 4 không thể đạt 24/30 dù sáu bài còn lại đều đạt,
nên không cần tiêu thêm API vào phần đuôi chỉ để tìm bản thắng.
Vòng 5 dùng campaign mới, 30 bài mới; mục tiêu ít nhất 24/30 được duyệt cuối.

```powershell
python -X utf8 -m training.collect_reviewed prepare --campaign data/fine-tuning/diagnostic-v6-loop-r5-20261023 --artifact artifacts/prompts/conversation-reviewed-collection-v6-20261023.json --diagnostic --retain-diagnostic-evidence
python -X utf8 -m training.collect_reviewed collect --campaign data/fine-tuning/diagnostic-v6-loop-r5-20261023 --limit 30
```

Chỉ gửi khi Web cho phép. Sau đó Codex đọc nguồn/context từng bài trước khi so Web,
lưu quyết định có hash/evidence; không tự động nhận theo PASS. Nếu campaign đã được
prepare thì bỏ lệnh prepare. Không dùng test cũ để chứng minh ứng viên mới đã đạt.

Kiểm tra offline: **179 passed, 1 skipped**; UTF-8 và diff riêng đạt trước
khi giao Git. Mock xác nhận hợp đồng/projection/SFT/no-replay; không chứng minh năng
lực ngữ nghĩa hoặc tỷ lệ live 80% của V6.
