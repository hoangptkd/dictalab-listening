# Đánh giá 30 lần sinh mới — teacher/judge v2

Ngày 23/10/2026. DeepSeek `deepseek-flash` sinh nội dung và bản dịch cùng một lời
gọi; ChatGPT Web chấm; Codex đọc nguồn, ngữ cảnh và từng slot/fact để duyệt độc lập.
Alias Web chưa xác minh model thực tế. Đây là duyệt AI, chưa có nhãn chuẩn con người.

## Kết quả

**12/30 bài được nhận cuối (40%).** Có **14/30 đạt nội dung qua duyệt độc lập**,
nhưng 2 bài trong số đó bị loại vì phản hồi judge không hợp lệ. Hai bài còn mơ hồ
được loại thận trọng, không tính thành lỗi nội dung đã chứng minh.

| Mức | Số lần sinh | Qua hard | Đạt nội dung độc lập | Nhận cuối |
| --- | ---: | ---: | ---: | ---: |
| A1 | 8 | 8 | 6 | 6/8 — 75% |
| A2 | 8 | 8 | 4 | 4/8 — 50% |
| B1 | 7 | 7 | 4 | 2/7 — 28,6% |
| B2 | 7 | 5 | 0 | 0/7 |
| **Tổng** | **30** | **28** | **14** | **12/30 — 40%** |

- **30/30 đúng giới hạn từ**: A1 8–35, A2 18–55, B1/B2 30–90 theo cấu hình đã khóa.
  Ở bài JSON lỗi, chỉ trích xuất chuỗi `utteranceEn` để đo từ; không sửa JSON hay duyệt bài.
- **29/30 JSON đọc được**; 0016 lỗi cú pháp JSON. 0028 đọc được nhưng thiếu
  `questionVi`, `taskVi`, `skill`. Vì vậy chỉ 28 bài qua hard và được gửi Web.
- Không có bài bị chặn gần trùng. Không repair, retry, chấm lại hoặc sinh bù.
- **28 lượt Web → 24 verdict hợp lệ → 18 cho qua, 6 gắn cờ**. Bốn phản hồi sai
  hợp đồng được giữ nguyên để chẩn đoán, không sửa hoặc nạp lại làm verdict hợp lệ.
- Cả 30 lượt được sinh trong một lô cố định trước khi adjudicate; quyết định duyệt
  không được đưa ngược vào context của các lượt sau. Dữ liệu chỉ dùng diagnostic,
  không cộng vào 500 bài train và bị chặn export SFT.

## Các lỗi còn lại

1. **Phân loại vẫn dựa vào câu nguồn thay vì ý được hỏi.** 0006 hỏi tên phố nhưng
   gắn `instruction_sequence`; 0010 hỏi thời điểm nhưng gắn hành động; 0012/0020
   hỏi làm gì dưới if nhưng gắn `condition`; 0029 hỏi nhiệm vụ hành động nhưng gắn
   `direct_detail`. 0030 yêu cầu salad *instead of fries* nhưng vẫn gắn direct detail
   theo precedence thay đổi hiện tại.
2. **Slot, câu hỏi và đáp án chưa đồng bộ.** 0004 hỏi vì sao nhưng đáp án chỉ nhắc
   lại lựa chọn; 0008 hỏi loại vé nhưng đáp án thêm số lượng và điểm đến; 0024 slot
   so email với gặp trực tiếp, còn câu hỏi/đáp án lại so email với cổng trực tuyến.
3. **Lịch sử vẫn bị hỏi lại.** 0009 đổi thìa/bàn ba thành bánh mì/bàn cạnh cửa sổ,
   nhưng vẫn hỏi hai ý đã CAPTURED: đồ thiếu và nơi giao trong cùng encounter.
4. **Deadline vẫn dịch sai.** 0011/0019 dịch `by Thursday evening/morning` thành
   “trước tối/sáng thứ Năm”, làm hẹp hạn bao gồm cả mốc đó theo rubric hiện hành.
5. **Hai trường hợp chưa chắc:** 0022 chọn nghĩa ghế ngồi cho `bench` trong phòng
   workshop, trong khi bàn thao tác vẫn là cách hiểu phù hợp ngữ cảnh và đổi địa điểm
   được hỏi; 0027 có “office, not front desk” nhưng chưa rõ là sửa địa điểm hay chỉ
   làm rõ. Quy tắc “explicitly contrasted final value” quá rộng để chứng nhận nhãn
   direct_detail chắc chắn ở trường hợp này. Không tự sửa rubric trong lô.

Không xuất hiện lại lỗi vượt số slot/fact hoặc thiếu primary evidence trong các
bài đã qua hard. Điều này chỉ mô tả lô hiện tại, chưa chứng minh lỗi đã hết.

## Chất lượng judge

### Bỏ sót ở các verdict cho qua

Trong **18 verdict PASS hợp lệ**, Codex nhận 11 và loại 7:

| Bài | Lý do chưa nhận |
| --- | --- |
| 0006 | Tên phố sai taskType; slot nói bảo tàng đóng, nguồn chỉ nói cổng trước đóng |
| 0008 | Đáp án loại vé thêm số lượng và điểm đến không được hỏi |
| 0009 | Lặp hai ý đã CAPTURED trong cùng encounter |
| 0019 | Dịch sai ranh giới deadline “by” |
| 0024 | Slot so hai phương thức khác với câu hỏi và đáp án |
| 0027 | Chưa chắc về ranh giới direct_detail/change_revision; loại thận trọng |
| 0030 | Chưa áp dụng precedence change_revision cho món thay thế được tương phản rõ |

Như vậy có **6 bất đồng do vi phạm xác định theo rubric và 1 chưa chắc**.
Không gọi 7/18 là độ sai chung của judge trên mọi dữ liệu.

### Báo lỗi giả và giải thích mâu thuẫn

- **0023:** Web nói đáp án/câu hỏi thêm Saturday. Thực tế Saturday chỉ nằm trong
  nguồn và `evidenceEn`, không nằm trong đáp án hay câu hỏi. Codex bác cờ alignment,
  lưu căn cứ và nhận bài. Đây là 1/6 bài Web gắn cờ hợp lệ được nhận cuối.
- **0003:** nguồn nói thang cuốn *only for passengers with heavy bags*; diễn đạt
  lại thành chỉ người có túi nặng được dùng là có căn cứ. Web tự tạo lỗi chính sách,
  alignment và dịch. Nội dung được Codex đánh giá đạt nhưng phản hồi judge bị chặn.
- **0007:** câu hỏi nói rõ “trong điều kiện nào”; Web đọc thành hỏi hành động và
  đòi đổi `condition` sang `instruction_sequence`. Codex đánh giá nội dung đạt;
  phản hồi judge vẫn không hợp lệ nên không nhận vào pipeline.
- **0010/0022:** Web đòi đưa cả chi tiết không được hỏi vào đáp án, rồi gọi việc
  thiếu chi tiết đó là alignment lỗi. Không lấy các giải thích này làm căn cứ loại.
- **0004/0029:** một số check ghi false nhưng phần giải thích tự kết luận check
  phải true. Riêng 0029 vẫn có lỗi taskType thật mà Web bỏ sót, nên không nhận bài
  chỉ vì cờ alignment của Web sai.

### Bốn lỗi hợp đồng

| Bài | Lỗi phản hồi Web |
| --- | --- |
| 0003 | `sourceQuote` là bản dịch tiếng Anh của câu hỏi, không có nguyên văn trong input |
| 0007 | Trích một đoạn câu hỏi bắt đầu “Khách” viết hoa, trong input thực tế là “khách”; không phải nguyên văn |
| 0012 | Top-level translationFaithful=true nhưng factAudit.translationSupported=false |
| 0020 | Trích câu ví dụ “What document does the customer ask about?” không thuộc bài; còn gắn issue cho check=true |

0012/0020 vẫn có lỗi bài sinh thật; 0003/0007 đạt nội dung độc lập. Bốn envelope
chỉ được đọc offline để chẩn đoán, không gửi lại Web hoặc sửa thành response đạt.
Ở 0020, câu “lễ tân không cần mở cửa nữa” có thể diễn đạt quầy lễ tân không cần tiếp
tục hoạt động; chưa đủ căn cứ kết luận đó là hành động mở một cánh cửa như Web nói.

## Biên bản 30 bài

| Bài | Mức | Từ | Web | Kết luận cuối |
| --- | --- | ---: | --- | --- |
| 0001 | A1 | 22 | Qua | Nhận |
| 0002 | A2 | 36 | Qua | Nhận |
| 0003 | B1 | 54 | Lỗi hợp đồng | Nội dung đạt; chưa nhận |
| 0004 | B2 | 70 | Gắn cờ | Hỏi lý do, đáp án thiếu lý do |
| 0005 | A1 | 23 | Qua | Nhận |
| 0006 | A2 | 40 | Qua | Sai taskType và phạm vi slot |
| 0007 | B1 | 66 | Lỗi hợp đồng | Nội dung đạt; chưa nhận |
| 0008 | B2 | 63 | Qua | Đáp án thêm số lượng/điểm đến |
| 0009 | A1 | 22 | Qua | Lặp ý lịch sử |
| 0010 | A2 | 45 | Gắn cờ | Thời điểm bị gắn nhãn hành động |
| 0011 | B1 | 72 | Gắn cờ | Dịch sai deadline |
| 0012 | B2 | 65 | Lỗi hợp đồng | Slot lệch câu hỏi; hành động gắn condition |
| 0013 | A1 | 23 | Qua | Nhận |
| 0014 | A2 | 37 | Qua | Nhận |
| 0015 | B1 | 69 | Qua | Nhận |
| 0016 | B2 | 55 | Không gửi | JSON sinh lỗi |
| 0017 | A1 | 23 | Qua | Nhận |
| 0018 | A2 | 38 | Qua | Nhận |
| 0019 | B1 | 69 | Qua | Dịch sai deadline |
| 0020 | B2 | 74 | Lỗi hợp đồng | Hành động gắn condition, thiếu focus |
| 0021 | A1 | 28 | Qua | Nhận |
| 0022 | A2 | 46 | Gắn cờ | Chưa chắc nghĩa bench; loại thận trọng |
| 0023 | B1 | 52 | Gắn cờ | Nhận; bác cờ giả với căn cứ |
| 0024 | B2 | 74 | Qua | Slot/câu hỏi so hai cặp phương thức khác nhau |
| 0025 | A1 | 27 | Qua | Nhận |
| 0026 | A2 | 40 | Qua | Nhận |
| 0027 | B1 | 66 | Qua | Chưa chắc ranh giới phân loại; loại thận trọng |
| 0028 | B2 | 61 | Không gửi | Thiếu questionVi/taskVi/skill |
| 0029 | A1 | 19 | Gắn cờ | Sai taskType; giải thích alignment của Web tự mâu thuẫn |
| 0030 | A2 | 35 | Qua | Sai nhãn final replacement theo precedence hiện tại |

## So sánh và giới hạn

Lô v1 30 bài trước nhận 12/30; lô v2 này cũng nhận 12/30. Bản mới chưa cho thấy
cải thiện tỷ lệ nhận toàn luồng. A1 tăng 4/8 → 6/8, A2 giảm 6/8 → 4/8, B1 giữ
2/7; B2 giữ 0/7. Chỉ là mô tả hai lô nhỏ trên catalogue xác định, không phải thử
nghiệm ngẫu nhiên hay bằng chứng nhân quả/thống kê. Corpus kiểm trùng cũng đã thay đổi.

Tỷ lệ nội dung độc lập của v2 là 14/30; không so trực tiếp với 12/30 v1 vì đó là
tỷ lệ nhận cuối. B2 có lập luận thật ở một số nguồn nhưng chưa bài nào đạt tất cả
tiêu chí. Không thể suy từ 0/7 rằng model hoàn toàn không sinh được bài B2 tốt.

Ưu tiên sửa tiếp khi được giao: bảo đảm judge trích nguyên văn và thống nhất verdict
với giải thích; phân loại theo câu hỏi; đồng bộ slot/câu hỏi/đáp án; xử lý deadline
và lịch sử. Ranh giới change_revision cần làm rõ trước khi dùng làm nhãn train hàng loạt.

## Lưu trữ, usage và kiểm tra

Campaign riêng: `data/fine-tuning/diagnostic-v2-30-20261023/`.

- `review-details.html`: nguồn, lịch sử, bản dịch, slots/facts, Web gốc, Codex,
  request/response và căn cứ bác cờ giả của từng bài.
- `session-report.json`: số liệu và kết luận từng bài; `evidence-integrity.json`:
  chỉ mục hash. `independent-decisions/`: đủ 30 bản duyệt độc lập.
- `rejected-evidence/`: giữ nguyên 18 bài không nhận, kể cả lỗi provider và judge.
- `sent-index.jsonl`: **30 teacher + 28 judge**, không trùng id gửi.

Teacher dùng **163.528 token**: 148.024 input, 15.504 output; cache hit 26.624.
Web không trả số token/chi phí; không suy ra số tiền từ dữ liệu thiếu.
Bridge health trước/sau và danh sách tab xác nhận chỉ **một tab**; hội thoại mới cho
mỗi lượt chấm. Đã dừng bridge do phiên này khởi chạy sau khi hoàn tất.

Kiểm tra offline số lượng, hash, quyết định, toàn vẹn bằng chứng và không còn pending
đạt; UTF-8 đạt trên campaign và báo cáo. Chỉ thêm báo cáo, không sửa mã chạy hay
prompt trong lượt test này. Không chạy thêm test API, train, deploy hoặc tắt máy.
GitHub Actions chưa xác minh nếu API tiếp tục trả 404; push nhánh không phải deploy.
