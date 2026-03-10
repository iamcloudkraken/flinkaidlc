import apiClient from './axios';

export type PipelineStatus =
  | 'DRAFT'
  | 'DEPLOYING'
  | 'RUNNING'
  | 'SUSPENDED'
  | 'FAILED'
  | 'DELETED';

export type UpgradeMode = 'STATELESS' | 'SAVEPOINT' | 'LAST_STATE';

export type StartupMode =
  | 'EARLIEST'
  | 'LATEST'
  | 'GROUP_OFFSETS'
  | 'TIMESTAMP';

export interface KafkaSource {
  tableName: string;
  topic: string;
  bootstrapServers: string;
  consumerGroup: string;
  startupMode: StartupMode;
  schemaRegistryUrl?: string;
  avroSubject?: string;
  watermarkColumn?: string;
  watermarkDelayMs?: number;
}

export interface KafkaSink {
  tableName: string;
  topic: string;
  bootstrapServers: string;
  schemaRegistryUrl?: string;
  avroSubject?: string;
}

export interface DeploymentInfo {
  jobId?: string;
  jobManagerUrl?: string;
  savepointPath?: string;
  lastDeployedAt?: string;
  errorMessage?: string;
}

export interface Pipeline {
  id: string;
  name: string;
  description?: string;
  status: PipelineStatus;
  parallelism: number;
  checkpointIntervalMs: number;
  upgradeMode: UpgradeMode;
  sqlQuery: string;
  sources: KafkaSource[];
  sinks: KafkaSink[];
  deploymentInfo?: DeploymentInfo;
  createdAt: string;
  updatedAt: string;
}

export interface PipelineListItem {
  id: string;
  name: string;
  status: PipelineStatus;
  parallelism: number;
  createdAt: string;
  updatedAt: string;
}

export interface PipelinePage {
  items: PipelineListItem[];
  total: number;
  page: number;
  size: number;
}

export interface CreatePipelineRequest {
  name: string;
  description?: string;
  parallelism: number;
  checkpointIntervalMs: number;
  upgradeMode: UpgradeMode;
  sqlQuery: string;
  sources: KafkaSource[];
  sinks: KafkaSink[];
}

export type UpdatePipelineRequest = CreatePipelineRequest;

export interface ApiValidationError {
  field: string;
  message: string;
}

export interface ApiError {
  message: string;
  details?: ApiValidationError[];
}

export async function listPipelines(
  page = 0,
  size = 20,
): Promise<PipelinePage> {
  const { data } = await apiClient.get<any>('/pipelines', {
    params: { page, size },
  });
  return {
    total: data.total,
    page: data.page,
    size: data.size,
    items: (data.items ?? []).map((item: any) => ({
      id: item.pipelineId,
      name: item.name,
      status: item.status,
      parallelism: item.parallelism,
      createdAt: item.createdAt,
      updatedAt: item.updatedAt,
    })),
  };
}

export async function getPipeline(id: string): Promise<Pipeline> {
  const { data } = await apiClient.get<any>(`/pipelines/${id}`);
  const hasDeploymentInfo =
    data.lifecycleState || data.lastSavepointPath || data.errorMessage;
  return {
    id: data.pipelineId,
    name: data.name,
    description: data.description,
    status: data.status,
    parallelism: data.parallelism,
    checkpointIntervalMs: data.checkpointIntervalMs,
    upgradeMode: data.upgradeMode,
    sqlQuery: data.sqlQuery,
    sources: data.sources ?? [],
    sinks: data.sinks ?? [],
    deploymentInfo: hasDeploymentInfo
      ? {
          savepointPath: data.lastSavepointPath,
          errorMessage: data.errorMessage,
        }
      : undefined,
    createdAt: data.createdAt,
    updatedAt: data.updatedAt,
  };
}

export async function createPipeline(
  payload: CreatePipelineRequest,
): Promise<Pipeline> {
  const { data } = await apiClient.post<Pipeline>('/pipelines', payload);
  return data;
}

export async function updatePipeline(
  id: string,
  payload: UpdatePipelineRequest,
): Promise<Pipeline> {
  const { data } = await apiClient.put<Pipeline>(`/pipelines/${id}`, payload);
  return data;
}

export async function suspendPipeline(id: string): Promise<Pipeline> {
  const { data } = await apiClient.post<Pipeline>(
    `/pipelines/${id}/suspend`,
    {},
  );
  return data;
}

export async function resumePipeline(id: string): Promise<Pipeline> {
  const { data } = await apiClient.post<Pipeline>(
    `/pipelines/${id}/resume`,
    {},
  );
  return data;
}

export async function deletePipeline(id: string): Promise<void> {
  await apiClient.delete(`/pipelines/${id}`);
}
