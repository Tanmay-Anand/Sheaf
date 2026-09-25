package com.sheaf.application.planning;

import com.sheaf.domain.common.Nullable;

import java.util.List;

/**
 * A chat model, provider-neutral. Adapters (adapters/llm/…) translate to one provider's API; no
 * provider type crosses this port. Implementations must never log, echo or embed the API key in
 * an exception message.
 */
public interface LanguageModelPort {

    /** A short name for run records, e.g. "openrouter". */
    String provider();

    /** Where requests go (a base URL, never with the key): shown to the user, checked by zero-egress mode. */
    String endpoint();

    /** Whether a key (or a local endpoint) is configured, so the pane can say so before a call fails. */
    boolean configured();

    Reply complete(Request request);

    /** The provider's model ids, where it has a model-list endpoint. */
    List<String> models();

    /** Proves the endpoint and key work, without paying for a completion. */
    default void verify() {
        models();
    }

    /**
     * @param model       The provider's model id; configuration, never code.
     * @param system      Instructions (the versioned prompt plus the workbook description).
     * @param messages    The conversation so far: the question, then any model reply and repair request.
     * @param maxTokens   A hard cap on the reply, which also caps the cost of one call.
     */
    record Request(String model, String system, List<Message> messages, int maxTokens, double temperature) {
        public Request {
            messages = List.copyOf(messages);
        }
    }

    /** @param role "user" or "assistant". */
    record Message(String role, String content) {}

    /**
     * @param model The model that actually answered (a router may resolve an alias).
     * @param cost  What the call cost in USD, when the provider reports it.
     */
    record Reply(String text, String model, int promptTokens, int completionTokens, @Nullable Double cost) {}

    /** A failure the user must be told about specifically, never with the key in it. */
    final class ModelException extends RuntimeException {

        public enum Kind { NOT_CONFIGURED, INVALID_KEY, NO_CREDIT, RATE_LIMITED, TIMEOUT, CONTEXT_TOO_LONG, MODEL_NOT_FOUND, UNAVAILABLE, BAD_RESPONSE }

        private final Kind kind;

        public ModelException(Kind kind, String message) {
            super(message);
            this.kind = kind;
        }

        public Kind kind() {
            return kind;
        }

        /** What the pane shows. */
        public String userMessage() {
            return switch (kind) {
                case NOT_CONFIGURED -> "No API key is set for this provider. Add one in the model settings.";
                case INVALID_KEY -> "The provider rejected the API key. Check it, or replace it.";
                case NO_CREDIT -> "The provider account is out of credit.";
                case RATE_LIMITED -> "The provider is rate-limiting requests. Wait a moment and try again.";
                case TIMEOUT -> "The model took too long to answer. Try again, or pick a faster model.";
                case CONTEXT_TOO_LONG -> "The workbook description is too long for this model. Pick a model with a larger context.";
                case MODEL_NOT_FOUND -> "The provider doesn't know that model id.";
                case UNAVAILABLE -> "The provider is unavailable right now. Try again shortly.";
                case BAD_RESPONSE -> "The provider sent an answer Sheaf couldn't read.";
            };
        }
    }
}
