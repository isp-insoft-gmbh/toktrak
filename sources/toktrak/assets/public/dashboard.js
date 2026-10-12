const COUNTER_DURATION_MILLIS = 450;
const COUNTER_FRAMES_MAX = 30;
const COUNTERS_MAX = 3;

export function timestampLabel(value, locale = undefined, timeZone = undefined) {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$/u.test(value)) return null;
  const instant = new Date(value);
  if (Number.isNaN(instant.getTime())) return null;
  return new Intl.DateTimeFormat(locale, {
    day: "numeric",
    month: "short",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    timeZone,
    timeZoneName: "short",
  }).format(instant);
}

export function counterValue(value) {
  const match = value.match(/^-?(?:0|[1-9]\d*)(?:\.(\d+))?$/u);
  if (match === null || value.length > 24 || (match[1]?.length ?? 0) > 6) return null;
  const number = Number(value);
  return Number.isFinite(number) && Math.abs(number) <= 1_000_000_000 ? number : null;
}

export function createCounterAnimator(environment) {
  const counters = new WeakMap();
  const connected = (counter) => counter.isConnected !== false;
  const baseline = (counter, value, display) => counters.set(counter, { value, display, cancel: null });

  const cancel = (counter) => {
    const previous = counters.get(counter);
    if (previous?.cancel) previous.cancel();
  };

  const animate = (counter, from, to, display) => {
    cancel(counter);
    const match = display.match(/^(.*?)(-?\d[\d,]*(?:\.(\d+))?)(.*?)$/u);
    if (match === null || !connected(counter)) {
      baseline(counter, to, display);
      return;
    }
    const [, prefix, , decimals = "", suffix] = match;
    const formatter = new Intl.NumberFormat("en-US", {
      minimumFractionDigits: decimals.length,
      maximumFractionDigits: decimals.length,
    });
    let complete = false;
    let frameHandle = null;
    let timer = null;
    let frameCount = 0;
    const startedAt = environment.now();
    const visual = counter.visual;
    const finish = () => {
      if (complete) return;
      complete = true;
      if (frameHandle !== null) environment.cancelFrame(frameHandle);
      if (timer !== null) environment.clearTimer(timer);
      if (connected(counter)) {
        visual.data = display;
        counter.canonical.data = display;
      }
      baseline(counter, to, display);
    };
    timer = environment.setTimer(finish, COUNTER_DURATION_MILLIS + 50);
    const frame = (now) => {
      if (complete || !connected(counter)) return;
      frameCount += 1;
      const progress = Math.min(1, (now - startedAt) / COUNTER_DURATION_MILLIS);
      visual.data = `${prefix}${formatter.format(from + (to - from) * progress)}${suffix}`;
      if (progress === 1 || frameCount >= COUNTER_FRAMES_MAX) finish();
      else frameHandle = environment.frame(frame);
    };
    counters.set(counter, {
      value: to,
      display,
      cancel: () => {
        if (complete) return;
        complete = true;
        if (frameHandle !== null) environment.cancelFrame(frameHandle);
        if (timer !== null) environment.clearTimer(timer);
      },
    });
    frameHandle = environment.frame(frame);
  };

  return {
    refresh(counter, initial = false) {
      const display = counter.text();
      const target = counterValue(counter.target());
      const previous = counters.get(counter);
      if (target === null) {
        cancel(counter);
        counters.delete(counter);
      } else if (previous === undefined || initial) {
        cancel(counter);
        baseline(counter, target, display);
      } else if (previous.value !== target) {
        if (environment.reducedMotion() || !connected(counter)) {
          cancel(counter);
          baseline(counter, target, display);
        } else animate(counter, previous.value, target, display);
      }
    },
    cancel,
  };
}

if (typeof document !== "undefined") {
  let refreshPending = false;
  const environment = {
    now: () => performance.now(),
    frame: (callback) => requestAnimationFrame(callback),
    cancelFrame: (handle) => cancelAnimationFrame(handle),
    setTimer: (callback, delay) => setTimeout(callback, delay),
    clearTimer: (handle) => clearTimeout(handle),
    reducedMotion: () => matchMedia("(prefers-reduced-motion: reduce)").matches,
  };
  const animator = createCounterAnimator(environment);
  const counterModels = new WeakMap();

  const counter = (element) => {
    const existing = counterModels.get(element);
    if (existing !== undefined && existing.visual.isConnected) return existing;
    if (existing !== undefined) animator.cancel(existing);
    let visual = element.querySelector(":scope > [data-counter-visual]");
    let canonical = element.querySelector(":scope > [data-counter-canonical]");
    if (visual === null || canonical === null) {
      const display = element.textContent;
      element.replaceChildren();
      visual = document.createElement("span");
      visual.setAttribute("aria-hidden", "true");
      visual.setAttribute("data-counter-visual", "");
      visual.append(document.createTextNode(display));
      canonical = document.createElement("span");
      canonical.className = "sr-only";
      canonical.setAttribute("data-counter-canonical", "");
      canonical.append(document.createTextNode(display));
      element.append(visual, canonical);
    }
    const model = existing ?? {
      get isConnected() {
        return element.isConnected;
      },
      target: () => element.getAttribute("data-counter") ?? "",
      text: () => model.visual.data,
    };
    model.visual = visual.firstChild;
    model.canonical = canonical.firstChild;
    counterModels.set(element, model);
    return model;
  };

  const refresh = (initial) => {
    refreshPending = false;
    for (const time of document.querySelectorAll("time[datetime]")) {
      const label = timestampLabel(time.dateTime);
      if (label !== null && time.textContent !== label)
        time.firstChild?.nodeType === Node.TEXT_NODE ? (time.firstChild.data = label) : (time.textContent = label);
    }
    let counterCount = 0;
    for (const element of document.querySelectorAll("[data-counter]")) {
      if (counterCount++ >= COUNTERS_MAX) break;
      animator.refresh(counter(element), initial);
    }
  };

  const scheduleRefresh = (records) => {
    if (
      records.every(
        (record) =>
          record.type === "characterData" &&
          (record.target.parentElement?.hasAttribute("data-counter-visual") ||
            record.target.parentElement?.hasAttribute("data-counter-canonical")),
      )
    )
      return;
    if (refreshPending) return;
    refreshPending = true;
    requestAnimationFrame(() => refresh(false));
  };

  refresh(true);
  new MutationObserver(scheduleRefresh).observe(document.documentElement, {
    childList: true,
    characterData: true,
    subtree: true,
    attributes: true,
    attributeFilter: ["data-counter", "datetime"],
  });
}
