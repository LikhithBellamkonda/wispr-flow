import {
  RefinementMode,
  LanguageStyle,
  WritingPreference,
  FeedbackExample,
  PersonalDictionaryEntry,
  PipelineTrace,
  InferenceConfig,
} from '../types';

export const PROMOTION_CONFIDENCE_THRESHOLD = 0.65;
export const INITIAL_CANDIDATE_CONFIDENCE = 0.35;
export const REPEATED_PATTERN_BOOST = 0.35;

export interface ExtractedPatternSignal {
  patternKey: string;
  preferenceText: string;
  category: string;
}

const STOP_WORDS = new Set([
  'the', 'a', 'an', 'and', 'or', 'but', 'in', 'on', 'at', 'to', 'for',
  'of', 'with', 'by', 'is', 'are', 'was', 'were', 'be', 'been', 'it',
  'this', 'that', 'i', 'we', 'you', 'they', 'he', 'she',
]);

const HINGLISH_MARKERS = new Set([
  'kal', 'aaj', 'abhi', 'mein', 'hai', 'hain', 'toh', 'mujhe', 'tumhe', 'aana',
  'jaana', 'karna', 'kya', 'kyun', 'kaise', 'nahi', 'nhi', 'bahut', 'bohot',
  'accha', 'acha', 'theek', 'thik', 'bhai', 'yaar', 'matlab', 'zaroor', 'jaldi',
  'kaam', 'baat', 'sath', 'saath', 'pe', 'par', 'aur', 'lekin', 'agar', 'wala',
]);

const KANGLISH_MARKERS = new Set([
  'naale', 'nale', 'ivattu', 'iga', 'ge', 'ige', 'bega', 'barbeku', 'barabeku',
  'hogbeku', 'madbeku', 'maadbeku', 'ide', 'illa', 'hage', 'hege', 'yaake',
  'yake', 'enu', 'yenu', 'swalpa', 'thumba', 'tumba', 'chennagide', 'aytu',
  'aaytu', 'gottu', 'gottilla', 'madi', 'maadi', 'beku', 'beda', 'kodi',
  'heli', 'nange', 'nimge', 'namaskara', 'maga', 'machaa', 'oota', 'alli',
]);

const TRANSLITERATION_VARIANT_GROUPS: string[][] = [
  ['naale', 'nale'],
  ['barbeku', 'barabeku'],
  ['madbeku', 'maadbeku'],
  ['madi', 'maadi'],
  ['aytu', 'aaytu', 'aayithu'],
  ['thumba', 'tumba'],
  ['mujhe', 'muje'],
  ['nahi', 'nhi', 'nai'],
  ['accha', 'acha', 'achha'],
  ['theek', 'thik'],
  ['bahut', 'bohot'],
  ['toh', 'to'],
  ['mein', 'me'],
];

const CONTRACTION_PAIRS: [string, string][] = [
  ['do not', "don't"],
  ['cannot', "can't"],
  ['will not', "won't"],
  ['should not', "shouldn't"],
  ['would not', "wouldn't"],
  ['could not', "couldn't"],
  ['i am', "i'm"],
  ['we are', "we're"],
  ['they are', "they're"],
  ['it is', "it's"],
  ['let us', "let's"],
  ['that is', "that's"],
];

export function detectDominantLanguageStyle(transcript: string): LanguageStyle {
  const words = tokenizeWords(transcript);
  let hinglishHits = 0;
  let kanglishHits = 0;
  for (const w of words) {
    if (HINGLISH_MARKERS.has(w)) hinglishHits++;
    if (KANGLISH_MARKERS.has(w)) kanglishHits++;
  }
  if (kanglishHits > 0 && kanglishHits >= hinglishHits) return 'KANGLISH';
  if (hinglishHits > 0) return 'HINGLISH';
  return 'ENGLISH';
}

export function extractPatternsFromUserCorrection(
  originalTranscript: string,
  generatedOutput: string,
  correctedText: string
): ExtractedPatternSignal[] {
  const gen = generatedOutput.trim();
  const cor = correctedText.trim();
  if (!gen || !cor || gen === cor) return [];

  const signals: ExtractedPatternSignal[] = [];
  const genWords = tokenizeWords(gen);
  const corWords = tokenizeWords(cor);

  // 1. Hinglish / Kanglish transliteration spelling variants
  for (const group of TRANSLITERATION_VARIANT_GROUPS) {
    const genMatch = genWords.find((w) => group.includes(w));
    const corMatch = corWords.find((w) => group.includes(w));
    if (genMatch && corMatch && genMatch !== corMatch) {
      signals.push({
        patternKey: `translit_${genMatch}_to_${corMatch}`,
        preferenceText: `Spell the Romanized word "${corMatch}" as "${corMatch}" instead of "${genMatch}".`,
        category: 'TRANSLITERATION',
      });
    }
  }

  // 2. Punctuation: exclamation removal vs addition
  if (gen.includes('!') && !cor.includes('!')) {
    signals.push({
      patternKey: 'punct_no_exclamation',
      preferenceText: 'Avoid exclamation marks; use periods for a calm, composed tone.',
      category: 'PUNCTUATION',
    });
  } else if (!gen.includes('!') && cor.includes('!')) {
    signals.push({
      patternKey: 'punct_use_exclamation',
      preferenceText: 'Preserve enthusiastic exclamation marks in casual messages.',
      category: 'TONE',
    });
  }

  // 3. Sentence length & conciseness
  if (genWords.length >= 8 && corWords.length <= Math.floor(genWords.length * 0.78)) {
    signals.push({
      patternKey: 'length_concise_direct',
      preferenceText: 'Keep sentences concise and direct; trim unnecessary transitional words and verbose phrasing.',
      category: 'LENGTH',
    });
  }

  // 4. Formality: contractions vs expanded forms
  const genLower = gen.toLowerCase();
  const corLower = cor.toLowerCase();
  let switchedToContractions = false;
  let switchedToFormalExpanded = false;

  for (const [expanded, contracted] of CONTRACTION_PAIRS) {
    if (genLower.includes(expanded) && corLower.includes(contracted)) {
      switchedToContractions = true;
    } else if (genLower.includes(contracted) && corLower.includes(expanded)) {
      switchedToFormalExpanded = true;
    }
  }

  if (switchedToContractions) {
    signals.push({
      patternKey: 'formality_natural_contractions',
      preferenceText: "Use natural conversational contractions (e.g., don't, can't, let's, we're) instead of stiff expanded verbs.",
      category: 'FORMALITY',
    });
  } else if (switchedToFormalExpanded) {
    signals.push({
      patternKey: 'formality_no_contractions',
      preferenceText: 'Use full formal verb forms (e.g., do not, cannot, we will) without contractions.',
      category: 'FORMALITY',
    });
  }

  // 5. Vocabulary & Spelling 1-to-1 substitutions
  const genTokens = gen.split(/\s+/).map((t) => t.replace(/^[.,?!;:]+|[.,?!;:]+$/g, ''));
  const corTokens = cor.split(/\s+/).map((t) => t.replace(/^[.,?!;:]+|[.,?!;:]+$/g, ''));

  if (genTokens.length === corTokens.length) {
    let addedVocab = 0;
    for (let i = 0; i < genTokens.length && addedVocab < 2; i++) {
      const g = genTokens[i];
      const c = corTokens[i];
      if (g !== c && g.length >= 2 && c.length >= 2) {
        if (!STOP_WORDS.has(g.toLowerCase()) && !STOP_WORDS.has(c.toLowerCase())) {
          const alreadyAdded = signals.some((s) => s.patternKey.endsWith(`_to_${c.toLowerCase()}`));
          if (alreadyAdded) continue;

          if (g.toLowerCase() === c.toLowerCase()) {
            signals.push({
              patternKey: `spelling_case_${c.toLowerCase()}`,
              preferenceText: `Always spell and capitalize "${c}" exactly as "${c}".`,
              category: 'SPELLING',
            });
          } else {
            const isIndic = HINGLISH_MARKERS.has(c.toLowerCase()) || KANGLISH_MARKERS.has(c.toLowerCase());
            signals.push({
              patternKey: `vocab_${g.toLowerCase()}_to_${c.toLowerCase()}`,
              preferenceText: isIndic
                ? `Spell the Romanized word "${c}" as "${c}" instead of "${g}".`
                : `Prefer the word "${c}" instead of "${g}" when appropriate.`,
              category: isIndic ? 'TRANSLITERATION' : 'VOCABULARY',
            });
          }
          addedVocab++;
        }
      }
    }
  }

  return signals;
}

export function selectMostRelevantExamples(
  currentTranscript: string,
  candidates: FeedbackExample[],
  mode: RefinementMode,
  maxExamples: number = 2
): FeedbackExample[] {
  const queryContent = tokenizeContentWords(currentTranscript);
  const queryLen = Math.max(1, tokenizeWords(currentTranscript).length);

  return candidates
    .filter(
      (f) =>
        (f.writingMode === mode || mode === 'CUSTOM' || mode === 'CASUAL') &&
        (f.feedbackType === 'EDITED' || f.feedbackType === 'ACCEPTED') &&
        f.approvedCorrection.trim().length > 0 &&
        (f.signalWeight ?? 1.0) > 0
    )
    .map((candidate) => {
      const candContent = tokenizeContentWords(candidate.originalTranscript);
      const jaccard = computeJaccard(queryContent, candContent);
      const candLen = Math.max(1, tokenizeWords(candidate.originalTranscript).length);
      const lenRatio = Math.min(queryLen, candLen) / Math.max(queryLen, candLen);
      const editPriority = candidate.feedbackType === 'EDITED' ? 1.0 : 0.55;
      const score = jaccard * 0.65 + lenRatio * 0.2 + editPriority * 0.15;
      return { candidate, score };
    })
    .sort((a, b) => b.score - a.score)
    .slice(0, maxExamples)
    .map((x) => x.candidate);
}

export function executeVoiceRefinementPipeline(
  rawTranscript: string,
  mode: RefinementMode,
  preferences: WritingPreference[],
  dictionary: PersonalDictionaryEntry[],
  feedbackExamples: FeedbackExample[],
  config: InferenceConfig,
  simulateTimeoutError: boolean = false
): PipelineTrace {
  const trimmedRaw = rawTranscript.trim();

  const detectedLanguageStyle =
    config.languageStyle === 'AUTO_CODE_MIXED'
      ? detectDominantLanguageStyle(trimmedRaw)
      : config.languageStyle;

  // Step 1: Apply Personal Dictionary conservatively
  const sortedDict = [...dictionary].sort((a, b) => b.spokenForm.length - a.spokenForm.length);
  const matchedDictionaryEntries: PersonalDictionaryEntry[] = [];
  let dictApplied = trimmedRaw;

  for (const entry of sortedDict) {
    if (!entry.spokenForm.trim()) continue;
    const escaped = entry.spokenForm.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const regex = new RegExp(`\\b${escaped}\\b`, 'gi');
    if (regex.test(dictApplied)) {
      matchedDictionaryEntries.push(entry);
      dictApplied = dictApplied.replace(regex, entry.preferredSpelling);
    }
  }

  // Step 2: Filter promoted preferences (confidenceScore >= 0.65)
  const promotedPreferences = config.personalizationEnabled
    ? preferences
        .filter((p) => p.enabled && (p.confidenceScore ?? 1.0) >= PROMOTION_CONFIDENCE_THRESHOLD)
        .slice(0, 5)
    : [];

  // Step 3: Similarity-ranked top-2 few-shot examples
  const fewShotExamplesUsed = config.personalizationEnabled
    ? selectMostRelevantExamples(dictApplied, feedbackExamples, mode, 2)
    : [];

  // Step 4: Build ChatML Prompts
  const constructedChatMlPrompt = buildQwenChatMlPrompt(
    dictApplied,
    mode,
    detectedLanguageStyle,
    config.customRewriteInstruction,
    promotedPreferences,
    fewShotExamplesUsed,
    config.personalizationEnabled
  );

  const unpersonalizedBaselinePrompt = buildQwenChatMlPrompt(
    trimmedRaw,
    mode,
    detectedLanguageStyle,
    config.customRewriteInstruction,
    [],
    [],
    false
  );

  const unpersonalizedBaselineOutput = refineTranscriptDeterministically(
    trimmedRaw,
    mode,
    detectedLanguageStyle,
    config.customRewriteInstruction,
    [],
    []
  );

  if (mode === 'RAW') {
    return {
      rawTranscript: trimmedRaw,
      detectedLanguageStyle,
      dictionaryAppliedTranscript: trimmedRaw,
      matchedDictionaryEntries: [],
      activePreferencesUsed: [],
      fewShotExamplesUsed: [],
      constructedChatMlPrompt: '// Raw mode inserts the recognized transcript with minimal changes.',
      finalOutput: trimmedRaw,
      unpersonalizedBaselineOutput: trimmedRaw,
      unpersonalizedBaselinePrompt: '// Raw mode',
      fallbackTriggered: false,
      sttLatencyMs: 280,
      preprocessLatencyMs: 1,
      retrievalLatencyMs: 0,
      llmInferenceLatencyMs: 0,
      validationLatencyMs: 1,
      totalLatencyMs: 282,
      tokensPerSecond: 0,
    };
  }

  if (simulateTimeoutError) {
    return {
      rawTranscript: trimmedRaw,
      detectedLanguageStyle,
      dictionaryAppliedTranscript: dictApplied,
      matchedDictionaryEntries,
      activePreferencesUsed: promotedPreferences,
      fewShotExamplesUsed,
      constructedChatMlPrompt,
      finalOutput: trimmedRaw,
      unpersonalizedBaselineOutput,
      unpersonalizedBaselinePrompt,
      fallbackTriggered: true,
      fallbackReason: `Inference exceeded ${config.timeoutMs}ms budget or was cancelled. Restored raw STT transcript.`,
      sttLatencyMs: 310,
      preprocessLatencyMs: 3,
      retrievalLatencyMs: 4,
      llmInferenceLatencyMs: config.timeoutMs,
      validationLatencyMs: 2,
      totalLatencyMs: 319 + config.timeoutMs,
      tokensPerSecond: 0,
    };
  }

  let refined = config.personalizationEnabled
    ? refineTranscriptDeterministically(
        dictApplied,
        mode,
        detectedLanguageStyle,
        config.customRewriteInstruction,
        promotedPreferences,
        fewShotExamplesUsed
      )
    : unpersonalizedBaselineOutput;

  let fallbackTriggered = false;
  let fallbackReason: string | undefined;
  if (!refined || refined.trim().length === 0) {
    refined = trimmedRaw;
    fallbackTriggered = true;
    fallbackReason = 'Empty model output detected; reverted to raw transcript.';
  }

  const wordCount = Math.max(4, trimmedRaw.split(/\s+/).length);
  const estimatedTokens = Math.round(wordCount * 1.35);
  const tokensPerSec = config.threads >= 4 ? 26.4 : 16.8;
  const llmLatencyMs = Math.min(
    config.timeoutMs - 150,
    Math.round(340 + (estimatedTokens / tokensPerSec) * 1000)
  );

  return {
    rawTranscript: trimmedRaw,
    detectedLanguageStyle,
    dictionaryAppliedTranscript: dictApplied,
    matchedDictionaryEntries,
    activePreferencesUsed: promotedPreferences,
    fewShotExamplesUsed,
    constructedChatMlPrompt,
    finalOutput: refined,
    unpersonalizedBaselineOutput,
    unpersonalizedBaselinePrompt,
    fallbackTriggered,
    fallbackReason,
    sttLatencyMs: 290,
    preprocessLatencyMs: 3,
    retrievalLatencyMs: 5,
    llmInferenceLatencyMs: llmLatencyMs,
    validationLatencyMs: 2,
    totalLatencyMs: 300 + llmLatencyMs,
    tokensPerSecond: tokensPerSec,
  };
}

function buildQwenChatMlPrompt(
  transcript: string,
  mode: RefinementMode,
  languageStyle: LanguageStyle,
  customInstruction: string,
  preferences: WritingPreference[],
  examples: FeedbackExample[],
  personalizationEnabled: boolean
): string {
  const codeMixRule =
    languageStyle === 'HINGLISH'
      ? 'Language Style: HINGLISH (Hindi-English code-mixed speech in Latin/Roman alphabet). Keep all Hindi words in English letters (e.g., "Kal college mein meeting hai, toh mujhe time pe aana."). NEVER convert Hinglish into Devanagari script and NEVER translate Hindi words into English.'
      : languageStyle === 'KANGLISH'
      ? 'Language Style: KANGLISH (Kannada-English code-mixed speech in Latin/Roman alphabet). Keep all Kannada words in English letters (e.g., "Naale college ge bega barbeku."). NEVER convert Kanglish into Kannada script and NEVER translate Kannada words into English.'
      : 'Language Style: Preserve any code-mixed Hindi (Hinglish) or Kannada (Kanglish) words in Latin/English script without translating them into English or Indic scripts.';

  const modeRule =
    mode === 'CLEAN'
      ? 'You are a voice keyboard text cleaner operating in CLEAN mode. Correct speech recognition errors, spelling, capitalization, and punctuation while strictly preserving the original words and language mixture.'
      : mode === 'POLISH'
      ? 'You are a voice keyboard text refiner operating in POLISH mode. Improve clarity and sentence flow and resolve mid-sentence spoken self-corrections while preserving exact meaning and language style.'
      : mode === 'CASUAL'
      ? 'You are a voice keyboard refiner operating in CASUAL mode. Keep the wording natural, warm, and conversational for chat apps while preserving Hinglish/Kanglish expressions.'
      : `You are a voice keyboard rewriter operating in CUSTOM mode. User Instruction: ${
          customInstruction.trim() || 'Clean up punctuation and preserve meaning.'
        }`;

  const prefLines =
    personalizationEnabled && preferences.length > 0
      ? '\nLearned User Style & Transliteration Preferences:\n' +
        preferences.map((p) => `- [${p.context}] ${p.preferenceText}`).join('\n')
      : '';

  const fewShotBlocks =
    personalizationEnabled && examples.length > 0
      ? examples
          .map(
            (ex) =>
              `<|im_start|>user\n${ex.originalTranscript}<|im_end|>\n<|im_start|>assistant\n${ex.approvedCorrection}<|im_end|>`
          )
          .join('\n')
      : '';

  return [
    `<|im_start|>system\n${modeRule}\n\n${codeMixRule}${prefLines}<|im_end|>`,
    fewShotBlocks,
    `<|im_start|>user\n${transcript}<|im_end|>`,
    `<|im_start|>assistant`,
  ]
    .filter(Boolean)
    .join('\n');
}

function refineTranscriptDeterministically(
  input: string,
  mode: RefinementMode,
  languageStyle: LanguageStyle,
  customInstruction: string,
  activePreferences: WritingPreference[],
  fewShotExamples: FeedbackExample[]
): string {
  let text = input;

  // 1. Remove spoken fillers
  text = text.replace(/\b(um+|uh+|er+|ah+|like,?\s+you know|you know what i mean|basically|literally)\b[,]?\s*/gi, '');

  // 2. Collapse immediate duplicate words
  text = text.replace(/\b(\w+)\s+\1\b/gi, '$1');

  // 3. Natural punctuation for Hinglish clause connectors ("meeting hai toh" -> "meeting hai, toh")
  if (languageStyle === 'HINGLISH') {
    text = text.replace(/\b(hai|hain)\s+(toh|lekin|par)\b/gi, '$1, $2');
  }

  // 4. Normalize numbers/times
  text = text
    .replace(/\beight gigs of ram\b/gi, '8 GB of RAM')
    .replace(/\bthree seconds\b/gi, '3 seconds')
    .replace(/\btwo pm\b/gi, '2:00 PM')
    .replace(/\bthree thirty pm\b/gi, '3:30 PM')
    .replace(/\bfour pm\b/gi, '4:00 PM');

  if (mode === 'POLISH' || mode === 'CUSTOM') {
    text = text.replace(
      /\b(at\s+\d+:\d+\s*[AP]M|\b(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)(?:\s+afternoon|\s+morning)?)\s+(?:no\s+wait(?:\s+actually)?|wait\s+no|i\s+mean|sorry\s+actually)\s+((?:at\s+)?\d+:\d+\s*[AP]M|(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)(?:\s+afternoon|\s+morning)?)/gi,
      '$2'
    );
    text = text.replace(
      /\b(\d+:\d+\s*[AP]M)\s+(?:no\s+wait(?:\s+actually)?|i\s+mean)\s+(\d+:\d+\s*[AP]M)/gi,
      '$2'
    );
  }

  // Apply learned preferences (including Romanized Hinglish/Kanglish transliteration rules)
  for (const pref of activePreferences) {
    const pLower = pref.preferenceText.toLowerCase();
    const key = pref.patternKey || '';

    if (key === 'length_concise_direct' || pLower.includes('concise') || pLower.includes('hedging')) {
      text = text.replace(/\bi think maybe\s+/gi, '');
      text = text.replace(/\bi guess maybe\s+/gi, '');
      text = text.replace(/\bso for\b/gi, 'For');
    }

    if (key === 'formality_natural_contractions' || pLower.includes('contractions')) {
      for (const [expanded, contracted] of CONTRACTION_PAIRS) {
        const reg = new RegExp(`\\b${expanded}\\b`, 'gi');
        text = text.replace(reg, contracted);
      }
    }

    if (key === 'formality_no_contractions' || pLower.includes('without contractions')) {
      for (const [expanded, contracted] of CONTRACTION_PAIRS) {
        const reg = new RegExp(`\\b${contracted}\\b`, 'gi');
        text = text.replace(reg, expanded);
      }
    }

    // Learned Romanized transliteration rule ("Spell the Romanized word X as X instead of Y")
    const translitMatch = pref.preferenceText.match(/Spell the (?:Romanized|Hinglish\/Kanglish) word "([^"]+)" as "[^"]+" instead of "([^"]+)"/i);
    if (translitMatch) {
      const [, preferredWord, variantWord] = translitMatch;
      const tReg = new RegExp(`\\b${variantWord.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\b`, 'gi');
      text = text.replace(tReg, preferredWord);
    }

    const vocabMatch = pref.preferenceText.match(/Prefer the word "([^"]+)" instead of "([^"]+)"/i);
    if (vocabMatch) {
      const [, preferredWord, originalWord] = vocabMatch;
      const vReg = new RegExp(`\\b${originalWord.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\b`, 'gi');
      text = text.replace(vReg, preferredWord);
    }
  }

  for (const ex of fewShotExamples) {
    if (
      ex.originalTranscript.toLowerCase().includes('push the release') &&
      ex.approvedCorrection.toLowerCase().includes("let's move the release")
    ) {
      text = text.replace(/\bcan we push the release\b/gi, "let's move the release");
    }
  }

  if (mode === 'CUSTOM' && customInstruction.toLowerCase().includes('bullet')) {
    text = `• ${text}`;
  }

  text = text.replace(/\s{2,}/g, ' ').trim();
  text = text.replace(/\bi\b/g, 'I');
  if (text.length > 0) {
    text = text.charAt(0).toUpperCase() + text.slice(1);
  }

  const noExclamationPref = activePreferences.some(
    (p) => p.patternKey === 'punct_no_exclamation' || p.preferenceText.toLowerCase().includes('exclamation')
  );
  const useExclamationPref = activePreferences.some((p) => p.patternKey === 'punct_use_exclamation');

  if (text.length > 0 && !/[.!?]$/.test(text)) {
    if (!noExclamationPref && (mode === 'CASUAL' || useExclamationPref || /^hey\b/i.test(text))) {
      text += '!';
    } else {
      text += '.';
    }
  }

  if (noExclamationPref) {
    text = text.replace(/!+/g, '.');
  }

  return text;
}

function tokenizeWords(text: string): string[] {
  return text
    .toLowerCase()
    .split(/[^a-z0-9']+/)
    .filter(Boolean);
}

function tokenizeContentWords(text: string): Set<string> {
  return new Set(tokenizeWords(text).filter((w) => w.length > 2 && !STOP_WORDS.has(w)));
}

function computeJaccard(setA: Set<string>, setB: Set<string>): number {
  if (setA.size === 0 || setB.size === 0) return 0;
  let inter = 0;
  for (const item of setA) {
    if (setB.has(item)) inter++;
  }
  const union = setA.size + setB.size - inter;
  return union === 0 ? 0 : inter / union;
}
