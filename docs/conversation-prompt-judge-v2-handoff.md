# Bàn giao kiểm thử prompt sinh và judge v2

Ngày sửa: 23/10/2026. Chủ dự án yêu cầu **chỉ sửa**, hoãn các lượt gọi model thật
để giao model khác kiểm thử. Chưa có bằng chứng v2 tăng tỷ lệ đạt thực tế.

## Những gì đã sửa

| Lỗi quan sát | Thay đổi |
| --- | --- |
| Sinh 4 slot/fact | Chốt đúng số slot trước khi viết; chi tiết phụ không cần thêm slot; đếm cả ba mảng content.slots/content.facts/translation.facts. |
| Thiếu primary evidence | Với inference_prediction và argument_relation, toàn bộ evidenceEn phải trùng đúng một phần tử segmentsEn; thêm các cue cần thiết còn lại. |
| Nhầm taskType/focus | Chọn loại từ ý nghĩa được hỏi; action dưới if vẫn là action; final value sau tương phản rõ dùng change_revision. |
| Đáp án ý kiến chứa lý do | Giữ object + evaluation; nếu cần hỏi lý do, dùng một slot why đã nằm trong giới hạn số lượng. |
| Lặp ý đã CAPTURED | So từng slot với ý đã hỏi trong lịch sử; đổi tên/giá trị hoặc thêm một fact mới không làm slot cũ thành mới. Giữ ngoại lệ revision thật. |
| Tự thêm giới tính | So cả bản dịch toàn bài và đáp án EN/VI; dùng tham chiếu trung tính khi nguồn chưa xác định. |
| Câu hỏi thành chính sách | Giữ trạng thái hỏi/đề nghị trong câu hỏi và đáp án; không suy quyền đổi vé từ việc khách hỏi có được đổi hay không. |
| Codex từng kết luận đảo vai quá chắc | Judge kiểm tra quy chiếu thực tế; generic you hoặc việc khách biết một quy định không tự chứng minh sai vai. |
| B2 chỉ hỏi chi tiết đơn giản | Truyền levelDemand cho cả teacher và judge: ít nhất một đáp án phải cần xử lý quan hệ lập luận/tương phản/ý kiến có điều kiện. |
| Verdict true dù phần audit có vấn đề | Thêm expectedTaskType, answerScopeSupported, newMeaningSupported, attributesSupported, historyQuote theo từng fact; chặn verdict mâu thuẫn với audit. |
| Adapter chấm lại thiếu hardPass | Từ chối trước khi dispatch; không tốn lượt Web rồi mới phát hiện metadata thiếu. |

Quy tắc phân loại vẫn là `conversation-semantic-v3`; không đổi schema bài, giới hạn
từ, validator hay ngưỡng nhận. `slots.kind` vẫn là tên slot, không phải nhãn taskType.
Các kiểm tra audit chỉ phát hiện mâu thuẫn cấu trúc; model vẫn có thể chấm sai nhất quán.
Không coi unit test là bằng chứng judge hiểu đúng ngữ nghĩa.

Artifact mặc định mới: `artifacts/prompts/conversation-reviewed-collection-v2-20261023.json`.
Artifact v1 và mọi biên bản cũ giữ nguyên. Thay đổi nằm trong luồng teacher/thu thập
offline; chưa thay prompt production Java, deploy, train hoặc gọi API thật.

## Kiểm tra đã chạy

```powershell
python -m pytest training/tests/test_collect_reviewed.py training/tests/test_iterate_teacher.py training/tests/test_generate_teacher_data.py -q --disable-warnings
```

114 test offline đạt. HTTP được mock trong kiểm tra transport. Đã kiểm tra UTF-8,
hash artifact và diff trước giao code. Không chạy benchmark, calibration hoặc train thật.

## Việc model tiếp theo làm khi được giao chạy test

1. Đọc AGENTS.md và tài liệu này. Không bật shutdown; không sửa prompt/rubric giữa lô.
2. Tái dùng registry DeepSeek và bridge Web một tab. Nếu 9222 vẫn trả 404, dùng CDP
   9223 như lượt trước. Kiểm tra `http://127.0.0.1:3000/health` có một page.
   Không đổi model/account ngầm; không bật billing, retry hoặc repair.
3. Chuẩn bị campaign diagnostic mới; nếu tên đã tồn tại, kiểm tra sổ gửi hoặc chọn
   tên mới rõ ràng. Không sửa hash manifest cũ để tiếp tục bằng prompt mới.

```powershell
python -m training.collect_reviewed prepare --campaign data/fine-tuning/diagnostic-v2-next --diagnostic --retain-diagnostic-evidence
```

### A. Năm lượt Web chấm lại

Nguồn đã khôi phục và kiểm hash:
`data/fine-tuning/diagnostic-10-recheck-20261023/recheck-five/*.recovered.json`.
Chỉ chấm 0007, 0009, 0020, 0023, 0030. Không gửi historicalDecision hoặc nhãn mong
đợi cho judge. Không chạy script private `run_rechecks.py` cũ: script đó tự chạy
thêm 10 teacher sau recheck và dùng hợp đồng cũ.

Đoạn dưới là mã để chạy **sau này**, không nằm trong bước sửa code. Chạy từ gốc
repository bằng một script Python riêng trong campaign private. Mỗi lần gửi có
stem duy nhất và sổ chống replay; nếu đã có request, dừng kiểm tra thay vì xóa nó.

```python
import asyncio
from pathlib import Path
from training import collect_reviewed as collect
from training import generate_teacher_data as gen
from training import iterate_teacher as run
from training.prepare_dataset import validate_training_task

async def main():
    path = Path("data/fine-tuning/diagnostic-v2-next").resolve()
    manifest, artifact = collect.load(path)
    source = Path("data/fine-tuning/diagnostic-10-recheck-20261023/recheck-five")
    folder = path / "rechecks"
    folder.mkdir(exist_ok=True)
    for number in (7, 9, 20, 23, 30):
        ident = f"candidate-{number:04d}"
        original = gen.read_json(source / f"{ident}.recovered.json")
        case = {key: original[key] for key in ("id", "context", "task")}
        assert gen.digest(case["task"]) == original["historicalDecision"]["taskHash"]
        assert gen.digest(case["context"]) == original["historicalDecision"]["contextHash"]
        errors = validate_training_task(case["task"], case["context"]["difficulty"],
                                        artifact, case["context"]["levelProfile"])
        assert not errors, errors
        case["hardPass"] = True
        gen.write_json(folder / f"{ident}.input.json", case)
        # Do not retry a failed call. Preserve request/envelope and report the failure.
        await run._judge_one(path, manifest, case, ident, "v2-" + ident,
                             None, folder=folder)

asyncio.run(main())
```

So sánh với biên bản cũ, phân biệt verdict hợp lệ, lỗi hợp đồng và lỗi ngữ nghĩa:

| Bản gốc | Điểm cần judge kiểm tra |
| --- | --- |
| 0007 | Lý do trong đáp án ý kiến không được hỏi; không buộc judge báo sai vai. |
| 0009 | Lặp ý dụng cụ thiếu/nơi giao trong cùng encounter; cần dẫn lịch sử. |
| 0020 | Action bị gắn condition và thiếu focus thật. Không dùng tên slots.kind làm bằng chứng sai alignment. |
| 0023 | Precedence change_revision của final value tương phản rõ; thiếu direct_detail đúng focus. |
| 0030 | I chưa xác định giới tính nhưng đáp án thêm he/anh ấy. |

Đây là kiểm tra hồi quy trên lỗi đã biết, không phải gold/holdout độc lập. Nếu Web
cho qua cả năm, không tự duyệt chỉ vì đủ schema. Nếu Web gắn cờ tất cả, vẫn phải
đọc căn cứ; verdict có thể đúng nhưng giải thích sai. Không tự đổi rubric để tăng điểm.

### B. Lô sinh mới nhỏ trước

```powershell
python -m training.collect_reviewed collect --campaign data/fine-tuning/diagnostic-v2-next --limit 4
python -m training.collect_reviewed status --campaign data/fine-tuning/diagnostic-v2-next
```

Lô đầu có A1/A2/B1/B2, tối đa 4 teacher và 4 Web; bài lỗi hard/gần trùng không gửi Web.
Duyệt độc lập từng bài, lưu quyết định có hash bằng lệnh adjudicate hiện có. Nếu chỉ
được giao test, dừng sau lô này; không tự mở rộng thành 30/500 bài hoặc train.

Nếu chủ dự án giao mở rộng, sau khi hết pending mới chạy lô tiếp theo. Giữ toàn bộ
evidence kể cả bài bị loại. Báo số lần sinh, hard pass, Web pass, duyệt độc lập,
lỗi giả/bỏ sót, token nếu có, và giới hạn mẫu nhỏ. Không áp dụng teacher phải đạt 28/30.
Để đo false positive sau đó, có thể dùng các đối chứng development hiện có trong
`judge_generalization_cases()`; phần gọi model này cũng đang hoãn để tiết kiệm chi phí.

## Prompt khởi động để sao chép

> Chạy kiểm thử theo docs/conversation-prompt-judge-v2-handoff.md: 5 Web recheck trên
> bản gốc đã khôi phục, rồi 4 lần sinh mới A1/A2/B1/B2. Giữ nguyên cấu hình trong lô,
> một tab Web, không repair/retry, không sửa rubric để tăng điểm. Duyệt nội dung độc
> lập và giữ đầy đủ biên bản, kể cả lỗi. Tách lỗi transport/schema khỏi lỗi ngữ nghĩa;
> báo cụ thể những chỗ chưa chắc chắn. Không train, deploy hoặc tắt máy. Dừng sau
> phạm vi 5 + 4 này và báo kết quả; không tự chạy tiếp 30/500 mẫu.
