package mage.webbridge;

import mage.cards.decks.DeckCardInfo;
import mage.cards.decks.DeckCardLists;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Rebuilds a deck only from the exact card-copy pool supplied by XMage. */
final class SideboardDeckBuilder {

    private SideboardDeckBuilder() {
    }

    static DeckCardLists build(String name, Map<UUID, DeckCardInfo> allowed,
                               List<String> mainIds, List<String> sideboardIds) {
        List<UUID> main = parse(allowed, mainIds);
        List<UUID> sideboard = parse(allowed, sideboardIds);
        Set<UUID> submitted = new HashSet<>();
        for (UUID id : main) {
            if (!submitted.add(id)) {
                throw new IllegalArgumentException("A card copy was included more than once.");
            }
        }
        for (UUID id : sideboard) {
            if (!submitted.add(id)) {
                throw new IllegalArgumentException("A card copy was included more than once.");
            }
        }
        if (!submitted.equals(allowed.keySet())) {
            throw new IllegalArgumentException("The submitted deck must contain exactly the cards XMage provided.");
        }

        DeckCardLists deck = new DeckCardLists();
        deck.setName(name);
        for (UUID id : main) {
            deck.getCards().add(allowed.get(id).copy());
        }
        for (UUID id : sideboard) {
            deck.getSideboard().add(allowed.get(id).copy());
        }
        return deck;
    }

    private static List<UUID> parse(Map<UUID, DeckCardInfo> allowed, List<String> values) {
        if (values == null) {
            throw new IllegalArgumentException("Both main deck and sideboard card lists are required.");
        }
        List<UUID> result = new ArrayList<>();
        for (String value : values) {
            UUID id;
            try {
                id = UUID.fromString(value == null ? "" : value.trim());
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("A valid sideboard card ID is required.");
            }
            if (!allowed.containsKey(id)) {
                throw new IllegalArgumentException("That card copy is not part of the sideboarding pool.");
            }
            result.add(id);
        }
        return result;
    }
}
