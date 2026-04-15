# PlaneFlightBattle Online Server Setup

This project now supports two multiplayer paths:

- **LAN mode** (existing):
  - `H` = host on local network
  - `J` = join on local network
- **Online relay mode** (new):
  - `O` = online host (Plane 1)
  - `P` = online join (Plane 2)

Online mode uses a small Python relay server so players do **not** need to be on the same Wi-Fi.

## 1) Run the relay server

From the repository root:

```powershell
python online_server/relay_server.py --host 0.0.0.0 --port 6000
```

If you deploy this on a cloud VM, open inbound TCP port `6000` in the VM firewall/security group.

## 2) Point game clients to the relay server

Each player should set these before launching the game:

```powershell
$env:PFB_SERVER_HOST = "YOUR_SERVER_PUBLIC_IP_OR_DNS"
$env:PFB_SERVER_PORT = "6000"
./gradlew run
```

Alternative JVM properties (same effect):

```powershell
./gradlew run --args="-Dpfb.server.host=YOUR_SERVER_PUBLIC_IP_OR_DNS -Dpfb.server.port=6000"
```

## 3) Start a match

- Player 1 presses `O` (online host)
- Player 2 presses `P` (online join)

The relay pairs the first waiting host and first waiting join client, then forwards gameplay messages.

## Notes

- The relay is intentionally minimal for consistency and school-network compatibility.
- It supports multiple matches by pairing clients in order.
- If either player disconnects, the other side gets a connection-lost event.
