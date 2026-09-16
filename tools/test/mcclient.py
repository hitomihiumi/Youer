#!/usr/bin/env python3
"""
A minimal Minecraft protocol client, just enough to prove a real connection:
a server list ping, then an offline-mode login carried through the
configuration phase into play.

Only the packets the server insists on are implemented; everything else the
server sends is read and discarded. Compression must be off on the server
(network-compression-threshold=-1), which keeps the framing to varint length
plus payload.
"""
import socket, struct, sys, json, time, uuid

PROTOCOL = 772  # 1.21.8


def varint(value):
    out = b""
    while True:
        b = value & 0x7F
        value >>= 7
        if value:
            out += bytes([b | 0x80])
        else:
            out += bytes([b])
            return out


def string(value):
    data = value.encode("utf-8")
    return varint(len(data)) + data


class Conn:
    def __init__(self, host, port, timeout=20):
        self.sock = socket.create_connection((host, port), timeout=timeout)
        self.buf = b""

    def send(self, packet_id, payload=b""):
        body = varint(packet_id) + payload
        self.sock.sendall(varint(len(body)) + body)

    def _recv(self, n):
        while len(self.buf) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise EOFError("server closed the connection")
            self.buf += chunk
        data, self.buf = self.buf[:n], self.buf[n:]
        return data

    def read_varint(self):
        value = 0
        for i in range(5):
            b = self._recv(1)[0]
            value |= (b & 0x7F) << (7 * i)
            if not b & 0x80:
                return value
        raise ValueError("varint too long")

    def read_packet(self):
        length = self.read_varint()
        body = self._recv(length)
        pid, off = 0, 0
        for i in range(5):
            b = body[off]
            off += 1
            pid |= (b & 0x7F) << (7 * i)
            if not b & 0x80:
                break
        return pid, body[off:]

    def close(self):
        self.sock.close()


def read_varint_from(data, off):
    value, shift = 0, 0
    while True:
        b = data[off]
        off += 1
        value |= (b & 0x7F) << shift
        shift += 7
        if not b & 0x80:
            return value, off


def read_string(data, off):
    length, shift = 0, 0
    while True:
        b = data[off]
        off += 1
        length |= (b & 0x7F) << shift
        shift += 7
        if not b & 0x80:
            break
    return data[off:off + length].decode("utf-8"), off + length


def handshake(conn, host, port, next_state):
    conn.send(0x00, varint(PROTOCOL) + string(host) + struct.pack(">H", port) + varint(next_state))


def status(host, port):
    conn = Conn(host, port)
    handshake(conn, host, port, 1)
    conn.send(0x00)
    pid, data = conn.read_packet()
    assert pid == 0x00, f"expected status response, got {pid:#x}"
    payload, _ = read_string(data, 0)
    conn.close()
    return json.loads(payload)


def login(host, port, username, verbose=False, stay_seconds=0, command=None):
    """
    Packet ids are the registration order in LoginProtocols, ConfigurationProtocols
    and GameProtocols, so they are read off those files rather than guessed.
    """
    LOGIN_CB_DISCONNECT, LOGIN_CB_FINISHED, LOGIN_CB_COMPRESSION = 0x00, 0x02, 0x03
    LOGIN_SB_HELLO, LOGIN_SB_ACKNOWLEDGED = 0x00, 0x03
    CFG_CB_DISCONNECT, CFG_CB_FINISH, CFG_CB_KEEP_ALIVE = 0x02, 0x03, 0x04
    CFG_CB_PING, CFG_CB_KNOWN_PACKS = 0x05, 0x0E
    CFG_SB_CLIENT_INFORMATION, CFG_SB_FINISH = 0x00, 0x03
    CFG_SB_KEEP_ALIVE, CFG_SB_PONG, CFG_SB_KNOWN_PACKS = 0x04, 0x05, 0x07
    PLAY_CB_LOGIN = 0x2A

    conn = Conn(host, port)
    handshake(conn, host, port, 2)
    conn.send(LOGIN_SB_HELLO, string(username) + uuid.UUID(bytes=b"\x00" * 16).bytes)

    seen = {"login_finished": False, "configuring": False, "play_login": False,
            "keep_alive": False, "teleported": False, "command_sent": False}
    stay_until = 0
    state = "login"
    deadline = time.time() + 60 + stay_seconds
    while time.time() < deadline:
        pid, data = conn.read_packet()
        if verbose:
            print(f"  <- {state} {pid:#04x} ({len(data)} bytes)")
        if state == "login":
            if pid == LOGIN_CB_DISCONNECT:
                raise RuntimeError("disconnected during login: " + read_string(data, 0)[0])
            if pid == LOGIN_CB_COMPRESSION:
                raise RuntimeError("server enabled compression; set network-compression-threshold=-1")
            if pid == LOGIN_CB_FINISHED:
                seen["login_finished"] = True
                conn.send(LOGIN_SB_ACKNOWLEDGED)
                state = "config"
                conn.send(
                    CFG_SB_CLIENT_INFORMATION,
                    string("en_us") + bytes([8]) + varint(0) + bytes([1]) + bytes([0x7F])
                    + varint(1) + bytes([0]) + bytes([1]) + varint(0),
                )
        elif state == "config":
            seen["configuring"] = True
            if pid == CFG_CB_DISCONNECT:
                raise RuntimeError("disconnected during configuration: " + repr(data[:200]))
            if pid == CFG_CB_PING:
                conn.send(CFG_SB_PONG, data)
            elif pid == CFG_CB_KEEP_ALIVE:
                conn.send(CFG_SB_KEEP_ALIVE, data)
            elif pid == CFG_CB_KNOWN_PACKS:
                conn.send(CFG_SB_KNOWN_PACKS, varint(0))
            elif pid == CFG_CB_FINISH:
                conn.send(CFG_SB_FINISH)
                state = "play"
        elif state == "play":
            # play ids, again straight from GameProtocols
            PLAY_CB_KEEP_ALIVE, PLAY_CB_PLAYER_POSITION = 0x28, 0x43
            PLAY_SB_ACCEPT_TELEPORTATION, PLAY_SB_CHAT_COMMAND = 0x00, 0x06
            PLAY_SB_KEEP_ALIVE, PLAY_SB_PLAYER_LOADED = 0x1B, 0x2B
            if pid == PLAY_CB_LOGIN and not seen["play_login"]:
                seen["play_login"] = True
                conn.send(PLAY_SB_PLAYER_LOADED)
                if not stay_seconds:
                    break
                stay_until = time.time() + stay_seconds
            elif pid == PLAY_CB_KEEP_ALIVE:
                seen["keep_alive"] = True
                conn.send(PLAY_SB_KEEP_ALIVE, data)
            elif pid == PLAY_CB_PLAYER_POSITION:
                teleport_id, _ = read_varint_from(data, 0)
                seen["teleported"] = True
                conn.send(PLAY_SB_ACCEPT_TELEPORTATION, varint(teleport_id))
            if seen["play_login"] and command and not seen["command_sent"]:
                conn.send(PLAY_SB_CHAT_COMMAND, string(command))
                seen["command_sent"] = True
            if seen["play_login"] and stay_seconds and time.time() > stay_until:
                break
    conn.close()
    return seen


if __name__ == "__main__":
    host, port = "127.0.0.1", 25565
    what = sys.argv[1] if len(sys.argv) > 1 else "status"
    if what == "status":
        print(json.dumps(status(host, port), indent=2)[:2000])
    else:
        args = [a for a in sys.argv[2:] if not a.startswith("-")]
        print(login(host, port, args[0] if args else "YouerTester", verbose="-v" in sys.argv,
                    stay_seconds=15, command="youer version"))
