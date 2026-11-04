/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        sb: "var(--sb-bg)",
        "sb-hover": "var(--sb-hover)",
        "sb-active": "var(--sb-active)",
        "sb-fg": "var(--sb-fg)",
        "sb-strong": "var(--sb-strong)",
        "sb-label": "var(--sb-label)",
        canvas: "var(--bg)",
        subtle: "var(--bg-subtle)",
        muted: "var(--bg-muted)",
        line: "var(--border)",
        "line-strong": "var(--border-strong)",
        fg: "var(--fg)",
        "fg-2": "var(--fg-2)",
        "fg-3": "var(--fg-3)",
        danger: "var(--danger)",
        "danger-bg": "var(--danger-bg)",
        ok: "var(--ok)",
        "ok-bg": "var(--ok-bg)",
        warn: "var(--warn)",
        "warn-bg": "var(--warn-bg)",
      },
      fontFamily: {
        sans: ['"Geist Variable"', "system-ui", '"Segoe UI"', "sans-serif"],
        mono: ['"Geist Mono Variable"', "ui-monospace", "Consolas", "monospace"],
      },
      fontSize: {
        xs: ["12px", "16px"],
        sm: ["13px", "20px"],
        base: ["14px", "22px"],
      },
    },
  },
  plugins: [],
};
