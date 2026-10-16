# Đăng nhập JWT và Redis

## Cơ chế

Website sử dụng JWT access token và refresh token dạng chuỗi ngẫu nhiên. Không tạo hoặc đọc
HttpSession để xác thực. Spring Security xác minh JWT; Redis giữ trạng thái phiên token,
PostgreSQL giữ tài khoản và `authVersion` để thu hồi quyền truy cập.

- Access token: HS256, mặc định 10 phút; kiểm tra issuer, audience `dictalab-web`, key ID,
  loại `access`, chữ ký và hạn dùng. Claims gồm `sub`, `sid`, `ver`, `iat`, `exp`, `jti`;
  không chứa email, mật khẩu, hash mật khẩu hoặc nội dung học tập.
- Refresh token: UUID phiên + 32 byte ngẫu nhiên; hạn tuyệt đối mặc định 7 ngày.
  Redis chỉ giữ SHA-256 của token, không giữ secret gốc.
- Cookie `DICTALAB_ACCESS`: HttpOnly, Path `/`. Cookie `DICTALAB_REFRESH`: HttpOnly,
  Path `/api/auth`. Cả hai SameSite=Lax; production HTTPS bắt buộc Secure.
- Giữ CSRF cho mọi thao tác ghi, gồm login/register/refresh/logout. Frontend nhận token CSRF
  từ `GET /api/auth/csrf`. Cookie CSRF có thể đọc bằng JavaScript, cookie đăng nhập thì không.
- Endpoint `POST /api/auth/refresh` trả 204 và đặt lại hai cookie. Refresh token cũ bị đánh dấu
  đã dùng, token mới thay thế nguyên tử bằng Lua. Hạn refresh ban đầu không được kéo dài.
- Dùng lại token đã đổi sẽ thu hồi toàn bộ phiên token; chuỗi ngẫu nhiên sai nhưng có cùng UUID
  không đủ để thu hồi một phiên hợp lệ. Mất phản hồi refresh có thể yêu cầu đăng nhập lại;
  không tự gửi lại refresh khi lỗi mạng hoặc timeout.

Redis kiểm tra phiên cho mỗi yêu cầu đã xác thực; hệ thống không hoạt động theo JWT hoàn toàn
không lưu trạng thái. Namespace riêng `{dictalab-auth}:v1:` không đụng bộ đếm rate limit.
Giới hạn mặc định: 20.000 phiên toàn hệ thống, 10 phiên mỗi tài khoản, 2.048 lượt rotation mỗi phiên.
Các key có TTL đến hạn refresh; index giữ cùng hạn và dọn thành viên hết hạn khi tạo phiên.
Metadata kiểm tra quyền không tải danh sách các token đã dùng.
Trần số phiên không bảo đảm đủ bộ nhớ cho mọi mức rotation. Theo dõi Redis memory/503 và điều chỉnh
trần phiên hoặc dung lượng trước khi mở rộng tải; cấu hình Redis local 128 MiB chỉ phục vụ chạy thử.

## Đăng xuất và quyền tài khoản

`POST /api/auth/logout` xác minh refresh secret, thu hồi phiên Redis hiện tại, xóa cookie;
endpoint này vẫn hoạt động khi access token đã hết hạn. `logout-all`, đổi/reset mật khẩu,
đổi email/quyền và khóa tài khoản dùng trạng thái/`authVersion` hiện có trong PostgreSQL.
Cả access và refresh cũ bị từ chối. Bản ghi Redis cũ tự hết hạn; lần đăng nhập mới dọn các phiên
cùng tài khoản có `authVersion` cũ trước khi kiểm tra giới hạn phiên.
Đăng nhập lại trong cùng trình duyệt thu hồi phiên refresh đang được trình duyệt gửi để tránh
để lại các phiên không còn cookie sử dụng; các thiết bị khác vẫn giữ phiên riêng.

Redis/DB không khả dụng: trả 503 và Retry-After, không cấp cookie mới hoặc bỏ qua xác thực.
Frontend giữ cookie khi lỗi hạ tầng. Redis mất key phiên: trả 401, cần đăng nhập lại.
Đăng ký có thể đã commit tài khoản trước khi cấp token thất bại; khi Redis phục hồi, đăng nhập
bằng tài khoản vừa tạo thay vì đăng ký lại.

## Frontend, quản trị và Room

Frontend tự refresh khi nhận 401, chỉ gửi lại yêu cầu bị từ chối xác thực một lần.
Khi CSRF không còn khớp cookie, máy chủ trả 403 kèm `X-CSRF-Error: 1` trước khi chạy
nghiệp vụ. Frontend lấy CSRF mới và thử lại một lần; áp dụng cả cho yêu cầu refresh.
Thao tác tiếp tục trên màn hình hiện tại, giữ nội dung đang làm khi refresh còn hợp lệ.
Không tự refresh hoặc gửi lại lỗi 403 thiếu quyền thật. JWT trong cookie vẫn bắt buộc
kiểm tra CSRF cho thao tác ghi, không dùng ngoại lệ CSRF mặc định dành cho bearer token.
Không gửi lại thao tác ghi sau lỗi mạng hoặc 503. Web Locks phối hợp refresh, login/register/logout
giữa các tab; không lưu access/refresh token trong localStorage/sessionStorage hoặc URL.
Yêu cầu trình duyệt có Web Locks trong secure context (HTTPS hoặc localhost). Trình duyệt cũ
vẫn đăng nhập được nhưng cần đăng nhập lại khi access hết hạn; không thử refresh cạnh tranh.

Trang admin hết access sẽ chuyển tới login với `next` nội bộ. Nếu refresh còn hợp lệ, frontend
khôi phục phiên rồi trở về trang admin. Audio tải bằng API/fetch hỗ trợ refresh; audio quản trị
có một lần tải lại sau khi kiểm tra phiên. Không thay đổi quyền admin/media riêng.

WebSocket xác thực bằng access cookie của handshake, giữ kiểm tra Origin và quyền thành viên.
Kiểm tra token hết hạn/thu hồi và trạng thái tài khoản trên heartbeat và lịch kiểm tra 20 giây,
kể cả socket không gửi message. Khi access cũ hết hạn, frontend xác thực lại và reconnect;
không gửi token qua query string. Room state và media vẫn cần thiết kế riêng nếu chạy nhiều backend.

## Cấu hình và chạy local

`.env` local hiện tại đã được bổ sung khóa ngẫu nhiên riêng; không đưa khóa này lên Git hoặc tài liệu.
Môi trường mới cần tự tạo khóa Base64 từ ít nhất 32 byte ngẫu nhiên và đặt `JWT_SIGNING_KEY`.
Không dùng khóa cố định trong test cho môi trường chạy thật. Có thể tạo khóa bằng PowerShell:

```powershell
$jwtBytes = [byte[]]::new(64)
[System.Security.Cryptography.RandomNumberGenerator]::Fill($jwtBytes)
[Convert]::ToBase64String($jwtBytes)
```

Lưu giá trị vào secret của môi trường, không ghi vào log. Các biến cấu hình nằm trong `.env.example`.
`JWT_COOKIE_SECURE=false` chỉ dành cho HTTP localhost; production đặt `true` và sử dụng HTTPS.
Khóa thiếu/ngắn/sai Base64 làm backend từ chối khởi động, không tự sinh khóa mỗi lần restart.

Dừng bản ứng dụng cũ, mở Docker Desktop rồi chạy:

```powershell
pwsh -File scripts/run-local.ps1
# Bật thêm Redis rate limit và RabbitMQ Listening:
pwsh -File scripts/run-local.ps1 -WithMessaging
```

Redis là thành phần bắt buộc cho đăng nhập JWT, kể cả khi rate limit vẫn dùng RAM hoặc RabbitMQ tắt.
Script tự khởi động Redis local khi dùng host localhost và không cung cấp REDIS_URL riêng.
Health Redis tham gia core health. Redis local vẫn bind loopback, AOF và noeviction; production
cần kết nối có xác thực/TLS nếu qua mạng và theo dõi bộ nhớ, không công khai cổng Redis.

## Xoay khóa, phát hành và quay lui

HS256 phù hợp backend cùng một hệ thống; mọi backend có secret đều có khả năng ký token.
Mỗi môi trường dùng khóa riêng, giữ cùng issuer/key ID/config giữa các backend trong môi trường đó.

1. Chuẩn bị khóa mới và key ID mới. Phân phối cấu hình chấp nhận cả khóa cũ/mới trước khi
   thay khóa ký của các backend; tránh backend cũ từ chối access do backend mới cấp.
2. Đặt `JWT_SIGNING_KEY`/`JWT_KEY_ID` cho khóa ký hiện tại; `JWT_PREVIOUS_SIGNING_KEY`/
   `JWT_PREVIOUS_KEY_ID` cho khóa còn được chấp nhận. Hai key ID phải khác nhau.
3. Sau khi tất cả backend ký bằng khóa mới và access khóa cũ đã hết hạn, bỏ khóa previous.
   Refresh vẫn hoạt động và cấp access khóa mới. Nếu khóa bị lộ, loại ngay khóa bị lộ;
   thu hồi phiên liên quan theo quy trình sự cố, không chờ đủ thời gian overlap.

Không cần migration SQL mới cho bước chuyển đổi này. Khi thay bản ứng dụng, người dùng có
JSESSIONID cũ cần đăng nhập lại; cookie cũ không thể xác thực với bản JWT.
Quay lui cần dừng bản JWT trước, triển khai lại bản session và yêu cầu đăng nhập lại.
Giữ dữ liệu học tập và không khôi phục token đã thu hồi từ snapshot Redis cũ. Khi phục hồi
snapshot auth, cần vô hiệu trạng thái token cũ có kiểm soát trước khi mở xác thực lại.
Tích hợp local không đồng nghĩa đã triển khai production; script deploy hiện có cần được cấu hình
khóa JWT và Redis trên máy đích trước lần phát hành tiếp theo.

## Kiểm thử

```powershell
mvn --batch-mode --no-transfer-progress test
node --test src/test/js/*.test.cjs
pwsh -File scripts/test-messaging.ps1
```

Suite thông thường dùng mock store cho đăng ký và không yêu cầu Redis để kiểm tra nghiệp vụ.
Suite messaging dùng PostgreSQL, Redis và RabbitMQ thật trong project Compose ngẫu nhiên;
kiểm tra JWT cookie, TTL, CSRF, rotation, replay, refresh đồng thời, logout, logout-all,
đổi mật khẩu/khóa tài khoản, mất phiên/lỗi store, WebSocket thật và hồi quy queue/rate limit.
Sau test chỉ xóa hạ tầng/dữ liệu thử của project đó; báo cáo kiểm thử được giữ trong `target`.

Kết quả local 04/10/2026: suite backend 195 bài đạt, 20 bài tùy chọn bỏ qua; frontend 93/93 đạt.
Suite xác thực cuối 25/25 đạt trên PostgreSQL 18.4 và Redis 8.0.5 thật, gồm 11 ca JWT/Redis và
handshake WebSocket thật. Báo cáo: `target/jwt-final/surefire-reports` và
`target/jwt-verified/surefire-reports`. UTF-8 và cú pháp JavaScript/PowerShell đã kiểm tra.
Docker Desktop gặp lỗi khởi động `dockerInference`, nên kiểm thử cuối dùng Redis tạm từ gói Ubuntu
được giải nén trong `target` và cluster PostgreSQL native riêng bằng `scripts/test-postgres.ps1`.
Không cài gói hệ thống mới; dịch vụ thử đã dừng, dữ liệu/log thử được giữ để đối chiếu.
Để chạy bằng Compose cần khắc phục Docker Desktop; không reset/xóa dữ liệu Docker trong bước tích hợp.

## Chuẩn bị phát hành GitHub — 04/10/2026

Bản source phát hành được chuẩn bị trên nhánh `codex/jwt-redis-production`, chưa vào `main`
để tránh workflow tự triển khai khi VPS chưa có Redis và khóa JWT. Đóng gói lại thành công:
195 kiểm thử backend đạt, 21 kiểm thử tùy chọn bỏ qua; frontend 93/93 đạt.
Artifact và báo cáo local nằm trong `target/github-production-20261004/`.

Kiểm tra VPS: DictaLab Running, health UP, schema V24. Chưa có dịch vụ Redis/RabbitMQ hoặc
cấu hình JWT; WSL/LxssManager ở trạng thái StopPending. Không thay JAR, sửa dữ liệu production,
cài thêm dịch vụ hoặc khởi động lại VPS trong lượt kiểm tra này. Cần quyết định hạ tầng Redis
production trước khi phát hành JWT; không dùng bản Redis Windows không được hỗ trợ hoặc
bỏ kiểm tra phiên để vượt qua điều kiện này. Các ứng dụng khác trên VPS được giữ hoạt động.

## Redis cloud đã cấu hình — 04/10/2026

Đã chuyển phương án hạ tầng sang Redis cloud do người dùng cung cấp, không dùng WSL hoặc
Memurai. Java 17 trên VPS xác minh chứng chỉ TLS 1.3, AUTH/PING và Lua/hash/index/TTL đạt.
Redis 7.2.15 có giới hạn 200 MiB, `noeviction`, 0 key bị eviction tại lần kiểm tra; AOF đang tắt.
Chưa xác minh lịch snapshot hoặc cam kết độ bền của nhà cung cấp. Không phục hồi snapshot
token cũ để khôi phục các phiên đã thu hồi. Quota này không chứng minh đủ bộ nhớ cho mọi
giới hạn phiên/rotation mặc định; cần theo dõi memory và 503 trước khi mở rộng.

`scripts/configure-redis-cloud-vps.ps1` đọc URL từ SecureString DPAPI tại
`C:\ProgramData\DictaLab\auth\redis-url.xml`, giữ cấu hình cũ và tạo khóa JWT production riêng.
`-CheckOnly` kiểm tra cấu hình mà không ghi. Thư mục auth giới hạn Administrators/SYSTEM;
URL và khóa không nằm trong Git. Script đã áp dụng môi trường dịch vụ: TLS Redis, JWT cookie
Secure, Redis rate limit, timeout Redis 2 giây/connect 3 giây; RabbitMQ giữ tắt.
Các biến môi trường không thuộc phạm vi thay đổi đã được đối chiếu và giữ nguyên.

Ở bước chuẩn bị, đây là **cấu hình chờ phát hành**, chưa kích hoạt JWT trên website. Artifact candidate
SHA256 `B169C885275FDD2AE53D9F8A2A1A0866F36D3B690B80716BCE032A5A36C2C041`
đã upload; 24 migration đã áp dụng khớp byte với JAR production hiện tại. 16 kiểm tra dữ liệu
trước phát hành đều có 0 lỗi. Production vẫn Running/UP, JAR `E39A4210…`, schema V24.

VPS chỉ còn 73 MiB RAM khả dụng ở mẫu kiểm tra trước; MoreLogin chiếm hơn 2 GiB. Chưa dừng ứng dụng
hoặc các ứng dụng khác. Release guard mới yêu cầu ít nhất 1.000 MiB dự kiến sau khi dừng
DictaLab, rồi kiểm tra lại sau khi dừng. Nhánh chặn đã được thực chạy với ngưỡng thử 16.384 MiB
trên VPS 7 GiB: từ chối trước khi dừng, production vẫn UP và checksum JAR giữ nguyên.
Người dùng sau đó đồng ý tạm đóng MoreLogin nếu cần để tiếp tục phát hành.

Restore drill mới dành riêng Redis database 15 và khóa JWT ngẫu nhiên khác production;
đã kiểm tra database 15 trống và khả năng SELECT trước khi dùng. Không dùng database này
cho ứng dụng khác. Drill đăng xuất token thử trước cleanup, chạy candidate rồi JAR cũ trên
cùng DB PostgreSQL đã migrate; log mỗi artifact được giữ riêng. Tại bước chuẩn bị, quy trình
chưa được thực chạy sau sửa vì thiếu RAM; kết quả thực chạy được ghi bên dưới.
Frontend kiểm thử lại 93/93 đạt; cú pháp PowerShell và UTF-8 của script đạt.

## Đã triển khai production — 04/10/2026

RAM phục hồi lên khoảng 2,3 GiB trước phát hành, nên không cần đóng MoreLogin. Sau phát hành
còn khoảng 2,0 GiB. MoreLogin vẫn có 8 tiến trình; WebLens và BilingualBackend vẫn Running.
Không tắt hoặc khởi động lại VPS, không dùng WSL và không cài Memurai.

Lần thử đầu bị chặn trong restore drill: Spring Boot 4.1.1/Lettuce nhận cổng `-1` khi URL
`rediss` không ghi cổng, dẫn đến `redisConnectionFactory: Port out of range: -1`.
Production tự khôi phục JAR cũ và health UP; schema production chưa thay đổi trong lần thử này.
Đã sửa script cấu hình và restore để thêm cổng mặc định 6379 nếu thiếu, giữ nguyên cổng
được chỉ định. Không đổi endpoint, mật khẩu, khóa JWT hoặc cấu hình bảo mật. Cú pháp,
kiểm tra cổng mặc định/cổng riêng và UTF-8 đều đạt. Restore drill thực chạy lại đạt.

- Sao lưu nhất quán khi ứng dụng dừng: `dictalab-20261004-111359-004.dump`, 7.187.309 byte;
  SHA256 `D0DD6A74E4C16C5F3553EA3A6AB0A6975C656B39AE696412F7E139178EF0BB6C`.
  Cả 3 media có checksum hợp lệ và 3 tham chiếu DB/media được xác minh.
- Restore drill `dictalab_restore_20261004111401` đạt trong 121,65 giây. Candidate và
  JAR cũ đều đăng ký, tải catalog, bắt đầu luyện tập và đăng xuất thành công trên cùng
  PostgreSQL đã migrate V25. Logs mỗi artifact được giữ trong `C:\DictaLab\backups`.
- Production đã chạy JAR SHA256
  `B169C885275FDD2AE53D9F8A2A1A0866F36D3B690B80716BCE032A5A36C2C041`;
  DictaLab/HTTPS/PostgreSQL Running, health localhost và HTTPS công khai UP.
- Schema V25, 0 migration lỗi, 0 FK không hợp lệ. Giữ nguyên 7 tài khoản và 37 phiên
  luyện tập sau khi dọn tài khoản thử. Không còn DB restore hoặc JVM restore tạm.
- Smoke trực tiếp qua HTTPS công khai đạt: route riêng từ chối khách, cookie access/refresh
  HttpOnly/Secure/SameSite=Lax, access 600 giây, refresh đổi token cùng phiên. Redis thực
  giữ đúng SHA-256 của refresh mới, TTL dương, rotation=1 và index rate limit có dữ liệu.
  Logout xóa key phiên Redis và access cũ bị từ chối 401. Chỉ tài khoản thử có email UUID
  riêng do lượt kiểm tra này tạo được xóa; không tạo/xóa dữ liệu luyện tập production.
- Redis database 15 trống sau drill; không FLUSH hoặc xóa key của ứng dụng khác.
  Biến môi trường không thuộc phạm vi thay đổi được đối chiếu với bản sao trước cấu hình
  và giữ nguyên. Redis rate limit đã bật; RabbitMQ vẫn tắt vì chưa được provision.
- Frontend chạy lại 93/93 đạt. Không sửa mã backend hoặc đóng gói lại JAR sau bản đã có
  195 backend test đạt/21 optional skipped; thay đổi bổ sung chỉ là script và tài liệu.

Website: `https://160.191.244.27:18443/`. Người dùng có phiên JSESSIONID cũ cần đăng nhập lại.
Access mặc định 10 phút, refresh hạn tuyệt đối 7 ngày. Redis cloud vẫn 200 MiB/noeviction,
0 eviction tại hậu kiểm; AOF tắt và lịch snapshot nhà cung cấp chưa xác minh.
Mất trạng thái Redis sẽ yêu cầu đăng nhập lại; không khôi phục token đã thu hồi từ snapshot.

Kiểm tra có thể chạy lại: `scripts/update-windows-vps.ps1` đã chứa gate khôi phục/candidate/
compatibility. Smoke và hậu kiểm của lượt phát hành được giữ tại
`target/redis-cloud-release/production-auth-smoke.ps1`, `postdeploy-check.ps1` và
`RedisCloudProbe.java`; bản chạy trên VPS ở `C:\DictaLab\staging\redis-cloud`.
Smoke chỉ đọc secret DPAPI trên VPS, không chứa mật khẩu/token production trong source/log.
Source và cập nhật vận hành được push nhánh `codex/jwt-redis-production`; chưa merge `main`
để tránh kích hoạt thêm một lượt deploy tự động không cần thiết.

## Phát hành sửa duy trì thao tác — 04/10/2026

Đã triển khai bản phục hồi JWT/CSRF lên `https://160.191.244.27:18443/`.
JAR production có SHA-256 `FAF3EF2DEDE7D192512C727DCA4E02F1CE4EB4DCD63D0633EF58991CFA872652`.
Bản đã qua 102 kiểm thử frontend và 198 kiểm thử backend; 31 kiểm thử tùy chọn bỏ qua.
Checksum byte JavaScript qua HTTPS khớp source đã kiểm thử. Dịch vụ Running,
health nội bộ và HTTPS đều UP; cấu hình dịch vụ và khóa JWT được giữ nguyên.

Đã sao lưu nhất quán DB/media vào `C:\DictaLab\backups\dictalab-20261004-171719-412.dump`
và snapshot media cùng tên. Khôi phục thử thành công; cả JAR mới và JAR trước đều
đăng nhập và tạo phiên luyện tập được trên bản sao. JAR trước được giữ tại
`C:\DictaLab\app\dictalab.previous.jar`.

Kiểm tra HTTPS với tài khoản thử riêng xác nhận: cookie JWT vẫn yêu cầu CSRF;
thiếu quyền thật trả 403 không có dấu phục hồi CSRF; giả lập hết access cookie và
CSRF cũ vẫn refresh được rồi lưu thao tác chờ. Logout thành công, tài khoản thử
đã dọn. Hậu kiểm: schema V25, 7 tài khoản, 39 phiên luyện tập, 0 migration lỗi,
0 khóa ngoại hỏng, 0 database phục hồi còn lại; Redis database 15 trống.
Kịch bản smoke local: `target/session-recovery/production-session-recovery-smoke.ps1`.
Log kích hoạt VPS: `C:\DictaLab\staging\jwt-session-20261004-88599e3d\activation.log`.
Tab đã mở trước khi phát hành cần tải lại mã trang một lần để dùng luồng mới.
