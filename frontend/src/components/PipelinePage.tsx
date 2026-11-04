import { Check, Copy } from "lucide-react";
import { useState } from "react";
import { Button, PageHeader, Section } from "./ui";

const STAGES = [
  { title: "Commit", body: "Change proposed, with a manifest of intended behaviour changes." },
  { title: "Build and unit tests", body: "Normal checks run first and fail fast." },
  { title: "TwinGuard gate", body: "Tests v2 against v1. Fails the build on a block, and stores the evidence.", active: true },
  { title: "Hardware-in-the-loop", body: "Slower checks on real ECUs, starting with TwinGuard's failing scenarios." },
  { title: "Staged rollout", body: "A small share of the fleet first, with rollback at every step." },
  { title: "Fleet monitoring", body: "Field events become new scenarios and fault types." },
];

const CATCHES = [
  "Braking: late, weak, or missed braking, and phantom braking from a false radar return",
  "Infotainment and gateway traffic faults that delay brake messages",
  "Battery: overvoltage, overheating, overcharging, and fast charging while frozen",
  "Adaptive cruise: collisions, following too closely, and exceeding the set speed",
  "Lane keeping: leaving the lane, extra drift, and unwanted steering",
  "Pedestrians: avoidable hits, late braking, and false stops, using simulated detection",
  "Real code bugs and software crashes, including in third-party FMUs",
];

const MISSES = [
  "Camera and radar perception. Sensors are simulated as values plus noise, including pedestrian detection",
  "Real ECU timing, CAN bus electrical faults, and hardware failures",
  "Moving-obstacle braking, cut-ins, and real battery chemistry",
  "Combined faults. Each is injected alone, which can mask some of them",
  "Changes inside the tolerances, such as a 5% threshold shift",
  "Many small code changes, such as < versus <=, and code that only matters under combined faults",
  "Features other than the five on this dashboard",
];

const NEXT = [
  "Use CARLA for the other features, and for camera perception",
  "Add vendor simulators behind the same interface",
  "Mutate the gateway and sensor code too, not only the control logic",
  "Inject fault pairs to expose masked faults",
  "Calibrate every model and tolerance against recorded vehicle data",
];

const COMMANDS = `java -jar twinguard.jar campaign --report=campaign.json --min-score=70
java -jar twinguard.jar mutation --report=mutation.json
java -jar twinguard.jar gate --release=v2.2-media --report=gate-report.json --html=gate-report.html
java -jar twinguard.jar feature --id=battery --release=bms-1.3-winter --report=battery.json
java -jar twinguard.jar gate --fmu-baseline=approved.fmu --fmu-candidate=candidate.fmu --report=fmu.json`;

function Rows({ items }: { items: string[] }) {
  return (
    <ul>
      {items.map((item) => (
        <li key={item} className="border-t border-line py-2.5 text-sm text-fg-2 first:border-t-0 first:pt-0">{item}</li>
      ))}
    </ul>
  );
}

export default function PipelinePage() {
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(COMMANDS);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      setCopied(false);
    }
  };

  return (
    <>
      <PageHeader title="Pipeline" description="TwinGuard is a fast filter before expensive testing. It doesn't replace hardware-in-the-loop or road testing." />
      <Section title="Where it fits in an OTA release">
        <ol className="grid gap-px overflow-hidden rounded-lg border border-line bg-line sm:grid-cols-2 xl:grid-cols-6">
          {STAGES.map((stage, i) => (
            <li key={stage.title} className={`p-4 ${stage.active ? "bg-fg text-canvas" : "bg-canvas"}`}>
              <div className={`num text-xs ${stage.active ? "opacity-60" : "text-fg-3"}`}>{String(i + 1).padStart(2, "0")}</div>
              <div className="mt-2 text-sm font-medium">{stage.title}</div>
              <div className={`mt-1 text-xs leading-[18px] ${stage.active ? "opacity-75" : "text-fg-2"}`}>{stage.body}</div>
            </li>
          ))}
        </ol>
      </Section>

      <div className="grid border-b border-line lg:grid-cols-3 lg:divide-x lg:divide-line">
        <div className="px-5 py-6 sm:px-8">
          <h2 className="text-base font-semibold tracking-[-0.01em]">What it catches</h2>
          <div className="mt-4"><Rows items={CATCHES} /></div>
        </div>
        <div className="border-t border-line px-5 py-6 sm:px-8 lg:border-t-0">
          <h2 className="text-base font-semibold tracking-[-0.01em]">What it doesn't catch</h2>
          <div className="mt-4"><Rows items={MISSES} /></div>
        </div>
        <div className="border-t border-line px-5 py-6 sm:px-8 lg:border-t-0">
          <h2 className="text-base font-semibold tracking-[-0.01em]">What we'd build next</h2>
          <div className="mt-4"><Rows items={NEXT} /></div>
        </div>
      </div>

      <Section
        title="Run the same gate in CI"
        description="The exit code stops the pipeline: 0 approve, 1 block, 2 needs review."
        actions={
          <Button onClick={copy}>
            {copied ? <Check size={14} /> : <Copy size={14} />}
            {copied ? "Copied" : "Copy"}
          </Button>
        }
      >
        <pre className="num overflow-x-auto rounded-lg border border-line bg-subtle p-4 text-xs leading-6 text-fg-2"><code>{COMMANDS}</code></pre>
      </Section>
      <p className="px-5 py-4 text-xs text-fg-3 sm:px-8">An approval means no confirmed regression in the scenarios tested. It's evidence, not proof of safety, and not a certification against any standard.</p>
    </>
  );
}
