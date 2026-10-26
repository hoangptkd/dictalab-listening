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

## Bổ sung: đối chiếu nguồn phát hành lúc 16:30 ngày 26/10 (UTC+7)

Đã lấy metadata công khai từ [repository Qwen tại đúng revision](https://huggingface.co/Qwen/Qwen3.5-4B/tree/851bf6e806efd8d0a36b00ddf55e13ccb7b8cd0a), rồi tính lại hash trực tiếp trên các tệp local. **Cả 7/7 tệp cần cho đường text-only với fast tokenizer đều khớp**, không có mismatch. Kết quả này bổ sung bằng chứng nguồn phát hành còn thiếu ở lượt chẩn đoán phía trên; không sửa manifest lịch sử.

| Tệp | Phép đối chiếu với metadata phát hành | Kết quả |
|---|---|---|
| Shard trọng số 1 | SHA-256 `26a93f066e1916adb13453dae5a0c707c0fbc71299ed98779571a907b8e74c61` và kích thước | Khớp |
| Shard trọng số 2 | SHA-256 `cb544bd9bfae93dc59b0f22b292f5933573854a7f9b97835c67060d7d910e188` và kích thước | Khớp |
| `tokenizer.json` | SHA-256 `5f9e4d4901a92b997e463c1f46055088b6cca5ca61a6522d1b9f64c4bb81cb42` và kích thước | Khớp |
| `model.safetensors.index.json` | Git blob ID và kích thước | Khớp |
| `config.json` | Git blob ID và kích thước | Khớp |
| `tokenizer_config.json` | Git blob ID và kích thước | Khớp |
| `chat_template.jinja` | Git blob ID và kích thước | Khớp |

SHA trọng số cũng được kiểm trên trang [shard 1](https://huggingface.co/Qwen/Qwen3.5-4B/blob/851bf6e806efd8d0a36b00ddf55e13ccb7b8cd0a/model.safetensors-00001-of-00002.safetensors) và [shard 2](https://huggingface.co/Qwen/Qwen3.5-4B/blob/851bf6e806efd8d0a36b00ddf55e13ccb7b8cd0a/model.safetensors-00002-of-00002.safetensors). Metadata API cùng revision được lưu nguyên bản; `publisher-verification.json` ghi thuật toán, kích thước, digest local/phát hành của từng tệp. Bốn tệp tùy chọn không có trong cache là `merges.txt`, `vocab.json` và hai cấu hình ảnh/video; fast tokenizer đã chứa vocab/merges và phép thử chỉ dùng văn bản. Không tải thêm các tệp không được dùng.

Do đó, không thấy bằng chứng cache trọng số/config/tokenizer local bị khác bản phát hành ở các tệp đã đối chiếu. Điều này không xác minh implementation thư viện hoặc chứng nhận câu trả lời đúng.

### Chuẩn bị đối chiếu GPU

- Chrome profile Văn Hoàng vẫn dùng trình duyệt hiện có. Đã mở lại đúng notebook full07; trang hiện `Connect T4 — Connect to a new runtime`, đồng thời có lỗi JavaScript output/auth-cookie. Các output cũ trong notebook không phải bằng chứng runtime hiện tại.
- Local chỉ có AMD Radeon; Ubuntu WSL có Python nhưng chưa có torch/transformers/peft/safetensors. Không cài dependency hoặc khởi chạy inference trong WSL.
- Đã chuẩn bị riêng `reference_gpu.py`, dùng đúng hash 8 prompt của lượt trước, revision và adapter checkpoint-200. Script chỉ inference; kiểm phiên bản trước khi tải model, xác minh cả 7 tệp theo metadata phát hành, đối chiếu 64 tensor adapter và logit base trước/sau tắt LoRA. Đây là kiểm tra sẽ chạy, chưa phải kết quả GPU.
- Thiết kế dùng CUDA FP16 trên T4 so với Windows CPU FP32 trước đó; phải ghi rõ khác biệt thiết bị, dtype, torch và hệ điều hành. Nếu kết quả khác, chưa thể quy nguyên nhân riêng cho một khác biệt trong số đó.
- Gói probe đã qua self-check, biên dịch và đối chiếu lại manifest ZIP. Chưa tải lên Colab, chưa cấp runtime mới, chưa tải thêm model trên Colab hoặc chạy 16 đầu ra GPU dự kiến.
- Chỉ dẫn theo dõi trước đó cấm tạo runtime mới; đã gửi yêu cầu xác nhận cụ thể để cấp T4 cho inference riêng. Bước GPU giữ chờ quyết định này; local training vẫn `PAUSED_BY_USER`.

Bằng chứng private: `C:/Users/HP/Desktop/English_dictition/data/fine-tuning/colab-transfer-20261025/inference-reference-20261026/`. Tệp metadata công khai `publisher-model-info.json` có SHA-256 `7a212d9b30b2c532e0680d7bb678fac2919ac17d534a70a66b308c8202af593f`; gói `full07-reference-probes.zip` có SHA-256 `bbb925ef72c887e1adfd6e3f4df1bbd509508e21781ede19acd59ce1fac013a4`. Gói chỉ gồm script, 8 prompt và metadata model công khai; không chứa dataset, credential hoặc trọng số. Không đưa các tệp private vào Git.

Self-check không nạp model hoặc truy cập mạng:

```powershell
& 'C:/Users/HP/Desktop/English_dictition/data/fine-tuning/.venv-cpu/Scripts/python.exe' -X utf8 'C:/Users/HP/Desktop/English_dictition/data/fine-tuning/colab-transfer-20261025/inference-reference-20261026/verify_publisher.py' --self-check
& 'C:/Users/HP/Desktop/English_dictition/data/fine-tuning/.venv-cpu/Scripts/python.exe' -X utf8 'C:/Users/HP/Desktop/English_dictition/data/fine-tuning/colab-transfer-20261025/inference-reference-20261026/reference_gpu.py' --self-check
```

### Kết quả yêu cầu cấp T4 sau khi người dùng duyệt

Người dùng đã đồng ý cấp T4 cho inference riêng. Đã bấm đúng `Connect T4` trong notebook full07 hiện có; Colab trả về hộp thoại **`Cannot connect to GPU backend`**, với lý do **`You cannot currently connect to a GPU due to usage limits in Colab.`** Đây là bằng chứng giới hạn cấp GPU ở lần thử này; không dùng nó để giải thích các lỗi hoặc lần ngắt runtime trước đó.

- Chưa kết nối được GPU mới, chưa tải/chạy script hoặc tạo đầu ra GPU. Không có kết quả tham chiếu GPU để so với CPU.
- Không bấm `Connect without GPU`, không cài dependency, không chạy lại training/cell cũ và không mua tài nguyên.
- Đã giữ ảnh `colab-gpu-usage-limit.png`, sự kiện `gpu-allocation-attempt.json` và cập nhật riêng trường đối chiếu inference trong `full07-progress.json`; lịch sử huấn luyện và `PAUSED_BY_USER` giữ nguyên.
- Bước còn lại cần một GPU khả dụng trong phạm vi tài nguyên được người dùng cho phép. Chưa xác minh được thời điểm Colab cấp lại quota; không tự ước đoán hoặc tạo lịch thử lại.

Bằng chứng quota nằm cùng thư mục private `inference-reference-20261026/` đã nêu ở trên. Các tệp model, checkpoint và dataset không thay đổi. Xác minh 7/7 tệp với nguồn phát hành vẫn hoàn tất; phần GPU giữ trạng thái bị chặn bởi hạn mức, không đánh dấu đã kiểm tra thành công.

## Đối chiếu lớp model đầy đủ trên CPU theo yêu cầu tiếp tục

Lượt này hoàn tất lúc `2026-10-26T09:49:54Z` (16:49 UTC+7). Đã nạp đúng **`Qwen3_5ForConditionalGeneration`**, là architecture trong config phát hành, rồi chạy lại đúng 8 prompt trên CPU FP32. Đối chiếu với 16 đầu ra base/adapter của `Qwen3_5ForCausalLM` đã lưu trước đó; không sửa prompt hoặc sinh một bộ câu hỏi khác.

| Hạng mục | Kết quả |
|---|---|
| Đầu ra mới | 8 base nguyên gốc + 1 control tắt LoRA + 8 adapter = **17** |
| So với đường text-only | **16/16 đầu ra base/adapter khớp từng token** |
| Giới hạn đầu ra | 0/17 bị cắt bởi giới hạn token |
| Loading info của lớp đầy đủ | Không báo missing, unexpected, mismatched keys hoặc error |
| Model đầu vào | Tính lại hash của 7/7 tệp theo metadata phát hành, đều khớp |
| LoRA sau nạp | 64/64 tensor khớp chính xác; đủ 32 LoRA layers, chỉ nằm trong language model |
| Tắt LoRA so với base nguyên gốc của lớp đầy đủ | Logit đầu khớp tuyệt đối trên 8/8 prompt, max abs delta đều `0.0` |
| Control sinh khi tắt LoRA | Probe dịch Wednesday khớp từng token với base text-only |
| Bật LoRA | Logit đầu đổi trên cả 8 prompt; max abs delta mỗi prompt từ `8.149991989135742` đến `10.267924308776855` |
| Tắt lại sau adapter | Logit probe dịch Wednesday khớp base nguyên gốc của lớp đầy đủ |
| Tệp adapter và artifact đóng băng | Hash giữ nguyên |

### Cách nạp adapter vào architecture đầy đủ

Adapter đã lưu dùng tên `base_model.model.model.layers.*`; lớp đầy đủ đặt các lớp văn bản tại `base_model.model.model.language_model.layers.*`. Script dùng API `key_mapping` có sẵn của PEFT, thay đúng tiền tố `model.layers.` thành `model.language_model.layers.` sau khi PEFT bỏ prefix của mình. Self-check kiểm tra ánh xạ một-một đủ 64 key, và sau khi nạp đối chiếu chính xác cả 64 tensor theo tên đã ánh xạ. Không sửa safetensors, adapter config hoặc checkpoint để đạt phép kiểm tra này.

Các tham số đều đóng băng, model ở `eval()` và `inference_mode()`. Cùng revision, tokenizer, chat template, seed17, greedy, FP32 và môi trường Windows CPU đã dùng trước đó; không quantization, không cài dependency, không truy cập mạng. Chỉ dùng văn bản dù lớp đầy đủ có phần vision; lượt này không kiểm khả năng xử lý ảnh/video.

### Ý nghĩa của kết quả

Kết quả nghĩa giữ nguyên như bảng tám cặp ở trên: cả hai nhánh trả đúng Wednesday cho câu hỏi tiếng Anh, nhưng dịch Wednesday thành **“Thứ Ba”**; khi có ánh xạ thì trả đúng **“thứ Tư”**. Cả hai còn mất nghĩa hạn chót trong câu deadline và tự chọn “anh” dù tuổi chưa rõ. Hai câu hỏi điều kiện ngắn trả đúng No/Không; JSON sao chép giữ đúng key/value.

Không phát hiện sai lệch do chọn lớp text-only thay lớp architecture đầy đủ hoặc do nạp LoRA ở các probe đã chạy. Tuy nhiên, hai đường vẫn dùng chung phiên bản torch/Transformers và các reference kernels CPU; **đây không phải một backend độc lập hoặc phép đối chiếu GPU**. Việc lặp lại cùng 8 probe không tạo thêm 8 trường hợp độc lập để tính tỷ lệ chất lượng tổng quát. Chưa có căn cứ sửa dataset hoặc huấn luyện lại; quyết định chưa đưa prompt v2/model vào sử dụng giữ nguyên.

### Bằng chứng và kiểm tra lại

Thư mục private: `C:/Users/HP/Desktop/English_dictition/data/fine-tuning/colab-transfer-20261025/inference-cpu-full-loader-20261026/`. Có script, prompt, raw results, metadata, controls, completion, verification và stdout/stderr; các lượt cũ được giữ nguyên.

- SHA-256 `compare_full_loader.py`: `fd12d589e199868fff1bd88f211d348dfaa4092aa412aee4d10636d540ec89c0`.
- SHA-256 `results.jsonl`: `8099a97c980d8f2a22055834249d7cffb4a302902d57c19efee2346145f069d0`.
- SHA-256 `controls.json`: `9cc427af066d696d3687c7f3a1b04f70d35cc197db4c72474306d7fd1014f74c`.
- Prompt SHA-256 vẫn là `9d0c91435275c929f9266a24e2e3ca2ed466bc455b2d874ced6d9d227d2c6d42`.

Kiểm tra bằng chứng mà không nạp model hoặc sinh lại:

```powershell
& 'C:/Users/HP/Desktop/English_dictition/data/fine-tuning/.venv-cpu/Scripts/python.exe' -X utf8 'C:/Users/HP/Desktop/English_dictition/data/fine-tuning/colab-transfer-20261025/inference-cpu-full-loader-20261026/verify.py'
& 'C:/Users/HP/Desktop/English_dictition/data/fine-tuning/.venv-cpu/Scripts/python.exe' -X utf8 'C:/Users/HP/Desktop/English_dictition/data/fine-tuning/colab-transfer-20261025/inference-cpu-full-loader-20261026/compare_full_loader.py' --self-check
```

Đã kiểm tra row count/uniqueness, so từng token với nguồn gốc, controls, tensor/hash, Python compile và UTF-8. Không còn tiến trình của lượt inference này sau khi hoàn tất. Local training vẫn `PAUSED_BY_USER`; dataset và tập test không được đọc/tokenize. Trạng thái GPU vẫn bị chặn bởi hạn mức Colab, không thử cấp lại trong lượt CPU này.

## Đối chiếu GPU đã hoàn tất sau yêu cầu điều khiển Chrome

Ngày 26/10, người dùng yêu cầu chạy GPU và đã duyệt tải adapter/tokenizer, 8 prompt riêng cùng script lên Colab đang mở trong Chrome. Runtime này cấp được **Tesla T4 15.360 MiB**. Kết quả cấp GPU mới không thay đổi bằng chứng quota bị từ chối ở lần thử trước.

### Lỗi môi trường ban đầu và cách khôi phục

Lần đầu sinh đủ 8 đầu ra base rồi dừng khi nạp PEFT: `torchao 0.10.0` không tương thích, PEFT yêu cầu bản trên `0.16.0`. Tiến trình kết thúc `exitCode=1`; chưa có đầu ra adapter ở lần này. Log, `failure.json`, metadata và 8 đầu ra được giữ riêng tại `inference-gpu-20261026/attempt01/`. Cả 8 đầu ra base của lần lỗi khớp từng token với CPU.

Sau dry-run tìm được wheel phù hợp, người dùng duyệt nâng riêng `torchao==0.17.0`. Cài bằng `--no-deps --only-binary=:all: --require-hashes`, khóa SHA-256 wheel `87a418ce0ec064a821ceab83c921b501acef0ce9a6ccd1be358fcd16c3ae8c58`. Kiểm tra metadata trước/sau xác nhận giữ nguyên torch `2.11.0+cu130`, Transformers `5.20.2`, PEFT `0.21.2`, safetensors `0.8.0`, accelerate `1.15.0`, huggingface_hub `1.33.0`; torchao import, PEFT availability và CUDA đều PASS. Không cài causal-conv1d hoặc flash-linear-attention; model dùng reference PyTorch kernels trên CUDA.

### Kết quả lần chạy mới

Script `reference_gpu.py` giữ nguyên hash đã chuẩn bị; chạy vào thư mục mới, sử dụng cache offline và giữ lần lỗi. Lượt mới kết thúc lúc **`2026-10-26T10:43:17Z` (17:43 UTC+7)**, `exitCode=0`.

| Phép kiểm tra | Kết quả |
|---|---|
| Môi trường | Linux Python 3.13.16, T4, base FP16; LoRA được PEFT giữ FP32 |
| Cấu hình sinh | Seed17, greedy, không thinking, không quantization; cùng 8 prompt và giới hạn token |
| Đầu vào | Hash 7/7 tệp base khớp metadata phát hành đúng revision; tokenizer/template của adapter khớp base |
| Đầu ra | 8 base nguyên gốc + 8 adapter = **16**, không đầu ra nào chạm giới hạn token |
| So với CPU FP32 | **16/16 đầu ra khớp từng token**, raw text và số token đầu vào cũng khớp |
| Nạp LoRA | 64/64 tensor khớp tệp adapter; tất cả tham số đóng băng |
| Tắt LoRA so với pristine GPU base | Logit đầu khớp tuyệt đối trên 8/8 prompt, delta đều `0.0` |
| Bật LoRA | Logit đầu thay đổi trên cả 8 prompt, max abs delta từ `8.158203125` đến `10.2578125`; các giá trị đã đo hữu hạn |
| Adapter sau chạy | SHA-256 giữ nguyên; không sửa checkpoint/config |
| Tiến trình | Worker inference đã thoát; không có training được khởi chạy |

Các kết quả nghĩa giữ nguyên như CPU: cả base và adapter dịch Wednesday thành **“Thứ Ba”**; có ánh xạ thì chép đúng **“thứ Tư”**. Câu deadline vẫn mất nghĩa hạn chót và sai ngày; câu brother vẫn tự chọn “anh” dù tuổi chưa rõ. Hai câu điều kiện trả đúng No/Không. JSON giữ key/value, có newline cuối nên không phải bản sao byte-exact của chuỗi yêu cầu.

Như vậy, **các lỗi đã quan sát vẫn tái hiện trên GPU**, không chỉ trên Windows CPU. Tuy nhiên, đây là 8 probe lặp lại, dùng cùng Transformers và reference PyTorch implementations; khác biệt thiết bị, dtype, torch/Python/OS chưa được tách riêng. Không chứng nhận chất lượng tổng quát, không kết luận dataset sai hoặc cần train lại, không đưa model/prompt v2 vào production từ kết quả này.

### Bằng chứng và kiểm tra lại

Thư mục private: `C:/Users/HP/Desktop/English_dictition/data/fine-tuning/colab-transfer-20261025/inference-gpu-20261026/`. Có gói tải lên, attempt01, retry, script xác minh, raw outputs, environment, completion, exit code, console log, hash export và ảnh Colab. Do output iframe/download báo lỗi JavaScript, gói bằng chứng đã được đọc qua output văn bản của notebook, giải mã tại local và đối chiếu SHA-256 trước khi giải nén. Không xuất khóa hoặc dataset.

- SHA-256 script nguyên gốc: `f5b574bf0489adeb8c46a7516661c8356c5921e4ebb6f95fb5abc44bb37c66ce`.
- SHA-256 gói bằng chứng retry: `e80f9c2a9095268043d4faa582a7195414a5b547f61e99337003e2521a3e6fa2`.
- SHA-256 `retry/results.jsonl`: `7b9483b21b8c3ffdba3d82726e904da695f02f33e8736d075f1a7965b87e9914`.
- SHA-256 `retry/completion.json`: `1768954edafb0c3f06cb855525520170a30eda64d34da7ec1a87182f916353fd`.

Kiểm tra bằng chứng đã lưu, không nạp model hoặc chạy sinh lại:

```powershell
& 'C:/Users/HP/Desktop/English_dictition/data/fine-tuning/.venv-cpu/Scripts/python.exe' -X utf8 'C:/Users/HP/Desktop/English_dictition/data/fine-tuning/colab-transfer-20261025/inference-gpu-20261026/verify_gpu.py'
```

Verifier kiểm hash export, 16 row độc nhất, từng token/raw text với CPU, 8 control tắt LoRA, các delta hữu hạn, môi trường, hash adapter/prompt/script và giữ nguyên lỗi đầu tiên. Local training vẫn `PAUSED_BY_USER`; dataset/test không được đọc hoặc tokenize. Không deploy, mua tài nguyên hoặc shutdown runtime.

## Bộ thử cải thiện tiếp theo

Đã hoàn tất thêm 60 đầu ra phát triển trên T4 với prompt ngắn, ví dụ và bảng thuật ngữ. Chưa có ứng viên đạt đủ điều kiện cấu trúc/nghĩa để chọn hoặc chạy bốn tình huống riêng. Kết quả, lỗi thinking/raw evidence và giới hạn thiết kế ngữ cảnh nằm trong [báo cáo cải thiện GPU](full07-inference-improvement-gpu-20261026.md). Các trọng số và quyết định chưa triển khai giữ nguyên.
