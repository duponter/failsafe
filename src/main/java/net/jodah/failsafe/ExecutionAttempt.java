//package net.jodah.failsafe;
//
//import net.jodah.failsafe.internal.util.Assert;
//
//import java.time.Duration;
//
//public class ExecutionAttempt<R> {
//  private final ExecutionContext<R> executionContext;
//  volatile Duration startTime = Duration.ZERO;
//
//  ExecutionAttempt(ExecutionContext<R> executionContext) {
//    this.executionContext = executionContext;
//  }
//
//  // The index of a PolicyExecutor that cancelled the execution. 0 represents non-cancelled.
//  volatile int cancelledIndex;
//  volatile R result;
//  volatile Throwable failure;
//  /* Whether the supplier is in progress */
//  volatile boolean inProgress;
//  /* Whether the execution attempt has been recorded */
//  volatile boolean attemptRecorded;
//  /* Whether the execution can be interrupted */
//  volatile boolean canInterrupt;
//  /* Whether the execution has been internally interrupted */
//  volatile boolean interrupted;
//  /* The wait time in nanoseconds. */
//  volatile long waitNanos;
//  /* Whether the execution has been completed */
//  volatile boolean completed;
//
//  @SuppressWarnings("unchecked")
//  void record(ExecutionResult executionResult, boolean timeout) {
//    Assert.state(!completed, "Execution has already been completed");
//    if (!interrupted) {
//      //Assert.log("AbstractExecution recording "+result + " attemptRecorded:"+attemptRecorded + " inProgress:"+inProgress)  ;
//
//      recordAttempt();
//      if (inProgress) {
//        Assert.log(this, "recording result with inProgress="+inProgress+", attemptRecorded="+attemptRecorded);
//        result = (R) executionResult.getResult();
//        failure = executionResult.getFailure();
//        executionContext.executions.incrementAndGet();
//        if (!timeout)
//          inProgress = false;
//      }
//    }
//    Assert.log(this, "attempts="+executionContext.attempts.get()+ ", executions="+executionContext.executions.get());
//  }
//
//  /**
//   * Records an execution attempt which may correspond with an execution result. Async executions will have results
//   * recorded separately.
//   */
//  void recordAttempt() {
//    if (!attemptRecorded) {
//      executionContext.attempts.incrementAndGet();
//      attemptRecorded = true;
//      Assert.log(this, "setting attemptRecorded="+attemptRecorded);
//    }
//  }
//
//  synchronized void preExecute() {
//    startTime = Duration.ofNanos(System.nanoTime());
//    if (executionContext.startTime == Duration.ZERO)
//      executionContext.startTime = startTime;
//    inProgress = true;
//    Assert.log(this, "preExecute setting inProgress=true, attemptRecorded=false");
//    attemptRecorded = false;
//    cancelledIndex = 0;
//    canInterrupt = true;
//    interrupted = false;
//  }
//}
