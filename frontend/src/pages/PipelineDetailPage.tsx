import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  getPipeline,
  suspendPipeline,
  resumePipeline,
  deletePipeline,
  type Pipeline,
} from '../api/pipelines';

const STATUS_COLORS: Record<string, string> = {
  RUNNING: 'bg-green-100 text-green-800',
  DEPLOYING: 'bg-blue-100 text-blue-800',
  SUSPENDED: 'bg-yellow-100 text-yellow-800',
  FAILED: 'bg-red-100 text-red-800',
  DRAFT: 'bg-gray-100 text-gray-800',
  DELETED: 'bg-gray-100 text-gray-400',
};

export default function PipelineDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [pipeline, setPipeline] = useState<Pipeline | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionLoading, setActionLoading] = useState(false);

  const fetchPipeline = async () => {
    if (!id) return;
    try {
      const data = await getPipeline(id);
      setPipeline(data);
      setError(null);
    } catch {
      setError('Failed to load pipeline details.');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchPipeline();
  }, [id]);

  // Auto-refresh every 10s when DEPLOYING
  useEffect(() => {
    if (pipeline?.status !== 'DEPLOYING') return;
    const interval = setInterval(fetchPipeline, 10_000);
    return () => clearInterval(interval);
  }, [pipeline?.status]);

  const handleSuspend = async () => {
    if (!id) return;
    setActionLoading(true);
    try {
      const updated = await suspendPipeline(id);
      setPipeline(updated);
    } catch {
      setError('Failed to suspend pipeline.');
    } finally {
      setActionLoading(false);
    }
  };

  const handleResume = async () => {
    if (!id) return;
    setActionLoading(true);
    try {
      const updated = await resumePipeline(id);
      setPipeline(updated);
    } catch {
      setError('Failed to resume pipeline.');
    } finally {
      setActionLoading(false);
    }
  };

  const handleDelete = async () => {
    if (!id || !window.confirm('Are you sure you want to delete this pipeline?')) return;
    setActionLoading(true);
    try {
      await deletePipeline(id);
      navigate('/pipelines');
    } catch {
      setError('Failed to delete pipeline.');
      setActionLoading(false);
    }
  };

  if (loading) {
    return <div className="text-center py-8 text-gray-500">Loading pipeline...</div>;
  }

  if (error) {
    return <div className="bg-red-50 border border-red-200 rounded p-4 text-red-700">{error}</div>;
  }

  if (!pipeline) {
    return <div className="text-center py-8 text-gray-500">Pipeline not found.</div>;
  }

  const statusColor = STATUS_COLORS[pipeline.status] ?? 'bg-gray-100 text-gray-600';

  return (
    <div className="max-w-4xl mx-auto space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">{pipeline.name}</h1>
          {pipeline.description && (
            <p className="text-gray-600 mt-1">{pipeline.description}</p>
          )}
        </div>
        <span className={`px-3 py-1 rounded-full text-sm font-medium ${statusColor}`}>
          {pipeline.status}
        </span>
      </div>

      {/* Actions */}
      <div className="flex gap-3">
        <button
          onClick={() => navigate(`/pipelines/${id}/edit`)}
          disabled={actionLoading}
          className="px-4 py-2 bg-white border border-gray-300 rounded-md text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50"
        >
          Edit
        </button>
        {pipeline.status === 'RUNNING' && (
          <button
            onClick={handleSuspend}
            disabled={actionLoading}
            className="px-4 py-2 bg-yellow-600 text-white rounded-md text-sm font-medium hover:bg-yellow-700 disabled:opacity-50"
          >
            Suspend
          </button>
        )}
        {pipeline.status === 'SUSPENDED' && (
          <button
            onClick={handleResume}
            disabled={actionLoading}
            className="px-4 py-2 bg-green-600 text-white rounded-md text-sm font-medium hover:bg-green-700 disabled:opacity-50"
          >
            Resume
          </button>
        )}
        <button
          onClick={handleDelete}
          disabled={actionLoading}
          className="px-4 py-2 bg-red-600 text-white rounded-md text-sm font-medium hover:bg-red-700 disabled:opacity-50"
        >
          Delete
        </button>
      </div>

      {/* Details grid */}
      <div className="bg-white border border-gray-200 rounded-lg p-6">
        <h2 className="text-lg font-semibold mb-4">Configuration</h2>
        <dl className="grid grid-cols-2 gap-4">
          <div>
            <dt className="text-sm text-gray-500">Parallelism</dt>
            <dd className="text-sm font-medium text-gray-900">{pipeline.parallelism}</dd>
          </div>
          <div>
            <dt className="text-sm text-gray-500">Checkpoint Interval</dt>
            <dd className="text-sm font-medium text-gray-900">{pipeline.checkpointIntervalMs} ms</dd>
          </div>
          <div>
            <dt className="text-sm text-gray-500">Upgrade Mode</dt>
            <dd className="text-sm font-medium text-gray-900">{pipeline.upgradeMode}</dd>
          </div>
          <div>
            <dt className="text-sm text-gray-500">Created</dt>
            <dd className="text-sm font-medium text-gray-900">
              {new Date(pipeline.createdAt).toLocaleString()}
            </dd>
          </div>
        </dl>
      </div>

      {/* SQL */}
      <div className="bg-white border border-gray-200 rounded-lg p-6">
        <h2 className="text-lg font-semibold mb-4">SQL Query</h2>
        <pre className="bg-gray-900 text-green-400 p-4 rounded text-sm overflow-x-auto whitespace-pre-wrap">
          {pipeline.sqlQuery}
        </pre>
      </div>

      {/* Sources */}
      <div className="bg-white border border-gray-200 rounded-lg p-6">
        <h2 className="text-lg font-semibold mb-4">Sources</h2>
        {pipeline.sources.length === 0 ? (
          <p className="text-gray-500 text-sm">No sources configured.</p>
        ) : (
          <div className="space-y-3">
            {pipeline.sources.map((source, idx) => (
              <div key={idx} className="border border-gray-100 rounded p-3 text-sm">
                <div className="font-medium">{source.tableName}</div>
                <div className="text-gray-600">Topic: {source.topic}</div>
                <div className="text-gray-600">Bootstrap Servers: {source.bootstrapServers}</div>
                {source.schemaRegistryUrl && (
                  <div className="text-gray-600">Schema Registry: {source.schemaRegistryUrl}</div>
                )}
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Sinks */}
      <div className="bg-white border border-gray-200 rounded-lg p-6">
        <h2 className="text-lg font-semibold mb-4">Sinks</h2>
        {pipeline.sinks.length === 0 ? (
          <p className="text-gray-500 text-sm">No sinks configured.</p>
        ) : (
          <div className="space-y-3">
            {pipeline.sinks.map((sink, idx) => (
              <div key={idx} className="border border-gray-100 rounded p-3 text-sm">
                <div className="font-medium">{sink.tableName}</div>
                <div className="text-gray-600">Topic: {sink.topic}</div>
                <div className="text-gray-600">Bootstrap Servers: {sink.bootstrapServers}</div>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Deployment info */}
      {pipeline.deploymentInfo && (
        <div className="bg-white border border-gray-200 rounded-lg p-6">
          <h2 className="text-lg font-semibold mb-4">Deployment</h2>
          <dl className="space-y-2 text-sm">
            {pipeline.deploymentInfo.savepointPath && (
              <div>
                <dt className="text-gray-500">Last Savepoint</dt>
                <dd className="text-gray-900 font-mono text-xs break-all">{pipeline.deploymentInfo.savepointPath}</dd>
              </div>
            )}
            {pipeline.deploymentInfo.errorMessage && (
              <div>
                <dt className="text-gray-500">Error</dt>
                <dd className="text-red-700">{pipeline.deploymentInfo.errorMessage}</dd>
              </div>
            )}
          </dl>
        </div>
      )}
    </div>
  );
}
