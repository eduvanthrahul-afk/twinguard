"""Matches CARLA's braking to each road friction level, and writes calibration.json.

This measures the vehicle alone. It brakes at full force with no TwinGuard firmware involved, so
the calibration can't be tuned toward any test result. The target is the fast simulator's physics:
a friction-limited deceleration of friction * g.

Stop bridge.py first, then run: python calibrate.py [--map Town04_Opt]
"""

import argparse
import json
import sys

import bridge

FRICTIONS = [0.2, 0.4, 0.6, 0.8, 1.0]
TEST_SPEED_MS = 20.0
VALIDATION_SPEEDS_MS = [13.9, 27.8]
TORQUE_SCALES = [1.0, 2.0, 4.0, 8.0]
TIRE_LOW = 0.05
TIRE_HIGH = 8.0
BISECT_STEPS = 9
GRAVITY = 9.81
MAX_TICKS = 3000
ACCEPTABLE_ERROR = 0.10


def brake_test(world, friction_value, speed):
    """Full brake with no obstacle. Returns mean deceleration between 90% and 10% of the start speed."""
    world.reset("GHOST", speed, 150.0, friction_value)
    state = {"speed": speed}
    for _ in range(20):
        state = world.step(0.0)
    start = state["speed"]
    t_high = t_low = None
    distance = 0.0
    tick = 0
    while state["speed"] > 0 and tick < MAX_TICKS:
        state = world.step(1.0)
        distance += state["speed"] * bridge.DT
        tick += 1
        if t_high is None and state["speed"] <= 0.9 * start:
            t_high = tick
        if t_low is None and state["speed"] <= 0.1 * start:
            t_low = tick
    if t_high is None or t_low is None or t_low <= t_high:
        return 0.0, distance
    return 0.8 * start / ((t_low - t_high) * bridge.DT), distance


def with_setting(world, scale, tire):
    world.calibration = {"torque_scale": scale, "table": [{"mu": 0.0, "tire_friction": tire}]}


def strongest_braking(world, scale):
    with_setting(world, scale, TIRE_HIGH)
    return brake_test(world, 1.0, TEST_SPEED_MS)[0]


def tire_for(world, scale, target):
    low, high = TIRE_LOW, TIRE_HIGH
    best = (TIRE_HIGH, 0.0)
    for _ in range(BISECT_STEPS):
        tire = (low + high) / 2
        with_setting(world, scale, tire)
        decel = brake_test(world, 1.0, TEST_SPEED_MS)[0]
        if abs(decel - target) < abs(best[1] - target):
            best = (tire, decel)
        if decel < target:
            low = tire
        else:
            high = tire
    return best


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--carla-host", default="127.0.0.1")
    parser.add_argument("--carla-port", type=int, default=2000)
    parser.add_argument("--map", default="Town04_Opt")
    args = parser.parse_args()

    import carla

    client = carla.Client(args.carla_host, args.carla_port)
    client.set_timeout(60.0)
    world = bridge.World(carla, client, args.map)
    try:
        print("Torque search:", flush=True)
        scale = TORQUE_SCALES[-1]
        for candidate in TORQUE_SCALES:
            strongest = strongest_braking(world, candidate)
            print("  torque x%.0f: strongest braking %.2f m/s2" % (candidate, strongest), flush=True)
            if strongest >= max(FRICTIONS) * GRAVITY:
                scale = candidate
                break

        table = []
        for friction in FRICTIONS:
            target = friction * GRAVITY
            tire, achieved = tire_for(world, scale, target)
            table.append({"mu": friction, "tire_friction": round(tire, 4)})
            print("  friction %.1f: target %.2f, achieved %.2f m/s2, tyre setting %.3f" % (friction, target, achieved, tire), flush=True)

        calibration = {"torque_scale": scale, "vehicle": bridge.EGO_BLUEPRINT, "table": table}
        world.calibration = calibration

        print("Validation against stopping distance, using friction levels between the table points too:", flush=True)
        worst = 0.0
        for friction in [0.2, 0.3, 0.5, 0.7, 0.8, 0.9, 1.0]:
            for speed in VALIDATION_SPEEDS_MS:
                decel, distance = brake_test(world, friction, speed)
                theory = speed * speed / (2 * friction * GRAVITY)
                error = abs(distance - theory) / theory
                worst = max(worst, error)
                print("  friction %.1f at %3.0f km/h: stopped in %6.1f m, theory %6.1f m, error %3.0f%%, decel %.2f"
                      % (friction, speed * 3.6, distance, theory, error * 100, decel), flush=True)
        calibration["worst_stopping_error"] = round(worst, 3)

        with open(bridge.CALIBRATION_FILE, "w", encoding="utf-8") as file:
            json.dump(calibration, file, indent=2)
        print("Wrote %s. Worst stopping-distance error: %.0f%%" % (bridge.CALIBRATION_FILE, worst * 100), flush=True)
        if worst > ACCEPTABLE_ERROR:
            print("WARNING: above the %.0f%% target, so treat re-checks with caution." % (ACCEPTABLE_ERROR * 100), flush=True)
    finally:
        world.close()


if __name__ == "__main__":
    sys.exit(main())
