// Three themes, cycled from one control: light → dark → high contrast.
// High contrast is a light variant with stronger text and borders, for projectors and
// accessibility reviews.
(function () {
  const THEMES = ["light", "dark", "contrast"];

  function apply(theme) {
    const root = document.documentElement;
    root.classList.toggle("dark", theme === "dark");
    root.classList.toggle("light", theme !== "dark");
    root.classList.toggle("high-contrast", theme === "contrast");
    root.dataset.theme = theme;
    const label = document.querySelector("[data-theme-label]");
    if (label) {
      label.textContent = theme === "contrast" ? "High contrast" : theme === "dark" ? "Dark" : "Light";
    }
  }

  window.cycleTheme = function () {
    const current = document.documentElement.dataset.theme || "light";
    const next = THEMES[(THEMES.indexOf(current) + 1) % THEMES.length];
    localStorage.setItem("mend-theme", next);
    apply(next);
  };

  const stored = localStorage.getItem("mend-theme");
  const preferred = window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
  apply(THEMES.includes(stored) ? stored : preferred);
  document.addEventListener("DOMContentLoaded", () => apply(document.documentElement.dataset.theme));
})();
