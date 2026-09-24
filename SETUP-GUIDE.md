# Sheaf — Setup Guide

Everything you need to get the monorepo building, the tests passing, and the add-in running in Excel.

---

## Prerequisites

| Tool | Version | Why |
|------|---------|-----|
| Java (Temurin) | 21 | Spring Boot service + IR type hierarchy |
| Maven | 3.9+ | Build + schema generation |
| Node.js | 22 | Add-in bundler (webpack) |
| npm | 10+ | Package manager |
| Excel | 2019 / 365 | Target host for the add-in |

Verify each before continuing:

```bash
java -version
mvn -version
node -version
npm -version
```

---

## Repository Layout

```
Sheaf/
├── corpus/          # Six CSV fixtures + questions.md — M-0 design corpus
├── docs/            # ir-spec.md, diagnostics.md
├── contract/        # Generated artefacts (committed snapshot)
│   ├── schema/      #   plan.schema.json  — written by Maven
│   └── types/       #   plan.d.ts, catalog.d.ts — written by npm
├── service/         # Spring Boot 3.3 / Java 21 backend
└── addin/           # React 18 / Fluent UI v9 Office Add-in
```

`contract/` is checked in so CI can detect drift without running both builds locally.

---

## 1 — Build the Service

```bash
cd service
mvn --batch-mode verify
```

This compiles the Java IR and catalog types, runs the tests (ArchUnit layering rules, MockMvc endpoint tests, catalog policy tests), and generates `contract/schema/plan.schema.json` and `contract/schema/catalog.schema.json` at the `prepare-package` phase.

Every record component is `required` in the generated schema unless it is annotated `@Nullable` (`com.sheaf.domain.common.Nullable`). That is what makes the generated TypeScript types strict.

**Expected output (abbreviated):**

```
[INFO] Tests run: 13, Failures: 0, Errors: 0
[INFO] BUILD SUCCESS
```

### Windows 11 + Java 21 NIO issue

If you start the service with `java -jar` you may see:

```
java.net.SocketException: Invalid argument
    at sun.nio.ch.UnixDomainSockets.connect0
```

Java 21's `WEPollSelectorImpl` uses Unix domain sockets for internal Tomcat wakeup pipes, and it creates them in the temp directory. **The usual cause is a space in that path** (for example `C:\Users\First Last\AppData\Local\Temp`). Point the sockets at any existing directory without spaces:

```bash
java -Djdk.net.unixdomain.tmpdir=D:/tmp -jar target/sheaf-service-0.1.0-SNAPSHOT.jar --spring.profiles.active=https
```

The same flag works with the Maven launcher (`-Dspring-boot.run.jvmArguments=-Djdk.net.unixdomain.tmpdir=D:/tmp`). The older workaround below also works through Maven on some machines:

```bash
mvn spring-boot:run -Dspring-boot.run.jvmArguments=-Djdk.nio.enableUnixDomainSpecialFiles=false
```

The endpoint tests use MockMvc (in-process, no real server socket), so `mvn verify` is unaffected on all platforms.

---

## 2 — Install Add-in Dependencies

```bash
cd addin
npm install
```

If you see `ECONNRESET` errors (transient network failure), simply retry. The install usually succeeds on the second or third attempt.

> **Package name note:** The Office Add-in tools ship as `office-addin-debugging`, `office-addin-dev-certs`, and `office-addin-manifest` — without any `@microsoft/` scope prefix. The `package.json` already reflects this; do not add the prefix.

---

## 3 — Generate the TypeScript Contract Types

The TypeScript types are derived from the Java IR, not written by hand. After any change to the Java domain types, regenerate both sides and commit:

```bash
# Step 1 — regenerate the JSON Schema from Java
cd service
mvn prepare-package

# Step 2 — regenerate the TypeScript types from the JSON Schema
cd ../addin
npm run generate-types
```

The updated files are in `contract/`. Check them in so the snapshot stays current.

The add-in imports these types (`@sheaf/contract` for plans, `@sheaf/contract/catalog` for the workbook catalog), so a Java change that isn't regenerated breaks the add-in's own type-check.

---

## 4 — Type-check, Lint and Test the Add-in

```bash
cd addin
npm run typecheck
npm run lint
npm test
```

All three must pass before the service tests count as a clean build. `npm test` runs Vitest over the pure catalog code in `src/catalog/` (no Office dependency). The golden tests read the fixtures in `../corpus/`, and one test checks that a 50,000-row sheet catalogues in under 2 seconds.

> **Fluent UI note:** The add-in `tsconfig.json` sets `"skipLibCheck": true`. This is required: Fluent UI v9's own type declarations have internal inconsistencies that break strict-mode checking in third-party consumers. Skipping lib checks is the recommended workaround; it does not weaken checks on `addin/src/**`.

---

## 5 — Run the Add-in Dev Server

First, install HTTPS dev certs (one-time):

```bash
cd addin
npx office-addin-dev-certs install
```

Then start the webpack dev server:

```bash
npm run start:web
```

The server listens on `https://localhost:3000`. The manifest points there for `taskpane.html`.

To sideload in Excel Desktop:

1. Open Excel.
2. Insert → Add-ins → My Add-ins → Upload My Add-in.
3. Browse to `addin/manifest.xml`.
4. Click Upload.

The "Ask Sheaf" button appears in the Home ribbon. Clicking it opens the task pane.

---

## 6 — Run the Full Stack Locally

The pane calls the service over HTTPS on `https://localhost:8443`, using the same dev certificate as the add-in (`~/.office-addin-dev-certs/`, installed in step 5). HTTPS is on from now on because API keys will be entered in the pane in M5, and they must never travel over plain HTTP.

Terminal 1 — service, with the `https` profile:

```bash
cd service
mvn spring-boot:run -Dspring-boot.run.profiles=https -Dspring-boot.run.jvmArguments=-Djdk.nio.enableUnixDomainSpecialFiles=false
```

If your certificates live elsewhere, set `SHEAF_DEV_CERT_DIR`. To point the pane at a different service URL, set `SHEAF_SERVICE_URL` before starting the dev server (for example `http://localhost:8080` if you run the service without the `https` profile).

Terminal 2 — add-in dev server:

```bash
cd addin
npm run start:web
```

The pane opens on the **Workbook** tab. **Scan workbook** reads every sheet and builds the catalog: tables, columns, inferred types, what depends on each column, and possible links between tables. The catalog is saved inside the workbook as a custom XML part and sent to `POST /api/catalog`, which rejects anything that could carry rows. Change a type in the dropdown to correct it; the correction is kept across rescans.

The **Ask** tab calls `/api/plan`, which still returns the hardcoded Q1 plan until the planner lands in M5.

---

## 7 — CI

The GitHub Actions workflow (`.github/workflows/ci.yml`) has three jobs:

| Job | What it does |
|-----|-------------|
| `service` | `mvn --batch-mode verify` — compiles, runs the tests, generates both schemas |
| `addin` | `npm ci` → generate-types → typecheck → lint → test → validate-manifest → build |
| `contract-drift` | Regenerates both sides, then `git diff --exit-code contract/` — fails if a Java change wasn't followed by a contract commit |

The `addin` job depends on `service` (downloads the generated schema as an artifact). `contract-drift` depends on both.

---

## 8 — Design Documentation

The M-0 design documents live in `docs/` and `corpus/`:

| File | Contents |
|------|----------|
| `corpus/questions.md` | 20 real questions with hand-written IR for each expressible one; 5 documented scope boundaries |
| `docs/ir-spec.md` | 8 operator definitions, predicate grammar, expression set, type lattice, 10 pipeline invariants |
| `docs/diagnostics.md` | 25 error codes + 3 warnings with user-facing messages and LLM repair hints |

Start with `docs/ir-spec.md` to understand the algebra, then `corpus/questions.md` to see how real questions map to IR.

---

## Quick Reference

```bash
# Full service build + tests
cd service && mvn --batch-mode verify

# Run service over HTTPS (Windows workaround included)
cd service && mvn spring-boot:run -Dspring-boot.run.profiles=https -Dspring-boot.run.jvmArguments=-Djdk.nio.enableUnixDomainSpecialFiles=false

# Install add-in deps
cd addin && npm install

# Typecheck + lint + tests
cd addin && npm run typecheck && npm run lint && npm test

# Regenerate contract (after Java IR changes)
cd service && mvn prepare-package
cd addin && npm run generate-types

# Start add-in dev server
cd addin && npm run start:web
```
