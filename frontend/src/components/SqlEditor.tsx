import { Suspense, lazy } from 'react';
import type { OnMount } from '@monaco-editor/react';

// Monaco Editor is lazy-loaded to keep the initial bundle small.
// It is only fetched when this component is first rendered (i.e., on step 4 of the wizard).
const Editor = lazy(() => import('@monaco-editor/react'));

interface SqlEditorProps {
  value: string;
  onChange: (value: string) => void;
  height?: string;
  readOnly?: boolean;
}

export default function SqlEditor({
  value,
  onChange,
  height = '300px',
  readOnly = false,
}: SqlEditorProps) {
  const handleMount: OnMount = (editor) => {
    // Disable browser context menu to avoid monaco-generated inline event handlers
    editor.updateOptions({ contextmenu: false });
  };

  return (
    <div className="border border-gray-700 rounded overflow-hidden">
      <Suspense
        fallback={
          <div
            className="flex items-center justify-center bg-gray-900 text-gray-400 text-sm"
            style={{ height }}
          >
            Loading editor…
          </div>
        }
      >
        <Editor
          height={height}
          language="sql"
          theme="vs-dark"
          value={value}
          onMount={handleMount}
          onChange={(val) => onChange(val ?? '')}
          options={{
            minimap: { enabled: false },
            fontSize: 14,
            wordWrap: 'on',
            readOnly,
            scrollBeyondLastLine: false,
          }}
        />
      </Suspense>
    </div>
  );
}
