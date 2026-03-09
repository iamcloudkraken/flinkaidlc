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

export interface PipelinePage {
  content: Pipeline[];
  totalElements: number;
  totalPages: number;
  number: number;
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
  const { data } = await apiClient.get<PipelinePage>('/pipelines', {
    params: { page, size },
  });
  return data;
}

export async function getPipeline(id: string): Promise<Pipeline> {
  const { data } = await apiClient.get<Pipeline>(`/pipelines/${id}`);
  return data;
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
