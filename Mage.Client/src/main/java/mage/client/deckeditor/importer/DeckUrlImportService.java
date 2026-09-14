package mage.client.deckeditor.importer;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Imports public deck URLs through tightly allowlisted, source-specific adapters.
 *
 * Kept in the desktop client so URL import works without the web bridge or a
 * modified XMage server.
 */
public final class DeckUrlImportService {

    private static final int MAX_DOWNLOAD_BYTES = 5 * 1024 * 1024;
    private static final Pattern ARCHIDEKT = Pattern.compile(
            "^https?://(?:www\\.)?archidekt\\.com/decks/(\\d+)(?:[/#?].*)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MOXFIELD = Pattern.compile(
            "^https?://(?:www\\.)?moxfield\\.com/decks/([A-Za-z0-9_-]+)(?:[/#?].*)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MTGTOP8 = Pattern.compile(
            "^https?://(?:www\\.)?mtgtop8\\.com/(?:event(?:\\.php)?|mtgo)(?:[?#].*)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MTGTOP8_ID = Pattern.compile("[?&]d=(\\d+)", Pattern.CASE_INSENSITIVE);

    private final Gson gson = new Gson();

    public ImportedDeck importUrl(String sourceUrl) throws IOException {
        if (sourceUrl == null || sourceUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("Paste a public deck URL.");
        }

        String normalized = sourceUrl.trim();
        Matcher archidekt = ARCHIDEKT.matcher(normalized);
        if (archidekt.matches()) {
            return importArchidekt(normalized, archidekt.group(1));
        }

        Matcher moxfield = MOXFIELD.matcher(normalized);
        if (moxfield.matches()) {
            try {
                return importText(normalized, "Moxfield", "Moxfield deck",
                        "https://api2.moxfield.com/v2/decks/all/" + moxfield.group(1) + "/export");
            } catch (IOException blocked) {
                throw new IOException("Moxfield blocked direct export. In Moxfield, choose Export, copy the list, then use Import > From clipboard.");
            }
        }

        if (MTGTOP8.matcher(normalized).matches()) {
            Matcher id = MTGTOP8_ID.matcher(normalized);
            if (!id.find()) {
                throw new IllegalArgumentException("That MTGTop8 link does not contain a deck ID.");
            }
            return importText(normalized, "MTGTop8", "MTGTop8 deck " + id.group(1),
                    "https://www.mtgtop8.com/mtgo?d=" + id.group(1));
        }

        throw new IllegalArgumentException("Use a public Archidekt, Moxfield, or MTGTop8 deck URL.");
    }

    private ImportedDeck importArchidekt(String sourceUrl, String deckId) throws IOException {
        String json = download("https://archidekt.com/api/decks/" + deckId + "/", "application/json");
        JsonObject root = gson.fromJson(json, JsonObject.class);
        if (root == null || !root.has("cards")) {
            throw new IOException("Archidekt returned an unfamiliar deck format.");
        }

        Set<String> excludedCategories = new HashSet<>();
        JsonArray categories = root.has("categories") ? root.getAsJsonArray("categories") : new JsonArray();
        for (JsonElement element : categories) {
            JsonObject category = element.getAsJsonObject();
            if (category.has("includedInDeck") && !category.get("includedInDeck").getAsBoolean()) {
                excludedCategories.add(text(category, "name").toLowerCase(Locale.ENGLISH));
            }
        }

        StringBuilder main = new StringBuilder();
        StringBuilder sideboard = new StringBuilder();
        for (JsonElement element : root.getAsJsonArray("cards")) {
            JsonObject entry = element.getAsJsonObject();
            JsonArray cardCategories = entry.has("categories") ? entry.getAsJsonArray("categories") : new JsonArray();
            boolean excluded = false;
            boolean side = booleanValue(entry, "commander") || booleanValue(entry, "isCommander")
                    || booleanValue(entry, "companion") || booleanValue(entry, "isCompanion");

            for (JsonElement categoryElement : cardCategories) {
                String category = categoryElement.isJsonObject()
                        ? text(categoryElement.getAsJsonObject(), "name")
                        : categoryElement.getAsString();
                String lower = category.toLowerCase(Locale.ENGLISH);
                excluded |= excludedCategories.contains(lower);
                side |= lower.equals("sideboard") || lower.equals("commander") || lower.equals("companion");
            }
            if (excluded) {
                continue;
            }

            JsonObject card = entry.has("card") && entry.get("card").isJsonObject()
                    ? entry.getAsJsonObject("card") : null;
            JsonObject oracleCard = card != null && card.has("oracleCard") && card.get("oracleCard").isJsonObject()
                    ? card.getAsJsonObject("oracleCard") : null;
            String name = oracleCard == null ? "" : text(oracleCard, "name");
            int quantity = entry.has("quantity") ? entry.get("quantity").getAsInt() : 1;
            if (!name.isEmpty() && quantity > 0) {
                (side ? sideboard : main).append(quantity).append(' ').append(name).append('\n');
            }
        }

        if (sideboard.length() > 0) {
            main.append("\nSideboard\n").append(sideboard);
        }
        return new ImportedDeck(text(root, "name"), "Archidekt", sourceUrl, main.toString().trim());
    }

    private ImportedDeck importText(String sourceUrl, String source, String defaultName, String endpoint)
            throws IOException {
        String deckText = download(endpoint, "text/plain").trim();
        String lower = deckText.toLowerCase(Locale.ENGLISH);
        if (deckText.isEmpty() || lower.startsWith("<html") || lower.startsWith("<!doctype")) {
            throw new IOException(source + " did not return an exportable text deck.");
        }
        return new ImportedDeck(defaultName, source, sourceUrl, deckText);
    }

    private String download(String endpoint, String accept) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", accept);
        connection.setRequestProperty("User-Agent", "XMage-Desktop/1.0 (personal deck import)");

        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            connection.disconnect();
            throw new IOException("Deck provider returned HTTP " + status + '.');
        }

        try (InputStream input = connection.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_DOWNLOAD_BYTES) {
                    throw new IOException("The imported deck is unexpectedly large.");
                }
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    private String text(JsonObject object, String field) {
        return object.has(field) && !object.get(field).isJsonNull()
                ? object.get(field).getAsString() : "";
    }

    private boolean booleanValue(JsonObject object, String field) {
        return object.has(field) && !object.get(field).isJsonNull()
                && object.get(field).isJsonPrimitive() && object.get(field).getAsBoolean();
    }

    public static final class ImportedDeck {
        private final String name;
        private final String source;
        private final String sourceUrl;
        private final String text;

        ImportedDeck(String name, String source, String sourceUrl, String text) {
            this.name = name;
            this.source = source;
            this.sourceUrl = sourceUrl;
            this.text = text;
        }

        public String getName() {
            return name;
        }

        public String getSource() {
            return source;
        }

        public String getSourceUrl() {
            return sourceUrl;
        }

        public String getText() {
            return text;
        }
    }
}
