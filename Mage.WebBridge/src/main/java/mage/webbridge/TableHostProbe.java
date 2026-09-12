package mage.webbridge;

import mage.constants.SkillLevel;
import mage.constants.TableState;
import mage.constants.PlayerAction;
import mage.cards.decks.DeckCardLists;
import mage.cards.decks.DeckCardInfo;
import mage.game.match.MatchOptions;
import mage.interfaces.MageClient;
import mage.interfaces.callback.ClientCallback;
import mage.players.PlayerType;
import mage.players.net.UserData;
import mage.players.net.UserGroup;
import mage.remote.Connection;
import mage.remote.Session;
import mage.remote.SessionImpl;
import mage.utils.MageVersion;
import mage.view.TableView;
import mage.view.GameClientMessage;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Disposable ordinary table host used by the bridge compatibility smoke test. */
public final class TableHostProbe implements MageClient {

    private static final MageVersion VERSION = new MageVersion(TableHostProbe.class);
    private final Session session = new SessionImpl(this);
    private final boolean sideboardMode;
    private DeckCardLists hostDeck;
    private boolean concededFirstGame;

    private TableHostProbe(boolean sideboardMode) {
        this.sideboardMode = sideboardMode;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 4) {
            System.err.println("Usage: TableHostProbe <host> <username> [port] [seconds]");
            System.exit(2);
        }
        String host = args[0];
        String username = args[1];
        int port = args.length >= 3 ? Integer.parseInt(args[2]) : 17171;
        int seconds = args.length == 4 ? Integer.parseInt(args[3]) : 90;

        boolean sideboardMode = "true".equalsIgnoreCase(System.getenv("XMAGE_PROBE_SIDEBOARD"));
        TableHostProbe probe = new TableHostProbe(sideboardMode);
        if (!probe.connect(host, port, username)) {
            throw new IllegalStateException(probe.session.getLastError());
        }
        UUID roomId = probe.session.getMainRoomId();
        MatchOptions options = new MatchOptions("Web bridge join test", "Two Player Duel", false);
        options.getPlayerTypes().add(PlayerType.HUMAN);
        options.getPlayerTypes().add(PlayerType.HUMAN);
        options.setDeckType("Constructed - Freeform");
        options.setWinsNeeded(sideboardMode ? 2 : 1);
        options.setSkillLevel(SkillLevel.CASUAL);
        options.setSpectatorsAllowed(true);
        options.setQuitRatio(100);

        TableView table = probe.session.createTable(roomId, options);
        if (table == null) {
            throw new IllegalStateException("Could not create test table: " + probe.session.getLastError());
        }
        DeckCardLists deck = new DeckCardLists();
        deck.setName("Host Mountains");
        for (int i = 0; i < 60; i++) {
            deck.getCards().add(new DeckCardInfo("Mountain", "269", "M21"));
        }
        probe.hostDeck = deck;
        if (!probe.session.joinTable(roomId, table.getTableId(), username,
                PlayerType.HUMAN, 1, deck, "")) {
            throw new IllegalStateException("Host could not join test table: " + probe.session.getLastError());
        }
        System.out.println("HOST_TABLE_READY id=" + table.getTableId());
        try {
            long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(seconds);
            while (System.currentTimeMillis() < deadline) {
                TableView current = probe.session.getTable(roomId, table.getTableId()).orElse(null);
                if (current != null && current.getTableState() == TableState.READY_TO_START) {
                    if (!probe.session.startMatch(roomId, table.getTableId())) {
                        throw new IllegalStateException("Could not start test match: " + probe.session.getLastError());
                    }
                    System.out.println("HOST_GAME_STARTED table=" + table.getTableId());
                    break;
                }
                TimeUnit.MILLISECONDS.sleep(250);
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining > 0) {
                TimeUnit.MILLISECONDS.sleep(remaining);
            }
        } finally {
            probe.session.removeTable(roomId, table.getTableId());
            probe.session.connectStop(false, false);
        }
    }

    private boolean connect(String host, int port, String username) {
        Connection connection = new Connection();
        connection.setHost(host);
        connection.setPort(port);
        connection.setUsername(username);
        connection.setPassword("");
        connection.setUserIdStr("xmage-web-bridge-table-host");
        connection.setProxyType(Connection.ProxyType.NONE);
        UserData userData = UserData.getDefaultUserDataView();
        userData.setGroupId(UserGroup.PLAYER.getGroupId());
        connection.setUserData(userData);
        return session.connectStart(connection);
    }

    @Override
    public MageVersion getVersion() {
        return VERSION;
    }

    @Override
    public void connected(String message) {
    }

    @Override
    public void disconnected(boolean askToReconnect, boolean keepMySessionActive) {
    }

    @Override
    public void showMessage(String message) {
        System.out.println("HOST_MESSAGE " + message);
    }

    @Override
    public void showError(String message) {
        System.err.println("HOST_ERROR " + message);
    }

    @Override
    public void onNewConnection() {
    }

    @Override
    public void onCallback(ClientCallback callback) {
        callback.decompressData();
        switch (callback.getMethod()) {
            case START_GAME:
                mage.view.TableClientMessage start = (mage.view.TableClientMessage) callback.getData();
                session.joinGame(start.getGameId());
                break;
            case GAME_ASK:
                GameClientMessage question = (GameClientMessage) callback.getData();
                boolean answer = question.getMessage() == null
                        || !question.getMessage().toLowerCase(java.util.Locale.ENGLISH).contains("mulligan");
                session.sendPlayerBoolean(callback.getObjectId(), answer);
                break;
            case GAME_TARGET:
                GameClientMessage target = (GameClientMessage) callback.getData();
                if (target.getTargets() != null && !target.getTargets().isEmpty()) {
                    session.sendPlayerUUID(callback.getObjectId(), target.getTargets().iterator().next());
                } else if (!target.isFlag()) {
                    session.sendPlayerBoolean(callback.getObjectId(), false);
                }
                break;
            case GAME_INIT:
                if (sideboardMode && !concededFirstGame) {
                    concededFirstGame = true;
                    session.sendPlayerAction(PlayerAction.CONCEDE, callback.getObjectId(), null);
                }
                break;
            case SIDEBOARD:
                if (sideboardMode) {
                    mage.view.TableClientMessage sideboard = (mage.view.TableClientMessage) callback.getData();
                    session.submitDeck(sideboard.getCurrentTableId(), hostDeck.copy());
                }
                break;
            default:
        }
    }
}
