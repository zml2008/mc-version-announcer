package ca.stellardrift.mcannouncer.discord;

import com.google.gson.annotations.SerializedName;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.immutables.gson.Gson;
import org.immutables.value.Value;

import java.net.URI;
import java.net.URL;
import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;

/**
 * @see <a href="https://discord.com/developers/docs/resources/channel#embed-object">Discord API Docs</a>
 * @see <a href="https://discord.com/developers/docs/resources/channel#embed-limits">Embed API Limits</a>
 */
@Gson.TypeAdapters
@Value.Enclosing
@Value.Immutable
public interface Embed {

    int MAX_LENGTH = 6000;

    int MAX_TITLE = 256;

    int MAX_DESCRIPTION = 4096;

    int MAX_FIELDS = 25;

    static Builder builder() {
        return new Builder();
    }

    String title(); // 256 chars max
    @Nullable String description(); // 4096 chars max
    @Nullable String url();
    @Nullable Instant timestamp();
    OptionalInt color();
    @Nullable Footer footer();
    @Nullable Media image();
    @Nullable Media thumbnail();
    @Nullable Media video(); // not settable on webhooks
    @Nullable Provider provider(); // not settable on webhooks
    @Nullable Author author();
    @Value.Default
    default List<Field> fields() { // max 25
        return List.of();
    }

    @Value.Immutable
    interface Footer {
        int MAX_TEXT = 2048;

        static Footer of(final String text) {
            return EmbedImpl.FooterImpl.of(text, null);
        }

        @Value.Parameter
        String text(); // max 2048 chars
        @Value.Parameter
        @SerializedName("icon_url")
        @Nullable URL iconUrl();

        @Value.Check
        default void validateLength() {
            if (this.text().length() > MAX_TEXT) {
                throw new IllegalArgumentException("Footer text must be no longer than " + MAX_TEXT + " characters long (got " + this.text().length() + ')');
            }
        }
    }

    @Value.Immutable
    interface Media {

        static Media of(final URI uri) {
            return EmbedImpl.MediaImpl.of(uri, OptionalInt.empty(), OptionalInt.empty());
        }

        static Media of(final URI uri, final int height, final int width) {
            return EmbedImpl.MediaImpl.of(uri, height, width);
        }

        @Value.Parameter
        URI uri();
        @Value.Parameter
        OptionalInt height();
        @Value.Parameter
        OptionalInt width();
    }

    @Value.Immutable
    interface Provider {

        static Provider of(final String name, final URL url) {
            return EmbedImpl.ProviderImpl.of(name, url);
        }

        @Value.Parameter
        String name();
        @Value.Parameter
        URL url();
    }

    @Value.Immutable
    interface Author {
        int MAX_NAME = 256;

        static Builder builder() {
            return new Builder();
        }

        String name();
        @Nullable URL url();
        @SerializedName("icon_url")
        @Nullable URL iconUrl();

        @Value.Check
        default void validateNameLength() {
            if (this.name().length() > MAX_NAME) {
                throw new IllegalArgumentException("Name must be no longer than " + MAX_NAME + " characters long (got " + this.name().length() + ')');
            }
        }

        final class Builder extends EmbedImpl.AuthorImpl.Builder {
            // auto-generated
        }

    }

    @Value.Immutable
    interface Field {
        int MAX_NAME = 256;
        int MAX_VALUE = 1024;

        static Builder builder() {
            return new Builder();
        }

        static Field ofSeparated(final String name, final String value) {
            return EmbedImpl.FieldImpl.of(name, value, false);
        }

        static Field ofInline(final String name, final String value) {
            return EmbedImpl.FieldImpl.of(name, value, true);
        }

        @Value.Parameter
        String name(); // max 256 chars
        @Value.Parameter
        String value(); // max 1024 chars
        @Value.Parameter
        boolean inline();

        @Value.Check
        default void validateFieldsLength() {
            if (this.name().length() > MAX_NAME) {
                throw new IllegalArgumentException("Name must be no longer than " + MAX_NAME + " characters long (got " + this.name().length() + ')');
            }
            if (this.value().length() > MAX_VALUE) {
                throw new IllegalArgumentException("Value must be no longer than " + MAX_VALUE + " characters long (got " + this.value().length() + ')');
            }
        }

        final class Builder extends EmbedImpl.FieldImpl.Builder {
            // auto-generated
        }
    }

    /**
     * Get the total length of content, for calculating Discord rate limits.
     *
     * @return the total length
     */
    @Value.Derived
    default int totalContentLength() {
        // max total length
        int totalLength = 0;
        totalLength += this.title().length();
        if (this.description() != null) {
            totalLength += this.description().length();
        }
        if (this.footer() != null) {
            totalLength += this.footer().text().length();
        }
        if (this.author() != null) {
            totalLength += this.author().name().length();
        }
        for (final Field field : this.fields()) {
            totalLength += field.name().length();
            totalLength += field.value().length();
        }
        return totalLength;
    }

    @Value.Check
    default void validate() {
        if (this.title().length() > MAX_TITLE) {
            throw new IllegalArgumentException("Title must be no longer than " + MAX_TITLE + " characters long (got " + this.title().length() + ')');
        }
        if (this.description() != null && this.description().length() > MAX_DESCRIPTION) {
            throw new IllegalArgumentException("Description must be no longer than " + MAX_DESCRIPTION + " characters long (got " + this.description().length() + ')');
        }
        if (this.fields().size() > MAX_FIELDS) {
            throw new IllegalArgumentException("An embed can have no more than " + MAX_FIELDS + " fields (got " + this.fields().size() + ')');
        }

        final int totalLength = this.totalContentLength();
        if (totalLength > MAX_LENGTH) {
            throw new IllegalArgumentException("The combined text content of this embed (title, description, footer, author, field name + content) must be no longer than " + MAX_LENGTH + " characters, but was " + totalLength);
        }
    }

    class Builder extends EmbedImpl.Builder {
        // auto-generated
    }

}
