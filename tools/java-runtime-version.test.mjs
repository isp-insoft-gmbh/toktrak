import assert from "node:assert/strict";
import test from "node:test";
import { javaRuntimeVersion } from "./java-runtime-version.mjs";

const property = (version) => `Property settings:\n    java.runtime.version = ${version}\n`;

test("given_vendorOptionalMetadata_when_comparingJavaBuilds_then_preservesRuntimeParity", () => {
  const expected = javaRuntimeVersion(property("27+35"));
  for (const version of ["27+35-FR", "27+35-LTS", "27+35-vendor.1"]) {
    assert.equal(javaRuntimeVersion(property(version)), expected);
  }
  assert.equal(javaRuntimeVersion(property("27-ea+35-FR")), javaRuntimeVersion(property("27-ea+35")));
  assert.equal(javaRuntimeVersion(property("27+-vendor.1")), javaRuntimeVersion(property("27")));
  assert.equal(javaRuntimeVersion(property("27-ea-vendor.1")), javaRuntimeVersion(property("27-ea")));
  assert.equal(javaRuntimeVersion(property("27.0.0.0.1+35")), "27.0.0.0.1+35");
});

test("given_differentJavaComponents_when_comparingJavaBuilds_then_rejectsMismatch", () => {
  const expected = javaRuntimeVersion(property("27+35"));
  for (const version of ["27+36", "27.0.1+35", "27.0.0.1+35", "27-ea+35", "27+0", "27"]) {
    assert.notEqual(javaRuntimeVersion(property(version)), expected);
  }
});

test("given_invalidJavaVersion_when_normalizing_then_rejectsOutput", () => {
  for (const output of [
    "java.vendor = Eclipse Adoptium\n",
    `${property("27+35")}${property("27+36")}`,
    ...[
      "invalid",
      "27+",
      "27-ea+-vendor",
      "27+035",
      "027+35",
      "27.01+35",
      "27.0+35",
      "27+35_vendor",
      "2147483648+35",
      "27+2147483648",
    ].map(property),
  ]) {
    assert.throws(() => javaRuntimeVersion(output));
  }
});
