# 한술 AWS 시험 배포

서울 리전의 앱 EC2(`m7i-flex.large`)와 별도 AI EC2(`t3.small`)를 배포합니다. AI 인스턴스는 StateNote 예시처럼 **발신용 공인 IPv4는 있지만 인바운드 규칙이 0개**입니다. 앱 EC2에서만 VPC 피어링·보안 그룹 참조를 통해 사설 AI 포트 8765에 접근합니다. SSH는 열지 않습니다. 앱 원점은 Cloudflare IPv4 대역의 443만 허용합니다. AI 스택은 별도 Terraform state를 사용하며 기본 `destroy.ps1` 범위 밖입니다.

## 준비와 실행

1. `deploy.config.example.psd1`을 Git에 포함되지 않는 `deploy.config.psd1`로 복사하고 프로젝트 전용 AWS CLI 프로필과 JDK 25 경로를 설정합니다. 루트 프로필 사용은 권장하지 않으며, 시험 목적으로 명시적으로 승인했다면 `AllowRoot = $true`로 선택합니다.
2. AWS CLI v2, Terraform >= 1.10, JDK 25, Windows `tar`를 준비합니다. `C:\dev\aws-deploy-kit`의 `cloudflare.env`, `domain/cloudflare.crt`, `domain/cloudflare.key`가 필요합니다. 스크립트는 비밀값 내용을 출력하지 않습니다.
3. Cloudflare SSL/TLS 모드를 **Full (strict)** 로 설정합니다. 현재 DNS 토큰에는 SSL 설정을 읽는 권한이 없어 자동 확인은 불가능합니다.

프로젝트 루트에서 이미 AI가 실행 중일 때의 코드 갱신은 다음 명령입니다. AI가 없는 첫 배포·전체 종료 후 재기동에는 `-IncludeAi`가 필수입니다. 백업이 전혀 없는 첫 배포에서만 `-Fresh`도 지정합니다.

```powershell
powershell -NoProfile -File .\infra\aws\hansul\deploy.ps1
powershell -NoProfile -File .\infra\aws\hansul\deploy.ps1 -IncludeAi -Fresh  # 백업 없는 최초 설치
```

실행은 서버 테스트·JAR 빌드 → 버전 관리·잠금이 있는 전용 S3 Terraform state 준비 → AI 스택/앱 스택 plan 검사·apply → SSM을 통한 AI 어댑터 설치·앱 배포 → 내부 건강 검사 → Cloudflare DNS 업데이트 → 외부 HTTPS 검사 순서입니다. Terraform plan에 `delete`가 있으면 중단합니다. 실행 중 앱의 코드 재배포에서는 두 PostgreSQL DB를 `pg_dump`하여 암호화된 비공개 S3 버킷에 업로드한 후 새 API 컨테이너를 띄웁니다. 건강 검사가 실패하면 이전 릴리스 컨테이너를 다시 실행하고 DNS를 변경하지 않습니다.

비용 절감을 위해 앱 스택만 완전히 내렸다가 다시 올릴 때는 다음 두 명령을 사용합니다. **AI 인스턴스와 Codex 로그인은 유지됩니다.**

```powershell
powershell -NoProfile -File .\infra\aws\hansul\destroy.ps1
powershell -NoProfile -File .\infra\aws\hansul\deploy.ps1
```

한술의 AWS 상시 비용을 없애는 **전체 종료·전체 기동** 옵션은 아래와 같습니다. 종료 전 두 DB를 `infra/aws/hansul/.local/aws-backups/<snapshot-id>/`에 검증해 저장하고, 앱·AI Terraform 스택, AI 루트 EBS, 한술 전용 SSM 파라미터, 버전 관리된 한술 전용 S3 state 버킷까지 삭제합니다. 이 PC의 로컬 백업을 잃으면 계정·조리 데이터 복원이 불가능합니다. 백업은 DB 내용이 포함된 평문 파일이므로 PC 디스크 암호화와 별도 안전한 로컬 백업을 권장합니다. AI 루트 EBS가 삭제되므로 재기동 뒤 Codex CLI 기기 인증 로그인을 다시 해야 합니다. AI가 삭제된 상태에서 일반 `deploy.ps1`은 오류로 중단하며 자동으로 AI를 만들지 않습니다.

```powershell
powershell -NoProfile -File .\infra\aws\hansul\destroy.ps1 -PlanOnly -IncludeAi
powershell -NoProfile -File .\infra\aws\hansul\destroy.ps1 -IncludeAi
powershell -NoProfile -File .\infra\aws\hansul\deploy.ps1 -IncludeAi
```

`-IncludeAi` 종료가 중간에 실패하면 출력에 따라 남은 EC2·EBS·S3 리소스를 확인해야 합니다. 실패했다고 안전하게 전체 명령을 무조건 반복하지 마세요. 이전까지 발생한 요금, 다른 프로젝트 리소스, 요청 단위 요금은 이 옵션이 없애지 못합니다. 정상 완료 후 **한술 전용 상시 AWS 리소스**가 없는 상태를 목표로 합니다.

앱·AI Terraform 스택은 이미 모두 삭제됐지만 마지막 state 버킷 정리만 실패한 경우에만 다음 복구 모드를 사용합니다. 이 모드는 검증된 로컬 DB 백업, DNS 제거, 활성 EC2·EBS·VPC·IAM·SSM 부재, 배포 S3 버킷 부재를 확인하고, 남은 state 버킷의 객체 버전이 한술 소유인 경우에만 그 버킷을 삭제합니다. 다른 리소스가 남아 있으면 중단합니다.

```powershell
powershell -NoProfile -File .\infra\aws\hansul\destroy.ps1 -IncludeAi -FinalizeOnly -PlanOnly
powershell -NoProfile -File .\infra\aws\hansul\destroy.ps1 -IncludeAi -FinalizeOnly
```

종료 명령은 앱·Keycloak 쓰기를 중지하고 두 DB를 덤프합니다. 로컬 `infra/aws/hansul/.local/aws-backups/<snapshot-id>/`와 앱 스택 밖 Terraform state S3 버킷에 보관하고, 크기·SHA-256 및 S3에서 다시 받은 파일까지 검증한 뒤 **앱 Terraform 스택 전체**(EC2, 루트·DB EBS, 앱 VPC·피어링, 배포 S3 버킷)를 삭제합니다. Cloudflare의 한술 DNS 레코드도 제거합니다. 다음 배포는 최신의 완전한 백업을 자동 선택·검증하고 새 PostgreSQL의 한술·Keycloak DB에 복원합니다. 백업이 없을 때만 빈 DB를 만듭니다. 백업을 의도적으로 무시하려면 `deploy.ps1 -Fresh`를 명시합니다. 종료가 백업 중 실패하면 앱 쓰기 컨테이너를 재시작합니다. 오래된 로컬·S3 스냅샷의 보존/삭제 정책은 운영자가 정해야 합니다.

삭제 대상만 먼저 확인하려면 `destroy.ps1 -PlanOnly` 또는 전체 종료의 경우 `destroy.ps1 -PlanOnly -IncludeAi`를 실행합니다. 이 옵션은 DB 백업, 컨테이너 중지, Terraform 적용, DNS 변경을 하지 않습니다.

배포 후 데이터·컨테이너·공개 건강 검사와 앱 서버→AI 서버의 실제 모델 응답을 각각 확인할 수 있습니다.

```powershell
powershell -NoProfile -File .\infra\aws\hansul\verify-deployment.ps1
powershell -NoProfile -File .\infra\aws\hansul\verify-ai.ps1
```

AI 서버는 첫 설치 뒤 **본인의 Codex CLI 기기 인증 로그인**이 필요합니다. 설치만으로 실제 AI 응답이 가능하다고 간주하지 마세요. AWS 콘솔 → Systems Manager → Session Manager → `hansul-ai` 인스턴스에 접속해 다음을 실행합니다. 로컬 Session Manager 플러그인이 설치돼 있다면 `aws ssm start-session --profile <profile> --region ap-northeast-2 --target <AI instance ID>`도 가능합니다.

```bash
sudo -iu ec2-user /usr/local/bin/codex login --device-auth
sudo -iu ec2-user /usr/local/bin/codex login status
```

기기 코드·`auth.json`을 채팅, 로그 또는 Git에 공유하지 마세요. 로그인 뒤 동일한 배포 명령을 다시 실행하거나 AI 서비스의 `/readyz`를 사설 네트워크에서 확인합니다. 한술 서버는 앱에 API 키를 전달하지 않고, Parameter Store `SecureString`으로 저장한 토큰으로 전용 AI 어댑터의 `/v1/responses`에 요청합니다. 이 어댑터는 한술의 strict JSON-schema 요청 하위 집합만 구현하며 일반 OpenAI API 전체를 대체하지 않습니다.

## 데이터, 보안, 비용

- 실행 중 앱 루트 EBS 20GiB, PostgreSQL 전용 암호화 EBS 8GiB, AI 루트 EBS 20GiB: 합계 48GiB. 앱 코드 재배포에서는 DB 볼륨을 유지하고, 앱 스택 `destroy` 시에는 두 앱 EBS를 삭제한 뒤 검증된 백업으로 다음 기동에 복원합니다. AI 루트 디스크·로그인 상태는 앱 스택 종료와 무관하게 유지됩니다.
- 공인 IPv4 2개는 앱 원점과 AI 호스트의 **발신** 용도입니다. AI 호스트의 8765·22 포트는 공개되지 않습니다. 이 구조는 StateNote AI 파일의 네트워크 패턴을 따릅니다.
- 기본 앱 스택만 내린 동안 앱 EC2·앱 EBS·앱 공인 IPv4 요금은 멈추지만, 계속 켜 둔 AI EC2·AI EBS·AI 공인 IPv4와 백업/상태 S3 요금은 남습니다. `-IncludeAi` 전체 종료는 검증된 로컬 DB 백업만 남기고 한술 전용 AWS 상시 리소스를 모두 제거합니다.
- 2026-09-29 AWS 가격 API의 서울 Linux 온디맨드 컴퓨트 단가: 앱 시간당 $0.11771, AI 시간당 $0.026. 730시간이면 컴퓨트만 약 $105/월이며 EBS, 두 공인 IPv4, S3, 데이터 전송과 모델 서비스 비용은 별도입니다. 운영 전 현재 요금을 다시 확인하세요.
- Terraform state S3는 앱·AI 양쪽 스택과 별개이며 버전 관리·SSE·잠금을 사용합니다. 비밀값은 Terraform state나 배포 아카이브에 넣지 않고 SSM Parameter Store에 보관합니다. 앱 인스턴스 역할은 자체 배포 S3 객체와 지정된 파라미터만 읽으며, AI 역할은 AI 토큰만 읽습니다.
- Cloudflare Origin CA 인증서는 프록시 원점 전용입니다. EC2 IP 직접 접속은 공개 신뢰 TLS가 아니며, 외부 사용자는 Cloudflare 프록시 도메인을 통해서만 접근합니다.
- Keycloak은 시험용으로 이메일 인증 없는 자체 가입을 허용합니다. 공개 서비스 전 이메일 검증·가입 제한·남용 방지를 추가하세요. `hansul-realm.production.json`에는 로컬 개발용 고정 사용자나 smoke client가 없습니다.

## 영상 레시피 분석 (선택)

아래 파라미터가 없으면 영상 분석 API는 HTTP 503으로 꺼져 있습니다. 모두 운영자가 Parameter Store에 직접 만들며, 배포 때마다 다시 읽습니다.

- `/hansul/app/gemini-api-key` (SecureString): Gemini API 키입니다. YouTube 링크 분석은 이 키만으로 켜집니다.
- `/hansul/app/gemini-paid-tier` (값 `true`): 위 키가 **결제가 켜진 Google Cloud 프로젝트**의 키일 때만 만듭니다. 무료 등급에서는 Google이 입력을 제품 개선에 쓰고 사람이 검토할 수 있으므로, 이 값이 없으면 얼굴·목소리·집 안이 담길 수 있는 휴대폰 영상 업로드는 HTTP 503으로 거부됩니다. 휴대폰 영상이 Google Gemini로 전송된다는 점은 개인정보 처리방침과 Play 데이터 보안 양식에도 적어야 합니다.
- `/hansul/app/youtube-api-key` (SecureString, 선택): 설명란과 영상 길이를 읽습니다. 없으면 링크의 길이를 알 수 없어 30분 제한이 YouTube 링크에는 적용되지 않습니다.

휴대폰 영상 조각은 컨테이너 밖 앱 루트 EBS의 `/var/lib/hansul/video-uploads`에 보관되어 재배포 뒤에도 이어 올리거나 다시 분석할 수 있습니다. 서버는 진행 중인 업로드 전체를 3GiB 이내로, 이 디스크의 여유 공간을 2GiB 이상으로 유지합니다. 앱 스택 `destroy`에서는 루트 EBS와 함께 삭제되고, `-IncludeAi` 전체 종료는 위 세 파라미터도 삭제합니다.

## 제거·복구

기본 `destroy.ps1`은 앱 스택만 종료하고 AI·Terraform state 버킷의 `retained-backups/snapshots/`는 유지합니다. `-IncludeAi`는 AI와 state 버킷까지 삭제합니다. `deploy.ps1`은 S3 또는 전체 종료 때 남긴 최신 로컬 스냅샷의 두 DB를 자동 복원하며, 백업을 잃어버렸거나 해시가 다르면 빈 DB로 진행하지 않고 중단합니다. 백업을 의도적으로 무시하고 빈 DB를 만들 때만 `-Fresh`를 사용하세요. Docker가 설치된 PC에서는 `verify-restore.ps1`로 최신 S3 스냅샷 또는 전체 종료 후의 로컬 스냅샷을 격리된 임시 PostgreSQL에 복원 시험할 수 있습니다. 시험 컨테이너는 종료 시 제거됩니다.

```powershell
powershell -NoProfile -File .\infra\aws\hansul\verify-restore.ps1
```

배포 실패 때는 출력된 SSM command ID와 해당 인스턴스의 `cloud-init`/Docker 로그를 확인하고, AWS 세션 만료 시 CLI 프로필로 다시 로그인한 다음 상태를 확인하고 재실행합니다. AI 스택의 삭제·재생성은 `-IncludeAi`로만 허용합니다.
