# Chẩn đoán luồng inference full07 — 26/10/2026

## Kết luận

Đã hoàn tất tám cặp prompt ngắn và ba lượt control, lưu đủ **19 đầu ra**, không đầu ra nào chạm giới hạn token. Các kiểm tra số học không phát hiện base bị ảnh hưởng khi tắt LoRA; adapter được nạp đúng và có tác động lên logit. Kiểm tra cache trên một câu dịch cũng không làm thay đổi chuỗi token sinh ra.

Lỗi dịch Wednesday thành **“Thứ Ba” xuất hiện ngay ở base gốc chưa gắn adapter**, và tiếp tục xuất hiện khi bật adapter. Cả hai còn tự chọn “anh” dù prompt yêu cầu giữ tuổi chưa rõ. Vì vậy, chưa có căn cứ coi các lỗi này là do gắn nhầm LoRA hoặc chỉ do full07 gây ra. Các phép đo này giới hạn ở môi trường CPU hiện có; chưa xác nhận toàn bộ backend hoặc nguyên nhân cuối cùng.

Giữ quyết định chưa chọn prompt v2 và chưa triển khai model. Local training vẫn `PAUSED_BY_USER`; không mở dữ liệu train/validation/test, không huấn luyện lại, không cài dependency hoặc đổi cấu hình sản phẩm.

## Phạm vi và phương pháp

Tiếp nối hai báo cáo `full07-training-verification-backup-20261026.md` và `full07-inference-prompt-followup-20261026.md`, lần này dùng tám prompt tự soạn ngắn, cùng một system prompt đơn giản. Không ghép teacher instruction hoặc schema nội dung từng có mâu thuẫn, không chèn đáp án của từng câu vào system prompt. Riêng `mapping-copy` cố ý cung cấp ánh xạ để đo khả năng dùng thông tin đã cho.

- Base: cache local `Qwen/Qwen3.5-4B`, revision `851bf6e806efd8d0a36b00ddf55e13ccb7b8cd0a`.
- Dùng lớp text-only `Qwen3_5ForCausalLM` có trong Transformers đã cài; không chạy nhánh hình ảnh của checkpoint multimodal. Loading info không báo missing/mismatched/error keys.
- Adapter: SHA-256 `1670910dcad7f1e8f66d1dd74e6297fe914c2067d0c26240b72b25fd36c922bf`.
- Windows CPU, FP32, torch `2.10.0+cpu`, transformers `5.20.2`, peft `0.21.2`, safetensors `0.8.0`; seed17, greedy, `eval()` và `inference_mode()`, không quantization.
- Tất cả tham số đóng băng; không có optimizer. Offline, `local_files_only=True`; effective tokenizer backend và chat template của cache/checkpoint khớp.
- Tám nhánh base được chạy **trước khi gắn adapter**; sau đó mới gắn adapter, kiểm tra khi tắt và bật. Không dùng cùng một nhánh PEFT-disabled để tự nhận đó là bằng chứng base nguyên gốc.
- Từng prompt cùng giới hạn đầu ra cho hai nhánh; đầu vào cộng giới hạn đầu ra không vượt 4.608 token, không truncate.
- Lượt hoàn tất lúc `2026-10-26T09:01:06Z` (16:01 UTC+7); không còn tiến trình chẩn đoán sau khi kết thúc.

## Kiểm tra tính toàn vẹn và luồng tính toán

| Kiểm tra | Bằng chứng thực tế |
|---|---|
| Cache trọng số local | Hai shard, header/key/index/data offsets và tổng byte tensor khớp; đã ghi SHA-256 của từng shard |
| Giới hạn xác minh trọng số | Chưa đối chiếu digest của nhà phát hành từ xa; SHA local không tự chứng minh nguồn chính thức |
| Adapter sau nạp | Đủ 64 tensor khớp chính xác với safetensors đã lưu; 32 LoRA layers; mọi tham số không nhận gradient |
| Tắt LoRA so với base nguyên gốc | Logit token đầu khớp tuyệt đối trên cả 8 prompt: max abs delta đều `0.0` |
| Sinh khi tắt LoRA | Cả hai control `english-next` và `weekday-vi` khớp từng token với base nguyên gốc |
| Bật LoRA | Logit token đầu đổi trên cả 8 prompt, max abs delta mỗi prompt từ `8.149991989135742` đến `10.267924308776855` |
| Tắt lại sau khi chạy adapter | Logit của control `english-next` khớp base nguyên gốc; không suy rộng kiểm tra cuối này thành cả 8 prompt |
| Bật/tắt cache khi sinh câu deadline | Cùng toàn bộ 15 token sinh ra, kể cả token kết thúc; câu dịch vẫn sai ngày |
| Full-prefix so với incremental cache | 8 prefix của câu deadline: top1 khớp 8/8, max abs logit delta lớn nhất `0.000029921531677246094` |
| Hash sau inference | Adapter và artifact prompt đóng băng giữ nguyên |

Chênh lệch logit khi bật adapter chỉ chứng minh adapter có tác động tính toán, không chứng minh câu trả lời tốt hơn. Kiểm tra cache chỉ bao phủ một câu dịch và tám prefix; không chứng nhận mọi độ dài, tác vụ hoặc môi trường GPU. Cảnh báo reference convolution/delta kernels được giữ trong log; không cài kernel khác để thay môi trường.

## Đọc tám cặp câu trả lời

Các ô dưới đây bỏ newline cuối để dễ đọc; raw output/token IDs vẫn nguyên vẹn trong bằng chứng private.

| Probe | Base nguyên gốc | Adapter | Nhận xét |
|---|---|---|---|
| Ngày ngay sau Tuesday, trả tiếng Anh | `Wednesday` | `Wednesday` | Cả hai đúng ở câu hỏi này |
| Dịch riêng Wednesday sang tiếng Việt | `Thứ Ba` | `Thứ Ba` | Cả hai sai; đúng là thứ Tư |
| Cho sẵn `Wednesday = thứ Tư`, yêu cầu lấy giá trị | `thứ Tư` | `thứ Tư` | Dùng đúng ánh xạ được cấp; không phải kiểm tra nhớ độc lập |
| Dịch “Please return the key by Wednesday evening” | “Vui lòng trả lại chìa khóa vào tối thứ Ba.” | “Làm ơn trả lại chìa khóa vào tối thứ Ba.” | Cả hai sai ngày; “vào tối” cũng không giữ rõ nghĩa hạn chót `by` |
| Dịch brother, giữ tuổi chưa rõ | “Anh tôi có thể thu thập nó. Giữ nguyên độ tuổi chưa rõ của anh.” | “Anh tôi có thể thu thập nó. Giữ nguyên độ tuổi không xác định của anh.” | Tự chọn anh, dùng “thu thập” thiếu tự nhiên khi nhận đồ; còn dịch cả chỉ dẫn kiểm soát thay vì chỉ dịch câu nguồn |
| Nếu đến sau 16h vẫn nhận được sáng hôm sau, có bắt buộc đến trước 16h cho mọi việc nhận không? Tiếng Anh | `No` | `No` | Cả hai đúng |
| Cùng câu hỏi điều kiện, tiếng Việt | `Không` | `Không` | Cả hai đúng |
| Sao chép object có Wednesday và mã mQ6 | `{"day":"Wednesday","code":"mQ6"}` | `{"day":"Wednesday","code":"mQ6"}` | Giữ đúng key/value và JSON; bỏ khoảng trắng của nguồn và thêm newline, nên không phải bản sao byte chính xác |

Không cộng các control này thành tỷ lệ chất lượng tổng quát. Chúng được chọn sau khi đã quan sát lỗi ở các lượt trước, không phải benchmark mù hoặc kết quả tập test. Thành công ở điều kiện ngắn không phủ nhận lỗi entailment trong câu B1 dài ở báo cáo prompt v2.

## Lỗi công cụ chẩn đoán đã sửa

Lượt `inference-diagnostics-20261026` dừng trước khi sinh bất kỳ câu trả lời nào vì metadata từ loader chứa Python `set`, gây `TypeError: Object of type set is not JSON serializable`. Đây là lỗi ghi metadata của script private; không phải bằng chứng model hoặc trainer lỗi.

Giữ nguyên script, log và `failure.json` của lượt đó. Retry ở thư mục riêng chỉ thêm serializer chuyển `set/frozenset` thành list đã sắp xếp; kiểu không hỗ trợ khác vẫn báo lỗi, không dùng `default=str`. Self-check kiểm tra cả cấu trúc lồng nhau và việc từ chối object không hỗ trợ, trước khi nạp lại model. Không có kết quả cũ nào bị xóa hoặc gộp thành mẫu chất lượng.

## Bằng chứng và cách xác minh lại

Thư mục private ngoài Git trên máy này:

`C:/Users/HP/Desktop/English_dictition/data/fine-tuning/colab-transfer-20261025/inference-diagnostics-retry-20261026/`

- `diagnose.py`, `prompts.json`, `metadata.json`, `cache-inventory.json`.
- `results.jsonl`: 8 base nguyên gốc + 1 no-cache + 2 PEFT-disabled + 8 adapter = **19 row duy nhất**.
- `controls.json`, `completion.json`, `verification.json`, `verify.py`, stdout/stderr.
- SHA-256 script: `7da838a221b4c94cf5adb4f2ed38e24321841db73cba4a40de60d32caf3b84f3`.
- SHA-256 prompt: `9d0c91435275c929f9266a24e2e3ca2ed466bc455b2d874ced6d9d227d2c6d42`.
- SHA-256 raw results: `de2c99f270f11487a329648019d78263c2a01fe4a6761d2fc424d4f496fa13d8`.
- SHA-256 controls: `1b74dbc4b033757d933fbd2b169f10bcef6977fe851b0779a58b36ec63869eb4`.

Kiểm tra lại bằng chứng mà không nạp model hoặc sinh thêm:

```powershell
& 'C:/Users/HP/Desktop/English_dictition/data/fine-tuning/.venv-cpu/Scripts/python.exe' -X utf8 'C:/Users/HP/Desktop/English_dictition/data/fine-tuning/colab-transfer-20261025/inference-diagnostics-retry-20261026/verify.py'
```

Self-check serializer và đầu vào, không nạp model:

```powershell
& 'C:/Users/HP/Desktop/English_dictition/data/fine-tuning/.venv-cpu/Scripts/python.exe' -X utf8 'C:/Users/HP/Desktop/English_dictition/data/fine-tuning/colab-transfer-20261025/inference-diagnostics-retry-20261026/diagnose.py' --self-check
```

Script inference từ chối ghi đè kết quả hiện có. `full07-progress.json` ghi riêng kết quả chẩn đoán, giữ lịch sử lỗi wrapper và local pause. Tính toàn vẹn backup và việc trainer kết thúc 200/200 giữ bằng chứng riêng; chẩn đoán này không khôi phục các tệp optimizer/postflight đã mất.

## Hướng tiếp theo và giới hạn

Ưu tiên đối chiếu cùng probe trong một môi trường inference tham chiếu đã xác minh trọng số trước khi quyết định huấn luyện lại hoặc thêm luật prompt. Việc đó chưa được thực hiện trong lượt này. Không sửa dữ liệu đóng băng để phản ứng với tám câu nhỏ và không dùng ngày đúng khi có bảng ánh xạ để chứng nhận bản dịch tổng quát.

Đã kiểm tra đủ row/uniqueness, completion, controls, hash, serializer self-check, Python compile và UTF-8. Chỉ bổ sung báo cáo trong Git; private script/model/raw output không được commit. Chưa đối chiếu GPU, chưa dùng tập test, chưa triển khai. CI GitHub phải báo theo trạng thái kiểm tra thực tế, không coi các phép kiểm tra local là CI đã chạy.
