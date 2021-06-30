package ca.stellardrift.mcannouncer.discord;

import com.google.gson.annotations.SerializedName;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.immutables.gson.Gson;
import org.immutables.value.Value;

import java.nio.file.Path;
import java.util.List;

@Gson.TypeAdapters
@Value.Immutable
public interface Webhook {
    int MAX_EMBEDS = 10;
    int MAX_COMPONENTS = 5;

    static Builder builder() {
        return new Builder();
    }

    @Nullable String content();
    @Nullable String username();
    @SerializedName("avatar_url")
    @Nullable String avatarUrl();
    @Value.Default
    default boolean tts() {
        return false;
    }
    @Nullable Path file();
    List<Embed> embeds(); // max 10
    @SerializedName("allowed_mentions")
    @Nullable AllowedMention allowedMentions();
    List<MessageComponent> components();

    @Value.Check
    default void checkOneOfContentEmbedsFileSet() {
        int set = 0;
        if (this.content() != null) {
            set++;
        }

        if (this.embeds().size() > 0) {
            set++;
        }

        if (this.file() != null) {
            set++;
        }
        if (set < 1) {
            throw new IllegalArgumentException("At least one of content, embeds, and file must be set");
        }

        if (this.embeds().size() > MAX_EMBEDS) {
            throw new IllegalArgumentException("A webhook may contain no more than " + MAX_EMBEDS + " embeds, but this one had " + this.embeds().size());
        }

        if (this.components().size() > MAX_COMPONENTS) {
            throw new IllegalArgumentException("A webhook may contain no more than " + MAX_COMPONENTS + " components, but this one had " + this.components().size());
        }

        // todo: better validation
    }


    final class Builder extends WebhookImpl.Builder {
        // auto-generated
    }

}
