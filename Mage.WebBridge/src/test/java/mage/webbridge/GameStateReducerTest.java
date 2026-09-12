package mage.webbridge;

import mage.view.AbilityPickerView;
import mage.view.GameClientMessage;
import mage.util.MultiAmountMessage;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GameStateReducerTest {

    @Test
    void reducesAbilityPickerToSafeOrderedChoices() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Map<UUID, String> modes = new LinkedHashMap<>();
        modes.put(first, "1. <b>Cast the front face</b>");
        modes.put(second, "2. Activate &amp; draw");

        AbilityPickerView picker = new AbilityPickerView(null, modes, "<html>Choose<br>one</html>");
        Map<String, Object> prompt = new GameStateReducer().abilityPrompt(42, picker);

        assertEquals("GAME_CHOOSE_ABILITY", prompt.get("type"));
        assertEquals(42L, prompt.get("messageId"));
        assertEquals("Choose\none", prompt.get("message"));
        assertFalse((Boolean) prompt.get("required"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) prompt.get("abilityItems");
        assertEquals(2, items.size());
        assertEquals(first.toString(), items.get(0).get("value"));
        assertEquals("1. Cast the front face", items.get(0).get("label"));
        assertEquals(second.toString(), items.get(1).get("value"));
        assertEquals("2. Activate & draw", items.get(1).get("label"));
    }

    @Test
    void exposesMultiAmountRangesAndDefaults() {
        Map<String, java.io.Serializable> options = new LinkedHashMap<>();
        options.put("title", "Divide damage");
        options.put("canCancel", true);
        GameClientMessage message = new GameClientMessage(null, options, Arrays.asList(
                new MultiAmountMessage("First target", 0, 3, 1),
                new MultiAmountMessage("Second target", 1, 3, 2)
        ), 3, 3);

        Map<String, Object> prompt = new GameStateReducer().prompt("GAME_GET_MULTI_AMOUNT", 9, message);

        assertEquals(3, prompt.get("min"));
        assertEquals(3, prompt.get("max"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) prompt.get("amountItems");
        assertEquals("First target", items.get(0).get("label"));
        assertEquals(1, items.get(0).get("value"));
        assertEquals(1, items.get(1).get("min"));
        assertEquals(3, items.get(1).get("max"));
    }
}
