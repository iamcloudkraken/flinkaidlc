import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  listPipelines,
  deletePipeline,
  suspendPipeline,
  resumePipeline,
  type PipelineListItem,
  type PipelineStatus,
} from '../api/pipelines';

const STATUS_COLORS: Record<PipelineStatus, string> = {
  DRAFT: 'bg-gray-100 text-gray-600',
  DEPLOYING: 'bg-blue-100 text-blue-700',
  RUNNING: 'bg-green-100 text-green-700',
  SUSPENDED: 'bg-yellow-100 text-yellow-700',
  FAILED: 'bg-red-100 text-red-700',
  DELETED: 'bg-gray-200 text-gray-500',
};

function StatusBadge({ status }: { status: PipelineStatus }) {
  return (
    <span
      className={`inline-block px-2 py-0.5 rounded-full text-xs font-semibold ${STATUS_COLORS[status]}`}
    >
      {status}
    </span>
  );
}

export default function PipelineListPage() {
  const [page, setPage] = useState(0);
  const navigate = useNavigate();
  const qc = useQueryClient();

  const { data, isLoading, isError } = useQuery({
    queryKey: ['pipelines', page],
    queryFn: () => listPipelines(page, 20),
    staleTime: 30_000,
  });

  const suspendMutation = useMutation({
    mutationFn: (id: string) => suspendPipeline(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['pipelines'] }),
  });

  const resumeMutation = useMutation({
    mutationFn: (id: string) => resumePipeline(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['pipelines'] }),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => deletePipeline(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['pipelines'] }),
  });

  function handleDelete(pipeline: PipelineListItem) {
    if (
      window.confirm(
        `Delete pipeline "${pipeline.name}"? This action cannot be undone.`,
      )
    ) {
      deleteMutation.mutate(pipeline.id);
    }
  }

  return (
    <div className="max-w-6xl mx-auto">
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-2xl font-bold text-gray-800">Pipelines</h1>
        <button
          onClick={() => navigate('/pipelines/new')}
          className="bg-indigo-600 hover:bg-indigo-700 text-white text-sm font-medium px-4 py-2 rounded transition-colors"
        >
          + New Pipeline
        </button>
      </div>

      {isLoading && (
        <div className="text-center py-12 text-gray-500">
          Loading pipelines…
        </div>
      )}

      {isError && (
        <div
          role="alert"
          className="p-4 bg-red-50 border border-red-200 rounded text-red-700 text-sm"
        >
          Failed to load pipelines. Please try again.
        </div>
      )}

      {data && data.items.length === 0 && (
        <div className="text-center py-12 text-gray-400">
          No pipelines yet.{' '}
          <Link to="/pipelines/new" className="text-indigo-600 hover:underline">
            Create one
          </Link>
          .
        </div>
      )}

      {data && data.items.length > 0 && (
        <>
          <div className="bg-white shadow rounded-lg overflow-hidden">
            <table className="min-w-full divide-y divide-gray-200">
              <thead className="bg-gray-50">
                <tr>
                  {['Name', 'Status', 'Parallelism', 'Created', 'Actions'].map(
                    (h) => (
                      <th
                        key={h}
                        className="px-4 py-3 text-left text-xs font-semibold text-gray-500 uppercase tracking-wider"
                      >
                        {h}
                      </th>
                    ),
                  )}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {data.items.map((p) => (
                  <tr key={p.id} className="hover:bg-gray-50 transition-colors">
                    <td className="px-4 py-3 text-sm font-medium text-gray-800">
                      <Link
                        to={`/pipelines/${p.id}`}
                        className="hover:text-indigo-600 hover:underline"
                      >
                        {p.name}
                      </Link>
                    </td>
                    <td className="px-4 py-3">
                      <StatusBadge status={p.status} />
                    </td>
                    <td className="px-4 py-3 text-sm text-gray-600">
                      {p.parallelism}
                    </td>
                    <td className="px-4 py-3 text-sm text-gray-500">
                      {new Date(p.createdAt).toLocaleDateString()}
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-2">
                        <Link
                          to={`/pipelines/${p.id}`}
                          className="text-xs text-indigo-600 hover:underline"
                        >
                          View
                        </Link>
                        {p.status === 'RUNNING' && (
                          <button
                            onClick={() => suspendMutation.mutate(p.id)}
                            className="text-xs text-yellow-600 hover:underline"
                          >
                            Suspend
                          </button>
                        )}
                        {p.status === 'SUSPENDED' && (
                          <button
                            onClick={() => resumeMutation.mutate(p.id)}
                            className="text-xs text-green-600 hover:underline"
                          >
                            Resume
                          </button>
                        )}
                        <button
                          onClick={() => handleDelete(p)}
                          className="text-xs text-red-600 hover:underline"
                        >
                          Delete
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {/* Pagination */}
          <div className="mt-4 flex items-center justify-between text-sm text-gray-600">
            <span>
              Page {data.page + 1} of {Math.ceil(data.total / data.size)} ({data.total}{' '}
              total)
            </span>
            <div className="flex gap-2">
              <button
                disabled={page === 0}
                onClick={() => setPage((p) => Math.max(0, p - 1))}
                className="px-3 py-1 border rounded disabled:opacity-40 hover:bg-gray-100 transition-colors"
              >
                Previous
              </button>
              <button
                disabled={page >= Math.ceil(data.total / data.size) - 1}
                onClick={() => setPage((p) => p + 1)}
                className="px-3 py-1 border rounded disabled:opacity-40 hover:bg-gray-100 transition-colors"
              >
                Next
              </button>
            </div>
          </div>
        </>
      )}
    </div>
  );
}
