# Thử nghiệm 30 ứng viên Conversation — 23/10/2026

## Kết quả

**12/30 được Codex duyệt cuối (40%).** Đây là tỷ lệ đạt ngay lần sinh đầu,
không phải 30 bài đã lọc đạt chất lượng. Không sinh bù, repair hoặc gửi lại.

| Mức | Lần sinh | Qua hard checks | Được duyệt cuối | Tỷ lệ trên lần sinh |
| --- | ---: | ---: | ---: | ---: |
| A1 | 8 | 8 | 4 | 50% |
| A2 | 8 | 8 | 6 | 75% |
| B1 | 7 | 7 | 2 | 28,6% |
| B2 | 7 | 5 | 0 | 0% |
| Tổng | 30 | 28 | 12 | 40% |

Luồng: DeepSeek `deepseek-flash` trong registry → validator/dedup → ChatGPT Web
qua bridge → Codex đọc độc lập ngữ cảnh, bài và kết quả Web để quyết định.
Không áp dụng teacher 28/30 hoặc judge gold gate. Alias Web chưa xác minh model phía sau.

- Đúng **30 request teacher** và **27 request Web**, có sổ gửi chống replay.
- 28 bài qua hard checks; trong đó 1 bài gần trùng dữ liệu cũ nên không gửi Web.
- 27 lượt Web: 25 kết quả đúng hợp đồng, 2 kết quả bị loại vì hợp đồng chấm.
- Trong 25 bài Codex có thể duyệt: 12 nhận, 12 có lỗi và 1 chưa chắc chắn nên loại.
- 18 ứng viên loại đã xóa nội dung chi tiết, giữ log gọn/hash/usage/trạng thái gửi.
- Bridge health xác nhận **1 page**; mỗi lượt chấm dùng hội thoại mới trong tab đó.

## Lỗi được thấy

Các nhóm ngữ nghĩa dưới đây có thể cùng xuất hiện trong một bài, không cộng thành 30.

| Nhóm | Số bài | Nhận xét |
| --- | ---: | --- |
| Sai hoặc chưa chắc taskType | 5 | 4 lỗi và 1 trường hợp ranh giới chưa chắc; nổi bật là thấy “if” rồi gán condition dù câu hỏi hỏi thời gian/hành động. |
| Lệch slot/câu hỏi/đáp án | 4 | Nhầm nơi chờ với nơi lên tàu; đáp án thêm thông tin thời gian không được hỏi. |
| Không đạt focus thực sự | 3 | Gắn nhãn condition/direct_detail nhưng ý nghĩa được hỏi thuộc loại khác. |
| Dịch hoặc thêm thuộc tính không có căn cứ | 3 | Hai bài đổi hạn “by” thành “trước”; một bài tự thêm giới tính ở fact tiếng Anh và tiếng Việt. |
| Lặp ý đã kiểm tra trong cùng encounter | 2 | Lặp món đổi hoặc đổi tên đồ vật nhưng giữ nguyên ý nghĩa đã hỏi. |
| Sai vai giao tiếp | 1 | Khách nói quy định/hướng dẫn lên tàu với người bán vé. |

Hai bài B2 không qua hard/pipeline checks chỉ còn nhóm `DatasetError` trong log cũ;
không đủ bằng chứng kết luận lỗi cụ thể là độ dài, schema hay grounding. Không được
báo “30/30 không lỗi độ dài/Unicode” từ kết quả này. Log đã được bổ sung giữ mã
validator an toàn cho lần sau, gồm word count/bounds nếu lỗi đó được trả về.

## Chất lượng chấm Web

Trong **25 verdict hợp lệ**:

- Web cho qua 15 bài; Codex giữ 10 và loại 5: `0007, 0009, 0020, 0023, 0030`.
- Web đánh dấu 10 bài; Codex nhận lại 2 bài có căn cứ rõ: `0002, 0018`.
- Có giải thích tự mâu thuẫn ở `0015, 0028`: kết luận check sai nhưng diễn giải lại
  nói không có vi phạm. Có trường hợp đánh dấu đúng nhóm nhưng viện dẫn sai lỗi.
- Lỗi chấm giả lặp lại: bắt kiểm tra mọi chi tiết phụ trong transcript, hoặc bác đáp án
  điều kiện chỉ vì đáp án là cụm từ. Thay đổi được nêu rõ cũng bị nhầm là lặp ý cũ.

Vì vậy, positive fact audit và schema hợp lệ vẫn chưa bảo đảm Web chấm đúng. Các
quyết định nhận lại có hash và căn cứ override từng check; không tự nhận theo Web.

## Phạm vi và bằng chứng

- Campaign riêng: `data/fine-tuning/diagnostic-30-20261023`.
- Kết quả máy đọc: `session-report.json`; log duyệt gọn: `diagnostic-summary-rows.json`;
  quyết định đầy đủ cho bài nhận: `decisions/`; nguồn và Web của bài nhận: `candidates/`.
- Toàn bộ lô được đánh dấu **diagnosticOnly/developmentOnly**, bị chặn export SFT.
  Các nhãn split nội bộ chỉ phục vụ dựng ngữ cảnh; không phải train/validation/test
  thật. Bộ chính trước đó vẫn chỉ có 1 bài train A1 đã duyệt.
- Giữ nguyên prompt sinh, schema, rubric và model trong cả phép thử. Thay đổi giữa
  các lô chỉ bổ sung chẩn đoán lỗi, không thay target hoặc điều kiện đạt.
- B2 hiện dùng profile văn bản ngắn thử nghiệm 30–90 từ. Kết quả 0/7 phản ánh luồng
  và profile hiện tại; chưa đủ kết luận về khả năng B2 nói chung của model. Bài được
  gắn B2 còn thiên về thông tin trực tiếp; cần đánh giá riêng độ phân biệt B1/B2.
- Đây là duyệt AI được chủ dự án ủy quyền, không phải chứng nhận CEFR hoặc duyệt
  bởi con người. Không kiểm tra audio, không train và không deploy trong phép thử này.

## Thứ tự khắc phục đề xuất

1. Chọn taskType từ ý nghĩa câu hỏi; kiểm tra cùng một ý xuyên suốt slot, câu hỏi,
   fact và focus. Tách thời gian/hành động dưới “if” khỏi điều kiện tiên quyết.
2. Ràng buộc bản dịch giữ deadline bao gồm hạn cuối; không tự thêm giới tính/tuổi.
3. Phân biệt ý mới, thay tên giá trị, và thay đổi thật có tín hiệu trong lịch sử.
4. Chuẩn hóa judge: tách chi tiết nguồn được phép không hỏi khỏi thông tin thừa
   trong answer key; kiểm tra verdict có mâu thuẫn với chính giải thích không.
5. Chốt yêu cầu năng lực riêng cho B2 trước khi thu thập quy mô lớn. Thử lại trên
   lô mới; giữ toàn bộ lô chẩn đoán hiện tại ngoài SFT và holdout thật.

Kiểm tra code liên quan: **118 passed, 1 skipped**; kiểm tra UTF-8 và diff trước giao code.
