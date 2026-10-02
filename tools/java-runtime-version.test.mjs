import assert from "node:assert/strict";
import test from "node:test";
import { javaRuntimeVersion } from "./java-runtime-version.mjs";

test("given_equivalentJavaBuilds_when_normalizingRuntimeVersion_then_matchesUndottedAndDottedVersions", () => {
  const host = "Property settings:\n    java.runtime.version = 27+35\n";
  const image = "Property settings:\n    java.runtime.version = 27.0.0+35-LTS\n";
  assert.equal(javaRuntimeVersion(host), "27+35");
  assert.equal(javaRuntimeVersion(host), javaRuntimeVersion(image));
  assert.notEqual(javaRuntimeVersion(host), javaRuntimeVersion("java.runtime.version = 27.0.1+35\n"));
});

test("given_missingOrAmbiguousJavaVersion_when_normalizing_then_rejectsOutput", () => {
  for (const output of [
    "java.vendor = Eclipse Adoptium\n",
    "java.runtime.version = 27+35\njava.runtime.version = 27+36\n",
    "java.runtime.version = invalid\n",
  ]) {
    assert.throws(() => javaRuntimeVersion(output));
  }
});
