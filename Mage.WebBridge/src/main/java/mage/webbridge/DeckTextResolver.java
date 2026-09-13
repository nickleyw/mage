package mage.webbridge;

import mage.cards.decks.CardNameUtil;
import mage.cards.decks.DeckCardInfo;
import mage.cards.decks.DeckCardLists;
import mage.view.SimpleCardView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts common exported deck text into the native records sent by an XMage client. */
final class DeckTextResolver {

    private static final Pattern CARD_LINE = Pattern.compile("^(\\d+)\\s*x?\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRINTING_SUFFIX = Pattern.compile(
            "\\s+\\([A-Z0-9_]+\\)\\s+[A-Z0-9-]+(?:\\s+\\*F\\*)?$", Pattern.CASE_INSENSITIVE);
    private static final Set<String> MAIN_HEADINGS = new LinkedHashSet<>(
            Arrays.asList("deck", "main", "mainboard", "decklist"));
    private static final Set<String> SIDE_HEADINGS = new LinkedHashSet<>(
            Arrays.asList("sideboard", "side board", "commander", "commanders", "companion"));
    private static final Set<String> IGNORE_HEADINGS = new LinkedHashSet<>(
            Arrays.asList("maybeboard", "maybe board", "considering", "tokens"));

    private final CardIndex cardIndex = new CardIndex();

    Result resolve(String deckName, String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("Decklist text is required.");
        }
        DeckCardLists deck = new DeckCardLists();
        deck.setName(deckName == null || deckName.trim().isEmpty() ? "Web deck" : deckName.trim());

        List<String> skipped = new ArrayList<>();
        Map<String, Integer> unresolved = new LinkedHashMap<>();
        Map<String, String> correctedNames = new LinkedHashMap<>();
        int mainCount = 0;
        int sideboardCount = 0;
        Section section = Section.MAIN;
        String[] lines = text.split("\\r?\\n", -1);

        for (int index = 0; index < lines.length; index++) {
            String original = lines[index];
            String line = original.trim();
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("#")) {
                continue;
            }

            String heading = line.replaceFirst(":$", "").trim().toLowerCase(Locale.ENGLISH);
            if (MAIN_HEADINGS.contains(heading)) {
                section = Section.MAIN;
                continue;
            }
            if (SIDE_HEADINGS.contains(heading)) {
                section = Section.SIDEBOARD;
                continue;
            }
            if (IGNORE_HEADINGS.contains(heading)) {
                section = Section.IGNORE;
                continue;
            }

            boolean sideboardLine = line.regionMatches(true, 0, "SB:", 0, 3);
            if (sideboardLine) {
                line = line.substring(3).trim();
            }
            Matcher matcher = CARD_LINE.matcher(line);
            if (!matcher.matches()) {
                skipped.add("Line " + (index + 1) + ": " + original.trim());
                continue;
            }
            int quantity;
            try {
                quantity = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException error) {
                skipped.add("Line " + (index + 1) + ": " + original.trim());
                continue;
            }
            if (quantity < 1 || section == Section.IGNORE) {
                continue;
            }

            String cardName = cleanCardName(matcher.group(2));
            CardIndex.Printing card = cardIndex.find(cardName);
            if (card == null) {
                unresolved.put(cardName, unresolved.containsKey(cardName)
                        ? unresolved.get(cardName) + quantity : quantity);
                continue;
            }
            if (!card.name.equalsIgnoreCase(cardName)) {
                correctedNames.put(cardName, card.name);
            }

            List<DeckCardInfo> target = sideboardLine || section == Section.SIDEBOARD
                    ? deck.getSideboard() : deck.getCards();
            for (int copy = 0; copy < quantity; copy++) {
                target.add(new DeckCardInfo(card.name, card.cardNumber, card.setCode));
            }
            if (target == deck.getSideboard()) {
                sideboardCount += quantity;
            } else {
                mainCount += quantity;
            }
        }
        return new Result(deck, mainCount, sideboardCount, unresolved, skipped, correctedNames);
    }

    DeckCardInfo resolveCard(SimpleCardView card) {
        CardIndex.Printing printing = cardIndex.find(card.getExpansionSetCode(), card.getCardNumber());
        if (printing == null) {
            throw new IllegalArgumentException("Card from XMage was not found in the bundled index: "
                    + card.getExpansionSetCode() + " " + card.getCardNumber());
        }
        return new DeckCardInfo(printing.name, card.getCardNumber(), card.getExpansionSetCode());
    }

    private String cleanCardName(String value) {
        String name = PRINTING_SUFFIX.matcher(value).replaceFirst("")
                .replaceFirst("(?i)\\s+\\*CMDR\\*$", "")
                .replaceFirst("(?i)\\s+\\*F\\*$", "")
                .trim();
        name = CardNameUtil.normalizeCardName(name);
        if (name.contains("//") && !name.contains(" // ")) {
            name = name.replace("//", " // ");
        }
        return name.replaceFirst("(?<=[^/])\\s*/\\s*(?=[^/])", " // ");
    }

    private enum Section { MAIN, SIDEBOARD, IGNORE }

    static final class Result {
        private final DeckCardLists deck;
        private final int mainCount;
        private final int sideboardCount;
        private final Map<String, Integer> unresolved;
        private final List<String> skipped;
        private final Map<String, String> correctedNames;

        Result(DeckCardLists deck, int mainCount, int sideboardCount,
               Map<String, Integer> unresolved, List<String> skipped,
               Map<String, String> correctedNames) {
            this.deck = deck;
            this.mainCount = mainCount;
            this.sideboardCount = sideboardCount;
            this.unresolved = unresolved;
            this.skipped = skipped;
            this.correctedNames = correctedNames;
        }

        DeckCardLists deck() {
            return deck;
        }

        boolean canJoin() {
            return mainCount + sideboardCount > 0 && unresolved.isEmpty();
        }

        Map<String, Object> summary() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name", deck.getName());
            result.put("mainCount", mainCount);
            result.put("sideboardCount", sideboardCount);
            result.put("unresolved", unresolved);
            result.put("skipped", skipped);
            result.put("correctedNames", correctedNames);
            List<String> warnings = new ArrayList<>();
            String lowerName = deck.getName().toLowerCase(Locale.ENGLISH);
            if (mainCount == 100 && sideboardCount == 0
                    && (lowerName.contains("edh") || lowerName.contains("commander"))) {
                warnings.add("This looks like a Commander deck, but no commander is marked. Put the commander after a Commander heading (XMage stores it in the sideboard).");
            }
            if (!skipped.isEmpty()) {
                warnings.add(skipped.size() + " decklist line" + (skipped.size() == 1 ? " was" : "s were") + " skipped.");
            }
            result.put("warnings", warnings);
            result.put("canJoin", canJoin());
            result.put("cardIndexReady", true);
            return result;
        }
    }
}
