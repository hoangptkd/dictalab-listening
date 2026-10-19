# Thử nghiệm cải tiến hội thoại offline — 20/10/2026

## Phạm vi và cách đọc

Chỉ thay đổi công cụ thử nghiệm offline. Không sửa prompt production, không thêm chặn backend, không deploy. Tái sử dụng s4 ở temperature 0,7, 15 input × seed 17/43/97. Các bước thử là tích lũy, không phải ablation độc lập.

Mọi nhận định dưới đây là **AI rà soát**, không phải gán nhãn thủ công. Không có người thật duyệt; không dùng tỷ lệ judge cho qua làm “độ chính xác thật”. Chưa đủ bằng chứng khác với đúng.

Nguồn gốc: s4 trong `data/conversation-ablation-20261019/`; verdict cũ `listening-ablation-v6-context-rules`. Thử nghiệm mới và toàn bộ request/response lưu riêng ở `data/conversation-offline-refinement-20261020/`, ngoài Git. Không sửa hoặc ghi đè output cũ.

## 1. AI rà soát s4 và verdict

Tiêu chí:

- **R — Vai:** câu tiếng Anh do partner nói; câu hỏi hiển thị cho learner. “Bạn” trong câu hỏi chỉ learner, không tự đổi thành partner. “I meet you” có tính đối xứng, không tự nó đảo vai.
- **Q — Câu hỏi:** đọc đúng điều câu hỏi yêu cầu, không thay bằng ý định trong slots. Mệnh lệnh/phần đã nói sẵn không phải một ý đang được hỏi. Không lộ giá trị đáp án.
- **F — Fact:** mỗi ý được hỏi có đúng một đáp án tương ứng; evidence thực sự hỗ trợ ý đó. Không thêm lý do chưa hỏi. Một tuyến đường có thể là một ý, không tách máy móc động từ và địa danh.
- **T — Dịch:** giữ ý, số, mã, giới hạn, điều kiện; không tự thêm AM/PM. Đọc cả bài nghe để xác định ngữ cảnh. Phân biệt *salmon/cá hồi*, *warm/ấm*.
- **C — Tiếp diễn:** lượt 2–3 giữ sắp xếp đã chốt và hỏi ý mới. Lặp ý thuộc continuity, không tự làm sai question–fact.
- **X — Chuyển giao:** lượt 4 là cuộc gặp độc lập trong cùng nhóm tình huống. Cần liên kết đặc thù với chuyện cũ để bác tính độc lập; “the parcel”, “instead”, chào khách quay lại chưa đủ. Hai chi tiết cụ thể được giữ/sửa hoặc tham chiếu rõ chính cuộc trò chuyện trước là bằng chứng mạnh. Không đủ liên kết thì ghi chưa đủ bằng chứng.
- **M — Thước đo:** số từ đo riêng bằng code; không gọi câu đơn giản là sai nội dung chỉ vì ngắn. inputCoherent chỉ xét input, không xét lỗi model vừa sinh.

### Các bất đồng có căn cứ

| Ca s4 | Trích dẫn | Kết luận AI rà soát và lý do |
| --- | --- | --- |
| cafe-A1-t2:43 | “I would like an orange juice…” / “**Bạn gọi** loại đồ uống gì…” | **Judge sai ở R:** learner là server nhưng câu hỏi quy hành động gọi món cho learner. Judge đúng khi bác C do hỏi lại đồ uống/món ăn. |
| cafe-A1-t2:97 | “I would like a coffee…” / “**Bạn gọi** đồ uống gì…” | **Judge sai ở R**, cùng cơ chế trên; lỗi C đã được bắt đúng. |
| cafe-A1-t2:17 | “**Anh/chị gọi** đồ uống gì…” | C được bác đúng. **Chưa đủ bằng chứng để coi cách xưng hô này là một ca đảo vai chắc chắn** nếu không xét quy ước người nhận câu hỏi; quy ước learner phải được định nghĩa trong judge. Không dùng làm gold hiệu chỉnh R. |
| cafe-B1-t4:43 | “I would like to order…” / “**Bạn muốn gọi** món gì…” | **Judge bỏ sót R:** speaker là customer, learner là server. X không bị bác chỉ vì đổi món là đúng. |
| cafe-A2-t3:97 | “I will pay by credit card” / “**Bạn sẽ thanh toán** bằng gì…” | **Judge bỏ sót R. Judge sai M** khi level=false chỉ vì 14 từ dù thừa nhận ngôn ngữ phù hợp. |
| cafe-B1-t4:97 | “**Please tell me the preparation time**” | **Judge đúng Q/F:** đây là yêu cầu cung cấp thời gian, không phải thời lượng đã được nói. **Bỏ sót R** ở “Bạn muốn gọi”; phạt level chỉ do số từ là sai M. |
| delivery-A2-t4:43 | “collect it from the guard… **sign your name**” / fact “**Bảo vệ**” | **Judge đúng Q/F:** bảo vệ không phải người được bảo ký. **Bỏ sót R:** speaker được chỉ định là người nhận, learner là courier nhưng lời nói biến learner thành người đến lấy và ký nhận. |
| directions-A1-t4:43 | “**Hãy đi thẳng và rẽ phải tại đâu?**” / f1 “Thẳng tiếp tục” | **Judge sai Q:** chỉ hỏi địa điểm rẽ, đã lộ hướng đi; fact hướng đi là thừa. Không được đọc lại câu này thành hai câu hỏi “hướng nào + mốc nào”. |
| directions-A2-t2:97 | f2 “Cẩn thận với vũng nước **vì trời đang mưa**”; evidence “**because it is raining**” | **Judge bỏ sót F:** hỏi cần chú ý điều gì, không hỏi lý do; evidence không tự hỗ trợ đáp án vũng nước. **Sai M:** 17 từ không tự làm level sai. |
| hotel-B1-t2:43 | “after **four o'clock**” → “Sau bốn giờ **chiều**” | **Judge bỏ sót T:** không có PM trong bài nghe. “Good morning” không chứng minh giờ nhận phòng là chiều. Phạt level chỉ do 29 từ cũng sai M. |
| plans-A2-t3:17 | “at ten o'clock **instead of Saturday**” | **Judge sai M:** cụm vô lý do output tạo ra, không phải input lỗi. T bị bác đúng ở “that day” → “Hôm nay”. Skill điều kiện không phù hợp câu hỏi giờ/thời tiết cần đánh giá riêng. |
| plans-A2-t3:43 | “**warm** and sunny” → “**Nóng** và nắng” | **Judge bỏ sót T:** warm là ấm, không phải hot/nóng. Không gọi đây là lỗi schema. |
| cafe-B1-t4:17 | “grilled **salmon**” → “**Cá** nướng” | **Judge bỏ sót T:** mất loại cá cụ thể ở cả fact và bản dịch. Không phạt X vì tình huống mới là hợp lệ. |
| hotel-A2-t4:43 | “**Welcome back. Your room is the same as last time**” | **Chưa đủ bằng chứng cho X=false:** có thể là kỳ lưu trú mới của khách quay lại, không nêu số phòng hay giá trị đặc thù cũ. Tuy nhiên **T/Q sai chắc chắn**: “nine” → fact “Mười giờ”; thứ tự slots wifi/breakfast cũng không khớp facts breakfast/wifi. |
| plans-B1-t4:17 | “**Saturday at the park because the cafe will be closed**” | **Judge đúng X:** cùng ngày, công viên, lý do quán đóng cửa, rồi sửa địa điểm/giờ. Đủ liên kết đặc thù với chuyện cũ. |
| plans-B1-t4:43 | “cafe near the **park**… closed… **Saturday**… Sunday… instead” | **Judge đúng kết luận X**, nhưng trích “cancel our plans” riêng lẻ không đủ; cần trích cả tổ hợp quán/công viên/thứ Bảy. Evidence cũ yếu hơn nội dung thực tế. |
| plans-B1-t4:97 | “the picnic on **Saturday**… The original plan is cancelled” | **Chưa đủ bằng chứng:** một giá trị Saturday và picnic chung; history không nói rõ sự kiện là picnic, dù chăn/bánh gợi ý. Không được biến suy luận này thành liên kết chắc chắn. |
| directions-A2-t2:17 | “Walk straight…” sau chỉ dẫn “Take the bus…” | **T bị bác đúng:** Walk → “Chạy”. **Chưa đủ bằng chứng để bác C** chỉ vì đi bộ: có thể là đoạn đến trạm; output chưa nói thay toàn bộ hành trình xe buýt bằng đi bộ. |
| directions-A1-t4:97 | “Go straight to the roundabout, then turn right…” / “Tiếp theo đi đâu…” | **Q còn mơ hồ**, không mặc định “tiếp theo” chỉ là rẽ phải. **Lý do atomic=false của judge không hợp lệ:** đi thẳng đến vòng xoay có thể là một chỉ dẫn duy nhất, không bắt buộc tách động tác và điểm đến. |
| delivery-B1-t2:97 | “five o'clock” → “năm giờ **chiều**”; fact thêm “**vì đồ dễ vỡ**” | Judge bắt đúng chữ Trung trong question, nhưng **bỏ sót T/F**: tự thêm PM, thêm lý do vào fact khi chỉ hỏi cách xử lý. |

### Các ca còn lại đã đọc đối chiếu

Nhận định dưới đây giới hạn ở các tiêu chí nêu trên, không phải chứng nhận hoàn hảo toàn bộ bài:

| Ca | Bằng chứng và kết luận |
| --- | --- |
| cafe-A2-t3:17 | “peanuts” → “Hạt điều”; “please tell me if…” không cho biết món nào có hạt. Judge bác Q/F/T đúng. |
| cafe-A2-t3:43 | “credit card”, “at two o'clock” khớp hai đáp án; R đúng qua “Khách…”. Chuyển nhận đồ sang giao đến phòng họp có thể là điều chỉnh yêu cầu; chưa đủ bằng chứng để kết luận C chắc chắn sai. |
| hotel-A1-t3:17,43,97 | “by one o'clock/clean”, “noon/ten dollars”, “noon/front desk” khớp các ý hỏi và facts. Chưa thấy lỗi chắc chắn ở R/Q/F/T. Không tự coi deadline trả khóa trước 11 và trả phòng trưa là mâu thuẫn tuyệt đối. |
| hotel-A2-t4:17 | “sunshine123” → fact “Sunshine123”: mã bị đổi hoa/thường; judge bỏ sót T theo yêu cầu giữ nguyên mã. “room… in the lobby” là nội dung vụng/vô lý, không chứng minh đang tiếp diễn kỳ lưu trú cũ. |
| hotel-A2-t4:97 | “two o'clock in the afternoon” hỗ trợ “Hai giờ chiều”; không bác X chỉ vì còn trong nhóm khách sạn. Mật khẩu viết dạng lời có độ mơ hồ về việc viết số/chữ; không gán một chuỗi ký tự chuẩn mà bài chưa quy định. |
| hotel-B1-t2:17 | “two o'clock”, “envelope under your door mat” khớp hai ý hỏi, không tự thêm PM. Chưa thấy bất đồng nội dung chắc chắn. |
| hotel-B1-t2:97 | “twenty o'clock sharp” là tiếng Anh thiếu tự nhiên; nghĩa 20 giờ vẫn nhận biết được. Tách lỗi chất lượng ngôn ngữ khỏi sai giá trị đáp án. |
| directions-A1-t4:17 | “main entrance”, “old bridge” khớp nơi bắt đầu/đích đến; không có liên kết đặc thù với hành trình cũ. X không được chứng minh sai. |
| directions-A2-t2:43 | “Walk north… fountain. Then wait for the bus…” vẫn giữ phương tiện bus, không phải đổi sang đi bộ toàn tuyến. Hai đáp án khớp. |
| directions-B1-t3:17 | “half past two”, “five dollars” khớp câu hỏi. Verdict bị lỗi giao thức flags/issues; **không có nhãn judge hợp lệ**, không tính là bài đúng hay sai. |
| directions-B1-t3:43 | “River Street” đã là nơi xuống xe, nay hỏi nơi chuyển sang đi bộ cùng địa điểm: **judge bỏ sót C**, không phải mới ý chỉ vì đổi tên nhãn. |
| directions-B1-t3:97 | “8:30 AM”, “two dollars” khớp câu hỏi. Phạt level chỉ vì 16 từ là sai M; số từ vẫn báo riêng. |
| plans-A1-t2:17 | “Sunday… library” thay Saturday/park mà không thông báo đổi, hỏi lại ngày/nơi. Judge bác C có căn cứ. |
| plans-A1-t2:43 | “eight o'clock” → “Bảy giờ tối”: judge bác Q/T đúng; ngoài sai số còn tự thêm PM. |
| plans-A1-t2:97 | Hỏi “mấy giờ” nhưng chỉ nói “Sunday”; judge bác Q/answerable đúng, đồng thời lặp ý nơi gặp. |
| plans-A2-t3:97 | “6 PM”, “sunny” hỗ trợ facts/câu hỏi; chưa thấy lỗi chắc chắn trong tiêu chí rà soát. Không dùng suy đoán khí hậu để bác thời tiết. |
| delivery-A1-t3:17 | “9 AM…5 PM” → “Sáu đến bảy giờ”; “fifty-five” → “năm trăm năm mươi”: judge bác T/Q đúng. |
| delivery-A1-t3:43 | “Check **if** the package is broken” không khẳng định gói hàng hỏng: judge bác answerable/Q/T đúng. |
| delivery-A1-t3:97 | “cash or credit card **only**” → bản dịch bỏ “only”: judge bỏ sót giới hạn T; facts cho câu hỏi phương thức vẫn có thể chỉ cần liệt kê hai cách. |
| delivery-A2-t4:17 | “side gate”, “call my brother… get the key” không đủ chứng minh cùng việc giao cũ: gate trùng, nhưng brother giữ vai khác. “Bạn gặp tôi/tôi gặp bạn” đối xứng, không tự đảo vai. X chưa đủ bằng chứng. |
| delivery-A2-t4:97 | “side gate”, “leave the package there” khớp ý hỏi; một địa điểm chung chưa đủ liên kết với kiện cũ. X chưa đủ bằng chứng, không gọi là đã chứng minh độc lập. |
| delivery-B1-t2:17 | “after 6 p.m. or before 8 a.m.”, “text message” khớp hai ý hỏi, T giữ điều kiện. Chưa thấy bất đồng chắc chắn. |
| delivery-B1-t2:43 | Question hỏi thời gian và điều kiện cánh cửa, facts trả đúng. Nhưng slot thứ hai “package_condition” không phải “door condition”: quan hệ slot–fact sai dù question/facts khớp. Judge cũ không nhận raw slots nên không phát hiện được. |

## 2. Hiệu chỉnh và đóng băng judge

Judge mới vẫn dùng bước trả lời mù chỉ từ English/question rồi mới audit. Audit bổ sung vai trong cả English lẫn question; ý thực sự được hỏi/lộ đáp án; quan hệ slots–facts; lỗi dịch với trích dẫn; quyết định chuyển giao có trạng thái chưa đủ bằng chứng.

Phiên bản nháp v9 dừng ngay ca hiệu chỉnh đầu vì `transferAudit=continuation` ở lượt 2, không khớp phạm vi trường chuyển giao. Giữ nguyên raw request/response; không dùng để tính baseline. v10 nói rõ lượt 2–3 dùng `not_applicable`. Đây là sửa giao thức judge, không phải model sinh được cải thiện.

v10 bắt được đảo vai nhưng ca giao hàng vừa ghi `decision=uncertain` vừa đặt `transfer=false`: bị dừng vì tự mâu thuẫn. v11 thêm bảng quyết định cuối, hoàn tất 11 ca và khớp **9/11 kỳ vọng của AI rà soát**. Hai bất đồng còn lại là lý do thừa trong fact và khách quay lại. Ở ca khách sạn, judge ghép old “Breakfast is in the garden” với new “breakfast starts at nine…” — ghép **địa điểm** với **giờ**, không phải hai giá trị của cùng một ý. Đây là bằng chứng cụ thể để bổ sung audit liên kết cùng ý, không đơn thuần ép đổi một nhãn không thích.

v12 yêu cầu audit từng fact, trích phần thừa chưa hỏi, và phân loại tham chiếu lần ghé trước/cuộc trò chuyện trước. Mọi kết quả v9–v11 vẫn giữ riêng; không nhập vào điểm baseline. Các file request dự kiến r1–r3 v1 chưa được chạy; bản v2 sửa thứ tự trường trong few-shot English để khớp schema English, giữ nguyên nội dung ví dụ.

v12 khớp 11/11 vector cờ kỳ vọng trong tập hiệu chỉnh, nhưng khi chấm baseline vẫn bỏ sót đảo vai ở `cafe-B1-t4:43`: giải thích thừa nhận câu hỏi hướng tới server mà vẫn cho cờ vai qua. Dừng sạch sau 25/45 verdict, tại ranh giới giữa các lời gọi; giữ nguyên toàn bộ 25 kết quả và không nhập chúng vào baseline mới. Điều này cho thấy qua tập hiệu chỉnh không bảo đảm tổng quát hóa.

v13 tách audit vai vào lời gọi trả lời mù hiện có: chỉ có bài nghe, câu hỏi và vai được chỉ định, không có lịch sử/facts/bản dịch của candidate. Cờ vai và issue tương ứng được suy ra nhất quán từ audit riêng này cho **mọi ca**; ý kiến vai của lời gọi chấm tổng vẫn lưu riêng để xem bất đồng. Đây là phiên bản judge tổng hợp mới, không phải sửa nhãn chọn lọc. Thêm hai ca đảo vai đã bỏ sót, nâng tập hiệu chỉnh lên 13 ca. Số khớp chỉ nói về các cờ kỳ vọng được chọn, không phải toàn bộ bài đã được con người xác nhận.

v13 khớp 11/13 vector kỳ vọng, hai ca còn lại bị loại do giao thức, không phải bất đồng ở cờ cần hiệu chỉnh:

- `input-not-candidate`: judge ghi “hôm nay”, nguồn là “Hôm nay”; `inputCoherent=true` đúng kỳ vọng, nhưng kiểm tra substring phân biệt hoa/thường loại cả verdict.
- `returning-guest-not-proof`: `transfer=true` đúng kỳ vọng; audit đồng thời ghi fact “Mười giờ” không khớp “nine”, nhưng cờ `questionFactsAligned=true`. Không được bỏ qua mâu thuẫn này chỉ vì cờ chuyển giao đã đúng.

v14 (`listening-refinement-v14-audit-derived-flags`) giữ nguyên prompt/model/cấu hình và có hai sửa đổi giao thức áp dụng cho mọi ca:

- Neo trích dẫn bằng substring nguyên văn hoặc một vị trí duy nhất khi bỏ phân biệt hoa/thường; không dung sai sai chính tả, dấu, số hay dấu câu. Quy tắc này **không** cho phép đáp án mật khẩu/mã tự đổi hoa/thường.
- Audit nêu lỗi câu hỏi/fact thừa/bản dịch thì cờ tương ứng phải false. Không có lỗi trong audit **không** tự nâng một cờ đang bị bác thành đúng. Cờ và response gốc được giữ để đối chiếu.

Đọc lại offline đúng response v13 cho kết quả **13/13 vector cờ kỳ vọng khớp**, không phát sinh lời gọi AI. Có hash nguồn, kiểm tra hợp đồng request giống nhau và không chọn verdict thuận lợi. Đây không phải 13/13 bài hoàn hảo, độ chính xác 100% hay gold do người gán nhãn. v14 được đóng băng trước khi chấm baseline và các bản thử.

Đóng băng prompt, model alias, temperature 0, JSON mode, giới hạn token, cấu hình input, full history và vai do hệ thống chỉ định. Lưu hash theo đúng thứ tự serialize của từng request, raw response và model trả về. Không đổi nhãn bằng cách gọi lại để chọn verdict thuận lợi. Sai giao thức/evidence không khớp là **judge failure/unknown**, không phải nội dung sai.

Hạn chế: model cloud là alias `claude-sonnet-4-5`; provider không cung cấp checksum trọng số. Có thể cố định request/alias nhưng không bảo đảm nhà cung cấp giữ cùng checkpoint. Các ca hiệu chỉnh do AI chọn, không phải gold độc lập.

## 3. Thiết kế các bước thử

Kiểm tra code trước khi thử: `ConversationLocalClient.requestBody` hiện yêu cầu `translationVi` và `facts.textVi` trong cùng lần sinh; chưa có lời gọi dịch riêng. Schema đã có slots-first, giới hạn 2 fact ở A1 và code tự gán ID. `ConversationGenerationPrompt` cũng đã có vai, ví dụ theo lượt và brief, nên thử nghiệm không xây lại các phần đó. Production hiện đặt temperature 0,3; benchmark này cố ý giữ s4 ở 0,7 để so đúng baseline được yêu cầu, không đại diện trực tiếp cho tỷ lệ lỗi production.

| Bản | Thay đổi so với bản trước | Điều giữ nguyên |
| --- | --- | --- |
| r0 | Chấm lại 45 output s4 đã có bằng judge mới | Output không sinh lại; temperature gốc 0,7 |
| r1 | Sinh English và `facts.textEn` trước; gọi dịch riêng ở temperature 0,1 để tạo `translationVi`/`facts.textVi` | Slots-first, vai, brief, few-shot và input s4; model 9B, seed, temperature sinh 0,7 |
| r2 | Thêm hợp đồng ngắn: một slot → đáp án đúng chỉ số → câu hỏi thực sự hỏi ý đó, không lộ giá trị; nhắc dùng tên vai khi hỏi hành động của speaker | Schema r1, dịch riêng, input/vai/brief/few-shot |
| r3 | Lượt 2–3 có index sắp xếp/ý đã hỏi. Lượt 4 dùng allowlist input, mục tiêu cuộc gặp độc lập và danh sách giá trị tiếng Việt cần tránh | Full history vẫn gửi cho judge; schema/dịch/temperature như r2 |
| r4 | Một vòng sửa offline cho các ca r3 có lỗi ngữ nghĩa đã chấm; chấm lại toàn bộ tiêu chí | Không lặp đến khi qua; không biến judge lỗi thành ca qua; không backend gate |

r1 thay cả cách sinh fact English và cách dịch: không được diễn giải chênh lệch tổng hợp thành tác động thuần túy của temperature dịch. Cùng seed không bảo đảm cùng bài nghe sau khi đổi prompt/schema.

r2 bổ sung cả nhắc ngôi của câu hỏi trong hợp đồng slot–fact; không được quy toàn bộ chênh lệch cho chỉ số mảng hoặc cho slots-first (vốn đã có từ s4).

Repair giữ đầy đủ input sinh và brief trong user message cuối, thêm candidate trước cùng lỗi đã chấm. Mỗi candidate được sửa một lần rồi chấm lại tất cả tiêu chí, không chỉ tiêu chí vừa lỗi. Ca không sửa tái sử dụng verdict r3; không gọi lại judge chỉ để chấm lại dữ liệu không đổi. Ca sửa thất bại vẫn thay thế kết quả trước trong tổng sau sửa, không chọn kết quả có lợi hơn. Ca judge không kết luận được không tự được coi là đúng hoặc đưa đi repair.

Index trạng thái được ánh xạ từ 15 fixture đã có, không giả vờ đây là bộ trích xuất trạng thái tổng quát. Full history ở lượt 2–3 vẫn có thẩm quyền với chi tiết ngoài index. Lượt 4 không nhận prose lịch sử, journey hoặc recentPractice; danh sách tránh tiếng Việt vẫn có thể gợi lại nội dung cũ, nên cần đo thay vì tuyên bố giải quyết hoàn toàn.

## 4. Tái chạy và kiểm chứng

```powershell
python -X utf8 scripts/test-conversation-offline-refinement.py
python -X utf8 scripts/conversation-offline-refinement.py prepare
python -X utf8 -u scripts/conversation-offline-refinement.py run
python -X utf8 scripts/conversation-offline-refinement.py report
python -X utf8 scripts/conversation-offline-refinement.py audit
```

Cần artifacts s4 gốc và Ollama có đúng digest model đã đóng băng. Key cloud chỉ đọc từ cấu hình runtime, không ghi vào code/Git/report. Có ledger riêng, kiểm tra trước mỗi paid call, không tự nạp tiền/tăng ngân sách; ngưỡng giám sát không phải hard cap từ nhà cung cấp. Nếu ngưỡng chạm hoặc judge không đạt hiệu chỉnh, dừng và giữ kết quả, không tiếp tục bằng một ledger trắng.

## Kết quả

### Baseline s4 đã chấm lại đủ 45 ca

| Thước đo trên cùng output s4 | Judge cũ v6 | Judge cố định v14 |
| --- | ---: | ---: |
| Verdict hợp lệ | 44/45 | 45/45 |
| Không bị bác ở 5 tiêu chí nội dung | 32/45 | 19/45 |
| Không bị bác ở toàn bộ 9 tiêu chí ngữ nghĩa | 21/45 | 16/45 |
| Lỗi giao thức judge / unknown | 1 | 0 |

Đây là **đổi thước đo**, không phải model sinh kém đi. Năm tiêu chí nội dung là khớp câu hỏi–fact, đáp án có căn cứ, fact nguyên tử, dịch và vai. Bốn tiêu chí còn lại là continuity, transfer, mức ngôn ngữ và tính nhất quán input. Không dùng tỷ lệ v6 để so trực tiếp với các bản sinh chấm bằng v14.

| Loại lỗi v14 | Số ca baseline |
| --- | ---: |
| Câu hỏi–slot–fact không khớp | 12 |
| Đáp án không được bài nghe hỗ trợ | 5 |
| Fact chứa ý thừa | 4 |
| Dịch sai | 12 |
| Đảo vai | 10 |
| Tiếp diễn lượt 2–3 | 5/30 |
| Chuyển giao lượt 4 | 3/15 |
| Mức ngôn ngữ | 1 |
| Input không nhất quán | 0 |

Một ca có thể có nhiều lỗi; không cộng các dòng thành số ca sai. Audit ghi 1 ca lộ đáp án và 8 ca lệch slot/fact. Hai ca transfer chưa đủ bằng chứng trong audit judge là `delivery-A2-t4:17,43`; chưa có audit vai ở trạng thái uncertain.

Điểm mù còn lại: v14 vẫn cho qua `hotel-A2-t4:17` dù `sunshine123` thành `Sunshine123`. Đối chiếu literal offline phát hiện thêm lỗi chắc chắn này; nếu loại ca đó thì số ca qua nhóm nội dung còn **18/45**. Đây là thống kê bổ sung, không ghi đè verdict. Chẩn đoán này chỉ xét mã/password dạng literal phù hợp, không phải bộ kiểm tra mọi lỗi số/mã.

**Bất đồng chưa giải quyết:** v14 bác transfer ở `plans-B1-t4:97` bằng “the picnic on Saturday” và “The original plan is cancelled”. Liên kết thứ hai chỉ là một sắp xếp chung, không chỉ ra giá trị thứ hai tương ứng trong lời mới; “park picnic established in turn 1” còn diễn giải picnic từ chăn/bánh trong history. AI rà soát giữ **chưa đủ bằng chứng** cho lỗi transfer ở ca này, không kết luận chắc judge sai hoặc bài độc lập. Hai ca plans seed 17/43 có liên kết rõ hơn như phần rà soát đầu báo cáo. Vì vậy 3 lỗi transfer theo judge có 1 ca còn tranh luận. Ca seed 97 vẫn có lỗi vai chắc chắn: partner nói “I cannot make it…” nhưng câu hỏi hỏi “Lý do bạn hủy”. Không đổi judge giữa các nhánh chỉ để giải quyết một nhãn chưa chắc.

Kiểm tra cứng offline ghi 8 ca ngoài khoảng số từ và 1 ca evidence không phải substring nguyên văn. Các lỗi này được báo riêng, không trộn thành lỗi ngữ nghĩa hoặc thêm chặn backend.

Thời gian baseline được tái sử dụng từ lượt s4 cũ: median **56,36 giây/ca**, tổng **2.519,67 giây**. Đây không phải đo đối chứng đồng thời; không quy chênh lệch thời gian với các lượt hiện tại hoàn toàn cho thay đổi prompt.

### Các bản thử và repair

#### r1 — Tách dịch: đã hoàn tất 45 lượt sinh/chấm

Không có lỗi sinh; **43 verdict hợp lệ, 2 judge failure**. Có 29 ca qua nhóm nội dung và 23 ca qua toàn rubric trong 43 verdict hợp lệ; không tự coi hai ca unknown là đúng hoặc sai.

Đối chiếu đúng **43 cặp chung** (không lấy baseline đủ 45 để so với r1 chỉ có 43 nhãn):

| Tiêu chí không bị bác | r0 | r1 | Cặp tốt lên | Cặp kém đi |
| --- | ---: | ---: | ---: | ---: |
| Nhóm nội dung cốt lõi | 18 | 29 | 14 | 3 |
| Toàn rubric ngữ nghĩa | 15 | 23 | 13 | 5 |
| Dịch | 31 | 42 | 11 | 0 |
| Nội dung ngoài phần dịch | 25 | 29 | 11 | 7 |

| Loại lỗi trên cùng tập cặp | r0 | r1 |
| --- | ---: | ---: |
| Câu hỏi–slot–fact | 11 | 7 |
| Đáp án thiếu căn cứ | 5 | 2 |
| Fact thừa ý | 4 | 5 |
| Dịch | 12 | 1 |
| Vai | 10 | 10 |
| Continuity | 5/30 | 7/30 |
| Transfer | 3/13 | 3/13 |
| Mức ngôn ngữ | 1 | 0 |
| Input | 0 | 0 |

Đây là hiệu quả **của cấu hình English-first + dịch riêng trên tập phát triển này**, không phải tác động thuần túy của temperature dịch. Lỗi dịch giảm rõ trong dữ liệu quan sát; lỗi vai không giảm ròng (5 cặp được sửa nhưng 5 cặp phát sinh), continuity còn tăng. Phần nội dung ngoài dịch chỉ tăng ròng 4 ca, chưa đủ để kết luận chắc tác động tổng quát, nhất là 3 seed của cùng một input có tương quan.

Các ca **hồi quy nhóm nội dung**: `cafe-A2-t3:43`, `delivery-B1-t2:17,43`. Các ca **hồi quy toàn rubric** còn thêm `directions-A2-t2:43` và `hotel-A1-t3:97`. Danh sách tốt lên/kém đi theo từng tiêu chí, từng seed/lượt/level nằm trong `summary.json`; raw verdict và phản hồi gốc không thay đổi. Đã bổ sung thống kê cặp cho từng cờ bằng đọc lại artifact, không gọi lại AI.

Median thời gian: English **92,74 giây**, dịch **25,29 giây**, tổng một ca **118,43 giây**; tổng 45 ca **5.353,93 giây** (~89,2 phút sinh tuần tự). Median của tổng không nhất thiết bằng tổng hai median. Ollama chạy CPU (`size_vram=0`). Không suy ra rằng tách dịch làm chậm production đúng 2,1 lần: baseline 56,36 giây là số đo lịch sử, khác thời điểm. Thêm bước dịch có chi phí thực đo khoảng 25 giây ở median trong lượt này.

Hai judge failure đều ở lượt 4, nên việc chỉ xét 43 cặp không đại diện hoàn toàn cho mọi output. Ca directions đã có lỗi Q chắc chắn theo AI rà soát, xem bảng dưới. Ca `delivery-A2-t4:97` có trích dẫn đúng nguồn nhưng audit giữ một link `sameMeaning=false`, trái yêu cầu chỉ giữ neo cùng ý; verifier báo `Inexact transfer anchors`. Judge thực ra ghi `decision=uncertain` và không nêu issue, chứ không bác transfer. AI rà soát cũng chưa đủ bằng chứng để bác chỉ từ cổng bên trùng nhau; phần dịch “my sister” → “chị tôi” tự chọn tuổi tương đối chưa được English nói rõ, cần thống nhất chính sách dịch trước khi dùng làm nhãn huấn luyện. Không sửa nhãn hoặc gọi lại ca này giữa các nhánh.

Kiểm tra cứng riêng r1: 9 ca ngoài khoảng số từ, 2 ca evidence không là substring nguyên văn. Chưa thấy lỗi case-only literal bằng chẩn đoán hẹp đã công bố; điều này không chứng minh mọi mã/số đều đúng.

#### r2–r4 — Đang chạy

R2 đã bắt đầu. Chưa có cơ sở công bố mức cải thiện r2/r3 hoặc hiệu quả repair.

Các ca r1 đã đọc cụ thể (AI rà soát):

| Ca | Tiêu chí, dẫn chứng và kết luận |
| --- | --- |
| plans-A2-t3:17 | **Dịch sai chắc chắn:** “half past two” là 2:30 nhưng bản dịch và fact đều ghi “hai giờ kém mười lăm” (1:45). Tách dịch ở temperature 0,1 không loại hết lỗi hiểu giờ. Judge còn bác atomic vì “bên ngoài” trong đáp án thời tiết; mức độ đây là một ý thừa độc lập còn có thể tranh luận, không dùng làm kết luận chắc ngoài lỗi giờ đã rõ. |
| delivery-B1-t2:17 | **Đảo vai chắc chắn:** recipient nói “I can only receive the package…” nhưng câu hỏi “Bạn có thể nhận hàng…” hướng tới learner là courier. Bản dịch đúng không giải quyết được sai ngôi của câu hỏi. |
| hotel-A1-t3:97 | **Continuity sai có căn cứ:** history đã chốt ăn sáng “Ngoài vườn”; lượt mới nói “in the lobby restaurant” và hỏi lại “ăn sáng ở đâu”, không báo đổi. Nội dung hiện tại có thể tự khớp nhưng sai trạng thái hội thoại. |
| directions-A1-t4:97 | **Nội dung Q sai chắc chắn theo AI rà soát; judge failure về giao thức:** `questionVi` là “Hãy đi thẳng và rẽ trái tại đồng hồ đỏ lớn. Bạn sẽ thấy thư viện ở bên phải.” — không hỏi ý nào và cung cấp luôn đáp án. Main audit bắt được lộ đáp án, nhưng `requestedPhraseVi` lại ghi “Hướng đi tiếp theo”/“Điểm mốc”, không phải substring của question. Giữ trạng thái unknown của scorer, không gọi lại/chuyển nhãn; đồng thời báo riêng lỗi nội dung đã có bằng chứng. |
