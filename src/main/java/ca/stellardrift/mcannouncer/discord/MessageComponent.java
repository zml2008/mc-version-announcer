package ca.stellardrift.mcannouncer.discord;

import com.google.gson.annotations.SerializedName;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.immutables.gson.Gson;
import org.immutables.value.Value;

import java.net.URL;
import java.util.List;

@Gson.TypeAdapters
@Gson.ExpectedSubtypes({MessageComponent.ActionRow.class, MessageComponent.Button.class})
@Value.Enclosing
public interface MessageComponent {

    int type();

    static ActionRow actionRow(final MessageComponent... components) {
        return MessageComponentImpl.ActionRowImpl.of(List.of(components));
    }

    static ActionRow.Builder actionRowBuilder() {
        return new ActionRow.Builder();
    }

    static Button.Builder buttonBuilder() {
        return new Button.Builder();
    }

    @Value.Immutable
    interface ActionRow extends MessageComponent {
        int MAX_CHILDREN = 5;

        @Override
        @Value.Derived
        default int type() {
            return 1;
        }

        @Value.Parameter
        List<MessageComponent> components();

        @Value.Check
        default void validate() {
            if (this.components().size() > MAX_CHILDREN) {
                throw new IllegalArgumentException("Too many children, max of " + MAX_CHILDREN + " but got " + this.components().size());
            }
        }

        final class Builder extends MessageComponentImpl.ActionRowImpl.Builder {

        }
    }

    @Value.Immutable
    interface Button extends MessageComponent {
        @Override
        @Value.Derived
        default int type() {
            return 2;
        }

        ButtonStyle style();
        String label();
        // todo emoji
        @SerializedName("custom_id")
        @Nullable String customId();
        @Nullable URL url();
        @Value.Default
        default boolean disabled() {
            return false;
        }

        @Value.Check
        default void validateType() {
            this.style().validate(this);
        }

        final class Builder extends MessageComponentImpl.ButtonImpl.Builder {

        }

    }

}
