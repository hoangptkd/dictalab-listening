# Distillation hội thoại: dữ liệu nghiên cứu, chưa huấn luyện

## Mục tiêu và phạm vi

Giữ nguyên prompt production V2, thứ tự schema, reviewer và validation backend. Tạo baseline 50 ca bằng `qwen3.5:9b`, sau đó thu 500 output teacher và lọc **offline** để chuẩn bị supervised fine-tuning. Không fine-tune, không deploy, không dùng dữ liệu thật của người học trong đợt này.

Đây là response distillation: học từ output tổng hợp của teacher; không phải chuyển trọng số, logits hay chain-of-thought. Chưa có cơ sở nói 500 mẫu đủ hoặc 2–3 nghìn sẽ tốt hơn. Chỉ tăng dữ liệu sau một lần fine-tune nhỏ và đánh giá trên tập giữ lại.

## Model đã kiểm tra thực tế

- Student/baseline: Ollama `qwen3.5:9b`, CPU, context 8192, timeout 600 giây, một request đồng thời, không repair và không chạy validation backend.
- Checkpoint cục bộ: 9.0B GGUF `Q4_K_M`, digest `56671c2ab9385f9cfcb404638e32cd62d88e3501d44822208363c010179a3c90`. Máy AMD Ryzen 7 PRO 6850HS, 8 nhân/16 luồng. Context hỗ trợ trong metadata model không phải context thực chạy: batch này giới hạn 8192.
- Teacher: ShopAIKey `deepseek-v3.2`, tắt thinking. Qwen3.5 397B/122B có trong danh sách nhưng probe hàng loạt trả `503 No available channel ... under group cheap`; không tiếp tục dùng model chỉ vì nó xuất hiện trong `/v1/models`.
- Judge: ShopAIKey `claude-sonnet-4-5`, tách khỏi teacher. Đây là judge offline riêng, không thay prompt đánh giá production. Judge vẫn có thể sai; kết quả là nhãn máy, không phải chứng nhận của con người.
- Judge phiên bản v3 được hiệu chỉnh bằng chín mẫu có nhãn định trước: tiếp nối hợp lệ với slot mới; câu hỏi lệch fact; dịch sai phủ định; fact thêm lý do không được hỏi; lặp loại slot; sai tình huống; giờ không ghi sáng/tối nhưng đáp án cũng không thêm; ngữ cảnh ghi rõ buổi chiều; đáp án tự thêm buổi sáng. Judge hiện tại đạt 9/9 trên bộ nhỏ này, không phải phép đo tổng quát về độ chính xác judge.
- Pilot v2 từng bắt sai giờ không ghi AM/PM và hiểu sai câu hỏi vị trí so với hướng rẽ. Đã dừng batch để sửa tiêu chí, thêm ca hiệu chỉnh và chấm lại output cũ qua `teacher-refilter.jsonl`; không ghi đè nhãn máy cũ. Chỉ verdict phiên bản hiện hành được xuất vào tập provisional.

## Định dạng input và thiết kế thử nghiệm

`ConversationDistillationTest#exportProductionContract` gọi chính `ConversationService.promptContext` trên trạng thái tổng hợp; xuất catalogue 198 context, schema và prompt/few-shot hiện hành. Không tự đoán cấu trúc input và không đọc database người học.

Baseline cố định trước khi chạy: 5 tình huống (`cafe`, `hotel`, `directions`, `plans`, `delivery`) × 3 level × lượt 2/3/4 = 45; thêm 5 ca lượt 4 có 10 phiên luyện gần đây. Câu 1 hiện dùng nội dung curated nên không giả lập gọi LLM ở lượt đó. Lịch sử lượt 2–3 là fixture song ngữ riêng cho từng tình huống; không dùng lịch sử khách sạn cho mọi tình huống. Đây là bộ tập trung rủi ro, **không đại diện đủ cả 24 tình huống**.

Mỗi ca local chỉ lấy output đầu tiên, lưu cả lỗi và thời gian. Input có SHA256, không tái sinh khi resume. Test Java chỉ xác nhận đủ bản ghi, không coi thu đủ 50 ca là nội dung đạt 50/50. Cùng 50 input có thể chạy teacher tham chiếu, nhưng reference không được đưa vào train.

Local dùng constrained JSON schema của Ollama, temperature `0.7`, `num_predict=1000` theo production; teacher qua API dùng `response_format=json_object`, temperature `0`, `max_tokens=2200`, không ràng buộc schema khi decode. Cả hai tắt thinking; judge dùng trần 1400 token. Prompt/few-shot và input giống nhau nhưng serving/decoding khác nhau: đây không phải thí nghiệm kiểm soát để quy mọi chênh lệch cho năng lực model. Teacher có thể thêm các trường ghi chú ngoài schema; bộ lọc giữ nguyên raw và loại chúng, không âm thầm xóa trường để nâng tỷ lệ pass. Ưu tiên đọc riêng chất lượng ngữ nghĩa, không suy từ tỷ lệ JSON đúng sang nội dung đúng.

Catalogue teacher phủ 24 tình huống, cả A1/A2/B1 và lượt 2–4. Giữ lại toàn bộ nhóm `scenario/branch/level` thuộc benchmark, không cho các lượt cùng nhánh/level rơi vào train. Tăng tần suất qua `practiceFocus` cho lý do, hành động, 2–3 slot và chuyển giao lượt 4; khoảng một phần tư kế hoạch session có 10 phiên gần đây. Đây là mục tiêu lấy mẫu; báo cáo coverage thực tế mới cho biết đã đạt hay chưa.

Teacher tạo theo chuỗi: chỉ output qua cả hard filter và judge mới được nối làm lịch sử cho lượt sau. Output không đạt và lỗi API vẫn được lưu; không âm thầm sửa hoặc chọn lại cùng ca cho đến khi pass. Vì vậy số ca từng lượt sau lọc có thể mất cân đối. 500 là mục tiêu **output thu thập**, không hứa có 500 mẫu sạch.

## Bộ lọc offline

1. Kiểm tra field/type/độ dài, số fact theo level, ID liên tiếp, skill thuộc danh sách, word bounds, `evidenceEn` là substring chính xác; chặn chép nguyên history/few-shot.
2. Judge kiểm tra câu hỏi ↔ từng fact, trả lời được từ bài nghe, fact một slot, bản dịch đầy đủ, mạch hội thoại, độ khó và input có nhất quán không. Mọi cờ phải true và `issues` phải rỗng mới đủ điều kiện.
3. Loại output trùng hoàn toàn sau chuẩn hóa và input/nhóm trùng benchmark. Chia train/validation/test theo hash **nhóm hội thoại**, không chia ngẫu nhiên từng lượt.
4. Xuất `*.provisional.jsonl` theo messages system/user/assistant, giữ few-shot production và thứ tự output cũ. Metadata provenance nằm ngoài messages. `targetMessageIndex=4` và `lossMaskPolicy=last-assistant-only`: trainer sau này phải mask cả system/user **và assistant few-shot**, chỉ tính loss trên assistant cuối. Đây là metadata yêu cầu, không tự cấu hình loss cho bất kỳ trainer nào. Chỉ chọn cột messages và áp dụng chat template/masking tương ứng checkpoint khi tích hợp SFT. Đây chưa phải dữ liệu được duyệt để huấn luyện.
5. Tạo hàng đợi người duyệt 15–20% mẫu sau lọc, kèm CSV quyết định và HTML có input/output. Không điền giả danh tính người duyệt. Nếu phát hiện một kiểu lỗi trong mẫu duyệt, cần rà lại cả nhóm lỗi trước khi đóng băng bản train.

Soi bổ sung bằng trợ lý AI phát hiện `teacher-c0-directions-busstop-A2-t2` có ba fact cho hai slot, trong đó hai fact cùng mô tả một điểm xuống xe, dù judge v3 cho qua. Lưu loại bỏ tại `assistant-audit.jsonl` kèm hash input/output; loại cả các lượt có lịch sử phụ thuộc mẫu đó khi export. Đây không phải nhãn người duyệt và không được tính vào tỷ lệ human review.

Soi ngẫu nhiên tiếp 10 mẫu đang được judge cho qua (seed `20261019`, tại giai đoạn thu khoảng 200 output) phát hiện thêm hai lỗi rõ: `teacher-c0-wrong-order-standard-B1-t3` có fact là điều kiện thời gian đã nêu sẵn trong câu hỏi; `teacher-c0-product-standard-A1-t2` hỏi lý do màu sắc nhưng bài nghe giải thích giảm giá/bán nhanh. Loại thêm `teacher-c0-opening-hours-holiday-A2-t3` theo hướng bảo thủ vì giờ hoạt động quầy mâu thuẫn giờ đóng cửa hàng trong history mà không giải thích. Các loại bỏ đều lưu riêng, không ghi đè verdict judge. Kiểm tra nhỏ này cho thấy judge vẫn có false acceptance; chưa thể gọi tập provisional là dữ liệu đúng đã được chứng nhận.

Soi tiếp năm mẫu sau khoảng 300 output (seed `20261020`, chọn từ phần sau 200 dòng candidate) loại thêm `teacher-c0-change-trip-standard-A2-t3`: tự đổi phí ba bảng thành bốn bảng và hỏi lại slot phí; `teacher-c0-clothes-fitting-A2-t4`: hỏi người nào nhưng fact kèm hành động/người đi cùng không được hỏi. Đây vẫn là rà soát bằng trợ lý AI, không được tính vào human review và không dùng để ước lượng tỷ lệ lỗi toàn bộ dữ liệu.

Baseline được judge trên mọi output có nội dung, kể cả output trượt hard filter, để tách lỗi cấu trúc khỏi lỗi ngữ nghĩa. Kết quả máy cần báo theo từng tiêu chí, không gộp thành một “điểm AI” duy nhất.

False acceptance cũng có ở baseline: judge v3 cho `eval-cafe-B1-t4` pass dù bài nghe tiếp tục đổi bánh mì kẹp phô mai và mang trà của giao dịch cũ, không phải encounter mới. Giữ nguyên verdict máy để so cùng tiêu chí, ghi nhận bất đồng tại báo cáo thay vì sửa nhãn âm thầm. Vì vậy semantic-pass của baseline và teacher đều không phải tỷ lệ đúng nội dung đã được con người xác nhận.

Judge còn nhầm phân loại: ở `eval-cafe-A1-t3`, nó đặt `inputCoherent=false` vì output đề cập taxi, không phải vì history đầu vào có taxi. Ca này thực sự có lỗi đáp án/bản dịch taxi → xe buýt, nhưng không thể lấy riêng cờ đó để kết luận fixture đầu vào sai. Các số đếm từng tiêu chí bên dưới là nhãn máy, cần đọc kèm `issues` và raw input/output.

`benchmark-comparison.jsonl` ghép theo ID và hash input: input, output student/thời gian, verdict offline của student, output teacher tham chiếu/verdict. Tệp này giúp đọc hoặc đưa cho công cụ phân tích so sánh mà không phải nối thủ công các tệp thô. Không lấy output teacher tham chiếu vào train.

## Lưu trữ, giới hạn và chạy lại

Code ở `scripts/conversation-distill.py`, kiểm tra không mạng ở `scripts/test-conversation-distill.py`. Runtime tại `data/conversation-distillation/` được Git bỏ qua. `finalize` tạo bản sao vào `Documents/DictaLab-research-backup/conversation-distillation/` trên máy HP; đây là sao lưu cục bộ, không phải backup khác ổ đĩa hoặc cloud.

Key chỉ đọc từ environment hoặc `.env` được bỏ qua; không truyền key trên dòng lệnh, không ghi vào dữ liệu hay Git. Endpoint HTTPS cố định, không theo redirect. Theo tài liệu provider, kiểm tra credit dùng endpoint `/usage` có key trong query, chỉ tạo URL trong bộ nhớ và không log URL/phản hồi thô.

Mỗi call có HTTP timeout, giới hạn response/token, ghi reservation trước request và usage sau response; tối đa 1800 request hoặc 12 triệu token reservation ước lượng bảo thủ theo byte. Sau pilot, điều chỉnh trần thăm dò 5 → 8 → 12 credit hiện có theo mức giảm credit quan sát được, để hoàn tất 500 output và các phép so sánh, bao gồm các lượt refilter và hiệu chỉnh judge. Kiểm tra mỗi 10 request; đây không phải báo giá USD/VND chính xác và có thể vượt nhẹ bởi các request giữa hai lần đo hoặc hoạt động khác trên cùng key. Mức giảm credit toàn tài khoản không thể quy hoàn toàn cho riêng job nếu có hoạt động khác. Không tự nạp tiền. Ba lỗi provider liên tiếp ở job generate sẽ dừng job để kiểm tra, không retry vô hạn hoặc tự đổi model.

```powershell
mvn --batch-mode --no-transfer-progress '-Dtest=ConversationDistillationTest#exportProductionContract' '-Dconversation.distillation=true' test
python scripts/conversation-distill.py prepare
python scripts/conversation-distill.py calibrate
python scripts/conversation-distill.py refilter
mvn --batch-mode --no-transfer-progress '-Dtest=ConversationDistillationTest#collectFiftyFirstOutputs' '-Dconversation.distillation=true' '-Dconversation.benchmark50=true' '-DforkCount=0' test
python scripts/conversation-distill.py generate --count 500
python scripts/conversation-distill.py teacher-benchmark
python scripts/conversation-distill.py judge-baseline
python scripts/conversation-distill.py finalize
python scripts/conversation-distill.py audit
python scripts/test-conversation-distill.py
```

Không chạy đồng thời nhiều tiến trình cloud trên cùng thư mục ledger. Có thể chạy baseline Ollama song song một job cloud. Các job resume giữ kết quả lỗi đã thu; muốn thử lại model/judge khác phải dùng phiên bản thư mục/kết quả riêng, không ghi đè baseline.

`forkCount=0` chỉ dành cho batch offline dài: cấu hình Surefire hiện giới hạn fork 600 giây tổng cộng, không đủ chạy 50 lần inference trên CPU. Lần chạy đầu dừng sau 12 bản ghi và đã resume từ bản ghi 13; không phải model timeout, không thay trần timeout của bộ unit test hoặc backend. Lượt đang dở khi runner ngắt chưa có output lưu và được gọi lại khi resume; 50 bản ghi không có nghĩa chỉ có đúng 50 HTTP request tổng cộng. Không có retry theo chất lượng nội dung.

## Cổng trước fine-tuning

Cần người thật duyệt hàng đợi, xử lý lỗi theo nhóm, kiểm tra dedup gần nghĩa/độ phủ, xác nhận quyền sử dụng dữ liệu từ nhà cung cấp, rồi đóng băng manifest và train split. Giấy phép [DeepSeek V3.2](https://huggingface.co/deepseek-ai/DeepSeek-V3.2/blob/main/LICENSE) không thay thế điều khoản dịch vụ của reseller. Chỉ output teacher được dùng làm target; không huấn luyện vào reasoning hoặc lời phê bình của judge.

Sau đó thử LoRA/QLoRA nhỏ, giữ nguyên 50 input và cùng bộ judge để so baseline → model fine-tuned; báo cả tỷ lệ đúng cấu trúc, grounding, câu hỏi/fact, dịch, mạch và latency. Không dùng test set để chọn hyperparameter hoặc tự thêm đáp án test vào train. Chỉ tăng dữ liệu khi validation cải thiện và test không bị dùng lặp làm mục tiêu tối ưu.

Nguồn API: [ShopAIKey chat format](https://shopaikey.com/en/docs/openai-format), [DeepSeek thinking mode](https://api-docs.deepseek.com/guides/thinking_mode/).

## Kết quả chạy

Đã hoàn tất 50 output local, 500 output teacher cho dữ liệu và 50 output teacher tham chiếu. Toàn bộ 50 output local và 50 reference có verdict của cùng judge v3. Không chạy lại output local theo chất lượng.

| Chỉ số trên cùng 50 input | Qwen3.5 9B local | DeepSeek V3.2 reference |
| --- | ---: | ---: |
| Có output | 50/50 | 50/50 |
| Qua bộ lọc cứng offline | 36/50 (72%) | 42/50 (84%) |
| Được judge cho qua ngữ nghĩa | 12/50 (24%) | 43/50 (86%) |
| Qua cả hai | 8/50 (16%) | 36/50 (72%) |

Đây là tỷ lệ **nhãn máy**, không phải số câu đúng đã được con người xác nhận. Không suy ra model fine-tuned sẽ đạt tỷ lệ của teacher. Khác biệt decoding/serving đã nêu ở trên cũng ngăn quy mọi chênh lệch cho riêng kích thước model.

Local: thời gian trung bình 49,30 giây, trung vị 48,06 giây, tối đa 76,43 giây. Trong 50 bản ghi đã hoàn tất, không có generation failure/timeout; lỗi runner tổng 600 giây và việc resume được mô tả riêng ở trên. Bộ lọc cứng bắt 14 ca ngoài khoảng số từ và một ca sai ID fact, có chồng lặp. Judge đánh dấu 26 ca lệch câu hỏi/fact, 27 ca sai mạch, tám ca dịch, sáu ca fact không nguyên tử và hai ca độ khó; một cờ `inputCoherent` bị phân loại nhầm như đã ghi. Các tiêu chí có thể trùng trên cùng ca. Trong bộ thử này, nút thắt không chỉ là timeout mà là tuân thủ nội dung và mạch hội thoại.

Dữ liệu distillation đã thu:

- 505 bản ghi candidate: 500 có output JSON đã parse; năm bản ghi không có output. Các lỗi và usage được giữ lại, không đưa vào target.
- 500 output → 430 qua hard filter → 333 được judge cho qua → loại sáu mẫu bằng rà soát AI bổ sung, còn 327 → loại thêm ba lượt phụ thuộc lịch sử bị bác, còn **324 mẫu provisional**. Không tìm thấy utterance trùng hoàn toàn sau chuẩn hóa; chưa kiểm dedup gần nghĩa.
- Hard filter loại 52 ca thêm/sai trường ngoài schema, 11 ca sai số fact, bảy ca số từ. Trong các ca được judge, các cờ sai gồm câu hỏi/fact 74, mạch 39, nguyên tử 24, dịch sáu, trả lời được một; có chồng lặp.
- Chia theo nhóm hội thoại: **train 267, validation 15, test 42**. Validation còn nhỏ; chưa dùng để kết luận lợi ích của fine-tuning. Không có nhóm trùng giữa các split hoặc với nhóm benchmark được giữ lại.
- A1/A2/B1: 109/117/98. Lượt 2/3/4: 156/105/63. Có 72 mẫu lịch sử gần đây dài. Phủ cả 24 tình huống nhưng chỉ **155/216 ô level × tình huống × lượt** trên toàn bộ tập sau lọc; không phải đủ độ phủ ở riêng train.
- Skill thực tế: hướng dẫn 138, thông tin cụ thể 74, điều kiện 82, lý do/mục đích 22, thay đổi tám; chưa có gist. Tần suất focus trong input không đồng nghĩa model đã tạo đúng số mẫu skill mong muốn.
- Hàng đợi người duyệt: **65 mẫu, khoảng 20%**. `humanReviewed=0`, `trainingReady=false`. CSV quyết định giữ một dòng cũ ngoài hàng đợi để không mất ghi chú; dùng ID trong HTML/JSONL hàng đợi hiện hành để duyệt.

Mức giảm credit được kiểm tra và vẫn dưới trần 12; chi tiết chỉ lưu tại `summary.json` cục bộ. Đây là biến động số dư toàn tài khoản từ mốc đo, không phải hóa đơn chính xác riêng job. Tổng API usage do provider báo: 2.267.872 prompt token và 326.418 completion token; ledger có 1.135 reservation, không coi reservation là số request thành công hoặc số token thực tế.

Các tệp để đọc và tiếp tục:

- `data/conversation-distillation/benchmark-comparison.jsonl`: 50 input, output local/teacher và verdict tương ứng.
- `data/conversation-distillation/summary.json`, `coverage.csv`, `integrity-audit.json`: số liệu, ô thiếu và kiểm tra toàn vẹn.
- `data/conversation-distillation/{train,validation,test}.provisional.jsonl`: target SFT chưa được người duyệt.
- `data/conversation-distillation/human-review.html`, `human-review-queue.jsonl`, `human-review-decisions.csv`: giao diện và phiếu duyệt.
- Bản sao tại `C:/Users/HP/Documents/DictaLab-research-backup/conversation-distillation/`: 26 tệp, đối chiếu hash khớp từng tệp với nguồn. Không phải backup khác ổ đĩa/cloud.

Kiểm tra: 18 Python unit test, Python compile, UTF-8 của bốn tệp và `git diff --check` đều qua; Maven profile `ci-unit` đã chạy thành công (125 discovered, 115 passed, 10 opt-in skipped). Test export production contract và test thu 50 output đã chạy riêng thành công. Audit dữ liệu xác nhận đủ số bản ghi, hash input, thứ tự target, cấu trúc messages và không rò nhóm/trùng utterance giữa các split; không chứng nhận nội dung đúng.

Ưu tiên tiếp theo: người thật duyệt 65 mẫu; dùng bất đồng để cải thiện/đo lại judge bằng tập gold độc lập; rà toàn bộ nhóm lỗi phát hiện; bổ sung các ô thiếu, lượt 4, lý do/thay đổi và tình huống ít mẫu trước khi tăng số lượng đều. Sau đó mới đóng băng bản được duyệt và thử LoRA/QLoRA. Đợt này không fine-tune, không sửa backend/prompt production, không deploy.

Soi nội dung bổ sung bằng trợ lý AI trên 15 ca local (A1 lượt 2, A2 lượt 3, B1 lượt 4 của cả 5 tình huống), **không phải người duyệt**:

- `eval-cafe-A1-t2`, `eval-hotel-A1-t2`, `eval-plans-A1-t2`, `eval-plans-A2-t3`: câu hỏi một slot nhưng thêm fact không được hỏi; đủ số fact không đồng nghĩa đúng nội dung.
- `eval-hotel-A2-t3`, `eval-delivery-A2-t3`: thay đổi sắp xếp đã chốt trong lịch sử mà không thông báo thay đổi; ca giao hàng còn hỏi lại các slot cũ.
- `eval-hotel-B1-t4`: câu hỏi đề cập một dịch vụ nhưng lý do trong bài nghe thuộc dịch vụ khác.
- `eval-directions-B1-t4`: câu hỏi chỉ hỏi lý do nhưng có fact là địa điểm đã nêu trong câu hỏi.
- `eval-cafe-B1-t4`, `eval-plans-B1-t4`: tiếp tục sửa giao dịch/cuộc hẹn cũ thay vì một encounter mới theo yêu cầu lượt 4.

Đây là ví dụ lỗi có bằng chứng trong raw output, không phải tỷ lệ đánh giá toàn bộ 50 ca; không thay thế hàng đợi người duyệt dữ liệu distillation.
