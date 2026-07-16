# Test Name Convention Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use /skill:executing-plans to
> implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking.

**Goal:** Rename every test case to the approved given/when/then convention and
reject future violations before tests execute.

**Architecture:** JUnit enforcement validates `MethodSource` names from the
already bounded discovery plan. `BuildTest` separately reflects over
non-synthetic private static no-argument `void` methods, its existing test-case
shape. Each isolated test context owns the same regular expression; no shared
production utility is added.

**Tech Stack:** Java 26, JUnit Platform 6, JUnit Jupiter 6, custom
`tools.Build`, Refaster 2.50.0

**Roadmap:** None

**Phase:** Single-plan implementation

---

## Task 1: Record why Refaster cannot rename test symbols

**Files:**

- Modify: `docs/REFASTER_RULE_FAILS.md`

- [x] **Step 1: Confirm the current Refaster boundary**

Use the current documentation already fetched from
`https://errorprone.info/docs/refaster`: Refaster templates match and replace
Java expressions, statements, and method bodies; they do not rename declared
method symbols and all call sites.

- [x] **Step 2: Record the failed rule attempt**

Append:

```markdown
## 2026-07-16 — Test method naming convention

**Pattern:** Rename every JUnit test method and manually invoked `BuildTest`
case to
`given_<camelCaseContext>_when_<camelCaseBehavior>_then_<camelCaseExpectation>`.

**Attempt:** Checked current Refaster 2.50.0 template capabilities against the
repeated method-declaration rename. JUnit methods require declaration-symbol
renames; `BuildTest` additionally requires matching direct call-site renames.

**Failure:** Refaster templates rewrite matched expressions, statements, and
method bodies. They cannot rename a declared method symbol or update every
reference to that symbol, so no safe Refaster rule can express this change.

**Outcome:** Rule rejected as impossible. Apply explicit symbol renames and let
javac verify every `BuildTest` call site.

**Upgrade trigger:** Refaster adds declaration-symbol rename support with
compiler-resolved call-site updates.
```

- [x] **Step 3: Format the failure log**

Run:

```bash
dprint fmt docs/REFASTER_RULE_FAILS.md
```

Expected: Markdown formatting succeeds. Do not stage or commit: this file
already contains unrelated worktree changes.

## Task 2: Add directly tested name predicates

**Files:**

- Modify: `tests/toktrak.tests/toktrak/tests/TestLauncher.java`
- Modify: `tests/toktrak.tests/toktrak/tests/TestLauncherTest.java`
- Modify: `tests/tools/BuildTest.java`

- [x] **Step 1: Add failing predicate tests**

Add this JUnit case to `TestLauncherTest`:

```java
@Test
void given_testNameForms_when_validatingConvention_then_acceptsOnlyCanonicalForm() {
  assertTrue(
      TestLauncher.validTestName("given_existingWorld_when_behaviorRuns_then_stateChanges"));
  assertFalse(TestLauncher.validTestName("existingWorld_when_behaviorRuns_then_stateChanges"));
  assertFalse(TestLauncher.validTestName("given_ExistingWorld_when_behaviorRuns_then_stateChanges"));
  assertFalse(TestLauncher.validTestName("given_existing_world_when_behaviorRuns_then_stateChanges"));
}
```

Add this invocation to the normal no-argument branch of `BuildTest.main`:

```java
given_testNameForms_when_validatingConvention_then_acceptsOnlyCanonicalForm();
```

Add this build-tool case:

```java
private static void
    given_testNameForms_when_validatingConvention_then_acceptsOnlyCanonicalForm() {
  if (!validTestName("given_existingWorld_when_behaviorRuns_then_stateChanges")
      || validTestName("existingWorld_when_behaviorRuns_then_stateChanges")
      || validTestName("given_ExistingWorld_when_behaviorRuns_then_stateChanges")
      || validTestName("given_existing_world_when_behaviorRuns_then_stateChanges")) {
    throw new AssertionError("test name convention mismatch");
  }
}
```

- [x] **Step 2: Run tests to verify compilation fails**

Run:

```bash
mise run test tests/toktrak.tests/toktrak/tests/TestLauncherTest.java
mise run test tests/tools/BuildTest.java
```

Expected: both commands fail because their local `validTestName(String)` methods
do not exist.

- [x] **Step 3: Add isolated predicates**

In both `TestLauncher` and `BuildTest`, import `java.util.regex.Pattern` and
add:

```java
private static final Pattern TEST_NAME =
    Pattern.compile(
        "given_[a-z][A-Za-z0-9]*_when_[a-z][A-Za-z0-9]*_then_[a-z][A-Za-z0-9]*");
```

Add this package-private method to `TestLauncher`:

```java
static boolean validTestName(String name) {
  assert name != null;
  return TEST_NAME.matcher(name).matches();
}
```

Add the same method as `private static` in `BuildTest`.

- [x] **Step 4: Run focused tests**

Run:

```bash
mise run fmt tests/toktrak.tests/toktrak/tests/TestLauncher.java tests/toktrak.tests/toktrak/tests/TestLauncherTest.java tests/tools/BuildTest.java
mise run test tests/toktrak.tests/toktrak/tests/TestLauncherTest.java
mise run test tests/tools/BuildTest.java
```

Expected: the focused JUnit tests and all build-tool tests pass.

## Task 3: Rename foundational JUnit tests

**Files:**

- Modify: `tests/toktrak.tests/toktrak/tests/AssertionsTest.java`
- Modify: `tests/toktrak.tests/toktrak/tests/BodyLimitTest.java`
- Modify: `tests/toktrak.tests/toktrak/tests/ConfigTest.java`
- Modify: `tests/toktrak.tests/toktrak/tests/DataLockTest.java`
- Modify: `tests/toktrak.tests/toktrak/tests/DevDataTest.java`
- Modify: `tests/toktrak.tests/toktrak/tests/EventEnvelopeTest.java`

- [x] **Step 1: Apply these exact method renames without changing bodies**

```text
assertionsAreEnabled -> given_testJvm_when_checkingAssertionStatus_then_assertionsAreEnabled
rejectsCallerLimitAboveGlobalCeiling -> given_limitAboveGlobalCeiling_when_readingBody_then_rejectsLimit
acceptsLimitBoundariesAndExactBody -> given_boundaryLimitsAndExactBodies_when_readingBody_then_returnsBodies
rejectsNegativeLimit -> given_negativeLimit_when_readingBody_then_rejectsLimit
rejectsZeroProgressRead -> given_zeroProgressStream_when_readingBody_then_throwsIOException
rejectsBodyAboveLimit -> given_bodyAboveCallerLimit_when_readingBody_then_rejectsBody

devAuthDefaultsPortAndDataDir -> given_devAuthMode_when_parsingConfig_then_defaultsPortAndDataDirectory
productionRequiresDataDirAndBaseUrl -> given_productionWithoutRequiredOptions_when_parsingConfig_then_rejectsArguments
rejectsNonLocalHttpWithoutDevAuth -> given_nonLocalHttpWithoutDevAuth_when_parsingConfig_then_rejectsBaseUrl
acceptsPinnedDevClockOnlyInDevMode -> given_devModeWithClockArgument_when_parsingConfig_then_clockReportsRequestedInstant
rejectsTooManyArguments -> given_argumentCountAboveLimit_when_parsingConfig_then_rejectsArguments
rejectsOversizedArgument -> given_argumentAboveLengthLimit_when_parsingConfig_then_rejectsArgument
rejectsMissingOptionValues -> given_optionsWithoutValues_when_parsingConfig_then_rejectsArguments
acceptsNonLocalHttpsInProduction -> given_nonLocalHttpsProductionUrl_when_parsingConfig_then_acceptsBaseUrl
rejectsOversizedEnvironmentValue -> given_environmentValueAboveLimit_when_parsingConfig_then_rejectsValue
acceptsExplicitDataDirAndPort -> given_explicitDataDirectoryAndPort_when_parsingConfig_then_usesValues

rejectsSecondLockInSameJvm -> given_directoryLockedInJvm_when_acquiringSecondLock_then_rejectsAcquisition

copiesCorpusToDisposableDataDirectory -> given_developmentCorpus_when_preparingData_then_copiesCorpus
failedBoundedCopyPreservesExistingData -> given_oversizedCorpusAndExistingData_when_copyFails_then_preservesExistingData
appStartupCopiesCorpusBeforeOpeningEventLog -> given_developmentCorpus_when_startingApp_then_eventLogMatchesCorpus

rejectsTypeAboveUtf8Limit -> given_typeAboveUtf8Limit_when_creatingEnvelope_then_rejectsType
rejectsActorAboveUtf8Limit -> given_actorAboveUtf8Limit_when_creatingEnvelope_then_rejectsActor
rejectsTooManyNestedValues -> given_nestedValuesAboveCountLimit_when_creatingEnvelope_then_rejectsData
acceptsDataAtValueAndDepthLimits -> given_dataAtValueAndDepthLimits_when_creatingEnvelope_then_preservesData
acceptsMaximumTopLevelDataEntries -> given_topLevelDataAtEntryLimit_when_creatingEnvelope_then_acceptsData
rejectsNestedDataAboveDepthLimit -> given_nestedDataAboveDepthLimit_when_creatingEnvelope_then_rejectsData
rejectsTooManyTopLevelDataEntries -> given_topLevelDataAboveEntryLimit_when_creatingEnvelope_then_rejectsData
```

- [x] **Step 2: Format and run the selected classes**

Run:

```bash
mise run fmt tests/toktrak.tests/toktrak/tests/AssertionsTest.java tests/toktrak.tests/toktrak/tests/BodyLimitTest.java tests/toktrak.tests/toktrak/tests/ConfigTest.java tests/toktrak.tests/toktrak/tests/DataLockTest.java tests/toktrak.tests/toktrak/tests/DevDataTest.java tests/toktrak.tests/toktrak/tests/EventEnvelopeTest.java
mise run test tests/toktrak.tests/toktrak/tests/AssertionsTest.java tests/toktrak.tests/toktrak/tests/BodyLimitTest.java tests/toktrak.tests/toktrak/tests/ConfigTest.java tests/toktrak.tests/toktrak/tests/DataLockTest.java tests/toktrak.tests/toktrak/tests/DevDataTest.java tests/toktrak.tests/toktrak/tests/EventEnvelopeTest.java
```

Expected: 27 selected tests pass with unchanged assertions.

## Task 4: Rename storage and HTTP JUnit tests

**Files:**

- Modify: `tests/toktrak.tests/toktrak/tests/EventLogTest.java`
- Modify: `tests/toktrak.tests/toktrak/tests/HealthModeTest.java`
- Modify: `tests/toktrak.tests/toktrak/tests/HttpAdmissionTest.java`
- Modify: `tests/toktrak.tests/toktrak/tests/HttpServerTest.java`
- Modify: `tests/toktrak.tests/toktrak/tests/JlinkSmokeTest.java`
- Modify: `tests/toktrak.tests/toktrak/tests/JsonLogFormatterTest.java`

- [x] **Step 1: Apply these exact method renames without changing bodies**

```text
appendsNewlineTerminatedEnvelopeAndReadsIt -> given_newEnvelope_when_appendingAndReplaying_then_returnsNewlineTerminatedEvent
streamsEventsInOrder -> given_twoLoggedEvents_when_replayingLog_then_returnsEventsInOrder
rejectsEventAboveReplayLimit -> given_eventCountAboveReplayLimit_when_replayingLog_then_rejectsReplay
rejectsOversizedLogBeforeRecovery -> given_logAboveFileLimit_when_recoveringTornTail_then_rejectsLog
writeFullyHandlesPartialChannelWrites -> given_partialWriteChannel_when_writingFully_then_writesAllBytes
truncatesOnlyFinalTornTail -> given_finalTornTail_when_recoveringLog_then_truncatesFragment
truncatesTornTailAtExactLineLimit -> given_tornTailAtLineLimit_when_recoveringLog_then_truncatesFragment
malformedCompleteLineFailsLoudly -> given_malformedCompleteLine_when_replayingLog_then_rejectsLine
rejectsInvalidUtf8InCompleteLine -> given_invalidUtf8CompleteLine_when_replayingLog_then_rejectsLine
rejectsJsonAboveNestingLimit -> given_jsonAboveNestingLimit_when_replayingLog_then_rejectsLine
rejectsJsonNumberAboveLengthLimit -> given_jsonNumberAboveLengthLimit_when_replayingLog_then_rejectsLine
rejectsLineAboveTenMiB -> given_lineAboveLengthLimit_when_replayingLog_then_rejectsLine

failWritesStartsAfterBindingAndReportsDegraded -> given_failWritesMode_when_startingApp_then_reportsDegradedHealth
rootShowsDevAuthStrip -> given_devAuthMode_when_requestingRoot_then_returnsDevAuthStrip

saturatedProductionSizedExecutorReturnsServiceUnavailable -> given_saturatedProductionExecutor_when_requestingHealth_then_returnsServiceUnavailable

healthReturnsOkJsonAndSecurityHeaders -> given_healthyApp_when_requestingHealth_then_returnsJsonAndSecurityHeaders
oversizedPathReturnsUriTooLong -> given_pathAboveLengthLimit_when_requestingRoute_then_returnsUriTooLong
unknownApiRouteReturnsEnvelope -> given_unknownApiRoute_when_requestingRoute_then_returnsNotFoundEnvelope
unknownBrowserRouteReturnsBrutalHtml -> given_unknownBrowserRoute_when_requestingRoute_then_returnsBrutalNotFoundHtml

prodRuntimeImageExistsAfterJlinkTaskWhenRequested -> given_optionalProductionImage_when_checkingImage_then_existingImageHasJavaExecutable

formatsOneCompactJsonLine -> given_infoLogRecord_when_formattingRecord_then_returnsLineWithExpectedFieldsAndNoEmail
```

- [x] **Step 2: Format and run untagged selected classes**

Run:

```bash
mise run fmt tests/toktrak.tests/toktrak/tests/EventLogTest.java tests/toktrak.tests/toktrak/tests/HealthModeTest.java tests/toktrak.tests/toktrak/tests/HttpAdmissionTest.java tests/toktrak.tests/toktrak/tests/HttpServerTest.java tests/toktrak.tests/toktrak/tests/JlinkSmokeTest.java tests/toktrak.tests/toktrak/tests/JsonLogFormatterTest.java
mise run test tests/toktrak.tests/toktrak/tests/EventLogTest.java tests/toktrak.tests/toktrak/tests/HealthModeTest.java tests/toktrak.tests/toktrak/tests/HttpAdmissionTest.java tests/toktrak.tests/toktrak/tests/HttpServerTest.java tests/toktrak.tests/toktrak/tests/JsonLogFormatterTest.java
```

Expected: 20 selected untagged tests pass. The tagged Jlink smoke case is
compiled here and runs later through `verify`.

## Task 5: Rename remaining JUnit tests and enforce discovered names

**Files:**

- Modify: `tests/toktrak.tests/toktrak/tests/MainTest.java`
- Modify: `tests/toktrak.tests/toktrak/tests/ProjectionTest.java`
- Modify: `tests/toktrak.tests/toktrak/tests/RestartAndLockTest.java`
- Modify: `tests/toktrak.tests/toktrak/tests/ShutdownTest.java`
- Modify: `tests/toktrak.tests/toktrak/tests/TestLauncher.java`
- Modify: `tests/toktrak.tests/toktrak/tests/TestLauncherTest.java`
- Modify: `tests/toktrak.tests/toktrak/tests/WriterTest.java`

- [x] **Step 1: Apply these exact method renames without changing bodies**

```text
helpUsesEnvironmentNeutralName -> given_helpOption_when_runningMain_then_printsEnvironmentNeutralName

rebuildCountsRawEventsAfterLatestCompatibleSnapshot -> given_compatibleSnapshotAndRawEvents_when_rebuildingProjection_then_countsFromSnapshot
acceptsZeroEventSnapshot -> given_zeroEventSnapshot_when_rebuildingProjection_then_preservesZeroCount
rawEventAfterMaximumSnapshotOverflows -> given_maximumSnapshotCount_when_applyingRawEvent_then_throwsArithmeticException
ignoresIncompatibleSnapshotAndReplaysAllRawEvents -> given_incompatibleSnapshotAndRawEvents_when_rebuildingProjection_then_countsRawEvents

restartRebuildsProjectionFromEventLog -> given_existingEventLog_when_restartingApp_then_rebuildsProjection
failedCorpusStartupCannotReplaceLiveEventLog -> given_liveEventLog_when_corpusStartupFails_then_preservesEventLog
secondAppCannotOpenSameDataDir -> given_lockedDataDirectory_when_startingSecondApp_then_rejectsStartup

appCloseIsIdempotent -> given_runningApp_when_closingTwice_then_completesWithoutError
writerCloseDrainsAcceptedCommands -> given_pausedWriterWithAcceptedCommand_when_closingWriter_then_drainsCommand

unitGroupIncludesOnlyUntaggedTests -> given_unitGroup_when_checkingTestTags_then_includesOnlyUntaggedTests
taggedGroupIncludesOnlyTaggedTests -> given_taggedGroup_when_checkingTestTags_then_includesOnlyTaggedTests
omitsZeroExceptionalCounts -> given_zeroExceptionalCounts_when_formattingSummary_then_omitsCounts
includesNonzeroExceptionalCounts -> given_nonzeroExceptionalCounts_when_formattingSummary_then_includesCounts

successReturnsOnlyAfterAppendAndProjectionApply -> given_acceptedCommand_when_awaitingSuccess_then_appendsAndAppliesProjection
projectionOverflowDoesNotAppendEvent -> given_maximumProjection_when_submittingCommand_then_doesNotAppendEvent
fullQueueReturnsRejectedFuture -> given_fullWriterQueue_when_tryingCommand_then_reportsNotAccepted
closedWriterReportsClosedInsteadOfFull -> given_closedWriter_when_submittingCommand_then_reportsClosed
forcedAbortFailsInFlightWithoutApplyingProjection -> given_inFlightCommandBlockedOnClock_when_forcingClose_then_failsWithoutProjection
forcedCloseFindsClaimedRequest -> given_claimedRequest_when_forcingClose_then_failsWithoutProjection
forcedAbortAfterFsyncDoesNotCommitProjectionOrSuccess -> given_fsyncedCommandNotApplied_when_forcingClose_then_commitsNeitherProjectionNorSuccess
injectedFailureMarksHealthDegraded -> given_injectedWriteFailure_when_submittingCommand_then_marksHealthDegraded
```

Keep the Task 2 convention predicate test name unchanged because it already
conforms.

- [x] **Step 2: Add failing discovery-enforcement call**

Immediately after validating `testsDiscovered` in `TestLauncher.main`, add:

```java
requireTestNames(allTests);
```

Run:

```bash
mise run test
```

Expected: compilation fails because `requireTestNames(TestPlan)` does not exist.

- [x] **Step 3: Implement bounded JUnit enforcement**

Import:

```java
import org.junit.platform.engine.support.descriptor.MethodSource;
```

Add:

```java
private static void requireTestNames(TestPlan testPlan) {
  assert testPlan != null;
  for (var root : testPlan.getRoots()) {
    for (var test : testPlan.getDescendants(root)) {
      if (!test.isTest()) continue;
      var source =
          test.getSource()
              .orElseThrow(
                  () -> new IllegalStateException("test has no source: " + test.getDisplayName()));
      if (!(source instanceof MethodSource methodSource)) {
        throw new IllegalStateException("test has no method source: " + test.getDisplayName());
      }
      if (!validTestName(methodSource.getMethodName())) {
        throw new IllegalStateException(
            "invalid test name: "
                + methodSource.getClassName()
                + "."
                + methodSource.getMethodName());
      }
    }
  }
}
```

Bound traversal before calling `requireTestNames`:

```java
private static final long TEST_DESCRIPTOR_COUNT_MAX = 20_000;

static void requireDescriptorCount(long descriptorCount) {
  if (descriptorCount < 0 || descriptorCount > TEST_DESCRIPTOR_COUNT_MAX) {
    throw new IllegalStateException(
        "test descriptors must be 0.."
            + TEST_DESCRIPTOR_COUNT_MAX
            + ": "
            + descriptorCount);
  }
}
```

Call `requireDescriptorCount(...countTestIdentifiers(_ -> true))` for both the
unfiltered discovery plan and the separately discovered filtered execution plan;
call `requireTestNames` for both before execution. `TestLauncherTest` checks the
20,000/20,001 boundary.

- [x] **Step 4: Format and run the full JUnit suite**

Run:

```bash
mise run fmt tests/toktrak.tests/toktrak/tests/MainTest.java tests/toktrak.tests/toktrak/tests/ProjectionTest.java tests/toktrak.tests/toktrak/tests/RestartAndLockTest.java tests/toktrak.tests/toktrak/tests/ShutdownTest.java tests/toktrak.tests/toktrak/tests/TestLauncher.java tests/toktrak.tests/toktrak/tests/TestLauncherTest.java tests/toktrak.tests/toktrak/tests/WriterTest.java
mise run test
```

Expected: 71 unit tests pass; name validation runs after bounded discovery and
before execution.

## Task 6: Rename and enforce build-tool test cases

**Files:**

- Modify: `tests/tools/BuildTest.java`

- [x] **Step 1: Apply these exact method and direct-call renames**

```text
rejectsOversizedHashInput -> given_oversizedFile_when_hashing_then_rejectsInput
rejectsTraversalAboveLimit -> given_oversizedTree_when_listingPaths_then_rejectsInput
rejectsArgumentLimits -> given_invalidArguments_when_writingArgumentFile_then_rejectsInput
generatesEclipseProjects -> given_projectSources_when_generatingEclipseProjects_then_writesValidMetadata
generatesIntellijProjects -> given_projectSources_when_generatingIntellijProjects_then_writesValidMetadata
selectsTestsByFileAndDirectory -> given_testPaths_when_selectingTests_then_returnsExpectedClasses
rejectsNonTestSelection -> given_productionPath_when_selectingTests_then_rejectsInput
rejectsSymbolicAncestor -> given_symbolicSourceAncestor_when_selectingPitTargets_then_rejectsInput
parsesPitSelections -> given_pitArguments_when_selectingTargets_then_parsesOptions
rejectsInvalidPitSelections -> given_invalidPitArguments_when_selectingTargets_then_rejectsInput
buildsPitArguments -> given_pitSelection_when_buildingArguments_then_preservesRequiredOptions
validatesPitReports -> given_pitReports_when_validatingReport_then_acceptsOnlyValidMutations
validatesPitDependencyArtifacts -> given_pitArtifactSets_when_validatingDependencies_then_acceptsCompleteSetAndRejectsMissingHistoryOrInvalidJunitPlugin
pitHelpHasNoFileSideEffects -> given_existingArgumentFile_when_requestingPitHelp_then_preservesFile
rejectsOversizedStamp -> given_stampAboveLengthLimit_when_readingStamp_then_rejectsInput
rejectsInvalidUtf8Stamp -> given_invalidUtf8Stamp_when_readingStamp_then_rejectsInput
validatesCacheArtifacts -> given_cacheArtifacts_when_checkingCacheHit_then_requiresMatchingOutputs
waitsForNormalProcess -> given_completedProcess_when_waitingForExit_then_returnsExitCode
passesArgumentsWithSpaces -> given_argumentsContainingSpaces_when_buildingCommand_then_preservesArguments
batchesFormatterSources -> given_manyFormatterSources_when_batchingSources_then_preservesSourceCount
rejectsOversizedCommand -> given_commandAboveLengthLimit_when_buildingCommand_then_rejectsInput
appliesWindowsOsNameRule -> given_windowsOsNamePattern_when_applyingRefaster_then_rewritesOnlyOsCheck
detectsDirtyGitTree -> given_gitTreeWithUntrackedFile_when_checkingStatus_then_reportsDirty
assignsTestGroupTimeouts -> given_testGroups_when_selectingTimeouts_then_returnsConfiguredDurations
forceTerminatesHardTimedOutProcess -> given_runningProcess_when_timeoutUsesForceOption_then_reportsTimeoutAndStopsProcess
terminatesTimedOutProcess -> given_runningProcess_when_timeoutExpires_then_terminatesProcess
terminatesTimedOutProcessDescendants -> given_runningProcessTree_when_timeoutExpires_then_terminatesDescendants
```

Task 2 already added and directly invoked
`given_testNameForms_when_validatingConvention_then_acceptsOnlyCanonicalForm`.

- [x] **Step 2: Add the failing enforcement calls**

Change the assertion helper call to:

```java
requireAssertions(BuildTest.class);
```

After argument-mode handling and the `args.length` validation, before the first
case invocation, add:

```java
requireTestNames(BuildTest.class.getDeclaredMethods());
```

Run:

```bash
mise run test tests/tools/BuildTest.java
```

Expected: compilation fails because both new helper signatures are absent.

- [x] **Step 3: Implement bounded reflection enforcement**

Import:

```java
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
```

Add beside the existing limits:

```java
private static final int DECLARED_METHOD_COUNT_MAX = 1_000;
```

Replace the assertion helper and add the name helper:

```java
private static void requireAssertions(Class<?> owner) {
  assert owner != null;
  if (!owner.desiredAssertionStatus()) {
    throw new IllegalStateException("Java assertions must be enabled with -ea");
  }
}

private static void requireTestNames(Method[] methods) {
  assert methods != null;
  if (methods.length > DECLARED_METHOD_COUNT_MAX) {
    throw new IllegalStateException(
        "declared methods exceed " + DECLARED_METHOD_COUNT_MAX + " entries");
  }
  for (var method : methods) {
    int modifiers = method.getModifiers();
    if (method.isSynthetic()
        || !Modifier.isPrivate(modifiers)
        || !Modifier.isStatic(modifiers)
        || method.getReturnType() != void.class
        || method.getParameterCount() != 0) {
      continue;
    }
    if (!validTestName(method.getName())) {
      throw new IllegalStateException("invalid test name: " + method.getName());
    }
  }
}
```

- [x] **Step 4: Format and run build-tool tests**

Run:

```bash
mise run fmt tests/tools/BuildTest.java
mise run test tests/tools/BuildTest.java
```

Expected: all build-tool cases pass, including the positive/negative predicate
case.

## Task 7: Persist the rule and verify the repository

**Files:**

- Modify: `CLAUDE.md`

- [x] **Step 1: Add the approved project rule**

Under `# Project Rules`, add exactly:

```markdown
- Name every test case
  `given_<camelCaseContext>_when_<camelCaseBehavior>_then_<camelCaseExpectation>`;
  helper methods are exempt.
```

- [x] **Step 2: Format and run complete verification**

Run:

```bash
dprint fmt CLAUDE.md
mise run verify
```

Expected: Markdown checks pass, 71 unit tests pass, one tagged smoke test
passes, and all build-tool tests pass.

- [x] **Step 3: Exercise mutation testing with shared history**

Run:

```bash
mise run pit --history
```

Expected: PIT completes, `output/pit.history` is nonempty,
`output/mutations/index.html` and `output/mutations/mutations.xml` exist, and no
exceptional mutation status is reported. If PIT reports a history error or
inconsistent results, delete `output/pit.history` and rerun `mise run pit` once.

- [x] **Step 4: Confirm every test case matches**

Run:

```bash
mise run test
mise run test tests/tools/BuildTest.java
git diff --check
```

Expected: runtime enforcement accepts every discovered JUnit test and every
structurally selected `BuildTest` case; the diff has no whitespace errors.

- [x] **Step 5: Preserve unrelated worktree changes**

Run:

```bash
git status --short
git diff -- CLAUDE.md tests docs/REFASTER_RULE_FAILS.md
```

Expected: the naming changes are visible alongside pre-existing worktree edits.
Do not stage or commit implementation files; whole-file staging would capture
unrelated user and mutation-testing changes.
