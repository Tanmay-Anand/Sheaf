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
├── docs/            # ir-spec.md, ir-decisions.md, diagnostics.md
├── contract/        # Generated artefacts (committed snapshot)
│   ├── schema/      #   plan.schema.json  — written by Maven
│   └── types/       #   plan.d.ts         — written by npm
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

This compiles the Java IR types, runs five tests (3 ArchUnit + 2 MockMvc), and generates `contract/schema/plan.schema.json` at the `prepare-package` phase.

**Expected output (abbreviated):**

```
[INFO] Tests run: 5, Failures: 0, Errors: 0
[INFO] BUILD SUCCESS
```

### Windows 11 + Java 21 NIO issue

If you start the service with `java -jar` you may see:

```
java.net.SocketException: Invalid argument
    at sun.nio.ch.UnixDomainSockets.connect0
```

Java 21's `WEPollSelectorImpl` uses Unix domain sockets for internal Tomcat wakeup pipes; some Windows 11 configurations reject this. Use the Maven launcher instead:

```bash
mvn spring-boot:run -Dspring-boot.run.jvmArguments=-Djdk.nio.enableUnixDomainSpecialFiles=false
```

The five tests use MockMvc (in-process, no real server socket), so `mvn verify` is unaffected on all platforms.

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

---

## 4 — Type-check and Lint the Add-in

```bash
cd addin
npm run typecheck
npm run lint
```

Both must pass before the service tests count as a clean build.

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

Terminal 1 — service:

```bash
cd service
mvn spring-boot:run -Dspring-boot.run.jvmArguments=-Djdk.nio.enableUnixDomainSpecialFiles=false
```

Terminal 2 — add-in dev server:

```bash
cd addin
npm run start:web
```

The task pane calls `http://localhost:8080/api/plan` on submit. At M-1 it returns a hardcoded Q1 plan (filter + aggregate + sort over the Orders entity). The plan JSON is displayed in the task pane.

---

## 7 — CI

The GitHub Actions workflow (`.github/workflows/ci.yml`) has three jobs:

| Job | What it does |
|-----|-------------|
| `service` | `mvn --batch-mode verify` — compiles + 5 tests + schema generation |
| `addin` | `npm ci` → typecheck → lint → generate-types → validate-manifest → build |
| `contract-drift` | Regenerates both sides, then `git diff --exit-code contract/` — fails if a Java change wasn't followed by a contract commit |

The `addin` job depends on `service` (downloads the generated schema as an artifact). `contract-drift` depends on both.

---

## 8 — Design Documentation

The M-0 design documents live in `docs/` and `corpus/`:

| File | Contents |
|------|----------|
| `corpus/questions.md` | 20 real questions with hand-written IR for each expressible one; 5 documented scope boundaries |
| `docs/ir-spec.md` | 8 operator definitions, predicate grammar, expression set, type lattice, 10 pipeline invariants |
| `docs/ir-decisions.md` | Operators IN with rationale; 9+ operators REJECTED with rationale |
| `docs/diagnostics.md` | 25 error codes + 3 warnings with user-facing messages and LLM repair hints |

Start with `docs/ir-spec.md` to understand the algebra, then `corpus/questions.md` to see how real questions map to IR.

---

## Quick Reference

```bash
# Full service build + tests
cd service && mvn --batch-mode verify

# Run service (Windows workaround)
cd service && mvn spring-boot:run -Dspring-boot.run.jvmArguments=-Djdk.nio.enableUnixDomainSpecialFiles=false

# Install add-in deps
cd addin && npm install

# Typecheck + lint
cd addin && npm run typecheck && npm run lint

# Regenerate contract (after Java IR changes)
cd service && mvn prepare-package
cd addin && npm run generate-types

# Start add-in dev server
cd addin && npm run start:web
```
