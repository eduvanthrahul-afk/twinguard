import { Check, CircleDashed, LoaderCircle, TriangleAlert, X } from "lucide-react";
import type { ButtonHTMLAttributes, ReactNode } from "react";
import type { Verdict } from "../api";

export function Button({ variant = "secondary", className = "", ...props }: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: "primary" | "secondary" | "ghost" }) {
  const styles = {
    primary: "bg-fg text-canvas hover:opacity-85",
    secondary: "border border-line-strong bg-canvas text-fg hover:bg-muted",
    ghost: "text-fg-2 hover:bg-muted hover:text-fg",
  }[variant];
  return (
    <button
      type="button"
      {...props}
      className={`inline-flex h-8 items-center justify-center gap-1.5 whitespace-nowrap rounded-md px-3 text-sm font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-40 ${styles} ${className}`}
    />
  );
}

export type Status = Verdict | "RUNNING" | "IDLE" | "FAILED";

const STATUS: Record<Status, { label: string; className: string; icon: ReactNode }> = {
  BLOCK: { label: "Blocked", className: "bg-danger-bg text-danger", icon: <X size={12} strokeWidth={2.5} /> },
  REVIEW: { label: "Needs review", className: "bg-warn-bg text-warn", icon: <TriangleAlert size={12} strokeWidth={2.2} /> },
  APPROVE: { label: "Approved", className: "bg-ok-bg text-ok", icon: <Check size={12} strokeWidth={2.5} /> },
  RUNNING: { label: "Running", className: "bg-muted text-fg", icon: <LoaderCircle size={12} className="spin" strokeWidth={2.2} /> },
  IDLE: { label: "Not run", className: "text-fg-3", icon: <CircleDashed size={12} strokeWidth={2} /> },
  FAILED: { label: "Run failed", className: "bg-danger-bg text-danger", icon: <TriangleAlert size={12} strokeWidth={2.2} /> },
};

export function StatusPill({ status }: { status: Status }) {
  const s = STATUS[status];
  return (
    <span className={`inline-flex h-[22px] items-center gap-1 whitespace-nowrap rounded-full px-2 text-xs font-medium ${s.className}`}>
      {s.icon}
      {s.label}
    </span>
  );
}

export function PageHeader({ title, description, actions, breadcrumb }: { title: ReactNode; description?: ReactNode; actions?: ReactNode; breadcrumb?: ReactNode }) {
  return (
    <header className="border-b border-line px-5 pb-5 pt-5 sm:px-8 sm:pt-7">
      {breadcrumb && <div className="mb-3 text-sm text-fg-3">{breadcrumb}</div>}
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <h1 className="flex flex-wrap items-center gap-2.5 text-[22px] font-semibold leading-7 tracking-[-0.02em]">{title}</h1>
          {description && <p className="mt-1.5 max-w-[68ch] text-base text-fg-2">{description}</p>}
        </div>
        {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
      </div>
    </header>
  );
}

export function Section({ title, description, actions, children, flush }: { title: ReactNode; description?: ReactNode; actions?: ReactNode; children: ReactNode; flush?: boolean }) {
  return (
    <section className="border-b border-line px-5 py-6 sm:px-8">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <h2 className="text-base font-semibold tracking-[-0.01em]">{title}</h2>
          {description && <p className="mt-1 max-w-[76ch] text-sm text-fg-2">{description}</p>}
        </div>
        {actions}
      </div>
      <div className={flush ? "mt-3" : "mt-5"}>{children}</div>
    </section>
  );
}

export function Metric({ label, value, hint }: { label: string; value: ReactNode; hint?: ReactNode }) {
  return (
    <div className="min-w-0">
      <div className="text-sm text-fg-3">{label}</div>
      <div className="mt-1 text-[20px] font-medium leading-7 tracking-[-0.02em] tabular-nums">{value}</div>
      {hint && <div className="mt-0.5 text-xs text-fg-3">{hint}</div>}
    </div>
  );
}

export function Segmented<T extends string | number>({ value, options, onChange, label }: { value: T; options: { value: T; label: string }[]; onChange: (v: T) => void; label: string }) {
  return (
    <div role="group" aria-label={label} className="inline-flex h-8 rounded-md bg-muted p-0.5">
      {options.map((o) => (
        <button
          key={String(o.value)}
          type="button"
          aria-pressed={o.value === value}
          onClick={() => onChange(o.value)}
          className={`whitespace-nowrap rounded-[5px] px-2.5 text-xs font-medium transition-colors ${o.value === value ? "bg-canvas text-fg shadow-[0_0_0_1px_var(--border)]" : "text-fg-2 hover:text-fg"}`}
        >
          {o.label}
        </button>
      ))}
    </div>
  );
}

export const tooltipStyle = {
  contentStyle: { background: "var(--bg)", border: "1px solid var(--border-strong)", borderRadius: 6, fontSize: 12, boxShadow: "0 4px 12px rgba(0,0,0,0.08)" },
  labelStyle: { color: "var(--fg-2)", marginBottom: 2 },
  itemStyle: { color: "var(--fg)", padding: 0 },
};
