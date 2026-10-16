# Dictation và audio production load — 12/10/2026

Đã hoàn tất 50 → 75 → 100 VU, 120 giây mỗi mức. Mức cao nhất đạt toàn bộ tiêu chí của đợt này: **50 VU / 120 giây**, audio p95 đã sát ngưỡng. Mức cao nhất đã chạy: **100 VU / 120 giây**, không lỗi nhưng không đạt latency SLO từng route và audio budget. Chưa chứng minh 100 VU / 30 phút với workload này; không gọi đây là công suất tối đa tuyệt đối.

## Phạm vi

- Production HTTPS `184.194.89.90`, VPS 1 GB RAM / 2 vCPU, Hikari pool 10. Generator Windows bên ngoài VPS; không thay code ứng dụng, cấu hình JVM, database hoặc gói dịch vụ.
- 100% VU luyện dictation, tài khoản synthetic độc lập: catalog A1/A2/B1/B2 → start/resume → STARTED → tải audio thật → chờ đúng độ dài đoạn → COMPLETED → một word check → reveal sentence → replay đủ đoạn → complete/advance → history. Think 2 giây; một câu mỗi journey. Không mô phỏng gõ đủ mọi từ hoặc toàn bài trong một iteration; có kiểm tra chuyển câu hoặc lesson result khi đến câu cuối.
- Frontend dùng Web Audio: tải cả file, decode, cache LRU 8 file mỗi người dùng. k6 cũng tải cả file một lần khi chưa cache; replay cache hit không tạo GET mới. Cache chỉ trong VU của từng đợt, không chia sẻ giữa các VU hoặc các mức tải.
- Kiểm tra HTTP 200, content type và magic bytes MP3/WAV/M4A/Ogg/FLAC. Không chỉ HEAD hoặc giả lập sleep mà bỏ GET. Bytes/audio readiness được ghi riêng. k6 không giải mã audio, không phát ra loa, không kiểm chứng browser CORS/codec/âm thanh nghe được.
- Inventory PostgreSQL read-only: 23 public lessons, 946 sentences, 82 distinct audio files; 79 file `/audio/...` (910 sentences), 3 file media khác (36 sentences). Không mặc định toàn bộ audio được tải trực tiếp từ S3. Inventory published gồm nội dung private không dùng trong workload: 59 lessons/2.200 sentences, không thiếu audio/segment; đoạn dài 0,591–19,247 giây.
- Chỉ dùng recorded audio/nội dung hiện có. Không gọi endpoint AI/coach/conversation/generate; check/hint/scoring là đường xử lý local của source hiện tại. Không bật RabbitMQ hoặc AI providers để test.
- API SLO giữ nguyên: aggregate p95 < 1.000 ms / p99 < 2.000 ms và từng 11 route p95 < 1.000 ms. Audio transfer budget cho đợt này khai báo riêng p95 < 3.000 ms / p99 < 5.000 ms, audio validation errors 0%. Đây chưa phải SLO audio đã được người dùng nghiệm thu từ trước. Audio-ready đo lượt download khi cache miss; cache hit không tạo mẫu readiness mới. HTTP/business errors < 1%, không OOM/restart/DB timeout mới; đầy đủ logout và invariants.

## Chuẩn bị và smoke

- Tái sử dụng runner, JWT/CSRF client và sampler hiện có; thêm workload `dictation-audio`, không sửa business code production. Lỗi audio không retry; lỗi request ghi không retry ngoài explicit pre-controller CSRF rejection đã có.
- 24 test local liên quan pass: 12 workload/report tests và 12 load-contract tests. Evidence `dict-audio-local-tests.tap`; kiểm tra UTF-8 cả 7 file code/test/report pass.
- Smoke 1 VU / 20 giây pass k6 và strict work SLO: API p95/p99 488,38/515,57 ms, 19 work API requests. Trong cửa sổ đo: 2 audio downloads / 479.614 bytes, 2 cache hits, 3 playbacks, 1 sentence complete; audio-ready p95/p99 1.207,50/1.216,70 ms, audio/HTTP errors 0. Cả 2 journey hoàn tất sau grace; không cộng hoạt động grace vào work counters.
- Smoke dùng label `dict-audio-smoke-1`, evidence trong `target/production-load-20261011`. Raw auth/fixture và stderr có ACL riêng; không chia sẻ signed URL/token/credentials.

## Kết quả tải

Mỗi đợt có manifest xác nhận hash artifact, pool thực tế và hash workload. Không so RPS trực tiếp với workload trước (80% reads/20% dictation, sleep 3 giây giả lập, không tải audio). API RPS dưới đây chỉ gồm `phase=work`; audio GET và cleanup được đo riêng. Cả ba mức giữ đủ VU liên tục trong 120 mẫu một giây. Setup và grace/cleanup không cộng vào cửa sổ 120 giây.

| VU / thời gian | API RPS | API p95/p99 ms | Audio-ready p95/p99 ms | API/audio errors | Kết luận |
|---|---:|---:|---:|---:|---|
| 50 / 120s | 43,76 | 616,50 / 929,16 | 2.980 / 4.116,80 | 0% / 0% | Pass API và audio budget |
| 75 / 120s | 59,75 | 668,92 / 1.056,67 | 5.039,70 / 9.743,26 | 0% / 0% | Fail resume p95 và audio budget |
| 100 / 120s | 68,84 | 989,77 / 1.418,18 | 4.701,60 / 8.317,64 | 0% / 0% | Fail 4 route p95 và audio budget |

Business errors, audio validation errors và interrupted journeys đều 0 ở cả ba mức. k6 exit lần lượt 0 / 99 / 99; exit 99 do vượt latency thresholds, không phải crash generator. Audio 100 VU nhanh hơn 75 VU ở tail trong một lần đo không chứng minh tải cao hơn nhanh hơn: file mix, cache và mạng có biến động.

| Khối lượng trong work window | 50 VU | 75 VU | 100 VU |
|---|---:|---:|---:|
| API requests | 5.251 | 7.170 | 8.261 |
| Audio downloads | 461 | 650 | 769 |
| Audio bytes | 225.390.236 | 326.885.556 | 409.174.605 |
| Payload audio trung bình, Mbps | 15,03 | 21,79 | 27,28 |
| Audio cache hits | 501 | 664 | 735 |
| Playback segments hoàn tất | 939 | 1.282 | 1.469 |
| Sentence complete | 457 | 617 | 705 |
| Journey hoàn tất, gồm grace | 499/499 | 687/687 | 790/790 |
| Logout thành công | 50/50 | 75/75 | 100/100 |

Các counter kết thúc ở thời điểm khác nhau: GET/playback/complete/history có thể nằm hai phía biên work window. Không coi chênh counter tại biên là mất request. Số Mbps là payload bytes / 120 giây, không phải phép đo NIC hoặc giới hạn bandwidth VPS.

## Latency từng API

SLO từng route là p95 < 1.000 ms. Aggregate p95 pass không thay thế gate từng route.

| Route, p95 ms | 50 VU | 75 VU | 100 VU |
|---|---:|---:|---:|
| catalog | 739,55 | 517,62 | 759,62 |
| start | 938,54 | 925,07 | **1.147,14** |
| resume | 750,00 | **1.031,42** | **1.377,79** |
| play STARTED | 664,81 | 938,70 | **1.493,38** |
| play COMPLETED | 475,25 | 490,48 | 807,36 |
| word check | 557,23 | 534,39 | 855,74 |
| hint / reveal | 715,09 | 788,73 | **1.054,13** |
| replay STARTED | 483,46 | 503,95 | 845,60 |
| replay COMPLETED | 428,05 | 493,65 | 864,70 |
| complete | 519,15 | 594,74 | 909,24 |
| history | 322,03 | 418,87 | 759,33 |

100 VU: riêng play STARTED p99 2.785,93 ms; p99 tổng 1.418,18 ms che khuất tail của route này. Trường hợp auth refresh phát sinh được tính vào work API metrics, không loại khỏi độ trễ.

## Tài nguyên và dependency

Các delta lấy giữa các mẫu trong cửa sổ tải; CPU bỏ tick đầu chưa đủ measurement interval. PostgreSQL probe khoảng 15 giây, Redis khoảng 30 giây; không coi probe thưa là quan sát mọi thời điểm.

| Chỉ số | 50 VU | 75 VU | 100 VU |
|---|---:|---:|---:|
| VPS CPU trung bình / tối đa, % | 20,97 / 38,83 | 27,24 / 43,83 | 31,32 / 62,05 |
| App CPU tối đa, % toàn 2 vCPU | 27,26 | 27,78 | 39,21 |
| CPU steal tối đa, % | 19,42 | 23,26 | 40,58 |
| RAM khả dụng tối thiểu, MiB | 188,02 | 175,02 | 163,18 |
| App RSS tối đa, MiB | 387,21 | 389,95 | 394,16 |
| Heap used tối đa / heap max, MiB | 179,64 / 371,25 | 179,66 / 371,25 | 177,66 / 371,25 |
| Swap used tối đa, MiB | 511,98 | 511,77 | 511,69 |
| Swap-in pages delta | 4.847 | 491 | 741 |
| GC pause tổng delta, giây / count | 0,347 / 25 | 0,253 / 34 | 0,460 / 39 |
| Hikari active / pending tối đa | 10 / 19 | 10 / 30 | 10 / 30 |
| Hikari acquire trung bình, ms | 4,60 | 14,08 | 105,55 |
| Hikari usage trung bình, ms | 68,92 | 68,74 | 69,95 |
| Connection occupied trung bình xấp xỉ / pool 10 | 6,10 | 8,29 | 9,69 |
| Auth PostgreSQL lookup trung bình, ms | 19,61 | 30,28 | 133,37 |
| Auth Redis lookup trung bình, ms | 2,24 | 2,21 | 2,32 |
| Catalog summary / preview trung bình, ms | 14,69 / 14,11 | 14,63 / 14,21 | 14,44 / 14,14 |
| Redis ping p95, ms / probe failures | 1,62 / 0 | 1,54 / 0 | 2,05 / 0 |
| DB timeout / OOM / restart mới | 0 / 0 / 0 | 0 / 0 / 0 | 0 / 0 / 0 |
| PG lock waiters / deadlock delta / rollback delta | 0 / 0 / 0 | 0 / 0 / 0 | 0 / 0 / 0 |
| Generator CPU tối đa, % | 42 | 31 | 22 |
| Generator RSS tối đa / RAM khả dụng min, MiB | 272,56 / 7.990 | 356,18 / 7.036 | 416,58 / 7.501 |

Connection occupied xấp xỉ = Hikari usage-seconds delta / telemetry-seconds, không phải PostgreSQL CPU. PG sessions tối đa 10; idle-in-transaction có lúc 9 / 8 / 10 trong các probe nhưng về 0 sau mỗi đợt. Không quan sát lock waiters; các transaction còn giữ connection giữa round trips vẫn cần kiểm tra tiếp. Counter DB timeout ban đầu là 8 từ chiến dịch cũ và cuối vẫn 8: không tính 8 đó là lỗi mới.

RabbitMQ: cả `DICTALAB_AI_QUEUE_ENABLED` và `DICTALAB_LISTENING_QUEUE_ENABLED` đang false. Workload này không test queue throughput/recovery. Provider inflight trong các mẫu đều 0; source/workload không có endpoint AI, nhưng đây không phải audit billing provider cho các tác vụ ngoài test.

## Bottleneck và hướng kiểm chứng tiếp

1. **Pool occupancy là bằng chứng mạnh nhất cho API bottleneck ở 100 VU.** Usage/connection vẫn khoảng 69 ms, nhưng occupied tăng gần 9,69/10 và acquire trung bình từ 4,60 lên 105,55 ms. Auth PostgreSQL lookup cũng tăng 19,61 → 133,37 ms, trong khi Redis/catalog reads ổn định. Không đủ bằng chứng kết luận phải nâng Neon; cần trace SQL/transaction hold time và giảm round trips/giữ connection trước khi chỉ tăng pool.
2. **Nhận nội dung file chiếm phần lớn thời gian audio.** Receiving trung bình / tổng download lần lượt 1.160,82/1.413,85 ms; 1.530,36/1.800,37 ms; 1.466,22/1.839,20 ms. 100 VU còn có audio waiting p95 1.496,08 ms. Cần đối chứng từ generator thứ hai và đo origin/CDN/network trước khi quy riêng cho VPS hoặc Internet máy test. Băng thông, cách phục vụ static audio và cache/preload là hướng tối ưu cần đo; chưa thay gì trong đợt này.
3. **CPU steal là yếu tố nhiễu đáng chú ý**, cao nhất 40,58% ở 100 VU; không phải toàn bộ CPU đó do Java sử dụng. Chưa có CPU saturation/OOM/GC pause lớn như đợt soak cũ trong 120 giây này. RAM/swap vẫn chật; không suy ra chạy 30 phút sẽ ổn.

Không tăng pool, sửa JVM, nâng gói hoặc deploy tối ưu trong đợt test này. Kết quả này chỉ ra nơi cần đo/sửa tiếp, không xác nhận một thay đổi chưa thực hiện sẽ giải quyết được vấn đề.

## Kiểm tra dữ liệu và trạng thái cuối

Sau 50, 75, 100 VU và kiểm tra cuối, sáu invariants đều 0: `duplicateActiveSolo`, `wordEventMismatch`, `replayCounterMismatch`, `revealCounterMismatch`, `completedDurationMismatch`, `incompleteCompletedLesson`. Idle-in-transaction và lock waiters sau test đều 0. Fixture có 100 tài khoản; session count từ baseline 420 lên 1.184, sentence attempts 7.075 lên 9.809, word events 6.836 lên 8.812. Dữ liệu synthetic được giữ, không xóa dữ liệu production.

Cuối đợt: health `UP`, PID 102403, restart 0, Hikari active/pending 0/0, pool 10, provider inflight 0. Artifact SHA256 vẫn `df703246ff9db6a4670a90fbdc86ede9e8988243cc283a2fdb8d9a2bb57249e1` ở cả ba manifest và kiểm tra cuối.

## Evidence và chạy lại

Evidence nằm trong `target/production-load-20261011` theo prefix `dict-audio-20261012-50`, `-75`, `-100`: `.manifest.json`, `.result.json`, `.summary.json`, `.analysis.json`, `.audio-timing.json`, `.raw.json.gz`, `.server.jsonl`, `.generator.json`. Invariants: `dict-audio-baseline-invariants.json`, `dict-audio-after50-invariants.json`, `dict-audio-after75-invariants.json`, `dict-audio-after100-invariants.json`, `dict-audio-final-invariants.json`. Trạng thái cuối: `dict-audio-final-runtime.txt`. Không chia sẻ `.private-auth.json`, `private-fixture.json` hoặc raw stderr có thể chứa signed URL.

Local changes chỉ gồm workload audio, nhánh chọn workload trong runner/production script, phân tích audio work window, test và báo cáo. Tái sử dụng code JWT/CSRF hiện có, không thêm dependency (Ponytail); kiểm tra UTF-8 bảo toàn tiếng Việt.

```powershell
node --test src/test/js/dictation-audio-load.test.cjs src/test/js/production-load.test.cjs src/test/js/load-contracts.test.cjs
# Production rerun là request ghi dữ liệu; dùng label mới để không ghi đè evidence.
pwsh -NoProfile -File scripts/run-production-load.ps1 -Users 50 -Seconds 120 -Workload dictation-audio -Label <new-label>
node scripts/load/summarize-production.cjs target/production-load-20261011/<new-label>-50
```

Chưa kiểm thử trong đợt này: 30 phút liên tục, gõ đủ mọi từ không dùng reveal, browser audio decode/CORS/playback thực, RabbitMQ/AI providers, hoặc giới hạn tuyệt đối cao hơn 100 VU. Không mặc định test này nghiệm thu toàn bộ đường S3/private audio vì mẫu tải lấy recorded public lessons hiện có.
