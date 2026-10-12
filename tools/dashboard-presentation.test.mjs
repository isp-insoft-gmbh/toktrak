import assert from "node:assert/strict";
import test from "node:test";
import { counterValue, createCounterAnimator, timestampLabel } from "../sources/toktrak/assets/public/dashboard.js";

test("given_isoInstant_when_formattingTimestamp_then_labelsSelectedBrowserTimezone", () => {
  assert.equal(timestampLabel("2026-07-14T14:14:39Z", "en-GB", "UTC"), "14 Jul 2026, 14:14 UTC");
  assert.equal(timestampLabel("2026-07-14T14:14:39Z", "en-US", "America/New_York"), "Jul 14, 2026, 10:14 AM EDT");
  assert.equal(timestampLabel("2026-07-14"), null);
});

test("given_counterValues_when_parsing_then_rejectsLossyNumbers", () => {
  assert.equal(counterValue("1068.19"), 1068.19);
  assert.equal(counterValue("1000000001"), null);
  assert.equal(counterValue("9007199254740992"), null);
  assert.equal(counterValue("1e3"), null);
});

test("given_serverDecimalDisplay_when_animating_then_restoresExactAccessibleValue", () => {
  const fake = clock();
  const counter = model("$100", "100");
  const animator = createCounterAnimator(fake.environment);
  animator.refresh(counter, true);
  counter.display = "$213.64";
  counter.targetValue = "213.64";
  animator.refresh(counter);
  fake.frame(225);
  assert.equal(counter.canonical.data, "$100");
  assert.match(counter.visual.data, /^\$\d+\.\d{2}$/u);
  fake.frame(450);
  assert.equal(counter.visual.data, "$213.64");
  assert.equal(counter.canonical.data, "$213.64");
});

test("given_bareIntegerAndDisconnect_when_animating_then_cancelsFramesWithoutFurtherWrites", () => {
  const fake = clock();
  const counter = model("100", "100");
  const animator = createCounterAnimator(fake.environment);
  animator.refresh(counter, true);
  counter.display = "200";
  counter.targetValue = "200";
  animator.refresh(counter);
  assert.equal(fake.frames.size, 1);
  counter.isConnected = false;
  fake.timer();
  assert.equal(counter.visual.data, "100");
  assert.equal(fake.frames.size, 0);
});

function model(display, targetValue) {
  return {
    isConnected: true,
    display,
    targetValue,
    visual: { data: display },
    canonical: { data: display },
    text() {
      return this.display;
    },
    target() {
      return this.targetValue;
    },
  };
}

function clock() {
  let next = 0;
  const frames = new Map();
  const timers = new Map();
  return {
    frames,
    environment: {
      now: () => 0,
      frame(callback) {
        const handle = ++next;
        frames.set(handle, callback);
        return handle;
      },
      cancelFrame(handle) {
        frames.delete(handle);
      },
      setTimer(callback) {
        const handle = ++next;
        timers.set(handle, callback);
        return handle;
      },
      clearTimer(handle) {
        timers.delete(handle);
      },
      reducedMotion: () => false,
    },
    frame(now) {
      const [handle, callback] = frames.entries().next().value;
      frames.delete(handle);
      callback(now);
    },
    timer() {
      const [handle, callback] = timers.entries().next().value;
      timers.delete(handle);
      callback();
    },
  };
}
