/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import React, { useState, useEffect } from 'react';
import { Download, Mic, Zap, CheckCircle2, Smartphone, ShieldCheck, Code2, Sliders } from 'lucide-react';
import {
  WritingPreference,
  FeedbackExample,
  PersonalDictionaryEntry,
  InferenceConfig,
  RefinementMode,
  LanguageStyle,
} from './types';
import {
  INITIAL_PREFERENCES,
  INITIAL_DICTIONARY,
  INITIAL_FEEDBACK,
} from './data/initialData';
import { ANDROID_SOURCE_FILES } from './data/androidCodeFiles';
import { PROMOTION_CONFIDENCE_THRESHOLD } from './utils/refinementEngine';
import { PersonalizationStudioTab } from './components/PersonalizationStudioTab';
import { AndroidCodebaseTab } from './components/AndroidCodebaseTab';

type ActiveWorkspaceTab = 'BUTTON_SERVICE' | 'PERSONALIZATION' | 'CODEBASE';

export default function App() {
  const [activeTab, setActiveTab] = useState<ActiveWorkspaceTab>('BUTTON_SERVICE');
  const [selectedCodePath, setSelectedCodePath] = useState<string | undefined>(undefined);
  const [selectedMode, setSelectedMode] = useState<RefinementMode>('CLEAN');

  const [preferences, setPreferences] = useState<WritingPreference[]>(() => {
    const saved = localStorage.getItem('vk_preferences_v2');
    return saved ? JSON.parse(saved) : INITIAL_PREFERENCES;
  });

  const [dictionary, setDictionary] = useState<PersonalDictionaryEntry[]>(() => {
    const saved = localStorage.getItem('vk_dictionary_v2');
    return saved ? JSON.parse(saved) : INITIAL_DICTIONARY;
  });

  const [feedbackExamples, setFeedbackExamples] = useState<FeedbackExample[]>(() => {
    const saved = localStorage.getItem('vk_feedback_v2');
    return saved ? JSON.parse(saved) : INITIAL_FEEDBACK;
  });

  const [inferenceConfig, setInferenceConfig] = useState<InferenceConfig>({
    modelFileName: 'qwen2.5-0.5b-instruct-q4_k_m.gguf',
    modelSizeMb: 398,
    quantization: 'Q4_K_M',
    threads: 4,
    contextSize: 1024,
    timeoutMs: 3000,
    temperature: 0.2,
    offlineSttEnforced: true,
    personalizationEnabled: true,
    automaticLearningEnabled: true,
    languageStyle: 'AUTO_CODE_MIXED',
    autoInsertAfterProcessing: true,
    customRewriteInstruction: '',
  });

  useEffect(() => {
    localStorage.setItem('vk_preferences_v2', JSON.stringify(preferences));
  }, [preferences]);

  useEffect(() => {
    localStorage.setItem('vk_dictionary_v2', JSON.stringify(dictionary));
  }, [dictionary]);

  useEffect(() => {
    localStorage.setItem('vk_feedback_v2', JSON.stringify(feedbackExamples));
  }, [feedbackExamples]);

  const handleAddPreference = (preferenceText: string, context: string) => {
    const now = new Date().toISOString().slice(0, 16).replace('T', ' ');
    const newPref: WritingPreference = {
      id: `pref-${Date.now()}`,
      patternKey: `manual_${Date.now()}`,
      preferenceText,
      context,
      sourceType: 'MANUAL',
      occurrenceCount: 1,
      confidenceScore: 1.0,
      enabled: true,
      createdAt: now,
      updatedAt: now,
    };
    setPreferences((prev) => [newPref, ...prev]);
  };

  const handleTogglePreference = (id: string) => {
    const now = new Date().toISOString().slice(0, 16).replace('T', ' ');
    setPreferences((prev) =>
      prev.map((p) => (p.id === id ? { ...p, enabled: !p.enabled, updatedAt: now } : p))
    );
  };

  const handleDeletePreference = (id: string) => {
    setPreferences((prev) => prev.filter((p) => p.id !== id));
  };

  const handleAddDictionaryEntry = (entry: Omit<PersonalDictionaryEntry, 'id'>) => {
    setDictionary((prev) => [{ ...entry, id: `dict-${Date.now()}` }, ...prev]);
  };

  const handleDeleteDictionaryEntry = (id: string) => {
    setDictionary((prev) => prev.filter((d) => d.id !== id));
  };

  const handleDeleteFeedback = (id: string) => {
    setFeedbackExamples((prev) => prev.filter((f) => f.id !== id));
  };

  const handleResetAllLearnedData = () => {
    setPreferences([]);
    setFeedbackExamples([]);
  };

  const handleExportFullBundle = () => {
    const bundleContent = ANDROID_SOURCE_FILES.map(
      (f) =>
        `================================================================================\nFILE: ${f.path}\nPHASE: ${f.phase} | FEATURES: ${f.featureRefs.join(', ')}\nDESCRIPTION: ${f.description}\n================================================================================\n\n${f.code}\n\n`
    ).join('\n');

    const blob = new Blob([bundleContent], { type: 'text/plain;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'FlowWriteAI_Android_Source_Bundle.txt';
    a.click();
    URL.revokeObjectURL(url);
  };

  return (
    <div className="min-h-screen bg-slate-950 text-slate-100 flex flex-col">
      <header className="flex items-center justify-between gap-8 px-6 py-4 border-b border-slate-800 bg-slate-900/90 sticky top-0 z-30 backdrop-blur-md">
        <a
          href="#service"
          onClick={(e) => {
            e.preventDefault();
            setActiveTab('BUTTON_SERVICE');
          }}
          className="text-lg font-bold tracking-tight text-slate-100 whitespace-nowrap shrink-0"
        >
          FlowWrite AI
        </a>

        <nav className="flex items-center gap-6 text-sm font-medium text-slate-400 overflow-x-auto">
          <button
            onClick={() => setActiveTab('BUTTON_SERVICE')}
            className={`transition-colors whitespace-nowrap shrink-0 pb-0.5 cursor-pointer ${
              activeTab === 'BUTTON_SERVICE'
                ? 'text-white underline underline-offset-8 decoration-indigo-500 decoration-2'
                : 'hover:text-slate-200'
            }`}
          >
            Voice Button Service
          </button>
          <button
            onClick={() => setActiveTab('PERSONALIZATION')}
            className={`transition-colors whitespace-nowrap shrink-0 pb-0.5 cursor-pointer ${
              activeTab === 'PERSONALIZATION'
                ? 'text-white underline underline-offset-8 decoration-indigo-500 decoration-2'
                : 'hover:text-slate-200'
            }`}
          >
            Learned Style DB
          </button>
          <button
            onClick={() => setActiveTab('CODEBASE')}
            className={`transition-colors whitespace-nowrap shrink-0 pb-0.5 cursor-pointer ${
              activeTab === 'CODEBASE'
                ? 'text-white underline underline-offset-8 decoration-indigo-500 decoration-2'
                : 'hover:text-slate-200'
            }`}
          >
            Android Kotlin &amp; JNI Source
          </button>
        </nav>

        <div className="flex items-center shrink-0">
          <button
            onClick={handleExportFullBundle}
            className="flex items-center gap-1.5 px-4 py-2 text-xs font-medium text-white bg-indigo-600 rounded-lg hover:bg-indigo-500 transition-colors whitespace-nowrap shrink-0 cursor-pointer"
          >
            <Download className="w-3.5 h-3.5" />
            <span>Export Android Project</span>
          </button>
        </div>
      </header>

      <main className="flex-1 max-w-4xl w-full mx-auto px-6 py-8">
        {activeTab === 'BUTTON_SERVICE' && (
          <div className="flex flex-col gap-6">
            {/* Hero Status Card: Strictly a Background Voice Button for Messaging Apps */}
            <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 flex flex-col gap-4">
              <div className="flex flex-wrap items-center justify-between gap-4">
                <div className="flex items-center gap-3">
                  <div className="w-11 h-11 rounded-2xl bg-indigo-600 flex items-center justify-center text-white shadow-lg">
                    <Mic className="w-5 h-5" />
                  </div>
                  <div>
                    <h1 className="text-lg font-bold text-white">
                      System-Wide Voice Button (`InputMethodService`)
                    </h1>
                    <p className="text-xs text-emerald-400 font-medium">
                      Active in Background · No Typing in FlowWrite · Zero Copy-Paste · &lt;5s Auto-Correction
                    </p>
                  </div>
                </div>

                <span className="px-3 py-1.5 rounded-full bg-emerald-950/80 border border-emerald-500/40 text-xs font-mono font-semibold text-emerald-300">
                  Direct InputConnection.commitText()
                </span>
              </div>

              <p className="text-sm text-slate-300 leading-relaxed">
                FlowWrite AI does <strong>not</strong> ask you to speak, type, or copy-paste inside this app.
                Instead, when you tap a message box inside <strong>WhatsApp, Instagram, Gmail, or Messages</strong> on your Android phone,
                the compact <strong>FlowWrite Voice Button</strong> appears. You speak directly in your messaging app,
                FlowWrite sends your speech to the local <strong>Qwen2.5-0.5B-Instruct-Q4_K_M</strong> LLM on-device,
                and automatically writes the corrected text back into your messaging app in <strong>under 5 seconds</strong>.
              </p>

              {/* Visual Preview of ONLY the Native Android Voice Button Bar (view_flowwrite_keyboard.xml) */}
              <div className="bg-slate-950 border border-slate-800 rounded-xl p-4 flex flex-col gap-3">
                <div className="flex items-center justify-between text-xs text-slate-400">
                  <span className="font-mono text-indigo-300">
                    Native Android Overlay (`view_flowwrite_keyboard.xml`)
                  </span>
                  <span className="font-mono tabular-nums text-emerald-400">
                    Target Latency: &lt;5.0s (4500ms hard cap)
                  </span>
                </div>

                <div className="flex flex-wrap items-center justify-between gap-3 bg-slate-900 border border-slate-800 rounded-xl p-3">
                  <div className="flex items-center gap-2">
                    <span className="px-2.5 py-1 rounded-md bg-slate-800 text-indigo-300 text-xs font-semibold">
                      Mode: {selectedMode}
                    </span>
                    <span className="px-2.5 py-1 rounded-md bg-slate-800 text-emerald-300 text-xs font-semibold">
                      {inferenceConfig.languageStyle}
                    </span>
                  </div>

                  <div className="flex items-center gap-2 px-5 py-2.5 rounded-xl bg-indigo-600 text-white text-xs font-bold shadow">
                    <Mic className="w-4 h-4" />
                    <span>Hold / Tap to Speak in Messaging App</span>
                  </div>

                  <span className="text-[11px] font-mono text-slate-400">
                    Auto-Returns to Chat Box
                  </span>
                </div>
              </div>
            </div>

            {/* Default Voice Button Settings (Synced to Android DataStore) */}
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5 flex flex-col gap-3">
                <div className="flex items-center gap-2 text-sm font-bold text-white">
                  <Sliders className="w-4 h-4 text-indigo-400" />
                  <span>Default Auto-Correction Mode</span>
                </div>
                <p className="text-xs text-slate-400">
                  Applied automatically when you speak into the button inside WhatsApp or Instagram.
                </p>
                <div className="grid grid-cols-3 gap-2 pt-1">
                  {(['CLEAN', 'POLISH', 'CASUAL'] as RefinementMode[]).map((mode) => (
                    <button
                      key={mode}
                      onClick={() => setSelectedMode(mode)}
                      className={`py-2 px-3 rounded-xl text-xs font-bold transition-colors cursor-pointer ${
                        selectedMode === mode
                          ? 'bg-indigo-600 text-white'
                          : 'bg-slate-950 border border-slate-800 text-slate-300 hover:bg-slate-800'
                      }`}
                    >
                      {mode}
                    </button>
                  ))}
                </div>
              </div>

              <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5 flex flex-col gap-3">
                <div className="flex items-center gap-2 text-sm font-bold text-white">
                  <Zap className="w-4 h-4 text-emerald-400" />
                  <span>Spoken Language Style (Latin Script)</span>
                </div>
                <p className="text-xs text-slate-400">
                  Preserves Hinglish &amp; Kanglish words in English letters without translating them.
                </p>
                <div className="grid grid-cols-2 gap-2 pt-1">
                  {(
                    [
                      { id: 'AUTO_CODE_MIXED', label: 'Auto Code-Mixed' },
                      { id: 'HINGLISH', label: 'Hinglish' },
                      { id: 'KANGLISH', label: 'Kanglish' },
                      { id: 'ENGLISH', label: 'English' },
                    ] as { id: LanguageStyle; label: string }[]
                  ).map((st) => (
                    <button
                      key={st.id}
                      onClick={() =>
                        setInferenceConfig({ ...inferenceConfig, languageStyle: st.id })
                      }
                      className={`py-2 px-3 rounded-xl text-xs font-bold transition-colors cursor-pointer ${
                        inferenceConfig.languageStyle === st.id
                          ? 'bg-emerald-600 text-white'
                          : 'bg-slate-950 border border-slate-800 text-slate-300 hover:bg-slate-800'
                      }`}
                    >
                      {st.label}
                    </button>
                  ))}
                </div>
              </div>
            </div>

            {/* Direct Native Android Pipeline Summary */}
            <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5 flex flex-col gap-4">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2 text-sm font-bold text-white">
                  <Smartphone className="w-4 h-4 text-indigo-400" />
                  <span>How the Native Android Button Works on Vivo Y200e</span>
                </div>
                <button
                  onClick={() => {
                    setSelectedCodePath(
                      'android-project/app/src/main/java/com/flowwrite/ai/keyboard/FlowWriteKeyboardService.kt'
                    );
                    setActiveTab('CODEBASE');
                  }}
                  className="text-xs font-semibold text-indigo-400 hover:text-indigo-300 flex items-center gap-1 cursor-pointer"
                >
                  <Code2 className="w-3.5 h-3.5" />
                  <span>Inspect FlowWriteKeyboardService.kt</span>
                </button>
              </div>

              <div className="grid grid-cols-1 md:grid-cols-3 gap-3 text-xs">
                <div className="p-3.5 rounded-xl bg-slate-950 border border-slate-800 space-y-1">
                  <div className="font-bold text-white flex items-center gap-1.5">
                    <CheckCircle2 className="w-3.5 h-3.5 text-emerald-400" />
                    <span>1. Tap Mic in Messaging App</span>
                  </div>
                  <p className="text-slate-400 leading-relaxed">
                    Open WhatsApp or Instagram, focus the chat box, and tap the FlowWrite Mic button.
                  </p>
                </div>

                <div className="p-3.5 rounded-xl bg-slate-950 border border-slate-800 space-y-1">
                  <div className="font-bold text-white flex items-center gap-1.5">
                    <CheckCircle2 className="w-3.5 h-3.5 text-emerald-400" />
                    <span>2. &lt;5s Local Qwen2.5 LLM</span>
                  </div>
                  <p className="text-slate-400 leading-relaxed">
                    Speech is transcribed on-device and sent via JNI to Qwen2.5-0.5B (`MAX_END_TO_END_LATENCY_MS = 4500L`).
                  </p>
                </div>

                <div className="p-3.5 rounded-xl bg-slate-950 border border-slate-800 space-y-1">
                  <div className="font-bold text-white flex items-center gap-1.5">
                    <CheckCircle2 className="w-3.5 h-3.5 text-emerald-400" />
                    <span>3. Instant Return to Chat</span>
                  </div>
                  <p className="text-slate-400 leading-relaxed">
                    `currentInputConnection.commitText()` writes the polished reply directly into the messaging app.
                  </p>
                </div>
              </div>
            </div>
          </div>
        )}

        {activeTab === 'PERSONALIZATION' && (
          <PersonalizationStudioTab
            preferences={preferences}
            dictionary={dictionary}
            feedbackExamples={feedbackExamples}
            onTogglePreference={handleTogglePreference}
            onDeletePreference={handleDeletePreference}
            onAddPreference={handleAddPreference}
            onAddDictionaryEntry={handleAddDictionaryEntry}
            onDeleteDictionaryEntry={handleDeleteDictionaryEntry}
            onDeleteFeedback={handleDeleteFeedback}
            onResetAllLearnedData={handleResetAllLearnedData}
          />
        )}

        {activeTab === 'CODEBASE' && (
          <AndroidCodebaseTab initialFilePath={selectedCodePath} />
        )}
      </main>
    </div>
  );
}
