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

    static SelectMenu.Builder selectBuilder() {
        return new SelectMenu.Builder();
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

    @Value.Immutable
    interface SelectMenu extends MessageComponent {

        @Override
        @Value.Derived
        default int type() {
            return 3;
        }

        @SerializedName("custom_id")
        String customId(); // max 100 chars
        List<Option> options(); // max 25
        @Nullable String placeholder(); // max 100 chars
        @Value.Default
        default int minValues() { // min 0, max 25
            return 1;
        }

        @Value.Default
        default int maxValues() { // min 1, max 25
            return 1;
        }

        @Value.Immutable
        interface Option {

            static Builder builder() {
                return new Builder();
            }

            String label(); // max 25 chars
            String value(); // max 100 chars
            @Nullable String description(); // max 50 chars
            // todo emoji
            @Value.Default
            @SerializedName("default")
            default boolean isDefault() {
               return false;
            }

            final class Builder extends MessageComponentImpl.OptionImpl.Builder {
                // auto-generated
            }

        }

        final class Builder extends MessageComponentImpl.SelectMenuImpl.Builder {
            // auto-generate
        }

    }

}
