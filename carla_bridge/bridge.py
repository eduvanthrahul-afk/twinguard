"""Connects TwinGuard to a running CARLA server.

TwinGuard keeps running the sensors, gateway, and braking software in Java. This bridge only
simulates the vehicle and road in CARLA, one 10 ms tick at a time.

Each request and reply is one line of JSON:
  {"cmd": "ping"}
  {"cmd": "reset", "type": "OBSTACLE", "speed": 27.7, "distance": 120, "friction": 0.8}
  {"cmd": "step", "brake": 0.4}

Usage: python bridge.py [--map Town04_Opt]
"""

import argparse
import json
import math
import os
import socketserver
import sys

DT = 0.01
LISTEN_HOST = "127.0.0.1"
STRAIGHT_ROAD_M = 240.0
MAX_HEADING_CHANGE_DEG = 2.0
WAYPOINT_SPACING_M = 10.0
SETTLE_TICKS = 30
STOPPED_MS = 0.02
EGO_BLUEPRINT = "vehicle.tesla.model3"
OBSTACLE_BLUEPRINT = "vehicle.carlamotors.carlacola"
CALIBRATION_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "calibration.json")


def heading_difference(a, b):
    return abs((a - b + 180.0) % 360.0 - 180.0)


def find_straight_road(world_map, length):
    """Finds a waypoint followed by a long, straight stretch of road."""
    for waypoint in world_map.generate_waypoints(WAYPOINT_SPACING_M):
        current = waypoint
        travelled = 0.0
        while travelled < length:
            following = current.next(WAYPOINT_SPACING_M)
            if not following:
                break
            current = following[0]
            travelled += WAYPOINT_SPACING_M
            change = heading_difference(waypoint.transform.rotation.yaw, current.transform.rotation.yaw)
            if change > MAX_HEADING_CHANGE_DEG or current.is_junction:
                break
        else:
            return waypoint
    raise RuntimeError("This map has no straight road of %d m. Try --map Town04_Opt." % length)


def load_calibration():
    """Reads the table written by calibrate.py. Without it, braking is not matched to the fast simulator."""
    if not os.path.exists(CALIBRATION_FILE):
        return None
    with open(CALIBRATION_FILE, encoding="utf-8") as file:
        return json.load(file)


def calibrated_tire_friction(calibration, friction):
    """Interpolates the tyre setting that makes the car brake at about friction * g."""
    table = calibration["table"]
    if friction <= table[0]["mu"]:
        return table[0]["tire_friction"]
    for low, high in zip(table, table[1:]):
        if friction <= high["mu"]:
            share = (friction - low["mu"]) / (high["mu"] - low["mu"])
            return low["tire_friction"] + share * (high["tire_friction"] - low["tire_friction"])
    return table[-1]["tire_friction"]


class World:
    """Owns the CARLA world and the vehicles for the current scenario."""

    backend = "carla"

    def __init__(self, carla, client, map_name):
        self.carla = carla
        self.client = client
        current = client.get_world().get_map().name.split("/")[-1]
        self.world = client.get_world() if current == map_name else client.load_world(map_name)
        self.map_name = map_name
        self.calibration = load_calibration()
        self.original_settings = self.world.get_settings()
        settings = self.world.get_settings()
        settings.synchronous_mode = True
        settings.fixed_delta_seconds = DT
        self.world.apply_settings(settings)
        self.remove_leftovers()
        self.road = find_straight_road(self.world.get_map(), STRAIGHT_ROAD_M)
        self.actors = []
        self.ego = None
        self.obstacle = None
        self.ghost_gap = 0.0
        self.collided = False
        self.cruising = False
        self.last_speed = 0.0
        self.distance = 0.0

    def version(self):
        return self.client.get_server_version()

    def remove_leftovers(self):
        """A bridge that was force-stopped leaves its vehicles behind, and new runs would drive into them."""
        actors = self.world.get_actors()
        leftovers = list(actors.filter("vehicle.*")) + list(actors.filter("sensor.other.collision"))
        for actor in leftovers:
            actor.destroy()
        self.world.tick()
        remaining = len(self.world.get_actors().filter("vehicle.*"))
        print("Removed %d leftover actors. Vehicles remaining: %d" % (len(leftovers), remaining), flush=True)
        if remaining:
            raise RuntimeError("Could not clear %d vehicles from the world" % remaining)

    def clear(self):
        for actor in self.actors:
            if self.carla.Sensor is not None and isinstance(actor, self.carla.Sensor):
                actor.stop()
            actor.destroy()
        self.actors = []
        self.ego = None
        self.obstacle = None

    def spawn(self, blueprint_name, transform):
        blueprint = self.world.get_blueprint_library().find(blueprint_name)
        transform.location.z += 0.3
        actor = self.world.spawn_actor(blueprint, transform)
        self.actors.append(actor)
        return actor

    def half_length(self, actor):
        return actor.bounding_box.extent.x

    def forward_speed(self):
        velocity = self.ego.get_velocity()
        forward = self.ego.get_transform().get_forward_vector()
        return max(0.0, velocity.x * forward.x + velocity.y * forward.y + velocity.z * forward.z)

    def gap(self):
        if self.obstacle is None:
            return self.ghost_gap
        ego = self.ego.get_location()
        obstacle = self.obstacle.get_location()
        forward = self.ego.get_transform().get_forward_vector()
        centre = (obstacle.x - ego.x) * forward.x + (obstacle.y - ego.y) * forward.y
        return centre - self.half_length(self.ego) - self.half_length(self.obstacle)

    def reset(self, kind, speed, distance, friction):
        self.clear()
        # In synchronous mode, destroyed actors stay in the world until the next tick.
        self.world.tick()
        self.collided = False
        self.distance = distance
        self.ghost_gap = distance
        self.ego = self.spawn(EGO_BLUEPRINT, self.road.transform)

        if kind == "OBSTACLE":
            # Placed roughly first, then corrected once both vehicle lengths are known.
            ahead = self.road.next(distance + 7.0)[0]
            self.obstacle = self.spawn(OBSTACLE_BLUEPRINT, ahead.transform)
            self.obstacle.set_simulate_physics(False)
            sensor_blueprint = self.world.get_blueprint_library().find("sensor.other.collision")
            sensor = self.world.spawn_actor(sensor_blueprint, self.carla.Transform(), attach_to=self.ego)
            sensor.listen(self.on_collision)
            self.actors.append(sensor)

        physics = self.ego.get_physics_control()
        wheels = physics.wheels
        for wheel in wheels:
            if self.calibration is None:
                wheel.tire_friction = wheel.tire_friction * friction
            else:
                wheel.tire_friction = calibrated_tire_friction(self.calibration, friction)
                wheel.max_brake_torque = wheel.max_brake_torque * self.calibration["torque_scale"]
        physics.wheels = wheels
        self.ego.apply_physics_control(physics)

        for _ in range(SETTLE_TICKS):
            self.world.tick()

        if self.obstacle is not None:
            exact = self.road.next(distance + self.half_length(self.ego) + self.half_length(self.obstacle))[0]
            location = exact.transform.location
            location.z = self.obstacle.get_location().z
            self.obstacle.set_location(location)

        self.ego.enable_constant_velocity(self.carla.Vector3D(speed, 0.0, 0.0))
        self.cruising = True
        self.world.tick()
        self.collided = False
        self.last_speed = self.forward_speed()
        return {"gap": self.gap(), "speed": self.last_speed}

    def on_collision(self, _event):
        self.collided = True

    def step(self, brake):
        if self.ego is None:
            raise RuntimeError("Send reset before step")
        if self.cruising and brake > 0:
            self.ego.disable_constant_velocity()
            self.cruising = False
        self.ego.apply_control(self.carla.VehicleControl(throttle=0.0, steer=0.0, brake=float(brake)))
        self.world.tick()
        speed = self.forward_speed()
        if speed < STOPPED_MS:
            speed = 0.0
        decel = max(0.0, (self.last_speed - speed) / DT)
        self.last_speed = speed
        return {"gap": self.gap(), "speed": speed, "decel": decel, "collided": self.collided}

    def close(self):
        self.clear()
        self.world.apply_settings(self.original_settings)


class Handler(socketserver.StreamRequestHandler):
    def handle(self):
        try:
            for raw in self.rfile:
                reply = self.server.respond(raw)
                self.wfile.write((json.dumps(reply) + "\n").encode("utf-8"))
                self.wfile.flush()
        except ConnectionError:
            pass  # TwinGuard disconnected, which is normal between runs.

    def finish(self):
        try:
            super().finish()
        except ConnectionError:
            pass


class BridgeServer(socketserver.TCPServer):
    allow_reuse_address = True

    def __init__(self, address, world):
        super().__init__(address, Handler)
        self.world = world

    def respond(self, raw):
        try:
            request = json.loads(raw)
            command = request.get("cmd")
            if command == "ping":
                return {"ok": True, "backend": self.world.backend, "map": self.world.map_name, "version": self.world.version(),
                        "calibrated": getattr(self.world, "calibration", None) is not None}
            if command == "reset":
                state = self.world.reset(request["type"], float(request["speed"]), float(request["distance"]), float(request["friction"]))
                return dict(state, ok=True)
            if command == "step":
                state = self.world.step(float(request["brake"]))
                if math.isnan(state["gap"]) or math.isnan(state["speed"]):
                    raise RuntimeError("CARLA returned an invalid vehicle state")
                return dict(state, ok=True)
            return {"ok": False, "error": "Unknown command: %s" % command}
        except Exception as error:  # A bad request or a CARLA failure must reach TwinGuard as a clear message.
            return {"ok": False, "error": "%s: %s" % (type(error).__name__, error)}


def main():
    parser = argparse.ArgumentParser(description="Connects TwinGuard to a running CARLA server.")
    parser.add_argument("--carla-host", default="127.0.0.1")
    parser.add_argument("--carla-port", type=int, default=2000)
    parser.add_argument("--listen-port", type=int, default=9797)
    parser.add_argument("--map", default="Town04_Opt")
    args = parser.parse_args()

    try:
        import carla
    except ImportError:
        sys.exit("The CARLA Python client isn't installed. See carla_bridge/README.md.")

    client = carla.Client(args.carla_host, args.carla_port)
    client.set_timeout(60.0)
    world = World(carla, client, args.map)
    print("Connected to CARLA %s on %s. Listening on %s:%d" % (world.version(), args.map, LISTEN_HOST, args.listen_port), flush=True)
    server = BridgeServer((LISTEN_HOST, args.listen_port), world)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        world.close()


if __name__ == "__main__":
    main()
