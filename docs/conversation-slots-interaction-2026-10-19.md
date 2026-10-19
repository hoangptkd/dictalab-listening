# Ô còn thiếu: prompt s0 + nhóm slots của s4

Hoàn tất ngày 20/10/2026: 45 output mới + 135 output mốc được giữ nguyên; đủ 180 outcome/verdict record. Không sửa prompt hoặc code ứng dụng production.

## Bước 0 — đọc năm ca lượt 4 bị bác ở s5

Đọc trực tiếp input, request thực gửi, output và lý do judge; đây là nhận xét của trợ lý AI, không phải người thật gán nhãn. Không ghi đè verdict v6.

| Ca | Bằng chứng | Kết luận về chuyển giao |
| --- | --- | --- |
| plans B1, seed 17 | Hủy picnic thứ Bảy ở công viên rồi đề xuất thư viện Chủ nhật; trùng cụm sắp xếp picnic/công viên/thứ Bảy của lịch sử | Lỗi model rõ: tiếp tục đổi cuộc hẹn cũ |
| plans B1, seed 97 | Nhắc lại thứ Bảy, quán cà phê đóng cửa, công viên, chăn và bánh mì kẹp | Lỗi model rõ: kéo cả câu chuyện cũ sang lượt 4 |
| delivery A2, seed 17 | `at the side gate ... instead of leaving it at reception`, đúng cặp địa điểm và chỉ dẫn giao cũ | Bằng chứng mạnh của việc sửa sắp xếp cũ; bác theo tổ hợp dữ kiện, không chỉ vì có `instead` |
| plans B1, seed 43 | `cancel our picnic ... next Sunday`; không nhắc lại ngày/địa điểm/vật dụng đặc thù cũ | Mơ hồ. Có thể là picnic cũ, cũng có thể là một lần đổi lịch độc lập. Chưa đủ căn cứ chắc chắn để bác |
| delivery A2, seed 43 | `The parcel is ready for you at the front desk ... sign ... your own name` | Judge suy diễn danh tính đơn cũ từ `the parcel`; nhãn transfer chưa đủ căn cứ. Lỗi đảo vai người nhận/người giao vẫn có thật và phải chấm riêng |

Vì vậy không được gọi cả năm là lỗi chuyển giao chắc chắn. Hai ca rõ, một ca bằng chứng mạnh, hai ca chưa đủ chứng cứ để bác. Ca mơ hồ không đồng nghĩa chất lượng tốt hoặc đã chứng minh chuyển giao thành công.

Ngoài ra, năm nhãn lỗi ở s0 **không phải cùng năm key** ở s5: s0 gồm cafe B1 seed 17/43, directions A1 seed 17/97, delivery A2 seed 97. Số lượng cùng bằng năm không chứng minh cải tiến không tác động tới loại lỗi này.

Request s5 đã xóa transcript/journey lượt 1–3, nhưng `previouslyTestedDetails` vẫn chứa toàn bộ giá trị cũ; `recentPractice` của delivery còn các mẩu tình huống cũ. Model vẫn có nguồn để dựng lại picnic/giao hàng cũ. Đây là giả thuyết nguyên nhân có bằng chứng đầu vào, chưa phải kết luận nhân quả. Không sửa brief production trong lúc đo ô còn thiếu.

## Sửa cách chấm trước khi đo tiếp

Judge offline giữ nguyên các tiêu chí v6, chỉ bổ sung ngưỡng bằng chứng chuyển giao:

- Không đồng nhất `the parcel`, `our appointment`, `instead`, đổi lịch, hoặc một loại slot quen thuộc với cuộc gặp cũ.
- Muốn bác phải chỉ ra liên kết đặc thù với lịch sử: tham chiếu lại rõ ràng hoặc tổ hợp sắp xếp cũ được tiếp tục/sửa.
- Mơ hồ thì không bác theo chính sách chỉ loại kết quả chắc chắn sai; vẫn kiểm lỗi vai/dịch/facts độc lập.
- Không sửa verdict gốc và không xem tỷ lệ pass tăng sau đổi judge là model được cải thiện.

Thử wording v7 trước: calibration chỉ khớp 4/5; judge vẫn dùng suy diễn `our picnic` để gán cả câu chuyện cũ cho ca plans seed 43. Giữ lại artifact calibration v7 bị lỗi, chưa sinh ô mới và chưa dùng nó chấm kết quả.

Bản `listening-ablation-v8-transfer-anchors` yêu cầu thêm `transferAudit`: phân loại `continuation`/`independent`/`uncertain`, các cặp trích dẫn exact substring của history/current, và tham chiếu trở lại rõ ràng nếu có. Ngưỡng bác vận hành: có ít nhất hai sắp xếp cũ phân biệt được tiếp tục/sửa, hoặc tham chiếu trở lại cuộc nói chuyện cũ rõ ràng; không dùng riêng `our picnic`/`the parcel`/`instead` làm tham chiếu. Code offline kiểm substring, số cặp bằng chứng và sự nhất quán verdict; **không áp dụng vào validation/backend production**. Vẫn có khả năng judge tạo hai trích dẫn đúng chữ nhưng diễn giải sai nghĩa; kiểm này không thay thế người duyệt.

`uncertain` được tính là không đủ bằng chứng bác theo chính sách hiện tại, nhưng được báo riêng, không xem là chứng minh bài chuyển giao tốt. Đây là thay đổi định nghĩa vận hành của chỉ số; không so trực tiếp điểm v8 với v6 để tuyên bố prompt tiến bộ.

Chấm lại toàn bộ s0 vì nay cung cấp vai authored giống các nhánh khác, khắc phục chênh lệch thông tin của lần so sánh trước. Chấm lại 15 output lượt 4 mỗi bản s4/s5 bằng rubric mới. Tái sử dụng 30 verdict lượt 2–3 của mỗi bản s4/s5 vì input judge và rubric hiệu lực không đổi; giữ cả lỗi judge, không gọi lại để tìm nhãn đẹp hơn. Chấm mới đủ 45 output của ô mới. Đã thực hiện 120 lượt đánh giá đầy đủ, mỗi lượt có trả lời độc lập rồi audit; thêm năm lượt calibration v7 và năm lượt v8, tổng cộng 260 lời gọi AI có ghi nhận completion. Calibration dùng năm ca trợ lý vừa đọc, **không phải gold độc lập**.

## Bước 1 — thiết kế ô mới

`s6-baseline-slots`: cùng 15 input đóng băng × seed 17/43/97; model Qwen3.5:9b đúng digest cũ, `think=false`, context 8192, tối đa 1000 token, timeout 600 giây, **temperature 0,7 như s4**. Chỉ lấy output đầu tiên, không repair/retry chất lượng/validation backend.

Giữ system prompt, dữ liệu lịch sử/journey và nội dung few-shot cũ của s0, ngoại trừ thay đổi cần thiết để dùng **cùng nhóm slots của s4**:

1. Schema `slots → utteranceEn → facts → questionVi → translationVi → taskVi → skill`, A1 đúng hai slot/fact, không sinh ID.
2. Chỉ dẫn ngắn giải thích slots và sự tương ứng theo thứ tự; code chuẩn hóa gán f1/f2/f3 như s4.
3. Ví dụ cũ giữ nguyên bài nghe, câu hỏi, bản dịch, task, skill và đáp án; chỉ thêm nhãn slot, đổi thứ tự trường, bỏ ID để không mâu thuẫn schema. Không dùng ví dụ gốm mới.
4. Giữ cùng mục tiêu số từ cụ thể của s4, đưa thành một dòng riêng, **không phải brief theo lượt**. Nếu bỏ cả mục tiêu này thì so với s4 sẽ có thêm yếu tố gây nhiễu ngoài s1–s3.

Không thêm metadata vai, lịch sử có speaker/question mới, brief/context transform hoặc few-shot mới. Vai ngầm/quy tắc vai cũ của s0 vẫn còn; đây không phải nhánh xóa mọi thông tin vai. Đây là thí nghiệm **nhóm slots**, không chứng minh riêng thứ tự JSON gây ra hiệu quả.

So sánh ghép cặp s0→s6 và s4→s6 trên cùng key, cùng seed, cùng vai chuẩn cho judge. Báo cả ca tốt lên lẫn kém đi, lỗi theo loại và lỗi judge; ca không có verdict không coi là đạt/trượt. Chênh lệch dưới 3–4 ca được coi chưa kết luận theo yêu cầu. Chênh lệch lớn hơn cũng chỉ là tín hiệu trên tập development nhỏ, không tự thành bằng chứng thống kê hay độ chính xác thật.

Runtime/input/output/verdict nằm trong `data/conversation-slots-interaction-20261019/`, được Git bỏ qua. Ledger riêng có trần bốn credit hiện có, không nạp tự động; không đưa key/số dư vào báo cáo.

## Chạy và kiểm tra

```powershell
python scripts/conversation-slots-interaction.py test
python scripts/test-conversation-ablation.py
python scripts/conversation-quick-ablation.py test
python scripts/conversation-slots-interaction.py run
python scripts/conversation-slots-interaction.py audit
```

Cần artifact đóng băng của đợt trước trên máy. Resume giữ nguyên output/lỗi đã lưu, kiểm digest model và request hash theo thứ tự trường. Không sửa code/prompt/chấm bài production, không deploy trong nhiệm vụ thử nghiệm này.

## Kiểm tra trực tiếp output mới

Đủ 45 output đầu tiên, không lỗi sinh/timeout. Kiểm cứng trên toàn bộ output: không lỗi schema/ID/số fact; 15 ca ngoài khoảng số từ; một evidence không phải substring (`eval-cafe-A2-t3:43`). Đây là số đo bằng code, không phụ thuộc judge.

Các ví dụ trợ lý AI đọc trực tiếp, không phải nhãn người thật:

- `eval-cafe-A1-t2:17`: câu hỏi `Bạn có muốn lấy trà đá và bánh mì nướng thịt gà không?` vừa có/không vừa lộ đáp án; schema slots vẫn đúng.
- `eval-cafe-A2-t3:17`: `credit card` được dịch trong cả fact và bản dịch thành `thẻ ghi nợ`, khác thẻ tín dụng. Skill ghi hiểu điều kiện nhưng câu hỏi chỉ hỏi địa điểm/phương thức thanh toán.
- `eval-hotel-B1-t2:17`: slots dự kiến là `room_key` và `key_location`, facts là chìa khóa và quầy lễ tân; question lại hỏi nơi lấy **và công dụng**. Model vẫn đổi ý cần hỏi sau khi lập slots, thiếu fact cho công dụng. Slots-first không bảo đảm khớp về nghĩa.
- `eval-hotel-A1-t3:17`: bài nghe chỉ `after two o'clock`, fact thêm `chiều`; câu hỏi còn có vế có/không về tình trạng phòng.
- `eval-plans-B1-t4:97`: skill hiểu lý do/mục đích nhưng hỏi giờ gặp và thời tiết, không hỏi lý do. Phần dịch cũng không giữ rõ `5 PM`.

Bản đầy đủ s4 cũng chưa sạch: cafe A2 seed 17 dịch `peanuts` thành hạt điều và hỏi món nào chứa hạt điều dù người nói chỉ **yêu cầu người nghe cho biết**, không tự cung cấp món nào. Không dùng điểm pass tổng để chứng nhận mọi output của một bản đều đúng.

## Kết quả chấm và ghép cặp

### Toàn bộ record

`contentPass`: câu hỏi–fact, answerability, atomicity, dịch, vai. `semanticPass`: toàn bộ rubric, có cả mạch/chuyển giao/độ khó/input coherence. Đây là nhãn AI, không phải số bài chắc chắn đúng. Không trừ trực tiếp tỷ lệ giữa các hàng có mẫu số khác nhau.

| Bản | Output | Verdict hợp lệ | Qua nội dung | Qua mọi tiêu chí | Lỗi judge |
| --- | ---: | ---: | ---: | ---: | ---: |
| s0 cũ, 0,7 | 45 | 43 | 20/43 | 9/43 | 2 |
| s4 đầy đủ, 0,7 | 45 | 44 | 31/44 | 20/44 | 1 |
| s5 đầy đủ, 0,3 | 45 | 42 | 35/42 | 22/42 | 3 |
| s6 s0 + nhóm slots, 0,7 | 45 | 45 | 24/45 | 15/45 | 0 |

Không có lỗi sinh/timeout ở bốn bản. Hai lỗi judge s0: flags/issues bất đồng; bác chuyển giao thiếu bằng chứng. Một lỗi s4 và ba lỗi s5 được giữ từ verdict lượt 2–3 đã lưu (flags/issues hoặc JSON không hợp lệ). Không sửa chúng thành pass/fail.

### So từng cặp cùng key, cùng seed

| So sánh, trái → phải | Số cặp | Nội dung trái → phải | Fail → pass | Pass → fail | Chênh lệch ròng |
| --- | ---: | ---: | ---: | ---: | ---: |
| s0 → s6 | 43 | 20 → 24 | 13 | 9 | +4, khoảng +9,3 điểm phần trăm |
| s4 → s6 | 44 | 31 → 23 | 5 | 13 | −8, khoảng −18,2 điểm phần trăm |
| s5 → s6 | 42 | 35 → 22 | 2 | 15 | −13; khác temperature, không tách tác dụng s1–s3 |

Theo mọi tiêu chí, s0→s6: 9→15, 11 tốt lên/5 kém đi; s4→s6: 20→14, 7 tốt lên/13 kém đi. Chỉ số này còn bị nhãn độ khó nhầm với số từ và các lỗi judge khác, nên không dùng làm chuẩn chất lượng tuyệt đối.

Đối chiếu thêm trên **42 key có verdict hợp lệ chung cho cả s0/s4/s6**: s0 20/42 (47,6%), s6 23/42 (54,8%), s4 30/42 (71,4%). Đây không phải một đợt sinh khác.

| Seed | s0 → s6, nội dung cùng cặp | s4 → s6, nội dung cùng cặp |
| --- | --- | --- |
| 17 | 7/14 → 8/14 | 9/14 → 7/14 |
| 43 | 8/14 → 9/14 | 11/15 → 9/15 |
| 97 | 5/15 → 7/15 | 11/15 → 7/15 |

Bản đầy đủ hơn ô mới ở cả ba seed, nhưng mức từng seed nhỏ. Mẫu development chỉ có 15 input với ba seed, không phải 45 bài độc lập được người thật duyệt; không tuyên bố ý nghĩa thống kê hay tương tác nhân quả đã được chứng minh.

### Lỗi theo loại

Hai cột dưới dùng đúng **44 cặp s4/s6**. Lỗi có thể chồng lặp; chuyển giao chỉ áp dụng lượt 4, continuity chỉ lượt 2–3.

Riêng s0→s6 trên 43 cặp: nhãn câu hỏi–fact 18→6 và atomicity 5→2, nhưng lỗi dịch 5→11, không trả lời được 2→4, vai 3→3. Điều này giải thích vì sao lợi ích căn chỉnh theo schema không chuyển thành một mức tăng lớn ở chất lượng tổng hợp; các nhãn vẫn có sai sót được nêu dưới đây.

| Nhãn lỗi | s4 đầy đủ | s6 s0 + slots |
| --- | ---: | ---: |
| Câu hỏi–fact | 7 | 6 |
| Không trả lời được | 4 | 4 |
| Fact gộp/tách ý | 0 | 2 |
| Dịch | 9 | 13 |
| Vai | 2 | 4 |
| Mạch lượt 2–3 | 6 | 12 |
| Chuyển giao lượt 4 | 4 | 3 |
| Độ khó, còn nhãn sai do số từ | 4 | 2 |

Kiểm bằng code trên đủ 45 output mỗi bản: số từ ngoài khoảng s0/s4/s5/s6 = **9/8/17/15**; evidence không substring = **0/1/1/1**; lỗi ID/số fact = **0/0/0/0**. Không được nói đã đo thấy cải thiện ID so với s0 trong bộ này, vì baseline vốn không có lỗi ID ở 45 output.

Ngắn prompt chưa chứng minh nhanh hơn: median prompt token s4 2.194 → s6 1.895 (khoảng −13,6%), nhưng median thời gian 56,36 → 58,23 giây. Các output chạy khác thời điểm/tải máy, không dùng thời gian này suy nhân quả hoặc hứa tăng tốc CPU.

### Chuyển giao và độ tin cậy judge

| Nhãn audit lượt 4 | s0 | s4 | s5 | s6 |
| --- | ---: | ---: | ---: | ---: |
| Tiếp tục câu chuyện cũ | 3 | 4 | 3 | 3 |
| Judge coi là độc lập | 4 | 9 | 11 | 8 |
| Chưa đủ bằng chứng kết luận | 6 | 2 | 1 | 4 |
| Lượt 4 thiếu verdict hợp lệ | 2 | 0 | 0 | 0 |

Năm ca kiểm tra ban đầu có cùng quyết định transfer trong calibration v8 và lần chấm thực: plans 17/97 và delivery 17 bị bác; plans 43 và delivery 43 không bị bác. Việc s5 từ năm nhãn lỗi v6 còn ba nhãn v8 là **đổi cách chấm trên cùng output**, không phải model cải thiện. Judge còn tự gọi delivery 43 là độc lập; nhận xét thận trọng của trợ lý vẫn chỉ là chưa đủ căn cứ liên kết đơn cũ, không phải đã chứng minh đó là đơn mới.

Còn lỗi judge cụ thể ngoài chuyển giao:

- s6 cafe A1 seed 17 được cho qua cả năm tiêu chí nội dung dù câu hỏi có/không, lộ đáp án và lời người phục vụ không khớp vai chuẩn. Đây còn được đếm là một cặp "cải thiện" từ s0, nhưng **không phải cải thiện nội dung thật được xác nhận**.
- s6 cafe A1 seed 43 (`You will get your tea...`) và seed 97 (`We accept cash or card...`) là nhân viên nói với khách, khác partnerRole khách/learnerRole phục vụ; judge vẫn cho qua vai. Ba cặp cafe này nằm trong 13 cặp nội dung được gọi là cải thiện s0→s6. Không tự trừ điểm hồi tố để dựng một bộ gold giả.
- Chấm lại cùng output s0 trên 41 key có verdict ở cả v6/v8: nhãn nội dung 25→20, **11 ca đổi nhãn** (ba tốt lên/tám kém đi). Có thêm lỗi thật được bắt như thiếu fact lý do/hỏi một ý nhưng có fact thừa; có ảnh hưởng metadata vai và độ không ổn định của judge. Không coi baseline model đã giảm chất lượng.
- s0 cafe A2 seed 17 bị gán `questionFactsAligned=false` chỉ vì hỏi lại loại ý cũ; đó đúng ra là lỗi continuity, không phải quan hệ câu hỏi–fact. Một số nhãn `levelAppropriate=false` vẫn chỉ phạt số từ, trái rubric.

Do đó +4 ca nội dung của slots đơn lẻ chỉ là **tín hiệu nhỏ/sát ngưỡng và có nhãn cải thiện sai**, chưa đủ để khẳng định slots đơn lẻ cải thiện chất lượng thật. Chênh lệch −8 so với s4 có tín hiệu rõ hơn và cùng chiều cả ba seed, nhưng không được gọi là tám lỗi thật đã được người duyệt xác nhận.

## Quyết định sau phép thử

1. **Giữ nhóm vai + brief/context + few-shot hiện tại**, không rút hết chỉ vì slots-first. Ô mới chưa gần s4 về tỷ lệ nội dung theo judge, còn nhiều lỗi dịch/mạch/vai hơn.
2. Nhóm slots có ích cho cấu trúc và có tín hiệu giảm lệch câu hỏi–fact, nhưng không làm gần hết việc: chất lượng tổng hợp cần sự kết hợp. Chưa thể gán tỷ lệ đóng góp riêng cho từng phần hoặc loại trừ nhiễu chỉ bằng một ô mới.
3. Không chọn s6 làm production. Không có đủ cơ sở để nói giảm 13,6% prompt token sẽ cho tốc độ CPU tốt hơn.
4. Bước sửa tiếp có căn cứ là context lượt 4: tránh đưa nguyên quan hệ/chi tiết cũ dưới dạng "avoid" khiến model tái dựng chuyện cũ; nên tạo một mục tiêu/cuộc gặp độc lập cụ thể rồi đo riêng. **Chưa triển khai bước đó trong thí nghiệm này**.
5. Trước khi dùng điểm judge làm mục tiêu tối ưu hoặc nhãn fine-tuning sạch, cần bộ ca độc lập được người thật gán nhãn và kiểm độ đồng thuận. Không dùng `uncertain` như positive gold.

## Kiểm tra và bàn giao

- 36 test đạt: bốn test ô mới, 13 ablation, một quick-variant, 18 distillation. Python compile và UTF-8 hai file của task đạt.
- Audit 180 record đạt: đủ key, cùng input/seed/role context; output mốc khớp nguyên bản; request semantic hash khớp; **45 request mới có wire hash giữ thứ tự schema**. Đợt cũ không có wire hash nên không nhận là đã kiểm wire order hồi tố cho 135 request mốc.
- Runtime và output không đẩy GitHub. Chỉ bàn giao script và báo cáo; bảo toàn các file đang sửa của người dùng/agent khác.
- Không sửa validation, reviewer hoặc prompt production; không chạy deployment. Chưa có human review hoặc kiểm thử giao diện/production trong đợt này.
