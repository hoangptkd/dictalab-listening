# Kế hoạch tích hợp SFT/LoRA cho Conversation AI

## 0. Mục tiêu

Mục tiêu là huấn luyện một adapter LoRA bằng Supervised Fine-Tuning để model tạo task Conversation đúng ngay ở lần đầu: đúng vai giao tiếp, đúng số lượng fact/slot, đúng giới hạn từ, đúng loại năng lực CEFR, grounding chính xác và không tự thêm thông tin trong bản dịch.

Kế hoạch này dùng Qwen3.5-4B làm base model theo yêu cầu cập nhật ngày 2026-10-22, dữ liệu hội thoại có cấu trúc của dự án và pipeline SFT của Hugging Face TRL + PEFT. Adapter được đánh giá độc lập, sau đó merge vào checkpoint và đóng gói thành model Ollama có tên, digest, artifact và rollback riêng.

Qwen3.5-4B trên Hugging Face là checkpoint Transformers dạng safetensors, có backbone text và vision encoder. Training dùng class text-only và checkpoint Transformers tương ứng, không dùng trực tiếp model GGUF đang chạy trong Ollama. Revision đang pin: `851bf6e806efd8d0a36b00ddf55e13ccb7b8cd0a`. Nguồn: https://huggingface.co/Qwen/Qwen3.5-4B và https://huggingface.co/docs/transformers/en/model_doc/qwen3_5.

## 1. Phạm vi và nguyên tắc bất biến

### Trong phạm vi

- Tạo dataset SFT có provenance, hash và split chống leakage.
- Huấn luyện một adapter LoRA/QLoRA cho task sinh Conversation.
- Đánh giá model base và model adapter bằng cùng dataset, seed, prompt, schema, validator và chế độ repair tắt.
- Merge adapter, đóng gói model cho Ollama, tạo artifact prompt/model mới và tích hợp bằng cấu hình.
- Canary/shadow, rollback và báo cáo chất lượng.

### Ngoài phạm vi

- Không fine-tune audio, ASR, TTS hoặc model chấm câu trả lời người học.
- Không mở B2/C1/C2 vào public API chỉ vì adapter đã train.
- Không dùng repair, retry chất lượng, chọn lại output hoặc human chỉnh output để làm benchmark pass.
- Không đưa dữ liệu người học thật vào bản đầu. Nếu sau này dùng dữ liệu thật, phải có đồng ý, xoá PII và quy trình quyền riêng tư riêng.
- Không commit checkpoint, adapter, API key, raw provider log hoặc dữ liệu chứa thông tin cá nhân vào Git.

### Nguyên tắc bắt buộc

1. Lần sinh đầu là chỉ số chính. repair_enabled=false, fallback=false, một lần sinh content và một lần dịch.
2. Validation không bị bỏ qua. Output sai bị reject và vẫn nằm trong mẫu số benchmark.
3. Mọi model, artifact, dataset và training config đều có version và SHA-256.
4. Không overwrite bundle cũ. Mỗi vòng train tạo artifact/version/run mới.
5. Loss chỉ tính trên assistant output cuối; không học thuộc system prompt, user context hay few-shot assistant mẫu.
6. Không huấn luyện chain-of-thought hoặc rationale ẩn. Chỉ train JSON output công khai và reasoningEvidence dạng bằng chứng được phép xuất hiện trong schema.

## 2. Hiện trạng cần khóa trước khi làm

- Pipeline có hai lần gọi model: content tiếng Anh trước, translation tiếng Việt sau.
- Python workflow có validation và repair mặc định tắt. Java outer repair cũng mặc định tắt.
- Bundle hiện tại là immutable registry; production yêu cầu model digest, artifact SHA và evaluation gate.
- Dataset distillation cũ có 324 mẫu provisional (train=267, validation=15, test=42) và hàng đợi human review 65 mẫu. trainingReady=false; đây là dữ liệu nghiên cứu, chưa được dùng thẳng làm target.
- Benchmark đã phát hiện role inversion, lỗi độ dài, answer leak, AM/PM invention và B1 inference giả. Các lỗi này phải trở thành nhãn loại trừ/ca adversarial, không được lặp lại trong target.
- Máy hiện tại: Ryzen 7 PRO 6850HS, RAM 31,3 GiB, Radeon integrated và không có CUDA. Cấu hình P0 đổi sang CPU LoRA float32 cho 4B. Inspection đếm 4.205.751.296 tham số text; riêng base cần khoảng 15,7 GiB. Runner yêu cầu thêm 8 GiB workspace ước tính, tổng khoảng 23,7 GiB RAM trống trước khi load. Máy đang còn khoảng 14,7 GiB nên chưa qua cổng RAM. Ngưỡng này chưa bảo đảm đủ bộ nhớ ở 4.096 token; phải đo bằng pretrained smoke sau khi đủ RAM và có dataset được duyệt. CPU train sẽ chậm, chưa có đo tốc độ thực tế.

## 3. Kiến trúc model và quyết định LoRA

### Lựa chọn ban đầu

- Base: Qwen/Qwen3.5-4B, class `Qwen3_5ForCausalLM`, revision cố định như trên.
- Objective: causal language modeling, SFT trên conversational prompt/completion.
- Adapter mặc định trên máy hiện tại: LoRA float32, CPU, batch 1, gradient accumulation 8, gradient checkpointing bật; giữ max_seq_length=4096 và cấm truncation. Nếu chuyển sang GPU, tạo lock/config CUDA riêng; QLoRA 4-bit NF4 chỉ dùng qua đường CUDA đã kiểm tra khả năng dtype. Không dùng QLoRA CPU hoặc tự chuyển dtype để né lỗi RAM.
- Thinking: tắt khi serving và khi tạo dữ liệu; không đưa reasoning ẩn vào completion.
- Adapter đầu tiên là một multi-task adapter dùng cho content và translation nhưng giữ nguyên system/user message production. Không tách hai model ở vòng đầu để tránh thay đổi routing Java/Python. Nếu adapter chung làm giảm translation hoặc content, vòng sau tách thành content và translation adapter/model riêng.

TRL hỗ trợ conversational dataset, assistant-only loss và PEFT LoRA. Với conversational data, assistant_only_loss=true chỉ tính loss trên assistant; cần kiểm tra chat template của phiên bản Transformers/TRL đang pin. Nguồn: https://huggingface.co/docs/trl/en/sft_trainer.

PEFT cho phép train adapter trên model 4-bit; QLoRA giảm memory nhưng không biến model quantized thành checkpoint để train lại toàn bộ. Target modules phải được kiểm tra từ model thực tế, không đoán theo Qwen2/Llama. Nguồn: https://huggingface.co/docs/peft/en/developer_guides/quantization.

### Target module policy

1. Inspect config/tokenizer trên meta device trước, sau đó load checkpoint trên thiết bị training đã qua preflight RAM/CUDA.
2. In toàn bộ module Linear và tên parameter trainable.
3. Chọn các projection có mặt thực tế: ưu tiên q_proj, k_proj, v_proj, o_proj; nếu architecture hybrid dùng tên khác thì lập mapping từ module inspection.
4. Không đưa vision encoder vào adapter của text-only task.
5. Smoke test phải chứng minh số trainable parameters > 0 và adapter save/load được.
6. Chạy hai config pilot: attention-only; và attention-plus-mlp nếu config đầu không học được format/length.
7. Không tăng target modules chỉ để làm loss đẹp. Chọn config bằng first-pass benchmark.

## 4. Dữ liệu SFT: contract bắt buộc

### 4.1 Nguồn dữ liệu được phép

Chỉ dùng:

- Output synthetic của teacher đã qua hard validator, semantic review độc lập và human review.
- Task production/fixture đã được người duyệt xác nhận, sau khi loại PII.
- Ca adversarial do đội dự án viết và duyệt riêng.

Không dùng raw output bị reject, output chỉ được repair, output judge chưa được kiểm tra, hoặc output có lỗi semantic chưa adjudicate.

### 4.2 Quy mô theo hai vòng

Pilot P0: tối thiểu 200–300 mẫu accepted có human review, đủ A1/A2/B1, turn 2/3/4, scenario và skill. Đây là vòng kiểm tra pipeline, không tuyên bố model production-ready.

Candidate P1: 800–1.500 mẫu accepted, trong đó ít nhất 20% là ca adversarial/edge và ít nhất 20% là B1. Mở rộng lên 2.000+ chỉ sau khi P0 chứng minh adapter không overfit và không regress.

Số lượng không thay thế chất lượng. Nếu chỉ có 324 provisional, chọn một subset nhỏ đã duyệt để pilot; không gắn nhãn trainingReady=true chỉ vì đủ số dòng.

### 4.3 Format mỗi record

Mỗi dòng JSONL phải có metadata ngoài messages:

- id, groupId, scenario, level, turnNumber, taskType.
- source, reviewStatus, reviewerReference, reviewVersion.
- inputHash, outputHash, artifactVersion, promptHash, schemaHash.
- targetMessageIndex, lossMaskPolicy.

messages phải chứa đúng thứ tự message production:

1. system: instruction + content/translation system prompt của artifact hiện hành.
2. user: JSON context đã redacted, gồm level, turn, role, history và profile.
3. assistant: JSON target đúng schema của purpose.

Không chèn lời giải thích ngoài JSON. Không đưa judge rationale, human notes, API response, token, credential hoặc raw chain-of-thought vào message.

Content target giữ các field cần cho bước merge: slots, utteranceEn, facts[].textEn, facts[].evidenceEn, facts[].taskType, facts[].reasoningEvidence khi được phép, questionVi, taskVi, skill.

Translation target chỉ giữ translationVi và facts[].textVi. evidenceEn là source contract, không được để translator sửa.

### 4.4 Nhãn chất lượng trước khi export

Mỗi record phải pass tất cả:

- JSON/schema đúng purpose.
- Word bounds đúng profile.
- evidenceEn là substring exact của utteranceEn.
- Fact count, slot count và thứ tự fact khớp.
- Câu hỏi hỏi đúng fact, không hỏi thêm lý do/địa điểm/thời gian không được yêu cầu.
- Không có answer leak trong questionVi/taskVi, kể cả leak theo nghĩa/paraphrase.
- Role partner/learner nhất quán.
- Turn 1–3 tiếp tục encounter; turn 4 là encounter mới trong cùng scenario.
- Không tự thêm AM/PM, số, ngày, phủ định, nguyên nhân hoặc kết luận ngoài transcript.
- B1 có phân bố thật cho gist, opinion/attitude, reason/condition và inference/prediction; inference phải có ít nhất hai cue hợp lệ.
- B2/C1 chỉ đưa vào adapter khi profile, schema và reviewer rubric đã được chốt riêng; không trộn shadow giả định vào supported A1–B1.

## 5. Làm sạch, duyệt và chống leakage

### Pipeline dữ liệu

1. export: đọc raw/provisional và production contract hiện tại.
2. normalize: chuẩn hóa Unicode, giữ nguyên nội dung có nghĩa, không đổi dấu tiếng Việt.
3. hard_validate: gọi validator cùng code production; reject đóng, không sửa output.
4. semantic_review: reviewer độc lập kiểm tra question–fact, role, continuity, CEFR, translation và evidence.
5. human_review: người duyệt đánh dấu approved, rejected hoặc needs_adjudication; phải có reference.
6. deduplicate: exact normalized utterance, exact input hash và near-duplicate theo nhóm slot/answer.
7. split: chia theo groupId, không chia ngẫu nhiên từng turn.
8. manifest: ghi counts, class balance, hashes, exclusions và command/version.

### Split bắt buộc

- test: giữ kín trước khi train; bao gồm toàn bộ benchmark canonical 30 mẫu và adversarial holdout riêng.
- validation: 10–15% theo group.
- train: phần còn lại.
- Không có cùng scenario branch/level/answer pattern ở train và test.
- History chain phải nằm cùng split; không để turn 2 train nhưng turn 3 test nếu turn 3 phụ thuộc trực tiếp vào output turn 2.
- Benchmark cũ và report ai-platform-cefr-real-benchmark-20261021-184520.json chỉ làm baseline/holdout, không làm target train.

### Adversarial holdout tối thiểu

- 20 ca length boundary: min−1, min, max, max+1 cho A1/A2/B1.
- 15 ca role inversion.
- 15 ca turn 4 continuity transfer.
- 15 ca answer leak semantic/paraphrase.
- 15 ca AM/PM, number, date, negation invention.
- 15 ca B1 gist/opinion/inference/prediction.
- 15 ca translation evidence preservation.

Các ca holdout phải có expected error/expected valid rõ ràng và được review trước khi train. Không báo cáo accuracy trên holdout nếu test set đã bị dùng để điều chỉnh prompt hoặc hyperparameter.

## 6. Hạ tầng và dependency

### Môi trường train

Tạo thư mục riêng training/ và lock dependency. Không trộn dependency train vào services/ai-generation/requirements.lock vì service production không cần CUDA/torch.

Dependency tối thiểu cần pin:

- Python 3.11 hoặc 3.12.
- PyTorch CPU đã pin trong `training/requirements-cpu.lock` cho máy hiện tại; nếu dùng CUDA phải có lock tương ứng riêng.
- transformers phiên bản có Qwen3.5 text-only support.
- trl, peft, accelerate, datasets, safetensors.
- bitsandbytes chỉ khi dùng QLoRA và GPU hỗ trợ.
- sentencepiece/tokenizer dependency theo checkpoint.

Trước khi train, ghi lock dependency, device/dtype, ước tính RAM CPU hoặc CUDA version/GPU name/VRAM, git HEAD và checkpoint revision/hash vào manifest. Không tự động cài dependency vào môi trường production.

### Preflight bắt buộc

- Download checkpoint từ revision cố định; kiểm tra đủ file và dung lượng.
- Load tokenizer/model trên thiết bị đã cấu hình rõ (`cpu` hiện tại); không tự offload sang thiết bị khác.
- Render 3 message mẫu bằng chat template.
- Chạy one-example forward/backward.
- Chạy PEFT module inspection.
- Kiểm tra nhãn đã token hóa chỉ giám sát assistant output cuối và terminator; toàn bộ system/user và padding là -100. TRL nhận nhãn đã kiểm tra, tắt automatic assistant/completion mask để tránh tạo mask lần hai.
- Nếu prefix/token boundary của chat template không rõ hoặc có hidden reasoning chèn thêm, dừng; không đoán mask, không train full sequence và không truncation.

## 7. Cấu hình pilot SFT/QLoRA

### Cấu hình P0 đề xuất

- Cấu hình đang dùng trên máy hiện tại: CPU LoRA float32, không quantize trọng số. QLoRA 4-bit NF4/double quantization/bfloat16 hoặc fp16 chỉ thuộc cấu hình CUDA riêng ở vòng sau.
- LoRA rank r=16, alpha=32, dropout 0.05, bias=none.
- Learning rate 1e-4 cho adapter; warmup ratio 0.05; cosine scheduler.
- Epochs 2; max_steps chỉ dùng cho smoke test.
- Per-device batch 1; gradient accumulation điều chỉnh effective batch 8–16.
- Gradient checkpointing bật; use_cache=false khi train.
- Max sequence length bắt đầu 4096; tăng 8192 nếu có history dài và validation truncation bằng 0.
- Packing tắt ở P0 để dễ kiểm soát assistant mask; chỉ bật sau khi benchmark chứng minh không lệch mask.
- Seed cố định 17; thêm seed 43 cho reproducibility check.
- Save safetensors, giữ 2–3 checkpoint gần nhất; chọn best theo validation loss và benchmark first-pass, không theo training loss đơn độc.

### Matrix nhỏ, không grid search mù

Chạy tối đa bốn thí nghiệm:

1. Base model không train.
2. LoRA attention-only r=16, lr=1e-4.
3. LoRA attention-only r=32, lr=1e-4.
4. LoRA attention-plus-MLP r=16, lr=5e-5 nếu P2 không đạt.

Mỗi run có run ID riêng. Không chọn run theo validation loss nếu first-pass length/semantic giảm.

## 8. Script và file model yếu hơn phải viết

Model triển khai code theo thứ tự sau, không gộp nhiều bước vào một script:

1. training/README.md: môi trường, GPU, lệnh và rollback.
2. training/requirements.lock: dependency pin.
3. training/configs/conversation-sft-p0.yaml: base revision, dataset manifest, LoRA, quantization, seed, output.
4. training/prepare_dataset.py: export, validate, dedup, group split, manifest; fail nếu có unreviewed row.
5. training/inspect_model.py: list architecture/modules, target module mapping và tokenizer/chat template smoke.
6. training/train_sft_lora.py: load dataset, build mask, load model, attach PEFT, train, save adapter + trainer state + manifest.
7. training/evaluate_local.py: generate trên validation/test bằng cùng prompt/schema/settings; không repair.
8. training/merge_adapter.py: load base revision + adapter, merge, save safetensors và hash.
9. training/export_ollama.ps1: tạo Modelfile, ollama create, kiểm tra /api/tags, ghi digest; không push registry tự động.
10. training/tests/: dataset contract, split leakage, mask, adapter load/save, export manifest.

Mỗi script nhận path/config rõ ràng, không dùng path ngầm, không ghi đè output tồn tại và không ghi credential vào log. Dùng output directory có run ID.

## 9. Đánh giá model sau train

### So sánh bắt buộc

Chạy base và candidate trên cùng:

- 30 supported A1/A2/B1 canonical samples.
- B2/C1 shadow chỉ khi scope của run yêu cầu.
- Adversarial holdout.
- 10–20% sample semantic review độc lập và một tập human review có reference.

Giữ repair_enabled=false, fallback=false, cùng temperature, context, num_predict, timeout, prompt version và seed policy. Với provider không truyền được seed, ghi rõ seedMode=not-transmitted và không gọi kết quả là paired deterministic.

### Metrics

- Coverage: expected, captured, missing, provider failure, timeout, validation rejection.
- First-pass schema/structure pass.
- Word-bound pass trên toàn bộ expected denominator và trên measured denominator.
- Evidence exact substring.
- Fact/slot count và order.
- Question/fact semantic alignment.
- Role consistency.
- Turn 4 independence.
- B1 task type coverage: gist, opinion/attitude, reason/condition, inference/prediction.
- Translation preservation: number/date/time/negation/AM–PM/source evidence.
- Latency p50/p95/p99, memory peak và output truncation.

### Promotion gate đề xuất

Candidate chỉ được xét promotion khi:

- 100% task được accepted đều pass schema và exact grounding.
- First-pass length đạt ít nhất 95% trên expected denominator; từng level không dưới 90% ở pilot nhỏ.
- Semantic independent review đạt ít nhất 90%; không có lỗi critical role, answer leak hoặc invented detail trong sample human review.
- B1 không còn chỉ có direct detail/condition; phải có các task type đã đặt trong dataset với evidence đúng.
- Không metric chính nào giảm so với base vượt sai số đã định trước.
- Không có train/test leakage, không có PII, manifest/hash đầy đủ.
- Model import và rollback smoke pass.

Các ngưỡng trên là cổng phát hành nội bộ; chúng không biến benchmark nhỏ thành chứng nhận CEFR tổng quát.

## 10. Merge và đóng gói Ollama

1. Giữ adapter safetensors độc lập để rollback và tái merge.
2. Merge adapter vào checkpoint base cùng revision bằng merge_and_unload.
3. Chạy output contract trên merged Transformers model trước khi quantize.
4. Nếu backend/llama.cpp hỗ trợ chính xác architecture Qwen3.5, quantize merged model sang GGUF sau khi kiểm tra converter; nếu không hỗ trợ, dùng serving Transformers/vLLM và không ép import hỏng vào Ollama.
5. Ollama hỗ trợ import thư mục safetensors bằng FROM path và tạo model bằng ollama create; GGUF phải được quantize trước khi import. Nguồn: https://docs.ollama.com/import.
6. Đặt tên immutable, ví dụ dictalab-conversation-sft-p0.
7. Lấy model digest từ Ollama /api/tags; ghi vào model manifest.
8. Tạo artifact mới trong artifacts/prompts/ với version mới, model name/digest/provider, base revision/hash, adapter hash, merged hash, dataset manifest hash, training config hash, benchmark report hash và approvalStatus=development.
9. Chỉ khi gate pass và có người duyệt mới đổi artifact sang trạng thái đủ điều kiện staging; không tự bật production.

## 11. Tích hợp vào code hiện tại

### Python

- Không đổi public request/response schema.
- Đổi DICTALAB_AI_MODEL và DICTALAB_AI_ARTIFACT_VERSION qua environment/config của staging.
- Registry phải verify model name/digest và artifact SHA như hiện tại.
- Giữ repair_enabled=false trong benchmark/staging đầu tiên.
- Giữ contentSystem, translationSystem, schema và turn rules; adapter không được là lý do bỏ prompt contract.
- Thêm test model metadata, artifact hash và fail-closed khi digest sai.

### Java

- Không đổi ConversationService contract, database hay public level catalog.
- Nếu Java gọi Python, chỉ thay model/artifact phía Python deployment; nếu Java gọi local Ollama, thay model name qua property.
- Bổ sung runbook canary bằng environment riêng; không sửa default production model.
- Rollback chỉ đổi model/artifact về base đã pin, không xoá adapter/checkpoint.

### Artifact registry

- Không overwrite conversation-cefr-b1-v7-20261021.
- Tạo tên mới, ví dụ conversation-cefr-sft-p0-20261021.
- Artifact mới phải có datasetHash, generationConfig, metricReport, approvalStatus và model digest.
- C2 vẫn không được generation; B2/C1 shadow vẫn tách khỏi supported.

## 12. Rollout và rollback

### Staging

- Chạy health/readiness: artifact, model digest, provider và schema.
- Chạy smoke 5–10 mẫu, kiểm tra output raw, trace, no-repair và latency.
- Chạy full benchmark candidate/base cùng config.
- Chỉ staging nếu candidate pass gate.

### Canary

- Bật adapter cho allowlist nhỏ hoặc service instance riêng.
- Theo dõi first-pass rejection, semantic review sample, latency, timeout, token/cost và cache fingerprint.
- Không dùng repair để che số liệu canary.
- Giữ base model sẵn sàng trong thời gian rollback.

### Rollback trigger

Rollback ngay nếu có:

- schema/evidence critical failure;
- role inversion hoặc answer leak lặp lại;
- invented number/date/time/AM–PM;
- latency/timeout vượt budget;
- model digest/artifact mismatch;
- PII hoặc prompt injection xuất hiện trong output/log.

Rollback bằng cách đổi env/artifact về base model đã pin, restart instance theo runbook, kiểm tra /health/ready, chạy smoke và ghi incident. Không xoá file model đang dùng để rollback.

## 13. Bằng chứng cần bàn giao

Mỗi run phải có thư mục data/fine-tuning/<runId>/ (gitignored) gồm:

- manifest.json: git HEAD, base revision/hash, dataset hash, split counts, GPU/CUDA, dependency lock hash.
- dataset-summary.json, excluded.jsonl, human-review-reference.csv.
- train-config.yaml, trainer_state.json, logs đã redact.
- adapter path/hash, merged model path/hash, Ollama model digest.
- base/candidate reports và comparison report.
- adversarial results, leakage audit, smoke logs.
- approval decision/reference; nếu chưa duyệt ghi PENDING_REVIEW.

Trong Git chỉ commit code train, config không chứa secret, test, README và artifact manifest/report cần thiết. Không commit model weights.

## 14. Checklist triển khai cho model code yếu hơn

### Giai đoạn A — chuẩn bị

- [ ] Đọc file này và kiểm tra branch/worktree sạch đối với các file thuộc task.
- [ ] Xác nhận base checkpoint, GPU training và lock dependency.
- [ ] Không chạy train nếu dataset manifest báo trainingReady=false.

### Giai đoạn B — dữ liệu

- [ ] Hoàn tất human review và adjudication cho subset P0.
- [ ] Chạy hard validator, semantic validator, dedup và group split.
- [ ] Tạo manifest với hashes và kiểm tra không có benchmark leakage.
- [ ] Kiểm tra assistant-only/completion-only mask.

### Giai đoạn C — train

- [ ] Chạy inspect_model.py và lưu module map.
- [ ] Chạy one-step smoke trên 1–2 mẫu.
- [ ] Chạy P0 attention-only LoRA.
- [ ] Lưu adapter/checkpoint/metrics với run ID mới.

### Giai đoạn D — benchmark

- [ ] Chạy base và candidate cùng seed/config, repair tắt.
- [ ] Chấm deterministic, semantic và human review sample.
- [ ] Chạy adversarial holdout.
- [ ] Dừng nếu candidate regression; không sửa output để làm xanh.

### Giai đoạn E — export/integration

- [ ] Merge adapter và kiểm tra trước quantization.
- [ ] Import Ollama hoặc chuyển sang serving backend được hỗ trợ.
- [ ] Tạo artifact/version/hash mới.
- [ ] Chạy readiness/smoke/staging canary.
- [ ] Chuẩn bị rollback và ghi approval reference.

## 15. Các lỗi thường gặp phải chặn

- Dùng GGUF Q4 hiện tại làm base training.
- Đưa raw/repaired/provisional output vào train vì muốn đủ số lượng.
- Mask sai khiến model học system/user hoặc few-shot assistant thay vì target cuối.
- Chia ngẫu nhiên từng turn làm history leakage.
- Chọn checkpoint theo loss mà không benchmark first-pass.
- Hardcode q_proj mà không inspect Qwen3.5 hybrid modules.
- Import adapter safetensors trực tiếp vào Ollama khi backend chỉ nhận merged safetensors/GGUF.
- Đổi prompt/schema cùng lúc với đổi model rồi kết luận LoRA có hiệu quả.
- Bật repair/retry trong candidate benchmark.
- Đổi model mặc định production trước khi có artifact digest và rollback.

## 16. Kết quả mong đợi và điểm dừng

Vòng P0 thành công khi pipeline chạy tái lập, mask đúng, adapter load/merge được, model candidate sinh JSON ổn định và benchmark không regression. P0 chưa đủ để tuyên bố CEFR hoặc production-ready.

Chỉ mở rộng dữ liệu/hyperparameter sau khi biết lỗi còn lại thuộc model, prompt, validator hay reviewer. Nếu candidate không cải thiện first-pass, giữ base model và sửa dữ liệu/prompt trước khi tăng rank hoặc số epoch.

Không bắt đầu bước export production nếu chưa có dataset human-approved, test holdout chưa bị chạm, report comparison, artifact hash, model digest, rollback smoke và approval reference.
