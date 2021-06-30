package ca.stellardrift.mcannouncer;

import ca.stellardrift.mcannouncer.util.GsonUtils;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Config {

    private String cacheDir;
    private Map<String, Webhook> endpoints = Map.of();

    static Config load(final Path file) throws IOException {
        final Config config = GsonUtils.parseFromJson(file, Config.class);
        if (config.cacheDir == null) {
            throw new JsonSyntaxException("No value provided for 'cacheDir'!");
        }
        return config;
    }

    static class Webhook {
        private URI webhookUrl;
        private List<String> roleMentions = List.of();
        private Set<String> tags = Set.of();

        public URI webhookUrl() { // the webhook URL, from discord API
            return this.webhookUrl;
        }

        public List<String> roleMentions() { // roles to mention when an update is detected
            return this.roleMentions;
        }

        boolean isTagged(final @Nullable String tag) {
            return tag == null || this.tags.contains(tag);
        }
    }

    public Path cacheDir() {
        return Path.of(this.cacheDir);
    }

    public Map<String, Webhook> endpoints() { // id, endpoint
        return this.endpoints;
    }


}
