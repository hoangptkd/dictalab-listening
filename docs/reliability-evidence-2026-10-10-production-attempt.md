# DictaLab — production attempt và release gate, 10/10/2026

Phạm vi lần này đã bao gồm kiểm tra VPS theo yêu cầu. Không chạy tải, không đổi JAR,
không dừng service và không sửa dữ liệu production vì các gate an toàn không đạt.

## Inventory read-only

- SSH/PowerShell read-only thành công.
- DictaLab service: `Running`; health: `UP`.
- JAR đang chạy SHA-256:
  `DFAC955B6FC915DEF78AB5563DA12D24311AD9E5BF0AAE7B027E9C182453CC2F`.
- RAM trống: 2.281 MiB; disk trống: 11,178 GiB.
- `backup-success.json` tồn tại.
- Staging restore script trên VPS có SHA khác source hiện tại và không chứa
  `-CheckS3Only`; không dùng nó để kết luận S3 recovery.

Raw inventory: `target/reliability-production-20261010-002120/inventory.json`.

## Preflight resource gate

Lượt đầu dùng cửa sổ 60 giây chỉ thu được 9 mẫu; đó là lỗi thiết kế thời lượng so với
điều kiện tối thiểu 10 mẫu. Đã sửa collector local và các caller từ 60 lên 70 giây,
không đổi ngưỡng. Contract test VPS đạt 5/5.

Lượt sau thu được 11 mẫu nhưng vẫn bị từ chối:

| Chỉ số | Quan sát | Ngưỡng |
|---|---:|---:|
| Samples | 11 | ≥10 |
| CPU average / peak | 10,27% / 15% | ≤35% / ≤60% |
| RAM trống thấp nhất | 2.152 MiB | ≥1.800 MiB |
| Disk trống thấp nhất | 11,18 GiB | ≥10 GiB |
| Disk queue peak | **8** | ≤2 |
| Paging peak | **48.162 pages/s** | ≤100 |

Kết luận: **resource gate FAIL**, `LoadStarted=false`. Không retry tự động và không
hạ ngưỡng. Raw:
`target/capacity/production-20261010-fix-preflight-20261010-002632.csv` và
`.result.json`.

## Release/recovery gate

Một lệnh SSH được truyền sai switch và đã chạy restore drill đầy đủ trên database tạm;
đây không phải release và không sửa database nguồn. Kết quả: 24 bảng, 8 users,
41 sessions, 0 invalid FK, 0 unvalidated constraints, schema 25, 8,25 giây.

Khi thử lại bằng encoded PowerShell command, staging script cũ vẫn bỏ qua switch và
chạy restore drill đầy đủ: 24 bảng, 8 users, 41 sessions, 0 invalid FK,
0 unvalidated constraints, 6,67 giây. Hai restore đều không chứng minh S3 object
recovery; output chỉ là DB restore. Backup SHA được báo:
`C1BBD2043E29C3F510E0198C8487520F1F622778DD563F88A1C7DC64614003E9`.

Do staging script mismatch và resource gate FAIL, không upload artifact, không chạy
`update-windows-vps.ps1`, không gọi backup production, không dừng service và không
chạy S3/Redis release gate bằng code source mới. Đây là chặn an toàn, không phải
production deployment failure.

## Thay đổi source lần này

- `preflight-evidence-vps.ps1`, `run-final-capacity-vps.ps1`,
  `start-capacity-vps.ps1`: cửa sổ preflight 70 giây.
- `src/test/js/reliability-gate.test.cjs`: kiểm tra 70 giây và giữ nguyên guards.
- Test local: **5/5 PASS** cho gate contract; Encoding Guard đạt trên các file sửa.

Không thay Java, migration, API hoặc dữ liệu. Không tự cập nhật staging VPS vì resource
gate chưa đạt; staging script cũ được ghi nhận để lần triển khai sau phải kiểm tra hash
trước khi dùng.

## Trạng thái và bước cần điều kiện mới

- Production vẫn `UP` với artifact cũ; rollback không cần thực hiện.
- Kiểm tra sau cùng lúc 00:30 UTC+7: service `Running`, health `UP`, load processes `0`.
- Toàn bộ JavaScript sau thay đổi preflight: **132/132 PASS**.
- P0 S3 object backup/restore thật vẫn mở.
- P1 release chỉ được tiếp tục sau khi preflight VPS đạt và staging scripts khớp hash.
- P2 load/soak/spike/stress chưa chạy.
- Không tắt máy: tiến trình chưa hoàn tất mục tiêu production-grade và service đang ổn
  định; tắt máy lúc này không phải rollback an toàn.
