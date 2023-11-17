package ca.stellardrift.mcannouncer.util;

import club.minnced.discord.webhook.send.WebhookEmbed;

public final class WebhookUtil {
    public static final int MAX_EMBED_LENGTH = 6000;
    public static final int MAX_FIELD_VALUE_LENGTH = 1024;
    private WebhookUtil() {
    }

    public static int totalContentLength(WebhookEmbed embed) {
        // max total length
        int totalLength = 0;
        totalLength += embed.getTitle().getText().length();
        if (embed.getDescription() != null) {
            totalLength += embed.getDescription().length();
        }
        if (embed.getFooter() != null) {
            totalLength += embed.getFooter().getText().length();
        }
        if (embed.getAuthor() != null) {
            totalLength += embed.getAuthor().getName().length();
        }
        for (final WebhookEmbed.EmbedField field : embed.getFields()) {
            totalLength += field.getName().length();
            totalLength += field.getName().length();
        }
        return totalLength;
    }
}
