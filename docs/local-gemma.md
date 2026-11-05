# 서버 없이 사용하는 로컬 조리 모드

한술의 로컬 모드는 로그인 없이 만개의레시피 링크를 가져오고, 기기에 저장한 레시피·원문 단계 사진·조리 상태·타이머로 요리합니다. AI 질문은 기기에 설치한 Gemma 4 E2B가 처리합니다. 클라우드 모드도 유지하며, 레시피·세션 ID의 출처로 저장소를 선택하므로 모드를 바꿔도 기존 조리 세션이 다른 서버로 이동하지 않습니다.

로컬 AI는 현재 실험적 기능입니다. 실제 S23 Ultra에서 GPU 추론은 동작하지만 질문 대상 판정·사진 선택·인분 맥락 오류가 실기 평가에서 발견되어 검증과 원문 검색을 보강하고 있습니다. 모든 요리 상황의 정확한 판단이나 즉각 응답을 보장하지 않습니다.

## 인터넷과 서버의 구별

- 새 레시피 URL을 읽을 때는 만개의레시피 사이트에 직접 접속합니다. 자체 백엔드는 필요하지 않습니다.
- 가져온 원문 텍스트와 성공적으로 내려받은 단계 사진은 기기에 보존합니다. 사진 캐시는 각 파일 5초·전체 10초 한도이며, 못 받은 사진은 원문 URL로 남으므로 인터넷이 필요할 수 있습니다. 레시피 반환 뒤 사진 URL을 바꾸는 백그라운드 작업은 하지 않습니다.
- 공식 모델을 최초 다운로드할 때 인터넷과 약 2.59 GB의 저장 공간이 필요합니다. 임시 파일·여유 공간을 포함하여 약 3 GB 이상을 준비합니다.
- 모델 다운로드는 사용자가 설정 화면의 다운로드 버튼을 누르거나 파일 선택을 완료할 때만 시작합니다. 앱 시작, 레시피 가져오기, 조리 시작은 모델 다운로드를 시작하지 않습니다.
- 음성 인식 서비스의 한국어 오프라인 지원과 TTS 음성 설치 여부는 기기별로 확인해야 합니다. 로컬 LLM이 있다는 것만으로 음성까지 완전 오프라인이라고 보장하지 않습니다. 화면 버튼과 글로 질문하는 기능은 음성 서비스 없이 사용 가능합니다.

## 모델과 실행

Google 공식 Android 실행 경로인 LiteRT-LM Kotlin API를 사용합니다. 의존성은 `com.google.ai.edge.litertlm:litertlm-android:0.18.0`으로 고정했습니다. 네이티브 초기화·추론·종료는 별도 단일 스레드에서 실행하며, 최초 질문 전에는 모델을 메모리에 올리지 않습니다. 원문 단계 사진을 표시하는 기능은 모델의 비전 인코더를 로딩하지 않고 원문 단계 ID를 이용합니다.

사용자가 가져올 수 있는 배포물은 아래 검증된 두 파일입니다. 원래 선택한 파일을 직접 사용·삭제하지 않고 앱 전용 `noBackupFilesDir`에 복사합니다. 크기와 SHA-256을 확인한 파일만 선택 상태로 저장합니다. 중단되거나 검증에 실패한 임시 파일은 AI 모델로 활성화하지 않습니다.

| 파일 | 크기 | SHA-256 |
| --- | ---: | --- |
| `gemma-4-E2B-it.litertlm` | 2,588,147,712 bytes | `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c` |
| `gemma-4-E2B-it-gpu.litertlm` | 2,008,432,640 bytes | `a53a59001894c58e6bdb5b9b227709f91a2e3e556baa7d85acf9c55402ba5cf5` |

다운로드 revision은 `b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1`입니다. 새 모델을 채택하려면 파일 크기·체크섬과 실제 동작 검증을 함께 갱신합니다. Gemma 4와 LiteRT-LM은 Apache 2.0 라이선스입니다. 공식 모델에는 한국어를 포함한 다국어 능력이 있지만 실제 요리 표현의 품질은 실기 평가가 필요합니다.

범용 모델은 GPU 초기화 실패 시 CPU를 시도합니다. GPU 전용 파일은 CPU 호환성을 가정하지 않고 범용 모델을 안내합니다. NPU 실행은 이 변경에 포함하지 않습니다. S23 Ultra의 SM8550에 맞춘 Gemma 4 NPU 배포물을 검증하지 않았기 때문입니다. 공개 S26 Ultra 벤치마크를 S23 Ultra의 성능으로 사용할 수 없습니다.

## 상태와 조리량

화면에서 단계를 보는 `navigate`와 실제 완료/건너뛰기 `updateStepProgress`를 구분합니다. 건너뛰기는 완료로 기록하지 않으며 이전 단계들을 자동 완료하지 않습니다. 저장된 미완료 세션은 같은 레시피·인분으로 조리를 시작하면 재개합니다. 전체 세션 snapshot과 클라이언트 명령 ID를 AtomicFile로 보존합니다. 예약 대기 중 타이머도 저장하고, 기기 예약 확인 후 시작 시각·종료 시각을 기록합니다. 저장된 절대 마감으로 미래 알람을 앱 시작·재부팅·앱 업데이트·정확한 알람 권한 부여 때 재등록하는 코드를 구현했습니다. 기기를 실제 재부팅해 검증하지 않았으며 제조사 절전 환경의 동작을 보장하지 않습니다. Android 강제 종료 상태에서는 앱을 다시 열기 전 알람이 제한됩니다.

AI의 JSON 응답은 네이티브 constrained decoding으로 생성한 뒤 다시 검증합니다. 디코더 호환성 오류만 일반 JSON 생성 경로를 시도하고, 동일한 결과 검증을 적용합니다. 존재하는 원문 단계·사진·타이머 ID와 최신 발화의 완전한 근거 구절을 확인합니다. 질문 부호나 부정 표현을 잘라낸 근거는 허용하지 않고 재료 이름·대체 이름·수량도 같은 구절에 있어야 합니다. 부분 행동 보고는 대화 문맥으로 보존하지만, 전체 단계 완료는 ‘3단계 전체 완료했어’ 또는 현재 단계가 명확한 ‘지금 단계 다 했어’처럼 명시한 경우만 기록합니다.

사진 질문은 사진만 보여주며 현재 본문 단계를 바꾸지 않습니다. 명시적 화면 이동은 실제 조리 완료와 별개입니다. 타이머는 원문에서 단일 시간으로 확인한 것만 제공하며 시간 범위·분수·‘1시간 반’·최소/최대/전체 예상 시간은 추측하지 않습니다. AI의 타이머 수락은 실제 긍정 요청이 있어야 하며, 요청한 타이머만 예약 확인할 수 있습니다. 이미 요청·예약한 타이머를 새 명령 ID로 반복 예약하지 않습니다.

사진 번호가 현재 단계와 섞이는 모델 오류에 대비해 원문의 실제 구절과 응답 문장의 고유한 텍스트 근거를 다시 확인합니다. 원문에서 유일하게 확인한 행동 설명에 맞는 단계 사진만 사용하고 근거가 약하면 사진을 생략합니다. 이 과정은 원문 텍스트 검색·조사 정규화이며 이미지를 보고 자르기 크기나 사진 내용을 검증하는 비전 기능은 아닙니다.

잘못된 완료·재료·타이머 제안은 적용하지 않습니다. 유효한 답변은 보존하되 기록하지 않은 항목을 명확히 안내하고, 잘못된 타이머 수락이면 아직 시작하지 않았다고 답합니다. 추론 중에는 조리 저장소 잠금을 잡지 않아 수동 이동·타이머를 처리할 수 있습니다. 그 사이 상태가 바뀌면 최신 단계를 유지하고 이전 상태에서 생성한 진행 변경은 폐기합니다.

관련 없는 대화와 불확실한 발화는 조리 상태를 바꾸지 않습니다. 이 검증은 안전을 위해 보수적이므로 자연스러운 일부 표현에도 재확인을 요구할 수 있습니다. AI는 잘못 판단할 수 있으므로 단계 상태를 직접 수정할 수 있습니다. 모델 미설정·로딩 실패·잘못된 출력은 AI 사용 불가로 알리고 수동 조리는 유지합니다.

같은 인분은 원문 양을 그대로 보여줍니다. 단위와 수량을 정확히 읽은 경우만 모든 계산값을 ‘비례 참고’로 동일하게 표시하며, 재료 이름 목록으로 조리량을 분류하거나 보정하지 않습니다. 비례 참고값은 실제 조리 정량이 아니며 물·양념·제품 지침·냄비·증발량 등을 AI 질문과 원문으로 확인해야 합니다. 원문이나 제품별 지침 없이 라면 2개의 물을 특정 수치로 확정하거나 조리 시간을 배수로 계산하지 않습니다.

## 실제 기기 검증

유닛 테스트는 원문 사진 매핑, 안전하지 않은 사진 URL 거절, 원문량과 인분 비례 참고값 구별, 복합·소수 시간과 범위 시간, JSON 상태 변경 검증을 확인합니다.

`LocalPersistenceTest`는 실제 Android 저장소에서 모델 없이 수동 이동·건너뛰기·타이머 대기 상태와 중복 확인 명령의 복원을 검증합니다. `LocalConcurrentTurnTest`는 추론 중 수동 이동이 1초 안에 처리되고 오래된 완료 제안이 최신 상태를 덮어쓰지 않는지 확인합니다. `LocalGemmaSmokeTest`는 모델을 먼저 설치한 실제 기기에서 한국어 질문 3개와 원문 사진 선택을 확인합니다. 로그는 자가 작성한 평가용 레시피·질문·모델 JSON, 실행 backend, 로딩·전체 추론 시간, 프로세스 `VmHWM`만 기록합니다. JSON 로깅은 테스트의 fixture flag가 켜진 경우에만 사용하고 실제 사용자 조리 기록·토큰은 로그에 쓰지 않습니다. `VmHWM`은 앱 프로세스의 peak RSS이며 GPU 메모리 전체를 나타내지는 않습니다.

2026-10-13 S23 Ultra SM-S918N의 초기 실기 평가에서는 GPU 실행이 확인됐고 모델 로딩 6.4~14.4초, 질문별 전체 추론 약 11.8~19.0초였습니다. 프로세스 peak RSS는 약 1.1~1.2 GB였으나 GPU 전체 메모리가 아닙니다. 저장소 복원·추론 중 수동 이동 검증은 통과했습니다. 초기 한국어 3문항 평가는 사진 선택과 원문/목표 인분 구별 때문에 실패했으며, 이를 안전 검증·사진 원문 재검색·인분 구조화 문맥으로 보강한 뒤 재평가 중입니다. 이 결과는 광범위한 품질 또는 실제 주방 소음에서의 음성 테스트를 뜻하지 않습니다.

```powershell
powershell -NoProfile -Command "Set-Location android; .\gradlew.bat :app:assembleDebugAndroidTest"
adb install -r android\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb shell am instrument -w -e class com.hansul.app.data.local.LocalPersistenceTest com.hansul.app.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class com.hansul.app.data.local.LocalConcurrentTurnTest com.hansul.app.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class com.hansul.app.ai.LocalGemmaSmokeTest com.hansul.app.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s HansulLocalBenchmark
```

## 공식 자료

- [Gemma 4 model card](https://ai.google.dev/gemma/docs/core/model_card_4)
- [공식 LiteRT-LM 모바일 배포 모델·메모리·벤치마크](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm)
- [Android LiteRT-LM 실행 API](https://developers.google.com/edge/litert-lm/android)
- [Google Maven release metadata](https://dl.google.com/dl/android/maven2/com/google/ai/edge/litertlm/litertlm-android/maven-metadata.xml)
- [Gemma 도구 호출과 결과 검증](https://ai.google.dev/gemma/docs/capabilities/text/function-calling-gemma4)
- [LiteRT-LM NPU 실행과 SoC별 배포물](https://developers.google.com/edge/litert/next/litert_lm_npu)
- [Android 기기 내 음성 인식 API](https://developer.android.com/reference/android/speech/SpeechRecognizer)
- [TTS Voice의 네트워크 요구 여부](https://developer.android.com/reference/android/speech/tts/Voice)
- [Apache 2.0 License](https://www.apache.org/licenses/LICENSE-2.0)
