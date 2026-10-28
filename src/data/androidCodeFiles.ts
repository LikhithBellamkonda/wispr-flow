import { AndroidSourceFile } from '../types';

import settingsGradle from '../../android-project/settings.gradle.kts?raw';
import rootBuildGradle from '../../android-project/build.gradle.kts?raw';
import appBuildGradle from '../../android-project/app/build.gradle.kts?raw';
import androidManifest from '../../android-project/app/src/main/AndroidManifest.xml?raw';
import methodXml from '../../android-project/app/src/main/res/xml/method.xml?raw';
import keyboardLayoutXml from '../../android-project/app/src/main/res/layout/view_flowwrite_keyboard.xml?raw';
import cmakeLists from '../../android-project/app/src/main/cpp/CMakeLists.txt?raw';
import flowwriteJniCpp from '../../android-project/app/src/main/cpp/flowwrite_jni.cpp?raw';
import mainActivityKt from '../../android-project/app/src/main/java/com/flowwrite/ai/app/MainActivity.kt?raw';
import keyboardServiceKt from '../../android-project/app/src/main/java/com/flowwrite/ai/keyboard/FlowWriteKeyboardService.kt?raw';
import audioCaptureKt from '../../android-project/app/src/main/java/com/flowwrite/ai/audio/AudioCaptureManager.kt?raw';
import sttEngineKt from '../../android-project/app/src/main/java/com/flowwrite/ai/stt/SpeechToTextEngine.kt?raw';
import llamaBridgeKt from '../../android-project/app/src/main/java/com/flowwrite/ai/llm/LlamaBridge.kt?raw';
import adaptiveLearningEngineKt from '../../android-project/app/src/main/java/com/flowwrite/ai/personalization/AdaptiveLearningEngine.kt?raw';
import personalizationRepoKt from '../../android-project/app/src/main/java/com/flowwrite/ai/personalization/PersonalizationRepository.kt?raw';
import databaseKt from '../../android-project/app/src/main/java/com/flowwrite/ai/database/FlowWriteDatabase.kt?raw';
import dictionaryKt from '../../android-project/app/src/main/java/com/flowwrite/ai/dictionary/DictionaryProcessor.kt?raw';
import settingsRepoKt from '../../android-project/app/src/main/java/com/flowwrite/ai/settings/SettingsRepository.kt?raw';
import outputValidatorKt from '../../android-project/app/src/main/java/com/flowwrite/ai/validation/OutputValidator.kt?raw';
import unitTestsKt from '../../android-project/app/src/test/java/com/flowwrite/ai/FlowWriteUnitTests.kt?raw';
import readmeMd from '../../android-project/README.md?raw';

export const ANDROID_SOURCE_FILES: AndroidSourceFile[] = [
  {
    id: 'adaptive-learning-engine-kt',
    path: 'android-project/app/src/main/java/com/flowwrite/ai/personalization/AdaptiveLearningEngine.kt',
    name: 'AdaptiveLearningEngine.kt',
    language: 'kotlin',
    phase: 'Adaptive Personalization',
    featureRefs: ['DiffAnalyzer', 'ConfidencePromotion', 'SimilarityTopK'],
    description: 'Extracts tone, sentence length, punctuation, formality, vocabulary, and spelling patterns by comparing (originalTranscript, generatedOutput, userCorrectedText) and ranks relevant few-shot examples by Jaccard + length similarity.',
    code: adaptiveLearningEngineKt,
  },
  {
    id: 'personalization-repo-kt',
    path: 'android-project/app/src/main/java/com/flowwrite/ai/personalization/PersonalizationRepository.kt',
    name: 'PersonalizationRepository.kt',
    language: 'kotlin',
    phase: 'Adaptive Personalization',
    featureRefs: ['AutoLearn', 'PromptBuilder', 'WeakNegativeDecay'],
    description: 'Processes user corrections, increments pattern occurrenceCount & confidenceScore in Room SQLite, decays unconfirmed candidates on revert/reject, and dynamically constructs Qwen2.5 ChatML prompts.',
    code: personalizationRepoKt,
  },
  {
    id: 'keyboard-service',
    path: 'android-project/app/src/main/java/com/flowwrite/ai/keyboard/FlowWriteKeyboardService.kt',
    name: 'FlowWriteKeyboardService.kt',
    language: 'kotlin',
    phase: 'Phase 2 & Adaptive IME',
    featureRefs: ['IME', 'InputConnection', 'AutoLearnHook', 'Revert'],
    description: 'Native Android InputMethodService wired to recordFeedbackAndLearn() on user edit/accept/revert and similarity-ranked prompt conditioning.',
    code: keyboardServiceKt,
  },
  {
    id: 'flowwrite-jni-cpp',
    path: 'android-project/app/src/main/cpp/flowwrite_jni.cpp',
    name: 'flowwrite_jni.cpp',
    language: 'cpp',
    phase: 'Phase 4',
    featureRefs: ['JNI', 'llama.cpp', 'GGUF', 'Cancellation'],
    description: 'Native C++ JNI bridge for llama.cpp implementing 4-byte GGUF magic header verification, llama_load_model_from_file with mmap, llama_sampler_chain (temp=0.2), atomic abort_callback, and resource cleanup.',
    code: flowwriteJniCpp,
  },
  {
    id: 'llama-bridge-kt',
    path: 'android-project/app/src/main/java/com/flowwrite/ai/llm/LlamaBridge.kt',
    name: 'LlamaBridge.kt',
    language: 'kotlin',
    phase: 'Phase 4',
    featureRefs: ['LLM', 'SAF Import', 'Timeout', 'MemoryTrim'],
    description: 'Kotlin manager for importing Qwen2.5-0.5B-Instruct-Q4_K_M.gguf into app-private storage, verifying GGUF header, reusing loaded native model on Dispatchers.IO, enforcing 3000ms timeout, and handling onTrimMemory.',
    code: llamaBridgeKt,
  },
  {
    id: 'main-activity-kt',
    path: 'android-project/app/src/main/java/com/flowwrite/ai/app/MainActivity.kt',
    name: 'MainActivity.kt',
    language: 'kotlin',
    phase: 'Phase 1 & Phase 6',
    featureRefs: ['Onboarding', 'Permissions', 'SAF', 'Settings'],
    description: 'Jetpack Compose MainActivity providing runtime RECORD_AUDIO permission request, Android IME Settings intent, SAF GGUF model importer, writing rules manager, personal dictionary editor, and privacy controls.',
    code: mainActivityKt,
  },
  {
    id: 'output-validator-kt',
    path: 'android-project/app/src/main/java/com/flowwrite/ai/validation/OutputValidator.kt',
    name: 'OutputValidator.kt',
    language: 'kotlin',
    phase: 'Phase 5',
    featureRefs: ['Validation', 'Fallback', 'RepetitionGuard'],
    description: 'Validates local LLM output for empty responses, conversational prefixes, length anomalies, and 3-word repetition loops, falling back to the raw transcript on failure.',
    code: outputValidatorKt,
  },
  {
    id: 'database-kt',
    path: 'android-project/app/src/main/java/com/flowwrite/ai/database/FlowWriteDatabase.kt',
    name: 'FlowWriteDatabase.kt',
    language: 'kotlin',
    phase: 'Phase 6',
    featureRefs: ['Room', 'SQLite', 'Migration_1_2'],
    description: 'Room entities, DAOs, and explicit SQLite Migration (1 -> 2) for WritingPreferences, FeedbackExamples, and PersonalDictionary.',
    code: databaseKt,
  },
  {
    id: 'dictionary-kt',
    path: 'android-project/app/src/main/java/com/flowwrite/ai/dictionary/DictionaryProcessor.kt',
    name: 'DictionaryProcessor.kt',
    language: 'kotlin',
    phase: 'Phase 6',
    featureRefs: ['Dictionary', 'BoundaryRegex'],
    description: 'Conservative personal dictionary processor applying whole-word case-insensitive replacements ordered longest-first.',
    code: dictionaryKt,
  },
  {
    id: 'stt-engine-kt',
    path: 'android-project/app/src/main/java/com/flowwrite/ai/stt/SpeechToTextEngine.kt',
    name: 'SpeechToTextEngine.kt',
    language: 'kotlin',
    phase: 'Phase 3',
    featureRefs: ['STT Interface', 'On-Device', 'PCM Hook'],
    description: 'Modular SpeechToTextEngine interface with AndroidOnDeviceSttEngine (API 31+ createOnDeviceSpeechRecognizer) and LocalPcmSttAdapter for standalone offline STT (Sherpa-ONNX / Whisper).',
    code: sttEngineKt,
  },
  {
    id: 'audio-capture-kt',
    path: 'android-project/app/src/main/java/com/flowwrite/ai/audio/AudioCaptureManager.kt',
    name: 'AudioCaptureManager.kt',
    language: 'kotlin',
    phase: 'Phase 3',
    featureRefs: ['AudioRecord', '16kHz PCM'],
    description: 'Captures 16 kHz mono 16-bit PCM microphone audio frames with runtime RECORD_AUDIO permission verification and RMS amplitude calculation.',
    code: audioCaptureKt,
  },
  {
    id: 'settings-repo-kt',
    path: 'android-project/app/src/main/java/com/flowwrite/ai/settings/SettingsRepository.kt',
    name: 'SettingsRepository.kt',
    language: 'kotlin',
    phase: 'Phase 6 & Phase 7',
    featureRefs: ['DataStore', 'n_ctx=1024', 'temp=0.2', '4 Threads'],
    description: 'Jetpack DataStore repository persisting context window (1024), temperature (0.2), CPU threads (4), timeout (3000ms), model file path, and feedback storage toggle.',
    code: settingsRepoKt,
  },
  {
    id: 'keyboard-layout-xml',
    path: 'android-project/app/src/main/res/layout/view_flowwrite_keyboard.xml',
    name: 'view_flowwrite_keyboard.xml',
    language: 'xml',
    phase: 'Phase 2',
    featureRefs: ['IME Layout', 'Review Panel'],
    description: 'Android View layout for the custom keyboard with Clean/Polish buttons, status indicator, progress bar, review/edit/revert panel, and microphone capture bar.',
    code: keyboardLayoutXml,
  },
  {
    id: 'android-manifest-xml',
    path: 'android-project/app/src/main/AndroidManifest.xml',
    name: 'AndroidManifest.xml',
    language: 'xml',
    phase: 'Phase 1 & Phase 2',
    featureRefs: ['Manifest', 'BIND_INPUT_METHOD', 'Zero Internet'],
    description: 'Declares RECORD_AUDIO permission, MainActivity, and FlowWriteKeyboardService with BIND_INPUT_METHOD while omitting android.permission.INTERNET.',
    code: androidManifest,
  },
  {
    id: 'method-xml',
    path: 'android-project/app/src/main/res/xml/method.xml',
    name: 'method.xml',
    language: 'xml',
    phase: 'Phase 2',
    featureRefs: ['IME Subtype', 'SettingsActivity'],
    description: 'InputMethod metadata linking FlowWriteKeyboardService to MainActivity and declaring voice subtype.',
    code: methodXml,
  },
  {
    id: 'cmake-lists',
    path: 'android-project/app/src/main/cpp/CMakeLists.txt',
    name: 'CMakeLists.txt',
    language: 'cmake',
    phase: 'Phase 1 & Phase 4',
    featureRefs: ['CMake', 'arm64-v8a', 'llama.cpp'],
    description: 'Native CMake build script compiling llama.cpp and flowwrite_jni.cpp with ARMv8.2-A dotprod and FP16 flags for Vivo Y200e.',
    code: cmakeLists,
  },
  {
    id: 'app-build-gradle',
    path: 'android-project/app/build.gradle.kts',
    name: 'app/build.gradle.kts',
    language: 'kotlin',
    phase: 'Phase 1',
    featureRefs: ['Gradle', 'Room 2.6.1', 'DataStore', 'NDK'],
    description: 'Module-level Gradle configuration for FlowWrite AI with Compose, Room KSP, DataStore, and CMake NDK arguments.',
    code: appBuildGradle,
  },
  {
    id: 'root-build-gradle',
    path: 'android-project/build.gradle.kts',
    name: 'build.gradle.kts',
    language: 'kotlin',
    phase: 'Phase 1',
    featureRefs: ['AGP 8.5.2', 'Kotlin 1.9.24'],
    description: 'Root Gradle build configuration.',
    code: `${settingsGradle}\n\n// --- build.gradle.kts ---\n${rootBuildGradle}`,
  },
  {
    id: 'unit-tests-kt',
    path: 'android-project/app/src/test/java/com/flowwrite/ai/FlowWriteUnitTests.kt',
    name: 'FlowWriteUnitTests.kt',
    language: 'kotlin',
    phase: 'Phase 7',
    featureRefs: ['JUnit4', 'PromptTests', 'ValidatorTests'],
    description: 'Unit test suite verifying ChatML prompt construction, conservative dictionary boundary replacements, and OutputValidator anomaly/repetition fallback.',
    code: unitTestsKt,
  },
  {
    id: 'readme-md',
    path: 'android-project/README.md',
    name: 'README.md',
    language: 'cmake',
    phase: 'Phase 1 – Phase 7',
    featureRefs: ['Setup', 'llama.cpp', 'GGUF Import', 'Testing'],
    description: 'Complete build, llama.cpp integration, Qwen2.5-0.5B-Instruct-Q4_K_M import, offline STT disclosure, and Vivo Y200e testing guide.',
    code: readmeMd,
  },
];
