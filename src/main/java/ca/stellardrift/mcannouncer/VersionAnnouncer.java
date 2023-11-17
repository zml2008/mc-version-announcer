package ca.stellardrift.mcannouncer;

import ca.stellardrift.mcannouncer.util.Signals;
import ca.stellardrift.mcannouncer.util.WebhookUtil;
import club.minnced.discord.webhook.WebhookClient;
import club.minnced.discord.webhook.WebhookClientBuilder;
import club.minnced.discord.webhook.send.AllowedMentions;
import club.minnced.discord.webhook.send.WebhookEmbed;
import club.minnced.discord.webhook.send.WebhookEmbedBuilder;
import club.minnced.discord.webhook.send.WebhookMessage;
import club.minnced.discord.webhook.send.WebhookMessageBuilder;
import com.google.gson.JsonParseException;
import okhttp3.Cache;
import okhttp3.OkHttpClient;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.spongepowered.gradle.vanilla.internal.util.Pair;
import org.tinylog.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static java.util.Objects.requireNonNull;

public class VersionAnnouncer implements AutoCloseable {
    private static final String USER_AGENT = "mc-version-announcer (https://github.com/zml2008/mc-version-announcer)";

    public VersionAnnouncer(final Config config) {
        this.config = config;
    }

    /**
     * Entry point for the CLI
     *
     * @param args arguments, usage: {@code <on-disk cache> <webhook URL>}
     */
    public static void main(final String[] args) {
        if (args.length != 1) {
            Logger.error("Incomplete arguments. Usage: ./version-announcer <config.json file>");
            System.exit(1);
        }
        final Path configFile = Path.of(args[0]);
        final Config config;
        try {
            config = Config.load(configFile);
        } catch (final IOException | JsonParseException ex) {
            Logger.error(ex, "Failed to load configuration from {}", args[0]);
            System.exit(1);
            return;
        }

        new VersionAnnouncer(config)
            .start();
    }

    private final Config config;
    private volatile ScheduledExecutorService scheduler;
    private HttpClient http;
    private List<WebhookEndpoint> discordSender;

    private CompletableFuture<ManifestState> last;

    record WebhookEndpoint(String name, @Nullable Set<String> tags, WebhookClient client) {
        public WebhookEndpoint {
            requireNonNull(name, "name");
            tags = tags == null ? Set.of() : Set.copyOf(tags);
            requireNonNull(client, "client");
        }

        boolean isTagged(final @Nullable String tag) {
            return tag == null || this.tags.contains(tag);
        }
    }

    public void start() {
        this.scheduler = Executors.newScheduledThreadPool(4);
        this.http = HttpClient.newBuilder()
            .executor(this.scheduler)
            .build();

        this.last = ManifestState.create(this.http, this.config.cacheDir(), true); // initialize state
        this.discordSender = new ArrayList<>();
        final OkHttpClient httpClient = new OkHttpClient.Builder()
            .addNetworkInterceptor(chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", USER_AGENT)
                        .build()
                ))
            .cache(new Cache(this.config.cacheDir().resolve("okhttp-cache").toFile(), 100 * 1024 * 1024 /* 100 mb */))
            .build();

        for (final var entry : this.config.endpoints().entrySet()) {
            final WebhookClient client = new WebhookClientBuilder(entry.getValue().url().toString())
                .setDaemon(true)
                .setAllowedMentions(AllowedMentions.none())
                .setExecutorService(this.scheduler)
                .setHttpClient(httpClient)
                .build();

            this.discordSender.add(new WebhookEndpoint(entry.getKey(), entry.getValue().tags(), client));
        }

        // Shut down gracefully on ctrl + c
        Signals.register("TERM", () -> {
            Logger.info("Received SIGTERM, shutting down");
            this.close();
        });
        Signals.register("INT", () -> {
            Logger.info("Received SIGINT, shutting down");
            this.close();
        });

        Logger.info("Broadcasting to endpoints: {}", this.config.endpoints().keySet());

        this.sendWebhook(new WebhookMessageBuilder()
            .setUsername("version-announcer")
            .addEmbeds(new WebhookEmbedBuilder()
                .setTitle(new WebhookEmbed.EmbedTitle("Successfully started", null))
                .setColor(0x883399)
                .setDescription("Startup has completed")
                .setFooter(new WebhookEmbed.EmbedFooter("meow :3", null))
                .addField(new WebhookEmbed.EmbedField(false, "Java Version", Runtime.version().toString()))
                .setAuthor(new WebhookEmbed.EmbedAuthor("version-announcer", null, null))
                .build()
            )
            // .content("Successfully started!")
            .build(), EndpointTag.ADMIN);

        /*this.last
            .thenCompose(state -> state.compareVersions("1.16.5", "1.17"))
            .thenAccept(comparison -> {
                try {
                    this.sendReport(List.of(comparison));
                } catch (final MalformedURLException e) {
                    throw new CompletionException(e);
                }
            }).exceptionally(error -> {
                Logger.error(error, "Error during test situation");
                return null;
        });*/

        this.scheduler.scheduleAtFixedRate(() -> {
            try {
                this.sendUpdate();
            } catch (final Exception ex) {
                this.sendError(ex);
            }
        }, 0, 30, TimeUnit.SECONDS);

        Logger.info("version-announcer successfully initialized!");
    }

    private void sendUpdate() {
        Logger.debug("Beginning update check at {}", DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS)));
        // the states
        final var last = this.last.handle((res, error) -> {
            if (error != null) this.sendError(error);
            return res;
        });
        final var nextFuture = ManifestState.create(this.http, this.config.cacheDir(), false);
        final var next = nextFuture.handle((res, error) -> {
            if (error != null) {
                this.sendError(error);
            } else {
                this.last = nextFuture; // update on success
            }
            return res;
        });

        last.thenCombine(next, (lastManifest, nextManifest) -> {
            if (lastManifest == null || nextManifest == null) {
                return null; // error captured earlier
            }
            return lastManifest.compare(nextManifest);
        }).handleAsync((result, error) -> {
                if (error != null) {
                    this.sendError(error);
                } else if (result != null) {
                    if (result.size() > 0) {
                        Logger.info("Successfully detected {} changes", result.size());
                    }

                    for (int i = 0 ; i < result.size(); i += 10) {
                        final int maxIdx = Math.min(i + 10, result.size());
                        final List<CompletableFuture<ComparisonReport>> reports = result.subList(i, maxIdx);
                        final CompletableFuture<?> all = CompletableFuture.allOf(reports.toArray(new CompletableFuture<?>[0]));
                        final int startIdx = i;
                        all.handle(($, reportError) -> {
                            if (reportError != null) {
                                Logger.error(reportError, "Failed to prepare report batch from {} to {}", startIdx, maxIdx);
                                return null;
                            }
                            final var completedReports = new ArrayList<ComparisonReport>(reports.size());
                            for (final var reportFuture : reports) {
                                try {
                                    completedReports.add(reportFuture.get());
                                } catch (final InterruptedException | ExecutionException ex) {
                                    throw new CompletionException(ex);
                                }
                            }

                            try {
                                this.sendReport(completedReports);
                            } catch (final URISyntaxException ex) {
                                Logger.error(ex, "Failed to send report batch from {} to {}", startIdx, maxIdx);
                            }
                            return null;
                        });
                    }
                    Logger.debug("Completed update check with {} changes", result.size());
                }
                return null;
            }, this.scheduler);
    }

    private void sendError(final Throwable thr) {
        Logger.error(thr, "Error occurred while trying to prepare status update");
    }

    // receives a list of max length 10
    private void sendReport(final List<ComparisonReport> reports) throws URISyntaxException {
        if (reports.size() > WebhookMessage.MAX_EMBEDS) {
            throw new IllegalArgumentException("Received a list of length >10");
        }

        WebhookMessageBuilder builder = new WebhookMessageBuilder()
            .setAllowedMentions(AllowedMentions.none());
        int totalLength = 0;
        for (final ComparisonReport report : reports) {
            if (!report.onlyWhenSectionsPresent() || !report.sections().isEmpty()) {
                final WebhookEmbed embed = this.asEmbed(report);
                if (totalLength + WebhookUtil.totalContentLength(embed) > WebhookUtil.MAX_EMBED_LENGTH) {
                    this.sendWebhook(builder.build());
                    builder = new WebhookMessageBuilder()
                        .setAllowedMentions(AllowedMentions.none());
                    totalLength = 0;
                }
                builder.addEmbeds(this.asEmbed(report));
                totalLength += WebhookUtil.totalContentLength(embed);
            }
        }

        if (totalLength == 0) {
            return;
        }


        /* todo: buttons don't seem to work
        // populate buttons (as part of message components)
        // only link buttons work now, since we don't have anywhere to listen to interaction responses
        final List<MessageComponent.Button> buttons = new ArrayList<>();
        if (reports.size() == 1) {
            final var report = reports.get(0);
            for (final Pair<String, URL> link : report.links()) {
                buttons.add(MessageComponent.buttonBuilder()
                    .style(ButtonStyle.LINK)
                    .label(link.first())
                    .url(link.second())
                    .build()
                );
            }
        } else {
            for (final ComparisonReport report : reports) {
                for (final Pair<String, URL> link : report.links()) {
                    buttons.add(MessageComponent.buttonBuilder()
                        .style(ButtonStyle.LINK)
                        .label(report.versionId() + " " + link.first())
                        .url(link.second())
                        .build()
                    );
                }
            }
        }

        final int componentsToSend = Math.min(MessageComponent.ActionRow.MAX_CHILDREN * Webhook.MAX_COMPONENTS, buttons.size());
        for (int i = 0; i < componentsToSend; i += MessageComponent.ActionRow.MAX_CHILDREN) {
            builder.addComponent(
                MessageComponent.actionRowBuilder()
                    .components(buttons.subList(i, Math.min(buttons.size(), componentsToSend)))
                    .build()
            );
        }*/

        this.sendWebhook(builder.build());
    }

    private WebhookEmbed asEmbed(final ComparisonReport report) {
        final WebhookEmbedBuilder builder = new WebhookEmbedBuilder();

        final StringBuilder description = new StringBuilder(report.description());

        if (!description.isEmpty() && !report.links().isEmpty()) {
            description.append("\n\n**Links:**\n");
            boolean first = true;
            for (final Pair<String, URL> link : report.links()) {
                if (!first) {
                    description.append(" | ");
                }
                first = false;
                description.append("[").append(link.first()).append("](").append(link.second()).append(')');
            }
        }

        builder
            .setTitle(new WebhookEmbed.EmbedTitle("Minecraft " + report.versionId(), config.changelogUrlFormat().formatted(report.versionId())))
            .setColor(report.colour())
            .setDescription(description.toString())
            .setFooter(new WebhookEmbed.EmbedFooter("Last updated", null));

        if (report.iconUrl() != null) {
            builder.setThumbnailUrl(report.iconUrl());
        }

        if (report.time() != null) {
            builder.setTimestamp(report.time());
        }

        for (final var entry : report.sections().entrySet()) {
            int count = 0;
            final StringBuilder value = new StringBuilder();
            int chars = 0;
            boolean added = false;
            for (final var it = entry.getValue().iterator(); it.hasNext();) {
                final String line = it.next();
                chars += line.length() + 1;
                if (chars >= WebhookUtil.MAX_FIELD_VALUE_LENGTH) {
                    builder.addField(
                        new WebhookEmbed.EmbedField(
                            false,
                            count == 0 ? entry.getKey() : entry.getKey() + "(cont'd " + count + ')',
                            value.toString()
                        )
                    );
                    added = true;
                    count++;
                    chars = 0;
                    // flush the buffer
                    value.delete(0, value.length());
                }
                value.append(line);
                if (it.hasNext()) {
                    value.append('\n');
                }
            }
            // flush the buffer
            if (!value.isEmpty()) {
                builder.addField(
                    new WebhookEmbed.EmbedField(
                        false,
                        count == 0 ? entry.getKey() : entry.getKey() + "(cont'd " + count + ')',
                        value.toString()
                    )
                );
                added = true;
            }
            if (!added) {
                Logger.warn("Field '{}' in embed for version '{}' has empty value", entry.getKey(), report.versionId());
            }
        }

        return builder.build();
    }

    private CompletableFuture<?> sendWebhook(final WebhookMessage message) {
        return this.sendWebhook(message, null);
    }

    private CompletableFuture<?> sendWebhook(final WebhookMessage message, final @Nullable String tag) {
        final List<CompletableFuture<?>> responses = new ArrayList<>();
        for (final var endpoint : this.discordSender) {
            if (!endpoint.isTagged(tag)) {
                continue;
            }
            responses.add(endpoint.client().send(message));
        }
        return CompletableFuture.allOf(responses.toArray(new CompletableFuture<?>[0]));
    }

    @Override
    public void close() {
        this.http = null;
        final ScheduledExecutorService scheduler = this.scheduler;
        this.scheduler = null;
        if (scheduler != null) {
            scheduler.shutdown();
            boolean success;
            try {
                success = scheduler.awaitTermination(10, TimeUnit.SECONDS);
            } catch (final InterruptedException ex) {
                success = false;
            }

            if (!success) {
                Logger.error("Failed to shut down executor within 10 seconds, forcibly terminating now.");
                scheduler.shutdownNow();
            }
        }

        for (final WebhookEndpoint endpoint : this.discordSender) {
            endpoint.client().close();
        }
    }

    public static HttpRequest get(final URI url) {
        return requestBuilder(url)
            .GET()
            .build();
    }

    public static HttpRequest.Builder requestBuilder(final URI uri) {
        return HttpRequest.newBuilder()
            .setHeader("User-Agent", USER_AGENT)
            .uri(uri);
    }
}
