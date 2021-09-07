/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */
package net.jodah.failsafe;

import net.jodah.failsafe.internal.util.Assert;
import net.jodah.failsafe.util.concurrent.Scheduler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;

/**
 * Common execution information.
 *
 * @param <R> result type
 * @author Jonathan Halterman
 */
public abstract class AbstractExecution<R> extends ExecutionContext<R> {
  final Scheduler scheduler;
  final FailsafeExecutor<R> executor;
  final List<PolicyExecutor<R, Policy<R>>> policyExecutors;

  // Internally mutable state
  /* Whether the inner supplier is in progress */
  volatile boolean inProgress;
  /* Whether the execution attempt has been recorded */
  volatile boolean attemptRecorded;
  /* Whether the execution can be interrupted */
  volatile boolean canInterrupt;
  /* Whether the execution has been internally interrupted */
  volatile boolean interrupted;
  /* The wait time in nanoseconds. */
  volatile long waitNanos;
  /* Whether the execution has been completed */
  volatile boolean completed;

  /**
   * Creates a new AbstractExecution for the {@code executor}.
   */
  AbstractExecution(Scheduler scheduler, FailsafeExecutor<R> executor) {
    this.scheduler = scheduler;
    this.executor = executor;
    policyExecutors = new ArrayList<>(executor.policies.size());
    ListIterator<Policy<R>> policyIterator = executor.policies.listIterator(executor.policies.size());
    for (int i = 1; policyIterator.hasPrevious(); i++) {
      PolicyExecutor<R, Policy<R>> policyExecutor = policyIterator.previous().toExecutor(this);
      policyExecutor.policyIndex = i;
      policyExecutors.add(policyExecutor);
    }
  }

  /**
   * Records an execution attempt so long as the execution has not already been completed or interrupted. In the case of
   * interruption, an execution may have already been recorded, but the result will be re-recorded by the interrupting
   * thread.
   *
   * @throws IllegalStateException if the execution is already complete
   */
  void record(ExecutionResult result) {
    record(result, false);
  }

  @SuppressWarnings("unchecked")
  void record(ExecutionResult result, boolean timeout) {
    Assert.state(!completed, "Execution has already been completed");
    if (!interrupted) {
      //Assert.log("AbstractExecution recording "+result + " attemptRecorded:"+attemptRecorded + " inProgress:"+inProgress)  ;

      recordAttempt();
      if (inProgress) {
        Assert.log(this, "recording result with inProgress="+inProgress+", attemptRecorded="+attemptRecorded);
        lastResult = (R) result.getResult();
        lastFailure = result.getFailure();
        executions.incrementAndGet();
        if (!timeout)
          inProgress = false;
      }
    }
    Assert.log(this, "attempts="+attempts.get()+ ", executions="+executions.get());
  }

  /**
   * Records an execution attempt which may correspond with an execution result. Async executions will have results
   * recorded separately.
   */
  void recordAttempt() {
    if (!attemptRecorded) {
      attempts.incrementAndGet();
      attemptRecorded = true;
      Assert.log(this, "setting attemptRecorded="+attemptRecorded);
    }
  }

  synchronized void preExecute() {
    attemptStartTime = Duration.ofNanos(System.nanoTime());
    if (startTime == Duration.ZERO)
      startTime = attemptStartTime;
    inProgress = true;
  //  new Exception().printStackTrace();
    Assert.log(this, "preExecute setting inProgress=true, attemptRecorded=false");
    attemptRecorded = false;
    cancelledIndex = 0;
    canInterrupt = true;
    interrupted = false;
  }

  boolean isAsyncExecution() {
    return false;
  }

  /**
   * Externally called. Records an execution and performs post-execution handling for the {@code result} against all
   * configured policy executors. Returns whether the result is complete for all policies.
   *
   * @throws IllegalStateException if the execution is already complete
   */
  synchronized ExecutionResult postExecute(ExecutionResult result) {
    record(result);
    boolean allComplete = true;
    for (PolicyExecutor<R, Policy<R>> policyExecutor : policyExecutors) {
      result = policyExecutor.postExecute(result);
      allComplete = allComplete && result.isComplete();
    }

    waitNanos = result.getWaitNanos();
    completed = allComplete;
    return result;
  }

  /**
   * Returns the time to wait before the next execution attempt. Returns {@code 0} if an execution has not yet
   * occurred.
   */
  public Duration getWaitTime() {
    return Duration.ofNanos(waitNanos);
  }

  /**
   * Returns whether the execution is complete or if it can be retried. An execution is considered complete only when
   * all configured policies consider the execution complete.
   */
  public boolean isComplete() {
    return completed;
  }
}
