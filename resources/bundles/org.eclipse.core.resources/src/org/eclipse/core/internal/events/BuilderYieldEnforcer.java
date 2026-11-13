/*******************************************************************************
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.core.internal.events;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.eclipse.core.internal.utils.Policy;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;

/**
 * Makes an auto-build give up the workspace when another operation waits for it.
 * <p>
 * An auto-build holds a scheduling rule on the whole workspace, so a save, an editor opening a
 * file, or any other workspace change waits until the build ends. The platform asks the build to
 * stop as soon as a conflicting operation arrives, see
 * {@link org.eclipse.core.internal.resources.Workspace#prepareOperation}, but that request is only
 * advisory: a builder that never checks it runs to its end and the user waits for all of it.
 * </p>
 * <p>
 * This class escalates the request while one builder runs:
 * </p>
 * <ol>
 * <li>after {@link #MILLIS_BEFORE_CANCEL}, cancel the build's progress monitor, which every builder
 * that checks its monitor already honours;</li>
 * <li>after {@link #MILLIS_BEFORE_INTERRUPT}, interrupt the build thread, which ends a builder that
 * blocks in a wait, a sleep, or an interruptible read.</li>
 * </ol>
 * <p>
 * JDT does the same for its own builder, with the same two steps and the same defaults, see
 * <code>org.eclipse.jdt.internal.core.builder.BuildNotifier</code>. Doing it in the platform covers
 * every builder instead of only the ones that implement it.
 * </p>
 * <p>
 * A forced yield leaves the builder unfinished, so the caller must keep the builder's last built
 * state. Otherwise {@link BuildManager} records the current tree as built and the skipped changes
 * never reach the next build. {@link #finish()} reports whether that happened.
 * </p>
 *
 * @see BuildManager
 */
final class BuilderYieldEnforcer {

	/**
	 * Milliseconds a conflicting operation waits before the build's progress monitor is canceled.
	 * Zero or less turns the step off.
	 */
	private static final int MILLIS_BEFORE_CANCEL = Integer
			.getInteger("org.eclipse.core.resources.millisecondsBeforeCancelAutoBuild", 1000).intValue(); //$NON-NLS-1$

	/**
	 * Milliseconds a conflicting operation waits before the build thread is interrupted. Zero or
	 * less turns the step off, which leaves only the monitor cancel.
	 */
	private static final int MILLIS_BEFORE_INTERRUPT = Integer
			.getInteger("org.eclipse.core.resources.millisecondsBeforeInterruptAutoBuild", 3000).intValue(); //$NON-NLS-1$

	/**
	 * Milliseconds a conflicting operation waits before the builder is reported to the log. Zero or
	 * less turns the report off.
	 */
	private static final int MILLIS_BEFORE_REPORT = Integer
			.getInteger("org.eclipse.core.resources.millisecondsBeforeReportSlowBuilder", 3000).intValue(); //$NON-NLS-1$

	private static final long POLL_MILLIS = 100;

	private static final ScheduledExecutorService WATCHER = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "Workspace build yield watcher"); //$NON-NLS-1$
		thread.setDaemon(true);
		return thread;
	});

	private final Thread buildThread = Thread.currentThread();

	private final IProgressMonitor monitor;

	private final String builderLabel;

	private final ScheduledFuture<?> task;

	private final Object lock = new Object();

	// the fields below are guarded by lock
	private long waitingSince = -1;

	private boolean monitorCanceled;

	private boolean threadInterrupted;

	private boolean reported;

	/**
	 * Starts watching the builder that runs on the current thread.
	 *
	 * @param builderLabel the builder and project to name in the log
	 * @param monitor the build's progress monitor
	 * @param yieldRequested tells whether another operation waits for the build
	 * @return the watcher, or <code>null</code> if every step is turned off
	 */
	static BuilderYieldEnforcer start(String builderLabel, IProgressMonitor monitor, BooleanSupplier yieldRequested) {
		if (MILLIS_BEFORE_CANCEL <= 0 && MILLIS_BEFORE_INTERRUPT <= 0 && MILLIS_BEFORE_REPORT <= 0) {
			return null;
		}
		return new BuilderYieldEnforcer(builderLabel, monitor, yieldRequested);
	}

	private BuilderYieldEnforcer(String builderLabel, IProgressMonitor monitor, BooleanSupplier yieldRequested) {
		this.builderLabel = builderLabel;
		this.monitor = monitor;
		this.task = WATCHER.scheduleWithFixedDelay(() -> check(yieldRequested), POLL_MILLIS, POLL_MILLIS,
				TimeUnit.MILLISECONDS);
	}

	private void check(BooleanSupplier yieldRequested) {
		boolean requested;
		try {
			requested = yieldRequested.getAsBoolean();
		} catch (RuntimeException e) {
			// never let the watcher thread die on a broken supplier
			Policy.log(IStatus.WARNING, "Could not read the build yield request", e); //$NON-NLS-1$
			return;
		}
		synchronized (lock) {
			if (!requested) {
				// the waiting operation went away, so start the grace period again
				waitingSince = -1;
				return;
			}
			long now = System.currentTimeMillis();
			if (waitingSince < 0) {
				waitingSince = now;
			}
			long waiting = now - waitingSince;
			if (!monitorCanceled && MILLIS_BEFORE_CANCEL > 0 && waiting >= MILLIS_BEFORE_CANCEL) {
				monitorCanceled = true;
				monitor.setCanceled(true);
				if (Policy.DEBUG_BUILD_INTERRUPT) {
					Policy.debug("Canceled " + builderLabel + " after " + waiting + "ms of waiting"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				}
			}
			if (!reported && MILLIS_BEFORE_REPORT > 0 && waiting >= MILLIS_BEFORE_REPORT) {
				reported = true;
				Policy.log(IStatus.WARNING, "A user operation waited " + waiting //$NON-NLS-1$
						+ "ms for " + builderLabel //$NON-NLS-1$
						+ ". The builder does not give up the workspace when asked to stop.", null); //$NON-NLS-1$
			}
			if (!threadInterrupted && MILLIS_BEFORE_INTERRUPT > 0 && waiting >= MILLIS_BEFORE_INTERRUPT) {
				threadInterrupted = true;
				buildThread.interrupt();
				if (Policy.DEBUG_BUILD_INTERRUPT) {
					Policy.debug("Interrupted the build thread for " + builderLabel); //$NON-NLS-1$
				}
			}
		}
	}

	/**
	 * Stops watching. Clears a pending interrupt of the build thread, so that the rest of the build
	 * does not see it. Call this from the build thread.
	 *
	 * @return <code>true</code> if the platform forced the builder to stop, in which case the
	 *         caller must keep the builder's last built state
	 */
	boolean finish() {
		synchronized (lock) {
			task.cancel(false);
			if (threadInterrupted) {
				// the interrupt was for this builder only
				Thread.interrupted();
			}
			return monitorCanceled || threadInterrupted;
		}
	}
}
