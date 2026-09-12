# Deploy the XMage iPad bridge

The browser interface and the XMage protocol bridge run together in one Java
container. The service needs at least 1.5 GB of memory, persistent storage at
`/data`, an HTTPS web address, and permission to make an outbound TCP connection
to the XMage server (normally port `17171`).

Your friend's setup does not change. In the web app, enter the same public XMage
server address, port, username, and optional server password that a desktop
player would use.

## Test it with Docker

From this repository:

```sh
export XMAGE_BRIDGE_TOKEN="replace-this-with-a-long-random-secret"
docker compose -f Mage.WebBridge/compose.yaml up --build
```

Open `http://localhost:8080`, expand **Bridge access**, and enter the same access
token. The first deck validation builds XMage's local card database and can take
a minute or two. Later launches reuse the `xmage-card-data` volume.

## Give it an HTTPS URL

The included `render.yaml` describes the required Docker service, generated
access token, health check, memory setting, and persistent disk. After this
branch is available in a Git repository:

1. Create a new Render Blueprint from that repository.
2. Confirm the `xmage-ipad-bridge` web service and persistent disk.
3. Deploy and copy the generated `XMAGE_BRIDGE_TOKEN` from the service settings.
4. Open the assigned `https://…onrender.com` address on the iPad.
5. Enter the access token under **Bridge access**, then connect to the XMage
   server. The app can remember the bridge token on that device.
6. In Safari, use **Share → Add to Home Screen**.

Use a paid/always-on instance for actual games. A provider that sleeps or
restarts the container will disconnect the current XMage session. Rebuild the
container whenever the target XMage server changes versions; the bridge must
match the server just as the desktop client does.

Do not expose port `8080` directly to the public internet without HTTPS. The
hosting platform should terminate HTTPS and forward requests to the container.
