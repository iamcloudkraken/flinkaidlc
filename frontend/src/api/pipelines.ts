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

export type S3AuthType = 'IAM_ROLE' | 'ACCESS_KEY';

export interface ColumnDefinition {
  name: string;
  type: string;
}

export interface KafkaSource {
  sourceType: 'KAFKA';
  sourceId: string;
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
  sinkType: 'KAFKA';
  sinkId: string;
  tableName: string;
  topic: string;
  bootstrapServers: string;
  schemaRegistryUrl?: string;
  avroSubject?: string;
}

export interface S3Source {
  sourceType: 'S3';
  sourceId: string;
  tableName: string;
  bucket: string;
  prefix: string;
  partitioned: boolean;
  authType: S3AuthType;
  accessKey?: string;
  columns: ColumnDefinition[];
}

export interface S3Sink {
  sinkType: 'S3';
  sinkId: string;
  tableName: string;
  bucket: string;
  prefix: string;
  partitioned: boolean;
  authType: S3AuthType;
  accessKey?: string;
  columns: ColumnDefinition[];
  s3PartitionColumns: string[];
}

export type PipelineSourceConfig = KafkaSource | S3Source;
export type PipelineSinkConfig = KafkaSink | S3Sink;

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
  sources: PipelineSourceConfig[];
  sinks: PipelineSinkConfig[];
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

export interface KafkaSourceRequest {
  type: 'KAFKA';
  tableName: string;
  topic: string;
  bootstrapServers: string;
  consumerGroup: string;
  startupMode: StartupMode;
  schemaRegistryUrl?: string;
  avroSubject?: string;
  watermarkColumn?: string;
  watermarkDelayMs?: number;
  columns?: ColumnDefinition[];
}

export interface KafkaSinkRequest {
  type: 'KAFKA';
  tableName: string;
  topic: string;
  bootstrapServers: string;
  schemaRegistryUrl?: string;
  avroSubject?: string;
  deliveryGuarantee?: string;
  columns?: ColumnDefinition[];
}

export interface S3SourceRequest {
  type: 'S3';
  tableName: string;
  bucket: string;
  prefix: string;
  partitioned: boolean;
  authType: S3AuthType;
  accessKey?: string;
  secretKey?: string;
  columns: ColumnDefinition[];
}

export interface S3SinkRequest {
  type: 'S3';
  tableName: string;
  bucket: string;
  prefix: string;
  partitioned: boolean;
  authType: S3AuthType;
  accessKey?: string;
  secretKey?: string;
  columns: ColumnDefinition[];
  s3PartitionColumns: string[];
}

export type PipelineSourceRequest = KafkaSourceRequest | S3SourceRequest;
export type PipelineSinkRequest = KafkaSinkRequest | S3SinkRequest;

export interface CreatePipelineRequest {
  name: string;
  description?: string;
  parallelism: number;
  checkpointIntervalMs: number;
  upgradeMode: UpgradeMode;
  sqlQuery: string;
  sources: PipelineSourceRequest[];
  sinks: PipelineSinkRequest[];
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
  const { data } = await apiClient.post<any>('/pipelines', payload);
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
    createdAt: data.createdAt,
    updatedAt: data.updatedAt,
  };
}

export async function updatePipeline(
  id: string,
  payload: UpdatePipelineRequest,
): Promise<Pipeline> {
  const { data } = await apiClient.put<any>(`/pipelines/${id}`, payload);
  return {
    id: data.pipelineId ?? id,
    name: data.name,
    description: data.description,
    status: data.status,
    parallelism: data.parallelism,
    checkpointIntervalMs: data.checkpointIntervalMs,
    upgradeMode: data.upgradeMode,
    sqlQuery: data.sqlQuery,
    sources: data.sources ?? [],
    sinks: data.sinks ?? [],
    createdAt: data.createdAt,
    updatedAt: data.updatedAt,
  };
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
