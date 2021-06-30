package ca.stellardrift.mcannouncer.util;

import ca.stellardrift.mcannouncer.discord.ButtonStyle;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.spongepowered.gradle.vanilla.internal.model.Argument;
import org.spongepowered.gradle.vanilla.internal.model.GroupArtifactVersion;
import org.spongepowered.gradle.vanilla.internal.model.rule.FeatureRule;
import org.spongepowered.gradle.vanilla.internal.model.rule.OperatingSystemRule;
import org.spongepowered.gradle.vanilla.internal.model.rule.RuleDeclarationTypeAdapter;
import org.spongepowered.gradle.vanilla.internal.util.GsonSerializers;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.ServiceLoader;

public class GsonUtils {
    private static final TypeAdapter<Instant> INSTANT_ADAPTER = new TypeAdapter<Instant>() {
        @Override
        public void write(final JsonWriter out, final Instant value) throws IOException {
            out.value(DateTimeFormatter.ISO_INSTANT.format(value.truncatedTo(ChronoUnit.SECONDS)));
        }

        @Override
        public Instant read(final JsonReader in) throws IOException {
            if (in.peek() != JsonToken.STRING) {
                throw new JsonSyntaxException("Expected a string to parse as an Instant");
            }
            return Instant.parse(in.nextString());
        }
    }.nullSafe();

    private static final TypeAdapter<ButtonStyle> BUTTON_STYLE = new TypeAdapter<ButtonStyle>() {
        @Override
        public void write(final JsonWriter out, final ButtonStyle value) throws IOException {
            out.value(value.id());
        }

        @Override
        public ButtonStyle read(final JsonReader in) throws IOException {
            if (in.peek() != JsonToken.NUMBER) {
                throw new JsonSyntaxException("Expected a type as a number");
            }
            final int styleId = in.nextInt();
            final @Nullable ButtonStyle style = ButtonStyle.byId(styleId);
            if (style == null) {
                throw new JsonSyntaxException("Unknown style ID " + styleId);
            }
            return style;
        }
    }.nullSafe();

    public static final Gson GSON;

    static {
        final GsonBuilder builder = new GsonBuilder()
            .registerTypeAdapter(ZonedDateTime.class, GsonSerializers.ZDT)
            .registerTypeAdapter(Instant.class, GsonUtils.INSTANT_ADAPTER)
            .registerTypeAdapter(GroupArtifactVersion.class, GsonSerializers.GAV)
            .registerTypeAdapter(ButtonStyle.class, GsonUtils.BUTTON_STYLE)
            .registerTypeAdapterFactory(new RuleDeclarationTypeAdapter.Factory(FeatureRule.INSTANCE, OperatingSystemRule.INSTANCE))
            .registerTypeAdapterFactory(new Argument.ArgumentTypeAdapter.Factory());

        // Discover type adapters generated by Immutables AP
        for (final TypeAdapterFactory factory : ServiceLoader.load(TypeAdapterFactory.class, org.spongepowered.gradle.vanilla.internal.util.GsonUtils.class.getClassLoader())) {
            builder.registerTypeAdapterFactory(factory);
        }

        GSON = builder.create();
    }

    public static <T> T parseFromJson(final URL url, final Class<T> type) throws IOException {
        try (final BufferedReader reader = new BufferedReader(new InputStreamReader(Objects.requireNonNull(url, "url").openStream(), StandardCharsets.UTF_8))) {
            return GsonUtils.GSON.fromJson(reader, type);
        }
    }

    public static <T> T parseFromJson(final Path path, final Class<T> type) throws IOException {
        try (final BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return GsonUtils.GSON.fromJson(reader, type);
        }
    }

    public static <T> T parseFromJson(final File file, final Class<T> type) throws IOException {
        try (final BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            return GsonUtils.GSON.fromJson(reader, type);
        }
    }

    public static <T> void writeToJson(final Path path, final T value, final Class<T> type) throws IOException {
        try (final BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GsonUtils.GSON.toJson(value, type, writer);
        }
    }

    private GsonUtils() {
    }

}
