# Test build: project-level build locks (Eclipse 4.41)

Do not merge. This folder is only for a test on Eclipse 4.41.

## What changes

- Builds do not lock the whole workspace any more. This is true for the
  auto-build, for Project > Build, for the build before a launch, and for
  Project > Clean. A build locks the whole workspace only for the short
  PRE_BUILD and POST_BUILD steps.
- Each builder runs with its own rule. A save in project B does not wait for a
  builder of project A that uses the rule of project A.
- A builder that asks for the whole workspace (the default of most builders)
  still locks it, but only while that builder runs.
- A build that has nothing to build does not wait for other builds.
- A launch waits only for builds of its own projects, if its launch
  configuration names them (most Java launches do).
- A save that waits for the running builder tells the build to stop
  (`isInterrupted()`), as before.

The patch changes four bundles: org.eclipse.core.resources,
org.eclipse.core.jobs, org.eclipse.debug.ui and org.eclipse.ui.ide.

## Install

1. Download the two files into one folder:

       curl -LO https://github.com/anydoby/eclipse.platform/raw/build-locks-4.41/build-locks-test/install.sh
       curl -LO https://github.com/anydoby/eclipse.platform/raw/build-locks-4.41/build-locks-test/classes.zip

2. Close Eclipse.
3. Run: `sh install.sh <eclipse dir>`.
   `<eclipse dir>` is the folder with `plugins/` and `eclipse.ini`.
   On macOS: `/Applications/Eclipse.app/Contents/Eclipse`.
4. Start Eclipse once with `-clean`.

To undo: `sh install.sh <eclipse dir> restore`, then start once with `-clean`.

## Give a slow builder the project lock

Most builders ask for the whole workspace. You can tell the platform to give a
builder the rule of its own project. Add this line under `-vmargs` in
`eclipse.ini`:

    -Dorg.eclipse.core.resources.projectRuleBuilders=<builder id>,<builder id>

To find the ID of a builder, open the `.project` file of the project and read
`<buildCommand><name>`. For example, EPIC (Perl) uses
`org.epic.perleditor.perlbuilder`.

If that builder needs more than its project, the first build fails once. Then
the Error Log shows the warning "Builder ... needs a larger scheduling rule than
its project", and the builder gets its own rule again for the rest of the
session. Its next build is a full build, so no change is lost.

Do not add the m2e builder (`org.eclipse.m2e.core.maven2Builder`) unless your
m2e has the fix "Let the Maven builder use the rule of its project": without
it, m2e can leave a rule held when it refreshes a project.

## Turn the change off

    -Dorg.eclipse.core.resources.relaxedBuildRules=false

This gives back the old behavior (one lock for each whole build).

## What to report

- Does a save or a launch in one project still wait for a build in another
  project?
- Error Log entries with "beginRule", "scheduling rule" or "needs a larger
  scheduling rule".
- Thread dumps if Eclipse still freezes (`jcmd <pid> Thread.print`).
