import apiClient from './axios';

export interface Tenant {
  id: string;
  name: string;
  slug: string;
  contactEmail: string;
  status: 'ACTIVE' | 'SUSPENDED';
  maxPipelines: number;
  maxTotalParallelism: number;
  usedPipelines: number;
  usedParallelism: number;
  createdAt: string;
}

export interface CreateTenantRequest {
  name: string;
  slug: string;
  contactEmail: string;
  maxPipelines: number;
  maxTotalParallelism: number;
}

export interface CreateTenantResponse {
  tenant: Tenant;
  fid: string;
  fidSecret: string;
}

export interface TenantSummary {
  id: string;
  name: string;
  slug: string;
}

export async function listTenants(): Promise<TenantSummary[]> {
  const { data } = await apiClient.get<TenantSummary[]>('/tenants');
  return data;
}

export async function getTenant(tenantId: string): Promise<Tenant> {
  const { data } = await apiClient.get<Tenant>(`/tenants/${tenantId}`);
  return data;
}

export async function createTenant(
  payload: CreateTenantRequest,
): Promise<CreateTenantResponse> {
  const { data } = await apiClient.post<CreateTenantResponse>(
    '/tenants',
    payload,
  );
  return data;
}
