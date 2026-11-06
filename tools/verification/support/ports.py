"""Fixed application port offsets per verification tool (decision 105).

Each tool owns a 1000-wide slot so tools never collide with each other, and every
application port stays below the Linux ephemeral range so outgoing connections
cannot already hold it. Infrastructure ports are still taken from the OS.
"""
import os
import socket

EPHEMERAL_START = 32768

OFFSETS = {
    "verify-jib": 11000,
    "verify-catalog": 12000,
    "verify-download": 13000,
    "verify-g4-operations": 14000,
    "verify-gateway-ownership": 15000,
    "verify-license": 16000,
    "verify-order": 17000,
    "verify-payment": 18000,
    "verify-review": 19000,
    "verify-settlement": 20000,
    "verify-store": 21000,
    "verify-studio": 22000,
}


def application_offset(tool, env_name, ports):
    """Return the tool's offset after checking every application port is usable.

    ``env_name`` may override the default, but the override passes the same checks.
    """
    offset = int(os.environ.get(env_name, OFFSETS[tool]))
    used = sorted(offset + port for port in ports)
    if used[-1] >= EPHEMERAL_START:
        raise RuntimeError(tool + ": application port " + str(used[-1]) + " is inside the ephemeral range (>= "
                           + str(EPHEMERAL_START) + "); lower " + env_name)
    busy = [port for port in used if not _free(port)]
    if busy:
        raise RuntimeError(tool + ": application ports in use: " + ", ".join(str(port) for port in busy)
                           + "; stop the other process or set " + env_name)
    return offset


def _free(port):
    # The application server listens with SO_REUSEADDR, so a port a previous run left in
    # TIME_WAIT is usable; only a live listener or bound socket counts as in use.
    with socket.socket() as probe:
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            probe.bind(("127.0.0.1", port))
            return True
        except OSError:
            return False
