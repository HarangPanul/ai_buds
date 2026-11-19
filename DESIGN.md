# ai_buds 설계

이어폰을 낀 채 볼륨 키로 말을 걸면, PC의 LLM이 답하고 그 답을 이어폰으로 읽어 주는 Android 앱이다.

이 문서는 `android/`의 새 앱을 설명한다.
새로 만든 목적은 기능을 고치거나 더할 때 손댈 곳이 한 군데로 모이게 하는 것이다.

## 1. 전체 그림

```
┌──────────────── 폰 (Android 13+) ────────────────┐         ┌──────── PC ────────┐
│ 이어폰 볼륨 올림 → 내림                          │         │                    │
│        │                                         │         │  LLM 서버          │
│        ▼                                         │   SSH   │  (llama.cpp,       │
│   듣기 ─────────▶ 생각하기 ─────────▶ 말하기     │  터널   │   ollama, vllm …)  │
│ 폰 음성 인식    OpenAI 호환 API     폰 내장 TTS  │◀───────▶│  /v1/chat/         │
│ (이어폰 마이크)  (스트리밍)        (이어폰 출력) │         │   completions      │
└──────────────────────────────────────────────────┘         └────────────────────┘
```

- 앱은 설정에 적힌 주소(기본 `http://127.0.0.1:8080`)로만 통신한다. SSH 터널은 다른 앱이 맡는다.
- 턴을 시작하는 방법은 **볼륨 올림 → 내림** 하나뿐이다. 화면에는 말하기 버튼이 없다.
- 앱은 최근 앱 화면에 있는 동안만 동작한다. 밀어서 닫으면 끝나고, 시스템이 종료해도 스스로 되살아나지 않는다.

## 2. 패키지와 규칙

코드는 `android/app/src/main/java/com/example/aibuds/` 아래 세 패키지로 나뉜다. 모듈은 하나다.

| 패키지 | 내용 | 규칙 |
|---|---|---|
| `core` | 턴의 흐름, 이벤트, 화면 상태, 바깥에 요구하는 인터페이스 | Android와 네트워크 라이브러리를 import하지 않는다. 전부 가짜 구현으로 단위 테스트한다. |
| `platform` | `core`의 인터페이스 구현: 마이크, 음성 인식, TTS, 신호음, LLM 통신, 저장 | `core`만 안다. `app`을 모른다. |
| `app` | 조립(`AppContainer`), 서비스, 화면 | 여기서만 부품을 고르고 잇는다. |

의존 방향은 `app → platform → core` 한쪽뿐이다.

### 파일 지도

```
core/
  Conversation.kt        입구. trigger / cancel / newConversation, 이벤트와 화면 상태를 내보낸다
  PipelineTurn.kt        한 턴: 듣기 → LLM → 말하기 (thinking 읽기 포함)
  Ports.kt               인터페이스: Listener, Brain, Voice, Cues, TurnEngine + Utterance, BrainChunk, Cue
  TurnEvent.kt           턴이 내보내는 이벤트
  ConversationState.kt   이벤트로 만드는 화면 상태
  History.kt, Message.kt 대화 내용
  VolumeTrigger.kt       볼륨 올림 → 내림 + 이어폰 확인 → 트리거
  VolumeGestureDetector.kt, SentenceSplitter.kt, PromptClock.kt   작은 순수 로직

platform/
  audio/   AudioRoute(이어폰 경로), Microphone(녹음·무음 판단·WAV), Beeper(신호음), Wav
  speech/  PhoneListener(폰 음성 인식), SystemVoice(내장 TTS)
  llm/     OpenAiBrain(스트리밍 대화), Dialect(서버별 차이), LlamaModels(모델 목록·로드), ServerCheck, Http
  settings/ SettingsStore
  store/   FinetuneDataset(학습용 녹음), EventLog(동작 기록)

app/
  AppContainer.kt        부품 조립, 턴마다 설정으로 한 턴 꾸리기, 이벤트 구독
  ConversationService.kt 포그라운드 서비스 + 볼륨 변경 방송 수신
  TurnLog.kt             이벤트 → 동작 기록 한 줄
  ui/                    MainActivity, MainScreen, SettingsScreen, MainViewModel
```

## 3. 한 턴이 도는 과정

```
볼륨 키(화면 켜짐)  ─┐
볼륨 변경 방송(꺼짐) ─┴▶ VolumeTrigger ──▶ Conversation.trigger()
                                                 │
                              newTurn(): 지금 설정으로 TurnEngine을 새로 만든다
                                                 │
                                                 ▼
                    PipelineTurn.run():  시작음 → Listener → Brain → Voice
                                                 │
                                          TurnEvent 흐름
                              ┌──────────────────┼───────────────────┐
                              ▼                  ▼                   ▼
                      ConversationState     동작 기록          학습용 녹음 저장
                         (화면)            (TurnLog.kt)        (FinetuneDataset)
```

### 3.1 Conversation: 입구

- `trigger()`: 새 턴을 시작한다. 돌던 턴이 있으면 중단한다(끼어들기).
- `cancel()`: 돌던 턴을 중단한다.
- `newConversation()`: 중단하고 대화 내용을 비운다.

한 번에 한 턴만 돈다. 앞 턴이 마이크와 재생을 다 정리하고 `Cancelled`를 알린 뒤에야 다음 턴이 시작된다.
턴의 시작(`Triggered`)과 끝(`Done` / `Failed` / `Cancelled`)은 `Conversation`이 알리고, 실패하면 오류 신호음을 낸다.

### 3.2 TurnEngine: 한 턴을 실행하는 방법

`TurnEngine`은 "한 턴을 어떻게 실행하는가"를 감싼 인터페이스다. 지금 구현은 `PipelineTurn` 하나다.

`PipelineTurn`의 순서:

1. 시작음(`Cue.ListenStart`)을 다 울린 뒤 `Listener.listen()`으로 듣는다.
2. 아무 말도 없으면 `NothingHeard`를 알리고 신호음을 낸 뒤 끝낸다.
3. 들었으면 `Heard`를 알리고, 대화 내용에 넣고, `Brain.chat()`으로 답을 받는다.
4. 답은 조각으로 온다. `SentenceSplitter`로 문장을 끊어 `Voice.speak()`로 차례로 읽는다.
5. thinking 읽기가 켜져 있으면, 답변이 시작되기 전까지 모델의 thinking을 읽는다.
   답변의 첫 조각이 오면 읽던 문장을 끊고, 신호음(`Cue.ReplyStart`)을 낸 뒤 답변을 읽는다.
6. 중단되거나 실패해도 그때까지 받은 답변은 대화 내용에 남긴다.

### 3.3 인터페이스 (`core/Ports.kt`)

| 인터페이스 | 하는 일 | 지금 구현 |
|---|---|---|
| `Listener` | 마이크를 열고, 말이 끝나면 스스로 마치고 `Utterance`(글, 오디오, 마이크 정보)를 돌려준다 | `PhoneListener` |
| `Brain` | 대화 내용을 받아 thinking 조각과 답변 조각을 흘려보낸다 | `OpenAiBrain` |
| `Voice` | 한 문장을 다 읽을 때까지 기다린다 | `SystemVoice` |
| `Cues` | 신호음 하나를 다 울릴 때까지 기다린다 | `Beeper` |
| `TurnEngine` | 한 턴을 실행한다 | `PipelineTurn` |

공통 규칙: **멈춤은 코루틴 취소 하나로 한다.** 인터페이스에 `stop()`이나 `cancel()`이 없다.
구현체는 취소되면 하던 일(녹음, 재생, HTTP)을 즉시 멈추고 자원을 정리해야 한다.

### 3.4 이벤트와 화면 상태

`Conversation.events`로 `TurnEvent`가 흘러나온다.

`Triggered` → `Listening` → `Partial`(중간 인식) → `Heard` 또는 `NothingHeard` → `Thinking` → `ReplyDelta`(답변 글자) / `Speaking`(읽기 시작한 문장) → `Done` / `Failed` / `Cancelled`

- 턴 본체에 넣지 않아도 되는 기능은 이 이벤트를 구독해서 만든다. 지금은 동작 기록과 학습용 녹음 저장이 그렇다.
- 화면은 이벤트를 직접 보지 않는다. `Conversation.state`(`ConversationState`)만 그린다.
  상태는 `ConversationState.after(event)`라는 순수 함수로 만들어진다.
- 구독자가 밀리면 턴이 기다린다. 이벤트를 받은 자리에서 오래 걸리는 일은 따로 코루틴을 띄워서 한다.

신호음만은 구독이 아니라 턴 안에서 직접 부른다. 시작음이 끝난 뒤에 들어야 하는 것처럼 순서가 중요하기 때문이다.

### 3.5 설정은 턴마다 한 번 읽는다

`AppContainer.newTurn()`이 턴을 시작할 때 그 순간의 설정으로 `PhoneListener`, `OpenAiBrain`, `PipelineTurn`을 새로 만든다.
턴 도중에 설정을 바꿔도 그 턴에는 영향이 없고, 다음 턴부터 적용된다.
어떤 엔진을 쓸지 고르는 일도 여기서 한다.

## 4. 부품별 설명

### 4.1 마이크 (`platform/audio/Microphone.kt`)

듣는 쪽은 모두 `Microphone`을 통해 소리를 받는다. 맡는 일은 다음과 같다.

- 이어폰 마이크 경로를 잡고, 끝나면(실패·취소 포함) 놓는다.
- 16 kHz mono PCM16으로 녹음한다.
- 무음 판단: 소리 크기가 문턱값을 넘지 않은 채 일정 시간이 지나면 말이 끝난 것으로 본다.
- 녹음되는 소리를 스트림으로 내주고, 끝나면 전체를 WAV로 내준다.

폰 음성 인식기가 마이크를 직접 열게 두지 않는다. 앱이 녹음한 소리를 인식기에 넘긴다(Android 13 이상에서 가능).
그래서 어떤 엔진을 쓰든 같은 소리를 듣고, 매 턴의 오디오가 항상 남는다.

**이어폰 경로:** 블루투스 이어폰의 마이크는 통화용 연결로 바꿔야 열린다. 그동안은 소리가 통화 음량으로 나간다.
그래서 듣기 시작할 때 소리 크기가 달라질 수 있다. 통화 음량을 미디어 음량에 맞추는 기능은 이전 앱에서 제대로 동작하지 않아 넣지 않았다.

**마이크 모드:** 통화 모드는 폰이 잡음 억제·자동 음량을 걸고, 음성 인식 모드는 가공을 줄인다. 설정에서 고른다.

### 4.2 폰 음성 인식 (`platform/speech/PhoneListener.kt`)

폰의 기본 음성 인식 서비스(삼성/구글)에 `Microphone`의 소리를 넘겨 받아 적게 한다.

- 말이 끝났는지는 인식기가 아니라 마이크의 무음 판단을 따른다. 중간 결과가 늘고 있으면 아직 말하는 중으로 본다.
- 입력을 끝낸 뒤 인식기가 5초 안에 결과를 주지 않으면 중간 결과로 마무리한다.

### 4.3 LLM (`platform/llm/`)

- `OpenAiBrain`: OpenAI 호환 `/v1/chat/completions`를 스트리밍으로 부른다. 수집이 취소되면 연결을 바로 끊는다.
- `Dialect`: 서버마다 다른 부분만 담는다.
  - `LlamaCpp`: 프롬프트 캐시를 켜고, 모델이 내려가 있으면 올린 뒤 한 번 더 시도한다.
  - `OpenAiCompatible`: 표준 그대로. ollama, vllm은 이걸로 쓴다.
- thinking은 서버에 따라 `reasoning_content` 또는 `reasoning`으로 오는데, 둘 다 읽는다.
- `ServerCheck`: `/v1/models`를 불러 서버에 닿는지 확인한다.

ollama와 vllm에는 아직 실제로 연결해 보지 않았다.

### 4.4 서비스와 트리거

- `ConversationService`는 포그라운드 서비스로 프로세스를 붙잡아 두고, 볼륨 변경 방송을 받아 `VolumeTrigger`에 넘긴다. 대화 자체는 하지 않는다.
- 앱이 화면에 있을 때는 `MainActivity`가 볼륨 키를 직접 받아 같은 `VolumeTrigger`에 넘긴다.
- 같은 누름이 두 경로로 들어와도 한 번만 트리거한다.
- 이어폰이 연결돼 있지 않으면 트리거를 무시한다. 답이 폰 스피커로 흘러나오지 않게 하기 위해서다.

### 4.5 저장

- **설정** (`SettingsStore`): 이전 앱과 같은 파일·같은 키를 쓴다. 덮어쓰기 설치하면 쓰던 값이 이어진다.
- **학습용 녹음** (`FinetuneDataset`): `Download/ai_buds/finetune`에 WAV와 `metadata.jsonl`로 쌓는다. 못 들은 녹음도 글이 빈 채로 저장한다. 형식은 이전 앱과 같아서 `tools/`의 도구가 그대로 읽는다.
- **동작 기록** (`EventLog`): 앱 내부 저장소의 `events.log`. adb 없이 기기에서 원인을 보려고 둔다. 설정에서 "디버그 정보 표시"를 켜면 설정 화면 맨 아래에서 보고 복사할 수 있다. 말한 내용은 남기지 않고 길이만 남긴다.

### 4.6 화면

- 메인 화면: 연결 상태, 대화 내용, 진행 상태, 오류, "중단", "새 대화", "설정".
- 디버그용 표시(LLM 주소, 사용한 마이크, 동작 기록)는 설정의 "디버그 정보 표시"가 켜져 있을 때만 보인다. 새 디버그 표시도 이 스위치 뒤에 둔다.

## 5. 고칠 때 어디를 손대나

| 하고 싶은 일 | 손댈 곳 |
|---|---|
| 새 STT 엔진 (서버 STT 등) | `platform/speech`에 `Listener` 구현 추가. 소리는 `Microphone`에서 받는다. `AppContainer.newTurn()`에서 설정에 따라 고른다. |
| 새 LLM 서버 종류 | `Dialect` 구현 추가, `Dialect.of()`와 설정 화면의 선택지에 한 줄. |
| OpenAI 호환이 아닌 LLM | `platform/llm`에 `Brain` 구현 추가. |
| 다른 TTS | `platform/speech`에 `Voice` 구현 추가. |
| 음성을 바로 음성으로 바꾸는 모델 | `TurnEngine` 구현 추가(듣기·LLM·말하기 세 단계에 맞지 않으므로). 소리는 `Microphone`에서 받는다. |
| 턴 진행에 따라 무언가 하기 (기록, 저장, 알림) | `AppContainer`에서 `conversation.events`를 구독. 턴 코드는 건드리지 않는다. |
| 설정 항목 추가 | `SettingsStore`(필드, load, save 각 한 줄) → `SettingsScreen`(편집 값, 입력칸, 저장 한 줄) → 쓰는 곳은 보통 `AppContainer.newTurn()`. |
| 신호음 종류 추가 | `Cue`에 항목, `Beeper`에 음, 울릴 자리(`PipelineTurn` 또는 `Conversation`)에 한 줄. |
| 새 트리거 | `Conversation.trigger()`를 부른다. |
| 화면에 새 정보 표시 | `ConversationState`에 필드와 `after()`의 해당 이벤트에 한 줄 → `MainScreen`. |
| 동작 기록에 남길 이벤트 | `app/TurnLog.kt`. |

## 6. 범위 밖으로 둔 것

- **웹 검색, 명령 실행 같은 skill:** 앱이 아니라 앱과 LLM 서버 사이의 에이전트 서버에서 다룬다.
  그 서버가 OpenAI 호환 API처럼 보이면 앱은 고칠 것이 없고, 진행 상황은 thinking 흐름에 실어 보내면 thinking 읽기가 읽어 준다.
  폰에서만 할 수 있는 일(알람 등)이 필요해지면 `BrainChunk`에 도구 요청 종류를 추가한다.
  - 아이디어(정해지지 않음): skill을 "전용 폴더에 있는 실행 파일"로 정의한다. 에이전트 서버가 명령을 실행할 때만 그 폴더를 PATH에 넣는다.
    skill 추가는 스크립트 하나를 넣는 것으로 끝난다. 정할 것: PATH에 추가만 할지, 그 폴더의 명령만 허용할지
    (음성을 받아 적은 글은 잘못 들릴 수 있어서 후자가 안전하다). 스크립트가 용도·인자 설명을 갖게 해 도구 목록을 자동으로 만들고,
    출력은 읽어 주기 좋은 짧은 말로, 되돌리기 어려운 명령은 한 턴 확인을 거치게 하는 안도 있다.
- **카메라 사진을 LLM에 넘기기(아이디어, 정해지지 않음):** 지속 스트리밍이 아니라 턴당 1장 스냅샷으로 한다.
  듣는 동안 카메라 프레임 한 장을 잡아(긴 변 1024px 안팎, JPEG) 질문과 함께 OpenAI 호환 `image_url`(base64)로 보낸다.
  `Message`에 선택적 이미지, `Ports.kt`에 `Camera`(`capture()`), 구현은 `platform/camera/`(CameraX).
  정할 것: 서버 모델이 이미지 입력을 받는지, Android 14+ 백그라운드 카메라 제약(화면 켜진 때만 가능할 수 있음),
  사진 턴을 구분하는 방법(설정 스위치 또는 별도 볼륨 제스처), 촬영 신호음.
  지속 스트리밍(저fps 라이브)은 이미지 프리필 지연과 배터리 때문에 나중 일.
- **화면의 말하기 버튼:** 볼륨 트리거만 쓴다.
- **통화 음량을 미디어 음량에 맞추기:** 4.1 참고.

아직 만들지 않은 것: 서버 STT, 음성 모델 실험, 마지막 녹음의 길이·소리 크기 표시.

## 7. 빌드와 테스트

```
cd android
./gradlew testDebugUnitTest assembleDebug
```

- APK: `android/app/build/outputs/apk/debug/app-debug.apk`
- 앱 ID와 서명 키(`app/debug.keystore`)가 이전 앱과 같다. 덮어쓰기 설치가 되고 앱 데이터가 남는다.
- 단위 테스트가 다루는 범위: `core` 전체, `platform/llm`(가짜 HTTP 서버).
- 단위 테스트가 다루지 못하는 범위: 마이크, 음성 인식, TTS, 신호음, 서비스, 화면. 이 부분은 기기에서 확인해야 한다.
