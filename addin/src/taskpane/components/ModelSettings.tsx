import * as React from "react";
import { useState } from "react";
import {
  Button,
  Caption1,
  Combobox,
  Dropdown,
  Field,
  Input,
  MessageBar,
  MessageBarBody,
  Option,
  Switch,
  makeStyles,
  tokens,
} from "@fluentui/react-components";
import { listModels, testConnection, type ModelChoice, type PlannerStatus, type ProviderInfo } from "../../api/askClient";
import { keys } from "../../api/keyStore";

/**
 * Which model plans, where it runs, and the user's key for it. The key field is write-only: once
 * saved it is never shown again, only removed. Zero-egress mode allows only a model on this machine.
 */

const CHOICE_SETTING = "sheaf.planner.choice";

const useStyles = makeStyles({
  root: {
    display: "flex",
    flexDirection: "column",
    gap: tokens.spacingVerticalS,
    padding: tokens.spacingHorizontalM,
    border: `1px solid ${tokens.colorNeutralStroke2}`,
    borderRadius: tokens.borderRadiusMedium,
  },
  row: { display: "flex", alignItems: "center", gap: tokens.spacingHorizontalS, flexWrap: "wrap" },
  muted: { color: tokens.colorNeutralForeground3 },
});

export function loadChoice(): Partial<ModelChoice> {
  try {
    return JSON.parse(window.localStorage.getItem(CHOICE_SETTING) ?? "{}") as Partial<ModelChoice>;
  } catch {
    return {};
  }
}

export function saveChoice(choice: ModelChoice): void {
  try {
    window.localStorage.setItem(CHOICE_SETTING, JSON.stringify(choice));
  } catch {
    // Storage blocked: the choice lasts for this session only.
  }
}

export function providerOf(status: PlannerStatus | null, id: string): ProviderInfo | undefined {
  return status?.providers.find((p) => p.id === id);
}

/** Whether a question can be sent with this choice: a model, and a key where one is needed. */
export function ready(status: PlannerStatus | null, choice: ModelChoice): boolean {
  const p = providerOf(status, choice.provider);
  if (!p || !choice.model.trim()) return false;
  if ((choice.zeroEgress || status?.zeroEgressForced) && !p.local) return false;
  return !p.needsKey || p.serverKey || keys.has(p.id);
}

export default function ModelSettings({
  status,
  choice,
  onChange,
}: {
  status: PlannerStatus;
  choice: ModelChoice;
  onChange: (c: ModelChoice) => void;
}) {
  const styles = useStyles();
  const [keyText, setKeyText] = useState("");
  const [, setVersion] = useState(0); // re-render after the key store changes
  const [message, setMessage] = useState<{ intent: "success" | "error"; text: string } | null>(null);
  const [listed, setListed] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const provider = providerOf(status, choice.provider);
  const zeroEgress = choice.zeroEgress || status.zeroEgressForced;
  const saved = provider ? keys.has(provider.id) : false;

  const set = (patch: Partial<ModelChoice>) => {
    setMessage(null);
    onChange({ ...choice, ...patch });
  };

  const pick = (id: string) => {
    const p = providerOf(status, id);
    setListed([]);
    set({ provider: id, model: p?.defaultModel ?? "", baseUrl: undefined });
  };

  async function run(action: () => Promise<string>) {
    setBusy(true);
    setMessage(null);
    try {
      setMessage({ intent: "success", text: await action() });
    } catch (e) {
      setMessage({ intent: "error", text: e instanceof Error ? e.message : "That didn't work." });
    } finally {
      setBusy(false);
    }
  }

  const models = [...new Set([...(provider?.suggestedModels ?? []), ...listed])];
  const showBaseUrl = provider && (provider.id === "ollama" || provider.id === "openai-compatible") && provider.customBaseUrl;
  const needsKey = provider && (provider.needsKey || provider.id === "openai-compatible");

  return (
    <div className={styles.root}>
      <Switch
        checked={zeroEgress}
        disabled={status.zeroEgressForced}
        label={status.zeroEgressForced ? "Zero-egress mode (set by this service)" : "Zero-egress mode: only a model on this computer"}
        onChange={(_, d) => {
          const local = status.providers.find((p) => p.local);
          if (d.checked && provider && !provider.local && local) set({ zeroEgress: true, provider: local.id, model: local.defaultModel, baseUrl: undefined });
          else set({ zeroEgress: d.checked });
        }}
      />
      <Field label="Provider">
        <Dropdown
          value={provider?.label ?? choice.provider}
          selectedOptions={[choice.provider]}
          onOptionSelect={(_, d) => d.optionValue && pick(d.optionValue)}
        >
          {status.providers.map((p) => (
            <Option key={p.id} value={p.id} text={p.label} disabled={zeroEgress && !p.local}>
              {p.label}
            </Option>
          ))}
        </Dropdown>
      </Field>
      {showBaseUrl && (
        <Field label="Endpoint (base URL)">
          <Input value={choice.baseUrl ?? ""} placeholder={provider.defaultBaseUrl || "http://localhost:1234/v1"} onChange={(_, d) => set({ baseUrl: d.value })} />
        </Field>
      )}
      {needsKey && (
        <Field label="API key">
          {saved ? (
            <div className={styles.row}>
              <Caption1>A key is saved in this browser (never in the workbook).</Caption1>
              <Button
                size="small"
                onClick={() => {
                  keys.remove(provider.id);
                  setVersion((v) => v + 1);
                  setMessage({ intent: "success", text: "Key removed from this browser." });
                }}
              >
                Remove key
              </Button>
            </div>
          ) : (
            <div className={styles.row}>
              <Input
                type="password"
                value={keyText}
                placeholder={provider.serverKey ? "Optional: the service has its own key" : "Paste your key"}
                onChange={(_, d) => setKeyText(d.value)}
                autoComplete="off"
              />
              <Button
                size="small"
                disabled={!keyText.trim() || !keys.available}
                onClick={() => {
                  keys.set(provider.id, keyText);
                  setKeyText("");
                  setVersion((v) => v + 1);
                  setMessage({ intent: "success", text: "Key saved in this browser. It is sent only with your questions, over HTTPS." });
                }}
              >
                Save key
              </Button>
            </div>
          )}
        </Field>
      )}
      <Field label="Model">
        <Combobox
          freeform
          value={choice.model}
          selectedOptions={[choice.model]}
          onOptionSelect={(_, d) => d.optionValue && set({ model: d.optionValue })}
          onChange={(e) => set({ model: (e.target as HTMLInputElement).value })}
        >
          {models.map((m) => (
            <Option key={m} value={m}>
              {m}
            </Option>
          ))}
        </Combobox>
      </Field>
      <div className={styles.row}>
        <Button size="small" disabled={busy} onClick={() => void run(async () => (await testConnection(choice), "Connected."))}>
          Test connection
        </Button>
        <Button
          size="small"
          disabled={busy}
          onClick={() =>
            void run(async () => {
              const found = await listModels(choice);
              setListed(found);
              return `${found.length} models available.`;
            })
          }
        >
          Load models
        </Button>
      </div>
      {message && (
        <MessageBar layout="multiline" intent={message.intent}>
          <MessageBarBody>{message.text}</MessageBarBody>
        </MessageBar>
      )}
      <Caption1 className={styles.muted}>
        {provider?.local
          ? "Runs on this computer: nothing leaves it."
          : "Your question and the workbook's structure (never rows) go to this provider through the Sheaf service."}
      </Caption1>
    </div>
  );
}
