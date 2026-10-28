# Microservice demo and evidence

This is the local implementation guide. The running VPS has not been migrated.
The accepted scope and outstanding acceptance gates remain in
[the delivery ledger](microservices-implementation.md).

## Architecture

```mermaid
flowchart LR
    Browser -->|auth, practice, ownership| Core[Learning Core / Spring Boot]
    Core --> CoreDB[(Core database)]
    Core -->|authenticated text and job API| AI[AI API / Python]
    AI --> AIRegistry[(Private AI provider registry)]
    AI --> Jobs[(Private durable job ledger)]
    AI --> Broker[RabbitMQ]
    Broker --> Worker[AI worker / Python]
    Worker --> Jobs
    Worker --> AIRegistry
    Worker -->|local demo| Model[CPU Model Serving / base + full07]
    Worker -->|VPS or opted-in fallback| Cloud[Configured cloud provider]
    AI --> Cloud
    CoreDB --> Outbox[Core transactional outbox]
    Outbox --> Broker
    Broker --> Progress[Progress / Spring Boot]
    Progress --> ProgressDB[(Progress database)]
    Core -->|remote dashboard projection| Progress
```

There are three business services. The API and worker are processes of the same
AI service; Model Serving is a separate inference process. Each business service
owns its data and build. Separate databases/roles may share a PostgreSQL cluster;
neither AI nor Progress reads Core tables. Redis coordinates JWT sessions and
provider quotas; it is omitted from the diagram for readability.

Core owns identity, sessions, scoring, recommendations and listening assessment.
Progress projects completed solo dictation sessions. Its dashboard is eventually
consistent; the response reports its source. Broker delivery is at least once.
Consumers handle duplicates, revisions and deletion tombstones. A Progress outage
can make the dashboard unavailable while Core still accepts completed practice.

## Start the local application

Prerequisites: Java 17, Maven, the pinned Python application dependencies,
PostgreSQL, Redis and RabbitMQ. Follow
[private database/configuration setup](ai-provider-database.md) to prepare the
protected `data/microservices/{core,ai,progress,model}.env` files. They are ignored
by Git. Do not copy provider credentials into Core's file.

Build from the repository root:

```powershell
mvn --batch-mode --no-transfer-progress -DskipTests package
mvn -f services/progress/pom.xml --batch-mode --no-transfer-progress -DskipTests package
python scripts/run-service.py stack --config-dir data/microservices --with-model --check-only
python scripts/run-service.py stack --config-dir data/microservices --with-model
```

The check confirms business-service health and model-server liveness. Model
weights load at the first inference request and still require at least 20 GiB
available RAM. Protected logs are under `data/microservices/logs/`. Ctrl+C stops
the processes owned by the supervisor. Infrastructure remains running.

Open the local Core UI, sign in, then open Conversation. The model-demo panel is
part of that screen, enabled only when the local demo flags are configured.
No separate public model endpoint or frontend provider credential is needed.

## Interview/demo sequence

1. Complete a dictation lesson. Show Core history and the remote Progress
   dashboard; explain outbox delivery and the response's freshness metadata.
2. In Conversation, submit a full07 demo job. Show queued/running/terminal states,
   stored history and the provider/model/prompt identifiers in the result.
3. Compare base and full07 on the same context. They run sequentially to reduce
   memory use. Comparisons never use cloud fallback. Compare actual content and
   validation outcomes; invalid output cannot bypass acceptance gates.
4. Explain the difference between a model result and a validated practice task.
   Show the original training and GPU evaluation evidence separately from
   application integration results. Fresh R13 application CPU generation is an
   outstanding gate while RAM is insufficient.
5. Show the automated recovery evidence: duplicate delivery/lost ACK, broker
   outage, service restart, durable jobs and deletion before late delivery.
   Do not kill a supervised child for a manual isolation demo: the supervisor
   deliberately stops the whole owned stack when one child exits. Use separate
   service launchers or the disposable integration test for that demonstration.

R13 adds source-derived glossary and clause/fact alignment for delivery scenes.
It does not change weights. The original GPU study found improvements in that
scope, including both base and adapter; it does not establish general adapter
superiority. B1 soft length targets remain imperfect. See
[the R13 evidence and limits](model-demo-guided-r13.md).

## Repeat the Linux resource rehearsal

Prerequisites: built JARs, Docker Engine, k6 and the existing Python dependencies.
Build the Progress image once, then run:

```powershell
docker build -t dictalab-progress:local services/progress
python -X utf8 tools/ai-eval/stack_capacity.py --users 5 --seconds 30
python -X utf8 tools/ai-eval/stack_capacity.py --users 20 --seconds 60
python -X utf8 -m pytest -q tools/ai-eval/test_stack_capacity.py
```

Each run creates its own PostgreSQL databases/roles, Redis, broker, AI registry,
AI ledger and random loopback ports. It uses the existing k6 mixed practice/read
journeys, with enabled authentication, CSRF and rate limits. Concurrent Coach
jobs traverse API, RabbitMQ and worker. All provider responses are synthetic;
there are no paid model calls or CPU-model weights in this rehearsal.

New evidence goes under a fresh protected `target/stack-capacity-<timestamp>/`:
`result.json`, `samples.json`, `k6.json`, `k6.log` and container logs. The run
checks unauthenticated requests, cross-service database access denial,
per-user completed-practice/projection accounting,
outbox drain, generic AI text, child liveness, OOM state and owned-resource
cleanup. Never commit private runtime configs or raw application logs.

Docker working-set samples use rounded display units. Kernel cgroup peaks also
include cache. These exclude the host OS and unrelated processes. Docker-host
CPU count differs from the VPS: local latency is not a VPS throughput claim.
`passed=true` means the stated functional rehearsal passed; the capacity verdict
remains measurement-only until the actual VPS passes its own acceptance.

### Measurements recorded on 28 October 2026

| Local Linux run | 5 users / 30 seconds | 20 users / 60 seconds |
| --- | ---: | ---: |
| Completed mixed journeys | 117 | 964 |
| HTTP requests | 396 | 3,168 |
| HTTP/business failure rate | 0% / 0% | 0% / 0% |
| Work HTTP p95 | 62.2 ms | 30.0 ms |
| Dashboard HTTP p95 | 65.4 ms | 35.6 ms |
| Completed practices / matching projections | 5 / 5 | 36 / 36 |
| Completed concurrent AI jobs | 20 | 36 |
| Pending Core outbox at acceptance | 0 | 0 |
| Simultaneous business processes + RabbitMQ working-set peak | 925.5 MiB | 1,006.8 MiB |
| All container working-set peak, including DB/Redis/fake provider | 1,050.4 MiB | 1,137.2 MiB |
| Core container limit | 512 MiB | 640 MiB |

These are separate functional/resource rehearsals, not a scaling comparison.
The 5-user run reached its Core limit. The 20-user run used a larger limit;
its kernel cgroup evidence records zero max/oom/oom-kill events for every
container. All owned resources were removed after each run. Host OS and other
services, long-duration soak, real audio traffic and real model inference are
excluded. Docker had 16 logical CPUs; the VPS has 2 vCPU.

The sanitized, hash-linked [measurement report](../artifacts/reports/microservices-stack-20261028.json)
contains artifact hashes, sample counts, per-component memory and kernel events.
Original data remains under the protected local `target/` directories above.

A read-only two-sample VPS check at 04:15 UTC found 953,704,448 bytes physical
RAM, at least 189,698,048 bytes available and up to 560,840,704 bytes swap used.
Core was active with restart count zero; Redis responded. PostgreSQL probing and
Prometheus scraping were unavailable in that bounded check, so the production
baseline is incomplete. No VPS state was changed.

## VPS deployment gates

The existing VPS has about 0.89 GiB physical RAM, and current baseline samples
show little available RAM plus substantial swap use. Complete Linux resource
measurements before selecting a RAM upgrade. Do not deploy extra services into
that remaining memory or assume managed databases remove all process overhead.
The owner selected upgrading the existing VPS to 2 GB on 28 October. This is a
deployment prerequisite and must be confirmed on the host; it is not evidence
that a 2 GB VPS has already passed acceptance.

After capacity is resolved, use the established main-branch release gates.
Provision separate runtime roles/config files, back up and verify restores,
deploy all three services, backfill the VPS's own history, compare shadow reads,
then enable remote reads. Verify health, existing practice/AI workflows, restart
recovery, database isolation and rollback on the actual host. Cloud inference
credentials stay in AI. The fine-tuned model remains local.

GitHub Actions artifact storage quota currently prevents evidence upload; a
workflow with a failed upload is not green CI. Fresh CPU R13 generation and
actual VPS rollout are still required before claiming the migration complete.
