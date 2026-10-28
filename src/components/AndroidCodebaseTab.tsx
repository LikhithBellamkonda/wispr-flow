import React, { useState } from 'react';
import { FileCode, Copy, Check, Download, FolderGit2 } from 'lucide-react';
import { ANDROID_SOURCE_FILES } from '../data/androidCodeFiles';
import { AndroidSourceFile } from '../types';

interface AndroidCodebaseTabProps {
  initialFilePath?: string;
}

export const AndroidCodebaseTab: React.FC<AndroidCodebaseTabProps> = ({ initialFilePath }) => {
  const [selectedFileId, setSelectedFileId] = useState<string>(() => {
    if (initialFilePath) {
      const found = ANDROID_SOURCE_FILES.find((f) => f.path === initialFilePath);
      if (found) return found.id;
    }
    return ANDROID_SOURCE_FILES[0].id;
  });
  const [copied, setCopied] = useState(false);

  const activeFile: AndroidSourceFile =
    ANDROID_SOURCE_FILES.find((f) => f.id === selectedFileId) || ANDROID_SOURCE_FILES[0];

  const handleCopyCode = () => {
    navigator.clipboard.writeText(activeFile.code);
    setCopied(true);
    setTimeout(() => setCopied(false), 1800);
  };

  const handleDownloadFile = (file: AndroidSourceFile) => {
    const blob = new Blob([file.code], { type: 'text/plain;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = file.name;
    a.click();
    URL.revokeObjectURL(url);
  };

  return (
    <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
      {/* File Tree Sidebar */}
      <div className="lg:col-span-4 bg-slate-900 border border-slate-800 rounded-xl p-4 flex flex-col gap-3">
        <div className="flex items-center gap-2 border-b border-slate-800 pb-3">
          <FolderGit2 className="w-4 h-4 text-indigo-400" />
          <h2 className="text-sm font-semibold text-slate-100">Android Project Source Tree</h2>
        </div>

        <div className="flex flex-col gap-1.5">
          {ANDROID_SOURCE_FILES.map((file) => {
            const isSelected = file.id === activeFile.id;
            return (
              <button
                key={file.id}
                onClick={() => setSelectedFileId(file.id)}
                className={`w-full text-left p-3 rounded-lg border transition-colors flex flex-col gap-1 ${
                  isSelected
                    ? 'bg-indigo-950/60 border-indigo-500/60 text-slate-100'
                    : 'bg-slate-950 border-slate-800/80 text-slate-400 hover:text-slate-200'
                }`}
              >
                <div className="flex items-center justify-between gap-2">
                  <span className="font-mono text-xs font-semibold flex items-center gap-1.5 truncate">
                    <FileCode className="w-3.5 h-3.5 text-indigo-400 shrink-0" />
                    {file.name}
                  </span>
                  <span className="text-[11px] font-mono text-slate-400 shrink-0">
                    {file.featureRefs.join(' · ')}
                  </span>
                </div>
                <span className="text-[11px] font-mono text-slate-500 truncate">{file.path}</span>
              </button>
            );
          })}
        </div>
      </div>

      {/* Code Viewer */}
      <div className="lg:col-span-8 bg-slate-900 border border-slate-800 rounded-xl p-5 flex flex-col gap-4">
        <div className="flex flex-wrap items-start justify-between gap-4 border-b border-slate-800 pb-4">
          <div>
            <div className="flex items-center gap-2 text-xs font-mono text-indigo-400">
              <span>{activeFile.phase}</span>
              <span>·</span>
              <span>Requirements: {activeFile.featureRefs.join(', ')}</span>
            </div>
            <h3 className="text-base font-semibold text-slate-100 font-mono mt-1">
              {activeFile.path}
            </h3>
            <p className="text-xs text-slate-400 mt-1 leading-relaxed">{activeFile.description}</p>
          </div>

          <div className="flex items-center gap-2 shrink-0">
            <button
              onClick={handleCopyCode}
              className="flex items-center gap-1.5 px-3 py-1.5 bg-slate-800 hover:bg-slate-700 text-slate-200 rounded-lg text-xs font-medium transition-colors whitespace-nowrap shrink-0"
            >
              {copied ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
              <span>{copied ? 'Copied' : 'Copy Source'}</span>
            </button>
            <button
              onClick={() => handleDownloadFile(activeFile)}
              className="flex items-center gap-1.5 px-3 py-1.5 bg-indigo-600 hover:bg-indigo-500 text-white rounded-lg text-xs font-medium transition-colors whitespace-nowrap shrink-0"
            >
              <Download className="w-3.5 h-3.5" />
              <span>Download {activeFile.name}</span>
            </button>
          </div>
        </div>

        <pre className="bg-slate-950 border border-slate-800 rounded-xl p-4 text-xs font-mono text-slate-200 overflow-x-auto leading-relaxed max-h-[620px]">
          <code>{activeFile.code}</code>
        </pre>
      </div>
    </div>
  );
};
