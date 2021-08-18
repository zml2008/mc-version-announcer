package ca.stellardrift.mcannouncer;

import com.google.gson.JsonSyntaxException;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.spongepowered.gradle.vanilla.internal.model.Download;
import org.spongepowered.gradle.vanilla.internal.model.DownloadClassifier;
import org.spongepowered.gradle.vanilla.internal.model.JavaRuntimeVersion;
import org.spongepowered.gradle.vanilla.internal.model.Library;
import org.spongepowered.gradle.vanilla.internal.model.VersionDescriptor;
import org.spongepowered.gradle.vanilla.internal.util.FileUtils;
import org.spongepowered.gradle.vanilla.internal.util.GsonUtils;
import org.spongepowered.gradle.vanilla.internal.util.Pair;
import org.spongepowered.gradle.vanilla.repository.ResolutionResult;
import org.tinylog.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents a single fetch of the manifest.
 */
public class ManifestState {
    private static final String UNKNOWN = "*(unknown)*";
    private static final String NONE = "*(none)*";

    private final VersionManifestV1 manifest;
    private final String manifestEtag;
    private final Path cacheLocation;
    private final Map<String, VersionDescriptor.Reference> references = new TreeMap<>();
    private final Map<String, CompletableFuture<ResolutionResult<VersionDescriptor.Full>>> loadedDescriptors = new ConcurrentHashMap<>();
    private final HttpClient client;

    public static CompletableFuture<ManifestState> create(final HttpClient client, final Path cacheLocation, final boolean trustExisting) {
        final URI requestUri = URI.create(VersionManifestV1.MANIFEST_ENDPOINT + "?t=" + System.currentTimeMillis());
        final Path destination = cacheLocation.resolve("manifest.json");
        final Path etagFile = cacheLocation.resolve("manifest.etag");

        final HttpRequest.Builder builder = VersionAnnouncer.requestBuilder(requestUri).GET();

        // if etag already exists, perform a HEAD request to compare the etag
        // https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/ETag
        if (Files.exists(etagFile)) {
            final String etag;
            try {
                etag = Files.readString(etagFile, StandardCharsets.UTF_8);
            } catch (final IOException ex) {
                return CompletableFuture.failedFuture(ex);
            }

            if (Files.exists(destination) && trustExisting) {
                // load and return stored
                try (final var reader = Files.newBufferedReader(destination, StandardCharsets.UTF_8)) {
                    return CompletableFuture.completedFuture(new ManifestState(GsonUtils.GSON.fromJson(reader, VersionManifestV1.class), etag, client, cacheLocation));
                } catch (final IOException | JsonSyntaxException ex) {
                    Logger.error(ex, "Failed to load existing version manifest from disk, re-downloading");
                }
            }

            builder.header("If-None-Match", etag.trim());
        }

        try {
            FileUtils.createDirectoriesSymlinkSafe(destination.getParent());
        } catch (final IOException ex) {
            return CompletableFuture.failedFuture(ex);
        }

        final CompletableFuture<? extends HttpResponse<?>> manifest = client.sendAsync(
            VersionAnnouncer.get(requestUri),
            info -> {
                if (info.statusCode() == 304) {
                    return HttpResponse.BodySubscribers.replacing(destination);
                } else {
                    return HttpResponse.BodySubscribers.ofFile(destination,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING
                    );
                }
            }
        );

        return manifest.thenApply(response -> {
            final Optional<String> etag = response.headers().firstValue("ETag");

            // Cache etag
            if (etag.isPresent()) {
                try {
                    Files.writeString(etagFile, etag.get());
                } catch (final IOException ex) {
                    throw new CompletionException(ex);
                }
            }

            try (final var reader = Files.newBufferedReader(destination, StandardCharsets.UTF_8)) {
                return new ManifestState(GsonUtils.GSON.fromJson(reader, VersionManifestV1.class), etag.orElse(null), client, cacheLocation);
            } catch (final IOException | JsonSyntaxException ex) {
                throw new CompletionException(ex);
            }
        });
    }

    private ManifestState(final VersionManifestV1 manifest, final String manifestEtag, final HttpClient client, final Path cacheLocation) {
        this.manifest = manifest;
        this.manifestEtag = manifestEtag;
        this.client = client;
        this.cacheLocation = cacheLocation;

        for (final VersionDescriptor.Reference ref : manifest.versions()) {
            this.references.put(ref.id(), ref);
        }
    }

    /**
     * Compare two cached version manifests.
     *
     * <p>This is the real core of the logic.</p>
     *
     * @param that newer manifest state
     * @return a set of versions that have changed.
     */
    public List<CompletableFuture<ComparisonReport>> compare(final ManifestState that) {
        if (Objects.equals(this.manifestEtag, that.manifestEtag)) {
            return List.of();
        }

        // compute the our versions, their versions, and shared versions
        final Map<String, VersionDescriptor.Reference> ourVersions = new HashMap<>(this.references);
        final Map<String, VersionDescriptor.Reference> theirVersions = new HashMap<>(that.references);
        final Set<String> sharedVersions = new HashSet<>();

        // compute the difference
        for (final var it = ourVersions.entrySet().iterator(); it.hasNext();) {
            final var entry = it.next();
            if (theirVersions.remove(entry.getKey()) != null) {
                sharedVersions.add(entry.getKey());
                it.remove();
            }
        }

        final List<CompletableFuture<ComparisonReport>> reports = new ArrayList<>();

        // find versions only in this (old): list removed
        // removed versions
        for (final var entry : ourVersions.entrySet()) {
            reports.add(CompletableFuture.completedFuture(ComparisonReport.builder()
                .versionId(entry.getKey())
                .removedVersion()
                .time(entry.getValue().time().toInstant())
                .build()));
        }

        // find versions only in that (new): compare against latest in ours
        // added versions
        if (!theirVersions.isEmpty()) {
            final VersionDescriptor.Reference ourLatest = this.manifest.versions().get(0);
            final CompletableFuture<ResolutionResult<VersionDescriptor.Full>> ourLatestFull = this.version(ourLatest.id());
            for (final var entry : theirVersions.entrySet()) {
                final var builder = ComparisonReport.builder()
                    .versionId(entry.getKey())
                    .time(entry.getValue().time().toInstant())
                    .newVersion(ourLatest.id());
                reports.add(ourLatestFull.thenCombine(that.version(entry.getKey()), (oldLatest, added) -> {
                   this.populateComparison(oldLatest.get(), added.get(), builder, false);
                   return builder.build();
                }));
            }
        }

        // find any version that exists in both, and if different:
        // generate an elementwise diff, into a discord embed
        // changed versions
        for (final String changedId : sharedVersions) {
            final VersionDescriptor.Reference ours = this.references.get(changedId);
            final VersionDescriptor.Reference theirs = that.references.get(changedId);
            if (ours == null || theirs == null) {
                Logger.warn("Encountered 'shared' version that was not actually shared: {}", changedId);
                continue;
            }
            if (ours.sha1().equals(theirs.sha1())) { // no change
                continue;
            }

            final var builder = ComparisonReport.builder()
                .versionId(changedId)
                .modifiedVersion()
                .time(theirs.time().toInstant())
                .onlyWhenSectionsPresent(true);

            reports.add(this.version(changedId).thenCombine(that.version(changedId), (original, changed) -> {
                this.populateComparison(original.get(), changed.get(), builder, true);
                return builder.build();
            }));
        }

        return reports;
    }

    public CompletableFuture<ComparisonReport> compareVersions(final String oldId, final String newId) {
        final VersionDescriptor.Reference ours = this.references.get(oldId);
        final VersionDescriptor.Reference theirs = this.references.get(newId);
        if (ours == null || theirs == null) {
            Logger.warn("Could not find one of the versions {} or {}", oldId, newId);
            return CompletableFuture.failedFuture(new NoSuchElementException("Could not find " + oldId + " or " + newId));
        }

        if (ours.sha1().equals(theirs.sha1())) { // no change
            return CompletableFuture.completedFuture(ComparisonReport.builder()
                .versionId(newId)
                .modifiedVersion()
                .time(theirs.time().toInstant())
                .description("No changes since " + oldId)
                .build());
        }

        final var builder = ComparisonReport.builder()
            .versionId(newId)
            .modifiedVersion()
            .time(theirs.time().toInstant())
            .description("Changes since " + oldId);

        return this.version(oldId).thenCombine(this.version(newId), (original, changed) -> {
            this.populateComparison(original.get(), changed.get(), builder, false);
            return builder.build();
        });
    }

    private void populateComparison(
        final VersionDescriptor.Full original,
        final VersionDescriptor.Full modified,
        final ComparisonReport.Builder diff,
        final boolean isModifiedVersion
    ) {
        // downloads
        if (!Objects.equals(original.downloads(), modified.downloads())) {
            diff.putSectionIfNotEmpty("Downloads", this.populateDownloads(original.downloads(), modified.downloads(), isModifiedVersion));
        }
        // asset index
        if (!Objects.equals(original.assets(), modified.assets())) {
            diff.putSection("Assets", List.of(String.format("`%s` -> `%s`", original.assets(), modified.assets())));
        }
        // libraries
        if (!Objects.equals(original.libraries(), modified.libraries())) {
            diff.putSectionIfNotEmpty("Libraries", this.populateLibraries(original.libraries(), modified.libraries()));
        }
        // java version
        if (!Objects.equals(original.javaVersion(), modified.javaVersion())) {
            diff.putSectionIfNotEmpty("Java Version", this.populateJavaVersion(original.javaVersion(), modified.javaVersion()));
        }

        // links to downloads
        for (final Map.Entry<DownloadClassifier, Download> entry : modified.downloads().entrySet()) {
            diff.addLink(Pair.of(entry.getKey().name().toLowerCase(Locale.ROOT), entry.getValue().url()));
        }
    }

    private List<String> populateJavaVersion(final @Nullable JavaRuntimeVersion current, final @Nullable JavaRuntimeVersion modified) {
        final List<String> changes = new ArrayList<>();
        final String currentMajorVersion = current == null ? UNKNOWN : String.valueOf(current.majorVersion());
        final String currentComponent = current == null ? UNKNOWN : current.component();
        final String modifiedMajorVersion = modified == null ? UNKNOWN : String.valueOf(modified.majorVersion());
        final String modifiedComponent = modified == null ? UNKNOWN : modified.component();

        if (!currentMajorVersion.equals(modifiedMajorVersion)) {
            changes.add("__Major Version__: "+ currentMajorVersion + " -> " + modifiedMajorVersion);
        }

        if (!currentComponent.equals(modifiedComponent)) {
            changes.add("__Component__: " + currentComponent + " -> " + modifiedComponent);
        }

        return changes;
    }

    private List<String> populateLibraries(final List<Library> current, final List<Library> modified) {
        final Map<String, Library> ours = this.index(current);
        final Map<String, Library> theirs = this.index(modified);
        final Map<String, Pair<Library, Library>> shared = new HashMap<>();
        // compute the difference
        for (final var it = ours.entrySet().iterator(); it.hasNext();) {
            final var entry = it.next();
            final @Nullable Library theirLibrary = theirs.remove(entry.getKey());
            if (theirLibrary != null) {
                shared.put(entry.getKey(), Pair.of(entry.getValue(), theirLibrary));
                it.remove();
            }
        }


        final List<String> result = new ArrayList<>();
        for (final var library : ours.entrySet()) { // removed
            result.add("- `" + library.getKey() + "`: `" + library.getValue().name().version() + "` -> " + NONE);
        }
        for (final var library : theirs.entrySet()) { // added
            result.add("- `" + library.getKey() + "`: " + NONE + " -> `" + library.getValue().name().version() + '`');
        }
        for (final var change : shared.entrySet()) { // changed
            final Pair<Library, Library> oldToNew = change.getValue();
            if (!oldToNew.first().name().equals(oldToNew.second().name())) {
                result.add("- `" + change.getKey() + "`: `" + oldToNew.first().name().version() + "` -> `" + oldToNew.second().name().version() + '`');
            }
        }

        return result;
    }

    private Map<String, Library> index(final List<Library> libraries) {
        final var result = new HashMap<String, Library>();
        for (final Library library : libraries) {
            if (!library.isNatives()) {
                result.put(library.name().group() + ':' + library.name().artifact(), library);
            }
        }
        return result;
    }

    private List<String> populateDownloads(final Map<DownloadClassifier, Download> current, final Map<DownloadClassifier, Download> modified, final boolean listChangedHashes) {
        final Map<DownloadClassifier, Download> ours = new HashMap<>(current);
        final Map<DownloadClassifier, Download> theirs = new HashMap<>(modified);
        final Map<DownloadClassifier, Pair<Download, Download>> shared = new HashMap<>();
        // compute the difference
        for (final var it = ours.entrySet().iterator(); it.hasNext();) {
            final var entry = it.next();
            final @Nullable Download theirDownload = theirs.remove(entry.getKey());
            if (theirDownload != null) {
                shared.put(entry.getKey(), Pair.of(entry.getValue(), theirDownload));
                it.remove();
            }
        }
        final List<String> result = new ArrayList<>();
        for (final var download : ours.keySet()) { // removed
            result.add("Removed: `" + download + "`");
        }
        for (final var download : theirs.keySet()) { // added
            result.add("Added: `" + download + "`");
        }
        if (listChangedHashes) {
            for (final var change : shared.entrySet()) { // modified
                result.add("Modified: `" + change.getKey() + "`: `" + change.getValue().first().sha1() + "` -> `" + change.getValue().second().sha1() + "`");
            }
        }

        return result;
    }

    public CompletableFuture<ResolutionResult<VersionDescriptor.Full>> version(final String version) {
        final VersionDescriptor.Reference ref = this.references.get(version);
        if (ref == null) {
            return CompletableFuture.completedFuture(ResolutionResult.notFound());
        }

        return this.loadedDescriptors.computeIfAbsent(ref.id(), id -> {
            final var localFile = this.pathOf(ref);
            if (Files.exists(localFile)) {
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        return ResolutionResult.result(GsonUtils.parseFromJson(localFile, VersionDescriptor.Full.class), true);
                    } catch (final IOException ex) {
                        throw new CompletionException(ex);
                    }
                });
            }

            final CompletableFuture<HttpResponse<Path>> request;
            try {
                FileUtils.createDirectoriesSymlinkSafe(localFile.getParent());
                request = this.client.sendAsync(
                    VersionAnnouncer.get(ref.url().toURI()),
                    HttpResponse.BodyHandlers.ofFile(localFile)
                );
            } catch (final URISyntaxException | IOException ex) {
                return CompletableFuture.failedFuture(ex);
            }

            return request.thenApply(response -> {
                if (response.statusCode() == 200) {
                    try {
                        return ResolutionResult.result(GsonUtils.parseFromJson(localFile, VersionDescriptor.Full.class), false);
                    } catch (final IOException ex) {
                        throw new CompletionException(ex);
                    }
                } else {
                    return ResolutionResult.notFound();
                }
            });
        });
    }

    private Path pathOf(final VersionDescriptor.Reference ref) {
        return this.cacheLocation.resolve("versions").resolve(ref.id()).resolve(ref.sha1() + ".json");
    }

}
