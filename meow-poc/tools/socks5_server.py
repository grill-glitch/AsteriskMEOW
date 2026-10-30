#!/usr/bin/env python3
"""Minimal SOCKS5 CONNECT server for AsteriskMEOW PoC tests.

Not a general-purpose proxy: no auth, no UDP ASSOCIATE, CONNECT only.
Logs each target so the PoC can prove traffic takes the proxy path.
"""
import argparse
import select
import socket
import struct
import sys
import threading


def recv_exact(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("eof")
        buf += chunk
    return buf


def read_addr(sock, atyp):
    if atyp == 1:
        return socket.inet_ntoa(recv_exact(sock, 4))
    if atyp == 3:
        return recv_exact(sock, recv_exact(sock, 1)[0]).decode()
    if atyp == 4:
        return socket.inet_ntop(socket.AF_INET6, recv_exact(sock, 16))
    raise ValueError(f"atyp {atyp}")


def reply(sock, code):
    sock.sendall(bytes([5, code, 0, 1, 0, 0, 0, 0, 0, 0]))


def relay(a, b):
    socks = [a, b]
    try:
        while True:
            readable, _, _ = select.select(socks, [], [], 60)
            if not readable:
                break
            for s in readable:
                data = s.recv(65536)
                if not data:
                    return
                (b if s is a else a).sendall(data)
    finally:
        a.close()
        b.close()


def handle(client, peer):
    try:
        ver, n = recv_exact(client, 2)
        methods = recv_exact(client, n)
        if ver != 5 or 0 not in methods:
            return
        client.sendall(b"\x05\x00")
        ver, cmd, _, atyp = recv_exact(client, 4)
        host = read_addr(client, atyp)
        port = struct.unpack("!H", recv_exact(client, 2))[0]
        if cmd != 1:
            reply(client, 7)
            return
        target = socket.create_connection((host, port), timeout=10)
        print(f"[socks] {peer[0]} -> {host}:{port}", flush=True)
        reply(client, 0)
        relay(client, target)
    except Exception as exc:  # noqa: BLE001 - test server
        print(f"[socks] {peer[0]} error: {exc}", flush=True)
    finally:
        try:
            client.close()
        except OSError:
            pass


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--listen", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=1080)
    args = ap.parse_args()
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((args.listen, args.port))
    srv.listen(64)
    print(f"[socks] listening on {args.listen}:{args.port}", flush=True)
    while True:
        client, peer = srv.accept()
        threading.Thread(target=handle, args=(client, peer), daemon=True).start()


if __name__ == "__main__":
    sys.exit(main())
