# 영상 레시피 가져오기

작성일 2026-11-05. YouTube 레시피 영상, 만개의레시피 페이지에 들어 있는 영상, 휴대폰에 저장한 요리 영상을 기존 만개의레시피 레시피와 같은 준비·조리 화면으로 안내하는 기능입니다. **실제 Gemini·YouTube API 호출과 휴대폰 실행은 아직 하지 않았습니다.** 확인한 범위는 아래 [검증 상태](#검증-상태)에 따로 적었습니다.

## 지원 범위

| 공유한 것 | 처리 | 결과 |
| --- | --- | --- |
| YouTube 링크 (`watch`, `shorts`, `live`, `embed`, `youtu.be`, `m.youtube.com`, `youtube-nocookie.com`) | 서버가 Gemini로 영상을 분석 | 모든 사용자가 같이 쓰는 레시피. 같은 영상은 다시 분석하지 않음 |
| 만개의레시피 페이지 | 기존 페이지 가져오기 | 페이지에 영상이 있으면 레시피 화면에 영상 패널을 표시 |
| 휴대폰 영상 파일 (`공유 → 한술` 또는 앱에서 선택) | 앱이 복사·압축해 서버로 올리고 서버가 Gemini로 분석 | 올린 사람만 볼 수 있는 레시피 |
| 인스타그램·틱톡·네이버TV·페이스북 링크 | 분석하지 않음 | "영상을 휴대폰에 저장한 뒤 한술로 공유해주세요" 안내 |

재생목록 전용 값(`videoseries`, `live_stream`)은 영상 ID로 받지 않습니다. 만개의레시피의 네이버TV 영상은 앱 안에서 재생할 수 없어 바깥 앱으로 엽니다.

## 사용자 흐름

1. YouTube 앱이나 브라우저에서 `공유 → 한술`을 누릅니다. 휴대폰 영상은 갤러리에서 공유하거나 앱의 영상 선택을 사용합니다.
2. 영상 분석은 한술 서버의 AI가 하므로 로그인이 필요합니다. 로컬 모드에서도 이 분석에만 로그인하며, 결과 레시피는 기기에 저장되고 조리는 기존 로컬 방식으로 진행합니다.
3. 휴대폰 영상은 처음 한 번 동의 화면이 나옵니다. 영상이 한술 서버를 거쳐 Google Gemini로 전송되고 얼굴·목소리·집 안 모습이 포함될 수 있다는 내용입니다. 동의하지 않으면 영상은 휴대폰 밖으로 나가지 않습니다.
4. 분석 중에는 진행 단계가 표시됩니다. 휴대폰 영상은 `영상 준비 중 → 영상 크기 줄이는 중 → 영상 올리는 중 n%`를 먼저 거치고, 그다음은 공통으로 `분석 차례를 기다리는 중 → 영상 정보 확인 중 → AI에게 영상 전달 중 → AI가 영상을 보는 중 → 레시피 정리 중 → 저장 중`입니다. 휴대폰 영상은 마지막에 `단계 장면 만드는 중`이 더 있습니다. 앱은 최대 20분까지 기다립니다. 앱을 닫았다가 다시 열면 진행 중이던 분석을 이어서 확인합니다.
5. 레시피 화면 위에는 "AI가 영상의 말과 화면 글자를 보고 정리한 레시피"라는 안내와 영상 패널이 나옵니다. 각 조리 단계에는 `영상에서 보기 00:28–00:45` 버튼이 있어 그 구간만 재생합니다. 휴대폰 영상은 기기에 남겨 둔 사본으로 재생하고, 단계 사진은 그 사본에서 뽑은 장면입니다. 임베드가 막힌 YouTube 영상이나 WebView가 없는 기기에서는 해당 시각부터 바깥 앱(YouTube 앱이나 브라우저)으로 엽니다.

실패한 분석은 앱을 다시 열어도 자동으로 다시 돌리지 않고 `다시 가져오기` 버튼을 보여 줍니다. 시간 초과나 연결 끊김만 자동으로 이어 갑니다.

## 분석 방식과 근거 규칙

AI가 영상에 없는 분량이나 시간을 지어내지 않도록, 서버는 두 번에 나눠 묻고 결과를 코드로 검사합니다.

1. **1차: 받아쓰기.** Gemini가 영상의 말과 화면 글자를 시간 구간(`s1`, `s2` …)별로 그대로 적습니다. 요약하지 않습니다.
2. **2차: 레시피 정리.** 1차 결과만 보고 재료·단계·인분을 만듭니다. 항목마다 근거 구간 ID와 그 구간에서 그대로 가져온 인용문을 붙입니다.
3. **검사.** 서버 코드가 다음을 확인합니다.
   - 인용문이 실제로 그 구간에 있는지 확인합니다(띄어쓰기·문장부호 차이는 허용).
   - 단계·재료량·인분에 나온 숫자가 근거 구간에도 있는지 확인합니다. 숫자는 `2`, `½`, `３`뿐 아니라 "두 스푼", "반 컵", "이삼 분", "두세 번", "밥 두 공기"처럼 말로 한 한국어 수사와 범위도 읽습니다. 지시어 "이 컵"의 `이`는 2로 보지 않습니다.
   - 단계의 영상 시간이 근거 구간 안에 있는지 확인합니다. 벗어나면 근거 구간 안으로 맞춥니다.
4. 검사에 걸리면 지적 사항을 붙여 2차를 한 번 더 묻습니다. 그래도 맞지 않으면:
   - 근거가 확인되지 않는 단계는 요약 대신 받아쓰기 원문을 그대로 보여 줍니다.
   - 근거가 확인되지 않는 재료량은 지우고, 응답의 `warnings`에 영상에서 확인하라는 경고(영문)를 붙입니다.
   - 인분 숫자도 근거가 확인될 때만 인분 환산의 기준으로 씁니다.

요리 영상이 아니면 `NOT_A_RECIPE_VIDEO`, 단계를 하나도 확인하지 못하면 `RECIPE_EXTRACTION_FAILED`로 끝납니다. 조리 세션의 AI에게는 이 레시피가 영상에서 AI가 정리한 것이라는 사실도 함께 전달합니다.

## 서버 설정

| 환경 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `HANSUL_GEMINI_API_KEY` | 없음 | 없으면 영상 분석 API가 HTTP 503 `VIDEO_ANALYSIS_UNAVAILABLE`을 반환합니다. YouTube 링크는 이 키만으로 켜집니다. |
| `HANSUL_GEMINI_PAID_TIER` | `false` | **휴대폰 영상은 `true`일 때만 받습니다.** 결제가 켜진 Google Cloud 프로젝트의 키일 때만 켜세요. 무료 등급은 입력을 Google 제품 개선에 쓰고 사람이 검토할 수 있습니다. |
| `HANSUL_GEMINI_MODEL` | `gemini-3.8-flash` | 분석 모델 |
| `HANSUL_YOUTUBE_API_KEY` | 없음 | 선택. 설명란과 영상 길이를 읽습니다. 없으면 oEmbed로 제목·채널만 읽고, YouTube 링크에는 30분 제한을 미리 적용할 수 없습니다. |
| `HANSUL_VIDEO_UPLOAD_DIR` | 임시 폴더 아래 `hansul-video-uploads` | 휴대폰 영상 조각을 보관하는 위치. 재시작 뒤에도 남는 경로를 지정하세요. |
| `HANSUL_GEMINI_THINKING_LEVEL`, `HANSUL_GEMINI_MEDIA_RESOLUTION` | 비움 | 비우면 요청에서 생략해 API 기본값을 씁니다. |

키는 서버에만 두고 앱에는 넣지 않습니다. 로그와 오류 응답에는 키·받아쓰기 내용·영상 내용을 남기지 않습니다. AWS 배포에서는 Parameter Store의 `/hansul/app/gemini-api-key`, `/hansul/app/gemini-paid-tier`, `/hansul/app/youtube-api-key`를 배포 때 읽습니다. 자세한 내용은 [AWS 안내](../infra/aws/hansul/README.md#영상-레시피-분석-선택)에 있습니다.

로컬에서 시험할 때:

```powershell
cd server
$env:HANSUL_GEMINI_API_KEY = '<Gemini API 키>'
$env:HANSUL_GEMINI_PAID_TIER = 'true'   # 결제 프로젝트 키로 휴대폰 영상까지 시험할 때만
./gradlew.bat bootRun
```

## 제한

| 항목 | 값 |
| --- | --- |
| 영상 길이 | 30분(1800초). 휴대폰 영상은 Gemini가 잰 실제 길이로도 다시 확인합니다. |
| 분석 횟수 | 사용자당 최근 24시간 10회. 이미 분석된 YouTube 영상이나, 누군가 분석 중인 같은 영상에 합류하는 요청은 세지 않습니다. 실패한 분석은 셉니다. |
| 동시 분석 | 서버 전체 2개. 나머지는 차례를 기다립니다. |
| 휴대폰 영상 크기 | 300 MiB. 앱은 100 MiB가 넘거나, 짧은 변이 1080px보다 크거나, 서버가 받지 않는 형식인 영상을 720p·약 2.5 Mbps H.264로 줄여 올립니다. 긴 영상은 300 MiB 안에 들도록 비트레이트를 더 낮춥니다. |
| 업로드 | 4 MiB 조각, 사용자당 진행 중 2개, 6시간 뒤 만료 |
| 서버 저장 공간 | 진행 중 업로드 전체 3 GiB, 디스크 여유 공간 최소 2 GiB |
| 멈춘 업로드 | 한도에 닿았을 때 5분 넘게 조각이 오지 않은 업로드를 먼저 정리 |

Gemini에 올린 파일은 분석이 성공하든 실패하든 바로 지웁니다. 서버의 영상은 분석에 성공했거나 영상 자체 문제로 실패하면 지웁니다. AI 사용량 초과, 일시 장애, 서버 재시작처럼 영상과 무관한 이유로 실패하면 다시 올리지 않고 재시도할 수 있게 만료(6시간) 때까지 남깁니다.

## API

모두 로그인 토큰이 필요합니다. 기존 `POST /v1/recipe-imports`는 만개의레시피 전용이며, YouTube 링크를 보내면 400 `VIDEO_IMPORT_REQUIRES_JOB`을 반환합니다.

| 요청 | 설명 |
| --- | --- |
| `POST /v1/recipe-import-jobs` | `{"url":"https://youtu.be/..."}` 또는 `{"uploadId":"..."}`. 새 작업은 202, 이미 있는 작업·완료된 레시피는 200 |
| `GET /v1/recipe-import-jobs/{id}` | `status`(QUEUED·RUNNING·SUCCEEDED·FAILED), `stage`, `recipeId`, `errorCode` |
| `POST /v1/video-uploads` | `{"contentType","sizeBytes","sha256","durationSeconds"}`. 새 업로드는 201, 같은 영상의 진행 중 업로드는 200 |
| `PUT /v1/video-uploads/{id}/chunks/{index}` | `application/octet-stream` 조각. 마지막 조각 말고는 정확히 4 MiB여야 하며, 같은 번호를 다시 보내면 덮어씁니다. |
| `GET /v1/video-uploads/{id}` | 받은 조각 목록으로 이어 올리기 |

휴대폰 영상은 `uploadId`로 작업을 만들 때 서버가 조각이 모두 있는지 확인하고, 합친 파일의 크기와 SHA-256이 처음 알린 값과 같은지 검사합니다.

`GET /v1/recipes/{id}` 응답에는 `sourceKind`(`WEB_PAGE` 또는 `VIDEO_AI`), `video`(`provider`, `videoId`, `pageUrl`, `durationSeconds`, `channel`, `embeddable`), 단계별 `videoStartSeconds`·`videoEndSeconds`·`keyFrameSeconds`가 추가됐습니다. 휴대폰 영상 레시피의 `sourceUrl`은 `hansul-upload:{uploadId}`입니다.

주요 오류 코드:

| 코드 | HTTP | 의미 |
| --- | --- | --- |
| `VIDEO_LINK_NOT_SUPPORTED` | 422 | 인스타그램·틱톡·네이버TV 등 |
| `INVALID_RECIPE_URL` | 400 | 지원하지 않는 링크나 잘못된 영상 ID |
| `VIDEO_IMPORT_DAILY_LIMIT` | 429 | 24시간 분석 횟수 초과 |
| `VIDEO_UPLOAD_LIMIT` | 429 | 진행 중 업로드 수·서버 저장 공간 한도 |
| `VIDEO_UPLOAD_TOO_LARGE` / `VIDEO_TOO_LONG` | 413 / 422 | 300 MiB 또는 30분 초과 |
| `UPLOAD_INCOMPLETE` / `UPLOAD_CHECKSUM_MISMATCH` / `VIDEO_UPLOAD_CLOSED` | 409 | 조각 누락, 해시 불일치, 이미 끝난 업로드 |
| `VIDEO_ANALYSIS_UNAVAILABLE` | 503 | Gemini 키 없음, 또는 결제 등급 확인 없이 휴대폰 영상 요청 |

작업 실패 코드(`errorCode`)는 `VIDEO_UNAVAILABLE`, `VIDEO_TOO_LONG`, `NOT_A_RECIPE_VIDEO`, `RECIPE_EXTRACTION_FAILED`, `AI_QUOTA_EXCEEDED`, `AI_BLOCKED`, `AI_UNAVAILABLE`, `SERVER_RESTARTED`, `INTERNAL_ERROR`입니다. 앱은 각 코드를 한국어 안내로 바꿔 보여 줍니다.

## 검증 상태

자동 검사로 확인한 것 (2026-11-05, 이 PC):

- 서버 `./gradlew.bat test`: 610개 통과, 실패 0. Testcontainers PostgreSQL 18.6으로 DB 마이그레이션과 업로드·분석 작업의 전체 흐름을 시험했습니다. Gemini와 YouTube는 가짜 서버로 대신했습니다.
- Android `:app:testDebugUnitTest :app:assembleDebug`: 162개 통과, 실패 0, debug APK 생성.
- 서버와 앱의 YouTube 링크 판별 표(27개 링크)가 같은지 양쪽 테스트가 확인합니다. 요청·응답 JSON 예시도 양쪽에서 같은 내용으로 확인합니다.
- 실제 만개의레시피 페이지 세 개(YouTube 영상·네이버TV 영상·영상 없음)를 줄인 HTML로 서버와 앱의 페이지 해석을 시험했습니다.

아직 확인하지 않은 것:

- 실제 Gemini API 호출. 응답 스키마 키워드 지원, 한국어 받아쓰기 품질, 분석 시간과 비용을 확인하지 않았습니다.
- 실제 YouTube Data API·oEmbed 호출.
- 휴대폰·에뮬레이터 실행. 공유 받기, 동의 화면, 영상 압축(Media3), 업로드 이어 올리기, YouTube 임베드 재생(Referer 허용 여부 포함), 백그라운드 전환 때 재생 멈춤, WebView가 없는 기기 처리를 확인하지 않았습니다.
- 배포. AWS에는 아무것도 배포하지 않았습니다.

## 알려진 제약

- YouTube Data API 키가 없으면 링크 영상의 길이를 미리 알 수 없어, 30분이 넘는 영상도 Gemini 분석이 시작됩니다.
- YouTube에서 가져온 정보와 레시피는 갱신하지 않고 계속 씁니다. YouTube API 약관의 30일 갱신 규정을 따르려면 스키마 변경과 보존 정책 결정이 필요합니다.
- 앱에서 분석을 취소해도 서버 작업은 계속됩니다. 업로드를 지우는 API도 없어, 취소한 업로드는 한도에 닿았을 때 5분 이상 멈춘 것부터 정리됩니다.
- 사용자당 한도는 동시에 들어온 요청 몇 개가 함께 통과할 수 있는 느슨한 한도입니다.
- 서버를 한 대만 띄운다고 가정합니다. 잠금, 1분마다 도는 멈춘 작업 복구, 업로드 저장소가 모두 한 프로세스 기준입니다.
- AI가 만든 레시피는 v1에서 고칠 수 없고 안내 문구와 함께 그대로 보여 줍니다. YouTube 임베드는 전체 화면을 지원하지 않습니다.
- 휴대폰 영상이 Google Gemini로 전송된다는 내용을 개인정보 처리방침과 Play 데이터 보안 양식에 적어야 합니다.
- 영상 기능 이전에 저장한 만개의레시피 레시피는 열 때 한 번 페이지를 다시 읽어 영상만 추가합니다(최대 8초). 페이지 구조가 바뀌어 실패하면 열 때마다 다시 시도합니다.
