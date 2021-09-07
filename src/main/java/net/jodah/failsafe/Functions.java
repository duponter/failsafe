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

import net.jodah.failsafe.PolicyExecutor.ExecutionRequest;
import net.jodah.failsafe.function.*;
import net.jodah.failsafe.internal.util.Assert;
import net.jodah.failsafe.util.concurrent.Scheduler;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Utilities for creating and applying functions.
 *
 * @author Jonathan Halterman
 */
final class Functions {
  interface SettableSupplier<T, R> extends Function<T, R> {
    void set(R value);

    boolean isSet();
  }

  /**
   * Returns a Supplier that pre-executes the {@code execution}, applies the {@code supplier}, records the result
   * and returns the result. This implementation also handles Thread interrupts.
   *
   * @param <R> result type
   */
  static <R> Supplier<ExecutionResult> get(CheckedSupplier<R> supplier, AbstractExecution<R> execution) {
    return () -> {
      ExecutionResult result;
      Throwable throwable = null;
      try {
        execution.preExecute();
        result = ExecutionResult.success(supplier.get());
      } catch (Throwable t) {
        throwable = t;
        result = ExecutionResult.failure(t);
      } finally {
        // Guard against race with Timeout interruption
        synchronized (execution) {
          execution.canInterrupt = false;
          if (execution.interrupted)
            // Clear interrupt flag if interruption was intended
            Thread.interrupted();
          else if (throwable instanceof InterruptedException)
            // Set interrupt flag if interrupt occurred but was not intended
            Thread.currentThread().interrupt();
        }
      }

      execution.record(result);
      return result;
    };
  }

  /**
   * Returns a Function that synchronously pre-executes the {@code execution}, applies the {@code supplier}, records the
   * result and returns a promise containing the result.
   *
   * @param <R> result type
   */
  static <R> Function<ExecutionRequest, CompletableFuture<ExecutionResult>> getPromise(
    ContextualSupplier<R, R> supplier, AbstractExecution<R> execution) {

    Assert.notNull(supplier, "supplier");
    return request -> {
      ExecutionResult result;
      try {
        execution.preExecute();
        result = ExecutionResult.success(supplier.get(execution));
      } catch (Throwable t) {
        result = ExecutionResult.failure(t);
      }
      execution.record(result);
      return CompletableFuture.completedFuture(result);
    };
  }

  /**
   * Returns a Function that asynchronously applies the {@code innerFn} on the first call, synchronously on subsequent
   * calls, and returns a promise containing the result.
   *
   * @param <R> result type
   */
  static <R> Function<ExecutionRequest, CompletableFuture<ExecutionResult>> getPromiseAsync(
    Function<ExecutionRequest, CompletableFuture<ExecutionResult>> innerFn, Scheduler scheduler,
    AsyncExecution<R> execution) {

    AtomicBoolean scheduled = new AtomicBoolean();
    return request -> {
      if (scheduled.get() || innerFn instanceof SettableSupplier && ((SettableSupplier<?, ?>) innerFn).isSet()) {
        return innerFn.apply(request);
      } else {
        CompletableFuture<ExecutionResult> promise = new CompletableFuture<>();
        Callable<Object> callable = () -> innerFn.apply(request).whenComplete((result, error) -> {
          if (error != null)
            promise.completeExceptionally(error);
          else
            promise.complete(result);
        });

        try {
          scheduled.set(true);
          Future<?> scheduledSupply = scheduler.schedule(callable, 0, TimeUnit.NANOSECONDS);

          // Propagate cancellation to the scheduled innerFn and its promise
          execution.future.injectCancelFn((mayInterrupt, cancelResult) -> {
            scheduledSupply.cancel(mayInterrupt);

            // Cancel a pending promise if the execution is not yet running
            if (!execution.inProgress)
              promise.complete(cancelResult);
          });
        } catch (Throwable t) {
          promise.completeExceptionally(t);
        }
        return promise;
      }
    };
  }

  static AtomicInteger counter = new AtomicInteger(5000);

  /**
   * Returns a Function that synchronously pre-executes the {@code execution}, runs the {@code runnable}, and attempts
   * to complete the {@code execution} if a failure occurs. Locks to ensure the resulting supplier cannot be applied
   * multiple times concurrently.
   *
   * @param <R> result type
   */
  static <R> Function<ExecutionRequest, CompletableFuture<ExecutionResult>> getPromiseExecution(
    AsyncRunnable<R> runnable, AsyncExecution<R> execution) {

    Assert.notNull(runnable, "runnable");
    return new Function<ExecutionRequest, CompletableFuture<ExecutionResult>>() {
      @Override
      public synchronized CompletableFuture<ExecutionResult> apply(ExecutionRequest request) {
        int count = counter.incrementAndGet();

        try {
          Assert.log("Functions.getPromiseExecution executing " + count);
          execution.preExecute();
          runnable.run(execution);
        } catch (Throwable e) {
          execution.record(null, e);
        }

        // Result will be provided later via AsyncExecution.record
        Assert.log("Functions.getPromiseExecution returning null for " + count);
        return ExecutionResult.NULL_FUTURE;
      }
    };
  }

  /**
   * Returns a Function that synchronously pre-executes the {@code execution}, applies the {@code supplier},
   * records the result and returns a promise containing the result.
   *
   * @param <R> result type
   */
  @SuppressWarnings("unchecked")
  static <R> Function<ExecutionRequest, CompletableFuture<ExecutionResult>> getPromiseOfStage(
    ContextualSupplier<R, ? extends CompletionStage<? extends R>> supplier, AsyncExecution<R> execution) {

    Assert.notNull(supplier, "supplier");
    return request -> {
      CompletableFuture<ExecutionResult> promise = new CompletableFuture<>();
      try {
        execution.preExecute();
        CompletionStage<? extends R> stage = supplier.get(execution);
        if (stage instanceof Future)
          execution.future.injectStage((Future<R>) stage);
        stage.whenComplete((result, failure) -> {
          if (failure instanceof CompletionException)
            failure = failure.getCause();
          ExecutionResult r = failure == null ? ExecutionResult.success(result) : ExecutionResult.failure(failure);
          execution.record(r);
          promise.complete(r);
        });
      } catch (Throwable t) {
        ExecutionResult result = ExecutionResult.failure(t);
        execution.record(result);
        promise.complete(result);
      }
      return promise;
    };
  }

  /**
   * Returns a Function that synchronously pre-executes the {@code execution}, applies the {@code supplier}, and
   * attempts to complete the {@code execution} if a failure occurs. Locks to ensure the resulting supplier cannot
   * be applied multiple times concurrently.
   *
   * @param <R> result type
   */
  @SuppressWarnings({ "unchecked", "rawtypes" })
  static <R> Function<ExecutionRequest, CompletableFuture<ExecutionResult>> getPromiseOfStageExecution(
    AsyncSupplier<R, ? extends CompletionStage<? extends R>> supplier, AsyncExecution execution) {

    Assert.notNull(supplier, "supplier");
    Semaphore asyncFutureLock = new Semaphore(1);
    return request -> {
      try {
        execution.preExecute();
        asyncFutureLock.acquire();
        CompletionStage<? extends R> stage = supplier.get(execution);
        stage.whenComplete((innerResult, failure) -> {
          try {
            if (failure != null)
              execution.record(innerResult, failure instanceof CompletionException ? failure.getCause() : failure);
          } finally {
            asyncFutureLock.release();
          }
        });
      } catch (Throwable e) {
        try {
          execution.record(null, e);
        } finally {
          asyncFutureLock.release();
        }
      }

      // Result will be provided later via AsyncExecution.record
      return ExecutionResult.NULL_FUTURE;
    };
  }

  /**
   * Returns a SettableSupplier for async execution calls that supplies the set value once then uses the {@code
   * supplier} for subsequent calls.
   *
   * @param <R> result type
   */
  static <R> SettableSupplier<ExecutionRequest, CompletableFuture<ExecutionResult>> toSettableSupplier(
    Function<ExecutionRequest, CompletableFuture<ExecutionResult>> supplier) {

    return new SettableSupplier<ExecutionRequest, CompletableFuture<ExecutionResult>>() {
      volatile boolean applied;
      volatile boolean set;
      volatile CompletableFuture<ExecutionResult> value;

      @Override
      public CompletableFuture<ExecutionResult> apply(ExecutionRequest request) {
        if (!applied && value != null) {
          applied = true;
          try {
            Assert.log("SettableSupplier returning set result " + value.get());
          } catch (Throwable e) {
          }
          return value;
        } else {
          CompletableFuture<ExecutionResult> rr = supplier.apply(request);
          try {
            Object rrr = rr.isDone() ? rr.get() : rr;
            Assert.log("SettableSupplier returning supplied result " + rrr);
          } catch (Throwable e) {
          }

          return rr;
        }
      }

      @Override
      public boolean isSet() {
        return set;
      }

      @Override
      public void set(CompletableFuture<ExecutionResult> value) {
        set = true;
        applied = false;
        this.value = value;
      }
    };
  }

  static CheckedSupplier<Void> toSupplier(CheckedRunnable runnable) {
    Assert.notNull(runnable, "runnable");
    return () -> {
      runnable.run();
      return null;
    };
  }

  static CheckedSupplier<Void> toSupplier(ContextualRunnable<Void> runnable, ExecutionContext<Void> context) {
    Assert.notNull(runnable, "runnable");
    return () -> {
      runnable.run(context);
      return null;
    };
  }

  static <R> CheckedSupplier<R> toSupplier(ContextualSupplier<R, R> supplier, ExecutionContext<R> context) {
    Assert.notNull(supplier, "supplier");
    return () -> supplier.get(context);
  }

  static ContextualSupplier<Void, Void> toCtxSupplier(CheckedRunnable runnable) {
    Assert.notNull(runnable, "runnable");
    return ctx -> {
      runnable.run();
      return null;
    };
  }

  static ContextualSupplier<Void, Void> toCtxSupplier(ContextualRunnable<Void> runnable) {
    Assert.notNull(runnable, "runnable");
    return ctx -> {
      runnable.run(ctx);
      return null;
    };
  }

  static <R, T> ContextualSupplier<R, T> toCtxSupplier(CheckedSupplier<T> supplier) {
    Assert.notNull(supplier, "supplier");
    return ctx -> supplier.get();
  }

  static <T, R> CheckedFunction<T, R> toFn(CheckedConsumer<T> consumer) {
    return t -> {
      consumer.accept(t);
      return null;
    };
  }

  static <T, R> CheckedFunction<T, R> toFn(CheckedRunnable runnable) {
    return t -> {
      runnable.run();
      return null;
    };
  }

  static <T, R> CheckedFunction<T, R> toFn(CheckedSupplier<R> supplier) {
    return t -> supplier.get();
  }

  static <T, R> CheckedFunction<T, R> toFn(R result) {
    return t -> result;
  }
}
