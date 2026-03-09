import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useAuth } from '../auth/AuthContext';
import { getTenant, type Tenant } from '../api/tenants';

function ProgressBar({ value, max, label }: { value: number; max: number; label: string }) {
  const pct = max > 0 ? Math.min(100, Math.round((value / max) * 100)) : 0;
  const color = pct >= 90 ? 'bg-red-500' : pct >= 70 ? 'bg-yellow-500' : 'bg-green-500';
  return (
    <div className="mb-4">
      <div className="flex justify-between text-sm text-gray-600 mb-1">
        <span>{label}</span>
        <span>
          {value} / {max} ({pct}%)
        </span>
      </div>
      <div className="h-3 bg-gray-200 rounded-full overflow-hidden">
        <div
          className={`h-full ${color} rounded-full transition-all`}
          style={{ width: `${pct}%` }}
          role="progressbar"
          aria-valuenow={value}
          aria-valuemin={0}
          aria-valuemax={max}
          aria-label={label}
        />
      </div>
    </div>
  );
}

function StatusBadge({ status }: { status: Tenant['status'] }) {
  const colorMap: Record<Tenant['status'], string> = {
    ACTIVE: 'bg-green-100 text-green-700',
    SUSPENDED: 'bg-red-100 text-red-700',
  };
  return (
    <span
      className={`inline-block px-2 py-0.5 rounded-full text-xs font-semibold ${colorMap[status]}`}
    >
      {status}
    </span>
  );
}

export default function TenantDashboardPage() {
  const { tenantId } = useAuth();

  const {
    data: tenant,
    isLoading,
    isError,
    error,
  } = useQuery({
    queryKey: ['tenant', tenantId],
    queryFn: () => getTenant(tenantId!),
    enabled: !!tenantId,
    staleTime: 60_000,
  });

  if (isLoading) {
    return (
      <div className="flex justify-center items-center py-20 text-gray-500">
        Loading dashboard…
      </div>
    );
  }

  if (isError || !tenant) {
    const msg =
      error instanceof Error ? error.message : 'Failed to load tenant data.';
    return (
      <div role="alert" className="max-w-lg mx-auto mt-10 p-4 bg-red-50 border border-red-200 rounded text-red-700">
        {msg}
      </div>
    );
  }

  return (
    <div className="max-w-2xl mx-auto">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-2xl font-bold text-gray-800">{tenant.name}</h1>
          <p className="text-sm text-gray-500">
            Slug: <span className="font-mono">{tenant.slug}</span>
          </p>
        </div>
        <StatusBadge status={tenant.status} />
      </div>

      <div className="bg-white shadow rounded-lg p-6 mb-6">
        <h2 className="text-base font-semibold text-gray-700 mb-4">
          Resource Usage
        </h2>
        <ProgressBar
          label="Pipelines"
          value={tenant.usedPipelines}
          max={tenant.maxPipelines}
        />
        <ProgressBar
          label="Total Parallelism"
          value={tenant.usedParallelism}
          max={tenant.maxTotalParallelism}
        />
      </div>

      <div className="bg-white shadow rounded-lg p-6">
        <h2 className="text-base font-semibold text-gray-700 mb-3">
          Account Details
        </h2>
        <dl className="grid grid-cols-2 gap-y-2 text-sm text-gray-600">
          <dt className="font-medium">Tenant ID</dt>
          <dd className="font-mono text-xs truncate">{tenant.id}</dd>
          <dt className="font-medium">Contact Email</dt>
          <dd>{tenant.contactEmail}</dd>
          <dt className="font-medium">Member Since</dt>
          <dd>{new Date(tenant.createdAt).toLocaleDateString()}</dd>
        </dl>
      </div>

      <div className="mt-6 text-right">
        <Link
          to="/pipelines"
          className="bg-indigo-600 hover:bg-indigo-700 text-white text-sm font-medium px-4 py-2 rounded transition-colors"
        >
          View Pipelines
        </Link>
      </div>
    </div>
  );
}
