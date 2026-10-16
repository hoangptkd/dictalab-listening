# DictaLab — kiểm kê dung lượng VPS, 09/10/2026

Theo phê duyệt của người dùng, đã mở rộng **kiểm kê chỉ đọc**, chưa xóa hoặc sao chép nội dung file production. Kết quả có một phương án nhỏ trong phạm vi DictaLab: sao lưu ra HP rồi xem xét xóa **đúng hai ZIP cài Ubuntu**, tổng **1,575 GiB**. Cần phê duyệt riêng trước khi thực hiện.

**Cập nhật 15:14 UTC+7:** hai ZIP Ubuntu không còn tại đường dẫn cũ; phương án hai file dưới đây là lịch sử, **không còn là danh sách cleanup hiện hành**. Không có thao tác xóa từ lượt kiểm tra này. Xem phần kiểm tra bổ sung cuối báo cáo trước khi quyết định.

## Kết quả

VPS `160.191.244.27`, host `WIN-D1G2GS3O7J7`; ổ C: tổng **79,46 GiB**, trống **9,48 GiB** ở kiểm tra root và candidate. Service DictaLab health UP; DictaLabPostgres, TURN, Prometheus, WebLens và Bilingual vẫn chạy. Không tắt ứng dụng, thay cấu hình hoặc chạy tải.

| Thư mục / file | GiB logical đếm được | Giới hạn |
|---|---:|---|
| `C:\ProgramData\WebLens` | 20,531 | Drill riêng không giới hạn; đang thuộc ứng dụng khác |
| `C:\.MoreLogin` | 8,785 | Chưa quét hết, dừng theo budget 15 giây; chủ yếu nhánh cache |
| `C:\DictaLab` | 7,813 | Quét xong, không lỗi enumeration/reparse |
| `C:\Users` | 4,026 | Bỏ 78 reparse points; không theo junction |
| `C:\WebLens` | 3,957 | Bỏ một reparse point; releases/runtime đang cần cho ứng dụng |
| `C:\Program Files` | 1,370 | Quét xong |
| `C:\Bilingual` | 0,922 | Bỏ năm reparse points; model/runtime/venv được giữ |
| `C:\Program Files (x86)` | 0,574 | Quét xong |
| `C:\Windows` | 5,197 | Chưa quét hết, bỏ 60 reparse points; không dùng để chọn file hệ thống cần xóa |
| `C:\pagefile.sys` | 4,840 | Metadata root; file hệ thống, không thu nhỏ/xóa |

`C:\ProgramData` tổng đếm được 21,572 GiB; hàng WebLens ở trên là **một phần của tổng này**, không cộng hai lần. Số byte là logical file length, không phải allocation vật lý trên NTFS; hard links/sparse/compression có thể làm chênh lệch. Đây không phải snapshot nhất quán khi các app còn ghi file và không phải phép đối soát toàn bộ 69,98 GiB đang dùng của partition. Windows/MoreLogin giới hạn thời gian; phần bị bỏ không được gọi là đã đo hết. Recycle Bin gần như rỗng, không giải quyết thiếu dung lượng.

Trong `C:\DictaLab`: `turn-wsl` 3,166 GiB, `backups` 1,634 GiB, `postgresql-17` 0,843 GiB, `staging` 0,569 GiB, `monitoring` 0,459 GiB. Số mới bao gồm subdirectories, khác thống kê direct-files-only của [lượt trước](reliability-evidence-2026-10-09-production-observation.md); không phải dữ liệu tự nhiên tăng đột biến.

WebLens có khoảng 8,304 GiB PostgreSQL, 8,251 GiB incoming và 2,787 GiB backups. Có nhiều ZIP release trong incoming, nhưng **chưa kiểm tra retention/current release/dependency/off-host backup của WebLens**, không đề xuất xóa chúng trong phương án DictaLab này. MoreLogin cache có thể chứa dữ liệu/profile cần giữ; chữ “cache” không tự chứng minh có thể xóa an toàn.

## Đề xuất nhỏ nhất, chưa thực hiện

| Exact target | Bytes | GiB | Mtime UTC+7 |
|---|---:|---:|---|
| `C:\DictaLab\turn-wsl\Ubuntu2204.zip` | 1.116.834.851 | 1,040 | 03/10/2026 00:49:40 |
| `C:\DictaLab\turn-wsl\Ubuntu-x64.zip` | 574.740.460 | 0,535 | 26/10/2022 17:30:14 |

Tổng **1.691.575.311 bytes = 1,575402 GiB**. File attributes hiện là `Archive`, không phải reparse/compressed/sparse theo metadata. TURN service đang Running bằng `C:\DictaLab\turn\dictalab-turn.exe`, working directory `C:\DictaLab\turn`. Không thấy running process command line hoặc scheduled task action tham chiếu trực tiếp hai exact target. Không xuất command lines/secret arguments.

Đây là **ứng viên installer archive**, không khẳng định chắc chắn không có caller gián tiếp hoặc nhu cầu cài lại tương lai. Phương án nếu được duyệt:

1. Xác minh lại đúng host, exact paths, kích thước, trạng thái file và dung lượng đích HP. Không dùng wildcard hoặc recursive deletion.
2. Sao chép **hai file này** sang thư mục backup riêng ngoài repo/target trên HP, giữ checksum và đường dẫn khôi phục. Đọc nội dung để hash/copy chỉ sau khi được duyệt bước này.
3. So SHA-256 source/destination và xác minh source không thay đổi; thất bại thì không xóa. Backup chỉ được coi là khả dụng khi checksums đã qua.
4. Chỉ xóa đúng hai ZIP trên VPS sau các điều kiện trên; giữ nguyên cả thư mục `turn-wsl`, các appx/install.tar.gz/bundle, binary/config TURN, DB, media và JAR rollback.
5. Kiểm tra lại service/health/disk; nếu cần khôi phục, copy lại file đã xác minh vào đúng đường dẫn cũ. Không hứa rollback tự động khi chưa thực hiện.

Ước tính disk trống có thể từ 9,48 lên khoảng **11,06 GiB**, không phải số đo sau xóa; allocation và ghi đồng thời có thể làm khác. Việc này **chỉ xử lý dung lượng**, không chứng minh paging/disk queue đã đạt guard. Phải chạy lại preflight đầy đủ trước bài tải/crash/release, không tự hạ ngưỡng.

Không dọn: DB data/WAL của mọi ứng dụng, backup DB/media, JAR hiện hành/previous/artifacts, Prometheus data, Windows/WinSxS/pagefile, profile Chrome/MoreLogin, WebLens/Bilingual. Không đổi retention hoặc trả phí tăng ổ đĩa.

## Bằng chứng và cách đo

Raw local: `target/reliability-production-disk-20261009/`.

- `root-inventory.log`: metadata root, volume/service/health.
- `directory-inventory-stdin.log`: 11 root records, tổng file/group và 20 file lớn nhất mỗi root.
- `weblens-breakdown.log`: drill metadata riêng ProgramData/WebLens.
- `candidate-inventory.log`: hai exact targets, process/task direct-reference count, TURN executable và health.
- `measure-directories.ps1`: diagnostic local, truyền qua SSH stdin; **không cài/ghi script trên VPS**. Dùng API .NET sẵn có, budget 15 giây/100.000 entries mỗi root, nghỉ 20ms mỗi 250 entries; không đi theo reparse points hoặc thư mục auth/.ssh đã loại trừ. Budget được kiểm tra giữa entries, không hứa ngắt một system call bị treo.
- `scanner-check-green.log`: PowerShell 5 fixture 505 bytes/32 files, group total, top-20 có giới hạn, entry budget và missing path PASS. Test được chạy local; fixture giữ trong evidence, không phải dữ liệu production.

Giữ cả `directory-inventory.log` thất bại do command line encoded quá dài; chuyển sang stdin, không thay scanner để che lỗi. `scanner-check.log` đầu thất bại vì `Measure-Object` trên OrderedDictionary trong assertion PowerShell 5; sửa assertion dùng giá trị key trực tiếp, không đổi kết quả scanner. Kiểm tra JSON/byte accounting của cả 11 root records và tổng candidate đã qua. Không có test/build backend mới.

Chỉ đọc metadata production; không hash/read nội dung source archive, không DB query/mutation, S3/provider call, deploy, restart, cleanup hoặc shutdown. Raw có tên/path nội bộ ứng dụng khác: giữ nội bộ, rà soát trước công bố. `target` bị Git ignore; manifest hash local được lưu cùng báo cáo/raw, không chỉnh raw gốc.

Ponytail chọn phương án hai file đã có, không tạo cleanup framework hoặc thêm dependency. Encoding guard kiểm tra UTF-8 báo cáo/diagnostic. Chưa đóng nghiệm thu production hoặc yêu cầu tắt HP.

## Kiểm tra bổ sung Temp/Downloads/cache lúc 15:13–15:14 UTC+7

Người dùng tiếp tục yêu cầu tìm phần nên xóa. Chỉ kiểm tra metadata, dùng lại scanner đã kiểm thử; budget tối đa 30 giây/100.000 entries cho mỗi thư mục. Raw mới ở `target/reliability-production-disk-followup-20261009/`, không ghi đè raw lượt trước.

| Khu vực | Dung lượng logical mới | Đánh giá cleanup |
|---|---:|---|
| WebLens incoming | 8,251 GiB tại lượt scan; riêng 23 ZIP trực tiếp được kiểm tra sau đó có 7,564 GiB | Ưu tiên đánh giá package release cũ; phải xác định current/rollback, có backup kiểm tra checksum và phê duyệt exact targets. Chưa đánh giá dependency/retention nên chưa được xóa |
| `C:\.MoreLogin\cache` | 11,205 GiB đã đếm, scan chưa hoàn tất | **Không xóa cả folder**: sample profile có Cookies, Login Data, Local Storage, Preferences, Sessions và Local State |
| User Temp | 0,291 GiB, bỏ 37 reparse points | Chủ yếu pytest khoảng 159,30 MiB và installer scratch Chocolatey khoảng 119,32 MiB; chỉ chọn file tạm cũ khi không còn test/installer dùng, chưa xác minh in-use |
| Administrator Downloads | 0,489 GiB, scan hoàn tất | Hai toolkit khoảng 249,29/243,67 MiB; có executable và dependencies, không coi toàn bộ là download thừa. Cần xác nhận không dùng trước khi dọn |
| Windows Temp | Khoảng 0,405 MiB | Không có lợi ích dung lượng đáng kể, có runtime perfdata; không cần dọn để đạt headroom |
| Windows Update Download | 0 bytes tại snapshot | Không có dung lượng để giải phóng ở thư mục này; không dừng Windows Update hoặc reset cache |

`profile-layout-health.log` chỉ đọc tên/loại và existence của file profile, **không đọc cookie, mật khẩu, session hoặc nội dung cấu hình**. Sample này chứng minh tên folder “cache” bao gồm dữ liệu profile cần bảo vệ; chưa phân loại toàn bộ browser cache thuần có thể dọn. Raw có đường dẫn nội bộ, giữ nội bộ.

`candidates-latest.log` lúc 15:14:47 xác nhận cả `Ubuntu2204.zip` và `Ubuntu-x64.zip` không còn tại exact paths cũ; không biết chúng được di chuyển/xóa bởi tác nhân nào. Không quy thay đổi đó cho trợ lý hoặc tự chạy cleanup cũ. Disk mới trống **11,62 GiB**; sample health 15:13:30 UP và disk 11,74 GiB. Các số thay đổi khi ứng dụng khác đang hoạt động, không phải snapshot quiescent hoặc chứng minh nguồn gây tăng/giảm.

Ưu tiên hiện tại: kiểm tra và lưu trữ off-host các **ZIP release cũ WebLens** sau khi được chốt phạm vi; kế đến mới chọn đúng file tạm/toolkit không dùng. Không xóa data PostgreSQL, backups, monitoring, runtime/JAR rollback, WinSxS/pagefile hoặc profile MoreLogin để lấy dung lượng. Đã vượt 10 GiB disk ở thời điểm cuối nhưng chưa đo lại paging/disk queue, chưa nghiệm thu resource preflight/reliability production.

JSON/accounting của sáu root mới và byte tổng của 23 ZIP được kiểm tra local; UTF-8 report đạt. Không sửa code backend, không chạy build/test production, không deploy/restart, copy/hash nội dung remote hoặc xóa file. Không tạo automation/shutdown.

## Rà ZIP WebLens lúc 20:43–20:46 UTC+7

[Danh sách exact targets và phương án](reliability-evidence-2026-10-09-weblens-archives.md): junction `C:\WebLens\current` trỏ tới release `6ade9332e2e4c4df12b304edecad5d756462143f`. Đề xuất giữ ba ZIP của hai release đã cài, xem xét sao lưu ngoài VPS rồi xóa đúng **20 ZIP cũ / 6,561664 GiB** sau phê duyệt. Không thấy direct process/task references; chưa chứng minh không có dependency gián tiếp hoặc rollback đã đạt. Chưa có bản sao WebLens off-host được kiểm chứng, chưa copy/hash nội dung ZIP hoặc xóa file. Giữ `.zip.relay`, DB/backups, runtime, configs và profiles. Snapshot mới disk **11,26 GiB**, DictaLab health UP và sáu WebLens services Running; chưa kiểm tra chức năng WebLens end-to-end hoặc đo lại paging/disk queue.
