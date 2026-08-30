// Slide navigation for /deck: arrow keys, space, Home/End, click on the right or left half,
// and #3 in the URL so a single slide can be linked to mid-presentation.
(function () {
  const slides = Array.from(document.querySelectorAll(".slide"));
  if (slides.length === 0) {
    return;
  }

  const position = document.querySelector("[data-slide-position]");
  const count = document.querySelector("[data-slide-count]");
  if (count) {
    count.textContent = String(slides.length);
  }

  let index = 0;

  function show(next, pushHash) {
    index = Math.max(0, Math.min(slides.length - 1, next));
    slides.forEach((slide, i) => slide.classList.toggle("current", i === index));
    if (position) {
      position.textContent = String(index + 1);
    }
    if (pushHash) {
      history.replaceState(null, "", "#" + (index + 1));
    }
    slides[index].scrollTop = 0;
  }

  function fromHash() {
    const parsed = parseInt(window.location.hash.replace("#", ""), 10);
    return Number.isNaN(parsed) ? 0 : parsed - 1;
  }

  document.addEventListener("keydown", (event) => {
    if (event.metaKey || event.ctrlKey || event.altKey) {
      return;
    }
    switch (event.key) {
      case "ArrowRight":
      case "PageDown":
      case " ":
        event.preventDefault();
        show(index + 1, true);
        break;
      case "ArrowLeft":
      case "PageUp":
        event.preventDefault();
        show(index - 1, true);
        break;
      case "Home":
        show(0, true);
        break;
      case "End":
        show(slides.length - 1, true);
        break;
      default:
        break;
    }
  });

  // Clicking advances, so the deck can be driven from a presenter remote — but not when the
  // click was on something the audience is meant to follow.
  document.addEventListener("click", (event) => {
    if (event.target.closest("a, button, object, .deck-bar")) {
      return;
    }
    show(event.clientX < window.innerWidth / 3 ? index - 1 : index + 1, true);
  });

  window.addEventListener("hashchange", () => show(fromHash(), false));
  show(fromHash(), false);
})();
