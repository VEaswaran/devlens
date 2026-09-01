# DevLens

An MCP server that answers *"what does this repo do, what does it talk to, and who owns it"* —
without a knowledge-transfer session, and with a file and line number behind every fact.

DevLens statically extracts facts from source repositories (HTTP routes, Kafka topics, outbound
service calls, dependencies, owners, commits), stores them as provenance-tagged JSON, and serves
them to an AI agent over the [Model Context Protocol](https://modelcontextprotocol.io).

**It makes no LLM calls of its own.** The client is already a model with more context than the
server has, so DevLens returns facts and evidence, and the agent does the reasoning. This is the
main cost and correctness lever in the design.

---

## Status

| Capability | State |
|---|---|
| MCP server over STDIO | ✅ Working |
| `list_indexed_repos` | ✅ Working |
| `get_repo_metadata` (incl. cross-repo, field filtering) | ✅ Working |
| `search_code` — cross-repo inverted index (routes, topics, symbols) | ✅ Working |
| `refresh_repo_index` — full extraction engine + secret redaction | ✅ Working |
| Extraction Engine (Spring MVC, Kafka, Maven deps, CODEOWNERS, git log) | ✅ Working |
| tree-sitter native grammars (Java, JS, TS) load from the fat jar | ✅ Verified by test |
| End-to-end MCP contract tests against the packaged jar | ✅ Integration tests |
| Maven build | ✅ `mvn verify` |
| Gradle build | ✅ `./gradlew check` |
| Docker image | ✅ `Dockerfile` (Maven jar) · `Dockerfile.gradle` (Gradle jar) |
| GitHub Actions CI | ✅ Both build systems in parallel |
| Bitbucket webhook | ❌ Deferred — see `plan.md` §3 |

---

## Quickstart

### Prerequisites

- JDK 17+ (`java -version`)
- Maven **or** Gradle — the project ships a Gradle wrapper, so no Gradle installation is required

### Maven

```bash
mvn clean verify
java -jar target/devlens.jar
```

### Gradle

```bash
# Linux / macOS / WSL
./gradlew check
java -jar build/libs/devlens.jar

# Windows PowerShell
.\gradlew.bat check
java -jar build\libs\devlens.jar
```

Both produce an identical fat JAR. `check` / `verify` runs unit tests + builds the jar + runs integration tests.

### Index a repo

Once the server is running (or via the MCP client), call `refresh_repo_index`:

```json
{
  "name": "refresh_repo_index",
  "arguments": {
    "repo_id": "checkout-service",
    "repo_path": "/absolute/path/to/checkout-service",
    "repo_url": "https://bitbucket.org/org/checkout-service"
  }
}
```

Then query with `get_repo_metadata` or `search_code`.

---

## Design principles

1. **Answers must be verifiable.** Every fact carries `provenance` (`file`, `line`). Every response
   carries an `extraction_report` describing parse coverage, so the agent can qualify its answer
   instead of implying the fact list is exhaustive.
2. **No LLM in the server.** Static extraction and code search only. POC exit criterion: $0 of
   server-side inference spend.
3. **Deterministic over clever.** Full re-extraction rather than incremental patching; exact-match
   caching rather than semantic similarity. Both trade a little efficiency to eliminate a class of
   silent-wrongness bugs.
4. **Never guess.** No results is a valid, useful answer. Incomplete coverage is reported, not
   silently omitted.
5. **Minimal tool surface.** Four tools total, covering four distinct *intents* — not four data
   types. Every extra tool is another thing the model can choose wrongly.

Full rationale, including why the design changed from its first revision, is in
[`plan.md`](plan.md) §11.

---

## Quick start

### Prerequisites

- **JDK 17+** (Maven must run on 17 or later — check with `mvn -v`)
- **Maven 3.9+**
- No `git` binary needed (JGit is used), no Node toolchain, no native compiler — tree-sitter
  grammars ship precompiled

### Build

```bash
mvn clean verify
```

Produces a self-contained fat jar at `target/devlens.jar` (~12 MB — it embeds the tree-sitter
native libraries for Windows, Linux and macOS).

### Run

```bash
java -jar target/devlens.jar
```

The server speaks JSON-RPC over stdin/stdout and waits for an MCP client. Running it by hand in a
terminal is not very interesting; register it with a client instead.

### Register with an MCP client

```json
{
  "mcpServers": {
    "devlens": {
      "command": "java",
      "args": ["-jar", "C:/Users/vijay/CascadeProjects/DevLens/target/devlens.jar"],
      "env": {
        "DEVLENS_DATA_DIR": "C:/Users/vijay/CascadeProjects/DevLens/devlens-data"
      }
    }
  }
}
```

> **Use an absolute path for `DEVLENS_DATA_DIR`.** The default (`./devlens-data`) resolves against
> the *client's* working directory, not the project directory. A relative path will silently give
> you zero indexed repos, with no error.

Then ask the agent something like *"what Kafka topics does checkout-service consume?"* and it will
call `get_repo_metadata` and answer with file/line citations.

---

## Configuration

Configuration is environment-only, because an MCP client launches this as a subprocess — there is
no command line to speak of and no interactive prompt available.

| Variable | Default | Meaning |
|---|---|---|
| `DEVLENS_DATA_DIR` | `./devlens-data` | Root of the metadata store |

---

## Tools

### `list_indexed_repos`

No parameters. Reads only the manifest, never per-repo metadata files, so it stays cheap.

> List every repo DevLens has indexed, with its type, indexed branch and commit, and parse
> coverage. Call this first if you are unsure of the exact repo_id.

```json
{ "count": 1,
  "repos": [ { "repo_id": "checkout-service", "repo_type": "backend", "branch": "main",
               "indexed_commit": "abc1234...", "files_scanned": 412, "files_parsed": 405,
               "parse_failure_count": 7 } ] }
```

### `get_repo_metadata`

| Param | Type | Notes |
|---|---|---|
| `repo_ids` | `string[]` | Optional. **Omit to query every indexed repo** — this is how cross-repo questions are answered |
| `fields` | `string[]` | Optional. One or more of `apis`, `kafka`, `outbound_calls`, `dependencies`, `owners`, `recent_commits`, `repo_type` |

Making `repo_ids` an optional *list* is what turns a per-repo lookup into a cross-repo one:
*"which repos produce `order.created`?"* is simply `fields: ["kafka"]` with no `repo_ids`.

`fields` narrows the response, but identity and coverage keys (`repo_id`, `indexed_commit`,
`branch`, `schema_version`, `generated_at`, `extraction_report`) are **always** returned — without
them the caller cannot tell how complete the answer is or which commit it describes.

A lookup where no requested repo is indexed returns an **error**, not an empty success, so the
agent can distinguish "no such repo" from "repo has no such facts".

### `search_code` and `refresh_repo_index`

Specified in [`plan.md`](plan.md) §6, not yet registered. `search_code` will be a cross-repo
literal/symbol search returning ranked snippets with file and line — it replaces the natural-language
`query_repo` from the plan's first revision, which had a fallback tier that could only fabricate.

---

## Data model

The schema is the real contract, and it exists before the parsers do. Server-side layout:

```
devlens-data/
  manifest.json                  # backs list_indexed_repos
  repos/<repo_id>/metadata.json  # per-repo facts
  index/symbols.json             # planned: symbol -> [repo, file, line]
  index/topics.json              # planned: topic  -> [{repo, role, provenance}]
  index/routes.json              # planned: route  -> [{repo, method, provenance}]
  cache/answers.json             # planned: exact-match answer cache
```

The inverted indexes are what make cross-repo questions answerable **without a graph database**.

A fact looks like this — note that `provenance` is mandatory on every one:

```json
{
  "topic": "order.created",
  "role": "consumer",
  "group_id": "checkout-svc",
  "provenance": { "file": "src/main/resources/application.yml", "line": 17 }
}
```

And every document carries honest coverage reporting:

```json
"extraction_report": {
  "files_scanned": 412,
  "files_parsed": 405,
  "parse_failures": [ { "file": "legacy/vendor.min.js", "reason": "minified; skipped" } ],
  "extractors_run": ["spring-mvc-routes", "kafka-yaml", "maven-deps", "codeowners", "git-log"],
  "redactions": 3
}
```

`schema_version` is present from day one. There are no `confidence` floats anywhere: either a fact
was extracted with evidence or it wasn't.

See [`plan.md`](plan.md) §5 for the full schema and
[`devlens-data/repos/checkout-service/metadata.json`](devlens-data/repos/checkout-service/metadata.json)
for a complete worked example.

---

## Why this stack

Pinned in [`pom.xml`](pom.xml). Built on JDK 17 and verified running on both JDK 17 and JDK 26,
on Windows.

| Concern | Choice | Version |
|---|---|---|
| MCP protocol | `io.modelcontextprotocol.sdk:mcp` | 2.0.1 |
| Parsing | `io.github.bonede:tree-sitter` + grammars | 0.26.6 (java 0.23.5, javascript 0.25.0, typescript 0.23.2) |
| Git access | `org.eclipse.jgit` | 7.7.1 |
| JSON | Jackson 3 via the SDK's `McpJsonMapper` | BOM-managed |
| Packaging | `maven-shade-plugin` | 3.6.2 |

**On tree-sitter bindings:** use the `io.github.bonede` lineage, which ships precompiled natives
for all three platforms and targets Java 11+. Do *not* switch to the official
`io.github.tree-sitter:jtreesitter` — it requires JDK 23+ and bundles no grammars, meaning you must
build `tree-sitter` plus every grammar as native libraries and generate bindings with `jextract`.

**On newer JDKs:** tree-sitter loads its native library via `System.load`, a *restricted method*
as of JDK 24 — it warns on 24–25 and is slated to be blocked by default later. The jar manifest
declares `Enable-Native-Access: ALL-UNNAMED`, so `java -jar devlens.jar` stays clean and no client
has to pass a JVM flag. Do not drop that manifest entry.

**Accepted trade-off:** tree-sitter yields a syntax tree, not a resolved semantic model. It will not
follow a route path through a constant, resolve a re-export chain, or evaluate a decorator argument
built by string concatenation. Extraction is therefore *best-effort with declared coverage*, which
`extraction_report` makes measurable rather than pretending otherwise. Where an AST is the wrong
tool — Spring `@RequestMapping` inheritance, Kafka topics in `application.yml` — use a
config/annotation parser instead.

---

## Development

### Commands

```bash
mvn clean verify            # 21 unit tests + shaded jar + 8 integration tests
mvn clean test              # unit tests only (skips the client contract — see below)
mvn -q package -DskipTests  # jar only
```

**Use `mvn verify`, not `mvn test`.** The integration tests (`DevLensServerIT`) run after packaging
and launch the shaded jar as a real subprocess speaking JSON-RPC. They are the only place the
things that actually break in the field get checked: native library loading out of the uber-jar,
stdout purity, and the tool contract. To prove they aren't passing vacuously, point them at an
empty store and watch three fail:

```bash
mvn failsafe:integration-test -Ddevlens.it.dataDir=/path/that/does/not/exist
```

### Layout

```
src/main/java/com/devlens/
  DevLensServer.java          # entry point; STDIO transport, tool registration, agent instructions
  DevLensConfig.java          # environment-derived config
  store/MetadataStore.java    # metadata reads, field filtering, path-traversal guard
  tools/MetadataTools.java    # list_indexed_repos + get_repo_metadata
src/main/resources/
  simplelogger.properties     # forces all logging to stderr — see below
src/test/java/com/devlens/
  store/MetadataStoreTest.java             # filtering, unknown repos, path traversal
  extract/TreeSitterAvailabilityTest.java  # all 3 grammars load natively; row indexing contract
  it/DevLensServerIT.java                  # end-to-end MCP over a real subprocess
```

### Rules for contributors

These are not style preferences — each one corresponds to a bug that is easy to introduce and
unpleasant to diagnose.

**Never write to `System.out`.** On a STDIO transport, stdout *is* the JSON-RPC channel. A stray
`println` corrupts the stream and the client disconnects with an opaque parse error. All logging
goes to stderr, enforced by `simplelogger.properties`.

**Only register implemented tools.** See Status above.

**No LLM calls server-side.** This is a design invariant, not a current limitation.

**tree-sitter rows are zero-based.** Emitted provenance must be `row + 1`, so line numbers match
what an editor shows. Pinned by `TreeSitterAvailabilityTest`.

**`repo_id` becomes a path segment.** It is validated against an allowlist pattern *and*
containment-checked against the data directory. Do not relax either check — `MetadataStoreTest`
covers `../`, absolute paths, and Windows separators.

**Secret redaction is not deferrable.** Config parsers will happily lift `bootstrap.servers`, SASL
credentials and JDBC URLs into metadata that gets served over MCP. The redactor must land with the
first config parser, not after.

### Testing the protocol by hand

Piping a file into stdin **does not work** for tool calls: stdin hits EOF and the server shuts down
before draining queued requests, so responses go missing. Drive it with a live stdin stream, writing
one message at a time, in the order `initialize` → `notifications/initialized` → `tools/call`.

---

## Roadmap

Per [`plan.md`](plan.md) §9, in order:

0. **Collect ~30 real questions from the pilot teams and answer them by hand.** Produces the golden
   eval set *and* a ranked list of which facts are worth extracting. Needs a human, and it is the
   highest-leverage step in the project.
1. Schema + hand-written fixture ✅
2. **Extraction Engine** — highest-value extractors first, with the Secret Redactor
3. Overview renderer (`.devlens/OVERVIEW.md`, never overwriting a team's `README.md`)
4. MCP skeleton + the two read tools ✅
5. `search_code` + inverted indexes
6. Exact-match answer cache
7. `refresh_repo_index` (async) + webhook

Exit criteria for "POC approved" are enumerated in [`plan.md`](plan.md) §12 — headline numbers are
≥80% golden-set accuracy with **zero fabrications**, ≥95% provenance accuracy, and zero secrets in
emitted metadata.

---

## Documentation map

| File | Contents |
|---|---|
| [`plan.md`](plan.md) | Design, architecture, tool specs, schema, build order, exit criteria, open questions |
| [`AGENTS.md`](AGENTS.md) | Build/run commands, dependency gotchas, hard constraints — read before editing |
| `README.md` | This file |

### Open questions needing a decision

Tracked in [`plan.md`](plan.md) §13. The ones that block real progress:

- Which branch per repo is indexed? (assumed: default branch)
- Should `.devlens/` metadata be committed back into each repo at all? Server-side-only eliminates
  write credentials, webhook loop guards and merge conflicts in one stroke.
- Who supplies the 30 golden questions per pilot repo?
- Is one MFE in the pilot set, or should the POC be backend + BFF only? React Router extraction is
  materially harder than Spring route extraction.
