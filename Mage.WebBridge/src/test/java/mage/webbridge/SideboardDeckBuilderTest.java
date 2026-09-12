package mage.webbridge;

import mage.cards.decks.DeckCardInfo;
import mage.cards.decks.DeckCardLists;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SideboardDeckBuilderTest {

    @Test
    void movesOnlyKnownPhysicalCopies() {
        UUID mountain = UUID.randomUUID();
        UUID forest = UUID.randomUUID();
        Map<UUID, DeckCardInfo> pool = new LinkedHashMap<>();
        pool.put(mountain, new DeckCardInfo("Mountain", "484", "M21"));
        pool.put(forest, new DeckCardInfo("Forest", "274", "M21"));

        DeckCardLists deck = SideboardDeckBuilder.build("Test", pool,
                Collections.singletonList(forest.toString()), Collections.singletonList(mountain.toString()));

        assertEquals("Forest", deck.getCards().get(0).getCardName());
        assertEquals("Mountain", deck.getSideboard().get(0).getCardName());
    }

    @Test
    void rejectsMissingDuplicateAndUnknownCopies() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Map<UUID, DeckCardInfo> pool = new LinkedHashMap<>();
        pool.put(first, new DeckCardInfo("Mountain", "484", "M21"));
        pool.put(second, new DeckCardInfo("Forest", "274", "M21"));

        assertThrows(IllegalArgumentException.class, () -> SideboardDeckBuilder.build("Test", pool,
                Collections.singletonList(first.toString()), Collections.emptyList()));
        assertThrows(IllegalArgumentException.class, () -> SideboardDeckBuilder.build("Test", pool,
                Arrays.asList(first.toString(), first.toString()), Collections.singletonList(second.toString())));
        assertThrows(IllegalArgumentException.class, () -> SideboardDeckBuilder.build("Test", pool,
                Arrays.asList(first.toString(), UUID.randomUUID().toString()), Collections.emptyList()));
    }
}
