# DevLens MCP Server — POC Implementation Plan

> Revision 2. Changes from rev 1 are called out in §11 so reviewers of the original can see what moved and why.

## 0. What DevLens Is For

A new engineer, or an AI agent acting on their behalf, should be able to answer "what does this repo do, what does it talk to, and who owns it" **without a knowledge-transfer session** — and every answer should point at the file and line it came from.

Two consequences shape the whole design:

1. **Answers must be verifiable.** Every extracted fact carries provenance (`file`, `line`, `commit`). An unverifiable answer is worse than no answer, because it gets trusted.
2. **The client is already an LLM.** DevLens is a *retrieval and extraction* service, not a reasoning service. It returns facts and code evidence; the calling agent composes the prose. This is the main cost lever — see §3.

---

## 1. Design Principles

**Minimal tool surface.** Every extra tool is another thing the LLM has to choose correctly between. Collapse to the fewest tools that cover distinct *intents*, not distinct data types.

**No LLM in the server.** Static extraction + code search only. If a question can't be answered from facts + snippets, DevLens returns the evidence and says so rather than generating prose.

**Deterministic over clever.** Full re-extraction instead of incremental patching; exact-match caching instead of semantic similarity. Both trade a little efficiency for the elimination of a class of silent-wrongness bugs. Revisit only with measurements in hand.

**Never guess.** If coverage is incomplete or a parser failed on a file, that is reported in the response, not silently omitted.

### Tool set — 4 tools

| Tool | Intent | Notes |
|---|---|---|
| `list_indexed_repos` | *What exists?* | Cheap manifest read so the agent doesn't guess repo names |
| `get_repo_metadata` | *Read a known fact* | Structured JSON, optional `fields` filter, optionally multiple repos |
| `search_code` | *Where is X? / who else does X?* | Cross-repo. Returns ranked snippets with file+line. Replaces rev 1's `query_repo` |
| `refresh_repo_index` | *Update the index* | Async, returns a job handle |

Four tools cover: *discover*, *read facts*, *find evidence*, *update*. Extraction, indexing, rendering and caching stay internal.

---

## 2. Requirements

**Functional**
- Answer factual questions about a repo from structured metadata, with provenance
- Answer "where / who else" questions across repos via a code + symbol index
- Commit-triggered re-index of repo metadata
- Repo classification (MFE / BFF / backend / mixed) — deterministic, used only to select parsers
- Storage: JSON, server-side as source of truth; optionally mirrored into the repo under `.devlens/`

**Non-functional**
- Minimize cost — **zero LLM calls in the server** for the POC
- Fast repeat queries (exact-match cache)
- Every fact traceable to file + line + commit
- No knowledge-transfer dependency

**Constraints**
- Bitbucket repos, file-based storage, minimal infra
- Small number of MCP tools
- Java / Maven implementation (see §4)

**Explicit non-goals for the POC** — see §10.

---

## 3. Architecture

```
                      ┌──────────────────────────────────┐
                      │        DevLens MCP Server        │
                      │                                  │
  Bitbucket ─────────▶│  Webhook handler                 │
  (commit push)       │   (sig-verified, bot-loop guard) │
                      │        │                         │
                      │        ▼                         │
                      │  Index Job Queue (async)         │
                      │        │                         │
                      │        ▼                         │
                      │  Extraction Engine               │
                      │  (tree-sitter, no LLM)           │
                      │        │                         │
                      │        ├──▶ Secret Redactor      │
                      │        ▼                         │
                      │  repo-metadata.json  ◀── server-side, source of truth
                      │  + cross-repo inverted index     │
                      │        │                         │
                      │        ├──▶ Overview renderer ──▶ .devlens/OVERVIEW.md
                      │        │      (pure template)     (mirrored to repo, optional)
                      │        ▼                         │
  LLM / Agent ──MCP──▶│  Read layer                      │
  (Claude, etc.)      │   • metadata reads (fields)      │
                      │   • code/symbol search           │
                      │   • exact-match answer cache     │
                      └──────────────────────────────────┘
```

**Why the read path is server-side, not repo-side:** queries never need a clone, a checkout, or write permission, and the metadata for all repos is co-located so cross-repo questions are answerable. The in-repo `.devlens/` copy is a *mirror for humans browsing Bitbucket*, not the query substrate. This decoupling means the whole POC can ship with repo mirroring turned off if teams object.

---

## 4. Implementation Stack

Decision: **Java + tree-sitter**, single JVM process.

Pinned in `pom.xml` and verified building and running on JDK 17 / Windows:

| Concern | Choice | Version | Rationale |
|---|---|---|---|
| MCP server | `io.modelcontextprotocol.sdk:mcp` | 2.0.1 | Official Java SDK; STDIO transport needs no web framework |
| Parsing | `io.github.bonede:tree-sitter` + grammar artifacts | 0.26.6 (+ java 0.23.5, javascript 0.25.0, typescript 0.23.2) | Ships **precompiled cross-platform natives**, Java 11+ |
| JSON | Jackson 3, via the `mcp` bundle's `McpJsonMapper` | managed by BOM | Avoids a second JSON stack |
| Git | `org.eclipse.jgit` | 7.7.1 | Pure Java, no `git` binary dependency |
| Packaging | `maven-shade-plugin` fat jar | 3.6.2 | Client launches `java -jar devlens.jar`; natives must be inside |
| Webhook (later) | Spring Boot + `mcp-spring-webmvc`, or plain `com.sun.net.httpserver` | — | Defer; STDIO is enough until webhooks are real |

Operational constraints that follow from this stack are recorded in `AGENTS.md` — most importantly
that **stdout is the JSON-RPC channel**, so nothing may ever be printed to it.

**Do not use the official `io.github.tree-sitter:jtreesitter`.** It requires JDK 23+ (FFM API) and ships no grammars — you must build `tree-sitter` plus each grammar as native libraries and generate bindings with `jextract`. The `bonede` lineage (or the `BrokkAi/tree-sitter-ng` fork, which adds null-safety and faster JNI traversal) avoids all of that.

Add dependencies via `mvn versions:use-latest-releases` / explicit pinned versions. **Pin exact versions, no ranges, and prefer releases published at least 7 days ago.**

**Accepted trade-off:** tree-sitter gives a syntax tree, not a resolved semantic model. It will not follow a route path through a constant, resolve a re-export chain, or evaluate a decorator argument built by string concatenation. Extraction is therefore *best-effort with declared coverage* — §7 makes that measurable rather than pretending otherwise. Where tree-sitter is too coarse (Spring `@RequestMapping` inheritance, Kafka topics from `application.yml`), use a **config/annotation parser** instead; not everything needs an AST.

---

## 5. Data Model

The schema is the real contract and comes **before** any parser. Sketch:

```json
{
  "schema_version": 1,
  "repo_id": "checkout-service",
  "repo_url": "https://bitbucket.org/org/checkout-service",
  "branch": "main",
  "indexed_commit": "abc123...",
  "generated_at": "2026-08-28T10:00:00Z",
  "repo_type": { "value": "backend", "evidence": ["pom.xml: spring-boot-starter-web"] },

  "apis": [
    { "method": "POST", "path": "/api/v1/checkout",
      "handler": "CheckoutController.create",
      "provenance": { "file": "src/main/java/.../CheckoutController.java", "line": 42 } }
  ],
  "kafka": [
    { "topic": "order.created", "role": "consumer", "group_id": "checkout-svc",
      "provenance": { "file": "src/main/resources/application.yml", "line": 17 } }
  ],
  "outbound_calls": [
    { "target": "payments-service", "kind": "http", "url_template": "/payments/{id}",
      "provenance": { "file": "...", "line": 88 } }
  ],
  "dependencies": [ { "name": "spring-boot", "version": "3.2.1", "scope": "compile" } ],
  "owners": [ { "pattern": "/src/api/", "owners": ["@team-checkout"], "source": "CODEOWNERS" } ],
  "recent_commits": [ { "hash": "abc123", "author": "...", "date": "...", "subject": "..." } ],

  "extraction_report": {
    "files_scanned": 412,
    "files_parsed": 405,
    "parse_failures": [ { "file": "legacy/weird.js", "reason": "syntax error at 1:1" } ],
    "extractors_run": ["spring-mvc", "kafka-yaml", "codeowners", "maven-deps"],
    "redactions": 3
  }
}
```

Rules:
- `schema_version` from day one — you will migrate.
- **Every fact has `provenance`.** No exceptions. This is what makes the agent's answer checkable.
- `extraction_report` is returned to the caller so it can qualify its answer ("DevLens parsed 405/412 files; 7 failed").
- No `confidence` floats anywhere. Either a fact was extracted with evidence or it wasn't.

Server-side layout:

```
devlens-data/
  manifest.json                  # for list_indexed_repos
  repos/<repo_id>/metadata.json
  index/symbols.json             # symbol -> [repo_id, file, line]
  index/topics.json              # topic  -> [{repo_id, role, provenance}]
  index/routes.json              # path pattern -> [{repo_id, method, provenance}]
  cache/answers.json
```

The inverted indexes are what make cross-repo questions ("who else consumes `order.created`?") answerable with **no graph database**.

---

## 6. Tool Specifications

### `list_indexed_repos`
```json
{
  "name": "list_indexed_repos",
  "description": "List every repo DevLens has indexed, with its type, indexed branch and commit. Call this first if you are unsure of the exact repo_id.",
  "input_schema": { "type": "object", "properties": {} }
}
```
Returns `[{ repo_id, repo_type, branch, indexed_commit, indexed_at, files_parsed, parse_failures }]`. Reads `manifest.json` only — never opens per-repo metadata.

### `get_repo_metadata`
```json
{
  "name": "get_repo_metadata",
  "description": "Return structured, provenance-tagged facts about one or more indexed repos: HTTP APIs, Kafka topics, outbound service calls, dependencies, owners, recent commits. Use this whenever the question maps to one of these known fields. Every fact includes the file and line it was extracted from.",
  "input_schema": {
    "type": "object",
    "properties": {
      "repo_ids": { "type": "array", "items": { "type": "string" },
                    "description": "One or more repo_ids. Omit to query all indexed repos (use with 'fields' to keep the response small)." },
      "fields": { "type": "array",
                  "items": { "enum": ["apis","kafka","outbound_calls","dependencies","owners","recent_commits","repo_type","extraction_report"] },
                  "description": "Optional. Omit to return everything for the named repos." }
    }
  }
}
```
Pure file read. `repo_ids` accepting a list (and being optional) is what turns this into a cross-repo tool: *"which repos produce `order.created`?"* is `fields: ["kafka"]` with no `repo_ids`. Response always carries `extraction_report` coverage so the caller can qualify its answer.

### `search_code`
```json
{
  "name": "search_code",
  "description": "Search indexed repo source code and symbols for a literal or regex pattern. Use this when the answer is not a field in get_repo_metadata — e.g. locating an implementation, finding all callers of a function, or tracing a config value. Returns ranked code snippets with repo, file and line. Returns no results rather than guessing.",
  "input_schema": {
    "type": "object",
    "properties": {
      "query":     { "type": "string" },
      "repo_ids":  { "type": "array", "items": { "type": "string" }, "description": "Optional. Omit to search all indexed repos." },
      "kind":      { "enum": ["text","symbol"], "default": "text" },
      "path_glob": { "type": "string", "description": "Optional filter, e.g. 'src/**/*.java'" },
      "max_results": { "type": "integer", "default": 30 }
    },
    "required": ["query"]
  }
}
```
Returns `{ results: [{ repo_id, file, line, snippet, symbol? }], truncated: bool }`.

**This replaces rev 1's `query_repo`, and deliberately drops the natural-language interface.** Rationale in §11.1 — the short version is that the calling model is better at decomposing a question into searches than a server-side heuristic is, and this tool cannot fabricate.

### `refresh_repo_index`
```json
{
  "name": "refresh_repo_index",
  "description": "Queue a re-index of a repo. Returns immediately with a job_id; indexing runs in the background. Poll status via list_indexed_repos (indexed_commit advances on success).",
  "input_schema": {
    "type": "object",
    "properties": {
      "repo_id": { "type": "string" },
      "ref":     { "type": "string", "description": "Branch or commit. Defaults to the repo's configured indexed branch." }
    },
    "required": ["repo_id"]
  }
}
```
Returns `{ job_id, status: "queued", repo_id }`. **Async is not optional** — a synchronous clone-and-parse of a large repo will blow the MCP client's tool timeout.

### Tool-boundary rule (important for tool-selection accuracy)

The descriptions above draw one crisp line: **known field → `get_repo_metadata`; unknown location → `search_code`.** No hedging language ("use this before that…", "if the question isn't a direct lookup…"), because vague tool descriptions are a top cause of wrong tool choice.

---

## 7. Internal Components

**Extraction Engine** — per-language extractors selected by deterministic repo classification:
- *Routes*: Spring annotations (Java), Express/Nest/Fastify route registration (JS/TS), React Router config (MFE)
- *Kafka*: `application.yml`/`.properties` + `@KafkaListener` + `KafkaTemplate` sends; `kafkajs` config for Node
- *Outbound calls*: `RestTemplate`/`WebClient`/`FeignClient` targets, `fetch`/`axios` base URLs
- *Dependencies*: `pom.xml`, `build.gradle`, `package.json` — plain parsing, no AST
- *Owners*: `CODEOWNERS`
- *Recent commits*: JGit log, last 15
- Each extractor emits facts **with provenance** and appends to `extraction_report`

**Repo Classifier** — deterministic rules over manifest files (`pom.xml` + `spring-boot-starter-web` → backend; `package.json` + `react` + no server entrypoint → MFE; both → mixed). Records the evidence string. **No probabilistic score** — the output only chooses which extractors run, so a confidence number would be unactionable.

**Secret Redactor** — runs on every extracted value *before* it is persisted or served. Config parsers will otherwise lift `bootstrap.servers`, SASL credentials and JDBC URLs into a JSON that gets committed and served over MCP. Pattern-based (high-entropy strings, known key names, URLs with credentials) + explicit allowlist. Counted in `extraction_report.redactions`. **This is not deferrable.**

**Indexer** — full re-extraction per commit, skipping unchanged files by content hash; rebuilds the inverted indexes. Deleting stale entries is trivially correct because nothing is patched in place.

**Overview Renderer** — pure JSON → Markdown template. No LLM. Writes `.devlens/OVERVIEW.md`.

**Answer Cache** — keyed on `sha256(tool_name + normalized_args)` → result, invalidated by a **fingerprint of the metadata sections the answer read**, not by repo commit hash. A formatting-only commit therefore does not flush the cache. **Exact-match only for the POC**; no similarity threshold.

**Repo Mirror Writer** (optional, off by default) — commits `.devlens/metadata.json` + `.devlens/OVERVIEW.md` back. Guards: dedicated bot author, `[skip ci]` in the message, **skip webhook events authored by the bot** (loop guard), single branch only, and an `OVERVIEW.md` that is a *separate file* — it must never overwrite a team's `README.md`. If README integration is wanted, inject between `<!-- devlens:start --> / <!-- devlens:end -->` markers.

---

## 8. Data Flows

**Index**
```
1. Webhook (sig-verified) or manual refresh_repo_index → job queued
   → if commit author == devlens-bot: drop (loop guard)
2. Fetch/update working copy of the configured branch (JGit)
3. Classify repo → select extractors
4. Full re-extraction, skipping files whose content hash is unchanged
5. Secret Redactor over all extracted values
6. Write repos/<repo_id>/metadata.json + rebuild inverted indexes + manifest
7. Invalidate cache entries whose section fingerprints changed
8. (Optional) Overview Renderer + Repo Mirror Writer commit back
```

**Query — note there is no fallback ladder any more; the agent drives**
```
Agent: "what Kafka topics does checkout-service consume?"
  → get_repo_metadata(repo_ids:["checkout-service"], fields:["kafka"])
  → facts + provenance + coverage report → agent answers, cites files

Agent: "who else consumes order.created?"
  → get_repo_metadata(fields:["kafka"])            # all repos, one field
  → filter server-side by topic via topics.json

Agent: "where is the retry backoff configured?"
  → search_code(query:"backoff", repo_ids:["checkout-service"])
  → ranked snippets → agent reads and explains

Nothing found?
  → empty result + extraction_report. The agent tells the user DevLens
    has no evidence. It does not invent an answer, and neither does DevLens.
```

---

## 9. Build Order

**0. Wizard-of-Oz validation (before any code).** Collect ~30 real questions from the pilot teams. Answer them **by hand** against 3–5 real repos (include one messy brownfield repo). Record, for each: which field or search would have answered it. Output is a **golden set** plus a ranked list of facts actually worth extracting. This is a day of work that can save weeks of building parsers for facts nobody asks about.

**1. JSON schema + golden set fixture.** Write `schema_version: 1` and the expected metadata for one pilot repo *by hand*. This is the contract, and the fixture the extractors must reproduce.

**2. Extraction Engine.** Highest-value extractors first, per step 0's ranking. Success = reproducing the hand-written fixture. Secret Redactor lands with the first config parser, not after.

**3. Overview renderer.** Prove JSON → Markdown produces something a new joiner can actually read. Ship as `.devlens/OVERVIEW.md`, mirroring off.

**4. MCP skeleton + `list_indexed_repos` + `get_repo_metadata`.** Pure reads, no cache. Point Claude at it over STDIO and run the golden set.

**5. `search_code` + inverted indexes.** Now the cross-repo questions become answerable.

**6. Cache.** Only after observing real query patterns.

**7. `refresh_repo_index` async + manual trigger / git hook.** Real webhook infra last, once extraction is trusted.

Steps 0–1 before 2 is the important reordering: **the schema and the eval set precede the parsers.**

---

## 10. Deliberately Deferred

| Deferred | Note |
|---|---|
| Central DB | Files are fine at POC scale |
| Redis | In-process cache is fine |
| Graph layer (CodeGraph/Graphify) | Inverted indexes cover most cross-repo questions. Add a graph when you can point at logged queries `search_code` handled badly |
| Semantic / embedding search | Add only when the golden set shows literal + symbol search failing |
| Server-side LLM narrative generation | The calling agent does this. Revisit only if agents prove bad at it |
| Multi-branch indexing | Configured branch per repo only |
| Draft-PR human gate for mirrored files | Mirroring is off by default, so the gate isn't needed yet |
| Coverage policy (report-only vs gate-aware) | **Cut from the POC** — a separate product from "understand this repo" |
| Permission-aware filtering | Fine for a single-team POC. **Must** be designed before any multi-team rollout — a cross-repo search tool is exactly where this bites |

Deferred but **not** optional at POC: secret redaction, webhook signature verification, bot-commit loop guard, `repo_id` path-traversal validation.

---

## 11. Rationale for the Changes from Rev 1

**11.1 `query_repo` → `search_code`; tier-3 web search removed.** Rev 1's resolution ladder ended in `web_search`. The web does not know what a private `checkout-service` consumes, so that tier could only produce plausible fabrication — labelled `source: "global_search"`, which reads as sourced. Tier 3 is now local code search returning file+line evidence. And since tiers 1–2 were really "cache" and "read the JSON", the natural-language wrapper added a routing heuristic without adding capability. The client model routes better and can iterate; `search_code` cannot fabricate.

**11.2 No LLM in the server.** The only consumer is an LLM with more context than the server has. Server-side generation pays for inference twice, discards the caller's context, and can't be corrected. Removing it also deletes the narrative-regeneration path and the Structural Change Detector, which existed *only* to gate that regeneration.

**11.3 Incremental JSON patching → full re-extraction.** Parsing one repo is seconds of CPU. Patching only changed sections bought almost nothing and introduced stale-entry bugs on deletes, renames and moves, plus per-field `last_changed_commit` bookkeeping. Full re-extraction with content-hash skipping is simpler and correct by construction. One component deleted.

**11.4 Cache key: commit hash → section fingerprint.** Keying on `last_indexed_commit` flushed the entire repo cache on a typo fix, which would have made rev 1's own "measurable cache-hit rate" success criterion read near-zero on any active repo.

**11.5 Semantic cache → exact-match.** Near-duplicate matching needs a similarity threshold, and a false hit serves a confidently wrong cached answer. Slow beats wrong.

**11.6 `README.md` → `.devlens/OVERVIEW.md`, server-side source of truth, mirroring off by default.** Rev 1 auto-committed to the repo, which fires the webhook, which re-indexes, which commits — with no loop guard specified. Beyond that: metadata JSON conflicts on every long-lived branch, and overwriting a team's README is how this gets rejected by the people it's for. Making the server-side copy authoritative also means the read path needs no clone and no write credentials.

**11.7 Provenance everywhere; `confidence` floats removed.** Rev 1 surfaced a classifier confidence in `list_indexed_repos` that no caller could act on. File+line evidence is both more useful and cheaper to produce.

**11.8 Cross-repo reads.** Rev 1 required a single `repo_id` on every tool, but the questions that justify the product — "who consumes this topic", "what breaks if I change this endpoint" — are inherently cross-repo. `repo_ids` is now an optional list on both read tools, backed by inverted indexes rather than a graph DB.

**11.9 Classifier confidence and coverage policy cut.** `repo_type` only selects extractors, so it can be deterministic. Coverage gating is unrelated to the core value proposition.

**11.10 Schema and eval set moved ahead of parsers.** Rev 1's build order started with extraction against a schema the document never specified, and its exit criteria ("all 4 tools respond correctly") weren't measurable.

---

## 12. POC Exit Criteria

Concrete, measurable — replaces rev 1 §9.

| # | Criterion | Target |
|---|---|---|
| 1 | **Golden set accuracy.** Agent + DevLens answers the ~30 questions from step 0 | ≥ 80% correct, **0 fabrications**, remainder correctly refused as "no evidence" |
| 2 | **Provenance.** Sampled facts point at the right file and line | ≥ 95% of a 50-fact sample verified by hand |
| 3 | **Coverage.** Files parsed vs scanned across the 5 pilot repos | ≥ 90% parsed; failures enumerated, not hidden |
| 4 | **Freshness.** Time from commit push to updated metadata | < 2 min for the largest pilot repo |
| 5 | **Cost.** Server-side LLM spend per commit | **$0** by construction — assert it |
| 6 | **Cache.** Hit rate on exact-repeat tool calls over a week of real use | measured and reported (no target — this is the baseline) |
| 7 | **Secrets.** Redactor run against a repo with deliberately planted credentials | 0 secrets present in emitted metadata |
| 8 | **Overview quality.** A new joiner reads `OVERVIEW.md` for one pilot repo | can name the repo's APIs, topics and owners unaided |

A 5th tool (e.g. `trace_dependency`) is justified only when logged `search_code` calls show a class of question it handles badly — not speculatively.

---

## 13. Open Questions

1. **Which branch per repo?** Assumed default branch. Confirm no team needs per-branch metadata for the POC.
2. **Where does the server-side working copy live**, and does DevLens get read-only Bitbucket credentials scoped to the pilot repos?
3. **Is repo mirroring (`.devlens/` commits) wanted at all for the POC**, or is server-side-only acceptable? Server-side-only removes write credentials, loop guards and merge conflicts entirely.
4. **Who supplies the 30 golden questions?** This is the single highest-leverage input and needs a named owner per pilot repo.
5. **MFE scope.** React Router config extraction is materially harder than Spring route extraction. Is one MFE in the pilot set enough, or should the POC be backend + BFF only?
