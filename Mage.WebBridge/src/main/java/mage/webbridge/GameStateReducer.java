package mage.webbridge;

import mage.view.CardView;
import mage.view.CardsView;
import mage.view.AbilityPickerView;
import mage.view.GameClientMessage;
import mage.view.GameView;
import mage.view.PermanentView;
import mage.view.PlayerView;
import mage.view.CommandObjectView;
import mage.view.CounterView;
import mage.view.ManaPoolView;
import mage.choices.Choice;
import mage.util.MultiAmountMessage;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Produces a small, browser-safe view of XMage's much larger serialized game graph. */
final class GameStateReducer {

    Map<String, Object> reduce(UUID gameId, long messageId, GameView game, Map<String, Object> prompt) {
        Set<UUID> playable = game.getCanPlayObjects() == null
                ? Collections.emptySet() : game.getCanPlayObjects().getObjects().keySet();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("gameId", gameId == null ? null : gameId.toString());
        result.put("messageId", messageId);
        result.put("turn", game.getTurn());
        result.put("phase", game.getPhase() == null ? null : game.getPhase().toString());
        result.put("step", game.getStep() == null ? null : game.getStep().toString());
        result.put("activePlayer", game.getActivePlayerName());
        result.put("priorityPlayer", game.getPriorityPlayerName());
        result.put("player", game.isPlayer());
        result.put("players", players(game.getPlayers(), playable));
        result.put("hand", cards(game.getMyHand(), playable));
        result.put("stack", cards(game.getStack(), playable));
        result.put("playableIds", ids(playable));
        result.put("prompt", prompt);
        return result;
    }

    Map<String, Object> prompt(String type, long messageId, GameClientMessage message) {
        Map<String, Object> prompt = new LinkedHashMap<>();
        prompt.put("type", type);
        prompt.put("messageId", messageId);
        prompt.put("message", plainText(message.getMessage()));
        prompt.put("required", message.isFlag());
        prompt.put("min", message.getMin());
        prompt.put("max", message.getMax());
        Map<String, Object> options = simpleOptions(message.getOptions());
        prompt.put("choices", options);
        prompt.put("cards", cards(message.getCardsView1()));
        prompt.put("otherCards", cards(message.getCardsView2()));
        if (message.getMessages() != null) {
            if (message.getMessage() == null || message.getMessage().trim().isEmpty()) {
                Object header = options.get("header");
                Object title = options.get("title");
                prompt.put("message", plainText(header instanceof String ? (String) header
                        : title instanceof String ? (String) title : null));
            }
            List<Map<String, Object>> amounts = new ArrayList<>();
            for (MultiAmountMessage amount : message.getMessages()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("label", plainText(amount.message));
                item.put("min", amount.min);
                item.put("max", amount.max);
                item.put("value", amount.defaultValue);
                amounts.add(item);
            }
            prompt.put("amountItems", amounts);
        }
        List<String> targets = new ArrayList<>();
        if (message.getTargets() != null) {
            for (UUID target : message.getTargets()) {
                targets.add(target.toString());
            }
        }
        prompt.put("targets", targets);
        prompt.put("possibleTargets", optionIds(message.getOptions(), "possibleTargets"));
        prompt.put("chosenTargets", optionIds(message.getOptions(), "chosenTargets"));
        Choice choice = message.getChoice();
        if (choice != null) {
            prompt.put("required", choice.isRequired());
            if ((message.getMessage() == null || message.getMessage().isEmpty()) && choice.getMessage() != null) {
                prompt.put("message", plainText(choice.getMessage()));
            }
            prompt.put("subMessage", plainText(choice.getSubMessage()));
            List<Map<String, Object>> choices = new ArrayList<>();
            if (choice.isKeyChoice()) {
                for (Map.Entry<String, String> entry : choice.getKeyChoices().entrySet()) {
                    choices.add(choice(entry.getKey(), plainText(entry.getValue()), false));
                }
            } else {
                for (String value : choice.getChoices()) {
                    choices.add(choice(value, plainText(value), false));
                }
            }
            if (choice.isSpecialEnabled() && choice.isSpecialCanBeEmpty()) {
                choices.add(choice("#", plainText(choice.getSpecialText()), true));
            }
            prompt.put("choiceItems", choices);
        }
        return prompt;
    }

    Map<String, Object> abilityPrompt(long messageId, AbilityPickerView picker) {
        Map<String, Object> prompt = new LinkedHashMap<>();
        prompt.put("type", "GAME_CHOOSE_ABILITY");
        prompt.put("messageId", messageId);
        prompt.put("message", plainText(picker.getMessage()));
        prompt.put("required", false);
        List<Map<String, Object>> choices = new ArrayList<>();
        for (Map.Entry<UUID, String> entry : picker.getChoices().entrySet()) {
            choices.add(choice(entry.getKey().toString(), plainText(entry.getValue()), false));
        }
        prompt.put("abilityItems", choices);
        return prompt;
    }

    private Map<String, Object> choice(String value, String label, boolean special) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("value", value);
        item.put("label", label);
        item.put("special", special);
        return item;
    }

    private String plainText(String value) {
        if (value == null) {
            return null;
        }
        return value
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("<[^>]+>", "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }

    private List<Map<String, Object>> players(List<PlayerView> views, Set<UUID> playable) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (PlayerView player : views) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", player.getPlayerId().toString());
            item.put("name", player.getName());
            item.put("life", player.getLife());
            item.put("handCount", player.getHandCount());
            item.put("libraryCount", player.getLibraryCount());
            item.put("wins", player.getWins());
            item.put("winsNeeded", player.getWinsNeeded());
            item.put("priorityTime", player.getPriorityTimeLeftSecs());
            item.put("bufferTime", player.getBufferTimeLeft());
            item.put("graveyardCount", player.getGraveyard().size());
            item.put("exileCount", player.getExile().size());
            item.put("controlled", player.getControlled());
            item.put("active", player.isActive());
            item.put("priority", player.hasPriority());
            item.put("left", player.hasLeft());
            item.put("mana", mana(player.getManaPool()));
            item.put("counters", counters(player.getCounters()));
            item.put("graveyard", cards(player.getGraveyard(), playable));
            item.put("exile", cards(player.getExile(), playable));
            item.put("command", commandObjects(player.getCommandObjectList(), playable));
            item.put("battlefield", permanents(player.getBattlefield(), playable));
            result.add(item);
        }
        return result;
    }

    private Map<String, Integer> mana(ManaPoolView pool) {
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("W", pool.getWhite());
        result.put("U", pool.getBlue());
        result.put("B", pool.getBlack());
        result.put("R", pool.getRed());
        result.put("G", pool.getGreen());
        result.put("C", pool.getColorless());
        return result;
    }

    private List<Map<String, Object>> counters(List<CounterView> views) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (CounterView counter : views) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", counter.getName());
            item.put("count", counter.getCount());
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> commandObjects(List<CommandObjectView> views, Set<UUID> playable) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (CommandObjectView command : views) {
            if (command instanceof CardView) {
                result.add(card((CardView) command, playable));
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", command.getId().toString());
            item.put("name", command.getName());
            item.put("type", "Command zone");
            item.put("setCode", command.getExpansionSetCode());
            item.put("playable", playable.contains(command.getId()));
            item.put("choosable", command.isChoosable());
            item.put("selected", command.isSelected());
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> cards(CardsView views) {
        return cards(views, Collections.emptySet());
    }

    private List<Map<String, Object>> cards(CardsView views, Set<UUID> playable) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (views == null) {
            return result;
        }
        for (CardView card : views.values()) {
            result.add(card(card, playable));
        }
        return result;
    }

    private List<Map<String, Object>> permanents(Map<UUID, PermanentView> views, Set<UUID> playable) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (PermanentView permanent : views.values()) {
            Map<String, Object> item = card(permanent, playable);
            item.put("tapped", permanent.isTapped());
            item.put("damage", permanent.getDamage());
            item.put("summoningSickness", permanent.hasSummoningSickness());
            item.put("controlled", permanent.isControlled());
            item.put("attachedTo", permanent.getAttachedTo() == null
                    ? null : permanent.getAttachedTo().toString());
            result.add(item);
        }
        return result;
    }

    private Map<String, Object> card(CardView card, Set<UUID> playable) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", card.getId().toString());
        item.put("name", card.getDisplayName() == null || card.getDisplayName().isEmpty()
                ? card.getName() : card.getDisplayName());
        item.put("type", card.getTypeText());
        item.put("manaCost", card.getManaCostStr());
        item.put("power", card.getPower());
        item.put("toughness", card.getToughness());
        item.put("loyalty", card.getLoyalty());
        item.put("setCode", card.getExpansionSetCode());
        item.put("cardNumber", card.getCardNumber());
        item.put("faceDown", card.isFaceDown());
        item.put("token", card.isToken());
        item.put("playable", playable.contains(card.getId()));
        item.put("choosable", card.isChoosable());
        item.put("selected", card.isSelected());
        return item;
    }

    private List<String> ids(Iterable<UUID> values) {
        List<String> result = new ArrayList<>();
        for (UUID value : values) {
            result.add(value.toString());
        }
        return result;
    }

    private List<String> optionIds(Map<String, Serializable> options, String key) {
        List<String> result = new ArrayList<>();
        if (options == null) {
            return result;
        }
        Object values = options.get(key);
        if (!(values instanceof Iterable)) {
            return result;
        }
        for (Object value : (Iterable<?>) values) {
            if (value instanceof UUID) {
                result.add(value.toString());
            }
        }
        return result;
    }

    private Map<String, Object> simpleOptions(Map<String, Serializable> options) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (options == null) {
            return result;
        }
        for (Map.Entry<String, Serializable> entry : options.entrySet()) {
            Object value = entry.getValue();
            if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
                result.put(entry.getKey(), value);
            }
        }
        return result;
    }
}
