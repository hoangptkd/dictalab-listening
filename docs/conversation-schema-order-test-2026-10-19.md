# Thử thứ tự facts → bài nghe → câu hỏi — 19/10/2026

## Kết luận

Đã thử thật **đúng 12 input đã lưu của đợt trước**, mỗi ca một lần gọi local, không validation, không sửa, không reviewer cloud. Cả 12 output thực tế đều theo thứ tự `facts → utteranceEn → questionVi → translationVi → taskVi → skill`.

**Đổi thứ tự schema đơn lẻ không cải thiện tổng thể ở lượt chạy này.** Có 11 ca lỗi nội dung rõ; 1 ca khớp hỏi–đáp nhưng bản dịch bỏ thông tin. Chưa có ca nào được công nhận đạt đầy đủ. Một lượt sampling không đủ để quy tỷ lệ lỗi cho thiết kế hoặc năng lực model.

| Hạng mục | Đợt trước | Thử facts-first |
| --- | ---: | ---: |
| Thu đủ output | 12/12 | 12/12 |
| Đúng cấu trúc | 12/12 | 12/12 |
| Nội dung được công nhận khớp đầy đủ trong phạm vi thử | 2 | 0 |
| Lỗi nội dung rõ | 7 | 11 |
| Chưa được công nhận đạt | 3 ca mơ hồ | 1 ca hỏi–đáp khớp, bản dịch thiếu |
| Timeout | 0 | 0 |

Không tính lặp, độ hay hay độ dài CEFR thành lỗi nội dung. Bản dịch được yêu cầu dịch bài nghe, không phải chỉ tóm tắt hai đáp án.

## Cách thử và giới hạn đối chứng

- Baseline: code `96018c3`, [báo cáo tính đúng](conversation-content-correctness-test-2026-10-19.md).
- Model giữ nguyên: `hf.co/Qwen/Qwen3-4B-GGUF:Q4_K_M`; Ollama local; temperature 0.7, context 8192, num_predict 1000, think=false; timeout 180 giây/lần.
- Không dựng lại fixtures: đọc lại `input` của từng ca trong JSON baseline, bao gồm nguyên recentPractice của phiên trước. Đã đối chiếu đủ 12 ID và input tương ứng.
- Chỉ đổi thứ tự `properties` và `required` của schema. Tên trường, kiểu, enum, số fact và giới hạn ký tự giữ nguyên.
- Prompt, nội dung ví dụ mẫu và thông số model không đổi. Không sắp xếp lại ví dụ hoặc thêm chỉ dẫn mới: đây là phép thử riêng giả thuyết đổi thứ tự schema.
- Có ghi lại thứ tự thuộc tính của từng output; 12/12 làm theo thứ tự mới. Vì vậy, không phải phép thử chỉ đổi schema nhưng model vẫn sinh thứ tự cũ.
- Baseline không lưu seed và không có lượt lặp cùng seed cho hai biến thể. Đây là đối chiếu một lượt mỗi ca, không phải thử nghiệm nhân quả có kiểm soát đầy đủ. Không chứng minh model không thể làm đúng, hoặc mọi lỗi còn lại đều do năng lực model.
- JSON đầu vào được giữ nguyên về dữ liệu và thứ tự khi replay. Baseline không lưu toàn bộ HTTP request; không khẳng định request byte-for-byte giống nhau ngoài schema, nhất là thứ tự khóa của JSON ví dụ được tạo từ Map.

Đợt gọi model ban đầu tạm dùng schema mới trong client. Sau khi có kết quả, đã khôi phục schema mặc định và giữ biến thể trong test qua một overload package-private của cùng đường gửi request. Unit test xác nhận request thử nghiệm có đúng schema mới, mọi constraint không đổi và request mặc định kế tiếp vẫn dùng schema cũ. Không chạy lại 12 ca sau khi tách đường test; cách dựng schema thử nghiệm được đối chiếu với schema đã lưu từ đợt chạy thật.

## So sánh từng ca

| Ca | Trước | Sau đổi thứ tự |
| --- | --- | --- |
| cafe-a1-followup | Sai: hỏi món, đáp án quầy | Sai: facts trà/bánh mì không có trong bài nghe; thiếu đáp án mã số |
| directions-a2-followup | Cần xem lại vai | Sai: thêm fact lý do dù câu hỏi chỉ hỏi đích và phương tiện |
| hotel-b1-followup | Sai: lý do thành địa điểm | Sai: không nêu lý do phòng 8; facts không trả lời các lý do; dịch chép câu hỏi |
| hotel-a1-followup | Khớp | Sai: facts phòng/vườn, bài nghe và câu hỏi lại là lấy đồ uống |
| cafe-a2-followup | Sai hỏi/đáp và thêm phô mai | Sai: thiếu fact lý do; facts quá chung; dịch là một câu hỏi |
| directions-b1-followup | Câu hỏi mơ hồ | Sai: facts hỏi đường, bài nghe nhận hành lý; dịch chép câu hỏi |
| hotel-a2-continuity | Sai giờ ăn thành giờ nhận phòng | Hỏi–đáp đã khớp, nhưng bản dịch lược địa điểm và cả câu hỗ trợ hành lý |
| hotel-a2-transfer | Sai thiếu/thừa ý | Sai: hai fact phòng/vườn không có trong bài nghe; thiếu fact giờ trả chìa khóa |
| cafe-a1-repeat-session | Sai hỏi món/địa điểm và dịch collect | Sai: thêm fact trà/bánh mì không được hỏi và không có trong bài nghe |
| cafe-a1-ten-recent-sessions | Sai salad và bằng chứng bịa | Sai: vẫn không có tên món, evidence `a takeaway` không tồn tại |
| directions-a2-practice-focus | Khớp | Sai: hỏi vì sao đường đông nhưng bài nghe không giải thích; dịch chép câu hỏi |
| hotel-b1-missed-transfer | Lẫn nghiệp vụ, cần xem lại | Sai: hỏi vì sao phòng ăn đóng cửa nhưng không có nguyên nhân; dịch tự tạo lý do |

## Bằng chứng đầu ra mới

### 1. cafe-a1-followup

- Facts: `Trà` / `a tea`; `Bánh mì kẹp phô mai` / `a cheese sandwich`.
- Bài nghe: “Please collect your order at the counter. The staff will prepare your items.”
- Câu hỏi: “Bạn gọi món gì và dùng mã số nào?”
- Dịch: “Xin lấy đồ uống tại quầy bên cạnh. Nhân viên sẽ chuẩn bị món ăn của bạn.”
- Hai món và cả hai evidence không xuất hiện trong bài nghe. Không có mã số để trả lời. Dịch tự thêm quầy bên cạnh và thu hẹp order thành đồ uống.

### 2. directions-a2-followup

- Facts: bảo tàng, xe buýt, đường quá đông để đi bộ.
- Bài nghe: “We need to get you to the museum. The road is too busy for walking, so we will take the bus.”
- Câu hỏi: “Bạn sẽ đi đâu và bằng phương tiện gì?”
- Hai ý hỏi có thông tin tương ứng, nhưng fact thứ ba về lý do không được hỏi. Người học trả lời đủ câu hỏi có thể bị chấm thiếu ý.

### 3. hotel-b1-followup

- Facts: phòng số 8, ngoài vườn, phòng ăn hôm nay đóng cửa.
- Bài nghe: “The dining room is closed today. Therefore, breakfast is in the garden. Your room is number eight.”
- Câu hỏi: “Người nói có lý do gì để đưa bạn vào phòng 8 và ăn sáng ở ngoài vườn?”
- Dịch chép nguyên câu hỏi phía trên, không dịch bài nghe.
- Chỉ có lý do ăn sáng ở vườn, không có lý do chọn phòng 8; số phòng và địa điểm không phải các lý do được hỏi.

### 4. hotel-a1-followup

- Facts: `Phòng tám` / `number eight`; `Ngoài vườn` / `in the garden`.
- Bài nghe: “Please collect your drink at the side counter. Your collection number is twelve.”
- Câu hỏi: “Bạn lấy đồ uống ở đâu và dùng mã số nào?”
- Dịch: “Xin lấy đồ uống tại quầy bên cạnh. Số lấy đồ của bạn là mười hai.”
- Câu hỏi và bản dịch khớp bài nghe, nhưng toàn bộ facts thuộc nội dung khác. Không phải chỉ lỗi đổi bối cảnh: dùng facts này sẽ chấm sai đáp án quầy bên cạnh/mười hai.

### 5. cafe-a2-followup

- Facts: `Đồ uống` / `a tea`; `Món ăn` / `a cheese sandwich`.
- Bài nghe: “I would like a tea and a cheese sandwich, please. This is because I have not had lunch yet.”
- Câu hỏi: “Khách gọi gì và tại sao?”
- Dịch: “Khách muốn gọi đồ uống gì và lý do là gì?”
- Không có fact cho lý do chưa ăn trưa. Facts chung chung đồ uống/món ăn không nêu các món cụ thể. Bản dịch biến lời gọi món và lý do thành một câu hỏi.

### 6. directions-b1-followup

- Facts: bảo tàng, xe buýt, đường quá đông để đi bộ.
- Bài nghe: “You can collect your luggage only after the security check has finished. Although the main desk is still open, please go straight to the collection area and show your receipt to the assistant there. This helps us avoid giving a bag to the wrong person.”
- Câu hỏi: “Bạn cần đi đâu và bằng phương tiện gì để đến bảo tàng?”
- Dịch chép nguyên câu hỏi.
- Không có thông tin đích/phương tiện cần trả lời trong bài nghe. Cả ba evidence của facts hỏi đường đều không có trong bài nghe nhận hành lý.

### 7. hotel-a2-continuity — cải thiện cục bộ

- Facts: `Bảy giờ sáng` / `seven in the morning`; `Trước mười một giờ` / `before eleven`.
- Bài nghe: “Breakfast in the garden starts at seven in the morning. Please return your room key at reception before eleven when you leave. Our staff can help you carry your bags.”
- Câu hỏi: “Ăn sáng bắt đầu lúc bao nhiêu và bạn phải trả chìa khóa trước bao giờ?”
- Dịch: “Ăn sáng bắt đầu lúc bảy giờ sáng và bạn phải trả chìa khóa trước mười một giờ.”
- Câu hỏi, facts và bằng chứng nay khớp. Không đánh trượt vì lặp lịch sử. Tuy nhiên bản dịch bỏ ngoài vườn, địa điểm trả chìa khóa tại lễ tân và toàn bộ câu hỗ trợ hành lý. Ghi riêng là hỏi–đáp đạt nhưng dịch chưa đầy đủ; không đánh đồng mức độ với các ca facts sai hoàn toàn.

### 8. hotel-a2-transfer

- Facts: `Phòng số 8` / `number eight`; `Ngoài vườn` / `in the garden`; `Ăn sáng bắt đầu lúc 7 giờ sáng` / `starts at seven in the morning`.
- Bài nghe: “Our breakfast starts at seven in the morning. Please return your room key at reception before eleven when you leave. The staff can help you carry your bags.”
- Câu hỏi: “Ăn sáng bắt đầu lúc bao giờ và bạn sẽ trả chìa khóa khi nào?”
- Dịch: “Ăn sáng bắt đầu lúc 7 giờ sáng. Xin trả chìa khóa trước mười một giờ khi bạn rời đi. Nhân viên có thể giúp bạn mang balo.”
- Hai fact đầu không có trong bài nghe và không được hỏi. Không có fact trước mười một giờ dù câu hỏi yêu cầu. Dịch bỏ lễ tân và tự thu hẹp bags thành balo.

### 9. cafe-a1-repeat-session

- Facts: `Trà và bánh mì kẹp phô mai` / `a tea and a cheese sandwich`; `Quầy bên cạnh` / `the side counter`.
- Bài nghe: “Please collect your drink and food at the side counter. Thank you.”
- Câu hỏi: “Bạn muốn lấy đồ uống và món ăn ở đâu?”
- Dịch: “Xin lấy đồ uống và món ăn tại quầy bên cạnh.”
- Chỉ hỏi nơi lấy; fact tên món là thừa, và tên món cụ thể không có trong bài nghe. Bỏ “Thank you” trong bản dịch không được dùng làm lý do chính để đánh trượt.

### 10. cafe-a1-ten-recent-sessions

- Facts: `Món ăn mang đi` / `a takeaway`; `Mười hai giờ` / `twelve`.
- Bài nghe: “Please take your meal away. Your collection time is twelve.”
- Câu hỏi: “Khách muốn mang đi món gì và lấy lúc mấy giờ?”
- Dịch: “Xin mang đồ ăn đi. Thời gian lấy đồ của bạn là mười hai.”
- Đã nêu rõ giờ lấy hàng hơn baseline. Nhưng vẫn không có tên món; món ăn mang đi không trả lời được món gì. Evidence `a takeaway` không tồn tại trong bài nghe, dù có cùng chủ đề với take your meal away.

### 11. directions-a2-practice-focus

- Facts: bảo tàng, xe buýt, đường quá đông để đi bộ.
- Bài nghe: “We need to go to the museum. The best way is by bus. However, the road is too busy for walking.”
- Câu hỏi: “Bạn sẽ đi đâu và bằng phương tiện gì? Vì sao đường quá đông để đi bộ?”
- Dịch chép nguyên câu hỏi.
- Đường quá đông có thể là lý do không đi bộ, nhưng không phải lời giải thích vì sao đường đông. Ý thứ ba không trả lời được. Evidence `the bus` không nguyên văn với `by bus`; đây là sai trích dẫn, không phải bằng chứng phương tiện bị bịa.

### 12. hotel-b1-missed-transfer

- Facts: phòng số 8, ngoài vườn, phòng ăn hôm nay đóng cửa.
- Bài nghe: “Your room is number eight. Breakfast is in the garden. This is because the dining room is closed today.”
- Câu hỏi: “Phòng số mấy và ăn sáng ở đâu? Vì sao phòng ăn hôm nay đóng cửa?”
- Dịch: “Phòng của bạn là số tám. Ăn sáng ở ngoài vườn. Lý do phòng ăn hôm nay đóng cửa là vì quầy ăn đang được đóng cửa.”
- Hai ý đầu có đáp án. Ý thứ ba hỏi nguyên nhân phòng ăn đóng cửa, nhưng bài nghe chỉ dùng việc đóng cửa để giải thích nơi ăn sáng. Fact đóng cửa không phải nguyên nhân của chính nó. Bản dịch tự tạo một quan hệ nguyên nhân không có trong bài nghe.

## Giải thích lựa chọn Qwen3 thay vì Qwen3.5

Máy đã có cả `hf.co/Qwen/Qwen3-4B-GGUF:Q4_K_M` và `qwen3.5:4b`. Không phải chưa tải Qwen3.5.

Theo [tài liệu triển khai local](conversation-generation-local.md), trước đây Qwen3.5 4B trên CPU gặp timeout ở ba lượt A1 đầu với giới hạn 30 giây, nên generator được cấu hình sang Qwen3-4B. Đây là quyết định thực dụng ở thời điểm đó, không chứng minh Qwen3 có nội dung tốt hơn. Coach vẫn mặc định dùng Qwen3.5.

Timeout nay đã là 180 giây nên chưa có cơ sở loại Qwen3.5 chỉ dựa vào kết quả 30 giây cũ. Đợt này giữ Qwen3 để so sánh riêng thứ tự schema, chưa thử lại Qwen3.5. Không tự thay model đồng thời với thứ tự schema vì sẽ không biết biến nào tạo khác biệt.

## Cách chạy lại

Cần giữ JSON baseline 12 ca ở `target/conversation-local-raw-content-evaluation.json`. Nếu thiếu, tạo lại bằng collector trong báo cáo baseline; khi đó sẽ là baseline mới, không còn đúng bộ input đã được nhận xét ở đây.

```powershell
mvn --batch-mode --no-transfer-progress '-Dtest=ConversationLocalEvaluationTest#replaySavedInputsWithFactsFirstSchema' '-Dconversation.evaluate=true' '-Dconversation.schema-order=true' test
mvn --batch-mode --no-transfer-progress '-Dtest=ConversationLocalClientTest,ConversationLocalEvaluationTest#checkCollectedOutputStructure' '-Dconversation.evaluate=true' '-Dconversation.raw-structure=true' '-Dconversation.raw-report=target/conversation-local-schema-order-evaluation.json' test
```

Test thu thập không chứng nhận nội dung đúng; thành công nghĩa là thu đủ output và quan sát đúng thứ tự mới. Test cấu trúc không đánh giá ngữ nghĩa. Unit test không gọi model thật.

Baseline SHA-256: `0df45d8fb851a9c8e3271d4a91bce8b028bee39bc5b926eb143fefc6401cc65d`, giữ nguyên sau replay. JSON kết quả mới: `target/conversation-local-schema-order-evaluation.json`, SHA-256 `d9778af5da255aaf72622a4618193c73e76f015be1c6e0573057df859f2c2f10`. Dữ liệu tổng hợp, không chứa thông tin người học thật, không đưa runtime JSON vào Git.

Tổng thời gian gọi mới 425,33 giây, mỗi ca 22,72–56,95 giây. Không gọi API trả phí, không đổi model mặc định, không bật pilot và không deploy.

Kiểm tra cuối: 7 unit test client và 1 kiểm tra file kết quả đạt; xác nhận 12/12 đúng cấu trúc, thứ tự quan sát được và schema test khớp schema đã dùng cho đợt chạy thật. Hồi quy `mvn --batch-mode --no-transfer-progress -Pci-unit verify`: 124 test được phát hiện, 116 đạt, 8 opt-in bỏ qua, không lỗi, build thành công. Những kết quả xanh này không chứng nhận chất lượng ngữ nghĩa của model.

UTF-8 đạt cho cả 4 tệp thay đổi, không cần sửa encoding. Giao test và báo cáo trên nhánh `codex/practice-drafts-audio-20261017`; trạng thái push và remote commit được xác nhận riêng khi bàn giao. GitHub Actions chưa xác minh được qua API vì trả HTTP 404 trong môi trường hiện tại. Không coi push nhánh là production deployment.
