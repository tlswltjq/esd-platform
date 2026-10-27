# PortOne TEST 웹훅의 OCI 공개 IP HTTPS 경로

2026-10-27에 사용자가 실행을 승인한 검증 환경이다. 공개 주소는
`https://161.33.31.159/api/v1/payments/webhooks/portone`이다.

## 구성과 범위

```text
PortOne → OCI nginx :443 → OCI loopback :18883
        → SSH 역방향 연결 → 로컬 Payment 127.0.0.1:18783
        → SSH 정방향 연결 → 격리 OCI MySQL 127.0.0.1:13383
```

- OCI 경로는 `/opt/stove-portone-webhook`이다. nginx는 `stove-portone-edge`, 검증 DB는 `stove-portone-validation-mysql`이다. 둘 다 전용 bind mount를 사용한다.
- `http.conf`와 `https.conf`의 `__PUBLIC_IP__`를 실제 IP로 치환해 `conf.d/`에 배치했다. 80은 ACME 검증 파일만, 443은 정확한 웹훅 POST 경로만 제공한다. 그 외 경로는 404, 웹훅의 다른 메서드는 405다.
- nginx는 원문과 서명 헤더를 전달하고 본문을 64 KiB로 제한한다. Payment가 서명·상점·기존 결제 연결값을 검증한다. 접근 기록에는 시각·상태·처리 시간만 남긴다.
- `PORTONE_WEBHOOK_URL`도 로컬 설정에서 읽고, 값은 위 공개 IP의 HTTPS 웹훅 주소다. 결제창 정보 API가 이 주소를 반환하므로 활성 포트원 설정에서 명시해야 한다.
- API Secret·웹훅 시크릿은 로컬 `.env.portone-test`에서 읽는다. OCI에는 이 값들을 복사하지 않았다. TLS 개인키는 OCI의 `letsencrypt/`에 보관한다.
- 로컬 Payment와 SSH 연결이 실행 중이어야 업무 수신이 가능하다. 연결이 없으면 nginx는 5xx를 반환하며 수신 성공으로 응답하지 않는다. 24시간 운영 배포는 별도 범위다.
- 검증 Payment는 격리 DB의 합성 주문 픽스처를 사용한다. Kafka 리스너와 Outbox 전송은 껐고 실제 PG 주기 조회는 켰다. 실제 Order·License·Settlement 연결 검증으로 확대하지 않는다.

## 인증서와 갱신

Let's Encrypt의 IPv4 SAN 인증서를 `portone-ip` 이름으로 발급했다. Certbot 5.8.0과 `shortlived` 프로필·webroot 방식을 사용했다. 인증서는 약 160시간 유효하며 자동 갱신이 필요하다.

초기 실행에서 이미지 태그를 아래 digest로 고정해 `images.env`에 저장했다. 이후 갱신도 같은 Certbot 이미지를 사용한다.

```text
nginx@sha256:47928bf67eb62a1863cb08d9ecb52d3e704304ba614eaee17884204677702951
certbot/certbot@sha256:f70ad0adbb7e117f0fe42a63c553f28ea451edabc0148757b6efcd9735acaa20
```

`renew.sh`와 두 systemd unit을 설치했다. 타이머는 UTC 00·08·16시에 최대 10분의 무작위 지연을 두고 확인하며, 놓친 실행을 보완한다. 갱신 성공 후 nginx 설정을 검증하고 인증서를 다시 읽힌다. nginx는 `unless-stopped`로 재시작한다.

```sh
ssh sng 'systemctl list-timers stove-portone-cert-renew.timer --no-pager'
ssh sng 'sudo -n /opt/stove-portone-webhook/renew.sh --dry-run'
ssh sng 'docker exec stove-portone-edge nginx -t'
```

IP는 현재 OCI의 `EPHEMERAL` 할당이다. IP를 반납·교체하면 인증서와 웹훅 주소도 갱신해야 한다. 기존 OCI 보안 목록과 호스트 방화벽이 이미 80·443을 허용해 방화벽·보안 목록은 수정하지 않았다.

## 검증 근거와 재개

`runs/portone-public-https-cm9y22_z/`에 인증서·공개 HTTP·실제 제공사 전송·DB 접수·중복·공유 자원 보존 근거를 보관했다. `resume-local.py`는 같은 로컬 검증 Payment와 SSH 연결을 재개한다. 빌드 JAR를 해시 이름의 `runtime/` 복사본으로 분리해 실행하므로 후속 bootJar가 실행 파일을 덮어쓰지 않는다. DB 자격은 이 디렉터리의 600 권한 파일에서 읽으며 실제 PG 키는 저장소 루트의 Git 제외 설정에서 읽는다.

```sh
python3 runs/portone-public-https-cm9y22_z/resume-local.py
```

SDK의 `noticeUrls`로 이 검증 결제에만 수신 주소를 지정했다. 실제 `Transaction.Ready`의 HTTP 200·포트원 `SUCCEEDED`·DB 접수와 재발송 시 최초 기록 보존을 확인했다. 새 거래는 `READY`, 로컬 결제는 `PENDING`이다. 결제창 개시를 결제 성공으로 확정하지 않았다. 기존 별도 거래의 9,000원 결제·전액 취소 완료 기록과 구분한다.

공유 컨테이너 18개의 ID·시작 시각·재시작 횟수·실행/건강 상태와 기존 볼륨 55개를 보존했다. 추가한 컨테이너는 위 두 개뿐이며 추가 Docker 볼륨은 없다. 이번 설정에 대해 로컬 Payment bootJar, 공개 HTTP 7건, TLS 신뢰·IP SAN, 인증서 갱신 dry-run과 systemd 실행을 검증했다. 기존 Payment 단위 140건·통합 57건은 앞선 검증 결과이며 이번 인프라 설정 뒤 재실행한 것으로 표시하지 않는다.

중지할 때는 해당 타이머와 두 전용 컨테이너·로컬 PID만 다룬다. 전체 Compose 종료·Docker prune·기존 볼륨 삭제를 사용하지 않는다. 인증서·검증 DB·접수 근거는 보존한다.

## 실제 주문 연결 후 최신 실행

2026-10-27 후속으로 실제 Order·Payment 연결 검증을 준비했다. 현재 공개 nginx의 연결 대상은 `runs/portone-order-live-j2flh033/`의 로컬 Payment다. 새 전용 DB·Kafka를 사용하고 Auth·Catalog·Order도 실행한다. 현재 재개 명령은 아래이며 구 `resume-local.py`와 동시에 실행하지 않는다. 자세한 범위·휴대폰 승인 상태는 [PG 연동 계획](../../../../docs/pg-test-integration-plan.md)을 확인한다.

```sh
python3 runs/portone-order-live-j2flh033/resume-stack.py
```
