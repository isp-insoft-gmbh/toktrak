import assert from "node:assert/strict";
import test from "node:test";
import { publishRelease } from "./release-publication.mjs";

function scenario(existing = false) {
  const calls = [];
  const record = { id: 3, draft: true };
  const actions = {
    existing,
    version: 3,
    ensureDraft: async (allowCreate) => {
      calls.push(`draft:${allowCreate}`);
      return record;
    },
    pushVersioned: async () => calls.push("push:v3"),
    latestTag: async () => {
      calls.push("latest-tag");
      return 3;
    },
    publishDraft: async (value) => {
      assert.equal(value, record);
      calls.push("publish");
    },
    pushLatest: async () => calls.push("push:latest"),
    verifyLatest: async () => calls.push("verify:latest"),
    markLatest: async (value) => {
      assert.equal(value, record);
      calls.push("mark:latest");
    },
  };
  return { actions, calls };
}

test("given_newVersion_when_publishing_then_freezesNotesBeforeImmutablePush", async () => {
  const { actions, calls } = scenario();
  await publishRelease(actions);
  assert.deepEqual(calls, [
    "draft:true",
    "push:v3",
    "publish",
    "latest-tag",
    "push:latest",
    "verify:latest",
    "mark:latest",
  ]);
});

test("given_draftCreationFails_when_publishing_then_neverPushes", async () => {
  const { actions, calls } = scenario();
  actions.ensureDraft = async () => {
    throw new Error("draft request failed");
  };
  await assert.rejects(publishRelease(actions), /draft request failed/u);
  assert.deepEqual(calls, []);
});

test("given_publishedVersion_when_retrying_then_neverRebuildsOrReplacesImmutableTag", async () => {
  const { actions, calls } = scenario(true);
  await publishRelease(actions);
  assert.deepEqual(calls, ["draft:false", "publish", "latest-tag", "push:latest", "verify:latest", "mark:latest"]);
});

test("given_publicationFailsAfterPush_when_retrying_then_usesExistingVersion", async () => {
  const initial = scenario();
  initial.actions.publishDraft = async () => {
    throw new Error("publication failed");
  };
  await assert.rejects(publishRelease(initial.actions), /publication failed/u);
  assert.deepEqual(initial.calls, ["draft:true", "push:v3"]);
  const retry = scenario(true);
  await publishRelease(retry.actions);
  assert.equal(retry.calls.includes("push:v3"), false);
  assert.equal(retry.calls.at(-1), "mark:latest");
});

test("given_newerTag_when_retryingOlderRelease_then_publishesWithoutMovingLatest", async () => {
  const { actions, calls } = scenario(true);
  actions.latestTag = async () => {
    calls.push("latest-tag");
    return 4;
  };
  await publishRelease(actions);
  assert.deepEqual(calls, ["draft:false", "publish", "latest-tag"]);
});

test("given_latestPushOrVerificationFails_when_retrying_then_finishesExistingRelease", async () => {
  for (const failedStep of ["pushLatest", "verifyLatest"]) {
    const first = scenario();
    first.actions[failedStep] = async () => {
      throw new Error("registry latest failed");
    };
    await assert.rejects(publishRelease(first.actions), /registry latest failed/u);
    assert.equal(first.calls.includes("mark:latest"), false);
    const retry = scenario(true);
    await publishRelease(retry.actions);
    assert.equal(retry.calls.at(-1), "mark:latest");
  }
});
