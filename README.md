> 이 저장소의 배포 도메인과 로컬 기기 주소는 예시값입니다. AWS 배포 전에 환경에 맞게 설정을 수정해야 합니다. 실제 비밀값과 로컬 설정은 저장소에 포함하지 않습니다.

# 한술

만개의레시피 페이지를 Android 공유 메뉴로 가져와 원문 사진을 보며 한국어 대화로 요리하는 앱의 MVP입니다. **로컬 모드가 기본**이며 레시피·수동 조리에는 로그인이나 한술 서버가 필요하지 않습니다. 로컬 AI 질문은 휴대폰에 설치한 Gemma 4 E2B가 처리합니다. 기존 Spring Boot·로그인 기반 클라우드 모드도 설정에서 선택할 수 있습니다.

YouTube 레시피 영상 링크와 휴대폰에 저장한 요리 영상도 같은 방법으로 공유할 수 있습니다. 한술 서버가 Gemini로 영상의 말과 화면 글자를 받아 적고, 근거를 검사한 레시피로 정리합니다. 조리 단계마다 해당 영상 구간을 다시 볼 수 있습니다. 영상 분석에만 로그인이 필요하며, 로컬 모드에서도 결과 레시피는 기기에 저장됩니다. 자세한 내용은 [영상 레시피 가져오기](docs/VIDEO_RECIPE_IMPORT.md)를 참고하세요. 이 기능은 아직 실제 Gemini 호출과 휴대폰에서 확인하지 않았습니다.

준비 화면은 원문 인분·읽기 전용 재료 목록·자유로운 질문을, 조리 화면은 큰 단계 사진·이전/다음·전체 단계 스크롤·완료/건너뛰기·타이머를 제공합니다. 재료 체크나 누락 재료 선택은 요구하지 않습니다. “이거 없는데 괜찮아?”처럼 직접 묻는 흐름입니다. 화면 이동은 실제 완료와 별개이며 음성 진행을 강요하지 않습니다. 비례 환산은 **정확한 권장량이 아닌 참고값**입니다. 물·간·조리시간·제품 지침까지 일괄 배수로 확정하지 않습니다.

[사용자 니즈·검증 시나리오](docs/COOKING_UX_RESEARCH.md), [공식 앱을 참고한 UI 재설계](docs/UI_REFERENCE_REDESIGN.md), [로컬 모델 설치·제약](docs/local-gemma.md), [전체 개발 계획](docs/DEVELOPMENT_PLAN.md)을 참고하세요. 로컬 AI는 실험 기능이며 실제 S23 Ultra에서 답변·사진·인분 문맥 오류가 확인돼 보강 중입니다.

## 휴대폰 로컬 모드

실기기용 APK는 [app-debug.apk](android/app/build/outputs/apk/debug/app-debug.apk)입니다. 앱의 설정에서 공식 모델을 다운로드하거나 검증된 모델 파일을 가져오세요. 모델은 약 2.59 GB이며 사용자 동의 없이 내려받지 않습니다. 새 레시피를 가져올 때는 원문 사이트에 인터넷으로 접속합니다. 이미 저장한 텍스트·캐시된 사진·로컬 AI는 한술 서버 없이 사용할 수 있습니다. 한국어 기기 내 STT·네트워크 없는 TTS 지원은 별도 확인이 필요합니다.

## 개발 서버 실행

필요한 것: JDK 25, Android Studio/Android SDK, Docker. `server/`와 `android/`는 각각 별도 Gradle 프로젝트입니다.

```powershell
cd server
docker compose up -d postgres keycloak
./gradlew.bat bootRun
```

서버 기본 주소는 `http://localhost:8080`입니다. 로컬 5432가 이미 사용 중이면 `POSTGRES_PORT=55432`와 `DATABASE_URL=jdbc:postgresql://localhost:55432/hansul`을 설정하세요. 개발용 로그인 설정은 [Keycloak 안내](server/infra/keycloak/README.md)를 참고하세요. 서버 AI 기능에는 별도로 `HANSUL_AI_API_KEY`와 `HANSUL_AI_MODEL` 환경 변수가 필요합니다. 비워 두면 레시피와 수동 진행은 작동하지만 대화 턴은 503을 반환합니다. 영상 레시피 분석에는 `HANSUL_GEMINI_API_KEY`가 필요합니다. 휴대폰 영상까지 받으려면 결제가 켜진 프로젝트의 키를 쓰고 `HANSUL_GEMINI_PAID_TIER=true`도 설정하세요. 키가 없으면 영상 분석 API만 503을 반환합니다. API 키는 앱에 넣지 않습니다.

Android Studio에서 `android/`를 열어 에뮬레이터에 설치합니다. 개발 기본 API/로그인 주소와 변경 방법은 [Android 안내](android/README.md)에 있습니다. 테스트 URL은 [바지락순두부찌개](https://www.10000recipe.com/recipe/6850292)입니다. 공유 URL의 추적 쿼리는 서버가 제거합니다.

## AWS 실기기 테스트

서울 AWS 시험 서버는 `https://hansul.example.com`에서 실행합니다. [AWS 배포·보안·복구 안내](infra/aws/hansul/README.md)에 앱만 내리는 기본 `destroy.ps1`/`deploy.ps1`과, AI·상태 S3까지 내렸다 올리는 `-IncludeAi` 옵션이 있습니다. 전체 종료 때 두 DB의 검증된 백업은 이 PC에만 남으며, AI 로그인은 재기동 후 다시 해야 합니다. 앱/Keycloak 헬스, 앱 서버에서 전용 AI 서버로 보내는 실제 구조화 모델 요청, 재배포 뒤 Terraform 무변경 계획 및 DB 백업 업로드를 확인했습니다. 휴대폰의 마이크·TTS·로그인·레시피 공유 전체 흐름은 실기기에서 추가 확인해야 합니다.

Android 8.0 이상 기기에서 브라우저·YouTube 앱·갤러리의 `공유 → 한술` 또는 앱의 URL 입력을 사용하세요. 클라우드 모드에서만 계정이 필요합니다. APK는 시험용 서명이며 Play 스토어 배포용이 아닙니다. 서버 코드 변경은 로컬 빌드와 별개로 배포해야 합니다.

## 현재 API

`/v1/**`는 로그인 토큰이 필요합니다. 주요 계약은 다음과 같습니다.

| 요청 | 설명 |
| --- | --- |
| `POST /v1/recipe-imports` | `{"url":"https://www.10000recipe.com/recipe/6850292"}` |
| `POST /v1/recipe-import-jobs` | `{"url":"https://youtu.be/..."}` 또는 `{"uploadId":"..."}`. 영상 분석 작업 생성 |
| `GET /v1/recipe-import-jobs/{id}` | 분석 진행 단계와 완료된 `recipeId` |
| `POST /v1/video-uploads` | 휴대폰 영상 업로드 시작 `{"contentType","sizeBytes","sha256","durationSeconds"}` |
| `PUT /v1/video-uploads/{id}/chunks/{index}` | 4 MiB 영상 조각 |
| `GET /v1/video-uploads/{id}` | 받은 조각 확인(이어 올리기) |
| `GET /v1/recipes/{id}` | 원문과 출처 근거, 단계 사진, 영상 레시피의 단계별 영상 구간 |
| `GET /v1/recipes/{id}/scaled-ingredients?servings=1` | 원문과 비례 참고값·주의사항 |
| `POST /v1/cooking-sessions` | `{"recipeId":"...","targetServings":1}` |
| `GET /v1/cooking-sessions/{id}` | 원문 기반 행동 그래프, 상태, 타이머 종료 시각 |
| `POST /v1/cooking-sessions/{id}/turns` | `{"clientTurnId":"UUID","transcript":"물 올렸어. 다음은?"}` |
| `POST /v1/cooking-sessions/{id}/navigation` | 화면 초점 이동, 완료로 간주하지 않음 |
| `POST /v1/cooking-sessions/{id}/step-progress` | 완료·건너뛰기·되돌리기 명시적 입력 |
| `POST /v1/cooking-sessions/{id}/timer-start-requests` | 터치로 원문 타이머 시작 요청 |
| `POST /v1/cooking-sessions/{id}/timer-start-acks` | `{"clientAckId":"UUID","timerCueId":"..."}` |

레시피 ID나 재료명별 분기 없이 구조화 데이터와 원문 단계에서 정보를 추출합니다. AI는 행동·관찰·타이머 근거를 제안할 뿐, 서버가 원문 참조와 발화 근거를 검증한 뒤에만 조리 상태를 갱신합니다. AI가 없거나 레시피 계획 검증에 실패하면 원문 순서로 돌아가며 시간·불 세기를 추측해 채우지 않습니다. 공유 페이지 자체의 재료 목록이 불완전할 수 있으므로 원문 단계도 확인해야 합니다.

세션 응답의 `plan.timerCues`는 `durationSeconds`, `sourceStepNumber`, `approximate`를 명시적으로 제공합니다. `approximate=true`는 원문이 “약 5분”처럼 근사 시간을 말한 경우입니다. 타이머는 사용자가 수락하고 단말이 실제 예약한 뒤 ack를 보내며, 만료 시 서버 상태에서 실행 중 표시를 해제합니다.

## 검증

```powershell
cd server
./gradlew.bat test
```

과거 서버 import·인증·세션 저장·AWS AI 연동 smoke와 Android 빌드를 수행했습니다. 영상 레시피 기능의 자동 검사 결과와 아직 확인하지 않은 항목은 [영상 레시피 가져오기](docs/VIDEO_RECIPE_IMPORT.md#검증-상태)에 있습니다. 이번 로컬 모드의 실제 기기 결과와 최신 테스트 수는 [실기 검증 기록](docs/DEVICE_QA.md)에 구분해 기록합니다. 모든 요리 상황이나 주방 소음·장시간 음성 품질을 보장하지 않습니다. 로컬 조리 세션 복원과 저장된 절대 마감 기준 알람 재등록을 구현했지만, 실제 재부팅·강제 종료·제조사 절전 환경은 별도 검증 대상입니다. Android 강제 종료 중에는 알람이 제한되며 앱을 다시 열어야 합니다.
