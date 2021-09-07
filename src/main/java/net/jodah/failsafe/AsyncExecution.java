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

import net.jodah.failsafe.Functions.SettableSupplier;
import net.jodah.failsafe.PolicyExecutor.ExecutionRequest;
import net.jodah.failsafe.internal.util.Assert;
import net.jodah.failsafe.util.concurrent.Scheduler;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

/**
 * Tracks asynchronous executions and allows retries to be scheduled according to a {@link RetryPolicy}. May be
 * explicitly completed or made to retry.
 *
 * @param <R> result type
 * @author Jonathan Halterman
 */
public final class AsyncExecution<R> extends AbstractExecution<R> {
  // An inner-most supplier that allows recorded results to be asynchronously set
  private SettableSupplier<ExecutionRequest, CompletableFuture<ExecutionResult>> innerExecutionSupplier;
  private Function<ExecutionRequest, CompletableFuture<ExecutionResult>> outerExecutionSupplier;
  final FailsafeFuture<R> future;
  private volatile boolean recordCalled;

  AsyncExecution(Scheduler scheduler, FailsafeFuture<R> future, FailsafeExecutor<R> executor) {
    super(scheduler, executor);
    this.future = future;
  }

  void inject(Function<ExecutionRequest, CompletableFuture<ExecutionResult>> syncFn, boolean asyncExecution) {
    if (asyncExecution)
      outerExecutionSupplier = innerExecutionSupplier = Functions.toSettableSupplier(syncFn);
    else
      outerExecutionSupplier = syncFn;
    outerExecutionSupplier = Functions.getPromiseAsync(outerExecutionSupplier, scheduler, this);

    for (PolicyExecutor<R, Policy<R>> policyExecutor : policyExecutors)
      outerExecutionSupplier = policyExecutor.applyAsync(outerExecutionSupplier, scheduler, this.future);
  }

  /**
   * Completes the execution and the associated {@code CompletableFuture}.
   *
   * @throws IllegalStateException if the most recent execution was already recorded or the execution is complete
   */
  public void complete() {
    try {
      Assert.state(!recordCalled, "The most recent execution has already been recorded");
    } catch (Exception e) {
      e.printStackTrace();
      throw e;
    }
    recordCalled = true;
    Assert.log(this, "complete called attemptRecorded:" + attemptRecorded);

    // Guard against race with a timeout expiring
    synchronized (future) {
      ExecutionResult result;
      if (!attemptRecorded) {
        result = ExecutionResult.NONE;
        super.record(result);
      } else
        result = new ExecutionResult(lastResult, lastFailure, true);
      complete(super.postExecute(result), null);
    }
  }

  @Override
  void preExecute() {
    super.preExecute();
    recordCalled = false;
  }

  /**
   * Records an execution {@code result} or {@code failure} which triggers failure handling, if needed, by the
   * configured policies. If policy handling is not possible or already complete, the resulting {@link
   * CompletableFuture} is completed.
   *
   * @throws IllegalStateException if the most recent execution was already recorded or the execution is complete
   */
  public void record(R result, Throwable failure) {
    //Assert.state(!recordCalled, "The most recent execution has already been recorded");
    recordCalled = true;

    // Guard against race with a timeout expiring
    synchronized (future) {
      if (!attemptRecorded) {
        Assert.log("AsyncExecution recording result " + result);
        ExecutionResult er = new ExecutionResult(result, failure, true).withWaitNanos(waitNanos);
        record(er);
        innerExecutionSupplier.set(CompletableFuture.completedFuture(er));
      } else {
        Assert.log("AsyncExecution not recording result since already recorded");
      }

      // Proceed with handling a previously recorded result
      outerExecutionSupplier.apply(ExecutionRequest.recording()).whenComplete(this::complete);
    }
  }

  @Override
  void record(ExecutionResult result, boolean timeout) {
    // Guard against race with the execution completing
    synchronized (future) {
      if (!attemptRecorded) {
        super.record(result, timeout);
        if (isAsyncExecution()) {
          //Assert.log("AsyncExecution Setting async recorded result " + result+ " attemptRecorded:"+attemptRecorded + " inProgress:"+inProgress);
          innerExecutionSupplier.set(CompletableFuture.completedFuture(result));
        }
      }
    }
  }

  /**
   * Records an execution {@code result} which triggers failure handling, if needed, by the configured policies. If
   * policy handling is not possible or already complete, the resulting {@link CompletableFuture} is completed.
   *
   * @throws IllegalStateException if the most recent execution was already recorded or the execution is complete
   */
  public void recordResult(R result) {
    record(result, null);
  }

  /**
   * Records an execution {@code failure} which triggers failure handling, if needed, by the configured policies. If
   * policy handling is not possible or already complete, the resulting {@link CompletableFuture} is completed.
   *
   * @throws IllegalStateException if the most recent execution was already recorded or the execution is complete
   */
  public void recordFailure(Throwable failure) {
    record(null, failure);
  }

  @Override
  boolean isAsyncExecution() {
    return innerExecutionSupplier != null;
  }

  /**
   * Performs an asynchronous execution.
   */
  void executeAsync() {
    outerExecutionSupplier.apply(ExecutionRequest.executing()).whenComplete(this::complete);
  }

  private void complete(ExecutionResult result, Throwable error) {
    if (result == null && error == null)
      return;

    completed = true;
    if (!future.isDone()) {
      if (result != null)
        future.completeResult(result);
      else {
        if (error instanceof CompletionException)
          error = error.getCause();
        future.completeResult(ExecutionResult.failure(error));
      }
    }
  }
}
