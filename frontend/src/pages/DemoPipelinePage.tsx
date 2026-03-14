import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { createPipeline } from '../api/pipelines';
import type { CreatePipelineRequest } from '../api/pipelines';

const BOOTSTRAP = 'kafka.ns-enterprise.svc.cluster.local:29092';
const SCHEMA_REGISTRY = 'http://schema-registry.ns-enterprise.svc.cluster.local:8081';

const DEMO_SQL = `INSERT INTO enriched_events
SELECT
  event_id,
  user_id,
  session_id,
  page,
  action,
  ts_ms,
  CONCAT(action, ' on ', page, ' by user ', user_id) AS enriched_event
FROM events`;

const DEMO_PAYLOAD: CreatePipelineRequest = {
  name: 'Demo Enrichment Pipeline',
  description: 'Reads raw click events, adds enriched_event column, writes to sink topic.',
  parallelism: 1,
  checkpointIntervalMs: 10000,
  upgradeMode: 'STATELESS',
  sqlQuery: DEMO_SQL,
  sources: [
    {
      type: 'KAFKA',
      tableName: 'events',
      topic: 'demo.events',
      bootstrapServers: BOOTSTRAP,
      consumerGroup: 'demo-enrichment-group',
      startupMode: 'EARLIEST',
      schemaRegistryUrl: SCHEMA_REGISTRY,
      avroSubject: 'demo.events-value',
      columns: [
        { name: 'event_id', type: 'STRING' },
        { name: 'user_id', type: 'STRING' },
        { name: 'session_id', type: 'STRING' },
        { name: 'page', type: 'STRING' },
        { name: 'action', type: 'STRING' },
        { name: 'ts_ms', type: 'BIGINT' },
      ],
    },
  ],
  sinks: [
    {
      type: 'KAFKA',
      tableName: 'enriched_events',
      topic: 'demo.enriched-events',
      bootstrapServers: BOOTSTRAP,
      schemaRegistryUrl: SCHEMA_REGISTRY,
      avroSubject: 'demo.enriched-events-value',
      columns: [
        { name: 'event_id', type: 'STRING' },
        { name: 'user_id', type: 'STRING' },
        { name: 'session_id', type: 'STRING' },
        { name: 'page', type: 'STRING' },
        { name: 'action', type: 'STRING' },
        { name: 'ts_ms', type: 'BIGINT' },
        { name: 'enriched_event', type: 'STRING' },
      ],
    },
  ],
};

export default function DemoPipelinePage() {
  const navigate = useNavigate();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleDeploy() {
    setError(null);
    setLoading(true);
    try {
      const pipeline = await createPipeline(DEMO_PAYLOAD);
      navigate(`/pipelines/${pipeline.id}`);
    } catch (err: unknown) {
      const msg =
        (err as any)?.response?.data?.message ??
        (err as any)?.message ??
        'Failed to create pipeline.';
      setError(String(msg));
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="max-w-2xl mx-auto">
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-gray-800">Demo Pipeline</h1>
        <p className="mt-1 text-sm text-gray-500">
          Pre-configured enrichment pipeline using the seeded Kafka topics.
        </p>
      </div>

      {error && (
        <div
          role="alert"
          className="mb-6 p-3 bg-red-50 border border-red-200 rounded text-red-700 text-sm"
        >
          {error}
        </div>
      )}

      <div className="space-y-4">
        {/* Source */}
        <section className="bg-white rounded-lg shadow-sm border border-gray-200 p-5">
          <h2 className="text-sm font-semibold text-gray-500 uppercase tracking-wide mb-3">
            Source
          </h2>
          <dl className="grid grid-cols-2 gap-x-4 gap-y-2 text-sm">
            <dt className="text-gray-500">Topic</dt>
            <dd className="font-mono text-gray-800">demo.events</dd>
            <dt className="text-gray-500">Format</dt>
            <dd className="text-gray-800">Avro (Confluent)</dd>
            <dt className="text-gray-500">Schema subject</dt>
            <dd className="font-mono text-gray-800">demo.events-value</dd>
            <dt className="text-gray-500">Startup mode</dt>
            <dd className="text-gray-800">EARLIEST</dd>
            <dt className="text-gray-500">Consumer group</dt>
            <dd className="font-mono text-gray-800">demo-enrichment-group</dd>
          </dl>
        </section>

        {/* Sink */}
        <section className="bg-white rounded-lg shadow-sm border border-gray-200 p-5">
          <h2 className="text-sm font-semibold text-gray-500 uppercase tracking-wide mb-3">
            Sink
          </h2>
          <dl className="grid grid-cols-2 gap-x-4 gap-y-2 text-sm">
            <dt className="text-gray-500">Topic</dt>
            <dd className="font-mono text-gray-800">demo.enriched-events</dd>
            <dt className="text-gray-500">Format</dt>
            <dd className="text-gray-800">Avro (Confluent)</dd>
            <dt className="text-gray-500">Schema subject</dt>
            <dd className="font-mono text-gray-800">demo.enriched-events-value</dd>
          </dl>
        </section>

        {/* SQL */}
        <section className="bg-white rounded-lg shadow-sm border border-gray-200 p-5">
          <h2 className="text-sm font-semibold text-gray-500 uppercase tracking-wide mb-3">
            SQL Query
          </h2>
          <pre className="bg-gray-50 border border-gray-200 rounded p-3 text-xs font-mono text-gray-800 overflow-x-auto whitespace-pre">
            {DEMO_SQL}
          </pre>
          <p className="mt-2 text-xs text-gray-500">
            Adds{' '}
            <span className="font-mono bg-gray-100 px-1 rounded">enriched_event</span>
            {' '}— a human-readable summary of each click event.
          </p>
        </section>

        {/* Config */}
        <section className="bg-white rounded-lg shadow-sm border border-gray-200 p-5">
          <h2 className="text-sm font-semibold text-gray-500 uppercase tracking-wide mb-3">
            Configuration
          </h2>
          <dl className="grid grid-cols-2 gap-x-4 gap-y-2 text-sm">
            <dt className="text-gray-500">Parallelism</dt>
            <dd className="text-gray-800">1</dd>
            <dt className="text-gray-500">Checkpoint interval</dt>
            <dd className="text-gray-800">10 s</dd>
            <dt className="text-gray-500">Upgrade mode</dt>
            <dd className="text-gray-800">STATELESS</dd>
          </dl>
        </section>
      </div>

      <div className="mt-6 flex gap-3">
        <button
          onClick={handleDeploy}
          disabled={loading}
          className="bg-indigo-600 hover:bg-indigo-700 disabled:bg-indigo-400 text-white font-medium py-2 px-6 rounded transition-colors"
        >
          {loading ? 'Creating pipeline…' : 'Create & Deploy Demo Pipeline'}
        </button>
        <button
          onClick={() => navigate('/pipelines')}
          className="text-gray-600 hover:text-gray-800 font-medium py-2 px-4 rounded border border-gray-300 hover:border-gray-400 transition-colors"
        >
          Cancel
        </button>
      </div>
    </div>
  );
}
