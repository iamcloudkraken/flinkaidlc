---
intent_slug: s3-parquet-source-sink
worktree_path: /Users/srikanthbalagoni/Documents/workspace/iamcloudkrakenbase/flinkaidlc/.ai-dlc/worktrees/s3-parquet-source-sink
project_maturity: established
provider_config: {}
---

# Intent Description

Add S3 (Parquet) as a supported source and sink type alongside Kafka when creating pipelines, for both the backend API and the React web UI.

## Clarification Answers

Q: What is the scope?
A: Both API and Web UI

Q: Which S3 source read patterns should be supported?
A: Both patterns — flat bucket/prefix reads AND partitioned path reads (e.g., year=2024/month=01/day=15/)

Q: How will the platform authenticate to S3?
A: Both options — user can choose either IAM role/instance profile (no credentials required) OR explicit access key + secret access key pair

Q: For S3 sink: how should Flink roll output files?
A: Fixed defaults, no config — hard-code sensible defaults (5-minute time-based rolling, 128 MB size limit). No per-pipeline configuration exposed in the UI.

## Discovery File Path

/Users/srikanthbalagoni/Documents/workspace/iamcloudkrakenbase/flinkaidlc/.ai-dlc/worktrees/s3-parquet-source-sink/.ai-dlc/s3-parquet-source-sink/discovery.md
