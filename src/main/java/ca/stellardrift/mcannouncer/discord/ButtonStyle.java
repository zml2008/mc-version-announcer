package ca.stellardrift.mcannouncer.discord;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A type of {@link MessageComponent.Button}
 */
public enum ButtonStyle {
    PRIMARY(1),
    SECONDARY(2),
    SUCCESS(3),
    DANGER(4),
    LINK(5) {
        @Override
        void validate(final MessageComponent.Button button) {
            if (button.customId() != null || button.url() == null) {
                throw new IllegalArgumentException("Link buttons must have a URL but not a custom id");
            }
        }
    };

    private static final List<@Nullable ButtonStyle> BY_ID = new ArrayList<>();
    private final int id;

    ButtonStyle(final int id) {
        this.id = id;
    }

    // throw IAE if invalid
    void validate(final MessageComponent.Button button) {
        if (button.customId() == null || button.url() != null) {
            throw new IllegalArgumentException("Non-link buttons must have a custom id but not a URL");
        }
    }

    public int id() {
        return this.id;
    }

    public static @Nullable ButtonStyle byId(final int id) {
        if (id < 0 || id >= BY_ID.size()) {
            return null;
        }

        return BY_ID.get(id);
    }

    static {
        final ButtonStyle[] values = ButtonStyle.values();
        for (final ButtonStyle style : values) {
            while (BY_ID.size() < style.id) {
                BY_ID.add(null);
            }
            BY_ID.add(style.id, style);
        }

        for (final ButtonStyle style : values) {
            if (ButtonStyle.byId(style.id) != style) {
                throw new IllegalStateException("Mismatch, expected " + style + " but got " + ButtonStyle.byId(style.id));
            }
        }
    }
}
