# FlowWrite AI — System-Wide Offline Voice Typing Keyboard (English · Hinglish · Kanglish)

**Target Device:** Vivo Y200e (8 GB Physical RAM, `arm64-v8a`)  
**Primary Component:** Android `InputMethodService` (`FlowWriteKeyboardService.kt`) invokable in WhatsApp, Instagram, Chrome, Gmail, Notes, and any standard Android text field.  
**Local LLM:** Auto-detects accessible `Qwen2.5-0.5B-Instruct-Q4_K_M.gguf` via `LlamaBridge.discoverAccessibleLocalModels()` + `llama.cpp` JNI.  
**Modes:** `Clean` (Default), `Polish`, `Casual`, `Raw`, `Custom`  
**Language Styles:** `Auto Code-Mixed`, `Hinglish` (Latin script), `Kanglish` (Latin script), `English`

---

## 1. Hinglish & Kanglish Code-Mixed Architecture & STT Evaluation (Section 2 & 3)

### Why Standard Single-Language Speech Models Struggle with Hinglish & Kanglish
1. **Script Mismatch in Native Hindi/Kannada Acoustic Models:**
   - Standard Hindi (`hi-IN`) and Kannada (`kn-IN`) speech models output Devanagari (`कल कॉलेज...`) or Kannada script (`ನಾಳೆ ಕಾಲೇಜ್...`).
   - Conversely, a US/UK English (`en-US`) acoustic model lacks Indian phonetic priors and often misrecognizes Romanized Hindi/Kannada words (`mujhe`, `barbeku`, `naale`, `toh`) as phonetically similar English words.
2. **How FlowWrite AI Solves This Offline:**
   - **On-Device Android SpeechRecognizer (`en-IN`):** `AndroidOnDeviceSttEngine` requests the `en-IN` (English – India) offline pack (`EXTRA_PREFER_OFFLINE = true`), which emits Latin script while recognizing Indian phonetics and code-mixed vocabulary.
   - **Standalone Offline Whisper.cpp / Vosk Hook (`LocalPcmSttAdapter`):** `AudioCaptureManager` records 16 kHz mono PCM and passes a Romanized Hinglish/Kanglish conditioning prompt (`"Kal college mein meeting hai, Naale college ge bega barbeku"`) so `whisper.cpp` (`ggml-base.bin` / `ggml-small.bin`) or `vosk-model-small-en-in-0.4` decodes speech into Latin script rather than Devanagari or Kannada script.
   - **Strict Latin-Script Guard (`OutputValidator.kt`):** Verifies that `Qwen2.5-0.5B-Instruct` never converts Romanized Hinglish/Kanglish into Devanagari (`U+0900..U+097F`) or Kannada (`U+0C80..U+0CFF`) script, and never translates local words into English unless requested in `Custom` mode.
   - **Adaptive Transliteration Learning (`AdaptiveLearningEngine.kt`):** Automatically learns your preferred Roman spellings when you edit outputs (e.g., `"nale"` $\rightarrow$ `"naale"`, `"barabeku"` $\rightarrow$ `"barbeku"`, `"nhi"` $\rightarrow$ `"nahi"`).

---

## 2. Automatic Local LLM Discovery & Android Sandboxing (Section 4)

- On launch, `LlamaBridge.discoverAccessibleLocalModels()` scans:
  1. `context.filesDir/models` (App-Private Internal Storage)
  2. `context.getExternalFilesDir(null)` (App External Storage)
  3. Shared `Download/` and `Documents/` directories (when readable)
- **Android Sandboxing Note:** Android's Linux UID sandbox prevents one app from directly reading another app's private `/data/data/<other.package>/` directory. If your Qwen2 `.gguf` file is in shared storage (`Download/`) or another folder, tap **Select / Import Existing GGUF File** once via Android's Storage Access Framework (SAF) to register and verify its 4-byte `GGUF` header.

---

## 3. Building, Enabling the Keyboard, and Testing in WhatsApp / Instagram / Gmail

### Step 1: Clone `llama.cpp` and Build the APK
```bash
cd android-project
git clone --depth 1 https://github.com/ggerganov/llama.cpp.git app/src/main/cpp/llama.cpp
./gradlew testDebugUnitTest
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Step 2: Enable FlowWrite AI in Android Settings
1. Open **FlowWrite AI** on your phone.
2. Tap **Grant Microphone Permission** (`RECORD_AUDIO`).
3. Tap **Enable in Settings** to toggle on *FlowWrite AI Voice Keyboard* in Android's System Keyboard Settings, then tap **Select Active Keyboard**.
4. In Step 3, select your detected `qwen2.5-0.5b-instruct-q4_k_m.gguf` model (or import it via the file picker).

### Step 3: Dictate in WhatsApp, Instagram, Chrome, or Gmail
1. Open WhatsApp, Instagram DM, Gmail, or Chrome and tap any text field.
2. Ensure **Clean** mode (default) and **Lang: Auto Mix** (or **Hinglish** / **Kanglish**) are selected on the top bar of the keyboard.
3. Tap **🎤 Tap to Speak** and say:
   - *Hinglish:* `"Kal college mein meeting hai, toh mujhe time pe aana."`
   - *Kanglish:* `"Naale college ge bega barbeku."`
   - *Code-Mixed Technical:* `"Naale morning standup alli API latency bug fix madbeku."`
4. Tap **⏹ Stop Recording**.
5. Review both the **Recognized** transcript and the **Rewritten** result in the keyboard preview bar, then tap **Insert Text** (or enable **Insert Automatically When Processing Finishes** in Settings for zero-tap insertion).
