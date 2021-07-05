package ca.stellardrift.mcannouncer;

import org.immutables.gson.Gson;
import org.immutables.value.Value;
import org.spongepowered.gradle.vanilla.internal.model.VersionClassifier;
import org.spongepowered.gradle.vanilla.internal.model.VersionDescriptor;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A V1 version manifest.
 */
@Value.Immutable
@Gson.TypeAdapters
public interface VersionManifestV1 {
    String MANIFEST_ENDPOINT = "https://launchermeta.mojang.com/mc/game/version_manifest.json";

    /**
     * Get the latest version for classifiers.
     *
     * <p>No latest version is provided for certain classifiers such as
     * {@link VersionClassifier#OLD_ALPHA} or {@link VersionClassifier#OLD_BETA}.</p>
     *
     * @return an unmodifiable map of classifier to version ID
     */
    Map<VersionClassifier, String> latest();

    /**
     * Get descriptors for all available versions.
     *
     * @return the version descriptor
     */
    List<V1Reference> versions();

    /**
     * Attempt to find a version descriptor for a certain version ID.
     *
     * <p>This will only provide information contained in the manifest, without
     * performing network requests.</p>
     *
     * @param id the version ID
     * @return a short descriptor, if any is present
     */
    default Optional<VersionDescriptor.Reference> findDescriptor(final String id) {
        Objects.requireNonNull(id, "id");

        for (final VersionDescriptor.Reference version : this.versions()) {
            if (version.id().equals(id)) {
                return Optional.of(version);
            }
        }

        return Optional.empty();
    }

    @Value.Immutable
    interface V1Reference extends VersionDescriptor.Reference {

        String PREFIX = "v1/packages/";

        @Override
        @Value.Derived
        default String sha1() { // meta v1 doesn't include this for some reason :(
            // https://launchermeta.mojang.com/v1/packages/<hash>/version.json
            final String shortenedUrl = this.url().getPath().substring(PREFIX.length() + 1);
            final int slashIndex = shortenedUrl.indexOf('/');
            return shortenedUrl.substring(0, slashIndex);

        }

    }

}
