package ca.stellardrift.mcannouncer;

import ca.stellardrift.mcannouncer.discord.ApiEndpoint;
import ca.stellardrift.mcannouncer.util.GsonUtils;
import com.google.gson.JsonSyntaxException;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Config {

    private String changelogUrlFormat = "https://mdcfe.dev/mc-changes?ver=%s";
    private String cacheDir;
    private Map<String, Webhook> endpoints = Map.of();

    static Config load(final Path file) throws IOException {
        final Config config = GsonUtils.parseFromJson(file, Config.class);
        if (config.cacheDir == null) {
            throw new JsonSyntaxException("No value provided for 'cacheDir'!");
        }
        for (final Map.Entry<String, Webhook> entry : config.endpoints.entrySet()) {
            entry.getValue().key = entry.getKey();
        }
        return config;
    }

    static class Webhook implements ApiEndpoint {
        private transient @MonotonicNonNull String key;
        private URI webhookUrl;
        private List<String> roleMentions = List.of();
        private Set<String> tags = Set.of();

        @Override
        public @NonNull String description() {
            return this.key;
        }

        @Override
        public @NonNull URI url() { // the webhook URL, from discord API
            return this.webhookUrl;
        }

        public List<String> roleMentions() { // roles to mention when an update is detected
            return this.roleMentions;
        }

        boolean isTagged(final @Nullable String tag) {
            return tag == null || this.tags.contains(tag);
        }
    }

    public String changelogUrlFormat() {
        return this.changelogUrlFormat;
    }

    public Path cacheDir() {
        return Path.of(this.cacheDir);
    }

    public Map<String, Webhook> endpoints() { // id, endpoint
        return this.endpoints;
    }


}
