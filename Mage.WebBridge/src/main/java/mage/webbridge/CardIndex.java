package mage.webbridge;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Base64;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Vector;
import java.util.zip.GZIPInputStream;

/** Compact preferred-printing index generated from this XMage revision. */
final class CardIndex {

    private final Map<String, Printing> cards;
    private final Map<String, Printing> printings;

    CardIndex() {
        Map<String, Printing> loadedCards = new LinkedHashMap<>();
        Map<String, Printing> loadedPrintings = new LinkedHashMap<>();
        load(loadedCards, loadedPrintings);
        cards = Collections.unmodifiableMap(loadedCards);
        printings = Collections.unmodifiableMap(loadedPrintings);
    }

    Printing find(String name) {
        if (name == null) {
            return null;
        }
        return cards.get(name.trim().toLowerCase(Locale.ENGLISH));
    }

    Printing find(String setCode, String cardNumber) {
        if (setCode == null || cardNumber == null) {
            return null;
        }
        return printings.get(printingKey(setCode, cardNumber));
    }

    private void load(Map<String, Printing> loadedCards, Map<String, Printing> loadedPrintings) {
        try (InputStream stream = openIndex();
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split("\\t", -1);
                if (fields.length != 4) {
                    throw new IllegalStateException("The bundled XMage card index is invalid.");
                }
                Printing printing = new Printing(fields[1], fields[2], fields[3]);
                loadedCards.put(fields[0], printing);
                loadedPrintings.put(printingKey(fields[2], fields[3]), printing);
            }
        } catch (IOException error) {
            throw new IllegalStateException("The bundled XMage card index could not be read.", error);
        }
    }

    private InputStream openIndex() throws IOException {
        Vector<InputStream> parts = new Vector<>();
        for (int part = 0; part < 4; part++) {
            InputStream stream = CardIndex.class.getResourceAsStream(
                    "/cards-index.tsv.gz.b64.part-" + part);
            if (stream == null) {
                throw new IllegalStateException("The bundled XMage card index is missing part " + part + '.');
            }
            parts.add(stream);
        }
        Enumeration<InputStream> streams = parts.elements();
        InputStream encoded = new java.io.SequenceInputStream(streams);
        return new GZIPInputStream(Base64.getDecoder().wrap(encoded));
    }

    private String printingKey(String setCode, String cardNumber) {
        return setCode.toUpperCase(Locale.ENGLISH) + '\t' + cardNumber;
    }

    static final class Printing {
        final String name;
        final String setCode;
        final String cardNumber;

        Printing(String name, String setCode, String cardNumber) {
            this.name = name;
            this.setCode = setCode;
            this.cardNumber = cardNumber;
        }
    }
}
