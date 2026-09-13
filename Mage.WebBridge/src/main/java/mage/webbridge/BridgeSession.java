package mage.webbridge;

import mage.interfaces.MageClient;
import mage.interfaces.callback.ClientCallback;
import mage.interfaces.callback.ClientCallbackMethod;
import mage.constants.TableState;
import mage.constants.PlayerAction;
import mage.players.PlayerType;
import mage.players.net.UserData;
import mage.players.net.UserGroup;
import mage.remote.Connection;
import mage.remote.Session;
import mage.remote.SessionImpl;
import mage.utils.MageVersion;
import mage.view.TableClientMessage;
import mage.view.TableView;
import mage.view.AbilityPickerView;
import mage.view.GameClientMessage;
import mage.view.GameView;
import mage.view.DeckView;
import mage.view.SimpleCardView;
import mage.view.ChatMessage;
import mage.cards.decks.DeckCardInfo;
import mage.cards.decks.DeckCardLists;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Owns the single XMage player session represented by this bridge process. */
final class BridgeSession implements MageClient {

    private static final MageVersion VERSION = new MageVersion(BridgeSession.class);

    private final EventBroker events;
    private final Session session;
    private final DeckTextResolver deckResolver = new DeckTextResolver();
    private final GameStateReducer gameReducer = new GameStateReducer();
    private volatile String state = "disconnected";
    private volatile String host = "";
    private volatile int port;
    private volatile String username = "";
    private volatile String lastError = "";
    private volatile String pendingJoinError = "";
    private volatile CountDownLatch pendingJoinErrorSignal = new CountDownLatch(0);
    private volatile UUID joinedTableId;
    private volatile UUID currentGameId;
    private volatile Map<String, Object> currentGame;
    private volatile Map<String, Object> currentPrompt;
    private volatile ClientCallbackMethod currentPromptMethod;
    private volatile long currentPromptMessageId = -1;
    private volatile Set<UUID> currentAllowedIds = Collections.emptySet();
    private volatile Set<String> currentAllowedStrings = Collections.emptySet();
    private volatile UUID currentSideboardTableId;
    private volatile String currentSideboardDeckName = "Web deck";
    private volatile Map<UUID, DeckCardInfo> currentSideboardCards = Collections.emptyMap();
    private volatile Map<String, Object> currentSideboard;

    BridgeSession(EventBroker events) {
        this.events = events;
        this.session = new SessionImpl(this);
    }

    synchronized Map<String, Object> connect(String requestedHost, int requestedPort,
                                              String requestedUsername, String password) {
        if (session.isConnected()) {
            throw new IllegalStateException("Disconnect the current XMage session first.");
        }

        host = requestedHost;
        port = requestedPort;
        username = requestedUsername;
        lastError = "";
        state = "connecting";
        events.publish("session.connecting", connectionDetails());

        Connection connection = new Connection();
        connection.setHost(host);
        connection.setPort(port);
        connection.setUsername(username);
        connection.setPassword(password == null ? "" : password);
        connection.setUserIdStr("xmage-web-bridge");
        connection.setProxyType(Connection.ProxyType.NONE);

        UserData userData = UserData.getDefaultUserDataView();
        userData.setGroupId(UserGroup.PLAYER.getGroupId());
        connection.setUserData(userData);

        if (!session.connectStart(connection)) {
            state = "error";
            lastError = session.getLastError();
            Map<String, Object> error = connectionDetails();
            error.put("message", lastError);
            events.publish("session.error", error);
            throw new IllegalStateException(lastError.isEmpty()
                    ? "XMage rejected the connection." : lastError);
        }
        return snapshot();
    }

    synchronized Map<String, Object> snapshot() {
        Map<String, Object> result = connectionDetails();
        result.put("connected", session.isConnected());
        result.put("version", VERSION.toString());
        result.put("lastError", lastError);
        result.put("joinedTableId", joinedTableId == null ? null : joinedTableId.toString());
        result.put("game", currentGame);
        result.put("sideboard", sideboardSnapshot());

        List<Map<String, Object>> tables = new ArrayList<>();
        if (session.isConnected()) {
            result.put("sessionId", session.getSessionId());
            UUID roomId = session.getMainRoomId();
            result.put("roomId", roomId == null ? null : roomId.toString());
            if (roomId != null) {
                try {
                    Collection<TableView> currentTables = session.getTables(roomId);
                    for (TableView table : currentTables) {
                        tables.add(summarize(table));
                    }
                } catch (Exception error) {
                    lastError = error.getMessage() == null ? error.toString() : error.getMessage();
                    result.put("lastError", lastError);
                }
            }
        }
        result.put("tableCount", tables.size());
        result.put("tables", tables);
        return result;
    }

    synchronized Map<String, Object> disconnect() {
        session.connectStop(false, false);
        state = "disconnected";
        joinedTableId = null;
        clearGame();
        return snapshot();
    }

    Map<String, Object> gameSnapshot() {
        Map<String, Object> result = currentGame;
        if (result == null) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("active", false);
            empty.put("gameId", currentGameId == null ? null : currentGameId.toString());
            return empty;
        }
        return result;
    }

    Map<String, Object> sideboardSnapshot() {
        return currentSideboard;
    }

    synchronized Map<String, Object> submitSideboard(List<String> mainIds, List<String> sideboardIds) {
        requireConnected();
        if (currentSideboardTableId == null || currentSideboard == null) {
            throw new IllegalStateException("XMage is not waiting for a sideboard submission.");
        }
        DeckCardLists deck = SideboardDeckBuilder.build(currentSideboardDeckName,
                currentSideboardCards, mainIds, sideboardIds);
        UUID tableId = currentSideboardTableId;
        if (!session.submitDeck(tableId, deck)) {
            throw new IllegalStateException(session.getLastError() == null || session.getLastError().isEmpty()
                    ? "XMage did not accept the sideboarded deck." : session.getLastError());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accepted", true);
        result.put("tableId", tableId.toString());
        result.put("mainCount", deck.getCards().size());
        result.put("sideboardCount", deck.getSideboard().size());
        clearSideboard();
        events.publish("sideboard.submitted", result);
        return result;
    }

    synchronized Map<String, Object> respondToGame(long messageId, String action, String value) {
        requireConnected();
        if (currentGameId == null || currentPromptMethod == null || currentPrompt == null) {
            throw new IllegalStateException("XMage is not waiting for a game response.");
        }
        if (messageId != currentPromptMessageId) {
            throw new IllegalArgumentException("That prompt has changed. Review the current game prompt and try again.");
        }
        if (action == null || action.trim().isEmpty()) {
            throw new IllegalArgumentException("A response action is required.");
        }

        boolean sent;
        switch (action) {
            case "boolean":
                if (!allowsBoolean(currentPromptMethod)) {
                    throw new IllegalArgumentException("The current XMage prompt does not accept a button response.");
                }
                if (!"true".equals(value) && !"false".equals(value)) {
                    throw new IllegalArgumentException("Boolean responses must be true or false.");
                }
                sent = session.sendPlayerBoolean(currentGameId, Boolean.parseBoolean(value));
                break;
            case "uuid":
                if (!allowsUuid(currentPromptMethod)) {
                    throw new IllegalArgumentException("The current XMage prompt does not accept a card or target.");
                }
                UUID selected = parseResponseId(value);
                if (!currentAllowedIds.contains(selected)) {
                    throw new IllegalArgumentException("That object is not selectable for the current prompt.");
                }
                sent = session.sendPlayerUUID(currentGameId, selected);
                break;
            case "integer":
                if (currentPromptMethod != ClientCallbackMethod.GAME_GET_AMOUNT) {
                    throw new IllegalArgumentException("The current XMage prompt does not accept an amount.");
                }
                int amount = parseAmount(value);
                int min = ((Number) currentPrompt.get("min")).intValue();
                int max = ((Number) currentPrompt.get("max")).intValue();
                if (amount < min || amount > max) {
                    throw new IllegalArgumentException("Choose an amount from " + min + " to " + max + ".");
                }
                sent = session.sendPlayerInteger(currentGameId, amount);
                break;
            case "string":
                if (currentPromptMethod != ClientCallbackMethod.GAME_CHOOSE_CHOICE
                        && currentPromptMethod != ClientCallbackMethod.GAME_GET_MULTI_AMOUNT) {
                    throw new IllegalArgumentException("The current XMage prompt does not accept a text choice.");
                }
                if (currentPromptMethod == ClientCallbackMethod.GAME_CHOOSE_CHOICE
                        && !currentAllowedStrings.contains(value)
                        && !(value == null && !Boolean.TRUE.equals(currentPrompt.get("required")))) {
                    throw new IllegalArgumentException("That choice is not available for the current prompt.");
                }
                if (currentPromptMethod == ClientCallbackMethod.GAME_GET_MULTI_AMOUNT) {
                    value = validateMultiAmount(value);
                }
                sent = session.sendPlayerString(currentGameId, value);
                break;
            default:
                throw new IllegalArgumentException("Unsupported game response action.");
        }
        if (!sent) {
            throw new IllegalStateException("XMage did not accept the game response.");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accepted", true);
        result.put("gameId", currentGameId.toString());
        result.put("messageId", messageId);
        result.put("action", action);
        // A fast server can publish the next prompt before the send call returns.
        // Only clear the prompt that this response actually answered.
        if (currentPromptMessageId == messageId) {
            clearPrompt();
            if (currentGame != null) {
                Map<String, Object> withoutPrompt = new LinkedHashMap<>(currentGame);
                withoutPrompt.put("prompt", null);
                currentGame = withoutPrompt;
            }
        }
        events.publish("game.response", result);
        return result;
    }

    synchronized Map<String, Object> performGameAction(String action) {
        requireConnected();
        if (currentGameId == null || currentGame == null || Boolean.FALSE.equals(currentGame.get("active"))) {
            throw new IllegalStateException("There is no active XMage game.");
        }
        if (!"concede".equals(action)) {
            throw new IllegalArgumentException("Unsupported game action.");
        }
        if (!session.sendPlayerAction(PlayerAction.CONCEDE, currentGameId, null)) {
            throw new IllegalStateException("XMage did not accept the concede action.");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accepted", true);
        result.put("gameId", currentGameId.toString());
        result.put("action", action);
        events.publish("game.action", result);
        return result;
    }

    private UUID parseResponseId(String value) {
        try {
            return UUID.fromString(value == null ? "" : value.trim());
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("A valid selectable object ID is required.");
        }
    }

    private int parseAmount(String value) {
        try {
            return Integer.parseInt(value == null ? "" : value.trim());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Enter a whole-number amount.");
        }
    }

    private String validateMultiAmount(String value) {
        Object rawItems = currentPrompt.get("amountItems");
        if (!(rawItems instanceof List)) {
            throw new IllegalStateException("XMage did not provide allocation choices.");
        }
        String[] parts = value == null || value.trim().isEmpty()
                ? new String[0] : value.trim().split("\\s+");
        List<?> items = (List<?>) rawItems;
        if (parts.length != items.size()) {
            throw new IllegalArgumentException("Enter one amount for every allocation row.");
        }
        int total = 0;
        List<String> normalized = new ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            int amount = parseAmount(parts[i]);
            Map<?, ?> item = (Map<?, ?>) items.get(i);
            int min = ((Number) item.get("min")).intValue();
            int max = ((Number) item.get("max")).intValue();
            if (amount < min || amount > max) {
                throw new IllegalArgumentException("Allocation " + (i + 1) + " must be from " + min + " to " + max + ".");
            }
            total += amount;
            normalized.add(Integer.toString(amount));
        }
        int minTotal = ((Number) currentPrompt.get("min")).intValue();
        int maxTotal = ((Number) currentPrompt.get("max")).intValue();
        if (total < minTotal || total > maxTotal) {
            throw new IllegalArgumentException("The allocation total must be from " + minTotal + " to " + maxTotal + ".");
        }
        return String.join(" ", normalized);
    }

    private boolean allowsBoolean(ClientCallbackMethod method) {
        switch (method) {
            case GAME_ASK:
            case GAME_SELECT:
            case GAME_CHOOSE_PILE:
            case GAME_PLAY_MANA:
            case GAME_PLAY_XMANA:
            case GAME_GET_AMOUNT:
                return true;
            case GAME_GET_MULTI_AMOUNT:
                Object choices = currentPrompt.get("choices");
                return choices instanceof Map && Boolean.TRUE.equals(((Map<?, ?>) choices).get("canCancel"));
            case GAME_CHOOSE_ABILITY:
                return true;
            case GAME_TARGET:
                return !Boolean.TRUE.equals(currentPrompt.get("required"));
            default:
                return false;
        }
    }

    private boolean allowsUuid(ClientCallbackMethod method) {
        return method == ClientCallbackMethod.GAME_TARGET
                || method == ClientCallbackMethod.GAME_SELECT
                || method == ClientCallbackMethod.GAME_PLAY_MANA
                || method == ClientCallbackMethod.GAME_CHOOSE_ABILITY;
    }

    Map<String, Object> validateDeck(String deckName, String deckText) {
        events.publish("deck.preparing", singletonMessage("Preparing the local XMage card database…"));
        DeckTextResolver.Result result = deckResolver.resolve(deckName, deckText);
        Map<String, Object> summary = result.summary();
        events.publish("deck.prepared", summary);
        return summary;
    }

    synchronized Map<String, Object> joinTable(String tableIdText, String deckName,
                                                String deckText, String password) {
        requireConnected();
        UUID roomId = session.getMainRoomId();
        UUID tableId = parseTableId(tableIdText);
        TableView table = findTable(roomId, tableId);
        if (table.getTableState() != TableState.WAITING) {
            throw new IllegalArgumentException("That table is no longer waiting for players. Refresh the lobby.");
        }

        events.publish("table.joining", tableEvent(tableId, table.getTableName()));
        boolean requiresDeck = !table.isTournament() || !table.isLimited();
        DeckTextResolver.Result resolved = null;
        DeckCardLists joinDeck = null;
        if (requiresDeck) {
            resolved = deckResolver.resolve(deckName, deckText);
            if (!resolved.canJoin()) {
                Map<String, Object> summary = resolved.summary();
                events.publish("deck.rejected", summary);
                if (!((Map<?, ?>) summary.get("unresolved")).isEmpty()) {
                    throw new IllegalArgumentException("Some card names could not be resolved. Review the deck validation details.");
                }
                throw new IllegalArgumentException("The deck contains no resolved cards.");
            }
            joinDeck = resolved.deck();
        }

        lastError = "";
        pendingJoinError = "";
        pendingJoinErrorSignal = new CountDownLatch(1);
        boolean joined = table.isTournament()
                ? session.joinTournamentTable(roomId, tableId, username, PlayerType.HUMAN, 1,
                        joinDeck, password == null ? "" : password)
                : session.joinTable(roomId, tableId, username, PlayerType.HUMAN, 1,
                        joinDeck, password == null ? "" : password);
        if (!joined) {
            String reason = waitForJoinError();
            throw new IllegalStateException(reason == null || reason.trim().isEmpty()
                    ? rejectionHint(table) : reason);
        }
        joinedTableId = tableId;
        Map<String, Object> result = tableEvent(tableId, table.getTableName());
        result.put("joined", true);
        result.put("deck", resolved == null ? null : resolved.summary());
        result.put("limited", table.isLimited());
        events.publish("table.joined", result);
        return result;
    }

    /** XMage delivers join explanations on its callback channel, independently of the false RPC result. */
    private String waitForJoinError() {
        String reason = firstNonBlank(pendingJoinError, lastError, session.getLastError());
        if (reason == null) {
            try {
                pendingJoinErrorSignal.await(12, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            reason = firstNonBlank(pendingJoinError, lastError, session.getLastError());
        }
        return reason;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return null;
    }

    private String rejectionHint(TableView table) {
        if (table.isPassworded()) {
            return "XMage rejected the join. This table is password protected; check the table password.";
        }
        if (positiveNumber(table.getMinimumRating())) {
            return "XMage rejected the join. This table requires a minimum rating of "
                    + table.getMinimumRating() + ".";
        }
        return "XMage rejected the join without an explanation. The seat may have filled or the table may have closed; refresh and try another open table.";
    }

    private boolean positiveNumber(String value) {
        try {
            return Integer.parseInt(value == null ? "0" : value) > 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    synchronized Map<String, Object> leaveTable(String tableIdText) {
        requireConnected();
        UUID roomId = session.getMainRoomId();
        UUID tableId = tableIdText == null || tableIdText.trim().isEmpty()
                ? joinedTableId : parseTableId(tableIdText);
        if (tableId == null) {
            throw new IllegalArgumentException("No joined table was found.");
        }
        if (!session.leaveTable(roomId, tableId)) {
            String reason = session.getLastError();
            throw new IllegalStateException(reason == null || reason.trim().isEmpty()
                    ? "XMage did not accept the leave request." : reason);
        }
        joinedTableId = null;
        Map<String, Object> result = tableEvent(tableId, null);
        result.put("left", true);
        events.publish("table.left", result);
        return result;
    }

    private void requireConnected() {
        if (!session.isConnected() || session.getMainRoomId() == null) {
            throw new IllegalStateException("Connect to an XMage server first.");
        }
    }

    private UUID parseTableId(String value) {
        try {
            return UUID.fromString(value == null ? "" : value.trim());
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("A valid table ID is required.");
        }
    }

    private TableView findTable(UUID roomId, UUID tableId) {
        try {
            for (TableView table : session.getTables(roomId)) {
                if (tableId.equals(table.getTableId())) {
                    return table;
                }
            }
        } catch (Exception error) {
            throw new IllegalStateException("Could not refresh the XMage lobby: " + error.getMessage());
        }
        throw new IllegalArgumentException("That table is no longer in the lobby. Refresh and try again.");
    }

    private Map<String, Object> tableEvent(UUID tableId, String tableName) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tableId", tableId.toString());
        result.put("tableName", tableName);
        return result;
    }

    private Map<String, Object> connectionDetails() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("state", state);
        details.put("host", host);
        details.put("port", port);
        details.put("username", username);
        return details;
    }

    private Map<String, Object> summarize(TableView table) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", table.getTableId().toString());
        item.put("name", table.getTableName());
        item.put("controller", table.getControllerName());
        item.put("gameType", table.getGameType());
        item.put("deckType", table.getDeckType());
        item.put("state", table.getTableState().name());
        item.put("stateText", table.getTableStateText());
        item.put("seats", table.getSeatsInfo());
        item.put("tournament", table.isTournament());
        item.put("limited", table.isLimited());
        item.put("requiresDeck", !table.isTournament() || !table.isLimited());
        item.put("passworded", table.isPassworded());
        item.put("minimumRating", table.getMinimumRating());
        item.put("maximumQuitRatio", table.getQuitRatio());
        item.put("spectatorsAllowed", table.getSpectatorsAllowed());
        item.put("details", table.getAdditionalInfoShort());
        item.put("joinable", table.getTableState() == TableState.WAITING);
        item.put("joined", table.getTableId().equals(joinedTableId));
        return item;
    }

    @Override
    public MageVersion getVersion() {
        return VERSION;
    }

    @Override
    public void connected(String message) {
        state = "connected";
        Map<String, Object> payload = connectionDetails();
        payload.put("message", message);
        events.publish("session.connected", payload);
    }

    @Override
    public void disconnected(boolean askToReconnect, boolean keepMySessionActive) {
        state = "disconnected";
        joinedTableId = null;
        clearGame();
        Map<String, Object> payload = connectionDetails();
        payload.put("reconnectSuggested", askToReconnect);
        payload.put("sessionRetained", keepMySessionActive);
        events.publish("session.disconnected", payload);
    }

    @Override
    public void showMessage(String message) {
        events.publish("xmage.message", singletonMessage(message));
    }

    @Override
    public void showError(String message) {
        lastError = message;
        events.publish("xmage.error", singletonMessage(message));
    }

    @Override
    public void onNewConnection() {
        events.publish("transport.ready", connectionDetails());
    }

    @Override
    public void onCallback(ClientCallback callback) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageId", callback.getMessageId());
        payload.put("method", callback.getMethod() == null ? null : callback.getMethod().name());
        payload.put("category", callback.getMethod() == null ? null : callback.getMethod().getType().name());
        payload.put("objectId", callback.getObjectId() == null ? null : callback.getObjectId().toString());
        try {
            callback.decompressData();
            Object data = callback.getData();
            payload.put("dataType", data == null ? null : data.getClass().getName());
            if (callback.getMethod() == ClientCallbackMethod.SHOW_USERMESSAGE
                    && data instanceof List) {
                List<?> messageData = (List<?>) data;
                String title = messageData.size() > 0 ? String.valueOf(messageData.get(0)) : "XMage message";
                String message = messageData.size() > 1 ? String.valueOf(messageData.get(1)) : title;
                lastError = message;
                if ("Join Table".equalsIgnoreCase(title)) {
                    pendingJoinError = message;
                    pendingJoinErrorSignal.countDown();
                }
                Map<String, Object> userMessage = singletonMessage(message);
                userMessage.put("title", title);
                events.publish("xmage.user-message", userMessage);
                return;
            }
            if (callback.getMethod() == ClientCallbackMethod.CHATMESSAGE
                    && data instanceof ChatMessage) {
                ChatMessage chat = (ChatMessage) data;
                Map<String, Object> chatMessage = singletonMessage(chat.getMessage());
                chatMessage.put("username", chat.getUsername());
                chatMessage.put("messageType", chat.getMessageType() == null ? null : chat.getMessageType().name());
                chatMessage.put("turnInfo", chat.getTurnInfo());
                events.publish("chat.message", chatMessage);
                return;
            }
            if (callback.getMethod() == ClientCallbackMethod.JOINED_TABLE
                    && data instanceof TableClientMessage) {
                TableClientMessage tableMessage = (TableClientMessage) data;
                joinedTableId = tableMessage.getCurrentTableId();
                payload.put("joinedTableId", joinedTableId == null ? null : joinedTableId.toString());
            }
            if (callback.getMethod() == ClientCallbackMethod.SIDEBOARD
                    && data instanceof TableClientMessage) {
                rememberSideboard((TableClientMessage) data);
            }
            updateGameState(callback.getMethod(), callback.getMessageId(), data, callback.getObjectId());
        } catch (RuntimeException error) {
            payload.put("decodeError", error.getMessage());
        }
        events.publish("xmage.callback", payload);
    }

    private void updateGameState(ClientCallbackMethod method, long messageId, Object data, UUID objectId) {
        if (method == null) {
            return;
        }
        if (method == ClientCallbackMethod.START_GAME && data instanceof TableClientMessage) {
            TableClientMessage message = (TableClientMessage) data;
            currentGameId = message.getGameId();
            clearSideboard();
            clearPrompt();
            Map<String, Object> started = new LinkedHashMap<>();
            started.put("active", true);
            started.put("gameId", currentGameId == null ? null : currentGameId.toString());
            started.put("status", "starting");
            currentGame = started;
            events.publish("game.started", started);
            if (currentGameId != null && !session.joinGame(currentGameId)) {
                lastError = session.getLastError();
                events.publish("game.error", singletonMessage(lastError == null || lastError.isEmpty()
                        ? "XMage did not accept the game-start acknowledgement." : lastError));
            }
            return;
        }

        GameView view = null;
        if ((method == ClientCallbackMethod.GAME_INIT || method == ClientCallbackMethod.GAME_UPDATE)
                && data instanceof GameView) {
            view = (GameView) data;
            clearPrompt();
        } else if (method == ClientCallbackMethod.GAME_CHOOSE_ABILITY
                && data instanceof AbilityPickerView) {
            AbilityPickerView picker = (AbilityPickerView) data;
            view = picker.getGameView();
            rememberAbilityPrompt(messageId, picker);
        } else if (data instanceof GameClientMessage) {
            GameClientMessage message = (GameClientMessage) data;
            view = message.getGameView();
            if (isPrompt(method)) {
                rememberPrompt(method, messageId, message, view);
            } else if (method == ClientCallbackMethod.GAME_UPDATE_AND_INFORM
                    || method == ClientCallbackMethod.GAME_OVER) {
                clearPrompt();
            }
        }

        if (view != null) {
            currentGameId = objectId == null ? currentGameId : objectId;
            Map<String, Object> reduced = gameReducer.reduce(currentGameId, messageId, view, currentPrompt);
            reduced.put("active", method != ClientCallbackMethod.GAME_OVER);
            if (data instanceof GameClientMessage) {
                String gameMessage = ((GameClientMessage) data).getMessage();
                if (gameMessage != null && !gameMessage.trim().isEmpty()) {
                    reduced.put("message", gameMessage);
                }
            }
            currentGame = reduced;
            events.publish(method == ClientCallbackMethod.GAME_OVER ? "game.over" : "game.state", reduced);
        }
    }

    private boolean isPrompt(ClientCallbackMethod method) {
        switch (method) {
            case GAME_ASK:
            case GAME_TARGET:
            case GAME_SELECT:
            case GAME_CHOOSE_PILE:
            case GAME_CHOOSE_CHOICE:
            case GAME_PLAY_MANA:
            case GAME_PLAY_XMANA:
            case GAME_GET_AMOUNT:
            case GAME_GET_MULTI_AMOUNT:
                return true;
            default:
                return false;
        }
    }

    private void rememberPrompt(ClientCallbackMethod method, long messageId,
                                GameClientMessage message, GameView view) {
        currentPrompt = gameReducer.prompt(method.name(), messageId, message);
        currentPromptMethod = method;
        currentPromptMessageId = messageId;

        Set<UUID> ids = new HashSet<>();
        if (message.getTargets() != null) {
            ids.addAll(message.getTargets());
        }
        if (message.getCardsView1() != null) {
            ids.addAll(message.getCardsView1().keySet());
        }
        if (message.getCardsView2() != null) {
            ids.addAll(message.getCardsView2().keySet());
        }
        if ((method == ClientCallbackMethod.GAME_SELECT || method == ClientCallbackMethod.GAME_PLAY_MANA)
                && view != null && view.getCanPlayObjects() != null) {
            ids.addAll(view.getCanPlayObjects().getObjects().keySet());
        }
        currentAllowedIds = Collections.unmodifiableSet(ids);

        Set<String> strings = new LinkedHashSet<>();
        if (message.getChoice() != null) {
            if (message.getChoice().isKeyChoice()) {
                strings.addAll(message.getChoice().getKeyChoices().keySet());
            } else {
                strings.addAll(message.getChoice().getChoices());
            }
            if (message.getChoice().isSpecialEnabled() && message.getChoice().isSpecialCanBeEmpty()) {
                strings.add("#");
            }
        }
        currentAllowedStrings = Collections.unmodifiableSet(strings);
    }

    private void rememberAbilityPrompt(long messageId, AbilityPickerView picker) {
        currentPrompt = gameReducer.abilityPrompt(messageId, picker);
        currentPromptMethod = ClientCallbackMethod.GAME_CHOOSE_ABILITY;
        currentPromptMessageId = messageId;
        currentAllowedIds = Collections.unmodifiableSet(new HashSet<>(picker.getChoices().keySet()));
        currentAllowedStrings = Collections.emptySet();
    }

    private void clearPrompt() {
        currentPrompt = null;
        currentPromptMethod = null;
        currentPromptMessageId = -1;
        currentAllowedIds = Collections.emptySet();
        currentAllowedStrings = Collections.emptySet();
    }

    private void rememberSideboard(TableClientMessage message) {
        DeckView deck = message.getDeck();
        if (deck == null || message.getCurrentTableId() == null) {
            return;
        }
        Map<UUID, DeckCardInfo> allowed = new LinkedHashMap<>();
        List<Map<String, Object>> main = sideboardCards(deck.getCards().values(), allowed);
        List<Map<String, Object>> sideboard = sideboardCards(deck.getSideboard().values(), allowed);
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("active", true);
        state.put("tableId", message.getCurrentTableId().toString());
        state.put("parentTableId", message.getParentTableId() == null ? null : message.getParentTableId().toString());
        state.put("deckName", deck.getName());
        state.put("limited", message.getFlag());
        state.put("deadlineEpochMs", System.currentTimeMillis() + Math.max(0, message.getTime()) * 1000L);
        state.put("main", main);
        state.put("sideboard", sideboard);
        currentSideboardTableId = message.getCurrentTableId();
        currentSideboardDeckName = deck.getName() == null ? "Web deck" : deck.getName();
        currentSideboardCards = Collections.unmodifiableMap(allowed);
        currentSideboard = state;
        events.publish("sideboard.started", state);
    }

    private List<Map<String, Object>> sideboardCards(Collection<SimpleCardView> cards,
                                                      Map<UUID, DeckCardInfo> allowed) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (SimpleCardView card : cards) {
            DeckCardInfo info = deckResolver.resolveCard(card);
            allowed.put(card.getId(), info);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", card.getId().toString());
            item.put("name", info.getCardName());
            item.put("setCode", info.getSetCode());
            item.put("cardNumber", info.getCardNumber());
            result.add(item);
        }
        return result;
    }

    private void clearSideboard() {
        currentSideboardTableId = null;
        currentSideboardDeckName = "Web deck";
        currentSideboardCards = Collections.emptyMap();
        currentSideboard = null;
    }

    private void clearGame() {
        currentGameId = null;
        currentGame = null;
        clearPrompt();
        clearSideboard();
    }

    private Map<String, Object> singletonMessage(String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", message);
        return payload;
    }
}
