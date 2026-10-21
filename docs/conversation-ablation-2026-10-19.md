# Thử nghiệm prompt hội thoại: 15 ca × 3 seed

Trạng thái: hoàn tất 270 output đầu tiên (6 bản × 15 ca × 3 seed), giữ toàn bộ lỗi; chưa triển khai production.

## Tóm tắt kết quả

Mỗi bản đủ 45 output; không có timeout/lỗi sinh. Mẫu số dưới đây là số verdict hợp lệ, không tự tính ca lỗi judge là ca đúng/sai. Đây là nhãn máy chưa được người thật xác nhận.

| Bản cộng dồn | Qua nhóm nội dung | Qua mọi tiêu chí | Lỗi judge |
| --- | ---: | ---: | ---: |
| s0 Baseline | 26/43 | 19/43 | 2 |
| s1 Vai | 26/45 | 13/45 | 0 |
| s2 Brief theo lượt | 24/43 | 10/43 | 2 |
| s3 Few-shot theo lượt/mức | 23/44 | 10/44 | 1 |
| s4 Slots-first + schema/ID | 32/44 | 21/44 | 1 |
| s5 Temperature 0,3 | 34/42 | 23/42 | 3 |

| Số ca lỗi máy gán, có chồng lặp | s0 | s1 | s2 | s3 | s4 | s5 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Câu hỏi–fact | 8 | 10 | 12 | 15 | 9 | 3 |
| Không trả lời được từ bài nghe | 2 | 1 | 1 | 2 | 4 | 1 |
| Fact gộp ý | 2 | 6 | 3 | 1 | 1 | 1 |
| Dịch | 8 | 6 | 7 | 8 | 8 | 4 |
| Vai | 2 | 7 | 7 | 2 | 1 | 1 |
| Mạch lượt 2–3 | 15 | 18 | 19 | 20 | 6 | 6 |
| Chuyển giao lượt 4 | 5 | 8 | 8 | 3 | 4 | 5 |
| Độ khó (judge có lẫn lỗi số từ) | 0 | 0 | 4 | 6 | 5 | 5 |
| Input coherence (có nhãn gán sai đối tượng) | 1 | 0 | 0 | 0 | 1 | 0 |
| Số từ ngoài khoảng, kiểm bằng code | 9 | 15 | 13 | 15 | 8 | 17 |
| Evidence không substring | 0 | 1 | 1 | 0 | 1 | 1 |
| ID sai | 0 | 1 | 1 | 1 | 0 | 0 |
| Số fact sai level | 0 | 0 | 3 | 2 | 0 | 0 |
| Lặp nguyên văn lịch sử/ví dụ cũ | 0 | 1 | 0 | 0 | 0 | 0 |

Không cộng các hàng thành tổng ca lỗi vì một ca có thể nhiều nhãn.

Tín hiệu cải thiện rõ nhất trong đợt development là nhóm s4: 16 ca từ lỗi sang đạt, 5 ca thoái lui so với s3 trong 43 cặp chấm được. Giảm temperature s5 chỉ có 8 cải thiện/7 thoái lui trong 41 cặp; chưa đủ kết luận thắng rõ, đồng thời lỗi số từ tăng 8→17. Giữ code s5 trên nhánh thử nghiệm để nghiên cứu tiếp, không coi đây là bản production đã nghiệm thu.

## Thiết kế

- 15 input tổng hợp đóng băng: 5 tình huống `cafe/hotel/directions/plans/delivery` × 3 mức `A1/A2/B1`. Phân bổ vòng lượt 2–4: mỗi lượt có 5 ca; có 2 ca lượt 4 kèm 10 phiên gần đây. Đây là bộ development/regression, không phải test độc lập chưa từng dùng để chỉnh thiết kế.
- Mỗi input chạy seed `17`, `43`, `97`: 45 output mỗi phiên bản. Seed không bảo đảm tái lập tuyệt đối giữa các phiên bản runtime/phần cứng.
- Qwen3.5:9b local, GGUF Q4_K_M, `think=false`, `num_ctx=8192`, `num_predict=1000`, timeout HTTP 600 giây. Giữ nguyên model/digest và các tham số ngoài yếu tố đang thử.
- Chỉ lấy phản hồi đầu tiên. Không backend validation, semantic reviewer production, repair, hay chọn lại output theo chất lượng. Lỗi API/JSON/truncation vẫn được lưu và tính riêng. Resume không thay thế bản ghi thất bại đã lưu.
- Request được xuất từ chính `ConversationLocalClient.requestBody`, không dựng lại prompt production bằng Python. Fixture legacy giữ nguyên; production mới có thêm question/speaker/slot metadata nhưng không giả tạo các trường thiếu trong fixture cũ.
- Baseline giữ nguyên prompt và schema cũ. Đã cố định serialization của few-shot ở các bản sau để khớp baseline, tránh thứ tự `Map.of` ngẫu nhiên giữa JVM làm thay đổi token đầu vào. Bản xuất nháp `s1-roles` không chạy, không tính kết quả; dùng `s1-roles-stable`.
- Đối chiếu đủ 270 key duy nhất theo từng bản, mỗi seed 15 ca, cùng 15 input và hash request theo nội dung. Collector đã chạy trước khi bổ sung `requestWireHash`, nên 270 bản ghi chưa có hash nhạy thứ tự byte/property; không bổ sung hash hồi tố như bằng chứng đã gửi. Giữ request JSON đóng băng và kiểm schema order khi Java export; collector hiện tại lưu thêm wire hash cho các đợt sau.

| Bản | Thay đổi cộng dồn so với bản trước |
| --- | --- |
| s0-baseline | Prompt/schema production cũ, temperature 0.7 |
| s1-roles-stable | Vai theo nhánh; câu hỏi theo vai; production lưu speaker/question trong history |
| s2-turn-brief | Brief riêng; lượt 4 bỏ transcript/journey cũ, giữ giá trị tránh và kỹ năng yếu |
| s3-matched-examples | Một few-shot đúng mức/lượt, có history khi tiếp diễn; ví dụ làm gốm; bỏ hai ví dụ vá lỗi trong quy tắc |
| s4-slots | Slots-first; code gán ID; A1 đúng hai slot/fact; mục tiêu số từ cụ thể; metadata nội bộ không đổi public fact contract |
| s5-temperature | Chỉ giảm temperature từ 0.7 xuống 0.3 |

Mỗi hàng là một nhóm thay đổi có liên quan, không thể quy chênh lệch riêng cho từng dòng code trong nhóm. Chưa tách dịch thành call thứ hai, chưa fine-tune, không đổi prompt chấm câu trả lời của người học hoặc semantic reviewer production.

## Phân loại lỗi

1. Transport/generation: không kết thúc, timeout, JSON không parse được, slot/fact không ghép được.
2. Cấu trúc: trường/type, số fact theo level, ID, skill, giới hạn ký tự.
3. Độ dài: số từ ngoài profile; báo riêng, không coi tự động là sai nội dung.
4. Grounding: evidence không là substring.
5. Câu hỏi–fact: thiếu, thừa hoặc đáp án sai so với ý được hỏi.
6. Answerability: bài nghe không cung cấp câu trả lời.
7. Atomicity: một fact gộp ý không được hỏi.
8. Dịch: bản dịch bài hoặc fact sai mã, số, hướng, vai, phủ định, điều kiện hoặc nghĩa.
9. Vai; mạch lượt 2–3; chuyển giao lượt 4; độ khó ngôn ngữ; input không nhất quán.

Các loại lỗi có thể chồng lặp. Tách lỗi judge/API khỏi lỗi model. Báo cả tổng 45, theo từng seed 15 ca, và cặp ca chuyển pass/fail giữa hai bản. Ba seed không biến 15 tình huống thành 45 tình huống độc lập; không khẳng định ý nghĩa thống kê từ thay đổi nhỏ.

Tách thêm `contentPass` (câu hỏi–fact, answerability, atomicity, dịch, vai) và `learningPass` (mạch, chuyển giao, độ khó). Một output đúng đáp án nhưng hỏi lại ý cũ không được mô tả là sai dữ kiện. `semanticPass` vẫn là đạt mọi tiêu chí judge; `schemaGroundingPass` không tính lỗi số từ, còn `hardPass` tính mọi hard check. Đây là tổng hợp lại cùng nhãn cố định, không chấm lại hoặc đổi rubric.

Thời gian chạy chỉ mang tính mô tả: baseline có tải model lần đầu và có lúc chạy build/test cùng máy. Không dùng chênh lệch latency giữa các bản để kết luận tốc độ thuần của prompt. Giữ token counts và timings trong envelope để điều tra chi phí.

## Judge offline

`listening-ablation-v6-context-rules`, Claude Sonnet 4.5 qua ShopAIKey; temperature 0. Lần gọi đầu chỉ thấy `utteranceEn/questionVi`, tự trả lời và trích evidence; không thấy facts, translation hay history của ứng viên. Lần gọi sau đối chiếu ứng viên, câu trả lời độc lập và input gốc. Câu trả lời độc lập vẫn có thể sai, không được coi là gold.

Rubric phân biệt rõ: continuity chỉ áp dụng lượt 2–3; lượt 4 được đổi cuộc gặp trong cùng nhóm tình huống và dùng lại skill/loại slot với đáp án mới. Chỉ bác lỗi có bằng chứng cụ thể. Kiểm dịch trực tiếp, không dùng dịch ngược làm chuẩn.

Đã qua 6 ca kiểm chuẩn soạn sẵn: mở rộng khía cạnh trong cùng cuộc gặp; câu hỏi không có đáp án; lượt 4 đổi cuộc gặp hợp lệ; lượt 4 tiếp tục đơn cũ; dịch sai mã chữ cái/hướng đi; hỏi lại ý cũ dù đáp án vẫn đúng. Đây không phải gold do người thật duyệt và không ước lượng độ chính xác toàn bộ judge. `humanReviewed=0`.

Trước khi chấm so sánh, judge v4 vẫn cho qua `eval-hotel-A1-t3:17` hỏi lại giờ/địa điểm ăn sáng đã học. V5 thêm history audit nhưng tự thêm issue continuity cho lỗi transfer dù cờ continuity=true. Hai lần chấm ca chuẩn lỗi cấu trúc không được chọn làm verdict hợp lệ. V6 chỉ gửi quy tắc encounter áp dụng cho lượt hiện tại, bắt buộc liệt kê ý đã hỏi/đang hỏi và lưu bằng chứng; cả 6 ca kiểm chuẩn qua. Giữ riêng các verdict v4, ca chuẩn v5 và raw chẩn đoán; không trộn vào điểm v6. Không tự sửa cờ của judge hoặc cho pass khi cờ và bằng chứng mâu thuẫn.

V6 vẫn không phải chuẩn tuyệt đối. Ví dụ `eval-plans-B1-t4:17` được cho pass dù câu hỏi chỉ hỏi lý do và fact thứ hai là địa điểm. Independent answer có một ý nhưng output có hai fact. Báo riêng `judgePassCountDisagreements` như tín hiệu cần xem lại; không tự đổi nhãn máy thành nhãn người hoặc coi mismatch số ý là bằng chứng tự động rằng ứng viên sai. Giữ phiên bản judge cố định trong toàn bộ so sánh, thay vì sửa tiêu chí sau mỗi output.

Trong bản sửa vai, judge còn cho qua `eval-cafe-A1-t2:17` dù câu hỏi lộ cả hai đáp án và dạng đúng/không, cùng `eval-cafe-A2-t3:43` có fact vị trí quầy ngoài câu hỏi số tiền/phương thức trả. Chiều ngược lại, lý do bác `eval-directions-A1-t4:43` diễn giải "bảo tàng mới" là tiếp tục bảo tàng cũ; đây là ca cần xem lại ranh giới chuyển giao, không đủ để tuyên bố cứ đổi bảo tàng là sai. Nhãn `inputCoherent` baseline có một ca thực chất phàn nàn nội dung ứng viên, không phải input; không dùng nhãn đó để tự loại fixture.

Ledger mới tách khỏi đợt distillation cũ, có trần 9 credit hiện có và không nạp tiền tự động. Trần ban đầu 4 credit được nâng lên 7 rồi 9 trước khi chạm giới hạn để chấm đủ sáu bản sau các lần kiểm chuẩn và chấm lại baseline; có xác nhận số dư hiện có đủ trần mới kèm khoản dự phòng. Không công bố số dư/key. Nếu judge bị giới hạn hoặc lỗi, vẫn giữ output local và ghi rõ ca chưa chấm; không tự cho pass. Dữ liệu cũ 50/500 ca không bị ghi đè hoặc trộn vào bộ mới.

## Chạy lại

```powershell
python scripts/test-conversation-ablation.py
mvn --batch-mode --no-transfer-progress '-Dtest=ConversationAblationTest' '-Dconversation.ablation=true' '-Dconversation.ablationStage=TEN-BAN-MOI' test
python scripts/conversation-ablation.py run --stage TEN-BAN-MOI
python scripts/conversation-ablation.py judge --stage TEN-BAN-MOI
python scripts/conversation-ablation.py report
```

Thay `TEN-BAN-MOI` bằng tên chữ thường/số/gạch nối, ví dụ `s6-next-change`. Dùng lại manifest 15 ca, ba seed và judge v6 đã đóng băng; không gọi `prepare/calibrate` lại sau mỗi sửa. Khởi tạo `prepare` chỉ dành cho một đợt mới có nguồn benchmark/distillation cũ trên máy; không phải lệnh tự tái tạo dữ liệu từ một checkout trống. Không dùng lại tên đã đóng băng cho request khác. `prepare` từ chối trộn input/checkpoint khác. `pipeline` chỉ dành cho sáu bản của đợt hiện tại: một collector local tuần tự và một judge cloud chấm output đã lưu; collector kế tiếp chỉ bắt đầu sau khi baseline đủ 45. Không chạy thêm collector/judge vào cùng ledger. Báo cáo nhận cả các bản mới đã có output, bỏ qua bản nháp chưa chạy.

Runtime/raw request, output, envelope, verdict, independent answer và manifest nằm ở `data/conversation-ablation-20261019/`, được Git bỏ qua. Code, test và báo cáo tổng hợp được đưa lên Git; không đưa key, ledger số dư hoặc dữ liệu runtime vào Git.

## Kết quả

Baseline đã đủ 45 output: 0 lỗi generation, 36 qua hard filter (9 lỗi số từ), 43 verdict hợp lệ và 2 lỗi judge tự mâu thuẫn. Judge chấp nhận ngữ nghĩa 19/43 ca chấm được; 12/45 qua cả hard filter và judge. Đây không phải 19 câu được người thật xác nhận đúng.

Theo nhóm tiêu chí: cả 45 qua schema/grounding; 26/43 verdict hợp lệ qua nhóm nội dung và 23/43 qua nhóm tiến trình học. Nhóm lỗi mạch không tự chứng minh đáp án sai; ngược lại qua schema không chứng minh câu hỏi/fact/dịch đúng.

Theo seed: 17 có 6/13 ca chấm được được chấp nhận (2 lỗi judge), 43 có 9/15, 97 có 4/15. Lỗi ngữ nghĩa máy gán: mạch lượt 2–3 15, câu hỏi–fact 8, dịch 8, chuyển giao 5, answerability 2, atomicity 2, vai 2, input coherence 1; có chồng lặp. Nhãn input coherence cần đọc bằng chứng, không tự kết luận fixture sai.

Rà bổ sung bằng trợ lý AI trên 9 key cố định, phủ cả 3 seed (đã xem baseline trước khi chốt mẫu, chưa xem output ứng viên mới): 1 accept, 8 reject. Đây là rà AI, không phải human review hay ước lượng tỷ lệ đúng toàn bộ bộ thử. Bất đồng rõ: `eval-plans-A2-t3:97` được judge cho pass nhưng thêm fact giày ngoài câu hỏi mang gì nếu mưa. Ca khách sạn A2 lượt 4 seed 17 được chấp nhận vì task xác định nhận phòng mới và hai ý đều được bài nghe cung cấp.

Toàn bộ sáu bản dưới đây đã hoàn tất. Giữ nguyên cả output lỗi và lỗi judge; không chọn lại ca theo chất lượng.

Bản `s1-roles-stable` đủ 45/45: 0 lỗi generation/judge, 26/45 qua nhóm nội dung, 19/45 qua nhóm tiến trình, 13/45 qua toàn bộ rubric. Theo seed 17/43/97: 4/4/5 ca qua toàn bộ. Trong 43 cặp có verdict baseline, 6 cải thiện và 13 thoái lui. Hard check: 15 lỗi số từ, 1 evidence không substring, 1 lặp nguyên văn và 1 ID sai; có chồng lặp. Nhãn máy: mạch 18, câu hỏi–fact 10, chuyển giao 8, vai 7, atomicity 6, dịch 6, answerability 1. Chưa thấy riêng sửa vai cải thiện tổng thể.

Rà bổ sung cùng 9 key ở bản sửa vai: 1 accept, 8 reject (không phải người thật). Cần thận trọng so sánh lỗi vai với baseline: baseline chưa gửi vai authored trong input judge, còn các bản sau có metadata này. Các số vai/toàn bộ rubric có phần khác mức thông tin của judge; không suy rằng tăng nhãn vai chứng minh riêng chất lượng model giảm. So sánh các lỗi câu hỏi, evidence, dịch và mạch vẫn được liệt kê riêng; đây là thử nghiệm development, không phải nghiên cứu nhân quả sạch mọi yếu tố.

Bản `s2-turn-brief` đủ 45 output: 0 lỗi generation, 43 verdict hợp lệ/2 lỗi judge; 24/43 qua nội dung, 14/43 qua tiến trình, 10/43 qua mọi tiêu chí. Seed 17/43/97: 4/14, 3/15, 3/14 qua mọi tiêu chí. So với s1: 4 cải thiện, 7 thoái lui/43 cặp. Lỗi máy: mạch 19, câu hỏi–fact 12, chuyển giao 8, vai 7, dịch 7, độ khó 4, atomicity 3, answerability 1. Hard: số từ 13, số fact 3, evidence 1, ID 1 (có chồng lặp). Rà AI cùng 9 mẫu: 1 accept/8 reject. Chưa có bằng chứng brief riêng cải thiện tổng thể.

Bản `s3-matched-examples` đủ 45 output: 0 lỗi generation, 44 verdict hợp lệ/1 lỗi judge; 23/44 qua nội dung, 21/44 qua tiến trình, 10/44 qua mọi tiêu chí. Seed 17/43/97: 2/15, 4/15, 4/14 qua mọi tiêu chí. So với s2: 6 cải thiện, 7 thoái lui/42 cặp. Lỗi máy: mạch 20, câu hỏi–fact 15, dịch 8, độ khó 6, chuyển giao 3, vai 2, answerability 2, atomicity 1. Hard: số từ 15, số fact 2, ID 1. Rà AI 9 mẫu: 1 accept/8 reject; một ca được judge chấp nhận nhưng dịch citrus thành cam. Chưa có bằng chứng few-shot riêng cải thiện tổng thể.

Bản `s4-slots` đủ 45 output: 0 lỗi generation, 44 verdict hợp lệ/1 lỗi judge; 32/44 qua nội dung, 29/44 qua tiến trình, 21/44 qua mọi tiêu chí. Seed 17/43/97: 7/14, 8/15, 6/15 qua mọi tiêu chí. So với s3: 16 cải thiện, 5 thoái lui/43 cặp. Lỗi máy: câu hỏi–fact 9, dịch 8, mạch 6, độ khó 5, answerability 4, chuyển giao 4, vai 1, atomicity 1, input coherence 1. Hard: số từ 8, evidence 1; không lỗi ID/số fact. Median 56,36 giây, 2.194 prompt token, 180 output token (không kết luận tốc độ thuần do nhiễu tải máy).

Rà AI s4 cùng 9 mẫu: 3 accept, 5 reject, 1 review. Ca cần xem lại có câu hỏi "tiếp theo đi đâu" mơ hồ điểm đến/hướng rẽ; judge còn tách máy móc một hành động kèm đích đến thành hai fact. Lỗi rõ còn lại gồm eight→bảy giờ tối, đổi chữ hoa mật khẩu, câu hỏi nhầm người nói/người nghe, chữ Trung trong câu hỏi và thêm lý do không được hỏi. Đây là cải thiện của cả nhóm slots-first + schema theo level + code gán ID + mục tiêu số từ cụ thể, không chứng minh riêng thứ tự trường tạo ra toàn bộ chênh lệch.

Kiểm lý do nhãn độ khó s4: cả 5 nhãn `levelAppropriate=false` chỉ dựa vào số từ dưới min, trái với rubric đã yêu cầu tách số từ khỏi đánh giá ngữ nghĩa. Một lý do còn ghi ngôn ngữ phù hợp nhưng vẫn bác do 14 từ. Không sửa verdict hoặc tự cho pass; ghi nhận đây là lỗi tuân thủ rubric của judge. Vì vậy ưu tiên `contentPass` và từng bằng chứng nội dung, không dùng `semanticPass/learningPass` như chất lượng chuẩn tuyệt đối.

Bản `s5-temperature` đủ 45 output: 0 lỗi generation, 42 verdict hợp lệ/3 lỗi judge; 34/42 qua nội dung, 26/42 qua tiến trình, 23/42 qua mọi tiêu chí. Seed 17/43/97: 9/14, 5/14, 9/14 qua mọi tiêu chí. So với s4: 8 cải thiện, 7 thoái lui/41 cặp. Lỗi máy: mạch 6, chuyển giao 5, độ khó 5, dịch 4, câu hỏi–fact 3, answerability 1, vai 1, atomicity 1. Hard: số từ 17, evidence 1; không lỗi ID/số fact. Có 44/45 qua schema/grounding, 27/45 qua toàn bộ hard check. Median 56,13 giây, 2.194 prompt token, 165 output token; không kết luận tốc độ thuần.

Rà AI s5 cùng 9 mẫu: 4 accept, 4 reject, 1 review. Lỗi rõ: `eval-cafe-B1-t4:43` dịch peanuts thành hạt điều và peanut oil thành dầu mè. Ca `eval-delivery-B1-t4:97` cần xem lại vì chủ thể thanh toán mơ hồ, không tự khẳng định sai. Tổng cộng rà bổ sung 54 quyết định AI (9 key × 6 bản), không có nhãn người thật. Giảm temperature chưa xử lý hết lỗi nghĩa/dịch và chưa thắng rõ s4; không đưa bản hiện tại lên production hoặc dùng output chưa duyệt làm dữ liệu fine-tuning sạch.

## Kiểm tra code

- `mvn --batch-mode --no-transfer-progress -Pci-unit test`: 133 ca được phát hiện, 121 đạt, 12 opt-in/integration bỏ qua theo cấu hình; không fail/error. Bao gồm 8 test prompt và 7 test local client.
- Hai lớp integration trên H2: 66 ca được phát hiện, 65 đạt, 1 ca opt-in bỏ qua; không fail/error. Có kiểm metadata slot/speaker được lưu nội bộ, không lộ ra public response, và ID do code gán vẫn dùng được khi chấm bài.
- Hai script test offline: 12 test ablation và 18 test distillation đạt. Kiểm tra mới xác nhận báo cáo tách lỗi độ dài khỏi schema/grounding, tách lỗi tiến trình khỏi sai nội dung, giữ lỗi generation, không bỏ sót loại hard error, và nhận bản thử tiếp theo.
- Opt-in Java đối chiếu normalization trên cả 90 output s4/s5 và kiểm request hiện tại khớp bản s5 đã đóng băng. Các sửa visibility/test/report sau benchmark không đổi prompt hoặc request đã đo.
- Kiểm tra UTF-8 đạt cho 12 file của đợt sửa. Chưa kiểm tích hợp PostgreSQL/production; không dùng test code thay cho kiểm nội dung model.
