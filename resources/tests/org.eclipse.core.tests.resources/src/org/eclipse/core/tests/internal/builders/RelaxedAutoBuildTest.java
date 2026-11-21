/*******************************************************************************
 * Copyright (c) 2026 Sergii Zolotarov and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.core.tests.internal.builders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.core.resources.ResourcesPlugin.getWorkspace;
import static org.eclipse.core.tests.resources.ResourceTestUtil.createInWorkspace;
import static org.eclipse.core.tests.resources.ResourceTestUtil.createTestMonitor;
import static org.eclipse.core.tests.resources.ResourceTestUtil.setAutoBuilding;
import static org.eclipse.core.tests.resources.ResourceTestUtil.updateProjectDescription;

import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IWorkspaceDescription;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ICoreRunnable;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.tests.internal.builders.TestBuilder.BuilderRuleCallback;
import org.eclipse.core.tests.resources.util.WorkspaceResetExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Tests that builds hold only the rule of the builder that runs, so that
 * operations that do not conflict with it do not wait for the build.
 */
@ExtendWith(WorkspaceResetExtension.class)
public class RelaxedAutoBuildTest {
	private static final String PROP_PROJECT_RULE_BUILDERS = "org.eclipse.core.resources.projectRuleBuilders";
	private static final long TIMEOUT_SECONDS = 30;

	private IProject projectA;
	private IProject projectB;
	private final CountDownLatch builderStarted = new CountDownLatch(1);
	private final CountDownLatch releaseBuilder = new CountDownLatch(1);

	@BeforeEach
	public void setUp() throws Exception {
		setAutoBuilding(false);
		projectA = getWorkspace().getRoot().getProject("A");
		projectB = getWorkspace().getRoot().getProject("B");
		createInWorkspace(new IProject[] { projectA, projectB });
		updateProjectDescription(projectA).addingCommand(EmptyDeltaBuilder.BUILDER_NAME).withTestBuilderId("a")
				.apply();
		// creates the builder; the clean build makes the next auto-build a full build
		projectA.build(IncrementalProjectBuilder.CLEAN_BUILD, createTestMonitor());
	}

	@AfterEach
	public void tearDown() throws Exception {
		releaseBuilder.countDown();
		System.clearProperty(PROP_PROJECT_RULE_BUILDERS);
		Job.getJobManager().join(ResourcesPlugin.FAMILY_AUTO_BUILD, null);
		EmptyDeltaBuilder builder = EmptyDeltaBuilder.getInstance();
		if (builder != null) {
			builder.reset();
		}
	}

	/**
	 * A builder with a project rule. It blocks until the test releases it, and
	 * records the rule that it runs with.
	 */
	private BuilderRuleCallback blockingBuilder(ISchedulingRule rule, AtomicReference<ISchedulingRule> ruleInBuild) {
		return new BuilderRuleCallback() {
			@Override
			public ISchedulingRule getRule(String name, IncrementalProjectBuilder projectBuilder, int trigger,
					Map<String, String> args) {
				return rule;
			}

			@Override
			public IProject[] build(int kind, Map<String, String> args, IProgressMonitor monitor) throws CoreException {
				ruleInBuild.set(Job.getJobManager().currentRule());
				builderStarted.countDown();
				try {
					releaseBuilder.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return super.build(kind, args, monitor);
			}
		};
	}

	/** Creates the file in another thread and returns the latch that ends when it is done. */
	private CountDownLatch createFileInOtherThread(IFile file, AtomicReference<Throwable> failure) {
		CountDownLatch done = new CountDownLatch(1);
		Thread thread = new Thread(() -> {
			try {
				file.create(new ByteArrayInputStream(new byte[0]), true, null);
			} catch (Throwable e) {
				failure.set(e);
			} finally {
				done.countDown();
			}
		}, "create " + file.getFullPath());
		thread.start();
		return done;
	}

	/** Turns on auto-build and waits until the builder runs. Does not wait for the build to end. */
	private void startAutoBuild() throws Exception {
		IWorkspaceDescription description = getWorkspace().getDescription();
		description.setAutoBuilding(true);
		getWorkspace().setDescription(description);
		assertThat(builderStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("builder started").isTrue();
	}

	@Test
	public void testBuilderRunsWithItsOwnRule() throws Exception {
		AtomicReference<ISchedulingRule> ruleInBuild = new AtomicReference<>();
		EmptyDeltaBuilder.getInstance().setRuleCallback(blockingBuilder(projectA, ruleInBuild));
		startAutoBuild();
		assertThat(ruleInBuild.get()).isEqualTo(projectA);
	}

	@Test
	public void testOperationInOtherProjectDoesNotWait() throws Exception {
		EmptyDeltaBuilder.getInstance().setRuleCallback(blockingBuilder(projectA, new AtomicReference<>()));
		startAutoBuild();

		AtomicReference<Throwable> failure = new AtomicReference<>();
		CountDownLatch done = createFileInOtherThread(projectB.getFile("b.txt"), failure);
		assertThat(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("file in B created while A builds").isTrue();
		assertThat(failure.get()).isNull();
		assertThat(releaseBuilder.getCount()).as("builder still runs").isEqualTo(1);
	}

	@Test
	public void testRootBuilderBlocksOtherProjects() throws Exception {
		EmptyDeltaBuilder.getInstance()
				.setRuleCallback(blockingBuilder(getWorkspace().getRoot(), new AtomicReference<>()));
		startAutoBuild();

		AtomicReference<Throwable> failure = new AtomicReference<>();
		CountDownLatch done = createFileInOtherThread(projectB.getFile("b.txt"), failure);
		assertThat(done.await(500, TimeUnit.MILLISECONDS)).as("file in B waits for a root builder").isFalse();
		releaseBuilder.countDown();
		assertThat(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("file in B created after the build").isTrue();
		assertThat(failure.get()).isNull();
	}

	@Test
	public void testProjectRuleBuildersProperty() throws Exception {
		System.setProperty(PROP_PROJECT_RULE_BUILDERS, "some.other.builder, " + EmptyDeltaBuilder.BUILDER_NAME);
		AtomicReference<ISchedulingRule> ruleInBuild = new AtomicReference<>();
		EmptyDeltaBuilder.getInstance().setRuleCallback(blockingBuilder(getWorkspace().getRoot(), ruleInBuild));
		startAutoBuild();
		assertThat(ruleInBuild.get()).isEqualTo(projectA);

		AtomicReference<Throwable> failure = new AtomicReference<>();
		CountDownLatch done = createFileInOtherThread(projectB.getFile("b.txt"), failure);
		assertThat(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("file in B created while A builds").isTrue();
		assertThat(failure.get()).isNull();
	}

	/** Starts a manual build of project A in a job without a rule, like a launch or the Clean dialog. */
	private Job startManualBuild() throws Exception {
		Job job = Job.create("build A", monitor -> {
			projectA.build(IncrementalProjectBuilder.INCREMENTAL_BUILD, monitor);
		});
		job.schedule();
		assertThat(builderStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("builder started").isTrue();
		return job;
	}

	/** Runs the action in another thread and returns the latch that ends when it is done. */
	private CountDownLatch runInOtherThread(String name, ICoreRunnable action, AtomicReference<Throwable> failure) {
		CountDownLatch done = new CountDownLatch(1);
		Thread thread = new Thread(() -> {
			try {
				action.run(null);
			} catch (Throwable e) {
				failure.set(e);
			} finally {
				done.countDown();
			}
		}, name);
		thread.start();
		return done;
	}

	@Test
	public void testManualBuildRunsBuilderWithItsOwnRule() throws Exception {
		AtomicReference<ISchedulingRule> ruleInBuild = new AtomicReference<>();
		EmptyDeltaBuilder.getInstance().setRuleCallback(blockingBuilder(projectA, ruleInBuild));
		Job job = startManualBuild();
		assertThat(ruleInBuild.get()).isEqualTo(projectA);
		releaseBuilder.countDown();
		job.join();
	}

	@Test
	public void testManualBuildDoesNotBlockOtherProject() throws Exception {
		EmptyDeltaBuilder.getInstance().setRuleCallback(blockingBuilder(projectA, new AtomicReference<>()));
		Job job = startManualBuild();

		AtomicReference<Throwable> failure = new AtomicReference<>();
		CountDownLatch created = createFileInOtherThread(projectB.getFile("b.txt"), failure);
		assertThat(created.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("file in B created while A builds").isTrue();
		// B has nothing to build, so its build must not wait for the build of A
		CountDownLatch built = runInOtherThread("build B",
				monitor -> projectB.build(IncrementalProjectBuilder.INCREMENTAL_BUILD, monitor), failure);
		assertThat(built.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("build of B ended while A builds").isTrue();
		assertThat(failure.get()).isNull();
		assertThat(releaseBuilder.getCount()).as("builder still runs").isEqualTo(1);
		releaseBuilder.countDown();
		job.join();
	}

	@Test
	public void testManualBuildWithRootBuilderBlocksOtherProject() throws Exception {
		EmptyDeltaBuilder.getInstance()
				.setRuleCallback(blockingBuilder(getWorkspace().getRoot(), new AtomicReference<>()));
		Job job = startManualBuild();

		AtomicReference<Throwable> failure = new AtomicReference<>();
		CountDownLatch done = createFileInOtherThread(projectB.getFile("b.txt"), failure);
		assertThat(done.await(500, TimeUnit.MILLISECONDS)).as("file in B waits for a root builder").isFalse();
		releaseBuilder.countDown();
		assertThat(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("file in B created after the build").isTrue();
		assertThat(failure.get()).isNull();
		job.join();
	}

	/**
	 * A builder in the project-rule list that needs a larger rule fails once. Then
	 * it gets its own rule again, and its next build is a full build.
	 */
	@Test
	public void testProjectRuleBuilderThatNeedsRootFallsBack() throws Exception {
		System.setProperty(PROP_PROJECT_RULE_BUILDERS, EmptyDeltaBuilder.BUILDER_NAME + ",fallback.test");
		AtomicInteger builds = new AtomicInteger();
		AtomicReference<ISchedulingRule> ruleInSecondBuild = new AtomicReference<>();
		AtomicInteger secondBuildKind = new AtomicInteger();
		EmptyDeltaBuilder.getInstance().setRuleCallback(new BuilderRuleCallback() {
			@Override
			public ISchedulingRule getRule(String name, IncrementalProjectBuilder projectBuilder, int trigger,
					Map<String, String> args) {
				return getWorkspace().getRoot();
			}

			@Override
			public IProject[] build(int kind, Map<String, String> args, IProgressMonitor monitor) throws CoreException {
				if (builds.incrementAndGet() == 1) {
					// like a builder that refreshes something with the workspace root
					getWorkspace().run(m -> {
					}, getWorkspace().getRoot(), 0, null);
				} else {
					ruleInSecondBuild.set(Job.getJobManager().currentRule());
					secondBuildKind.set(kind);
				}
				return super.build(kind, args, monitor);
			}
		});
		try {
			projectA.build(IncrementalProjectBuilder.FULL_BUILD, createTestMonitor());
		} catch (CoreException expected) {
			// the first build fails with the rule conflict
		}
		projectA.getFile("a.txt").create(new ByteArrayInputStream(new byte[0]), true, null);
		projectA.build(IncrementalProjectBuilder.INCREMENTAL_BUILD, createTestMonitor());

		assertThat(builds.get()).isEqualTo(2);
		assertThat(ruleInSecondBuild.get()).as("the builder has its own rule again").isEqualTo(getWorkspace().getRoot());
		assertThat(secondBuildKind.get()).as("the build after the failure is a full build")
				.isEqualTo(IncrementalProjectBuilder.FULL_BUILD);
	}

	@Test
	public void testOperationInSameProjectInterruptsBuilder() throws Exception {
		AtomicInteger builds = new AtomicInteger();
		AtomicBoolean interrupted = new AtomicBoolean();
		AtomicBoolean deltaHasFile = new AtomicBoolean();
		IFile file = projectA.getFile("a.txt");
		EmptyDeltaBuilder.getInstance().setRuleCallback(new BuilderRuleCallback() {
			@Override
			public ISchedulingRule getRule(String name, IncrementalProjectBuilder projectBuilder, int trigger,
					Map<String, String> args) {
				return projectA;
			}

			@Override
			public IProject[] build(int kind, Map<String, String> args, IProgressMonitor monitor) throws CoreException {
				if (builds.incrementAndGet() == 1) {
					builderStarted.countDown();
					long end = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS);
					while (!isInterrupted() && System.currentTimeMillis() < end) {
						try {
							Thread.sleep(10);
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
							break;
						}
					}
					interrupted.set(isInterrupted());
				} else {
					IResourceDelta delta = getDelta(projectA);
					if (delta != null && delta.findMember(file.getProjectRelativePath()) != null) {
						deltaHasFile.set(true);
					}
				}
				return super.build(kind, args, monitor);
			}
		});
		startAutoBuild();

		AtomicReference<Throwable> failure = new AtomicReference<>();
		CountDownLatch done = createFileInOtherThread(file, failure);
		assertThat(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("file in A created").isTrue();
		assertThat(failure.get()).isNull();
		assertThat(interrupted.get()).as("builder of A was interrupted").isTrue();

		Job.getJobManager().join(ResourcesPlugin.FAMILY_AUTO_BUILD, null);
		assertThat(builds.get()).as("A was built again").isGreaterThanOrEqualTo(2);
		assertThat(deltaHasFile.get()).as("the next build of A sees the new file").isTrue();
	}
}
