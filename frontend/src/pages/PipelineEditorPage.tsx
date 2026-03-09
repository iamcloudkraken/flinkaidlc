import React, { lazy, Suspense, useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  createPipeline,
  updatePipeline,
  getPipeline,
  type CreatePipelineRequest,
  type KafkaSource,
  type KafkaSink,
  type UpgradeMode,
  type StartupMode,
} from '../api/pipelines';

// Lazy-load Monaco editor to avoid blocking initial render
const SqlEditor = lazy(() => import('../components/SqlEditor'));

const STEPS = ['Basic Info', 'Sources', 'Sinks', 'SQL Query', 'Review & Submit'];

const defaultSource: KafkaSource = {
  tableName: '',
  topic: '',
  bootstrapServers: 'kafka:9092',
  consumerGroup: '',
  startupMode: 'GROUP_OFFSETS',
  schemaRegistryUrl: '',
  avroSubject: '',
  watermarkDelayMs: 5000,
};

const defaultSink: KafkaSink = {
  tableName: '',
  topic: '',
  bootstrapServers: 'kafka:9092',
  schemaRegistryUrl: '',
  avroSubject: '',
};

interface FormState {
  name: string;
  description: string;
  parallelism: number;
  checkpointIntervalMs: number;
  upgradeMode: UpgradeMode;
  sources: KafkaSource[];
  sinks: KafkaSink[];
  sqlQuery: string;
}

const initialForm: FormState = {
  name: '',
  description: '',
  parallelism: 2,
  checkpointIntervalMs: 30000,
  upgradeMode: 'SAVEPOINT',
  sources: [{ ...defaultSource }],
  sinks: [{ ...defaultSink }],
  sqlQuery: '-- Write your Flink SQL query here\nINSERT INTO output\nSELECT *\nFROM input',
};

export default function PipelineEditorPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const isEditing = Boolean(id);

  const [step, setStep] = useState(0);
  const [form, setForm] = useState<FormState>(initialForm);
  const [loading, setLoading] = useState(isEditing);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!isEditing || !id) return;
    getPipeline(id)
      .then((pipeline) => {
        setForm({
          name: pipeline.name,
          description: pipeline.description ?? '',
          parallelism: pipeline.parallelism,
          checkpointIntervalMs: pipeline.checkpointIntervalMs,
          upgradeMode: pipeline.upgradeMode,
          sources: pipeline.sources,
          sinks: pipeline.sinks,
          sqlQuery: pipeline.sqlQuery,
        });
      })
      .catch(() => setError('Failed to load pipeline for editing.'))
      .finally(() => setLoading(false));
  }, [id, isEditing]);

  const updateField = <K extends keyof FormState>(field: K, value: FormState[K]) => {
    setForm((prev) => ({ ...prev, [field]: value }));
  };

  const updateSource = (idx: number, updates: Partial<KafkaSource>) => {
    setForm((prev) => ({
      ...prev,
      sources: prev.sources.map((s, i) => (i === idx ? { ...s, ...updates } : s)),
    }));
  };

  const updateSink = (idx: number, updates: Partial<KafkaSink>) => {
    setForm((prev) => ({
      ...prev,
      sinks: prev.sinks.map((s, i) => (i === idx ? { ...s, ...updates } : s)),
    }));
  };

  const addSource = () => setForm((prev) => ({ ...prev, sources: [...prev.sources, { ...defaultSource }] }));
  const removeSource = (idx: number) =>
    setForm((prev) => ({ ...prev, sources: prev.sources.filter((_, i) => i !== idx) }));

  const addSink = () => setForm((prev) => ({ ...prev, sinks: [...prev.sinks, { ...defaultSink }] }));
  const removeSink = (idx: number) =>
    setForm((prev) => ({ ...prev, sinks: prev.sinks.filter((_, i) => i !== idx) }));

  const handleSubmit = async () => {
    setSubmitting(true);
    setError(null);
    const payload: CreatePipelineRequest = {
      name: form.name,
      description: form.description || undefined,
      parallelism: form.parallelism,
      checkpointIntervalMs: form.checkpointIntervalMs,
      upgradeMode: form.upgradeMode,
      sqlQuery: form.sqlQuery,
      sources: form.sources,
      sinks: form.sinks,
    };

    try {
      if (isEditing && id) {
        await updatePipeline(id, payload);
        navigate(`/pipelines/${id}`);
      } else {
        const pipeline = await createPipeline(payload);
        navigate(`/pipelines/${pipeline.id}`);
      }
    } catch (err: unknown) {
      const detail = (err as { response?: { data?: { detail?: string } } })?.response?.data?.detail;
      setError(detail ?? 'Failed to save pipeline. Please check your inputs.');
      setSubmitting(false);
    }
  };

  if (loading) {
    return <div className="text-center py-8 text-gray-500">Loading...</div>;
  }

  return (
    <div className="max-w-3xl mx-auto">
      <h1 className="text-2xl font-bold text-gray-900 mb-6">
        {isEditing ? 'Edit Pipeline' : 'New Pipeline'}
      </h1>

      {/* Step indicator */}
      <div className="flex items-center mb-8">
        {STEPS.map((label, idx) => (
          <React.Fragment key={label}>
            <button
              onClick={() => setStep(idx)}
              className={`flex items-center gap-2 text-sm font-medium ${
                idx === step ? 'text-blue-600' : idx < step ? 'text-green-600' : 'text-gray-400'
              }`}
            >
              <span
                className={`w-7 h-7 rounded-full flex items-center justify-center text-xs border-2 ${
                  idx === step
                    ? 'border-blue-600 text-blue-600'
                    : idx < step
                    ? 'border-green-600 bg-green-600 text-white'
                    : 'border-gray-300 text-gray-400'
                }`}
              >
                {idx < step ? '✓' : idx + 1}
              </span>
              <span className="hidden sm:inline">{label}</span>
            </button>
            {idx < STEPS.length - 1 && (
              <div className={`flex-1 h-0.5 mx-2 ${idx < step ? 'bg-green-400' : 'bg-gray-200'}`} />
            )}
          </React.Fragment>
        ))}
      </div>

      {error && (
        <div className="mb-4 bg-red-50 border border-red-200 rounded p-3 text-red-700 text-sm">{error}</div>
      )}

      {/* Step 0: Basic Info */}
      {step === 0 && (
        <div className="space-y-4">
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Name *</label>
            <input
              type="text"
              value={form.name}
              onChange={(e) => updateField('name', e.target.value)}
              className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
              placeholder="My Pipeline"
            />
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Description</label>
            <textarea
              value={form.description}
              onChange={(e) => updateField('description', e.target.value)}
              className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
              rows={3}
              placeholder="Optional description"
            />
          </div>
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Parallelism *</label>
              <input
                type="number"
                min={1}
                max={256}
                value={form.parallelism}
                onChange={(e) => updateField('parallelism', Number(e.target.value))}
                className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Checkpoint Interval (ms)</label>
              <input
                type="number"
                min={1000}
                value={form.checkpointIntervalMs}
                onChange={(e) => updateField('checkpointIntervalMs', Number(e.target.value))}
                className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
              />
            </div>
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Upgrade Mode</label>
            <select
              value={form.upgradeMode}
              onChange={(e) => updateField('upgradeMode', e.target.value as UpgradeMode)}
              className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
            >
              <option value="SAVEPOINT">SAVEPOINT</option>
              <option value="LAST_STATE">LAST_STATE</option>
              <option value="STATELESS">STATELESS</option>
            </select>
          </div>
        </div>
      )}

      {/* Step 1: Sources */}
      {step === 1 && (
        <div className="space-y-4">
          {form.sources.map((source, idx) => (
            <div key={idx} className="border border-gray-200 rounded-lg p-4 space-y-3">
              <div className="flex justify-between items-center">
                <h3 className="font-medium text-gray-900">Source {idx + 1}</h3>
                {form.sources.length > 1 && (
                  <button onClick={() => removeSource(idx)} className="text-red-600 text-sm hover:text-red-800">
                    Remove
                  </button>
                )}
              </div>
              <div className="grid grid-cols-2 gap-3">
                {(['tableName', 'topic', 'bootstrapServers', 'consumerGroup', 'schemaRegistryUrl', 'avroSubject'] as const).map((field) => (
                  <div key={field}>
                    <label className="block text-xs text-gray-600 mb-1">{field}</label>
                    <input
                      type="text"
                      value={String(source[field] ?? '')}
                      onChange={(e) => updateSource(idx, { [field]: e.target.value })}
                      className="w-full border border-gray-300 rounded px-2 py-1 text-sm focus:outline-none focus:ring-1 focus:ring-blue-500"
                    />
                  </div>
                ))}
                <div>
                  <label className="block text-xs text-gray-600 mb-1">startupMode</label>
                  <select
                    value={source.startupMode}
                    onChange={(e) => updateSource(idx, { startupMode: e.target.value as StartupMode })}
                    className="w-full border border-gray-300 rounded px-2 py-1 text-sm"
                  >
                    <option value="GROUP_OFFSETS">GROUP_OFFSETS</option>
                    <option value="EARLIEST">EARLIEST</option>
                    <option value="LATEST">LATEST</option>
                  </select>
                </div>
              </div>
            </div>
          ))}
          <button
            onClick={addSource}
            className="w-full py-2 border-2 border-dashed border-gray-300 rounded-lg text-sm text-gray-500 hover:border-blue-400 hover:text-blue-600"
          >
            + Add Source
          </button>
        </div>
      )}

      {/* Step 2: Sinks */}
      {step === 2 && (
        <div className="space-y-4">
          {form.sinks.map((sink, idx) => (
            <div key={idx} className="border border-gray-200 rounded-lg p-4 space-y-3">
              <div className="flex justify-between items-center">
                <h3 className="font-medium text-gray-900">Sink {idx + 1}</h3>
                {form.sinks.length > 1 && (
                  <button onClick={() => removeSink(idx)} className="text-red-600 text-sm hover:text-red-800">
                    Remove
                  </button>
                )}
              </div>
              <div className="grid grid-cols-2 gap-3">
                {(['tableName', 'topic', 'bootstrapServers', 'schemaRegistryUrl', 'avroSubject'] as const).map((field) => (
                  <div key={field}>
                    <label className="block text-xs text-gray-600 mb-1">{field}</label>
                    <input
                      type="text"
                      value={String(sink[field] ?? '')}
                      onChange={(e) => updateSink(idx, { [field]: e.target.value })}
                      className="w-full border border-gray-300 rounded px-2 py-1 text-sm focus:outline-none focus:ring-1 focus:ring-blue-500"
                    />
                  </div>
                ))}
              </div>
            </div>
          ))}
          <button
            onClick={addSink}
            className="w-full py-2 border-2 border-dashed border-gray-300 rounded-lg text-sm text-gray-500 hover:border-blue-400 hover:text-blue-600"
          >
            + Add Sink
          </button>
        </div>
      )}

      {/* Step 3: SQL Query */}
      {step === 3 && (
        <div>
          <label className="block text-sm font-medium text-gray-700 mb-2">SQL Query *</label>
          <p className="text-xs text-gray-500 mb-3">
            Write your Flink SQL INSERT statement. DDL statements (CREATE, DROP, ALTER) are not permitted here —
            sources and sinks are generated automatically from your configuration above.
          </p>
          <Suspense fallback={<div className="h-64 bg-gray-100 rounded flex items-center justify-center text-gray-400">Loading editor...</div>}>
            <SqlEditor
              value={form.sqlQuery}
              onChange={(val) => updateField('sqlQuery', val ?? '')}
              height="350px"
            />
          </Suspense>
        </div>
      )}

      {/* Step 4: Review */}
      {step === 4 && (
        <div className="space-y-4">
          <div className="bg-gray-50 rounded-lg p-4 space-y-2 text-sm">
            <div><span className="font-medium">Name:</span> {form.name}</div>
            <div><span className="font-medium">Parallelism:</span> {form.parallelism}</div>
            <div><span className="font-medium">Checkpoint Interval:</span> {form.checkpointIntervalMs} ms</div>
            <div><span className="font-medium">Upgrade Mode:</span> {form.upgradeMode}</div>
            <div><span className="font-medium">Sources:</span> {form.sources.length}</div>
            <div><span className="font-medium">Sinks:</span> {form.sinks.length}</div>
          </div>
          <div>
            <p className="text-sm font-medium text-gray-700 mb-1">SQL Preview:</p>
            <pre className="bg-gray-900 text-green-400 p-3 rounded text-xs overflow-x-auto whitespace-pre-wrap">
              {form.sqlQuery}
            </pre>
          </div>
        </div>
      )}

      {/* Navigation */}
      <div className="flex justify-between mt-8">
        <button
          onClick={() => setStep((s) => Math.max(0, s - 1))}
          disabled={step === 0}
          className="px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-md hover:bg-gray-50 disabled:opacity-40"
        >
          Back
        </button>
        {step < STEPS.length - 1 ? (
          <button
            onClick={() => setStep((s) => Math.min(STEPS.length - 1, s + 1))}
            className="px-4 py-2 text-sm font-medium text-white bg-blue-600 rounded-md hover:bg-blue-700"
          >
            Next
          </button>
        ) : (
          <button
            onClick={handleSubmit}
            disabled={submitting}
            className="px-6 py-2 text-sm font-medium text-white bg-blue-600 rounded-md hover:bg-blue-700 disabled:opacity-50"
          >
            {submitting ? 'Saving...' : isEditing ? 'Update Pipeline' : 'Create Pipeline'}
          </button>
        )}
      </div>
    </div>
  );
}
