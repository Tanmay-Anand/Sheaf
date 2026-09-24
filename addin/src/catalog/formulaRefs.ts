import type { UntraceableKind } from "@sheaf/contract/catalog";
import { type Box, parseRef } from "./a1";

/**
 * Static reference extraction from formula text. Handles A1 references (cells, ranges, whole
 * columns and rows, sheet-qualified and quoted), structured references (Table[Column],
 * Table[[#This Row],[Col]], [@Col], Table[[A]:[C]]) and defined names. Anything whose target
 * can only be known at run time (INDIRECT, OFFSET) or lives in another workbook is reported as
 * untraceable rather than guessed.
 */

export type FormulaRef =
  | { kind: "range"; sheet: string | null; box: Box }
  | { kind: "structured"; table: string | null; columns: string[] | "all"; span?: [string, string] }
  | { kind: "name"; name: string };

export interface ExtractResult {
  refs: FormulaRef[];
  untraceable: { kind: UntraceableKind; detail: string }[];
}

const SHEET = String.raw`(?:'((?:[^']|'')+)'|([A-Za-z_À-￿][\w.À-￿]*))!`;
const CELL = String.raw`\$?[A-Za-z]{1,3}\$?\d{1,7}`;
const A1_RE = new RegExp(
  String.raw`(?<![\w.$'!\]])(?:${SHEET})?(${CELL}(?::${CELL})?|\$?[A-Za-z]{1,3}:\$?[A-Za-z]{1,3}|\$?\d{1,7}:\$?\d{1,7})(?![\w(!\[])`,
  "g",
);
const NAME_RE = /(?<![\w.$'!\]\\])([A-Za-z_\\][\w.]*)(?![\w(!\[])/g;
const REF_TAIL = String.raw`[$A-Za-z0-9:]+`;
const EXTERNAL_RE = new RegExp(
  String.raw`'[^']*\[[^\]]+\][^']*'!${REF_TAIL}|\[[^\]]*\.(?:xlsx|xlsm|xlsb|xls|csv)\][^!\s'"(),+\-*/&=<>^]*!${REF_TAIL}|\[\d+\][^!\s'"(),+\-*/&=<>^]*!${REF_TAIL}`,
  "gi",
);
const RESERVED = new Set(["TRUE", "FALSE"]);

function blank(s: string, start: number, end: number): string {
  return s.slice(0, start) + " ".repeat(end - start) + s.slice(end);
}

/** Finds the index of the bracket closing the one at `open`. */
function matchBracket(s: string, open: number): number {
  let depth = 0;
  for (let i = open; i < s.length; i++) {
    const ch = s[i];
    if (ch === "'" ) {
      i++; // escaped character inside a structured reference
      continue;
    }
    if (ch === "[") depth++;
    else if (ch === "]" && --depth === 0) return i;
  }
  return -1;
}

function unescapeColumn(name: string): string {
  return name.replace(/'(.)/g, "$1").trim();
}

function parseStructured(inner: string): { columns: string[] | "all"; span?: [string, string] } {
  if (!inner.includes("[")) {
    const item = inner.replace(/^@/, "").trim();
    if (item === "" || item.startsWith("#")) return { columns: "all" };
    return { columns: [unescapeColumn(item)] };
  }
  const items = [...inner.matchAll(/\[((?:[^[\]']|'.)*)\]/g)].map((m) => m[1]!);
  const cols = items.filter((i) => !i.trim().startsWith("#")).map(unescapeColumn);
  if (cols.length === 0) return { columns: "all" };
  if (/\]\s*:\s*\[/.test(inner) && cols.length === 2) return { columns: cols, span: [cols[0]!, cols[1]!] };
  return { columns: cols };
}

export function extractReferences(text: string): ExtractResult {
  const refs: FormulaRef[] = [];
  const untraceable: ExtractResult["untraceable"] = [];
  let s = text.startsWith("=") ? text.slice(1) : text;

  // String literals can contain anything; they never reference cells.
  s = s.replace(/"(?:[^"]|"")*"/g, (m) => " ".repeat(m.length));

  s = s.replace(EXTERNAL_RE, (m) => {
    untraceable.push({ kind: "externalLink", detail: m.trim() });
    return " ".repeat(m.length);
  });
  if (/\bINDIRECT\s*\(/i.test(s)) untraceable.push({ kind: "indirect", detail: "INDIRECT builds its reference at run time" });
  if (/\bOFFSET\s*\(/i.test(s)) untraceable.push({ kind: "offset", detail: "OFFSET moves its reference at run time" });

  // Structured references.
  for (let i = s.indexOf("["); i >= 0; i = s.indexOf("[", i + 1)) {
    const close = matchBracket(s, i);
    if (close < 0) {
      untraceable.push({ kind: "unparsed", detail: "unbalanced [ in formula" });
      break;
    }
    const before = /([A-Za-z_\\][\w.]*)$/.exec(s.slice(0, i));
    const table = before ? before[1]! : null;
    const start = before ? i - table!.length : i;
    refs.push({ kind: "structured", table, ...parseStructured(s.slice(i + 1, close)) });
    s = blank(s, start, close + 1);
  }

  // A1 references.
  s = s.replace(A1_RE, (m, quoted: string | undefined, bare: string | undefined, ref: string) => {
    const box = parseRef(ref);
    if (!box) return m;
    const sheet = quoted !== undefined ? quoted.replace(/''/g, "'") : (bare ?? null);
    refs.push({ kind: "range", sheet, box });
    return " ".repeat(m.length);
  });

  // Whatever identifiers remain may be defined names; the caller keeps only real ones.
  const names = new Set<string>();
  for (const m of s.matchAll(NAME_RE)) {
    const name = m[1]!;
    if (!RESERVED.has(name.toUpperCase())) names.add(name);
  }
  for (const name of names) refs.push({ kind: "name", name });

  return { refs, untraceable };
}
