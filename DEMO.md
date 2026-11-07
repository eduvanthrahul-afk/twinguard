# 4-minute demo script

The app opens on a guided check with five steps: **Choose update, Review change, Shadow test, Verdict, Trust & ship**. Each step unlocks only when the one before it is done, and the button in the bottom right is always the next thing to press. You never need the sidebar during the demo.

Before you present, start the app, open **Open full console → Confidence** once to confirm the campaign has finished, then click **Guided check** to return. Read the numbers from the screen, because they are measured live.

If anyone asks, describe the results as **real computed results from a simulated car**, not real vehicle data.

## 1. The problem (30 seconds)

> A bug in an OTA update reaches thousands of cars at once. Passing tests does not tell you whether those tests could have caught the bug. So we built a gate that tests the update, and also measures how good its own testing is.

## 2. The catch (90 seconds)

**Step 1, Choose update.** Leave **Collision braking** selected and pick **v2.2 Media Player Update**.

> This update adds lossless audio. No braking code changed.

**Step 2, Review change.** Point at the single changed setting and the empty manifest.

> The update declares no change to braking, so the car should behave exactly like v1.

Click **Start shadow test**. **Step 3** fills in live.

> We run the old and new software side by side, with identical road conditions and identical sensor noise, so any difference comes from the update. Every red cell is a confirmed failure.

When it finishes, click **See the verdict**. **Step 4** shows the Blocked banner, then let the replay play.

> All 48 conventional tests passed, and TwinGuard still blocked it. The approved car stops. The updated car hits the obstacle. The update gave audio traffic the same gateway priority as radar and brake messages, so under heavy media load radar data arrives too late. These two charts show the cause.

## 3. Can you trust it? (40 seconds)

Click **Can we trust it?** **Step 5** shows the three scores.

> We let a tool rewrite the compiled braking code into about 100 real bugs, which we never tuned against. Here is the share we catch, with a confidence range. We also designed faults that look like real OTA mistakes. And on known-safe software, zero false alarms.

> The evidence report goes back to the team, with the exact failing scenario.

## 4. It isn't blocking everything (20 seconds)

Click **Test another update**, pick **v2.1 Smoother Pre-Braking**, and run it through.

> This update also changes braking, but on purpose, and it declares the change in its manifest. It gets approved. A gate that blocks everything is useless.

## Optional: other features, or someone else's code (30 seconds)

Click **Test another update**, choose **Battery management**, then **BMS 1.3 Winter Charging**. It passes every conventional test, then gets blocked for fast charging a frozen battery. The failures sit in the sub-zero rows of the map.

> Same five steps, different feature. Adding a feature means plugging in its scenarios, physics, and safety rules.

For third-party software, use the **Upload an FMU** link on step 1. Choose `brake_v1_supplier.fmu` as approved and `brake_v2_radar_bug.fmu` as the candidate. Test this on your presenting laptop first, because Windows can block unsigned FMUs.

## 5. Let the judge choose (45 seconds)

Use the **open the Fault Lab** link on step 1. Ask a judge to pick a setting and a value, point at the diff against v1, then click **Run gate**.

> Nothing here is scripted. You choose the bug.

If the bug survives, stay calm and use it. Small changes can stay inside the tolerances, and that is a stated limitation.

## 6. Limits and the pipeline (20 seconds)

From step 5, click **See it in CI**.

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
