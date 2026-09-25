import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { KeyStore, type KeyStorage } from "../keyStore";

class MemoryStorage implements KeyStorage {
  readonly items = new Map<string, string>();
  getItem(k: string) {
    return this.items.get(k) ?? null;
  }
  setItem(k: string, v: string) {
    this.items.set(k, v);
  }
  removeItem(k: string) {
    this.items.delete(k);
  }
}

function sources(dir: string): string[] {
  return readdirSync(dir).flatMap((f) => {
    const p = join(dir, f);
    if (statSync(p).isDirectory()) return f === "__tests__" ? [] : sources(p);
    return /\.(ts|tsx)$/.test(f) ? [p] : [];
  });
}

describe("store mode keys", () => {
  it("keeps one key per provider, trimmed, and Remove key leaves nothing behind", () => {
    const storage = new MemoryStorage();
    const keys = new KeyStore(storage);
    keys.set("openrouter", "  sk-or-123  ");
    keys.set("anthropic", "sk-ant-456");
    expect(keys.get("openrouter")).toBe("sk-or-123");
    expect(keys.has("gemini")).toBe(false);
    keys.remove("openrouter");
    expect(keys.get("openrouter")).toBeNull();
    expect([...storage.items.values()].join()).not.toContain("sk-or-123");
    keys.set("anthropic", "   ");
    expect(storage.items.size).toBe(0);
  });

  it("copes with blocked storage", () => {
    const keys = new KeyStore(null);
    expect(keys.available).toBe(false);
    keys.set("openrouter", "x");
    expect(keys.get("openrouter")).toBeNull();
  });

  it("never puts a key where it would travel with the workbook, or in a URL", () => {
    const root = resolve(process.cwd(), "src");
    for (const file of sources(root)) {
      const text = readFileSync(file, "utf8");
      if (!/keys\.(get|set)\(|keyStore/.test(text)) continue;
      // Files that handle keys must not touch workbook-borne storage.
      expect(text, file).not.toMatch(/document\.settings|customXmlParts/);
    }
    const client = readFileSync(join(root, "api", "askClient.ts"), "utf8");
    expect(client).toMatch(/h\[KEY_HEADER\] = key/);
    expect(client).not.toMatch(/\?key=|[?&]api_?key=/i);
  });
});

describe("content security policy", () => {
  it("is in the pane's HTML, with scripts limited to the bundle and Office.js", () => {
    const html = readFileSync(resolve(process.cwd(), "src/taskpane/taskpane.html"), "utf8");
    expect(html).toContain('http-equiv="Content-Security-Policy"');
    const webpack = readFileSync(resolve(process.cwd(), "webpack.config.js"), "utf8");
    expect(webpack).toMatch(/script-src 'self' \$\{microsoft\}/);
    expect(webpack).not.toMatch(/unsafe-eval/);
  });
});
