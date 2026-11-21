# Test build: project-level build locks (Eclipse 4.41)

Do not merge. This folder is only for a test on Eclipse 4.41.

## What changes

- The auto-build does not lock the whole workspace any more. It locks the
  workspace only for the short PRE_BUILD and POST_BUILD steps.
- Each builder runs with its own rule. A save in project B does not wait for
  a builder of project A that uses the rule of project A.
- A builder that asks for the whole workspace (the default of most builders)
  still locks it, but only while that builder runs.
- A save that waits for the running builder tells the build to stop
  (`isInterrupted()`), as before.

## Install

1. Close Eclipse.
2. Run: `sh install.sh <eclipse dir>`.
   `<eclipse dir>` is the folder with `plugins/` and `eclipse.ini`.
   On macOS: `/Applications/Eclipse.app/Contents/Eclipse`.
3. Start Eclipse once with `-clean`.

To undo: `sh install.sh <eclipse dir> restore`, then start once with `-clean`.

## Give a slow builder the project lock

Most builders ask for the whole workspace. You can tell the platform to give a
builder the rule of its own project. Add this line under `-vmargs` in
`eclipse.ini`:

    -Dorg.eclipse.core.resources.projectRuleBuilders=<builder id>,<builder id>

To find the ID of a builder, open the `.project` file of the project and read
`<buildCommand><name>`. For example, EPIC (Perl) uses
`org.epic.perleditor.perlbuilder`.

Risk: if that builder changes files outside its project, or starts a workspace
operation with a larger rule, it fails with "Attempted to beginRule ... does
not match outer scope rule" in the Error Log. Then remove its ID again.

## Turn the change off

    -Dorg.eclipse.core.resources.relaxedAutoBuild=false

This gives back the old behavior (one lock for the whole auto-build).

## What to report

- Does a save in one project still wait for a build in another project?
- Error Log entries with "beginRule" or "scheduling rule".
- Thread dumps if Eclipse still freezes (`jcmd <pid> Thread.print`).
