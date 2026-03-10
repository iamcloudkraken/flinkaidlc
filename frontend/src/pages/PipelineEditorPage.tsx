import React, { lazy, Suspense, useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  createPipeline,
  updatePipeline,
  getPipeline,
  type CreatePipelineRequest,
  type PipelineSourceRequest,
  type PipelineSinkRequest,
  type UpgradeMode,
  type StartupMode,
  type S3AuthType,
  type ColumnDefinition,
} from '../api/pipelines';

// Lazy-load Monaco editor to avoid blocking initial render
const SqlEditor = lazy(() => import('../components/SqlEditor'));

const STEPS = ['Basic Info', 'Sources', 'Sinks', 'SQL Query', 'Review & Submit'];

const SQL_TYPES = ['STRING', 'BIGINT', 'INT', 'DOUBLE', 'FLOAT', 'BOOLEAN', 'TIMESTAMP(3)', 'DATE'];

type KafkaSourceForm = {
  type: 'KAFKA';
  tableName: string;
  topic: string;
  bootstrapServers: string;
  consumerGroup: string;
  startupMode: StartupMode;
  schemaRegistryUrl: string;
  avroSubject: string;
  watermarkDelayMs: number;
};

type S3SourceForm = {
  type: 'S3';
  tableName: string;
  bucket: string;
  prefix: string;
  partitioned: boolean;
  authType: S3AuthType;
  accessKey: string;
  secretKey: string;
  columns: ColumnDefinition[];
};

type SourceForm = KafkaSourceForm | S3SourceForm;

type KafkaSinkForm = {
  type: 'KAFKA';
  tableName: string;
  topic: string;
  bootstrapServers: string;
  schemaRegistryUrl: string;
  avroSubject: string;
};

type S3SinkForm = {
  type: 'S3';
  tableName: string;
  bucket: string;
  prefix: string;
  partitioned: boolean;
  authType: S3AuthType;
  accessKey: string;
  secretKey: string;
  columns: ColumnDefinition[];
  s3PartitionColumns: string[];
};

type SinkForm = KafkaSinkForm | S3SinkForm;

const defaultKafkaSource: KafkaSourceForm = {
  type: 'KAFKA',
  tableName: '',
  topic: '',
  bootstrapServers: 'kafka:9092',
  consumerGroup: '',
  startupMode: 'GROUP_OFFSETS',
  schemaRegistryUrl: '',
  avroSubject: '',
  watermarkDelayMs: 5000,
};

const defaultS3Source: S3SourceForm = {
  type: 'S3',
  tableName: '',
  bucket: '',
  prefix: '',
  partitioned: false,
  authType: 'IAM_ROLE',
  accessKey: '',
  secretKey: '',
  columns: [],
};

const defaultKafkaSink: KafkaSinkForm = {
  type: 'KAFKA',
  tableName: '',
  topic: '',
  bootstrapServers: 'kafka:9092',
  schemaRegistryUrl: '',
  avroSubject: '',
};

const defaultS3Sink: S3SinkForm = {
  type: 'S3',
  tableName: '',
  bucket: '',
  prefix: '',
  partitioned: false,
  authType: 'IAM_ROLE',
  accessKey: '',
  secretKey: '',
  columns: [],
  s3PartitionColumns: [],
};

interface FormState {
  name: string;
  description: string;
  parallelism: number;
  checkpointIntervalMs: number;
  upgradeMode: UpgradeMode;
  sources: SourceForm[];
  sinks: SinkForm[];
  sqlQuery: string;
}

const initialForm: FormState = {
  name: '',
  description: '',
  parallelism: 2,
  checkpointIntervalMs: 30000,
  upgradeMode: 'SAVEPOINT',
  sources: [{ ...defaultKafkaSource }],
  sinks: [{ ...defaultKafkaSink }],
  sqlQuery: '-- Write your Flink SQL query here\nINSERT INTO output\nSELECT *\nFROM input',
};

function toSourceRequest(s: SourceForm): PipelineSourceRequest {
  if (s.type === 'S3') {
    return {
      type: 'S3',
      tableName: s.tableName,
      bucket: s.bucket,
      prefix: s.prefix,
      partitioned: s.partitioned,
      authType: s.authType,
      accessKey: s.accessKey || undefined,
      secretKey: s.secretKey || undefined,
      columns: s.columns,
    };
  }
  return {
    type: 'KAFKA',
    tableName: s.tableName,
    topic: s.topic,
    bootstrapServers: s.bootstrapServers,
    consumerGroup: s.consumerGroup,
    startupMode: s.startupMode,
    schemaRegistryUrl: s.schemaRegistryUrl || undefined,
    avroSubject: s.avroSubject || undefined,
    watermarkDelayMs: s.watermarkDelayMs,
  };
}

function toSinkRequest(s: SinkForm): PipelineSinkRequest {
  if (s.type === 'S3') {
    return {
      type: 'S3',
      tableName: s.tableName,
      bucket: s.bucket,
      prefix: s.prefix,
      partitioned: s.partitioned,
      authType: s.authType,
      accessKey: s.accessKey || undefined,
      secretKey: s.secretKey || undefined,
      columns: s.columns,
      s3PartitionColumns: s.s3PartitionColumns,
    };
  }
  return {
    type: 'KAFKA',
    tableName: s.tableName,
    topic: s.topic,
    bootstrapServers: s.bootstrapServers,
    schemaRegistryUrl: s.schemaRegistryUrl || undefined,
    avroSubject: s.avroSubject || undefined,
  };
}

function fromApiSource(src: any): SourceForm {
  if (src.sourceType === 'S3') {
    return {
      type: 'S3',
      tableName: src.tableName ?? '',
      bucket: src.bucket ?? '',
      prefix: src.prefix ?? '',
      partitioned: src.partitioned ?? false,
      authType: src.authType ?? 'IAM_ROLE',
      accessKey: src.accessKey ?? '',
      secretKey: '',
      columns: src.columns ?? [],
    };
  }
  return {
    type: 'KAFKA',
    tableName: src.tableName ?? '',
    topic: src.topic ?? '',
    bootstrapServers: src.bootstrapServers ?? 'kafka:9092',
    consumerGroup: src.consumerGroup ?? '',
    startupMode: src.startupMode ?? 'GROUP_OFFSETS',
    schemaRegistryUrl: src.schemaRegistryUrl ?? '',
    avroSubject: src.avroSubject ?? '',
    watermarkDelayMs: src.watermarkDelayMs ?? 5000,
  };
}

function fromApiSink(snk: any): SinkForm {
  if (snk.sinkType === 'S3') {
    return {
      type: 'S3',
      tableName: snk.tableName ?? '',
      bucket: snk.bucket ?? '',
      prefix: snk.prefix ?? '',
      partitioned: snk.partitioned ?? false,
      authType: snk.authType ?? 'IAM_ROLE',
      accessKey: snk.accessKey ?? '',
      secretKey: '',
      columns: snk.columns ?? [],
      s3PartitionColumns: snk.s3PartitionColumns ?? [],
    };
  }
  return {
    type: 'KAFKA',
    tableName: snk.tableName ?? '',
    topic: snk.topic ?? '',
    bootstrapServers: snk.bootstrapServers ?? 'kafka:9092',
    schemaRegistryUrl: snk.schemaRegistryUrl ?? '',
    avroSubject: snk.avroSubject ?? '',
  };
}

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
          sources: pipeline.sources.map(fromApiSource),
          sinks: pipeline.sinks.map(fromApiSink),
          sqlQuery: pipeline.sqlQuery,
        });
      })
      .catch(() => setError('Failed to load pipeline for editing.'))
      .finally(() => setLoading(false));
  }, [id, isEditing]);

  const updateField = <K extends keyof FormState>(field: K, value: FormState[K]) => {
    setForm((prev) => ({ ...prev, [field]: value }));
  };

  const updateSource = (idx: number, updates: Partial<SourceForm>) => {
    setForm((prev) => ({
      ...prev,
      sources: prev.sources.map((s, i) => (i === idx ? { ...s, ...updates } as SourceForm : s)),
    }));
  };

  const updateSink = (idx: number, updates: Partial<SinkForm>) => {
    setForm((prev) => ({
      ...prev,
      sinks: prev.sinks.map((s, i) => (i === idx ? { ...s, ...updates } as SinkForm : s)),
    }));
  };

  const updateSourceType = (idx: number, newType: 'KAFKA' | 'S3') => {
    const tableName = form.sources[idx].tableName;
    if (newType === 'KAFKA') {
      setForm((prev) => ({
        ...prev,
        sources: prev.sources.map((src, i) => i === idx ? { ...defaultKafkaSource, tableName } : src),
      }));
    } else {
      setForm((prev) => ({
        ...prev,
        sources: prev.sources.map((src, i) => i === idx ? { ...defaultS3Source, tableName } : src),
      }));
    }
  };

  const updateSinkType = (idx: number, newType: 'KAFKA' | 'S3') => {
    const tableName = form.sinks[idx].tableName;
    if (newType === 'KAFKA') {
      setForm((prev) => ({
        ...prev,
        sinks: prev.sinks.map((snk, i) => i === idx ? { ...defaultKafkaSink, tableName } : snk),
      }));
    } else {
      setForm((prev) => ({
        ...prev,
        sinks: prev.sinks.map((snk, i) => i === idx ? { ...defaultS3Sink, tableName } : snk),
      }));
    }
  };

  const addSource = () => setForm((prev) => ({ ...prev, sources: [...prev.sources, { ...defaultKafkaSource }] }));
  const removeSource = (idx: number) =>
    setForm((prev) => ({ ...prev, sources: prev.sources.filter((_, i) => i !== idx) }));

  const addSink = () => setForm((prev) => ({ ...prev, sinks: [...prev.sinks, { ...defaultKafkaSink }] }));
  const removeSink = (idx: number) =>
    setForm((prev) => ({ ...prev, sinks: prev.sinks.filter((_, i) => i !== idx) }));

  const addSourceColumn = (idx: number) => {
    const src = form.sources[idx];
    if (src.type !== 'S3') return;
    updateSource(idx, { columns: [...src.columns, { name: '', type: 'STRING' }] } as Partial<S3SourceForm>);
  };

  const updateSourceColumn = (srcIdx: number, colIdx: number, field: keyof ColumnDefinition, value: string) => {
    const src = form.sources[srcIdx];
    if (src.type !== 'S3') return;
    const newCols = src.columns.map((c, i) => i === colIdx ? { ...c, [field]: value } : c);
    updateSource(srcIdx, { columns: newCols } as Partial<S3SourceForm>);
  };

  const removeSourceColumn = (srcIdx: number, colIdx: number) => {
    const src = form.sources[srcIdx];
    if (src.type !== 'S3') return;
    updateSource(srcIdx, { columns: src.columns.filter((_, i) => i !== colIdx) } as Partial<S3SourceForm>);
  };

  const addSinkColumn = (idx: number) => {
    const snk = form.sinks[idx];
    if (snk.type !== 'S3') return;
    updateSink(idx, { columns: [...snk.columns, { name: '', type: 'STRING' }] } as Partial<S3SinkForm>);
  };

  const updateSinkColumn = (sinkIdx: number, colIdx: number, field: keyof ColumnDefinition, value: string) => {
    const snk = form.sinks[sinkIdx];
    if (snk.type !== 'S3') return;
    const newCols = snk.columns.map((c, i) => i === colIdx ? { ...c, [field]: value } : c);
    updateSink(sinkIdx, { columns: newCols } as Partial<S3SinkForm>);
  };

  const removeSinkColumn = (sinkIdx: number, colIdx: number) => {
    const snk = form.sinks[sinkIdx];
    if (snk.type !== 'S3') return;
    updateSink(sinkIdx, { columns: snk.columns.filter((_, i) => i !== colIdx) } as Partial<S3SinkForm>);
  };

  const validateStep = (s: number): string | null => {
    if (s === 1) {
      for (const [idx, src] of form.sources.entries()) {
        if (src.type === 'S3') {
          if (!src.bucket.trim()) return `Source ${idx + 1}: S3 bucket is required.`;
          if (!src.prefix.trim()) return `Source ${idx + 1}: S3 prefix is required.`;
          if (src.columns.length === 0) return `Source ${idx + 1}: at least one column is required.`;
          if (src.authType === 'ACCESS_KEY') {
            if (!src.accessKey.trim()) return `Source ${idx + 1}: Access Key ID is required.`;
            if (!src.secretKey.trim()) return `Source ${idx + 1}: Secret Access Key is required.`;
          }
        }
      }
    }
    if (s === 2) {
      for (const [idx, snk] of form.sinks.entries()) {
        if (snk.type === 'S3') {
          if (!snk.bucket.trim()) return `Sink ${idx + 1}: S3 bucket is required.`;
          if (!snk.prefix.trim()) return `Sink ${idx + 1}: S3 prefix is required.`;
          if (snk.columns.length === 0) return `Sink ${idx + 1}: at least one column is required.`;
          if (snk.authType === 'ACCESS_KEY') {
            if (!snk.accessKey.trim()) return `Sink ${idx + 1}: Access Key ID is required.`;
            if (!snk.secretKey.trim()) return `Sink ${idx + 1}: Secret Access Key is required.`;
          }
        }
      }
    }
    return null;
  };

  const handleNext = () => {
    const validationError = validateStep(step);
    if (validationError) {
      setError(validationError);
      return;
    }
    setError(null);
    setStep((s) => Math.min(STEPS.length - 1, s + 1));
  };

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
      sources: form.sources.map(toSourceRequest),
      sinks: form.sinks.map(toSinkRequest),
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

              {/* Type toggle */}
              <div className="flex gap-4 mb-4">
                <label className="flex items-center gap-2 cursor-pointer">
                  <input
                    type="radio"
                    name={`source-type-${idx}`}
                    value="KAFKA"
                    checked={source.type === 'KAFKA'}
                    onChange={() => updateSourceType(idx, 'KAFKA')}
                  />
                  <span className="text-sm">Kafka (Avro)</span>
                </label>
                <label className="flex items-center gap-2 cursor-pointer">
                  <input
                    type="radio"
                    name={`source-type-${idx}`}
                    value="S3"
                    checked={source.type === 'S3'}
                    onChange={() => updateSourceType(idx, 'S3')}
                  />
                  <span className="text-sm">S3 (Parquet)</span>
                </label>
              </div>

              {/* Table name — always shown */}
              <div>
                <label className="block text-xs text-gray-600 mb-1">tableName</label>
                <input
                  type="text"
                  value={source.tableName}
                  onChange={(e) => updateSource(idx, { tableName: e.target.value })}
                  className="w-full border border-gray-300 rounded px-2 py-1 text-sm focus:outline-none focus:ring-1 focus:ring-blue-500"
                />
              </div>

              {source.type === 'KAFKA' && (
                <div className="grid grid-cols-2 gap-3">
                  {(['topic', 'bootstrapServers', 'consumerGroup', 'schemaRegistryUrl', 'avroSubject'] as const).map((field) => (
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
              )}

              {source.type === 'S3' && (
                <div className="space-y-3">
                  <div className="grid grid-cols-2 gap-3">
                    <div>
                      <label className="block text-xs text-gray-600 mb-1">S3 Bucket *</label>
                      <input
                        type="text"
                        value={source.bucket}
                        onChange={(e) => updateSource(idx, { bucket: e.target.value } as Partial<S3SourceForm>)}
                        className="w-full border border-gray-300 rounded px-2 py-1 text-sm focus:outline-none focus:ring-1 focus:ring-blue-500"
                        placeholder="my-bucket"
                      />
                    </div>
                    <div>
                      <label className="block text-xs text-gray-600 mb-1">S3 Prefix *</label>
                      <input
                        type="text"
                        value={source.prefix}
                        onChange={(e) => updateSource(idx, { prefix: e.target.value } as Partial<S3SourceForm>)}
                        className="w-full border border-gray-300 rounded px-2 py-1 text-sm focus:outline-none focus:ring-1 focus:ring-blue-500"
                        placeholder="data/events"
                      />
                    </div>
                  </div>

                  <label className="flex items-center gap-2 cursor-pointer text-sm">
                    <input
                      type="checkbox"
                      checked={source.partitioned}
                      onChange={(e) => updateSource(idx, { partitioned: e.target.checked } as Partial<S3SourceForm>)}
                    />
                    Hive-style partitioned paths
                  </label>

                  <div>
                    <label className="block text-xs text-gray-600 mb-1">Authentication</label>
                    <div className="flex gap-4">
                      <label className="flex items-center gap-2 cursor-pointer text-sm">
                        <input
                          type="radio"
                          name={`source-auth-${idx}`}
                          value="IAM_ROLE"
                          checked={source.authType === 'IAM_ROLE'}
                          onChange={() => updateSource(idx, { authType: 'IAM_ROLE' } as Partial<S3SourceForm>)}
                        />
                        IAM Role
                      </label>
                      <label className="flex items-center gap-2 cursor-pointer text-sm">
                        <input
                          type="radio"
                          name={`source-auth-${idx}`}
                          value="ACCESS_KEY"
                          checked={source.authType === 'ACCESS_KEY'}
                          onChange={() => updateSource(idx, { authType: 'ACCESS_KEY' } as Partial<S3SourceForm>)}
                        />
                        Access Key
                      </label>
                    </div>
                  </div>

                  {source.authType === 'ACCESS_KEY' && (
                    <div className="grid grid-cols-2 gap-3">
                      <div>
                        <label className="block text-xs text-gray-600 mb-1">Access Key ID *</label>
                        <input
                          type="text"
                          value={source.accessKey}
                          onChange={(e) => updateSource(idx, { accessKey: e.target.value } as Partial<S3SourceForm>)}
                          className="w-full border border-gray-300 rounded px-2 py-1 text-sm focus:outline-none focus:ring-1 focus:ring-blue-500"
                        />
                      </div>
                      <div>
                        <label className="block text-xs text-gray-600 mb-1">Secret Access Key *</label>
                        <input
                          type="password"
                          value={source.secretKey}
                          onChange={(e) => updateSource(idx, { secretKey: e.target.value } as Partial<S3SourceForm>)}
                          className="w-full border border-gray-300 rounded px-2 py-1 text-sm focus:outline-none focus:ring-1 focus:ring-blue-500"
                        />
                      </div>
                    </div>
                  )}

                  {/* Columns editor */}
                  <div>
                    <label className="block text-xs text-gray-600 mb-2">Columns *</label>
                    {source.columns.length > 0 && (
                      <table className="w-full text-sm mb-2 border border-gray-200 rounded">
                        <thead>
                          <tr className="bg-gray-50">
                            <th className="text-left px-2 py-1 text-xs text-gray-600">Column Name</th>
                            <th className="text-left px-2 py-1 text-xs text-gray-600">SQL Type</th>
                            <th className="px-2 py-1"></th>
                          </tr>
                        </thead>
                        <tbody>
                          {source.columns.map((col, colIdx) => (
                            <tr key={colIdx} className="border-t border-gray-100">
                              <td className="px-2 py-1">
                                <input
                                  type="text"
                                  value={col.name}
                                  onChange={(e) => updateSourceColumn(idx, colIdx, 'name', e.target.value)}
                                  className="w-full border border-gray-300 rounded px-1 py-0.5 text-xs focus:outline-none focus:ring-1 focus:ring-blue-500"
                                  placeholder="column_name"
                                />
                              </td>
                              <td className="px-2 py-1">
                                <select
                                  value={col.type}
                                  onChange={(e) => updateSourceColumn(idx, colIdx, 'type', e.target.value)}
                                  className="w-full border border-gray-300 rounded px-1 py-0.5 text-xs"
                                >
                                  {SQL_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
                                </select>
                              </td>
                              <td className="px-2 py-1">
                                <button
                                  onClick={() => removeSourceColumn(idx, colIdx)}
                                  className="text-red-500 text-xs hover:text-red-700"
                                >
                                  Remove
                                </button>
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    )}
                    <button
                      onClick={() => addSourceColumn(idx)}
                      className="text-sm text-blue-600 hover:text-blue-800"
                    >
                      + Add Column
                    </button>
                  </div>
                </div>
              )}
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

              {/* Type toggle */}
              <div className="flex gap-4 mb-4">
                <label className="flex items-center gap-2 cursor-pointer">
                  <input
                    type="radio"
                    name={`sink-type-${idx}`}
                    value="KAFKA"
                    checked={sink.type === 'KAFKA'}
                    onChange={() => updateSinkType(idx, 'KAFKA')}
                  />
                  <span className="text-sm">Kafka (Avro)</span>
                </label>
                <label className="flex items-center gap-2 cursor-pointer">
                  <input
                    type="radio"
                    name={`sink-type-${idx}`}
                    value="S3"
                    checked={sink.type === 'S3'}
                    onChange={() => updateSinkType(idx, 'S3')}
                  />
                  <span className="text-sm">S3 (Parquet)</span>
                </label>
              </div>

              {/* Table name — always shown */}
              <div>
                <label className="block text-xs text-gray-600 mb-1">tableName</label>
                <input
                  type="text"
                  value={sink.tableName}
                  onChange={(e) => updateSink(idx, { tableName: e.target.value })}
                  className="w-full border border-gray-300 rounded px-2 py-1 text-sm focus:outline-none focus:ring-1 focus:ring-blue-500"
                />
              </div>

              {sink.type === 'KAFKA' && (
                <div className="grid grid-cols-2 gap-3">
                  {(['topic', 'bootstrapServers', 'schemaRegistryUrl', 'avroSubject'] as const).map((field) => (
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
              )}

              {sink.type === 'S3' && (
                <div className="space-y-3">
                  <div className="bg-blue-50 border border-blue-200 rounded px-3 py-2 text-xs text-blue-700">
                    Files roll every 5 min or 128 MB
                  </div>

                  <div className="grid grid-cols-2 gap-3">
                    <div>
                      <label className="block text-xs text-gray-600 mb-1">S3 Bucket *</label>
                      <input
                        type="text"
                        value={sink.bucket}
                        onChange={(e) => updateSink(idx, { bucket: e.target.value } as Partial<S3SinkForm>)}
                        className="w-full border border-gray-300 rounded px-2 py-1 text-sm focus:outline-none focus:ring-1 focus:ring-blue-500"
                        placeholder="my-bucket"
                      />
                    </div>
                    <div>
                      <label className="block text-xs text-gray-600 mb-1">S3 Prefix *</label>
                      <input
                        type="text"
                        value={sink.prefix}
                        onChange={(e) => updateSink(idx, { prefix: e.target.value } as Partial<S3SinkForm>)}
                        className="w-full border border-gray-300 rounded px-2 py-1 text-sm focus:outline-none focus:ring-1 focus:ring-blue-500"
                        placeholder="data/output"
                      />
                    </div>
                  </div>

                  <label className="flex items-center gap-2 cursor-pointer text-sm">
                    <input
                      type="checkbox"
                      checked={sink.partitioned}
                      onChange={(e) => updateSink(idx, { partitioned: e.target.checked } as Partial<S3SinkForm>)}
                    />
                    Hive-style partitioned paths
                  </label>

                  <div>
                    <label className="block text-xs text-gray-600 mb-1">Authentication</label>
                    <div className="flex gap-4">
                      <label className="flex items-center gap-2 cursor-pointer text-sm">
                        <input
                          type="radio"
                          name={`sink-auth-${idx}`}
                          value="IAM_ROLE"
                          checked={sink.authType === 'IAM_ROLE'}
                          onChange={() => updateSink(idx, { authType: 'IAM_ROLE' } as Partial<S3SinkForm>)}
                        />
                        IAM Role
                      </label>
                      <label className="flex items-center gap-2 cursor-pointer text-sm">
                        <input
                          type="radio"
                          name={`sink-auth-${idx}`}
                          value="ACCESS_KEY"
                          checked={sink.authType === 'ACCESS_KEY'}
                          onChange={() => updateSink(idx, { authType: 'ACCESS_KEY' } as Partial<S3SinkForm>)}
                        />
                        Access Key
                      </label>
                    </div>
                  </div>

                  {sink.authType === 'ACCESS_KEY' && (
                    <div className="grid grid-cols-2 gap-3">
                      <div>
                        <label className="block text-xs text-gray-600 mb-1">Access Key ID *</label>
                        <input
                          type="text"
                          value={sink.accessKey}
                          onChange={(e) => updateSink(idx, { accessKey: e.target.value } as Partial<S3SinkForm>)}
                          className="w-full border border-gray-300 rounded px-2 py-1 text-sm focus:outline-none focus:ring-1 focus:ring-blue-500"
                        />
                      </div>
                      <div>
                        <label className="block text-xs text-gray-600 mb-1">Secret Access Key *</label>
                        <input
                          type="password"
                          value={sink.secretKey}
                          onChange={(e) => updateSink(idx, { secretKey: e.target.value } as Partial<S3SinkForm>)}
                          className="w-full border border-gray-300 rounded px-2 py-1 text-sm focus:outline-none focus:ring-1 focus:ring-blue-500"
                        />
                      </div>
                    </div>
                  )}

                  {/* Columns editor */}
                  <div>
                    <label className="block text-xs text-gray-600 mb-2">Columns *</label>
                    {sink.columns.length > 0 && (
                      <table className="w-full text-sm mb-2 border border-gray-200 rounded">
                        <thead>
                          <tr className="bg-gray-50">
                            <th className="text-left px-2 py-1 text-xs text-gray-600">Column Name</th>
                            <th className="text-left px-2 py-1 text-xs text-gray-600">SQL Type</th>
                            <th className="px-2 py-1"></th>
                          </tr>
                        </thead>
                        <tbody>
                          {sink.columns.map((col, colIdx) => (
                            <tr key={colIdx} className="border-t border-gray-100">
                              <td className="px-2 py-1">
                                <input
                                  type="text"
                                  value={col.name}
                                  onChange={(e) => updateSinkColumn(idx, colIdx, 'name', e.target.value)}
                                  className="w-full border border-gray-300 rounded px-1 py-0.5 text-xs focus:outline-none focus:ring-1 focus:ring-blue-500"
                                  placeholder="column_name"
                                />
                              </td>
                              <td className="px-2 py-1">
                                <select
                                  value={col.type}
                                  onChange={(e) => updateSinkColumn(idx, colIdx, 'type', e.target.value)}
                                  className="w-full border border-gray-300 rounded px-1 py-0.5 text-xs"
                                >
                                  {SQL_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
                                </select>
                              </td>
                              <td className="px-2 py-1">
                                <button
                                  onClick={() => removeSinkColumn(idx, colIdx)}
                                  className="text-red-500 text-xs hover:text-red-700"
                                >
                                  Remove
                                </button>
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    )}
                    <button
                      onClick={() => addSinkColumn(idx)}
                      className="text-sm text-blue-600 hover:text-blue-800"
                    >
                      + Add Column
                    </button>
                  </div>

                  {/* Partition output */}
                  <div>
                    <label className="flex items-center gap-2 cursor-pointer text-sm mb-2">
                      <input
                        type="checkbox"
                        checked={sink.s3PartitionColumns.length > 0}
                        onChange={(e) => {
                          if (!e.target.checked) {
                            updateSink(idx, { s3PartitionColumns: [] } as Partial<S3SinkForm>);
                          }
                        }}
                      />
                      Partition output by column(s)
                    </label>
                    {sink.columns.length > 0 && (
                      <div className="flex flex-wrap gap-2">
                        {sink.columns.filter((c) => c.name).map((col) => (
                          <label key={col.name} className="flex items-center gap-1 cursor-pointer text-xs">
                            <input
                              type="checkbox"
                              checked={sink.s3PartitionColumns.includes(col.name)}
                              onChange={(e) => {
                                const current = sink.s3PartitionColumns;
                                const updated = e.target.checked
                                  ? [...current, col.name]
                                  : current.filter((c) => c !== col.name);
                                updateSink(idx, { s3PartitionColumns: updated } as Partial<S3SinkForm>);
                              }}
                            />
                            {col.name}
                          </label>
                        ))}
                      </div>
                    )}
                  </div>
                </div>
              )}
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
            onClick={handleNext}
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
