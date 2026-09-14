# Deploy XMage for iPad

The PWA and Java protocol bridge run in one container. The service needs an HTTPS URL and outbound TCP access to the XMage server, normally on port `17171`. Desktop players and the XMage server do not need any changes.

## Local Docker test

From the repository root:

```sh
export XMAGE_BRIDGE_TOKEN="replace-this-with-a-long-random-secret"
docker compose -f Mage.WebBridge/compose.yaml up --build
```

Open [http://localhost:8080](http://localhost:8080), expand **Bridge access**, and enter the same token.

## Render Blueprint

The repository-root [`render.yaml`](../render.yaml) defines the free Docker service, generated bridge token, health check, and reduced-memory JVM options.

1. In Render, create a Blueprint from this repository and choose the `web-bridge-deploy` branch.
2. Leave **Blueprint Path** empty so Render uses `render.yaml` at the repository root.
3. Confirm the service is `xmage-ipad-bridge` and the estimated price is free.
4. Deploy. The first build compiles a large portion of XMage and can take several minutes.
5. Open the service's **Environment** page and reveal or copy `XMAGE_BRIDGE_TOKEN`.
6. Open the assigned HTTPS URL. Enter the token under **Bridge access**.
7. Connect to `beta.xmage.today` on `17171`, or another version-compatible XMage server.
8. On iPad Safari, choose **Share → Add to Home Screen**.

The current example deployment is [xmage-ipad-bridge.onrender.com](https://xmage-ipad-bridge.onrender.com).

## Operational expectations

- A free Render instance sleeps after inactivity. Cold starts can take roughly a minute and active XMage sessions are lost on sleep, restart, or deploy.
- The 384 MB heap is tuned for an experimental bridge. Live matches, Solo Table's second session, and large card indexes are the practical memory tests.
- Rebuild after the target XMage server changes versions.
- Health checks use `GET /api/health`.
- Render terminates HTTPS and forwards to the container's `PORT`; do not expose raw HTTP publicly.
- Card previews are fetched by users' browsers from Scryfall and are not stored in the container.

## Security and sharing

`XMAGE_BRIDGE_TOKEN` is a bearer secret. Anyone who has it can control the bridge's active XMage session. Do not put it in Git, screenshots, client source, or public documentation.

This build is single-tenant. One instance supports one normal player session or one two-seat Solo Table, not multiple isolated users. Friends can open the project and deploy separate copies; safely hosting many independent users requires a multi-tenant session/authentication layer that is not implemented yet.

## Deployment troubleshooting

- **Maven compile failure:** use the first actual `[ERROR]` line above the reactor summary; the final Docker exit code is only a wrapper.
- **Very long compile at Mage.Sets:** normal—XMage compiles tens of thousands of card classes.
- **Connection rejected:** confirm the host, port, username, password, server reachability, and exact XMage version.
- **Cold or blank first load:** wait for the Render instance to wake, then reload once.
- **Old UI after deploy:** close the Home Screen app/browser tab and reopen it so the service worker can activate the new shell.
- **Game disappeared after deploy/restart:** sessions are in memory and cannot survive process replacement.
