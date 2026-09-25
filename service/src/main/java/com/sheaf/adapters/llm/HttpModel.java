package com.sheaf.adapters.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sheaf.application.planning.LanguageModelPort;
import com.sheaf.application.planning.LanguageModelPort.ModelException;
import com.sheaf.application.planning.LanguageModelPort.ModelException.Kind;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;

/**
 * What every provider adapter shares: one HTTP client, timeouts, JSON, and the rule that the key
 * never leaves the request header. Anything that might be shown or logged goes through
 * {@link #scrub}, which replaces the key.
 */
public abstract class HttpModel implements LanguageModelPort {

    protected static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    protected final String provider;
    protected final String baseUrl;
    protected final String apiKey;
    protected final Duration timeout;

    protected HttpModel(String provider, String baseUrl, String apiKey, Duration timeout) {
        this.provider = provider;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.apiKey = unquote(apiKey == null ? "" : apiKey.strip());
        this.timeout = timeout;
    }

    /** A .env value may be written "…" or '…'. */
    static String unquote(String s) {
        return s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("'") && s.endsWith("'"))
                ? s.substring(1, s.length() - 1) : s;
    }

    @Override
    public String provider() {
        return provider;
    }

    @Override
    public String endpoint() {
        return baseUrl;
    }

    protected boolean needsKey() {
        return true;
    }

    @Override
    public boolean configured() {
        return !needsKey() || !apiKey.isEmpty();
    }

    protected void requireKey() {
        if (!configured()) throw new ModelException(Kind.NOT_CONFIGURED, "No API key for " + provider + ".");
    }

    public record Http(int status, String body) {}

    protected Http send(String method, String path, Map<String, String> headers, String body) {
        var b = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(timeout);
        headers.forEach(b::header);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        try {
            HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Http(r.statusCode(), r.body());
        } catch (HttpTimeoutException e) {
            throw new ModelException(Kind.TIMEOUT, "No answer from " + provider + " within " + timeout.toSeconds() + " s.");
        } catch (ConnectException e) {
            throw new ModelException(Kind.UNAVAILABLE, provider + " is not reachable at " + baseUrl + ".");
        } catch (IOException e) {
            throw new ModelException(Kind.UNAVAILABLE, provider + " could not be reached: " + scrub(String.valueOf(e.getMessage())));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelException(Kind.UNAVAILABLE, "Interrupted while waiting for " + provider + ".");
        }
    }

    protected JsonNode json(Http r) {
        try {
            return JSON.readTree(r.body());
        } catch (IOException e) {
            throw new ModelException(r.status() >= 500 ? Kind.UNAVAILABLE : Kind.BAD_RESPONSE,
                    provider + " answered " + r.status() + " with something that isn't JSON.");
        }
    }

    protected ModelException failure(Kind kind, int code, String message) {
        return new ModelException(kind, provider + " (" + code + "): " + scrub(message));
    }

    /** Removes the key from any text that might be shown or logged. */
    protected String scrub(String text) {
        return apiKey.isEmpty() || text == null ? text : text.replace(apiKey, "[key]");
    }

    protected static String clip(String s) {
        return s.length() > 300 ? s.substring(0, 300) : s;
    }
}
