# 4-minute demo script

Before you present, start the app and open **Confidence** once to confirm the campaign has finished. Read the numbers from the screen, because they are measured live.

If anyone asks, describe the results as **real computed results from a simulated car**, not real vehicle data.

## 1. The problem (30 seconds)

> A bug in an OTA update reaches thousands of cars at once. Passing tests does not tell you whether those tests could have caught the bug. So we built a gate that tests the update, and also measures how good its own testing is.

## 2. The catch (75 seconds)

On **Releases**, find **v2.2 Media Player Update** and click **Run gate**.

> This update adds lossless audio. No braking code changed.

As the run progresses:

> We run the old and new software side by side, with identical road conditions and identical sensor noise, so any difference comes from the update.

When the **Blocked** status appears, point at the two check rows, then let the replay play.

> Every conventional test passed, and TwinGuard still blocked it. The approved car stops. The updated car hits the obstacle. A media update delayed or even prevented braking, because it gave audio traffic the same gateway priority as radar and brake messages. Under heavy media load, radar data arrives too late. These two charts show the cause.

## 3. It isn't blocking everything (20 seconds)

Go back to **Collision braking**. On **v2.1 Smoother Pre-Braking**, click **Run gate**.

> This update also changes braking, but on purpose, and it declares the change in a manifest. It gets approved. A gate that blocks everything is useless.

## Optional: It isn't only braking, or only our code (30 seconds)

Open **Battery management**, and run **BMS 1.3 Winter Charging**. It passes every conventional test, then gets blocked for fast charging a frozen battery.

> Same gate, different feature. Adding a feature means plugging in its scenarios, physics, and safety rules.

Open **Your software**. Choose `brake_v1_supplier.fmu` as approved and `brake_v2_radar_bug.fmu` as the candidate, then click **Run gate**.

> This controller is written in C and packaged as an FMU, the standard format suppliers use. TwinGuard has no idea what's inside it, and it still finds the bug.

Test this on your presenting laptop first, because Windows can block unsigned FMUs.

## 4. Let the judge choose (45 seconds)

Open **Braking Fault Lab**. Ask a judge to pick a setting and a value, point at the diff against v1, then click **Run gate**.

> Nothing here is scripted. You choose the bug.

If the bug survives, stay calm and use it. Small changes can stay inside the tolerances, and that is a stated limitation.

## 5. The proof (40 seconds)

Open **Confidence**.

> We injected 72 faults that we designed. Here is the share we caught, with a confidence range, and false alarms on known-safe releases. But we chose those faults, so we also let a tool rewrite the compiled braking code into about 100 real bugs. We never tuned against them, so that score is lower and more honest. Here is everything we missed, and why.

Point at the chart, and read its heading and summary from the screen. They are generated from the measured results.

> Change-aware testing finds faults sooner than random testing, but not more of them. That matters when each scenario is expensive, like on a hardware rig.

## 6. Limits and the pipeline (20 seconds)

Open **Pipeline**.

> This is a fast filter before hardware-in-the-loop testing, not a replacement. It doesn't cover perception or real ECU timing. And an approval is evidence, not proof of safety.

## Likely questions

**Is this real data?**
The results are computed live by the simulator. The road scenarios and sensor data are synthetic, and the firmware settings are design values, not taken from a real car. **Raw report** on any run shows the full output.

**Real CAN gives brake messages priority. How could this happen?**
Our bug is a wrong priority configuration in the update. That's exactly the mistake the gate exists to catch. In the model, the gateway serves messages in priority order.

**Your simulator is simplified. Why trust it?**
It is a filter, not the final word. Unit tests check its stopping distance against physics, and any failure can be re-run on a higher-fidelity rig. The simulator hasn't been calibrated against vehicle data, which is on our next-steps list.

**Why not run everything on CARLA?**
Speed and repeatability. We run hundreds of thousands of simulations in seconds, and each failure reproduces exactly. CARLA sits behind the same interface, and replays only the failures. We calibrated its braking to our model, then replayed 12 failures from three buggy releases: 9 reproduced, 1 was inconclusive, and 2 showed a different finding. Each scenario also ran v1 against v1 as a control.

**So CARLA proves your results?**
No. It's one run of each release, using CARLA's vehicle physics with our simulated radar, and tuned to match our model. It shows the failures aren't just an artifact of our fast physics. It doesn't test perception or real hardware.

**Why is your code mutant score so much lower than your fault score?**
Because we didn't choose those bugs. Many survivors are changes like < to <=, or code that only matters under combined faults. Some are real blind spots, and they're listed on the page.

**Why do you trust your score?**
Only within limits. It applies to this fault set and this simulator. We show ranges, a held-out set, and the misses. We also revised the generator and checks while the held-out results were visible, so it is not a formally blind test.

**Did the safe release ever get flagged?**
Yes, during development. It was flagged on 13 of 40 random seeds. Two causes were false alarms in our checks, which we fixed. The third was real: the release shrank the stopping gap more than its manifest declared, so we corrected the manifest. It then passed on 100 fresh seeds.

**How do you know the FMU support really works?**
The C version of the approved braking software behaved the same as the Java version across 3,000 scenarios, with zero difference in brake timing and stopping gap.

**Is the pedestrian feature real perception?**
No. Detection is simulated from range, noise, and dropouts. It tests the decision and braking logic, not whether a camera would see a person.

**Are the battery, cruise, lane, and pedestrian models realistic?**
They're simplified and aren't calibrated against real data, so they test software logic against stated rules. They also haven't been run on CARLA. Only collision braking has.

**What about ISO 26262 or UN R156?**
This could support that evidence, but we don't claim compliance or certification.
