import React from 'react';
import { CheckSquare, Square, ExternalLink, ShieldCheck, Cpu } from 'lucide-react';
import { PhaseTask, AcceptanceCriterion } from '../types';

interface ExecutionRoadmapTabProps {
  phases: PhaseTask[];
  criteria: AcceptanceCriterion[];
  onTogglePhaseTask: (id: string) => void;
  onToggleCriterion: (id: number) => void;
  onOpenSourceFile: (path: string) => void;
}

export const ExecutionRoadmapTab: React.FC<ExecutionRoadmapTabProps> = ({
  phases,
  criteria,
  onTogglePhaseTask,
  onToggleCriterion,
  onOpenSourceFile,
}) => {
  const completedTasks = phases.filter((p) => p.completed).length;
  const verifiedCriteria = criteria.filter((c) => c.verified).length;

  return (
    <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
      {/* Left 7 Cols: 6 Development Phases (Section 8) */}
      <div className="lg:col-span-7 bg-slate-900 border border-slate-800 rounded-xl p-5 flex flex-col gap-4">
        <div className="flex items-center justify-between border-b border-slate-800 pb-3">
          <div>
            <h2 className="text-base font-semibold text-slate-100">
              Development Priorities — Phases 1 to 6 (Section 8)
            </h2>
            <p className="text-xs text-slate-400 mt-0.5">
              Click any engineering deliverable to toggle state or jump directly to its Kotlin/C++ implementation.
            </p>
          </div>
          <span className="text-xs font-mono tabular-nums text-emerald-400">
            {completedTasks} / {phases.length} Ready
          </span>
        </div>

        <div className="divide-y divide-slate-800/80">
          {phases.map((item) => (
            <div key={item.id} className="py-3.5 first:pt-1 last:pb-1 flex flex-col gap-1.5">
              <div className="flex items-start justify-between gap-3">
                <button
                  onClick={() => onTogglePhaseTask(item.id)}
                  className="flex items-start gap-2.5 text-left group"
                >
                  {item.completed ? (
                    <CheckSquare className="w-4 h-4 text-emerald-400 shrink-0 mt-0.5" />
                  ) : (
                    <Square className="w-4 h-4 text-slate-500 shrink-0 mt-0.5" />
                  )}
                  <div>
                    <div className="text-xs font-mono text-indigo-400">
                      Phase {item.phaseNumber}: {item.phaseTitle} · {item.featureRef}
                    </div>
                    <div
                      className={`text-sm font-medium mt-0.5 ${
                        item.completed ? 'text-slate-100' : 'text-slate-400'
                      }`}
                    >
                      {item.taskTitle}
                    </div>
                  </div>
                </button>

                <button
                  onClick={() => onOpenSourceFile(item.targetFile)}
                  className="flex items-center gap-1 px-2.5 py-1 bg-slate-950 hover:bg-slate-800 border border-slate-800 rounded text-[11px] font-mono text-slate-300 transition-colors whitespace-nowrap shrink-0"
                >
                  <span>{item.targetFile.split('/').pop()}</span>
                  <ExternalLink className="w-3 h-3" />
                </button>
              </div>
              <p className="text-xs text-slate-400 pl-6">{item.technicalNotes}</p>
            </div>
          ))}
        </div>
      </div>

      {/* Right 5 Cols: 12 MVP Acceptance Criteria (Section 7) & Vivo Y200e Specs (Section 6) */}
      <div className="lg:col-span-5 flex flex-col gap-6">
        <div className="bg-slate-900 border border-slate-800 rounded-xl p-5 flex flex-col gap-4">
          <div className="flex items-center justify-between border-b border-slate-800 pb-3">
            <div className="flex items-center gap-2">
              <ShieldCheck className="w-4 h-4 text-emerald-400" />
              <h2 className="text-base font-semibold text-slate-100">
                12 MVP Acceptance Criteria (Section 7)
              </h2>
            </div>
            <span className="text-xs font-mono tabular-nums text-emerald-400">
              {verifiedCriteria} / {criteria.length} Verified
            </span>
          </div>

          <div className="divide-y divide-slate-800/70">
            {criteria.map((criterion) => (
              <div key={criterion.id} className="py-2.5 flex items-start justify-between gap-3">
                <button
                  onClick={() => onToggleCriterion(criterion.id)}
                  className="flex items-start gap-2.5 text-left"
                >
                  {criterion.verified ? (
                    <CheckSquare className="w-4 h-4 text-emerald-400 shrink-0 mt-0.5" />
                  ) : (
                    <Square className="w-4 h-4 text-slate-500 shrink-0 mt-0.5" />
                  )}
                  <div>
                    <div className="text-xs font-medium text-slate-200">
                      {criterion.id}. {criterion.title}
                    </div>
                    <div className="text-[11px] text-slate-400 font-mono mt-0.5">
                      {criterion.category} · {criterion.verificationMethod}
                    </div>
                  </div>
                </button>
              </div>
            ))}
          </div>
        </div>

        {/* Target Device Profile Card (Section 6) */}
        <div className="bg-slate-900 border border-slate-800 rounded-xl p-5 flex flex-col gap-3">
          <div className="flex items-center gap-2 text-sm font-semibold text-slate-100">
            <Cpu className="w-4 h-4 text-indigo-400" />
            <span>Target Hardware Profile: Vivo Y200e (Section 6)</span>
          </div>
          <div className="grid grid-cols-2 gap-3 text-xs font-mono">
            <div className="p-3 bg-slate-950 border border-slate-800 rounded-lg">
              <div className="text-slate-400">Physical RAM</div>
              <div className="text-slate-100 font-semibold mt-0.5">8 GB LPDDR4X</div>
            </div>
            <div className="p-3 bg-slate-950 border border-slate-800 rounded-lg">
              <div className="text-slate-400">Model Footprint</div>
              <div className="text-slate-100 font-semibold mt-0.5">~398 MB (Q4_K_M GGUF)</div>
            </div>
            <div className="p-3 bg-slate-950 border border-slate-800 rounded-lg">
              <div className="text-slate-400">Short-Text Target</div>
              <div className="text-emerald-400 font-semibold mt-0.5">&lt; 3,000 ms (Async IO)</div>
            </div>
            <div className="p-3 bg-slate-950 border border-slate-800 rounded-lg">
              <div className="text-slate-400">Network Policy</div>
              <div className="text-emerald-400 font-semibold mt-0.5">Zero Cloud / No API Key</div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};
