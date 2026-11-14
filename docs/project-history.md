# Lịch sử, bằng chứng và khôi phục

## Phạm vi dọn ngày 14/11/2026

Các báo cáo theo lần chạy, kế hoạch đã được thay thế và ảnh audit cũ đã rút khỏi cây source đang làm việc.
Không xóa raw benchmark, dữ liệu học, checkpoint, lịch sử lesson hoặc migration đã áp dụng.

Các tài liệu trước cleanup vẫn đọc được trong [snapshot Git](https://github.com/hoangptkd/dictalab-listening/tree/6228681915634c4ef7f307aaafccad6c25251a69/docs).
Khôi phục một file bằng `git restore --source=6228681915634c4ef7f307aaafccad6c25251a69 -- docs/<file>`.
Đây là thao tác khôi phục có chủ đích; không phục hồi cả cây cũ lên cấu hình hiện hành.

Bản sao ngoài Git: `C:/Users/HP/.dictalab/cleanup-20261114/`:

- `repository-before.bundle`: toàn bộ refs trước khi gộp.
- `documentation-before.zip`: 200 tài liệu/ảnh, gồm báo cáo chưa được track.
- SHA-256 archive tài liệu: `e4447298026478a02cb65ce257666a88dc78a6a1c5b2a538347886e1094579c4`.
- `personalized-verified-before.dump`: backup PostgreSQL trước migration vocabulary mới.
- Patch và file chưa track của Desktop được lưu riêng; có thể chứa dữ liệu riêng tư, không đưa vào Git.
- `retired-worktrees/` giữ nguyên 12 checkout cũ, cả dữ liệu bị Git ignore và chỉnh sửa chưa commit.
- `retire-worktrees.json`, `retired-branches.json`, `desktop-reconciliation.json` ghi nơi lưu, commit và các quyết định hợp nhất.
- `repository-consolidated.bundle` giữ refs sau hợp nhất; `repository-before.bundle` giữ refs trước đó.

Đã xóa 19 nhánh cũ ở local và origin sau khi xác minh tất cả đều là tổ tiên của nhánh hợp nhất.
Giữ `main` và `codex/fix-scenario-provider-503-20261106`; không merge vào `main` hoặc deploy trong đợt này.
Desktop là nguồn local duy nhất. Checkout của chat hiện tại được giữ detached vì công cụ đang sử dụng,
không khởi động dịch vụ từ đó. Credential QA tại đường dẫn được chỉ định trước đây vẫn giữ lại riêng;
thư mục đó không còn là Git worktree hay bản chạy local.

## Quyết định prompt còn hiệu lực

| Bản | Quyết định |
| --- | --- |
| V3 và các bản trước V4.13 | Giữ pin/compiled values để đọc/chấm bài cũ; không mở thí nghiệm mới trong Studio |
| V4.12 | Hai audit đã được thay bằng hợp đồng QUESTIONS → REVIEW_QUESTIONS → GRADE của V4.13 |
| V4.13 | Nguồn hiện hành; giữ few-shot đang có, năm bước sinh bài và chính sách review advisory |
| V4.14 few-shot candidate | Không kích hoạt: chưa có bằng chứng tăng chất lượng so với V4.13 |

Phép đo V4.13/V4.14 ngày 14/11 giữ cùng input/model/config ở các cặp so được:

- Bảy cặp GRADE cùng model: cả hai 7/7 khớp nhãn đã lưu.
- QUESTIONS trên cùng transcript/model: cả hai lỗi evidence cho agreement.
- Reviewer vẫn bỏ sót targets vượt phạm vi và mức chắc chắn; một số cặp khác model do fallback, không dùng để xếp hạng prompt.
- Bảy ca GRADE cùng model: prompt tokens 4.725 → 14.245 (3,01×), chưa có tăng chất lượng chấm.
- Pipeline còn cặp thiếu baseline/khác model; không kết luận bản nào tốt hơn từ số bài trả được.

Nguồn: [báo cáo gốc V4.14](https://github.com/hoangptkd/dictalab-listening/blob/6228681915634c4ef7f307aaafccad6c25251a69/docs/conversation-v4_14-fewshot-evaluation-20261114.md).
Raw outputs được giữ trong các thư mục `data/` tương ứng và bản sao bảo toàn worktree.
Không gắn nhãn human gold cho nhãn do agent soạn; không dùng tests mock để chứng minh AI đã tiến bộ.

## Tài liệu hiện hành

- [Chạy local](local-development.md)
- [Conversation AI](conversation-ai.md)
- [Evaluation Studio](ai-evaluation-studio.md)
- [Kiến trúc hệ thống](current-system-reference.md)
- [Runbook AI](ai-platform-runbook.md), [registry provider](ai-provider-database.md)
- [Vận hành](operations-runbook.md), [vocabulary](vocabulary.md), [training](../training/README.md)
