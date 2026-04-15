#!/usr/bin/env python3
import argparse
import socket
import threading
from collections import deque
from dataclasses import dataclass


@dataclass
class Endpoint:
    sock: socket.socket
    addr: tuple[str, int]
    mode: str

    def __post_init__(self) -> None:
        self.reader = self.sock.makefile("r", encoding="utf-8", newline="\n")
        self.writer = self.sock.makefile("w", encoding="utf-8", newline="\n")
        self.send_lock = threading.Lock()
        self.closed = False

    def send(self, line: str) -> bool:
        if self.closed:
            return False
        try:
            with self.send_lock:
                self.writer.write(line + "\n")
                self.writer.flush()
            return True
        except OSError:
            self.close()
            return False

    def close(self) -> None:
        if self.closed:
            return
        self.closed = True
        try:
            self.reader.close()
        except OSError:
            pass
        try:
            self.writer.close()
        except OSError:
            pass
        try:
            self.sock.close()
        except OSError:
            pass


class Matchmaker:
    def __init__(self) -> None:
        self.waiting_hosts: deque[Endpoint] = deque()
        self.waiting_joins: deque[Endpoint] = deque()
        self.lock = threading.Lock()

    def add(self, endpoint: Endpoint) -> None:
        endpoint.send("SYS|READY|WAITING")

        with self.lock:
            if endpoint.mode == "HOST":
                peer = self._pop_alive(self.waiting_joins)
                if peer is None:
                    self.waiting_hosts.append(endpoint)
                    return
                host, join = endpoint, peer
            else:
                peer = self._pop_alive(self.waiting_hosts)
                if peer is None:
                    self.waiting_joins.append(endpoint)
                    return
                host, join = peer, endpoint

        threading.Thread(target=self._run_match, args=(host, join), daemon=True).start()

    def _pop_alive(self, queue: deque[Endpoint]) -> Endpoint | None:
        while queue:
            candidate = queue.popleft()
            if not candidate.closed:
                return candidate
        return None

    def _run_match(self, host: Endpoint, join: Endpoint) -> None:
        print(f"[match] host={host.addr} join={join.addr}")

        if not host.send("SYS|PEER_CONNECTED|PILOT"):
            join.send("SYS|PEER_DISCONNECTED")
            join.close()
            return
        if not join.send("SYS|PEER_CONNECTED|TURRET"):
            host.send("SYS|PEER_DISCONNECTED")
            host.close()
            return

        stop_event = threading.Event()

        def relay(src: Endpoint, dst: Endpoint) -> None:
            try:
                for raw in src.reader:
                    line = raw.strip()
                    if not line:
                        continue
                    if not line.startswith("MSG|"):
                        if not src.send("SYS|ERR|unsupported_message"):
                            break
                        continue
                    if not dst.send(line):
                        break
            except OSError:
                pass
            finally:
                if not stop_event.is_set():
                    stop_event.set()
                    dst.send("SYS|PEER_DISCONNECTED")
                    src.close()
                    dst.close()

        t1 = threading.Thread(target=relay, args=(host, join), daemon=True)
        t2 = threading.Thread(target=relay, args=(join, host), daemon=True)
        t1.start()
        t2.start()
        t1.join()
        t2.join()
        print(f"[match-end] host={host.addr} join={join.addr}")


def read_hello(sock: socket.socket) -> tuple[str, str] | None:
    data = bytearray()
    try:
        while len(data) < 1024:
            chunk = sock.recv(1)
            if not chunk:
                break
            if chunk == b"\n":
                break
            data.extend(chunk)
    except OSError:
        return None

    line = data.decode("utf-8", errors="replace").strip()

    parts = line.split("|")
    if len(parts) != 3:
        return None
    if parts[0] != "HELLO" or parts[1] != "PFB1":
        return None

    mode = parts[2].strip().upper()
    if mode not in {"HOST", "JOIN"}:
        return None

    return (line, mode)


def handle_connection(conn: socket.socket, addr: tuple[str, int], matchmaker: Matchmaker) -> None:
    conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)

    hello = read_hello(conn)
    if hello is None:
        try:
            conn.sendall(b"SYS|ERR|invalid_hello\\n")
        except OSError:
            pass
        conn.close()
        return

    _, mode = hello
    endpoint = Endpoint(conn, addr, mode)
    print(f"[connect] {addr} mode={mode}")
    matchmaker.add(endpoint)


def serve(host: str, port: int) -> None:
    matchmaker = Matchmaker()

    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind((host, port))
        server.listen(128)

        print(f"PlaneFlightBattle relay listening on {host}:{port}")
        print("Protocol: HELLO|PFB1|HOST or HELLO|PFB1|JOIN, then MSG|...")

        while True:
            conn, addr = server.accept()
            threading.Thread(target=handle_connection, args=(conn, addr, matchmaker), daemon=True).start()


def main() -> None:
    parser = argparse.ArgumentParser(description="PlaneFlightBattle relay server")
    parser.add_argument("--host", default="0.0.0.0", help="Bind host (default: 0.0.0.0)")
    parser.add_argument("--port", type=int, default=6000, help="Bind port (default: 6000)")
    args = parser.parse_args()

    serve(args.host, args.port)


if __name__ == "__main__":
    main()
