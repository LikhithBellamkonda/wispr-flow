export type RefinementMode = 'CLEAN' | 'POLISH' | 'CASUAL' | 'RAW' | 'CUSTOM';

export type LanguageStyle = 'AUTO_CODE_MIXED' | 'HINGLISH' | 'KANGLISH' | 'ENGLISH';

export type TargetAppType =
  | 'WHATSAPP'
  | 'INSTAGRAM'
  | 'GMAIL'
  | 'CHROME_SEARCH'
  | 'CHROME_PASSWORD'
  | 'NOTES';

export interface WritingPreference {
  id: string;
  patternKey?: string;
  preferenceText: string;
  context: string; // TRANSLITERATION, TONE, PUNCTUATION, LENGTH, VOCABULARY, FORMALITY, SPELLING, MANUAL
  sourceType?: 'MANUAL' | 'AUTO_LEARNED';
  occurrenceCount?: number;
  confidenceScore?: number;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface FeedbackExample {
  id: string;
  originalTranscript: string;
  generatedOutput: string;
  approvedCorrection: string;
  feedbackType: 'ACCEPTED' | 'REJECTED' | 'EDITED' | 'REVERTED';
  writingMode: RefinementMode;
  signalWeight?: number;
  extractedTags?: string[];
  timestamp: string;
}

export interface PersonalDictionaryEntry {
  id: string;
  spokenForm: string;
  preferredSpelling: string;
  category: 'Name' | 'Acronym' | 'Technical' | 'Transliteration' | 'Custom';
}

export interface InferenceConfig {
  modelFileName: string;
  modelSizeMb: number;
  quantization: string;
  threads: number;
  contextSize: number;
  timeoutMs: number;
  temperature: number;
  offlineSttEnforced: boolean;
  personalizationEnabled: boolean;
  automaticLearningEnabled: boolean;
  languageStyle: LanguageStyle;
  autoInsertAfterProcessing: boolean;
  customRewriteInstruction: string;
}

export interface PipelineTrace {
  rawTranscript: string;
  detectedLanguageStyle: LanguageStyle;
  dictionaryAppliedTranscript: string;
  matchedDictionaryEntries: PersonalDictionaryEntry[];
  activePreferencesUsed: WritingPreference[];
  fewShotExamplesUsed: FeedbackExample[];
  constructedChatMlPrompt: string;
  finalOutput: string;
  unpersonalizedBaselineOutput: string;
  unpersonalizedBaselinePrompt: string;
  fallbackTriggered: boolean;
  fallbackReason?: string;
  sttLatencyMs: number;
  preprocessLatencyMs: number;
  retrievalLatencyMs: number;
  llmInferenceLatencyMs: number;
  validationLatencyMs: number;
  totalLatencyMs: number;
  tokensPerSecond: number;
}

export interface PhaseTask {
  id: string;
  phaseNumber: number;
  phaseTitle: string;
  taskTitle: string;
  featureRef: string;
  targetFile: string;
  completed: boolean;
  technicalNotes: string;
}

export interface AcceptanceCriterion {
  id: number;
  title: string;
  category: string;
  verified: boolean;
  verificationMethod: string;
}

export interface AndroidSourceFile {
  id: string;
  path: string;
  name: string;
  language: 'kotlin' | 'cpp' | 'xml' | 'cmake';
  phase: string;
  featureRefs: string[];
  description: string;
  code: string;
}
