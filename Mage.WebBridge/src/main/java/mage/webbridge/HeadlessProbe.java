package mage.webbridge;

import mage.interfaces.MageClient;
import mage.interfaces.callback.ClientCallback;
import mage.players.net.UserData;
import mage.players.net.UserGroup;
import mage.remote.Connection;
import mage.remote.Session;
import mage.remote.SessionImpl;
import mage.utils.MageVersion;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * First feasibility probe for a browser-to-XMage bridge.
 *
 * This class deliberately contains no Swing UI. It reuses XMage's existing
 * network session, receives typed server callbacks, and proves that an
 * unmodified XMage server can see the bridge as a normal client.
 */
public final class HeadlessProbe implements MageClient {

    private static final MageVersion VERSION = new MageVersion(HeadlessProbe.class);
    private static final int DEFAULT_PORT = 17171;
    private static final int CALLBACK_WINDOW_SECONDS = 10;

    private final CountDownLatch disconnected = new CountDownLatch(1);
    private final Session session = new SessionImpl(this);

    private HeadlessProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 4) {
            printUsage();
            System.exit(2);
        }

        String host = args[0];
        String username = args[1];
        int port = args.length >= 3 ? Integer.parseInt(args[2]) : DEFAULT_PORT;
        String password = args.length == 4 ? args[3] : "";

        HeadlessProbe probe = new HeadlessProbe();
        boolean connected = probe.connect(host, port, username, password);
        if (!connected) {
            System.err.println("PROBE_FAIL connection: " + probe.session.getLastError());
            System.exit(1);
        }

        try {
            System.out.println("PROBE_OK session=" + probe.session.getSessionId()
                    + " room=" + probe.session.getMainRoomId()
                    + " tables=" + probe.session.getTables(probe.session.getMainRoomId()).size());
            probe.disconnected.await(CALLBACK_WINDOW_SECONDS, TimeUnit.SECONDS);
        } finally {
            probe.session.connectStop(false, false);
        }
    }

    private boolean connect(String host, int port, String username, String password) {
        Connection connection = new Connection();
        connection.setHost(host);
        connection.setPort(port);
        connection.setUsername(username);
        connection.setPassword(password);
        connection.setUserIdStr("xmage-web-bridge-spike");
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
        System.out.println("CONNECTED " + message);
    }

    @Override
    public void disconnected(boolean askToReconnect, boolean keepMySessionActive) {
        System.out.println("DISCONNECTED reconnect=" + askToReconnect
                + " keepSession=" + keepMySessionActive);
        disconnected.countDown();
    }

    @Override
    public void showMessage(String message) {
        System.out.println("MESSAGE " + message);
    }

    @Override
    public void showError(String message) {
        System.err.println("ERROR " + message);
    }

    @Override
    public void onNewConnection() {
        System.out.println("TRANSPORT_READY");
    }

    @Override
    public void onCallback(ClientCallback callback) {
        callback.decompressData();
        Object data = callback.getData();
        String dataType = data == null ? "null" : data.getClass().getName();
        System.out.println("CALLBACK id=" + callback.getMessageId()
                + " method=" + callback.getMethod()
                + " object=" + callback.getObjectId()
                + " dataType=" + dataType);
    }

    private static void printUsage() {
        System.err.println("Usage: HeadlessProbe <host> <username> [port] [password]");
    }
}
