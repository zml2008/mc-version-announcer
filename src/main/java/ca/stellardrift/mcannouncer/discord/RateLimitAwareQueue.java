package ca.stellardrift.mcannouncer.discord;

import ca.stellardrift.mcannouncer.VersionAnnouncer;
import org.tinylog.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;

public class RateLimitAwareQueue { // todo: actually handle rate limits
    private static final String RATE_LIMIT_GLOBAL = "X-RateLimit-Global";
    private static final String RATE_LIMIT_LIMIT = "X-RateLimit-Limit";
    private static final String RATE_LIMIT_REMAINING = "X-RateLimit-Remaining";
    private static final String RATE_LIMIT_RESET = "X-RateLimit-Reset";
    private static final String RATE_LIMIT_RESET_AFTER = "X-RateLimit-Reset-After";
    private static final String RATE_LIMIT_BUCKET = "X-RateLimit-Bucket";


    private final HttpClient http;
    private final ScheduledExecutorService executor;
    private final Map<URI, String> bucketByEndpoint = new ConcurrentHashMap<>();
    private final Map<String, RateLimitData> buckets = new ConcurrentHashMap<>();

    public RateLimitAwareQueue(final HttpClient http, final ScheduledExecutorService executor) {
        this.http = http;
        this.executor = executor;
    }

    public CompletableFuture<HttpResponse<?>> sendJson(final ApiEndpoint api, final String content) {
        final HttpRequest request = VersionAnnouncer.requestBuilder(api.url())
            .POST(HttpRequest.BodyPublishers.ofString(content, StandardCharsets.UTF_8))
            .header("Content-Type", "application/json")
            .build();
        return this.http.sendAsync(
            request,
            HttpResponse.BodyHandlers.ofString() // json text?
        ).handleAsync((response, error) -> {
            if (error != null) {
                Logger.error(error, "Failed to send webhook payload to endpoint {}", api.description());
            } else if (response != null && response.statusCode() >= 400) { // error
                switch (response.statusCode()) {
                    case 429:
                        // capture rate-limit info from response
                        Logger.error("Rate limit exceeded for {} !!! Implement this !!!: {}", api.description(), response.body());
                        break;
                    default:
                        Logger.error("Received an error from discord while sending to {}, status {}: {}", api.description(), response.statusCode(), response.body());
                }
            }
            return response;
        }, this.executor);
    }

    static class RateLimitData {
        private final String bucket;
        private final Queue<HttpRequest> queuedRequests = new ArrayDeque<>();
        // the number of requests total
        private int limit;
        // the number remaining
        private int remaining;
        // time when the rate limit resets
        private Instant reset = Instant.MAX;

        RateLimitData(final String bucket) {
            this.bucket = bucket;
        }
    }

}
