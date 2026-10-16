# WebLens — đề xuất lưu trữ ZIP cũ trên VPS, 09/10/2026

## Kết luận

Kiểm tra chỉ đọc VPS `160.191.244.27`, host `WIN-D1G2GS3O7J7`: **23 ZIP = 8.121.673.537 bytes (7,564 GiB)** trong `C:\ProgramData\WebLens\incoming`. Đề xuất **giữ 3 ZIP = 1,002 GiB**, xem xét sao lưu ngoài VPS rồi xóa **đúng 20 ZIP cũ = 7.045.533.015 bytes (6,561664 GiB)**. Đây là danh sách cần phê duyệt, **không phải xác nhận an toàn để xóa ngay**. Chưa copy, hash nội dung archive hoặc xóa file production.

Snapshot 20:43:55 UTC+7: C: trống **11,26 GiB**, DictaLab health `UP`, cả sáu service WebLens `Running`. Không khẳng định chức năng WebLens end-to-end đã được kiểm thử. Snapshot runtime 20:46:18 UTC+7 xác nhận junction `C:\WebLens\current` trỏ tới release `6ade9332e2e4c4df12b304edecad5d756462143f`; wrappers Backend/Caddy/Capture/Crawler tham chiếu `current`. Giữ cả hai thư mục release đã cài và mọi runtime.

Đã cài hai release `6ade9332e2e4c4df12b304edecad5d756462143f` và `9e87e8b0c95ea1791dcc4814793e45b1e391ca36`. Bản thứ hai được giữ **để bảo toàn lựa chọn rollback**, chưa chứng minh rollback tương thích DB hoặc hoạt động. Không coi ZIP theo commit là bản sao của JAR đang chạy nếu có patch tại chỗ; chưa hash/đối chiếu binary.

## Giữ nguyên

| Exact path | Bytes | Lý do |
|---|---:|---|
| `C:\ProgramData\WebLens\incoming\weblens-6ade9332e2e4c4df12b304edecad5d756462143f-windows-amd64.zip` | 944.850.330 | Gói thuộc release mà junction current đang trỏ tới |
| `C:\ProgramData\WebLens\incoming\weblens-6ade9332e2e4c4df12b304edecad5d756462143f-runtime-reuse.zip` | 65.666.670 | Gói runtime-reuse cùng release; không giả định trùng nội dung với gói đầy đủ |
| `C:\ProgramData\WebLens\incoming\weblens-9e87e8b0c95ea1791dcc4814793e45b1e391ca36-windows-amd64.zip` | 65.623.522 | Bảo toàn gói của release cũ vẫn được cài |

Giữ cả `weblens-6ade9332e2e4c4df12b304edecad5d756462143f-windows-amd64.zip.relay` (373.489.664 bytes), scripts, thư mục redis-auth, service/config, backups và PostgreSQL data/WAL. `.zip.relay` không nằm trong 23 ZIP và không cộng vào phương án dọn. Không xóa MoreLogin, Bilingual, DB hoặc dữ liệu Windows.

## 20 exact targets đề xuất, chưa xóa

Các gói này có ngày sửa metadata trước hai release đã cài và tên không khớp release đã cài. Đây là ưu tiên lưu trữ cũ, **không suy ra không có dependency gián tiếp hoặc không cần dùng tương lai**.

| Exact path | Bytes |
|---|---:|
| `C:\ProgramData\WebLens\incoming\weblens-0568960-windows-amd64.zip` | 409.574.309 |
| `C:\ProgramData\WebLens\incoming\weblens-4d092c5-windows-amd64.zip` | 409.574.297 |
| `C:\ProgramData\WebLens\incoming\weblens-1e12fd7-windows-amd64.zip` | 409.574.319 |
| `C:\ProgramData\WebLens\incoming\weblens-0852040-windows-amd64.zip` | 409.574.360 |
| `C:\ProgramData\WebLens\incoming\weblens-b49f9af-windows-amd64.zip` | 409.574.385 |
| `C:\ProgramData\WebLens\incoming\weblens-b49f9af62d0cab23014739d9af2c9f183136adf0-windows-amd64.zip` | 409.574.486 |
| `C:\ProgramData\WebLens\incoming\weblens-660c54a6810fd9b90a53adb579881d6e402781bb-windows-amd64.zip` | 409.574.259 |
| `C:\ProgramData\WebLens\incoming\weblens-4cc4d7ae1ba8effea3e568e670ab2f65c5a8311a-windows-amd64.zip` | 409.574.563 |
| `C:\ProgramData\WebLens\incoming\weblens-0b42b628bdf7da224bc0f949de78afa304096eb1-windows-amd64.zip` | 409.574.559 |
| `C:\ProgramData\WebLens\incoming\weblens-19aeb423529dc4abb74b32e5faa4c7593807fdb9-windows-amd64.zip` | 944.799.061 |
| `C:\ProgramData\WebLens\incoming\weblens-2f759ec59d949522f845efd26bd789b58df93348-windows-amd64.zip` | 944.799.219 |
| `C:\ProgramData\WebLens\incoming\weblens-41fa0d9595d4ace1cab929152c22953109f6c778-windows-amd64.zip` | 944.801.222 |
| `C:\ProgramData\WebLens\incoming\weblens-4b562c4366748a3450bf98335f4a81d51084282f-windows-amd64.zip` | 65.618.654 |
| `C:\ProgramData\WebLens\incoming\weblens-af9e3ab69f44552fd030b5e1355da8099739747f-windows-amd64.zip` | 65.618.953 |
| `C:\ProgramData\WebLens\incoming\weblens-2efd59e54896469eaaf43c6e58daa4446d118f50-windows-amd64.zip` | 65.619.466 |
| `C:\ProgramData\WebLens\incoming\weblens-56b92dde4e179244887b717d53095d32ab24a62a-windows-amd64.zip` | 65.620.426 |
| `C:\ProgramData\WebLens\incoming\weblens-e7bae1d8131d875f645dcee839db5b5167ed3185-windows-amd64.zip` | 65.621.280 |
| `C:\ProgramData\WebLens\incoming\weblens-49666704dbaa30443c2cad67abd6f1bab9fee438-windows-amd64.zip` | 65.621.274 |
| `C:\ProgramData\WebLens\incoming\weblens-f873c77903965b4d6c9ad2498969d594e7b01401-windows-amd64.zip` | 65.621.303 |
| `C:\ProgramData\WebLens\incoming\weblens-5f7d6c3194209b221f618526d54f91ec9a98555c-windows-amd64.zip` | 65.622.620 |

Không gọi hai gói b49f9af là bản sao trùng nhau: tên commit ngắn/dài và kích thước gần nhau không chứng minh cùng bytes.

## Giới hạn bằng chứng và phương án sau phê duyệt

- Không thấy process command line hoặc scheduled task action tham chiếu trực tiếp exact paths của 23 ZIP. Scripts có thể dựng tên bằng biến hoặc truy cập qua đường dẫn khác; kết quả này không chứng minh không có caller gián tiếp.
- Rà 15 script `.ps1` trực tiếp trong incoming, chỉ xuất tên script và tên ZIP tham chiếu literal, không xuất nội dung/arguments/secrets. Không thấy literal filename đầy đủ; chưa phân tích luồng dựng tên động hay lịch sử deploy. Không đọc thư mục redis-auth.
- Kiểm tra registry NSSM không thấy Application/AppDirectory; bước tiếp theo xác định service wrappers có XML và dùng `current`. Giá trị executable tương đối không được suy đoán thành đường dẫn tuyệt đối. Cây process cùng junction cung cấp bằng chứng bổ sung; không coi trường rỗng ở probe đầu là không có dependency.
- Có 15 thư mục backup WebLens trên cùng VPS; tên thư mục không chứng minh restore đã đạt hoặc archive nằm trong backup. Trên HP, root `C:\Users\HP\.codex\backups` chỉ thấy DictaLab tại snapshot, chưa có bản sao WebLens được kiểm chứng.

Nếu người dùng duyệt **20 exact targets** ở trên:

1. Xác minh lại host, exact paths, ancestor/file không phải reparse point, metadata, current release, service/task references và headroom. Dừng nếu source thay đổi hoặc xuất hiện deployment đang chạy.
2. Copy tuần tự, giới hạn tốc độ, vào `C:\Users\HP\.codex\backups\WebLens\incoming-20261009`, ngoài repo/target, quyền truy cập hạn chế. HP trống khoảng 98,44 GiB ở snapshot, phải kiểm tra lại trước copy. Không tạo backup cùng ổ VPS để lấy dung lượng.
3. So SHA-256 và kích thước source/destination từng file; kiểm tra source ổn định trước/sau copy và trước xóa. Mismatch, file thay đổi, lỗi copy hoặc tài nguyên xấu thì dừng, không xóa file đó. SHA khớp chứng minh bảo toàn bytes, không chứng minh một release deploy/restore thành công.
4. Chỉ sau khi toàn bộ bản sao cần thiết được xác minh mới xóa từng **exact ZIP đã duyệt**, không wildcard hoặc recursive delete; giữ manifest source/destination/checksum. Nếu cần redeploy bản cũ, phải phục hồi ZIP tương ứng từ HP về exact path trước.
5. Đo lại dung lượng, service/health; giữ backup HP. Không tự giảm guard hoặc chạy fault/load tests. Chưa tắt HP vì nghiệm thu reliability production còn mở.

Dọn có thể giải phóng khoảng 6,562 GiB logical; khoảng **17,82 GiB trống** là ước tính từ snapshot 11,26 GiB, không phải số đo sau cleanup. NTFS allocation và ứng dụng đang ghi có thể làm khác. Hash/copy tạo disk I/O; phải hạn chế tốc độ và theo dõi tài nguyên. Paging/disk queue chưa được đo lại; dung lượng tốt hơn không đồng nghĩa production đã đạt preflight.

## Evidence và kiểm chứng

Local evidence: `target/reliability-weblens-archive-review-20261009/` (Git ignored, giữ nội bộ).

- `archive-inventory.log`: giữ probe đầu thất bại do cú pháp PowerShell foreach/pipeline.
- `archive-inventory.ps1`, `archive-inventory-green.log`: bản sửa cú pháp, danh sách ZIP, metadata, service/task direct references, health/disk. Không cài script trên VPS; truyền qua SSH stdin.
- `runtime-paths.ps1`, `runtime-paths.log`, `runtime-paths-final.log`: service XML whitelisted fields, cây process, junction target, kiểm tra literal references ở 15 scripts. Không xuất raw command line/config contents.
- `probe-check-green.log`, `runtime-check-green.log`, `runtime-check-final.log`: self-check local PowerShell 5.1 cho release ID extraction/path boundary và executable argument exclusion.
- `accounting-check.log`: đối soát 23 = 3 + 20, tổng bytes, exact paths trong báo cáo, junction và service trạng thái.

Không chạy build/backend test mới, production load/fault, deploy, restart, DB mutation, S3/provider call, cleanup hoặc shutdown. Chỉ có cập nhật evidence/docs local. Ponytail giữ diagnostic nhỏ, dùng PowerShell/.NET có sẵn; Encoding Guard kiểm tra UTF-8 của report và scripts.
