/**
 * Store mode (BYOK): the user's provider keys live in this add-in's own browser storage, one per
 * provider, and nowhere else. Never in document.settings or custom XML parts (both travel with
 * the workbook), never in the bundle, never in a URL. Sent only in a request header, over HTTPS,
 * to the Sheaf service, which uses a key for that one provider call and never stores it.
 * "Remove key" deletes it; nothing is left behind.
 */

const PREFIX = "sheaf.key.";

/** The subset of the Web Storage API used here, so tests can pass their own. */
export interface KeyStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

function browserStorage(): KeyStorage | null {
  try {
    return window.localStorage;
  } catch {
    return null; // storage blocked: keys can't be kept, and the pane says so
  }
}

export class KeyStore {
  constructor(private readonly storage: KeyStorage | null = browserStorage()) {}

  /** Whether keys can be kept in this browser at all. */
  get available(): boolean {
    return this.storage !== null;
  }

  get(provider: string): string | null {
    try {
      return this.storage?.getItem(PREFIX + provider) ?? null;
    } catch {
      return null;
    }
  }

  has(provider: string): boolean {
    return !!this.get(provider);
  }

  set(provider: string, key: string): void {
    const k = key.trim();
    if (!k) return this.remove(provider);
    this.storage?.setItem(PREFIX + provider, k);
  }

  remove(provider: string): void {
    try {
      this.storage?.removeItem(PREFIX + provider);
    } catch {
      // Already unavailable.
    }
  }
}

export const keys = new KeyStore();
