# Thử thêm 10 lần sinh và chấm lại 5 bài bất đồng

Ngày: 23/10/2026. Giữ nguyên DeepSeek `deepseek-flash`, prompt, schema và rubric
`conversation-semantic-v3`. Web dùng alias `chatgpt-web`, chưa xác minh model thực tế.

## 1. Mười lần sinh mới

**2/10 được Codex duyệt cuối (20%).** Không repair, retry hoặc sinh bù để đổi mẫu số.
Đây là 10 request sinh mới trên các cấu hình ngữ cảnh đã dùng, không phải 10 bài
độc lập được chọn ngẫu nhiên; không suy ra mức giảm chất lượng thống kê từ 40% xuống 20%.

| Bài mới | Mức | Từ | Hard | Web | Kết luận cuối |
| --- | --- | ---: | --- | --- | --- |
| 0001 | A1 | 21 | Đạt | Không gửi | Gần trùng dữ liệu cũ |
| 0002 | A2 | 38 | Đạt | Qua | Nhận |
| 0003 | B1 | 49 | Không đạt | Không gửi | 4 slot/fact vượt schema tối đa 3 |
| 0004 | B2 | 61 | Đạt | Qua | Đáp án ý kiến đổi sang lý do không được hỏi; bản dịch tự dùng “cô” cho server chưa rõ giới tính |
| 0005 | A1 | 22 | Đạt | Qua | Nhận |
| 0006 | A2 | 40 | Đạt | Qua | Chưa chắc: điều kiện nhận diện tòa nhà và điều kiện nhìn thấy tòa nhà chưa khớp rõ |
| 0007 | B1 | 54 | Đạt | Gắn cờ | Biến câu khách hỏi về đổi vé thành điều kiện được phép đổi vé |
| 0008 | B2 | 85 | Không đạt | Không gửi | `fact[2].reasoningEvidence:primary_evidence_missing` |
| 0009 | A1 | 20 | Đạt | Qua | Lặp ý dụng cụ thiếu/nơi giao trong cùng encounter |
| 0010 | A2 | 43 | Đạt | Qua | Hỏi lại món súp gà đã CAPTURED trong lịch sử |

- 8/10 qua hard checks; 10/10 envelope sinh chứa JSON đọc được và nằm trong giới hạn từ.
  JSON hợp lệ không đồng nghĩa đúng schema: bài 0003 vượt số slot/fact.
- 7 bài được gửi Web, cả 7 verdict hợp lệ; Web cho qua 6, Codex nhận 2.
  Trong 4 bất đồng mới có 3 bài bị loại theo rubric và 1 bài chưa chắc chắn (0006).
- Phân bổ nhận: A1 1/3; A2 1/3; B1 0/2; B2 0/2. Mẫu nhỏ, không đại diện toàn CEFR.
- Bài mới 0009 còn gần như trùng bài cũ 0009 đã khôi phục. Bản cũ chưa nằm trong
  prior-corpus lúc prepare vì trước đó đã bị xóa; phát hiện bổ sung được ghi sau khôi phục.
- Các chi tiết hỗ trợ không được hỏi, ví dụ đội mũ ở 0005, không tự động là lỗi.
  0006 được loại vì chưa chắc về nghĩa câu hỏi, không ghi thành lỗi đã chứng minh.

## 2. Chấm lại đúng năm bản gốc

Khôi phục task/context và prompt từ đúng hội thoại chấm cũ trên Web. Hash task,
context, request và prompt khớp **5/5**. Gửi mỗi bài một lần trong hội thoại mới;
không kèm kết luận cũ của Codex. Có kiểm tra riêng trước khi đọc phản hồi Web mới,
nhưng Codex đã biết kết luận lịch sử nên không gọi đây là đánh giá mù hoàn toàn.

| Bài cũ | Web cũ | Web mới | Codex đọc lại theo rubric hiện tại |
| --- | --- | --- | --- |
| 0007 | Qua | Qua | Vẫn loại vì đáp án ý kiến thêm lý do dù không hỏi “vì sao”. Rút kết luận chắc chắn về đảo vai: “you” có thể mang nghĩa chung. |
| 0009 | Qua | Qua | Vẫn loại theo quy tắc không hỏi lại cùng ý trong cùng encounter bằng cách đổi đồ vật/địa điểm. |
| 0020 | Qua | Gắn cờ | Đã bắt được action dưới “if” bị gắn condition và thiếu focus condition thật. Codex cũng ghi nhận đáp án thêm mục đích không được hỏi và yêu cầu B2 quá đơn giản. |
| 0023 | Qua | Qua | Vẫn loại theo precedence hiện tại: địa điểm mới được tương phản rõ với địa điểm cũ phải là change_revision; không còn fact direct_detail đúng focus. |
| 0030 | Qua | Qua | Vẫn loại: “I” chưa rõ giới tính bị đổi thành “he” / “anh ấy” trong fact. |

Web bắt được vấn đề chính ở **1/5** bài bất đồng được chọn lại; đây không phải độ
chính xác chung của judge. Ở 0020, giải thích alignment của Web vẫn dùng sai
`slots.kind` như nhãn phân loại chính thức. Codex dùng bằng chứng khác: phần mục
đích thừa trong đáp án. Đúng nhóm check không đồng nghĩa mọi lập luận đều đúng.

Ba quy tắc về đáp án chứa lý do, lặp ý khi đổi đồ vật, và precedence change_revision
có thể cần chủ dự án review về sư phạm. Kết quả trên tuân rubric đã khóa; không coi
mọi bất đồng là bằng chứng Web sai theo mọi cách thiết kế bài nghe.

## 3. Biên bản và sự cố được giữ

Thư mục riêng: `data/fine-tuning/diagnostic-10-recheck-20261023/`.

- **`review-details.html`**: toàn bộ 10 bài mới và 5 bản gốc; lịch sử, vai, câu hỏi,
  bản dịch, đáp án, taskType, phản hồi Web, quyết định Codex, request/response mở rộng.
- `session-report.json`: số liệu, usage, từng kết luận và hash.
- `recheck-five/`: bản khôi phục, nguồn hội thoại, Web cũ/mới và kiểm tra hash.
- `rejected-evidence/`: đầy đủ nội dung 8 bài mới bị loại, kể cả lỗi hard.
- `independent-decisions/`: quyết định Codex gắn hash bài/ngữ cảnh/Web.
- `sent-index.jsonl`: **10 teacher + 12 Web**, gồm 7 bài mới và 5 lượt chấm lại.

Adapter riêng cho lượt chấm lại ban đầu thiếu metadata `hardPass`, làm bước kiểm
tra local báo lỗi dù phản hồi đã có. Đã kiểm lại hard validation, JSON schema,
check/issue, trích dẫn và factAudit trực tiếp trên cả 5 envelope đã lưu; **không
gửi lại Web, không sửa phản hồi model**. Giữ log sự cố và kết quả kiểm lại offline.
Không tính sự cố adapter này thành 5 lỗi của Web.

Teacher dùng 50.877 token: 46.006 input, 4.871 output; input cache hit 44.032.
Web không trả token/chi phí. Không suy ra chi phí tiền khi chưa có giá thực đo.
Bridge health xác nhận một page trước/sau phép thử; đã chuyển CDP sang 9223 vì
9222 trả HTTP 404. Chrome chỉ dùng một tab cho khôi phục và chấm lần lượt.

Toàn bộ là dữ liệu diagnostic, bị chặn export SFT; 2 bài được duyệt không cộng vào
500 bài train. Không train, không deploy, không bật tắt máy tự động.

## 4. Kiểm tra và giao code

Thêm `--retain-diagnostic-evidence` cho campaign diagnostic; giữ bản gốc trước khi
dọn hàng đợi. Campaign thu thập train vẫn dùng quy tắc xóa chi tiết bài loại.
Đã cập nhật tài liệu ghi nhận chủ dự án hủy yêu cầu tắt máy.

Kiểm tra liên quan: **77 passed**; UTF-8 và `git diff --check` đạt trước commit.
GitHub Actions chưa xác minh nếu API vẫn trả 404; push nhánh không phải deploy.
