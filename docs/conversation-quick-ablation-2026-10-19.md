# Bỏ riêng từng thành phần: 5 ca × 2 seed

Hoàn tất: 30 output mới + 10 output mốc đã lưu, đủ 40 output/verdict. Không lỗi sinh/timeout hoặc lỗi cấu trúc verdict. Không sửa prompt production, không triển khai. Số pass của judge không phải số câu chắc chắn đúng.

## Thiết kế

Mục tiêu: kiểm tra ba thành phần có còn giúp bản slots-first hiện tại hay không. Không suy từ kết quả sửa cộng dồn rằng từng thành phần vô ích. Không sửa prompt production trong phép thử này.

- Mốc `s5-temperature` của commit `4b262e8`: Qwen3.5:9b Q4_K_M, `think=false`, context 8192, tối đa 1000 token, temperature 0,3. Kiểm model digest trước khi chạy. Giữ timeout 600 giây.
- Chốt năm input trước khi đọc kết quả tập con: cafe A1 lượt 2; hotel A2 lượt 4; directions B1 lượt 3; plans A2 lượt 3; delivery B1 lượt 2. Có đủ năm nhóm tình huống, ba level, ba lượt; chỉ một ca chuyển giao nên chưa đại diện đủ lượt 4.
- Seed 17 và 43; mỗi bản 10 output. Dùng lại 10 output và verdict mốc đã lưu, không gọi lại để tiết kiệm thời gian/chi phí. Sinh thêm 30 output đầu tiên; không repair, retry chất lượng hoặc chọn lại theo kết quả. Mốc chạy ở thời điểm khác; không dùng so sánh thời gian để kết luận tốc độ hoặc hứa tái lập tuyệt đối theo seed.
- Mỗi bản bỏ một nhóm độc lập so với mốc đầy đủ, không bỏ cộng dồn. Giữ nguyên schema, slots-first, code gán ID, mục tiêu số từ, temperature, model và tham số sinh.

| Bản | Bỏ gì | Giữ gì cần phân biệt |
| --- | --- | --- |
| q0-current | Không bỏ; output cache từ s5 | Toàn bộ bản hiện tại |
| q1-no-roles | Quy tắc vai chuyên biệt, partnerRole/learnerRole và speaker trong dữ liệu/ví dụ/brief | Brief còn lại, lịch sử, few-shot; không xóa yêu cầu bài nghe/câu hỏi có nghĩa |
| q2-no-brief | Brief cuối theo lượt; chỉ mục previouslyTestedDetails/weakSkills; khôi phục transcript/journey gốc lượt 4 | Quy tắc encounter chung trong system, vai, few-shot, mục tiêu số từ cụ thể của s4 |
| q3-no-fewshot | Cặp user/assistant minh họa và câu chỉ dẫn cặp đó | System rules, brief và dữ liệu hiện tại nguyên vẹn |

`q2-no-brief` đo cả nhóm brief + xử lý context của s2, không chỉ hiệu quả của thẻ `<turn_brief>`. Dữ liệu few-shot vẫn khớp mức/lượt; không đưa lại ví dụ cũ thuộc production. `q1-no-roles` không xóa vai ngầm trong tên tình huống/hành trình; đó là phần còn lại của bài toán chứ không phải thông tin vai bổ sung đang đo.

## Đánh giá

Dùng cùng judge offline v6 và quy tắc encounter đã đóng băng. Judge nhận cùng input gốc + vai chuẩn của mốc cho mọi bản, kể cả bản không gửi vai cho model sinh; không thay tiêu chí chấm theo điều kiện thử. Không thay prompt chấm bài của người học hoặc reviewer production.

Tách nhóm nội dung (câu hỏi–fact, trả lời được từ bài nghe, fact một ý, dịch, vai), nhóm mạch/chuyển giao/độ khó, và hard check bằng code. Báo lỗi judge riêng, không coi ca thiếu verdict là đúng/sai. Các lỗi có thể chồng lặp. Đây là nhãn AI, không có người thật duyệt; bộ nhỏ và được dùng để phát triển, không phải test độc lập.

Ledger riêng có trần 2 credit hiện có, không nạp tiền tự động. Không công bố key/số dư. Tất cả input/request/output/verdict nằm dưới `data/conversation-quick-ablation-20261019/` được Git bỏ qua; giữ bản ghi không thay thế lỗi.

## Chạy lại

```powershell
python scripts/test-conversation-ablation.py
python scripts/conversation-quick-ablation.py test
python scripts/conversation-quick-ablation.py
python scripts/conversation-quick-ablation.py audit
```

Cần runtime của đợt 15 × 3 đã có trên máy và Ollama đang phục vụ đúng model. Lệnh tiếp tục phần chưa chạy, không ghi đè request đóng băng hoặc sinh lại ca đã lưu. Muốn đổi thiết kế phải tạo đợt mới, không dùng lại tên/thư mục hiện tại.

## Kết quả hoàn chỉnh

| Bản | Output | Qua nhóm nội dung | Qua mọi tiêu chí | Lỗi judge |
| --- | ---: | ---: | ---: | ---: |
| Mốc đầy đủ, cache | 10 | 10/10 | 6/10 | 0 |
| Bỏ vai | 10 | 7/10 | 4/10 | 0 |
| Bỏ brief/context | 10 | 4/10 | 3/10 | 0 |
| Bỏ few-shot | 10 | 7/10 | 7/10 | 0 |

Các con số trên là nhãn máy, không phải số câu được người thật xác nhận. Bỏ vai có 0 cặp từ fail sang pass, 2 cặp từ pass sang fail theo toàn bộ rubric (hotel A2 seed 43 và delivery B1 seed 43). Các loại lỗi máy gán: vai 1, mạch 4, câu hỏi–fact 1, dịch 2; số từ 6. Mốc có lỗi mạch 2, độ khó 2 và số từ 5. Không cộng các loại lỗi thành số ca riêng biệt.

Bỏ brief/context có 2 cặp từ fail sang pass và 5 cặp từ pass sang fail theo toàn bộ rubric. Nhãn lỗi máy: câu hỏi–fact 3, fact gộp ý 3, dịch 2, vai 2, mạch 2, chuyển giao 2; số từ 8. Cả hai nhóm mới không lỗi sinh, ID, số fact hoặc evidence substring. Không coi mọi nhãn atomicity/continuity là lỗi chắc chắn vì có các bất đồng dưới đây.

Bỏ few-shot có 2 cặp từ fail sang pass và 1 cặp từ pass sang fail theo toàn bộ rubric; không phải bằng chứng thắng rõ. Lỗi độ dài giảm từ 5 xuống 3, nhưng xuất hiện 1 evidence không substring. Các nhãn nội dung/mạch của judge có sai sót đáng kể, được giữ nguyên để đối chiếu.

| Loại lỗi, có chồng lặp | Mốc | Bỏ vai | Bỏ brief/context | Bỏ few-shot |
| --- | ---: | ---: | ---: | ---: |
| Câu hỏi–fact, judge | 0 | 1 | 3 | 2 |
| Không trả lời được từ bài nghe, judge | 0 | 0 | 0 | 0 |
| Fact gộp/tách ý, judge | 0 | 0 | 3 | 1 |
| Dịch, judge | 0 | 2 | 2 | 0 |
| Vai, judge | 0 | 1 | 2 | 1 |
| Mạch lượt 2–3, judge | 2 | 4 | 2 | 3 |
| Chuyển giao lượt 4, judge | 0 | 0 | 2 | 0 |
| Độ khó, judge | 2 | 0 | 0 | 0 |
| Input coherence, judge | 0 | 0 | 0 | 0 |
| Số từ ngoài khoảng, code | 5 | 6 | 8 | 3 |
| Evidence không substring, code | 0 | 0 | 0 | 1 |
| Sai schema/ID/số fact, code | 0 | 0 | 0 | 0 |
| Lỗi sinh/timeout | 0 | 0 | 0 | 0 |

| Bản | Nội dung seed 17 | Nội dung seed 43 | Mọi tiêu chí seed 17 | Mọi tiêu chí seed 43 |
| --- | ---: | ---: | ---: | ---: |
| Mốc | 5/5 | 5/5 | 4/5 | 2/5 |
| Bỏ vai | 4/5 | 3/5 | 4/5 | 0/5 |
| Bỏ brief/context | 2/5 | 2/5 | 1/5 | 2/5 |
| Bỏ few-shot | 3/5 | 4/5 | 3/5 | 4/5 |

Tập con được chốt theo tình huống/mức/lượt trước khi đọc điểm tập con, nhưng tình cờ mốc có `contentPass=10/10`. Đây là hiệu ứng trần và có cả lỗi judge bỏ sót; không suy rằng bản hiện tại đúng 100% hoặc đại diện cho cả 45 ca. Cùng judge/prompt không bảo đảm cùng mức độ đúng giữa các verdict. `judgeFailures=0` chỉ nghĩa là verdict hợp cấu trúc, không nghĩa là lý do chấm hợp lý.

### Bằng chứng và bất đồng judge

- Bỏ vai, hotel A2 seed 43: `at nine o'clock` nhưng fact là `Bảy giờ`. Lỗi giá trị/diễn nghĩa rõ, evidence vẫn là substring nên chỉ kiểm cấu trúc không phát hiện được.
- Bỏ vai, delivery B1 cả hai seed: người nói nói với người nhận hàng (`If you cannot be home...`), khác partnerRole người nhận hàng được cố định trong bài toán; model đã chuyển sang lời người giao hàng. Judge bỏ sót ở seed 17, seed 43 lại bác theo mạch thay vì đúng lỗi vai.
- Bỏ vai, directions B1 seed 43: judge bác `Lúc tám ba mươi sáng` vì khác cách viết `8 giờ 30 sáng`, mặc dù chính lý do của judge thừa nhận hai cách giữ nguyên nghĩa. Đây là false positive rõ; giữ nguyên nhãn máy để kiểm toán, không tự tính là lỗi nội dung chắc chắn.
- Bỏ vai, plans A2 seed 43: judge bác việc thêm giờ gặp/thời tiết, đồng thời giải thích đó là ý mới phù hợp và không mâu thuẫn dữ kiện cũ. Đây là lý do chấm tự mâu thuẫn về nghĩa dù flags/issues vẫn khớp về cấu trúc. Không dùng nhãn này để chứng minh prompt kém đi.
- Cả mốc và bản bỏ vai, cafe A1 hai seed: người nói là khách (`I would like...`), người học là người phục vụ, nhưng hỏi `Bạn muốn...` hướng về sở thích người học. Mốc được judge cho qua nhóm nội dung cả hai seed; bản bỏ vai chỉ bị gán lỗi vai ở một seed với lý do chưa chính xác. Cả hai còn hỏi lại món ăn/đồ uống đã học. Vì vậy mốc `10/10 contentPass` không chứng minh không có lỗi nội dung.
- Bỏ brief, hotel A2 seed 17: `We have moved breakfast to the lobby` tiếp tục thay đổi bữa sáng của cuộc gặp cũ, thay vì tạo cuộc gặp độc lập ở lượt 4. Judge bắt đúng lỗi chuyển giao này.
- Bỏ brief, directions B1 seed 17: câu hỏi hỏi thêm điều cần chú ý khi đi bộ; bài nghe có `Watch out for puddles`, nhưng facts chỉ gồm xe buýt bị hủy và đi bộ đến cổng công viên. Thiếu đáp án được hỏi trong facts, dù bài nghe đủ thông tin. Judge bắt đúng lỗi câu hỏi–fact.
- Bỏ brief, directions B1 seed 43: `twelve o'clock` nhưng fact ghi `Hai giờ trưa`; translationVi lại ghi đúng mười hai giờ. Đây là lỗi nghĩa rõ trong fact.
- Bỏ brief, delivery B1 seed 43: `I expect to deliver... please leave it...` đổi vai và trộn lời người giao/người nhận; `five o'clock` còn bị thêm `chiều` trong fact dù không có AM/PM. Judge chỉ báo vai, bỏ sót chi tiết thời gian.
- Bỏ brief, plans A2 seed 17: nhãn `Hiểu lý do và mục đích` nhưng câu hỏi chỉ hỏi thời tiết và giờ gặp, không hỏi lý do. Judge bỏ sót lỗi chọn skill. Chiều ngược lại, việc bỏ nhắc lại công viên không tự chứng minh đã đổi địa điểm; không thể tự khẳng định `tomorrow` khác thứ Bảy khi input không có mốc ngày. Nhãn continuity của judge ở ca này cần xem lại.
- Bỏ brief, hotel A2 seed 43: hỏi `phòng bạn thế nào?` quá rộng so với fact chỉ nói sẵn sàng, trong khi bài nghe có thêm tầm nhìn bị che. Cần xem lại cách hỏi; không tự kết luận cứ dùng `Your room` là tiếp tục phòng cũ. Một số nhãn atomicity ở nhóm này cũng chia máy móc hành động kèm đích đến hoặc đòi gộp/tách cùng lúc, không phải bằng chứng lỗi chắc chắn.
- Bỏ few-shot, cafe A1 cả hai seed: câu hỏi chuyển sang `Khách gọi...`, khớp vai hơn `Bạn muốn...` của mốc. Đây là dấu hiệu đáng thử ví dụ khớp cả vai thay vì chỉ mức/lượt; không chứng minh chắc chắn ví dụ gây lỗi ngôi. Cả hai vẫn hỏi lại món/đồ uống cũ với đáp án mới. Judge ở seed 43 vừa ghi mapping đúng vừa gán false vì phân biệt "loại đồ uống" và "món cụ thể"; đây là false positive rõ. Ở seed 17, câu hỏi gộp `Khách gọi món gì?` kém rõ hơn việc hỏi tách đồ uống và món ăn; không tự kết luận mọi câu hỏi tập hợp đều chỉ được một fact.
- Bỏ few-shot, directions B1 seed 17: evidence `arrive at River Street... at 10:30 AM` ghép bằng dấu ba chấm, không phải substring. Bài nghe nói đến River Street gần bảo tàng, câu hỏi lại hỏi giờ đến bảo tàng; cần xem lại yêu cầu trả lời trực tiếp, không mặc định hai điểm đến tương đương. Judge cho qua toàn bộ ngữ nghĩa ở ca này; hard check vẫn bắt evidence sai.
- Bỏ few-shot, delivery B1 seed 17: partnerRole người nhận, learnerRole người giao; `Please deliver...` đúng là người nhận nói với người giao. Judge lại yêu cầu người giao phải nói, diễn giải ngược metadata, nên nhãn vai là false positive rõ. Không nhắc lại reception/người ký không tự chứng minh thay đổi sắp xếp. Ca plans A2 seed 17 còn hỏi lại loại ý vật cần mang đã kiểm ở lượt 2 nhưng judge bỏ sót.
- Bỏ few-shot, hotel A2 seed 17: `Bạn có thể cung cấp loại phòng và thời gian nhận phòng không?` diễn đạt mơ hồ như yêu cầu cung cấp phòng/thông tin, không phải câu hỏi nghe hiểu trực tiếp rõ ràng. Cần xem lại, không dùng verdict pass để bảo đảm chất lượng câu hỏi.
- Bỏ few-shot, directions B1 seed 43: bản dịch đổi `by 9:30 AM` thành `lúc 9:30 sáng`, làm mất nghĩa hạn chót/chậm nhất. Judge bỏ sót. Cặp này được gọi là "cải thiện" theo semanticPass chủ yếu vì mốc bị phạt level do số từ, còn ứng viên ngắn hơn vẫn được cho qua; không phải bằng chứng cải thiện độ khó ngôn ngữ.

Các nhận xét bổ sung do trợ lý AI đọc input/output; không phải human review hay một bộ gold mới. Không sửa verdict máy hồi tố, không dùng độ đẹp/tự nhiên của câu làm sai dữ kiện nếu nghĩa vẫn đúng.

## Kết luận và phạm vi

1. Chưa thể nói ba phần thêm trước đó "không có tác dụng". Thử cộng dồn không đo tác dụng độc lập; đợt bỏ riêng cho thấy có tương tác với slots-first và dữ liệu lịch sử.
2. Chưa nên bỏ nhóm brief/context: có lỗi cụ thể tiếp tục bữa sáng cũ ở lượt 4 và thiếu fact đúng câu hỏi. Không quy mọi chênh lệch riêng cho thẻ brief vì nhóm thử còn khôi phục lịch sử/journey cũ.
3. Chưa nên bỏ vai hoàn toàn. Có ca model chuyển người nhận thành người giao khi không cấp vai; chính bản giữ vai cũng còn lỗi ngôi câu hỏi, nên cần sửa chất lượng chỉ dẫn/ví dụ chứ không coi metadata là bảo đảm.
4. Few-shot là phần đáng cân nhắc rút gọn hoặc sửa tiếp nhất, không có bằng chứng rõ phải giữ nguyên hiện trạng. Bỏ nó có điểm tốt về ngôi ở cafe và số từ, nhưng có lỗi evidence mới và câu hỏi mơ hồ. `7/10` so với `6/10` theo mọi tiêu chí không đủ để tuyên bố thắng: chỉ 10 output, có một số ca judge sai cả hai chiều. Chưa tự động chọn bản bỏ few-shot làm production.
5. Không dùng các verdict này làm nhãn sạch cho fine-tuning. Giữ riêng lỗi chắc chắn, ca cần xem lại và nhãn máy gốc; cần chuẩn hóa cách đánh giá trước khi tối ưu theo tổng điểm. Không đổi reviewer/chấm bài backend trong nhiệm vụ này.

## Kiểm tra và bàn giao

- 13 test ablation cũ/mới, 1 test biến thể kiểm cả 15 request nguồn × 3 phép bỏ, 18 test distillation: đạt. Python compile và UTF-8 cho bốn file nhiệm vụ đạt.
- Audit đủ 40 output/verdict, cùng năm input và hai seed; judgeInput cố định giữa các điều kiện; hash request theo nội dung khớp toàn bộ, wire hash khớp 30 request mới; outputHash của verdict khớp. Request tái dựng từ phép bỏ đơn lẻ khớp bản đóng băng. Mốc cache không có wire hash của thời điểm chạy cũ, như đã ghi ở đợt trước.
- Chỉ sửa runner/test/report offline. Không thay Java, prompt production, validation, reviewer hoặc grading; không cần chạy lại suy luận do sửa báo cáo/audit không đổi request. Không triển khai production hoặc chạy thêm 45 ca ngoài phạm vi thử nhanh này.
