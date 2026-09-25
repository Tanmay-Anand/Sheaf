package com.sheaf.adapters.llm;

import com.sheaf.adapters.llm.anthropic.AnthropicModel;
import com.sheaf.adapters.llm.ollama.OllamaModel;
import com.sheaf.application.planning.LanguageModels.Choice;
import com.sheaf.application.planning.LanguageModels.ProviderRefused;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderRegistryTest {

    private static LlmProperties props(boolean customBase) {
        var p = new LlmProperties.ProviderProperties("x", "https://openrouter.ai/api/v1", "server-key", "m", List.of("m"));
        return new LlmProperties("openrouter", Duration.ofSeconds(5), true, false, customBase, Map.of(
                "openrouter", p,
                "anthropic", new LlmProperties.ProviderProperties("Anthropic", "https://api.anthropic.com", "", "claude-sonnet-4-6", List.of()),
                "ollama", new LlmProperties.ProviderProperties("Ollama", "http://localhost:11434", "", "", List.of()),
                "openai-compatible", new LlmProperties.ProviderProperties("Other", "", "", "", List.of())));
    }

    @Test
    void opens_the_chosen_adapter_and_prefers_the_pane_key_over_the_service_key() {
        var r = new ProviderRegistry(props(true));
        assertThat(r.open(new Choice("anthropic", null, "pane-key"))).isInstanceOf(AnthropicModel.class)
                .satisfies(m -> assertThat(m.configured()).isTrue());
        assertThat(r.open(new Choice("anthropic", null, null)).configured()).isFalse(); // no service key for Anthropic
        assertThat(r.open(new Choice(null, null, null)).configured()).isTrue(); // default provider, service key
        assertThat(r.open(new Choice("ollama", null, null))).isInstanceOf(OllamaModel.class);
    }

    @Test
    void refuses_unknown_providers_insecure_endpoints_and_custom_endpoints_when_disabled() {
        var r = new ProviderRegistry(props(true));
        assertThatThrownBy(() -> r.open(new Choice("acme", null, null))).isInstanceOfSatisfying(ProviderRefused.class,
                e -> assertThat(e.code()).isEqualTo("UNKNOWN_PROVIDER"));
        assertThatThrownBy(() -> r.open(new Choice("openai-compatible", "http://10.0.0.5:8000/v1", "k"))).isInstanceOfSatisfying(ProviderRefused.class,
                e -> assertThat(e.code()).isEqualTo("INSECURE_BASE_URL"));
        assertThatThrownBy(() -> r.open(new Choice("openai-compatible", null, null))).isInstanceOfSatisfying(ProviderRefused.class,
                e -> assertThat(e.code()).isEqualTo("BASE_URL_REQUIRED"));
        assertThat(r.open(new Choice("openai-compatible", "http://localhost:1234/v1", null)).endpoint()).isEqualTo("http://localhost:1234/v1");

        var locked = new ProviderRegistry(props(false));
        assertThatThrownBy(() -> locked.open(new Choice("ollama", "http://127.0.0.1:9999", null))).isInstanceOfSatisfying(ProviderRefused.class,
                e -> assertThat(e.code()).isEqualTo("CUSTOM_BASE_URL_DISABLED"));
    }

    @Test
    void lists_providers_without_their_keys() {
        var list = new ProviderRegistry(props(true)).providers();
        assertThat(list).extracting(p -> p.id()).contains("openrouter", "anthropic", "ollama");
        assertThat(list.stream().filter(p -> p.id().equals("openrouter")).findFirst().orElseThrow().serverKey()).isTrue();
        assertThat(list.stream().filter(p -> p.id().equals("ollama")).findFirst().orElseThrow().local()).isTrue();
        assertThat(list.toString()).doesNotContain("server-key");
    }

    @Test
    void only_this_machine_is_local() {
        assertThat(ProviderRegistry.isLocal("http://localhost:11434")).isTrue();
        assertThat(ProviderRegistry.isLocal("http://127.0.0.1:11434")).isTrue();
        assertThat(ProviderRegistry.isLocal("http://[::1]:11434")).isTrue();
        assertThat(ProviderRegistry.isLocal("http://localhost.evil.com")).isFalse();
        assertThat(ProviderRegistry.isLocal("https://openrouter.ai/api/v1")).isFalse();
    }
}
