import type { Value } from "./value";

/** An evaluated table: named columns and rows of values, row-major. */
export interface Table {
  columns: string[];
  rows: Value[][];
}

/**
 * Raised when a plan asks for something the evaluator can't do. The type checker has already
 * accepted the plan, so this means the two disagree (or the data changed shape): a bug to report,
 * never a normal outcome.
 */
export class EvalError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "EvalError";
  }
}

const indexCache = new WeakMap<string[], Map<string, number>>();

/** Column position by name, ignoring case as Excel does; -1 when absent. */
export function columnIndex(t: Table, name: string): number {
  let map = indexCache.get(t.columns);
  if (!map) {
    map = new Map();
    t.columns.forEach((c, i) => {
      const k = c.toLowerCase();
      if (!map!.has(k)) map!.set(k, i);
    });
    indexCache.set(t.columns, map);
  }
  return map.get(name.toLowerCase()) ?? -1;
}

export function requireColumn(t: Table, name: string): number {
  const i = columnIndex(t, name);
  if (i < 0) throw new EvalError(`Column '${name}' isn't in the table at this step (columns: ${t.columns.join(", ")}).`);
  return i;
}

/** "name", or "name (Entity)" when the name is taken ignoring case, then "name (Entity) 2"… (as the type checker names them). */
export function uniqueName(wanted: string, entity: string, taken: Set<string>): string {
  if (!taken.has(wanted.toLowerCase())) {
    taken.add(wanted.toLowerCase());
    return wanted;
  }
  const base = `${wanted} (${entity})`;
  let name = base;
  for (let i = 2; taken.has(name.toLowerCase()); i++) name = `${base} ${i}`;
  taken.add(name.toLowerCase());
  return name;
}
