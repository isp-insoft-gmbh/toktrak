import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import test from "node:test";
import { findInspectExecutable, parseInspectionResults, writeProfileXml } from "./run.mjs";

test("profile disables unselected known inspections", () => {
  const xml = writeProfileXml(["A", "B"], ["B"]);
  assert.match(xml, /class="A" enabled="false"/);
  assert.match(xml, /class="B" enabled="true"/);
});

test("profile rejects unknown inspection IDs", () => {
  assert.throws(() => writeProfileXml(["A"], ["B"]), /unknown IntelliJ inspection IDs: B/);
});

test("finds IntelliJ inspect executable from override", () => {
  const directory = mkdtempSync(join(tmpdir(), "toktrak-intellij-"));
  const executable = join(directory, "inspect.sh");
  writeFileSync(executable, "#!/bin/sh\n");
  assert.equal(findInspectExecutable({ TOKTRAK_INTELLIJ_INSPECT: executable }, "linux"), executable);
});

test("finds Toolbox IntelliJ inspect executable without user-specific paths", () => {
  const home = mkdtempSync(join(tmpdir(), "toktrak-home-"));
  const executable = join(
    home,
    ".local",
    "share",
    "JetBrains",
    "Toolbox",
    "apps",
    "IDEA-U",
    "ch-0",
    "1",
    "bin",
    "inspect.sh",
  );
  mkdirSync(dirname(executable), { recursive: true });
  writeFileSync(executable, "#!/bin/sh\n");
  assert.equal(findInspectExecutable({ HOME: home }, "linux"), executable);
});

test("parses IntelliJ XML findings", () => {
  const directory = mkdtempSync(join(tmpdir(), "toktrak-intellij-results-"));
  writeFileSync(
    join(directory, "BooleanParameter.xml"),
    `<problems><problem>
      <file>file://$PROJECT_DIR$/sources/toktrak/Foo.java</file>
      <line>7</line>
      <problem_class id="BooleanParameter" severity="WARNING">x</problem_class>
      <description>'public' method &lt;code&gt;foo()&lt;/code&gt;</description>
    </problem></problems>`,
  );
  assert.deepEqual(parseInspectionResults(directory), [
    {
      inspection: "BooleanParameter",
      file: "sources/toktrak/Foo.java",
      line: 7,
      severity: "WARNING",
      description: "'public' method foo()",
    },
  ]);
});
