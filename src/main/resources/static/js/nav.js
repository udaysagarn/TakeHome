// The navigation menus are <details>, so they open, close and take the keyboard on their own.
// This adds only what a mouse user expects on top of that: one menu open at a time, a click
// outside closes, and Escape closes and returns focus to the menu it came from.
(function () {
  function menus() {
    return Array.from(document.querySelectorAll(".nav details.menu"));
  }

  document.addEventListener("toggle", (e) => {
    const menu = e.target;
    if (!menu.matches(".nav details.menu") || !menu.open) return;
    menus().filter((other) => other !== menu).forEach((other) => (other.open = false));
  }, true);

  document.addEventListener("click", (e) => {
    menus()
        .filter((menu) => menu.open && !menu.contains(e.target))
        .forEach((menu) => (menu.open = false));
  });

  document.addEventListener("keydown", (e) => {
    if (e.key !== "Escape") return;
    menus().filter((menu) => menu.open).forEach((menu) => {
      menu.open = false;
      menu.querySelector("summary").focus();
    });
  });
})();
