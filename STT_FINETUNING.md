# STT 파인튜닝 메모

삼성 폰 STT의 한국어 인식 정확도가 부족해서 서버 STT(Qwen3-ASR)를 평가하고, 필요하면 파인튜닝하기 위한 기록.
조사 시점: 2026-11-15. 아래 수치와 주장은 출처의 보고이며 우리 데이터로 검증하지 않았다.

## 0. 현재 갖춰진 것

- **데이터 수집 (앱):** 설정 "학습용 녹음 저장"을 켜면 폰에 쌓인다.
  - `Download/ai_buds/finetune/audio/*.wav`: 16kHz mono PCM16
  - `Download/ai_buds/finetune/metadata.jsonl`: `{"audio","text","stt":"phone"|"server","mic":"communication"|"recognition","duration","created"}`
  - 아무것도 못 들은 녹음도 `"text":""`로 저장된다. 인식기가 가장 약한 사례라 평가에 꼭 포함한다.
  - `text`는 인식기 출력이라 삼성/Qwen의 오류가 그대로 들어 있다. **사람이 고친 정답만 학습·평가에 쓴다.**
- **마이크 입력 모드:** 설정 "마이크: 음성 인식 모드 (실험)". 끄면 `VOICE_COMMUNICATION`(통화용: 잡음 억제·자동 음량), 켜면 `VOICE_RECOGNITION`(처리 최소화). 녹음마다 `mic`로 남으니 두 모드를 번갈아 모아 `stt_eval score ./finetune --by mic`로 모드별 CER을 비교한다.
- **꺼내기:** `adb pull /sdcard/Download/ai_buds/finetune ./finetune` (adb가 없으면 USB 파일 전송으로 `Download/ai_buds/finetune` 폴더를 통째로 복사)
- **녹음 품질 점검:** `python3 tools/audio_check.py ./finetune`
  - 파일별 음량·클리핑·잡음 바닥·SNR, 말 앞뒤 잘림(H/T), 4.5~7 kHz 대역 에너지(hf)를 보여 주고 `mic`별로 요약한다.
  - hf 중앙값이 -45 dB 아래면 8 kHz 통화 경로(CVSD)로 연결된 것이다. adb 없이 코덱을 간접 확인하는 방법.
- **평가 도구:** `tools/stt_eval.py`
  - `run ./finetune --engine qwen3=http://127.0.0.1:8081` → `review.tsv`를 만든다.
  - `review.tsv`의 `ref` 칸을 정답으로 고치고 `reviewed=1`로 표시한다.
  - `score ./finetune [--show]` → CER(공백·문장부호 무시)과 완전 일치율을 낸다.
- **학습 장비:** RTX 5060 Ti 16GB.

## 1. 데이터 분량과 분할

| 분량 | 발화 수 (3~4초) | 용도 |
|---|---|---|
| 5분 | ~80 | 오류율이 2배쯤 차이 날 때만 판별 가능 |
| 10~15분 | 150~250 | 엔진 비교 (평가용 1차 목표) |
| 30분 | ~500 | 1~2%p 차이, 파인튜닝 전후 비교 |
| 1~2시간+ | | 학습용 최소치 |

- **평가용과 학습용을 분리한다.** 먼저 모은 10~15분을 평가용으로 고정하고, 학습에는 절대 넣지 않는다.
- **상황을 다양하게 섞는다:** 걷기, 길거리, 실내, 이어폰 마이크와 폰 마이크, 짧은 명령과 긴 질문, 고유명사와 숫자.
- **교정 비용:** 200문장에 약 1시간. 작은 평가셋으로 먼저 보고 애매할 때만 늘린다.

## 2. 학습 없이 먼저 해볼 것

1. **문맥 편향 (context biasing).** Qwen3-ASR은 시스템 프롬프트의 문맥 토큰을 배경지식으로 쓰도록 학습되어 있다 (기술 보고서).
   - 자주 쓰는 고유명사·기기 이름·직전 대화 몇 줄을 **system 메시지**에 넣는다.
   - 현재 앱은 STT 프롬프트를 user 메시지에 넣는다. `SttClient`와 `stt_eval.py`에서 system 메시지로 옮겨 실험해야 한다.
   - 공식 프롬프트 형식은 공개되지 않았으므로 몇 가지 형식을 A/B로 비교한다.
   - 단어 목록이 크면 전부 넣지 말고 관련 단어만 골라 넣는다 (검색, CTC 1차 결과, 발음 유사도로 필터링하는 연구들).
2. **LLM 후보정.** 대화 LLM이 문맥을 알고 있으니 "음성 인식 오류가 있을 수 있으니 문맥에 맞게 해석하라"는 지시를 시스템 프롬프트에 넣는다.

이 둘을 `stt_eval`로 비교한 뒤에도 부족하면 파인튜닝으로 간다.

## 3. 파인튜닝 레시피

### 공식 스크립트
- `QwenLM/Qwen3-ASR` 저장소의 `finetuning/qwen3_asr_sft.py` (전체 파라미터 SFT, `torchrun`으로 멀티 GPU).
- **데이터 형식 (jsonl):** `{"audio": "/path/x.wav", "text": "language Korean<asr_text>정답 문장"}`
  - 언어를 모르면 `language None<asr_text>...`
  - `metadata.jsonl`과 `review.tsv`(reviewed=1, ref)를 이 형식으로 바꾸는 변환 스크립트가 필요하다. 아직 만들지 않았다.
- **예시 설정:** `--batch_size 32 --grad_acc 4 --lr 2e-5 --epochs 1 --save_steps 200`. `--resume 1`로 이어서 학습할 수 있다.
- FlashAttention 2(bf16)를 권장한다. LoRA나 freeze 옵션은 없다.

### 16GB에서의 현실
- 1.7B 전체 학습은 AdamW 기준으로 가중치·그래디언트·옵티마이저 상태만 약 27GB라서 **16GB에 안 들어간다**.
- 0.6B 전체 학습은 약 10GB에 활성화 메모리가 더해져 들어갈 가능성이 있다.
- 1.7B를 쓰려면 다음 중 하나가 필요하다.
  - **LoRA** (PEFT로 직접 감싸기. 커뮤니티 예: r=16, alpha=32, q_proj/v_proj)
  - 8bit/paged 옵티마이저와 gradient checkpointing
  - 인코더 고정
- 하이퍼파라미터와 데이터 구성은 0.6B로 빠르게 정하고, 마지막에 1.7B LoRA를 시도한다.

### 전체 학습 vs LoRA
결과가 엇갈리므로 평가셋으로 직접 비교한다.
- 한국어 소규모 데이터(Whisper-small, Interspeech 2025)에서는 LoRA/adapter가 전체 학습보다 약간 나았다 (CER 약 19% 상대 감소).
- Qwen3-ASR-0.6B 아랍 방언 모델 카드에서는 LoRA가 과적합해 오히려 나빠졌고, 전체 학습과 최적 체크포인트 선택이 나았다.
- 2022년 SSL 연구에서는 저자원에서 LoRA가 adapter보다 못했다.

### 망각 방지 (일반 발화 성능 유지)
- **replay:** 일반 한국어 공개 데이터(Zeroth-Korean, KsponSpeech 일부 등)를 섞어 학습한다.
- **인코더 고정, 디코더/LLM 쪽만 학습:** 인코더를 다시 학습하면 일반화가 깨지기 쉽다.
- **평가 기반 체크포인트 선택과 조기 종료:** 우리 평가셋과 일반 평가셋을 함께 본다.

### 데이터 늘리기
- 재생 속도 0.9배·1.1배 (데이터 3배)
- 소음이나 잔향 섞기
- SpecAugment
- **TTS 합성:** 원하는 분야의 문장을 Qwen3-TTS 등으로 합성하고 일반 데이터를 섞어 학습했더니, 그 분야의 CER이 약 24%에서 약 6%로 줄었다는 사례가 있다. 실제 녹음이 부족할 때 쓴다.

## 4. 학습 속도
- bf16과 FlashAttention 2
- 길이별로 묶어 배치를 만들면(length bucketing) 패딩 낭비가 줄어든다.
- 0.6B로 반복 실험을 한다.
- Unsloth: Whisper 기준 약 1.5~2배 빠르고 VRAM이 절반쯤 든다고 한다. Qwen3-ASR 지원은 확인하지 못했다.
- ms-swift로 Qwen3-ASR-1.7B를 LoRA 학습하고 병합한 모델 카드가 있다. LoRA 경로의 대안이다.

## 5. 대안 엔진
- Qwen3-ASR로 부족하면 Nemotron-3.5-Transcribe를 검토한다. 한국어 보정 데이터로 REAP 방식 전문가 가지치기를 하는 방안이다.
- `akhaliq/nemotron-3.5-transcribe-mini-64e`를 GGUF로 변환해 `stt_eval`에 엔진으로 추가해 비교한다.

## 5-1. 음성 입력 LLM (STT 단계 없애기) 후보
- llama.cpp 공식 문서에 음성 입력이 있다고 나온 모델: Qwen3-Omni-30B-A3B, Gemma 4 E2B/E4B, Qwen2.5-Omni, Voxtral, Ultravox.
- **NVIDIA-Nemotron-3.5-Bolt-E4B** (2026-10, Gemma-4-E4B-it를 GRPO로 추가 학습. 8B, 실제 연산은 4B)
  - NVIDIA 공식 카드에는 입력이 "Text"로만 적혀 있고 음성 평가도 없다.
  - 그러나 오디오 인코더가 남아 있다. bartowski와 unsloth GGUF에 mmproj가 있고, 크기가 992MB로 원본 Gemma 4 E4B와 같다. bartowski는 "text, image and audio (with mmproj)"라고 적었다. 서드파티 측정으로 영어 LibriSpeech WER이 약 3.8%다.
  - 추가 학습 언어에 한국어가 없다. 한국어 음성 이해가 원본보다 나빠졌을 수 있으니 **원본 Gemma 4 E4B와 함께 `stt_eval`로 비교한다.**
  - Q4가 약 5GB에 mmproj 약 1GB라서 16GB GPU에 다른 모델과 함께 올릴 수 있다.

## 6. 진행 순서 (체크리스트)
- [ ] 평가용 10~15분 수집 → `stt_eval run` → 정답 교정 → `score` (삼성 대 Qwen3-ASR)
- [ ] 문맥 프롬프트를 system 메시지로 넣은 경우와 넣지 않은 경우 비교 (`stt_eval`에 옵션 추가 필요)
- [ ] 학습용 1~2시간 수집 및 교정, `metadata`를 SFT jsonl로 바꾸는 변환 스크립트
- [ ] 0.6B 전체 학습: 실제 녹음과 속도 변형, 일반 데이터 replay → 평가
- [ ] 1.7B LoRA → 평가, 0.6B 전체 학습과 비교
- [ ] 이긴 모델을 GGUF로 변환해 llama-server router에 등록하고 앱에서 sttModel로 선택

## 출처
- Qwen3-ASR: [GitHub](https://github.com/QwenLM/Qwen3-ASR), [finetuning](https://github.com/QwenLM/Qwen3-ASR/tree/main/finetuning), [Technical Report](https://arxiv.org/html/2601.21337)
- Qwen3-ASR LoRA 커뮤니티 튜토리얼: [CSDN 1](https://deepseek.csdn.net/6a76aa4110ee7a33f2980e0a.html), [CSDN 2](https://deepseek.csdn.net/6a7d504b10ee7a33f29a551e.html)
- 모델 카드 사례: [qwen3-asr-0.6b-arabic-dialectal](https://friendli.ai/models/oddadmix/qwen3-asr-0.6b-arabic-dialectal), [lemura-arabic-asr-qwen3](https://friendli.ai/models/lemuralabs/lemura-arabic-asr-qwen3), [Bro-ASR-1.7B](https://friendli.ai/models/Junlaii/Bro-ASR-1.7B), [Fine-Tuning Efficient Chinese Speech Models](https://evergreentree.github.io/speech2text/)
- [NVIDIA NeMo: Fine-tune Qwen3-Omni for ASR](https://docs.nvidia.com/nemo/automodel/latest/recipes-e2e-examples/qwen3-omni-asr)
- 저자원 학습: [Korean atypical speech LoRA/adapter (Interspeech 2025)](https://www.isca-archive.org/interspeech_2025/park25g_interspeech.pdf), [Efficient-tuning in SSL speech models](https://arxiv.org/pdf/2210.06175), [Damania et al. 2022](https://www.isca-archive.org/interspeech_2022/damania22_interspeech.pdf), [SpecAugment policy](https://arxiv.org/pdf/2210.08520), [Hakka ASR (ROCLING 2025)](https://aclanthology.org/anthology-files/pdf/rocling/2025.rocling-main.59.pdf)
- 망각 방지: [Frozen Whisper encoder](https://arxiv.org/pdf/2407.17874), [Whisper continual learning](https://www.themoonlight.io/review/continual-learning-with-embedding-layer-surgery-and-task-wise-beam-search-using-whisper), [Adapter-only bridging](https://scholarworks.sogang.ac.kr/item/f8533599-330a-4d8d-9fac-da9af63130a8), [Soft prompt tuning](https://arxiv.org/pdf/2506.21576v1)
- 문맥 편향: [Hotword retrieval + RL](https://arxiv.org/abs/2512.21828v1), [CTC-filtered hotwords](https://pdf.arxiv.org/pdf/2411.06437), [Phonetic name retrieval](https://www.arxiv.org/pdf/2409.15353)
- 속도: [Unsloth STT/TTS](https://docs.unsloth.ai/basics/text-to-speech-tts-fine-tuning), [Trelis: Whisper + Unsloth](https://trelis.substack.com/p/whisper-data-preparation-and-fine)
