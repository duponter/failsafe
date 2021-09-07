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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A PolicyExecutor that handles failures according to a {@link Timeout}.
 * <p>
 * Timeouts are scheduled to occur in a separate thread. When cancelled, the Timeout has no bearing on the execution
 * result, which will be set by a separate Supplier.
 *
 * @param <R> result type
 */
class TimeoutExecutor<R> extends PolicyExecutor<R, Timeout<R>> {
  TimeoutExecutor(Timeout<R> timeout, AbstractExecution<R> execution) {
    super(timeout, execution);
  }

  @Override
  protected boolean isFailure(ExecutionResult result) {
    return !result.isNonResult() && result.getFailure() instanceof TimeoutExceededException;
  }

  /**
   * Schedules a separate timeout call that fails with {@link TimeoutExceededException} if the policy's timeout is
   * exceeded.
   */
  @Override
  protected Supplier<ExecutionResult> supply(Supplier<ExecutionResult> innerSupplier, Scheduler scheduler) {
    return () -> {
      // Coordinates a result between the timeout and execution threads
      AtomicReference<ExecutionResult> result = new AtomicReference<>();
      Future<?> timeoutFuture;
      Thread executionThread = Thread.currentThread();

      try {
        // Schedule timeout check
        timeoutFuture = Scheduler.DEFAULT.schedule(() -> {
          // Guard against race with execution completion
          if (result.compareAndSet(null, ExecutionResult.failure(new TimeoutExceededException(policy)))) {
            // Cancel and interrupt
            execution.cancelledIndex = policyIndex;
            if (policy.canInterrupt()) {
              // Guard against race with the execution completing
              synchronized (execution) {
                if (execution.canInterrupt) {
                  execution.record(result.get(), true);
                  execution.interrupted = true;
                  executionThread.interrupt();
                }
              }
            }
          }
          return null;
        }, policy.getTimeout().toNanos(), TimeUnit.NANOSECONDS);
      } catch (Throwable t) {
        // Hard scheduling failure
        return postExecute(ExecutionResult.failure(t));
      }

      // Propagate execution, cancel timeout future if not done, and postExecute result
      if (result.compareAndSet(null, innerSupplier.get()))
        timeoutFuture.cancel(false);
      return postExecute(result.get());
    };
  }

  /**
   * Schedules a separate timeout call that blocks and fails with {@link TimeoutExceededException} if the policy's
   * timeout is exceeded.
   */
  @Override
  @SuppressWarnings("unchecked")
  protected Function<ExecutionRequest, CompletableFuture<ExecutionResult>> applyAsync(
    Function<ExecutionRequest, CompletableFuture<ExecutionResult>> innerFn, Scheduler scheduler,
    FailsafeFuture<R> future) {

    return request -> {
      // Coordinates a race between the timeout and execution threads
      AtomicReference<ExecutionResult> resultRef = new AtomicReference<>();
      AtomicReference<Future<R>> timeoutFutureRef = new AtomicReference<>();
      CompletableFuture<ExecutionResult> promise = new CompletableFuture<>();

      /*
      This implementation sets up a race between a timeout being triggered and the execution completing. Whichever
      completes first will complete the promise. For async executions, another timeout can only be scheduled after the
      previous timeout's future isDone.
      */

      // Guard against race with AsyncExecution.record, AsyncExecution.complete, future.complete or future.cancel
      synchronized (future) {
        if (!future.isDone()) {
          Future<R> timeoutFuture;
          boolean canScheduleTimeout = false;

          // Clear any pending timeout
          if (request.isExecuting()) {
            canScheduleTimeout = true;
            timeoutFuture = timeoutFutureRef.get();
            if (timeoutFuture != null) {
              timeoutFuture.cancel(false);
              canScheduleTimeout = timeoutFuture.isDone();
            }
          }

          // Schedule timeout if one is not already pending
          if (canScheduleTimeout) {
            try {
              timeoutFuture = (Future<R>) Scheduler.DEFAULT.schedule(() -> {
                // Guard against race with execution completion
                ExecutionResult cancelResult = ExecutionResult.failure(new TimeoutExceededException(policy));
                if (resultRef.compareAndSet(null, cancelResult)) {
                  Assert.log(TimeoutExecutor.this.getClass(), "timeout triggered before result");
                  execution.record(cancelResult, true);

                  // Cancel and interrupt
                  execution.cancelledIndex = policyIndex;
                  future.cancelDependencies(policy.canInterrupt(), cancelResult);
                }

                return null;
              }, policy.getTimeout().toNanos(), TimeUnit.NANOSECONDS);
              timeoutFutureRef.set(timeoutFuture);
              future.injectTimeout(timeoutFuture);
            } catch (Throwable t) {
              // Hard scheduling failure
              promise.completeExceptionally(t);
              return promise;
            }
          }
        }
      }

      // Propagate execution, cancel timeout future if not done, and postExecute result
      innerFn.apply(request).whenComplete((result, error) -> {
        if (error != null) {
          promise.completeExceptionally(error);
          return;
        }

        if (!execution.isAsyncExecution() && !resultRef.compareAndSet(null, result)) {
          // Fetch timeout result
          result = resultRef.get();
        }

        // Cancel timeout task
        Future<R> timeoutFuture = timeoutFutureRef.get();
        if (timeoutFuture != null && !timeoutFuture.isDone())
          timeoutFuture.cancel(false);

        // Post-execute results that are not async
        if (!execution.isAsyncExecution() || (result != null && result.isAsyncRecorded()))
          postExecuteAsync(result, scheduler, future);

        promise.complete(result);
      });

      return promise;
    };
  }
}
