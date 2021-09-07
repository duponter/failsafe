/*
 * Copyright 2018 the original author or authors.
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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Handles execution and execution results according to a policy. May contain pre and post execution behaviors. Each
 * PolicyExecutor makes its own determination about whether an execution result is a success or failure.
 * <p>
 * Part of the Failsafe SPI.
 *
 * @param <R> result type
 * @param <P> policy type
 */
public abstract class PolicyExecutor<R, P extends Policy<R>> {
  protected final P policy;
  protected final AbstractExecution<R> execution;
  // Index of the policy relative to other policies in a composition, inner-most first
  int policyIndex;

  /**
   * The type of async execution request, either executing or recording.
   */
  protected static class ExecutionRequest {
    private final boolean executing;

    private ExecutionRequest(boolean executing) {
      this.executing = executing;
    }

    static ExecutionRequest executing() {
      return new ExecutionRequest(true);
    }

    static ExecutionRequest recording() {
      return new ExecutionRequest(false);
    }

    /**
     * Returns whether the request is meant to perform an execution and handle any failures.
     */
    public boolean isExecuting() {
      return executing;
    }

    /**
     * Returns whether the request is meant to record an asynchronous failure rather than perform execution handling.
     */
    public boolean isRecording() {
      return !executing;
    }

    @Override
    public String toString() {
      return "[executing=" + isExecuting() + ", recording=" + isRecording() + "]";
    }
  }

  protected PolicyExecutor(P policy, AbstractExecution<R> execution) {
    this.policy = policy;
    this.execution = execution;
  }

  /**
   * Called before execution to return an alternative result or failure such as if execution is not allowed or needed.
   * Should return the provided {@code result} else some alternative.
   */
  protected ExecutionResult preExecute() {
    return null;
  }

  /**
   * Performs an execution by calling pre-execute else calling the supplier and doing a post-execute.
   */
  protected Supplier<ExecutionResult> supply(Supplier<ExecutionResult> innerSupplier, Scheduler scheduler) {
    return () -> {
      ExecutionResult result = preExecute();
      if (result != null) {
        // Still need to preExecute when returning an alternative result before making it to the terminal Supplier
        execution.preExecute();
        return result;
      }

      return postExecute(innerSupplier.get());
    };
  }

  /**
   * Performs synchronous post-execution handling for a {@code result}.
   */
  protected ExecutionResult postExecute(ExecutionResult result) {
    execution.recordAttempt();
    if (isFailure(result)) {
      result = onFailure(result.withFailure());
      callFailureListener(result);
    } else {
      result = result.withSuccess();
      onSuccess(result);
      callSuccessListener(result);
    }

    return result;
  }

  /**
   * Performs an async execution by calling pre-execute else calling the supplier and doing a post-execute. Implementors
   * must handle a null result from a supplier, which indicates that an async execution has occurred, a result will come
   * later, and postExecute handling should not be performed.
   */
  protected Function<ExecutionRequest, CompletableFuture<ExecutionResult>> applyAsync(
    Function<ExecutionRequest, CompletableFuture<ExecutionResult>> innerFn, Scheduler scheduler,
    FailsafeFuture<R> future) {

    return request -> {
      ExecutionResult result = preExecute();
      if (result != null) {
        // Still need to preExecute when returning an alternative result before making it to the terminal Supplier
        execution.preExecute();
        return CompletableFuture.completedFuture(result);
      }

      return innerFn.apply(request).thenCompose(r -> {
        Assert.log(getClass().getSimpleName() + " applyAsync almost done");
        return r == null ? ExecutionResult.NULL_FUTURE : postExecuteAsync(r, scheduler, future);
      });
    };
  }

  /**
   * Performs potentially asynchronous post-execution handling for a {@code result}.
   */
  protected CompletableFuture<ExecutionResult> postExecuteAsync(ExecutionResult result, Scheduler scheduler,
    FailsafeFuture<R> future) {
    execution.recordAttempt();
    if (isFailure(result)) {
      return onFailureAsync(result.withFailure(), scheduler, future).whenComplete((postResult, error) -> {
        callFailureListener(postResult);
      });
    } else {
      result = result.withSuccess();
      onSuccess(result);
      callSuccessListener(result);
      return CompletableFuture.completedFuture(result);
    }
  }

  /**
   * Returns whether the {@code result} is a success according to the policy. If the {code result} has no result, it is
   * not a failure.
   */
  @SuppressWarnings("rawtypes")
  protected boolean isFailure(ExecutionResult result) {
    if (result.isNonResult())
      return false;
    else if (policy instanceof FailurePolicy)
      return ((FailurePolicy) policy).isFailure(result);
    else
      return result.getFailure() != null;
  }

  /**
   * Performs post-execution handling for a {@code result} that is considered a success according to {@link
   * #isFailure(ExecutionResult)}.
   */
  protected void onSuccess(ExecutionResult result) {
  }

  /**
   * Performs post-execution handling for a {@code result} that is considered a failure according to {@link
   * #isFailure(ExecutionResult)}, possibly creating a new result, else returning the original {@code result}.
   */
  protected ExecutionResult onFailure(ExecutionResult result) {
    return result;
  }

  /**
   * Performs potentially asynchrononus post-execution handling for a failed {@code result}, possibly creating a new
   * result, else returning the original {@code result}.
   */
  protected CompletableFuture<ExecutionResult> onFailureAsync(ExecutionResult result, Scheduler scheduler,
    FailsafeFuture<R> future) {
    Assert.log("PolicyExecutor onFailureAsync");
    return CompletableFuture.completedFuture(onFailure(result));
  }

  /**
   * Returns whether execution has been cancelled for this policy by an outer policy. When an execution is cancelled,
   * postExecute should not be called.
   */
  boolean executionCancelled() {
    return execution.cancelledIndex > policyIndex;
  }

  /**
   * Propagates cancellation from the {@code sourceFuture} to the {@code targetFuture} and completes the {@code promise}
   * with the {@code sourceFuture}'s result.
   */
  void propagateCancellation(FailsafeFuture<R> sourceFuture, Future<?> targetFuture,
    CompletableFuture<ExecutionResult> promise) {
    sourceFuture.injectCancelFn((mayInterrupt, promiseResult) -> {
      targetFuture.cancel(mayInterrupt);
      if (executionCancelled())
        promise.complete(promiseResult);
    });
  }

  @SuppressWarnings("rawtypes")
  private void callSuccessListener(ExecutionResult result) {
    if (result.isComplete() && policy instanceof PolicyListeners) {
      PolicyListeners policyListeners = (PolicyListeners) policy;
      if (policyListeners.successListener != null)
        policyListeners.successListener.handle(result, execution);
    }
  }

  @SuppressWarnings("rawtypes")
  private void callFailureListener(ExecutionResult result) {
    if (result.isComplete() && policy instanceof PolicyListeners) {
      PolicyListeners policyListeners = (PolicyListeners) policy;
      if (policyListeners.failureListener != null)
        policyListeners.failureListener.handle(result, execution);
    }
  }
}
