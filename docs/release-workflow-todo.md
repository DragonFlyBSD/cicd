# Release Git workflow fixes

- [x] Require master/development for new major/minor release branches, and the
  matching release branch for patch releases.
- [x] Support subsequent RCs and final tags on an existing release branch,
  without changing master or repeating version edits.
- [x] Push new release/development branches atomically, reject concurrent ref
  changes, and document recovery after branches have already been published.
- [x] Record both release and next-development versions in master's param.h.
- [x] Run version, workflow, historical-edit, and local Git integration checks;
  update setup and workflow documentation.

Validation: both Groovy check scripts pass under Groovy 2.4.21 and 3.0.25.
Local Git checks cover major/minor creation, patch tagging, subsequent RC/final
tags, recovery with an existing tag, dry runs, rejected atomic pushes, concurrent
master/release changes, and servers without atomic-push support. Replaying the
helpers against the historical source also reproduces the development changes
in `81dd9c6184` exactly. Live Jenkins CPS, approval, and external GPG signing
remain deployment checks.
