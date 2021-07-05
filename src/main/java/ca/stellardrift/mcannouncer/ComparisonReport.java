package ca.stellardrift.mcannouncer;

import ca.stellardrift.mcannouncer.util.ImmutablesStyle;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.immutables.value.Value;
import org.spongepowered.gradle.vanilla.internal.util.Pair;

import java.net.URL;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generate a comparison report.
 *
 * <p>This is designed to work in a format that is compatible with </p>
 */
@ImmutablesStyle
@Value.Immutable
public interface ComparisonReport {
    int COLOUR_ADDED = 0x22BB44;
    int COLOUR_REMOVED = 0xBB2244;
    int COLOUR_MODIFIED = 0x8822CC;

    static Builder builder() {
        return new Builder();
    }

    String versionId();
    @Nullable String iconUrl();
    String description();
    int colour();
    Map<String, List<String>> sections(); // title -> lines
    Set<Pair<String, URL>> links(); // unused, name -> URL once message components implemented
    @Nullable Instant time();

    @Value.Default
    default boolean onlyWhenSectionsPresent() {
        return false;
    }

    final class Builder extends ComparisonReportImpl.Builder {

        Builder newVersion(final String previous) {
            return this.colour(COLOUR_ADDED)
                .description("This is a new version. Changes listed from " + previous + ".");
        }

        Builder removedVersion() {
            return this.colour(COLOUR_REMOVED)
                .description("Version has been removed from the manifest :(");
        }

        Builder modifiedVersion() {
            return this.colour(COLOUR_MODIFIED)
                .description("Version has been modified. Changes from previous instance:");
        }

        void putSectionIfNotEmpty(final String title, final List<String> values) {
            if (!values.isEmpty()) {
                this.putSection(title, values);
            }
        }
    }

}
