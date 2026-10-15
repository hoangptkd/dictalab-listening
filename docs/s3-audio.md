# Lưu audio quản trị trên Amazon S3

**Cập nhật 15/10/2026:** production đã chuyển toàn bộ audio đang dùng, gồm static,
nội dung cá nhân và hội thoại, sang S3. Xem [triển khai toàn bộ audio](all-audio-s3-2026-10-15.md).
Phạm vi và cách phát 307 trong các phần lịch sử dưới đây mô tả luồng upload ban đầu;
production hiện stream cùng tên miền để giữ quyền truy cập và hỗ trợ `workers.dev`.
Đã dọn 57 file audio local và 79 audio trong JAR đang chạy; nguồn phát hiện là S3.
Backup và JAR staging của các lần triển khai trước được giữ để phục hồi.

## Phạm vi

- Bật S3 để các file MP3/M4A/WAV tải lên từ quản trị được lưu vào bucket riêng tư.
- Giữ kiểm tra định dạng thực tế, dung lượng và quyền ADMIN/CSRF khi tải lên.
- Cơ sở dữ liệu lưu `/media/s3/<uuid>.<extension>`, không lưu link có chữ ký hết hạn.
- Khi nghe, ứng dụng kiểm tra JWT cookie và phiên Redis, trả 307 tới link S3 có chữ ký.
  Link mặc định sống 30 phút; phản hồi chuyển hướng không được cache.
- File cũ `/media/<filename>` vẫn được phục vụ từ `DICTALAB_MEDIA_ROOT`.
  Chưa tự di chuyển hoặc xóa dữ liệu cũ. Không cần đổi schema cơ sở dữ liệu.
- Audio mẫu `/audio/**`, nội dung cá nhân `/private-media/**` và WAV hội thoại trong
  PostgreSQL không thuộc luồng upload này, chưa chuyển sang S3.
- Source reliability mới ghi intent upload bền vững trước S3 I/O, gắn reference trong
  giao dịch riêng; rollback giữ reference cũ và intent object mới để recovery.
  Cleanup chỉ xét object không còn reference sau cửa sổ retention, có lease/tombstone.
  Physical cleanup mặc định tắt, chỉ bật sau khi đã kiểm chứng backup/retention.
- Lỗi S3 trả 503; không âm thầm lưu vào ổ đĩa. Thư mục media vẫn cần quyền ghi để
  kiểm tra file tạm; file tạm được dọn sau upload thành công hoặc thất bại.
- S3 client reliability mới chỉ gửi một lần cho mỗi lệnh SDK, giữ deadline tổng 45s
  và deadline một lần gửi 15s. Không tự replay PUT khi kết quả không chắc chắn;
  retry DELETE thuộc durable cleanup/backoff, không có retry ẩn trong SDK.
  Lỗi tạm thời có thể trả 503 sớm hơn. Không có bảo đảm exactly-once hoặc chống
  duplicate upload ở cấp HTTP; request người dùng gửi lại có thể tạo identity mới.

## Cấu hình backend

```properties
DICTALAB_MEDIA_S3_ENABLED=true
DICTALAB_MEDIA_S3_BUCKET=YOUR_BUCKET
DICTALAB_MEDIA_S3_REGION=YOUR_REGION
DICTALAB_MEDIA_S3_PREFIX=dictalab/audio/
DICTALAB_MEDIA_S3_SIGNED_URL_DURATION=30m
```

Giá trị mặc định là tắt S3 để môi trường hiện tại tiếp tục chạy khi chưa có bucket.
Thiếu bucket/region khi bật S3 làm ứng dụng dừng khởi động với lỗi cấu hình rõ ràng.
Không đổi bucket/prefix khi đã có object: URL lưu trong DB được ánh xạ vào cấu hình này.
Không tắt S3 sau khi đã dùng nếu vẫn cần nghe các audio mới trên S3.

AWS SDK Java v2 dùng credential chain mặc định. Cấu hình IAM role hoặc AWS profile của
**tài khoản chạy dịch vụ**; VPS ngoài AWS có thể dùng `AWS_ACCESS_KEY_ID` và
`AWS_SECRET_ACCESS_KEY` trong môi trường dịch vụ. Credential tạm cần thêm
`AWS_SESSION_TOKEN`. Không đưa credential vào mã nguồn, frontend, Git hoặc chat.
Mẫu `.env.example` không tự nạp vào Windows service; cần cấu hình môi trường dịch vụ thực tế.
Tham khảo [AWS credential chain](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/credentials-chain.html).

## Bucket và IAM

Giữ toàn bộ **Block Public Access** bật; không đặt ACL public-read. Bật mã hóa SSE-S3
và cân nhắc Versioning để phục hồi việc thay/xóa nhầm. Ví dụ policy IAM tối thiểu cho
ứng dụng, thay `YOUR_BUCKET` bằng bucket thật:

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"],
    "Resource": "arn:aws:s3:::YOUR_BUCKET/dictalab/audio/*"
  }]
}
```

Không cần quyền tạo bucket, quản lý policy hay `s3:*` cho ứng dụng. Nếu dùng SSE-KMS,
cần cấu hình thêm quyền KMS phù hợp; policy trên dành cho SSE-S3.
Tham khảo [Block Public Access](https://docs.aws.amazon.com/AmazonS3/latest/userguide/access-control-block-public-access.html).

## CORS cho phát audio

Web Audio của dự án tải bytes bằng fetch, nên bucket cần CORS cho đúng origin trang web.
Thêm cấu hình sau trong S3 console (đổi origin khi dùng tên miền mới):

```json
[
  {
    "AllowedOrigins": ["https://160.191.244.27:18443"],
    "AllowedMethods": ["GET", "HEAD"],
    "AllowedHeaders": ["Range"],
    "ExposeHeaders": ["Content-Length", "Content-Range", "Accept-Ranges", "ETag"],
    "MaxAgeSeconds": 300
  }
]
```

CORS không cấp quyền đọc công khai. Link có chữ ký là quyền đọc tạm thời: người có link
có thể nghe tới khi hết hạn; đăng xuất không thu hồi ngay link đã cấp. Không ghi link ký
vào log hoặc analytics. TTL cấu hình được giới hạn từ 1 giây đến 1 giờ.
Tham khảo [S3 CORS](https://docs.aws.amazon.com/AmazonS3/latest/userguide/ManageCorsUsing.html)
và [link S3 có chữ ký](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/examples-s3-presign.html).

## Kiểm thử và kích hoạt

```powershell
mvn '-Ddictalab.build.directory=target/s3-audio' '-Dtest=S3MediaConfigTest,MediaStorageServiceTest,S3MediaLifecycleIntegrationTest,S3MediaIntegrationTest,AuthFlowIntegrationTest,AdminContentIntegrationTest,AdminWorkflowIntegrationTest' test
```

Các kiểm thử dùng S3 client mock, chữ ký SDK thật và real SDK client gọi HTTP loopback
với credential giả; không gọi AWS hay phát sinh phí. Bao phủ upload, link riêng tư,
JWT cookie, CSRF, rollback, dữ liệu cũ, file dùng chung, retry ẩn, response bị mất,
phục hồi sau lỗi và input độc hại. Loopback không chứng nhận IAM/bucket/restore thật.

Trước khi bật trên production: cấu hình bucket/IAM/CORS, build và kiểm tra sức khỏe,
tải một audio thật qua quản trị, xác nhận object đã có trên S3 và nghe được từ trình duyệt.
Đồng thời kiểm tra audio cũ vẫn phát, người chưa đăng nhập không lấy được link.
Kiểm thử local không thay thế bước này: chưa xác minh quyền IAM, CORS hoặc bucket thật.

Backup DB/media hiện có không sao lưu object S3. Cần kế hoạch bảo vệ bucket riêng,
giữ cấu hình bucket/prefix và credential khả dụng khi phục hồi. Nếu bật Versioning,
DELETE tạo delete marker; phiên bản cũ tiếp tục chiếm dung lượng cho đến khi được xử lý
theo chính sách lưu giữ. Không đặt lifecycle tự xóa object đang được bài học tham chiếu.

## Production ngày 04/10/2026

- Đã kích hoạt trên `https://160.191.244.27:18443`; bucket
  `dictalab-audio-20261004-6ac4f43c`, region `us-east-1`, prefix `dictalab/audio/`.
- Bucket chặn toàn bộ public access, ACL bị tắt, mã hóa SSE-S3; CORS chỉ cho origin
  production ở trên. Không bật versioning hoặc lifecycle tự xóa.
- IAM user riêng `dictalab-audio-vps`, không có console access, chỉ có policy inline
  `DictaLabAudioObjects` với ba quyền object ở trên; không có quyền liệt kê bucket.
- Khóa được chuyển trực tiếp qua SSH vào profile `dictalab-audio` tại
  `C:\ProgramData\DictaLab\auth\s3-credentials`. File chỉ cho SYSTEM và Administrators
  truy cập; NSSM dùng `AWS_SHARED_CREDENTIALS_FILE` và `AWS_PROFILE`. Không đưa khóa
  vào registry môi trường, chat, mã nguồn hoặc Git. Khi đổi khóa, cập nhật profile
  an toàn và khởi động lại dịch vụ; không xóa khóa đang dùng trước khi kiểm tra khóa mới.
- JAR production SHA-256:
  `DFAC955B6FC915DEF78AB5563DA12D24311AD9E5BF0AAE7B027E9C182453CC2F`.
- Backup trước triển khai: `C:\DictaLab\backups\dictalab-20261004-183752-374.dump`,
  kèm snapshot ba file media. Đã phục hồi thử và chạy cả JAR mới lẫn JAR cũ trên bản
  phục hồi riêng; không thay đổi dữ liệu production trong bước phục hồi thử.
- Môi trường trước S3 được giữ dưới dạng DPAPI tại
  `C:\ProgramData\DictaLab\auth\app-env-before-s3-20261004-183741-021.xml`;
  JAR cũ được giữ tại `C:\DictaLab\app\dictalab.previous.jar`.

Kiểm tra thực tế từ VPS đã đạt: Put/Get/Delete, bytes khớp, HTTP 206 + CORS cho URL ký,
HTTP 403 với URL không ký, từ chối đọc ngoài prefix và từ chối liệt kê bucket.
Luồng ứng dụng đã đạt: upload bằng quản trị, CSRF/role protection, DB lưu URL ổn định,
JWT cookie lấy redirect 307 no-store, bytes nghe lại khớp và audio cũ trả HTTP 206.
Chrome thực tế đã tải đủ 16.044 bytes, giải mã Web Audio thành một giây/mono; fetch
Range trả 206 và đọc được `Content-Range: bytes 0-3/16044` qua CORS.
Kiểm tra cuối xác nhận toàn bộ giá trị môi trường cũ được giữ nguyên, ACL file khóa
chỉ cấp quyền cho SYSTEM/Administrators, health UP và không còn tài khoản/bài học thử.
Kiểm thử JWT production cũng đạt: access hết hạn + CSRF cũ được phục hồi, refresh
xoay vòng và thao tác đang chờ tiếp tục được; CSRF và quyền quản trị vẫn được bảo vệ.
Script kiểm tra dùng tài khoản/bài học nháp/audio tổng hợp riêng rồi dọn chúng; không
thay hoặc xóa audio của người dùng.

Các kiểm tra có thể chạy lại trên VPS bằng `scripts/verify-s3-audio-vps.ps1` và
`scripts/S3AudioStorageCheck.java` (compile Java 17, dùng thư viện trong JAR và profile
dịch vụ). `-WaitForChrome` yêu cầu bước kiểm tra Chrome hoàn thành trong ba phút;
URL ký tạm chỉ được giữ dạng DPAPI trong thư mục auth, không ghi vào log.
`scripts/configure-s3-audio-vps.ps1` nhận khóa qua SSH stdin, từ chối ghi đè profile
đã tồn tại và sao lưu môi trường dịch vụ trước khi cấu hình.

Restore drill phân biệt `/media/s3/...` với file local và báo số tham chiếu S3 bên ngoài;
kết quả restore DB/media không xác nhận bucket đã được sao lưu hoặc phục hồi.
Ba file audio local hiện có chưa được chuyển lên S3. Audio quản trị tải lên mới sẽ dùng S3.

## Release gate local ngày 09/10/2026 — chưa triển khai VPS

`update-windows-vps.ps1` mới gọi `restore-drill-windows-vps.ps1 -CheckS3Only`
trước khi dừng dịch vụ. Kiểm tra chỉ đọc, có deadline, từ chối release nếu hai bảng
audio còn URL `/media/s3/...` hoặc catalog V27 còn trạng thái khác `DELETED`.
DB trước V27 vẫn được kiểm tra URL; lỗi kết nối/quyền/schema/marker cũng bị từ chối.
Khi smoke JAR mới/cũ, drill kiểm tra lại trên DB phục hồi để bắt dependency được
ghi sau live preflight. Không truy cập bucket hoặc suy diễn metadata thành backup.

Trade-off: bản updater này chặn cả release hợp lệ có sử dụng S3 cho đến khi có
object recovery gate riêng được triển khai và kiểm chứng. Không có cờ bỏ qua;
không xóa dữ liệu/catalog hoặc tắt S3 để ép qua gate. DB-only drill không có smoke
artifact vẫn chạy, nhưng xuất `RESTORE_EXTERNAL_S3_VERIFIED=FALSE` và không chứng
minh phục hồi đầy đủ. Tombstone không còn tham chiếu được phép qua; object mồ côi
ngoài catalog vẫn cần kiểm kê bucket trong kế hoạch recovery thật.

Bằng chứng local: [báo cáo S3 gate](reliability-evidence-2026-10-09-s3-gate.md).
Các xác nhận production ngày 04/10 ở trên là lịch sử, không phải kiểm tra lại
production trong batch 09/10 này.
