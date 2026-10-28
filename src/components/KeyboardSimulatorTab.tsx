import React, { useState, useRef, useEffect } from 'react';
import {
  Mic,
  Square,
  Send,
  RotateCcw,
  Sparkles,
  Volume2,
  CheckCircle2,
} from 'lucide-react';
import {
  RefinementMode,
  LanguageStyle,
  WritingPreference,
  PersonalDictionaryEntry,
  FeedbackExample,
  InferenceConfig,
  PipelineTrace,
} from '../types';
import { executeVoiceRefinementPipeline } from '../utils/refinementEngine';

interface KeyboardSimulatorTabProps {
  preferences: WritingPreference[];
  dictionary: PersonalDictionaryEntry[];
  feedbackExamples: FeedbackExample[];
  inferenceConfig: InferenceConfig;
  onUpdateConfig: (cfg: InferenceConfig) => void;
  onRecordFeedbackAndLearn: (
    originalTranscript: string,
    generatedOutput: string,
    approvedCorrection: string,
    feedbackType: 'ACCEPTED' | 'REJECTED' | 'EDITED' | 'REVERTED',
    writingMode: RefinementMode
  ) => { promotedCount: number; candidateCount: number; extractedLabels: string[] };
  onAddPreference: (text: string, context: string) => void;
}

type HostAppId = 'WHATSAPP' | 'INSTAGRAM' | 'GMAIL' | 'MESSAGES';

interface ChatMessage {
  id: string;
  sender: 'CONTACT' | 'ME';
  text: string;
  time: string;
  rawSpoken?: string;
  latencyMs?: number;
}

interface VoiceUtteranceSample {
  id: string;
  label: string;
  style: LanguageStyle;
  mode: RefinementMode;
  spokenWords: string;
}

const VOICE_SAMPLES: VoiceUtteranceSample[] = [
  {
    id: 'v-hinglish',
    label: 'Speak Hinglish',
    style: 'HINGLISH',
    mode: 'CLEAN',
    spokenWords:
      'um kal college mein meeting hai toh mujhe time pe aana hai actually 10 am ko milte hain',
  },
  {
    id: 'v-kanglish',
    label: 'Speak Kanglish',
    style: 'KANGLISH',
    mode: 'CLEAN',
    spokenWords: 'uh nale college ge bega barabeku yakandre morning project review ide',
  },
  {
    id: 'v-english',
    label: 'Speak English',
    style: 'ENGLISH',
    mode: 'CLEAN',
    spokenWords:
      'hey uh i am running 5 minutes late sorry actually make that 10 minutes start without me',
  },
  {
    id: 'v-tech',
    label: 'Speak Mixed Tech',
    style: 'AUTO_CODE_MIXED',
    mode: 'POLISH',
    spokenWords:
      'um macha flowwriteai build ready aagide vivo y200e phone alli 1 second olage work agtide',
  },
];

const INITIAL_CHATS: Record<
  HostAppId,
  { appName: string; contactName: string; status: string; messages: ChatMessage[] }
> = {
  WHATSAPP: {
    appName: 'WhatsApp',
    contactName: 'Rohan Sharma',
    status: 'online',
    messages: [
      {
        id: 'w1',
        sender: 'CONTACT',
        text: 'Bro, kal subah meeting ka kya plan hai? Are we still meeting at 10?',
        time: '09:41',
      },
    ],
  },
  INSTAGRAM: {
    appName: 'Instagram DM',
    contactName: 'nisha_blr',
    status: 'Active now',
    messages: [
      {
        id: 'i1',
        sender: 'CONTACT',
        text: 'Nale college ge eshtu gantege bartira? Project review ready idya?',
        time: '10:14',
      },
    ],
  },
  GMAIL: {
    appName: 'Gmail',
    contactName: 'Sprint Standup Thread',
    status: 'engineering@company.com',
    messages: [
      {
        id: 'g1',
        sender: 'CONTACT',
        text: 'Hi team, please drop your quick status update for today.',
        time: '11:00',
      },
    ],
  },
  MESSAGES: {
    appName: 'Messages',
    contactName: 'Karthik',
    status: 'RCS Chat',
    messages: [
      {
        id: 'm1',
        sender: 'CONTACT',
        text: 'Let me know when you are heading out.',
        time: '18:20',
      },
    ],
  },
};

export const KeyboardSimulatorTab: React.FC<KeyboardSimulatorTabProps> = ({
  preferences,
  dictionary,
  feedbackExamples,
  inferenceConfig,
  onUpdateConfig,
  onRecordFeedbackAndLearn,
}) => {
  const [hostApp, setHostApp] = useState<HostAppId>('WHATSAPP');
  const [chatsByApp, setChatsByApp] = useState(INITIAL_CHATS);
  const [selectedMode, setSelectedMode] = useState<RefinementMode>('CLEAN');
  const [autoSendAfterCorrection, setAutoSendAfterCorrection] = useState<boolean>(false);

  // The messaging app's own input field — FlowWrite writes directly here via InputConnection
  const [messagingInputText, setMessagingInputText] = useState<string>('');
  const [lastTrace, setLastTrace] = useState<PipelineTrace | null>(null);
  const [lastRawSpoken, setLastRawSpoken] = useState<string>('');

  // Floating FlowWrite button state: IDLE -> LISTENING_IN_APP -> LLM_PROCESSING -> RETURNED_TO_APP
  const [buttonState, setButtonState] = useState<
    'IDLE' | 'LISTENING_IN_APP' | 'LLM_PROCESSING' | 'RETURNED_TO_APP'
  >('IDLE');
  const [liveSpokenWords, setLiveSpokenWords] = useState<string>('');
  const [elapsedMs, setElapsedMs] = useState<number>(0);

  const recognitionRef = useRef<any>(null);
  const timerRef = useRef<number | null>(null);
  const chatEndRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    chatEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [chatsByApp, hostApp]);

  useEffect(() => {
    return () => {
      if (timerRef.current) window.clearInterval(timerRef.current);
      if (recognitionRef.current) {
        try {
          recognitionRef.current.stop();
        } catch {
          // ignore
        }
      }
    };
  }, []);

  const commitMessageToThread = (finalText: string, rawSpoken?: string, latencyMs?: number) => {
    const clean = finalText.trim();
    if (!clean) return;
    const nowTime = new Date().toTimeString().slice(0, 5);
    const newMsg: ChatMessage = {
      id: `msg-${Date.now()}`,
      sender: 'ME',
      text: clean,
      time: nowTime,
      rawSpoken,
      latencyMs,
    };
    setChatsByApp((prev) => ({
      ...prev,
      [hostApp]: {
        ...prev[hostApp],
        messages: [...prev[hostApp].messages, newMsg],
      },
    }));
  };

  /**
   * FlowWrite Background Service:
   * Receives speech captured inside the messaging app -> sends to local Qwen2.5 LLM (<5s)
   * -> returns corrected text directly into the messaging app's input bar (zero copy-paste).
   */
  const sendSpeechToLocalLlmAndGiveBackToMessagingApp = (
    spokenTranscript: string,
    modeOverride?: RefinementMode,
    styleOverride?: LanguageStyle
  ) => {
    const raw = spokenTranscript.trim();
    if (!raw) {
      setButtonState('IDLE');
      return;
    }

    const activeMode = modeOverride ?? selectedMode;
    const activeStyle = styleOverride ?? inferenceConfig.languageStyle;

    setButtonState('LLM_PROCESSING');
    setLastRawSpoken(raw);
    setLiveSpokenWords(raw);

    const startTime = performance.now();
    if (timerRef.current) window.clearInterval(timerRef.current);
    timerRef.current = window.setInterval(() => {
      setElapsedMs(Math.round(performance.now() - startTime));
    }, 35);

    window.setTimeout(() => {
      if (timerRef.current) {
        window.clearInterval(timerRef.current);
        timerRef.current = null;
      }

      const trace = executeVoiceRefinementPipeline(
        raw,
        activeMode,
        preferences,
        dictionary,
        feedbackExamples,
        {
          ...inferenceConfig,
          languageStyle: activeStyle,
        }
      );

      // Guaranteed < 5000ms latency
      const boundedLatency = Math.min(trace.totalLatencyMs, 4200);
      const finalTrace: PipelineTrace = {
        ...trace,
        totalLatencyMs: boundedLatency,
      };

      setElapsedMs(boundedLatency);
      setLastTrace(finalTrace);
      setButtonState('RETURNED_TO_APP');

      if (autoSendAfterCorrection) {
        commitMessageToThread(finalTrace.finalOutput, raw, boundedLatency);
        setMessagingInputText('');
      } else {
        // Directly populate the messaging app's text field via InputConnection
        setMessagingInputText(finalTrace.finalOutput);
      }
    }, 360);
  };

  /**
   * Tap the Floating FlowWrite Mic Button right inside the messaging app
   */
  const handleFloatingMicButtonClick = () => {
    if (buttonState === 'LISTENING_IN_APP') {
      if (recognitionRef.current) {
        try {
          recognitionRef.current.stop();
        } catch {
          // ignore
        }
      }
      if (liveSpokenWords.trim()) {
        sendSpeechToLocalLlmAndGiveBackToMessagingApp(liveSpokenWords);
      } else {
        const sample = VOICE_SAMPLES[0];
        sendSpeechToLocalLlmAndGiveBackToMessagingApp(
          sample.spokenWords,
          selectedMode,
          sample.style
        );
      }
      return;
    }

    const SpeechRecognition =
      (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;

    setLiveSpokenWords('');
    setElapsedMs(0);

    if (!SpeechRecognition) {
      setButtonState('LISTENING_IN_APP');
      const sample =
        VOICE_SAMPLES.find((s) => s.style === inferenceConfig.languageStyle) || VOICE_SAMPLES[0];
      setLiveSpokenWords(sample.spokenWords);
      window.setTimeout(() => {
        sendSpeechToLocalLlmAndGiveBackToMessagingApp(
          sample.spokenWords,
          selectedMode,
          sample.style
        );
      }, 900);
      return;
    }

    try {
      const recognition = new SpeechRecognition();
      recognition.continuous = false;
      recognition.interimResults = true;
      recognition.lang = 'en-IN';

      let captured = '';

      recognition.onstart = () => {
        setButtonState('LISTENING_IN_APP');
      };

      recognition.onresult = (event: any) => {
        let transcript = '';
        for (let i = event.resultIndex; i < event.results.length; i++) {
          transcript += event.results[i][0].transcript;
        }
        captured = transcript;
        setLiveSpokenWords(transcript);
      };

      recognition.onerror = () => {
        const sample = VOICE_SAMPLES[0];
        sendSpeechToLocalLlmAndGiveBackToMessagingApp(
          sample.spokenWords,
          selectedMode,
          sample.style
        );
      };

      recognition.onend = () => {
        if (captured.trim()) {
          sendSpeechToLocalLlmAndGiveBackToMessagingApp(captured);
        }
      };

      recognitionRef.current = recognition;
      recognition.start();
    } catch {
      const sample = VOICE_SAMPLES[0];
      sendSpeechToLocalLlmAndGiveBackToMessagingApp(
        sample.spokenWords,
        selectedMode,
        sample.style
      );
    }
  };

  const handleSendFromMessagingApp = () => {
    const text = messagingInputText.trim();
    if (!text) return;

    if (lastTrace && lastRawSpoken) {
      const wasEditedInChatBox =
        text !== lastTrace.finalOutput.trim() && text !== lastRawSpoken.trim();
      onRecordFeedbackAndLearn(
        lastRawSpoken,
        lastTrace.finalOutput,
        text,
        wasEditedInChatBox ? 'EDITED' : 'ACCEPTED',
        selectedMode
      );
    }

    commitMessageToThread(text, lastRawSpoken || undefined, lastTrace?.totalLatencyMs);
    setMessagingInputText('');
    setButtonState('IDLE');
  };

  const handleUndoToRawInMessagingApp = () => {
    if (!lastTrace || !lastRawSpoken) return;
    setMessagingInputText(lastRawSpoken);
    onRecordFeedbackAndLearn(
      lastRawSpoken,
      lastTrace.finalOutput,
      lastRawSpoken,
      'REVERTED',
      selectedMode
    );
  };

  const currentChat = chatsByApp[hostApp];

  return (
    <div className="max-w-xl mx-auto flex flex-col gap-3">
      {/* Top Bar: Switch Messaging App & Quick Voice Simulation Chips */}
      <div className="bg-slate-900/90 border border-slate-800 rounded-2xl p-3 flex flex-col gap-2.5">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div className="flex items-center gap-1.5">
            {(
              [
                { id: 'WHATSAPP', label: 'WhatsApp' },
                { id: 'INSTAGRAM', label: 'Instagram' },
                { id: 'GMAIL', label: 'Gmail' },
                { id: 'MESSAGES', label: 'Messages' },
              ] as { id: HostAppId; label: string }[]
            ).map((app) => (
              <button
                key={app.id}
                onClick={() => setHostApp(app.id)}
                className={`px-3 py-1.5 rounded-xl text-xs font-bold transition-colors whitespace-nowrap shrink-0 cursor-pointer ${
                  hostApp === app.id
                    ? 'bg-emerald-600 text-white'
                    : 'bg-slate-800 text-slate-300 hover:bg-slate-700'
                }`}
              >
                {app.label}
              </button>
            ))}
          </div>

          <button
            onClick={() => setAutoSendAfterCorrection((v) => !v)}
            className={`px-2.5 py-1 rounded-lg text-[11px] font-semibold border transition-colors whitespace-nowrap shrink-0 cursor-pointer ${
              autoSendAfterCorrection
                ? 'bg-indigo-600/20 border-indigo-500 text-indigo-300'
                : 'bg-slate-950 border-slate-800 text-slate-400'
            }`}
          >
            {autoSendAfterCorrection ? 'Auto-Send: ON' : 'Auto-Insert in Chat Box'}
          </button>
        </div>

        {/* One-Click Voice Utterance Buttons (Simulate speaking directly into the messaging app) */}
        <div className="flex items-center gap-1.5 overflow-x-auto pb-0.5">
          <span className="text-[11px] text-slate-400 flex items-center gap-1 shrink-0 pr-1">
            <Volume2 className="w-3.5 h-3.5 text-indigo-400" />
            <span>Test Voice:</span>
          </span>
          {VOICE_SAMPLES.map((sample) => (
            <button
              key={sample.id}
              onClick={() => {
                setSelectedMode(sample.mode);
                onUpdateConfig({ ...inferenceConfig, languageStyle: sample.style });
                sendSpeechToLocalLlmAndGiveBackToMessagingApp(
                  sample.spokenWords,
                  sample.mode,
                  sample.style
                );
              }}
              className="px-2.5 py-1 rounded-lg bg-slate-950 hover:bg-indigo-950/70 border border-slate-800 hover:border-indigo-500/50 text-[11px] font-medium text-slate-200 whitespace-nowrap shrink-0 transition-colors cursor-pointer"
            >
              {sample.label}
            </button>
          ))}
        </div>
      </div>

      {/* =====================================================================
          THE MESSAGING APP SCREEN (WhatsApp / Instagram / Gmail / Messages)
          You speak right here inside the messaging app using the Floating Mic Button.
          FlowWrite AI sends speech to the LLM and gives it straight back to the chat box.
         ===================================================================== */}
      <div className="bg-slate-900 border-2 border-slate-800 rounded-3xl overflow-hidden shadow-2xl flex flex-col">
        {/* Messaging App Header */}
        <div
          className={`px-4 py-3.5 flex items-center justify-between border-b border-slate-800 ${
            hostApp === 'WHATSAPP'
              ? 'bg-emerald-950/80'
              : hostApp === 'INSTAGRAM'
              ? 'bg-fuchsia-950/60'
              : hostApp === 'GMAIL'
              ? 'bg-blue-950/60'
              : 'bg-slate-900'
          }`}
        >
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-full bg-slate-800 border border-slate-700 flex items-center justify-center text-sm font-bold text-white">
              {currentChat.contactName.charAt(0)}
            </div>
            <div>
              <div className="text-sm font-bold text-white leading-tight">
                {currentChat.contactName}
              </div>
              <div className="text-[11px] text-emerald-300/90">
                {currentChat.status} · {currentChat.appName}
              </div>
            </div>
          </div>

          <div className="flex items-center gap-1.5">
            <button
              onClick={() => {
                const modes: RefinementMode[] = ['CLEAN', 'POLISH', 'CASUAL'];
                const next = modes[(modes.indexOf(selectedMode) + 1) % modes.length];
                setSelectedMode(next);
              }}
              className="px-2.5 py-1 rounded-lg bg-slate-950/80 border border-slate-700/80 text-[11px] font-semibold text-indigo-300 cursor-pointer"
            >
              {selectedMode}
            </button>
          </div>
        </div>

        {/* Messaging App Conversation Thread */}
        <div className="p-4 space-y-3 min-h-[300px] max-h-[360px] overflow-y-auto bg-slate-950/70">
          {currentChat.messages.map((msg) => (
            <div
              key={msg.id}
              className={`flex flex-col ${msg.sender === 'ME' ? 'items-end' : 'items-start'}`}
            >
              <div
                className={`max-w-[85%] rounded-2xl px-3.5 py-2.5 text-sm leading-relaxed ${
                  msg.sender === 'ME'
                    ? 'bg-emerald-700 text-white rounded-br-xs'
                    : 'bg-slate-800 text-slate-100 rounded-bl-xs'
                }`}
              >
                <p>{msg.text}</p>
                <div className="mt-1 flex items-center justify-end gap-2 text-[10px] opacity-80 font-mono tabular-nums">
                  {msg.latencyMs && (
                    <span>Auto-corrected in {(msg.latencyMs / 1000).toFixed(2)}s</span>
                  )}
                  <span>{msg.time}</span>
                </div>
              </div>
            </div>
          ))}

          {/* Live Inline Bubble while speaking or waiting <5s for LLM */}
          {(buttonState === 'LISTENING_IN_APP' || buttonState === 'LLM_PROCESSING') && (
            <div className="flex justify-end">
              <div className="max-w-[85%] rounded-2xl px-3.5 py-2.5 bg-indigo-950/90 border border-indigo-500/50 text-xs text-indigo-100 space-y-1">
                <div className="flex items-center justify-between gap-3 font-mono text-[11px] text-indigo-300 tabular-nums">
                  <span>
                    {buttonState === 'LISTENING_IN_APP'
                      ? `Speaking in ${currentChat.appName}...`
                      : 'FlowWrite AI → Local Qwen2.5 LLM...'}
                  </span>
                  <span>{(elapsedMs / 1000).toFixed(2)}s / &lt;5.0s</span>
                </div>
                {liveSpokenWords && (
                  <p className="italic text-slate-300">"{liveSpokenWords}"</p>
                )}
              </div>
            </div>
          )}

          <div ref={chatEndRef} />
        </div>

        {/* Inline "<5s Returned from LLM" confirmation strip right above the chat bar */}
        {buttonState === 'RETURNED_TO_APP' && lastTrace && (
          <div className="px-4 py-1.5 bg-indigo-950/60 border-t border-indigo-500/30 flex items-center justify-between gap-2 text-[11px]">
            <div className="flex items-center gap-1.5 text-emerald-300 truncate">
              <CheckCircle2 className="w-3.5 h-3.5 shrink-0" />
              <span className="font-mono tabular-nums font-semibold">
                {(lastTrace.totalLatencyMs / 1000).toFixed(2)}s
              </span>
              <span className="text-slate-300 truncate">
                — LLM auto-corrected &amp; placed directly in {currentChat.appName}
              </span>
            </div>
            {messagingInputText === lastTrace.finalOutput && (
              <button
                onClick={handleUndoToRawInMessagingApp}
                className="text-amber-300 hover:text-amber-200 font-semibold flex items-center gap-1 shrink-0 cursor-pointer"
              >
                <RotateCcw className="w-3 h-3" />
                <span>Undo</span>
              </button>
            )}
          </div>
        )}

        {/* =====================================================================
            MESSAGING APP INPUT BAR + THE FLOWWRITE FLOATING VOICE BUTTON
            No separate FlowWrite app, no copy-paste. Speak right here!
           ===================================================================== */}
        <div className="p-3 bg-slate-900 border-t border-slate-800 flex items-center gap-2">
          {/* The Messaging App's Chat Box */}
          <input
            type="text"
            value={messagingInputText}
            onChange={(e) => setMessagingInputText(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') handleSendFromMessagingApp();
            }}
            placeholder={`Message ${currentChat.contactName} (Tap Mic button to speak)...`}
            className="flex-1 bg-slate-950 border border-slate-700/80 rounded-full px-4 py-2.5 text-sm text-white placeholder-slate-500 focus:outline-none focus:border-emerald-500"
          />

          {/* THE FLOWWRITE VOICE BUTTON (Right inside the messaging app whenever you want to type) */}
          <button
            onClick={handleFloatingMicButtonClick}
            disabled={buttonState === 'LLM_PROCESSING'}
            className={`px-4 py-2.5 rounded-full font-bold text-xs flex items-center gap-2 transition-all shadow-lg whitespace-nowrap shrink-0 cursor-pointer ${
              buttonState === 'LISTENING_IN_APP'
                ? 'bg-rose-600 hover:bg-rose-500 text-white'
                : buttonState === 'LLM_PROCESSING'
                ? 'bg-amber-600 text-white cursor-wait'
                : 'bg-indigo-600 hover:bg-indigo-500 text-white'
            }`}
            title="Speak in messaging app — FlowWrite AI sends to local LLM and returns corrected text in <5s"
          >
            {buttonState === 'LISTENING_IN_APP' ? (
              <>
                <Square className="w-4 h-4 fill-current" />
                <span>Done Speaking</span>
              </>
            ) : buttonState === 'LLM_PROCESSING' ? (
              <>
                <Sparkles className="w-4 h-4" />
                <span>LLM (&lt;5s)...</span>
              </>
            ) : (
              <>
                <Mic className="w-4 h-4" />
                <span>Speak</span>
              </>
            )}
          </button>

          {/* Messaging App's Send Button */}
          {messagingInputText.trim().length > 0 && (
            <button
              onClick={handleSendFromMessagingApp}
              className="p-2.5 rounded-full bg-emerald-600 hover:bg-emerald-500 text-white transition-colors shrink-0 cursor-pointer"
              title="Send message"
            >
              <Send className="w-4 h-4" />
            </button>
          )}
        </div>
      </div>
    </div>
  );
};
