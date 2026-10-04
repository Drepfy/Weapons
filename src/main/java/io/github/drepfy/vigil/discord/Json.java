package io.github.drepfy.vigil.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Small helpers for Discord's JSON. */
final class Json {

    /** Only visible to the user who used the command or button. */
    static final int EPHEMERAL = 64;

    private Json() {
    }

    /** A string member, or {@code null} when missing or null. */
    static String str(JsonObject object, String key) {
        if (object == null || !object.has(key)) {
            return null;
        }
        JsonElement value = object.get(key);
        return value.isJsonPrimitive() ? value.getAsString() : null;
    }

    /** An object member, or {@code null}. */
    static JsonObject obj(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonObject()) {
            return null;
        }
        return object.getAsJsonObject(key);
    }

    /** An array member, or an empty array. */
    static JsonArray arr(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonArray()) {
            return new JsonArray();
        }
        return object.getAsJsonArray(key);
    }

    /** A message that never pings anyone. */
    static JsonObject message(String content) {
        JsonObject message = new JsonObject();
        message.addProperty("content", cut(content, 2000));
        message.add("allowed_mentions", noMentions());
        return message;
    }

    static JsonObject ephemeral(String content) {
        JsonObject message = message(content);
        message.addProperty("flags", EPHEMERAL);
        return message;
    }

    static JsonObject embedMessage(JsonObject embed) {
        JsonObject message = new JsonObject();
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        message.add("embeds", embeds);
        message.add("allowed_mentions", noMentions());
        return message;
    }

    static JsonObject noMentions() {
        JsonObject mentions = new JsonObject();
        mentions.add("parse", new JsonArray());
        return mentions;
    }

    static JsonObject field(String name, String value, boolean inline) {
        JsonObject field = new JsonObject();
        field.addProperty("name", cut(name, 250));
        field.addProperty("value", value == null || value.isBlank() ? "-" : cut(value, 1020));
        field.addProperty("inline", inline);
        return field;
    }

    static JsonObject button(int style, String label, String customId) {
        JsonObject button = new JsonObject();
        button.addProperty("type", 2);
        button.addProperty("style", style);
        button.addProperty("label", label);
        button.addProperty("custom_id", customId);
        return button;
    }

    static JsonArray row(JsonObject... components) {
        JsonObject row = new JsonObject();
        row.addProperty("type", 1);
        JsonArray list = new JsonArray();
        for (JsonObject component : components) {
            list.add(component);
        }
        row.add("components", list);
        JsonArray rows = new JsonArray();
        rows.add(row);
        return rows;
    }

    /**
     * Names shown inside a line: characters that change Discord's formatting (bold, italics,
     * strike-through, code, spoilers, links) are escaped. Line-start markers like # are never
     * reached because names are not at the start of a line.
     */
    static String escape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (char c : text.toCharArray()) {
            if ("\\*_~`|[]".indexOf(c) >= 0) {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    static String cut(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() > max ? text.substring(0, Math.max(0, max - 3)) + "..." : text;
    }
}
