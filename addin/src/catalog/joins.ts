import type { JoinCandidate, ScalarKind } from "@sheaf/contract/catalog";

export interface JoinColumn {
  entity: string;
  column: string;
  kind: ScalarKind;
  keyCandidate: boolean;
  integerOnly: boolean;
  distinct: Set<string>;
}

const MIN_OVERLAP = 0.8;
const MAX_CANDIDATES = 50;
const MAX_DISTINCT = 50_000;
const TEXT: ScalarKind[] = ["string", "categorical"];

function tokens(name: string): Set<string> {
  return new Set(
    name
      .toLowerCase()
      .split(/[^a-z0-9]+/)
      .filter((t) => t.length > 1 && t !== "id"),
  );
}

function namesRelated(a: string, b: string): boolean {
  const ta = tokens(a);
  for (const t of tokens(b)) if (ta.has(t)) return true;
  return a.toLowerCase().replace(/[^a-z0-9]/g, "") === b.toLowerCase().replace(/[^a-z0-9]/g, "");
}

function round2(n: number): number {
  return Math.round(n * 100) / 100;
}

/**
 * Proposes joins from value overlap: `from` values found in a unique `to` column. Only a
 * proposal. The catalog marks every candidate unapproved, and plans can't use it until a person
 * approves it.
 */
export function inferJoins(columns: JoinColumn[]): JoinCandidate[] {
  const out: JoinCandidate[] = [];
  for (const to of columns) {
    if (!to.keyCandidate || to.distinct.size < 2 || to.distinct.size > MAX_DISTINCT) continue;
    const toText = TEXT.includes(to.kind);
    const toInt = to.kind === "number" && to.integerOnly;
    if (!toText && !toInt) continue;

    for (const from of columns) {
      if (from.entity === to.entity || from.distinct.size < 2 || from.distinct.size > MAX_DISTINCT) continue;
      const fromText = TEXT.includes(from.kind);
      const fromInt = from.kind === "number" && from.integerOnly;
      if (toText ? !fromText : !fromInt) continue;
      // Small integers overlap by accident (quantities vs ids); require related names for numbers.
      if (toInt && !namesRelated(from.column, to.column)) continue;

      let hits = 0;
      for (const v of from.distinct) if (to.distinct.has(v)) hits++;
      const overlap = hits / from.distinct.size;
      if (overlap < MIN_OVERLAP) continue;

      const evidence = Math.min(1, from.distinct.size / 5);
      const nameBonus = namesRelated(from.column, to.column) ? 1 : 0.9;
      out.push({
        fromEntity: from.entity,
        fromColumn: from.column,
        toEntity: to.entity,
        toColumn: to.column,
        cardinality: from.keyCandidate ? "one-to-one" : "many-to-one",
        overlap: round2(overlap),
        confidence: round2(overlap * (0.6 + 0.4 * evidence) * nameBonus),
        approved: false,
      });
    }
  }

  // A one-to-one pair shows up in both directions; keep the stronger (or first) one.
  const seen = new Set<string>();
  return out
    .sort((a, b) => b.confidence - a.confidence || a.fromEntity.localeCompare(b.fromEntity))
    .filter((j) => {
      const key = [`${j.fromEntity}.${j.fromColumn}`, `${j.toEntity}.${j.toColumn}`].sort().join("|");
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    })
    .slice(0, MAX_CANDIDATES);
}
