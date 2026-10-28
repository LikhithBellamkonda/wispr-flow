import React, { useState } from 'react';
import {
  Plus,
  Trash2,
  ToggleLeft,
  ToggleRight,
  BookOpen,
  MessageSquareQuote,
  Sliders,
  RotateCcw,
} from 'lucide-react';
import {
  WritingPreference,
  FeedbackExample,
  PersonalDictionaryEntry,
} from '../types';
import { PROMOTION_CONFIDENCE_THRESHOLD } from '../utils/refinementEngine';

interface PersonalizationStudioTabProps {
  preferences: WritingPreference[];
  dictionary: PersonalDictionaryEntry[];
  feedbackExamples: FeedbackExample[];
  onTogglePreference: (id: string) => void;
  onDeletePreference: (id: string) => void;
  onAddPreference: (text: string, context: string) => void;
  onAddDictionaryEntry: (entry: Omit<PersonalDictionaryEntry, 'id'>) => void;
  onDeleteDictionaryEntry: (id: string) => void;
  onDeleteFeedback: (id: string) => void;
  onResetAllLearnedData: () => void;
}

export const PersonalizationStudioTab: React.FC<PersonalizationStudioTabProps> = ({
  preferences,
  dictionary,
  feedbackExamples,
  onTogglePreference,
  onDeletePreference,
  onAddPreference,
  onAddDictionaryEntry,
  onDeleteDictionaryEntry,
  onDeleteFeedback,
  onResetAllLearnedData,
}) => {
  const [activeTable, setActiveTable] = useState<'PREFERENCES' | 'DICTIONARY' | 'FEEDBACK'>('PREFERENCES');

  const [newPrefText, setNewPrefText] = useState('');
  const [newPrefContext, setNewPrefContext] = useState('TONE');

  const [spokenForm, setSpokenForm] = useState('');
  const [preferredSpelling, setPreferredSpelling] = useState('');
  const [category, setCategory] = useState<'Name' | 'Acronym' | 'Technical' | 'Custom'>('Name');

  const handleCreatePref = (e: React.FormEvent) => {
    e.preventDefault();
    if (!newPrefText.trim()) return;
    onAddPreference(newPrefText.trim(), newPrefContext);
    setNewPrefText('');
  };

  const handleCreateDict = (e: React.FormEvent) => {
    e.preventDefault();
    if (!spokenForm.trim() || !preferredSpelling.trim()) return;
    onAddDictionaryEntry({
      spokenForm: spokenForm.trim().toLowerCase(),
      preferredSpelling: preferredSpelling.trim(),
      category,
    });
    setSpokenForm('');
    setPreferredSpelling('');
  };

  return (
    <div className="flex flex-col gap-6">
      <div className="bg-slate-900 border border-slate-800 rounded-xl p-5 flex flex-wrap items-center justify-between gap-4">
        <div>
          <h2 className="text-base font-semibold text-slate-100">
            On-Device Room SQLite — Adaptive Learning Inspector (Schema v3)
          </h2>
          <p className="text-xs text-slate-400 mt-0.5">
            Single corrections start as 35% candidates; repeated patterns reach &ge;65% confidence and automatically condition Qwen2.5 prompts.
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <div className="flex items-center gap-1 bg-slate-950 p-1 rounded-lg border border-slate-800">
            <button
              onClick={() => setActiveTable('PREFERENCES')}
              className={`flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium transition-colors whitespace-nowrap shrink-0 ${
                activeTable === 'PREFERENCES'
                  ? 'bg-indigo-600 text-white'
                  : 'text-slate-400 hover:text-slate-200'
              }`}
            >
              <Sliders className="w-3.5 h-3.5" />
              <span>writing_preferences ({preferences.length})</span>
            </button>
            <button
              onClick={() => setActiveTable('FEEDBACK')}
              className={`flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium transition-colors whitespace-nowrap shrink-0 ${
                activeTable === 'FEEDBACK'
                  ? 'bg-indigo-600 text-white'
                  : 'text-slate-400 hover:text-slate-200'
              }`}
            >
              <MessageSquareQuote className="w-3.5 h-3.5" />
              <span>feedback_examples ({feedbackExamples.length})</span>
            </button>
            <button
              onClick={() => setActiveTable('DICTIONARY')}
              className={`flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium transition-colors whitespace-nowrap shrink-0 ${
                activeTable === 'DICTIONARY'
                  ? 'bg-indigo-600 text-white'
                  : 'text-slate-400 hover:text-slate-200'
              }`}
            >
              <BookOpen className="w-3.5 h-3.5" />
              <span>personal_dictionary ({dictionary.length})</span>
            </button>
          </div>

          <button
            onClick={onResetAllLearnedData}
            className="flex items-center gap-1.5 px-3 py-1.5 bg-red-950/60 hover:bg-red-900/70 border border-red-800/60 text-red-300 rounded-lg text-xs font-medium transition-colors whitespace-nowrap shrink-0"
          >
            <RotateCcw className="w-3.5 h-3.5" />
            <span>Reset All Learned Data</span>
          </button>
        </div>
      </div>

      {/* TABLE 1: WRITING PREFERENCES */}
      {activeTable === 'PREFERENCES' && (
        <div className="bg-slate-900 border border-slate-800 rounded-xl p-5 flex flex-col gap-5">
          <form onSubmit={handleCreatePref} className="grid grid-cols-1 md:grid-cols-12 gap-3">
            <div className="md:col-span-7">
              <input
                type="text"
                value={newPrefText}
                onChange={(e) => setNewPrefText(e.target.value)}
                placeholder="Add manual writing rule (e.g., 'Prefer the word buy instead of purchase')..."
                className="w-full bg-slate-950 border border-slate-800 rounded-lg px-3.5 py-2 text-xs text-slate-100 placeholder:text-slate-500 focus:outline-none focus:border-indigo-500"
              />
            </div>
            <div className="md:col-span-3">
              <select
                value={newPrefContext}
                onChange={(e) => setNewPrefContext(e.target.value)}
                className="w-full bg-slate-950 border border-slate-800 rounded-lg px-3 py-2 text-xs text-slate-200"
              >
                <option value="TONE">Category: TONE</option>
                <option value="PUNCTUATION">Category: PUNCTUATION</option>
                <option value="LENGTH">Category: LENGTH</option>
                <option value="VOCABULARY">Category: VOCABULARY</option>
                <option value="FORMALITY">Category: FORMALITY</option>
                <option value="SPELLING">Category: SPELLING</option>
              </select>
            </div>
            <div className="md:col-span-2">
              <button
                type="submit"
                className="w-full flex items-center justify-center gap-1.5 px-4 py-2 bg-indigo-600 hover:bg-indigo-500 text-white rounded-lg text-xs font-medium transition-colors whitespace-nowrap shrink-0"
              >
                <Plus className="w-3.5 h-3.5" />
                <span>Add Rule</span>
              </button>
            </div>
          </form>

          <div className="overflow-x-auto">
            <table className="w-full text-left border-collapse">
              <thead>
                <tr className="border-b border-slate-800 text-[11px] font-mono text-slate-400">
                  <th className="py-2.5 px-3">State</th>
                  <th className="py-2.5 px-3">source_type · category</th>
                  <th className="py-2.5 px-3">preference_text</th>
                  <th className="py-2.5 px-3">occurrences · confidence</th>
                  <th className="py-2.5 px-3 text-right">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-800/70 text-xs">
                {preferences.map((pref) => {
                  const conf = pref.confidenceScore ?? 1.0;
                  const isPromoted = conf >= PROMOTION_CONFIDENCE_THRESHOLD;
                  return (
                    <tr key={pref.id} className="hover:bg-slate-950/50">
                      <td className="py-3 px-3">
                        <button
                          onClick={() => onTogglePreference(pref.id)}
                          className="flex items-center gap-1.5 text-xs font-medium"
                        >
                          {pref.enabled ? (
                            <>
                              <ToggleRight className="w-5 h-5 text-emerald-400" />
                              <span className="text-emerald-400">Enabled</span>
                            </>
                          ) : (
                            <>
                              <ToggleLeft className="w-5 h-5 text-slate-500" />
                              <span className="text-slate-500">Disabled</span>
                            </>
                          )}
                        </button>
                      </td>
                      <td className="py-3 px-3 font-mono text-slate-300 whitespace-nowrap">
                        {pref.sourceType || 'MANUAL'} · {pref.context}
                      </td>
                      <td className="py-3 px-3 font-medium text-slate-200">{pref.preferenceText}</td>
                      <td className="py-3 px-3 font-mono tabular-nums whitespace-nowrap">
                        <span className={isPromoted ? 'text-emerald-400' : 'text-amber-400'}>
                          {pref.occurrenceCount ?? 1}x · {Math.round(conf * 100)}%{' '}
                          {isPromoted ? '(PROMOTED)' : '(CANDIDATE)'}
                        </span>
                      </td>
                      <td className="py-3 px-3 text-right">
                        <button
                          onClick={() => onDeletePreference(pref.id)}
                          className="p-1.5 text-slate-400 hover:text-red-400 transition-colors"
                          title="Delete preference"
                        >
                          <Trash2 className="w-4 h-4" />
                        </button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* TABLE 2: PERSONAL DICTIONARY */}
      {activeTable === 'DICTIONARY' && (
        <div className="bg-slate-900 border border-slate-800 rounded-xl p-5 flex flex-col gap-5">
          <form onSubmit={handleCreateDict} className="grid grid-cols-1 md:grid-cols-12 gap-3">
            <div className="md:col-span-4">
              <input
                type="text"
                value={spokenForm}
                onChange={(e) => setSpokenForm(e.target.value)}
                placeholder="Spoken form (e.g., 'kubernetees')"
                className="w-full bg-slate-950 border border-slate-800 rounded-lg px-3.5 py-2 text-xs text-slate-100 placeholder:text-slate-500 focus:outline-none focus:border-indigo-500"
              />
            </div>
            <div className="md:col-span-4">
              <input
                type="text"
                value={preferredSpelling}
                onChange={(e) => setPreferredSpelling(e.target.value)}
                placeholder="Preferred spelling (e.g., 'Kubernetes')"
                className="w-full bg-slate-950 border border-slate-800 rounded-lg px-3.5 py-2 text-xs text-slate-100 placeholder:text-slate-500 focus:outline-none focus:border-indigo-500"
              />
            </div>
            <div className="md:col-span-2">
              <select
                value={category}
                onChange={(e) => setCategory(e.target.value as any)}
                className="w-full bg-slate-950 border border-slate-800 rounded-lg px-3 py-2 text-xs text-slate-200"
              >
                <option value="Name">Name</option>
                <option value="Acronym">Acronym</option>
                <option value="Technical">Technical</option>
                <option value="Custom">Custom</option>
              </select>
            </div>
            <div className="md:col-span-2">
              <button
                type="submit"
                className="w-full flex items-center justify-center gap-1.5 px-4 py-2 bg-indigo-600 hover:bg-indigo-500 text-white rounded-lg text-xs font-medium transition-colors whitespace-nowrap shrink-0"
              >
                <Plus className="w-3.5 h-3.5" />
                <span>Add Entry</span>
              </button>
            </div>
          </form>

          <div className="overflow-x-auto">
            <table className="w-full text-left border-collapse">
              <thead>
                <tr className="border-b border-slate-800 text-[11px] font-mono text-slate-400">
                  <th className="py-2.5 px-3">spoken_form</th>
                  <th className="py-2.5 px-3">preferred_spelling</th>
                  <th className="py-2.5 px-3">category</th>
                  <th className="py-2.5 px-3 text-right">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-800/70 text-xs">
                {dictionary.map((entry) => (
                  <tr key={entry.id} className="hover:bg-slate-950/50">
                    <td className="py-3 px-3 font-mono text-slate-300">"{entry.spokenForm}"</td>
                    <td className="py-3 px-3 font-mono font-semibold text-indigo-300">
                      "{entry.preferredSpelling}"
                    </td>
                    <td className="py-3 px-3 text-slate-400">{entry.category}</td>
                    <td className="py-3 px-3 text-right">
                      <button
                        onClick={() => onDeleteDictionaryEntry(entry.id)}
                        className="p-1.5 text-slate-400 hover:text-red-400 transition-colors"
                      >
                        <Trash2 className="w-4 h-4" />
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* TABLE 3: FEEDBACK EXAMPLES */}
      {activeTable === 'FEEDBACK' && (
        <div className="bg-slate-900 border border-slate-800 rounded-xl p-5 flex flex-col gap-4">
          <div className="overflow-x-auto">
            <table className="w-full text-left border-collapse">
              <thead>
                <tr className="border-b border-slate-800 text-[11px] font-mono text-slate-400">
                  <th className="py-2.5 px-3">mode · type · weight</th>
                  <th className="py-2.5 px-3">original_transcript</th>
                  <th className="py-2.5 px-3">generated_output</th>
                  <th className="py-2.5 px-3">approved_correction</th>
                  <th className="py-2.5 px-3 text-right">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-800/70 text-xs">
                {feedbackExamples.map((fb) => (
                  <tr key={fb.id} className="hover:bg-slate-950/50">
                    <td className="py-3 px-3 font-mono text-slate-300 whitespace-nowrap">
                      {fb.writingMode} ·{' '}
                      <span
                        className={
                          fb.feedbackType === 'REVERTED' || fb.feedbackType === 'REJECTED'
                            ? 'text-amber-400'
                            : 'text-emerald-400'
                        }
                      >
                        {fb.feedbackType} ({fb.signalWeight ?? 1.0})
                      </span>
                    </td>
                    <td className="py-3 px-3 text-slate-400 max-w-xs">{fb.originalTranscript}</td>
                    <td className="py-3 px-3 text-slate-300 max-w-xs">{fb.generatedOutput}</td>
                    <td className="py-3 px-3 text-emerald-300 font-medium max-w-xs">
                      {fb.approvedCorrection || '— (Weak Negative Signal)'}
                    </td>
                    <td className="py-3 px-3 text-right">
                      <button
                        onClick={() => onDeleteFeedback(fb.id)}
                        className="p-1.5 text-slate-400 hover:text-red-400 transition-colors"
                      >
                        <Trash2 className="w-4 h-4" />
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
};
