"""Runs the bridge's network code with a stand-in world instead of CARLA.

This is a TEST DOUBLE. It reports itself as "test-double", and TwinGuard labels its results as not
real CARLA. It uses simple physics, so a pass here proves the connection works, and says nothing
about how a failure behaves in real CARLA.

Usage: python fake_carla_server.py [--listen-port 9797]
"""

import argparse

from bridge import DT, LISTEN_HOST, BridgeServer

GRAVITY = 9.81
MAX_DECEL = 11.0


class FakeWorld:
    backend = "test-double"
    map_name = "FakeTown"

    def __init__(self):
        self.gap_m = 0.0
        self.speed = None
        self.friction = 1.0
        self.ghost = False

    def version(self):
        return "none"

    def reset(self, kind, speed, distance, friction):
        self.gap_m = distance
        self.speed = speed
        self.friction = friction
        self.ghost = kind == "GHOST"
        return {"gap": self.gap_m, "speed": self.speed}

    def step(self, brake):
        if self.speed is None:
            raise RuntimeError("Send reset before step")
        decel = min(brake * MAX_DECEL, self.friction * GRAVITY)
        self.speed = max(0.0, self.speed - decel * DT)
        if not self.ghost:
            self.gap_m -= self.speed * DT
        return {"gap": self.gap_m, "speed": self.speed, "decel": decel, "collided": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--listen-port", type=int, default=9797)
    args = parser.parse_args()
    server = BridgeServer((LISTEN_HOST, args.listen_port), FakeWorld())
    print("TEST DOUBLE, not CARLA. Listening on %s:%d" % (LISTEN_HOST, args.listen_port), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
