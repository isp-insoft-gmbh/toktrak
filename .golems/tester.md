---
harness: claude
model: fable
effort: med
schedule: [fri]
os: ubuntu-26.04
timeout: 45m
verify: mise run verify
---

# QA testing

Start the application with `mise run dev`. Load the document
`manual_testing_steps.xml`. Use only the local seeded dev app; never connect to
production. Keep screenshots in OS temp space and mask token secrets before
capture. Missing tooling or unmet prerequisites mean blocked, not a product
failure. Stop the dev server and close the browser before finishing. For each
step in the doc:

- execute it in the dev app
- take a screenshot after a step
- compare step.expectation with real state of app

in case that any expectation was not met:

Search existing open issues before creating a new issue in the TokTrak repo. Use
the file-upload skill for sanitized screenshot evidence. For a new issue:

- upload screenshots
- create very short summary of issue
- create a table of manual testing steps taken with:
  - step name
  - step instruction
  - step expectation
  - use 🗸 if expectation was met or 𝗫
  - embedded screenshot after step
