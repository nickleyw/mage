# Deploy the XMage iPad bridge

The browser interface and the XMage protocol bridge run together in one Java
container. The service needs an HTTPS web address and permission to make an
outbound TCP connection to the XMage server (normally port `17171`).

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
token. Deck validation uses the compact, version-matched card index bundled in
the bridge.

## Give it an HTTPS URL

The included `render.yaml` describes an experimental free-tier Docker service,
generated access token, health check, and reduced-memory JVM settings. After
this branch is available in a Git repository:

1. Create a new Render Blueprint from that repository.
2. Confirm the `xmage-ipad-bridge` web service shows a $0 monthly estimate.
3. Deploy and copy the generated `XMAGE_BRIDGE_TOKEN` from the service settings.
4. Open the assigned `https://…onrender.com` address on the iPad.
5. Enter the access token under **Bridge access**, then connect to the XMage
   server. The app can remember the bridge token on that device.
6. In Safari, use **Share → Add to Home Screen**.

The free configuration is intended for initial testing. A sleeping or
restarting instance disconnects the current XMage session. The reduced 384 MB
Java heap passed bridge startup and deck-validation tests, but a complete match
remains the practical memory test.
Upgrade to an always-on instance if free-tier sleeping or memory limits disrupt
games.

Rebuild the container whenever the target XMage server changes versions; the
bridge must match the server just as the desktop client does.

Do not expose port `8080` directly to the public internet without HTTPS. The
hosting platform should terminate HTTPS and forward requests to the container.
