# DevLens — Project Notes

MCP server exposing statically extracted, provenance-tagged facts about source repositories.
Design and roadmap live in `plan.md`; this file is build/operational knowledge only.

## Commands

Both Maven and Gradle build systems are fully supported and produce identical fat JARs.

### Maven
```bash
mvn clean test           # unit tests only  (excludes *IT)
mvn clean verify         # unit + fat jar + integration tests  ← preferred
mvn -q package -DskipTests
```

### Gradle
```bash
./gradlew test           # unit tests only  (excludes *IT)
./gradlew check          # unit + fat jar + integration tests  ← preferred
./gradlew shadowJar      # fat jar only (build/libs/devlens.jar)

# Windows PowerShell
.\gradlew.bat check
```

**The `check` / `verify` target is not optional.** The integration tests (`*IT`) run after
packaging and are the only thing that exercises the real client contract: launching the fat jar
as a subprocess, native library loading, and stdout purity. None of that is observable from unit
tests.

To confirm the ITs are not passing vacuously, point them at an empty store (three should fail):

```bash
# Maven
mvn failsafe:integration-test -Ddevlens.it.dataDir=C:/nonexistent

# Gradle
./gradlew integrationTest -Pdevlens.it.dataDir=C:/nonexistent
```

Both builds target JDK 17. Confirm with `java -version` or `mvn -v` / `./gradlew -v`.

## Running it

The server speaks MCP over **STDIO**:

```bash
java -jar target/devlens.jar
```

Configuration is environment-only, because an MCP client launches this as a subprocess:

| Variable | Default | Meaning |
|---|---|---|
| `DEVLENS_DATA_DIR` | `./devlens-data` | Root of the metadata store (`plan.md` §5) |

**Always set `DEVLENS_DATA_DIR` to an absolute path** when registering with a client. The default
is relative to the client's working directory, not the project, so a relative path silently yields
zero indexed repos.

MCP client registration:

```json
{
  "mcpServers": {
    "devlens": {
      "command": "java",
      "args": ["-jar", "C:/Users/vijay/CascadeProjects/DevLens/target/devlens.jar"],
      "env": { "DEVLENS_DATA_DIR": "C:/Users/vijay/CascadeProjects/DevLens/devlens-data" }
    }
  }
}
```

If using the Gradle-built JAR, replace the path with:
`"C:/Users/vijay/CascadeProjects/DevLens/build/libs/devlens.jar"`

## Hard constraints — read before editing

**Never write to `System.out`.** On a STDIO transport stdout *is* the JSON-RPC channel. A stray
`println` corrupts the stream and the client disconnects with an opaque parse error. All logging
goes to stderr, enforced by `src/main/resources/simplelogger.properties`.

**Only register implemented tools.** A tool that returns "not implemented" is worse than an absent
tool, because the model keeps selecting it and burning turns. All four tools defined in `plan.md`
§6 are now implemented and registered: `list_indexed_repos`, `get_repo_metadata`, `search_code`,
`refresh_repo_index`.

**No LLM calls server-side.** By design (`plan.md` §1) — the client is already a model with more
context. Keeping this true is POC exit criterion #5.

**tree-sitter rows are zero-based.** Emitted provenance must be `row + 1` so line numbers match
what an editor shows. Pinned by `TreeSitterAvailabilityTest`.

**`repo_id` becomes a path segment.** It is validated against an allowlist pattern *and*
containment-checked. Do not relax either check; see `MetadataStoreTest`.

**Keep `Enable-Native-Access: ALL-UNNAMED` in the shaded jar manifest.** tree-sitter calls
`System.load`, which became a *restricted method* in JDK 24: it prints a multi-line warning on
JDK 24–25 and is slated to be **blocked by default** in a future release. The manifest entry (set
by the shade plugin's `ManifestResourceTransformer`) means clients do not need to pass a JVM flag.
Pinned by `DevLensServerIT.nativeAccessIsDeclaredSoFutureJdksDoNotBlockTreeSitter`.

Note the warning goes to stderr, so it does not corrupt the protocol — but it is noise in every
client's logs, and the future hard failure would be fatal.

## Dependency notes

- **tree-sitter**: use `io.github.bonede:tree-sitter*`, which bundles precompiled natives for
  win/linux/mac and targets Java 11+. Do **not** switch to the official
  `io.github.tree-sitter:jtreesitter` — it needs JDK 23+ and bundles no grammars, so you would
  have to build each grammar natively and run `jextract`.
- **MCP SDK 2.0.x** uses Jackson 3 (`mcp` bundle = `mcp-core` + `mcp-json-jackson3`). Prefer
  `McpJsonMapper` over adding a second Jackson.
- Builder API gotcha: it is `McpServer.sync(...).tools(spec, spec)` (varargs), not `.tool(...)`.
- `Tool.builder(name, jsonMapper, schemaString)` is the overload that accepts a raw JSON Schema
  string. Schemas are meta-schema validated at `build()`, so a malformed schema fails fast.
- Tool inputs are validated against `inputSchema` before the handler runs, so handlers do not need
  to re-validate enum/type constraints.
- Maven Central's solr search index (`search.maven.org/solrsearch`) returns stale results for some
  of these artifacts. Query `https://repo1.maven.org/maven2/<group/path>/<artifact>/maven-metadata.xml`
  instead. Note `release` may point at an alpha/beta for Maven plugins — filter those out.
- Pin exact versions, no ranges, and prefer releases published at least 7 days ago.

## Verifying the protocol by hand

Prefer `mvn verify` — `DevLensServerIT` already automates this. If you do drive it manually:

- Piping a file into stdin does **not** work for tool calls: stdin hits EOF and the server shuts
  down before draining queued requests, so responses go missing. Use a live stdin stream, writing
  one message at a time.
- Order matters: `initialize` → `notifications/initialized` → `tools/call`.
- Do not `Kill()` the process before reading stdout; buffered output is lost and it looks like the
  server dropped requests. Close stdin and let it exit.

## Known cruft

`src/main/resources/archetype-resources/` and `src/main/resources/META-INF/maven/archetype.xml`
are leftovers from the Maven archetype this project was scaffolded from. They are not used and get
packaged into the jar as dead weight; safe to delete.
