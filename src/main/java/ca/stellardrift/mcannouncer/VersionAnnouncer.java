package ca.stellardrift.mcannouncer;

import ca.stellardrift.mcannouncer.discord.AllowedMention;
import ca.stellardrift.mcannouncer.discord.Embed;
import ca.stellardrift.mcannouncer.discord.Webhook;
import ca.stellardrift.mcannouncer.util.GsonUtils;
import com.google.gson.JsonParseException;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.spongepowered.gradle.vanilla.internal.util.Pair;
import org.tinylog.Logger;
import sun.misc.Signal;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class VersionAnnouncer implements AutoCloseable {

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

    private CompletableFuture<ManifestState> last;

    public void start() {
        this.scheduler = Executors.newScheduledThreadPool(4);
        this.http = HttpClient.newBuilder()
            .executor(this.scheduler)
            .build();

        this.last = ManifestState.create(this.http, this.config.cacheDir()); // initialize state

        // Shut down gracefully on ctrl + c
        sun.misc.Signal.handle(new Signal("TERM"), sig -> {
            Logger.info("Received SIGTERM, shutting down");
            this.close();
        });
        sun.misc.Signal.handle(new Signal("INT"), sig -> {
            Logger.info("Received SIGINT, shutting down");
            this.close();
        });

        this.sendWebhook(Webhook.builder()
            .username("version-announcer")
            .addEmbed(Embed.builder()
                .title("Successfully started")
                .color(0x883399)
                .description("Startup has completed")
                .footer("meow :3", null)
                .addField("Java Version", Runtime.version().toString(), false)
                .author(Embed.Author.builder().name("version-announcer").build())
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
        final var nextFuture = ManifestState.create(this.http, this.config.cacheDir());
        final var next = nextFuture.handle((res, error) -> {
            if (error != null) this.sendError(error);
            return res;
        });

        last.thenCombine(next, ManifestState::compare)
            .handleAsync((result, error) -> {
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
                            } catch (final MalformedURLException ex) {
                                Logger.error(ex, "Failed to send report batch from {} to {}", startIdx, maxIdx);
                            }
                            return null;
                        });
                    }
                    Logger.debug("Completed update check with {} changes", result.size());
                }
                return null;
            }, this.scheduler);
        this.last = nextFuture;
    }

    private void sendError(final Throwable thr) {
        Logger.error(thr, "Error occurred while trying to prepare status update");
    }

    // receives a list of max length 10
    private void sendReport(final List<ComparisonReport> reports) throws MalformedURLException {
        if (reports.size() > Webhook.MAX_EMBEDS) {
            throw new IllegalArgumentException("Received a list of length >10");
        }

        final Webhook.Builder builder = Webhook.builder();
        builder.allowedMentions(AllowedMention.none());
        boolean embedAdded = false;
        for (final ComparisonReport report : reports) {
            if (!report.onlyWhenSectionsPresent() || !report.sections().isEmpty()) {
                builder.addEmbed(this.asEmbed(report));
                embedAdded = true;
            }
        }

        if (!embedAdded) {
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

    private Embed asEmbed(final ComparisonReport report) throws MalformedURLException {
        final Embed.Builder builder = Embed.builder();

        final StringBuilder description = new StringBuilder(report.description());

        if (description.length() > 0 && !report.links().isEmpty()) {
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
            .title("Minecraft " + report.versionId())
            .color(report.colour())
            .description(description.toString())
            .url("https://quiltmc.org/mc-patchnotes/#" + report.versionId())
            .footer(Embed.Footer.of("Last updated"));

        if (report.iconUrl() != null) {
            builder.thumbnail(new URL(report.iconUrl()), OptionalInt.empty(), OptionalInt.empty());
        }

        if (report.time() != null) {
            builder.timestamp(report.time());
        }

        for (final var entry : report.sections().entrySet()) {
            int count = 0;
            final StringBuilder value = new StringBuilder();
            int chars = 0;
            for (final var it = entry.getValue().iterator(); it.hasNext();) {
                final String line = it.next();
                chars += line.length() + 1;
                if (chars >= Embed.Field.MAX_VALUE) {
                    builder.addField(
                        count == 0 ? entry.getKey() : entry.getKey() + "(cont'd " + count + ')',
                        value.toString(),
                        false
                    );
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
            if (value.length() > 0) {
                builder.addField(
                    count == 0 ? entry.getKey() : entry.getKey() + "(cont'd " + count + ')',
                    value.toString(),
                    false
                );
            }
        }

        return builder.build();
    }

    private CompletableFuture<?> sendWebhook(final Webhook hook) {
        return this.sendWebhook(hook, null);
    }

    private CompletableFuture<?> sendWebhook(final Webhook hook, final @Nullable String tag) {
        final String payload = GsonUtils.GSON.toJson(hook);
        Logger.debug(payload);

        final List<CompletableFuture<?>> responses = new ArrayList<>();
        for (final var endpoint : this.config.endpoints().entrySet()) {
            if (!endpoint.getValue().isTagged(tag)) {
                continue;
            }

            responses.add(this.http.sendAsync(
                requestBuilder(endpoint.getValue().webhookUrl())
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .header("Content-Type", "application/json")
                    .build(),
                HttpResponse.BodyHandlers.ofString() // json text?
            ).handle((response, error) -> {
                if (error != null) {
                    Logger.error(error, "Failed to send webhook payload to endpoint {}", endpoint.getKey());
                } else if (response != null && response.statusCode() >= 400) { // error
                    Logger.error("Received an error from discord while sending to {}, status {}: {}", endpoint.getKey(), response.statusCode(), response.body());
                }
                return response;
            }));
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
    }

    static HttpRequest get(final URI url) {
        return requestBuilder(url)
            .GET()
            .build();
    }

    static HttpRequest.Builder requestBuilder(final URI uri) {
        return HttpRequest.newBuilder()
            .setHeader("User-Agent", "mc-version-announcer")
            .uri(uri);
    }
}
