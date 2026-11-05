# 한술 Android

만개의레시피 모바일 페이지에서 `공유 → 한술`을 선택하면 URL을 기기에 먼저 저장합니다.
기본 로컬 모드는 로그인 없이 원문 사이트를 직접 읽고 기기에 레시피·조리 상태를 저장합니다.
Gemma 4 E2B를 설정에서 설치하면 로컬 AI 질문도 사용할 수 있습니다. 클라우드 모드를 선택한
경우에만 로그인 완료 뒤 Spring Boot 서버의 구조화된 레시피를 가져옵니다.
설치와 제약은 [로컬 AI 안내](../docs/local-gemma.md)를 참고하세요.

YouTube 링크와 휴대폰 영상(`video/*`)도 공유받습니다. 영상은 서버가 분석하므로 두 모드
모두 이때만 로그인이 필요합니다. 흐름과 제한은 [영상 레시피 가져오기](../docs/VIDEO_RECIPE_IMPORT.md)에
있습니다.

## 실행

개발 기본값은 Android Emulator에서 호스트 PC를 가리킵니다.

- API: `http://10.0.2.2:8080/`
- Keycloak issuer: `http://10.0.2.2:8180/realms/hansul`
- OIDC client ID: `hansul-android`
- redirect URI: `com.hansul.app:/oauth2redirect`

다른 주소는 프로젝트를 수정하지 않고 지정할 수 있습니다.

```powershell
./gradlew.bat assembleDebug `
  -PHANSUL_API_BASE_URL=https://api.example.com/ `
  -PHANSUL_OIDC_ISSUER=https://login.example.com/realms/hansul `
  -PHANSUL_OIDC_CLIENT_ID=hansul-android `
  -PHANSUL_OIDC_REDIRECT_URI=com.hansul.app:/oauth2redirect
```

또는 git에 포함되지 않는 `local.properties`에 추가합니다.

```properties
sdk.dir=C\:\\Users\\me\\AppData\\Local\\Android\\Sdk
HANSUL_API_BASE_URL=https://api.example.com/
HANSUL_OIDC_ISSUER=https://login.example.com/realms/hansul
HANSUL_OIDC_CLIENT_ID=hansul-android
HANSUL_OIDC_REDIRECT_URI=com.hansul.app:/oauth2redirect
```

Keycloak의 public client에는 Authorization Code flow와 PKCE(S256)를 사용하도록 설정하고,
위 redirect URI를 정확히 등록해야 합니다. 앱은 AppAuth Custom Tab으로 로그인하며 API 요청에
Bearer access token을 자동 첨부합니다. 인증 상태는 Android Keystore의 AES/GCM 키로 암호화한 뒤
DataStore에 저장합니다. HTTP issuer와 cleartext API는 debug 빌드에서만 허용하므로 release에는
반드시 HTTPS 주소를 주입해야 합니다.

서버 계약:

```http
POST /v1/recipe-imports
Content-Type: application/json
Authorization: Bearer {access-token}

{"url":"https://www.10000recipe.com/recipe/..."}
```

응답의 알 수 없는 필드는 무시합니다.

미리보기의 인분 선택기는 다음 범용 API를 사용합니다.

```http
GET /v1/recipes/{id}/scaled-ingredients?servings=1
```

특정 레시피 ID나 재료 이름에 따른 클라이언트 분기는 없습니다.

## 영상 레시피

YouTube 링크는 `POST /v1/recipe-import-jobs`에 `{"url":...}`로 보내고, 2초(30초 뒤부터 4초)
간격으로 `GET /v1/recipe-import-jobs/{id}`를 최대 20분 확인합니다. 인스타그램·틱톡·네이버TV
링크는 서버에 보내지 않고 영상을 저장해 공유하라고 안내합니다.

휴대폰 영상은 다음 순서로 처리합니다.

- `content://` 영상을 앱 저장소로 복사합니다. 같은 영상을 다시 고르면 기존 사본과 업로드를 이어 씁니다.
- 100 MiB 초과, 짧은 변 1080px 초과, 서버가 받지 않는 형식이면 Media3 Transformer로
  720p H.264·AAC(약 2.5 Mbps)로 줄입니다.
- 처음 한 번, 영상이 한술 서버를 거쳐 Google Gemini로 전송된다는 동의를 받습니다.
- `POST /v1/video-uploads` → 4 MiB 조각 `PUT` → `POST /v1/recipe-import-jobs` `{"uploadId":...}`.
  중단되면 `GET /v1/video-uploads/{id}`로 받은 조각을 확인해 이어 올립니다.
- 완료 뒤 단계 시각의 장면을 기기 사본에서 뽑아 단계 사진으로 씁니다. 사본은 단계 영상 재생에도
  쓰며, 레시피가 없는 사본은 24시간 뒤 앱 시작 때 지웁니다. 영상 사본은 Auto Backup에서 제외합니다.

YouTube 단계 영상은 사용자가 누른 뒤에만 WebView 임베드(Referer `https://com.hansul.app`,
200dp 이상, 위에 겹치는 화면 없음)로 재생합니다. 임베드가 막힌 영상과 WebView가 없는 기기는
해당 시각부터 바깥 앱으로 엽니다. 앱이 백그라운드로 가거나 마이크를 켜면 재생을 멈춥니다.
실패 코드별 한국어 안내는 `ui/VideoImportText.kt`에 있습니다.

## 음성 요리 MVP

요리 시작 시 선택한 모드의 조리 세션을 만들고, 사용자가 음성 버튼을 한 번 누르면
Android `SpeechRecognizer`를 발화 단위로 반복 실행합니다. 아래 HTTP 계약은 클라우드 모드에
해당하며, 로컬은 같은 domain 계약을 기기 저장소·Gemma로 처리합니다. 로컬 음성은 기기 내
인식기와 네트워크를 요구하지 않는 한국어 TTS가 없으면 글·터치 입력을 안내합니다.

- 각 발화는 고정 UUID `clientTurnId`와 함께 `POST /v1/cooking-sessions/{id}/turns`로 전송됩니다.
- `DIRECT`와 `UNCERTAIN` 응답만 Android TTS로 읽고, `UNRELATED`는 소리 내지 않습니다.
- TTS 중에는 STT를 멈추고 재생 완료 뒤 다시 듣습니다.
- 서버의 photo cue는 원문 단계 이미지 URL로 표시합니다.
- 네트워크 응답이 유실된 재시도는 같은 turn/ack ID를 재사용합니다.
- `TIMER_START_REQUESTED`는 사용자의 수락이 서버에 반영된 뒤에만 처리합니다. 앱이 정확한
  기기 알람을 먼저 예약하고 성공한 경우에만 `timer-start-acks`를 보냅니다.
- `approximate=true`인 타이머는 UI에서 `약 5분`처럼 표시합니다.

마이크, Android 13+ 알림, Android 12+ 정확한 알람 권한이 필요할 수 있습니다. 음성 청취는
앱이 전경에 있고 화면이 켜진 동안만 동작하며, 조리 중에는 화면을 켜 둡니다. OS나 음성
서비스가 청취를 중단하면 화면의 `수동으로 다시 듣기`로 재개할 수 있습니다. 백그라운드
상시 청취와 핫워드는 구현하지 않았습니다. 로컬은 미완료 세션을 같은 레시피·인분으로
다시 시작하면 복원합니다. 알람은 저장된 절대 마감으로 앱 실행·재부팅·앱 업데이트 때
재등록하도록 구현했으며, 권한이 있어야 합니다. 강제 종료 중 OS 제한과 제조사 절전,
실제 재부팅 복구는 실기 검증 대상입니다. 종료 후 임의 클라우드 세션 검색 UI는 없습니다.

서버에 모델 키가 없으면 turn API가 `503`을 반환하며 앱은 재시도 가능한 오류로 표시합니다.
