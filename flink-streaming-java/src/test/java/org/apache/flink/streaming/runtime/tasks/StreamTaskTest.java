/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.runtime.tasks;

import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.operators.MailboxExecutor;
import org.apache.flink.api.common.state.CheckpointListener;
import org.apache.flink.api.common.typeinfo.BasicTypeInfo;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.api.common.typeutils.base.StringSerializer;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.ReadableConfig;
import org.apache.flink.core.fs.FSDataInputStream;
import org.apache.flink.core.testutils.OneShotLatch;
import org.apache.flink.runtime.checkpoint.CheckpointException;
import org.apache.flink.runtime.checkpoint.CheckpointMetaData;
import org.apache.flink.runtime.checkpoint.CheckpointMetrics;
import org.apache.flink.runtime.checkpoint.CheckpointMetricsBuilder;
import org.apache.flink.runtime.checkpoint.CheckpointOptions;
import org.apache.flink.runtime.checkpoint.CheckpointType;
import org.apache.flink.runtime.checkpoint.OperatorSubtaskState;
import org.apache.flink.runtime.checkpoint.SubtaskState;
import org.apache.flink.runtime.checkpoint.TaskStateSnapshot;
import org.apache.flink.runtime.checkpoint.channel.ChannelStateWriter;
import org.apache.flink.runtime.deployment.ResultPartitionDeploymentDescriptor;
import org.apache.flink.runtime.execution.CancelTaskException;
import org.apache.flink.runtime.execution.Environment;
import org.apache.flink.runtime.execution.ExecutionState;
import org.apache.flink.runtime.executiongraph.ExecutionAttemptID;
import org.apache.flink.runtime.io.network.NettyShuffleEnvironment;
import org.apache.flink.runtime.io.network.NettyShuffleEnvironmentBuilder;
import org.apache.flink.runtime.io.network.api.writer.AvailabilityTestResultPartitionWriter;
import org.apache.flink.runtime.io.network.api.writer.RecordWriter;
import org.apache.flink.runtime.io.network.api.writer.ResultPartitionWriter;
import org.apache.flink.runtime.io.network.partition.consumer.InputGate;
import org.apache.flink.runtime.io.network.partition.consumer.TestInputChannel;
import org.apache.flink.runtime.jobgraph.OperatorID;
import org.apache.flink.runtime.jobgraph.tasks.AbstractInvokable;
import org.apache.flink.runtime.metrics.TimerGauge;
import org.apache.flink.runtime.metrics.groups.TaskIOMetricGroup;
import org.apache.flink.runtime.operators.testutils.DummyEnvironment;
import org.apache.flink.runtime.operators.testutils.ExpectedTestException;
import org.apache.flink.runtime.operators.testutils.MockEnvironment;
import org.apache.flink.runtime.operators.testutils.MockEnvironmentBuilder;
import org.apache.flink.runtime.operators.testutils.MockInputSplitProvider;
import org.apache.flink.runtime.shuffle.PartitionDescriptorBuilder;
import org.apache.flink.runtime.shuffle.ShuffleEnvironment;
import org.apache.flink.runtime.state.AbstractKeyedStateBackend;
import org.apache.flink.runtime.state.AbstractStateBackend;
import org.apache.flink.runtime.state.CheckpointStreamFactory;
import org.apache.flink.runtime.state.CheckpointableKeyedStateBackend;
import org.apache.flink.runtime.state.DoneFuture;
import org.apache.flink.runtime.state.FunctionInitializationContext;
import org.apache.flink.runtime.state.FunctionSnapshotContext;
import org.apache.flink.runtime.state.KeyGroupRange;
import org.apache.flink.runtime.state.KeyGroupStatePartitionStreamProvider;
import org.apache.flink.runtime.state.KeyedStateHandle;
import org.apache.flink.runtime.state.OperatorStateBackend;
import org.apache.flink.runtime.state.OperatorStateHandle;
import org.apache.flink.runtime.state.OperatorStreamStateHandle;
import org.apache.flink.runtime.state.SharedStateRegistry;
import org.apache.flink.runtime.state.SnapshotResult;
import org.apache.flink.runtime.state.StateBackendFactory;
import org.apache.flink.runtime.state.StateInitializationContext;
import org.apache.flink.runtime.state.StatePartitionStreamProvider;
import org.apache.flink.runtime.state.StreamStateHandle;
import org.apache.flink.runtime.state.TaskLocalStateStoreImpl;
import org.apache.flink.runtime.state.TaskStateManager;
import org.apache.flink.runtime.state.TaskStateManagerImpl;
import org.apache.flink.runtime.state.changelog.StateChangelogStorage;
import org.apache.flink.runtime.state.memory.MemoryStateBackend;
import org.apache.flink.runtime.taskmanager.CheckpointResponder;
import org.apache.flink.runtime.taskmanager.NoOpTaskManagerActions;
import org.apache.flink.runtime.taskmanager.Task;
import org.apache.flink.runtime.taskmanager.TaskExecutionState;
import org.apache.flink.runtime.taskmanager.TaskManagerActions;
import org.apache.flink.runtime.taskmanager.TestTaskBuilder;
import org.apache.flink.runtime.throughput.ThroughputCalculator;
import org.apache.flink.runtime.util.NettyShuffleDescriptorBuilder;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.checkpoint.CheckpointedFunction;
import org.apache.flink.streaming.api.environment.ExecutionCheckpointingOptions;
import org.apache.flink.streaming.api.functions.source.RichParallelSourceFunction;
import org.apache.flink.streaming.api.functions.source.SourceFunction;
import org.apache.flink.streaming.api.graph.StreamConfig;
import org.apache.flink.streaming.api.operators.AbstractStreamOperator;
import org.apache.flink.streaming.api.operators.AbstractStreamOperatorFactory;
import org.apache.flink.streaming.api.operators.ChainingStrategy;
import org.apache.flink.streaming.api.operators.InternalTimeServiceManager;
import org.apache.flink.streaming.api.operators.OneInputStreamOperator;
import org.apache.flink.streaming.api.operators.OperatorSnapshotFutures;
import org.apache.flink.streaming.api.operators.Output;
import org.apache.flink.streaming.api.operators.StreamMap;
import org.apache.flink.streaming.api.operators.StreamOperator;
import org.apache.flink.streaming.api.operators.StreamOperatorParameters;
import org.apache.flink.streaming.api.operators.StreamOperatorStateContext;
import org.apache.flink.streaming.api.operators.StreamSource;
import org.apache.flink.streaming.api.operators.StreamTaskStateInitializer;
import org.apache.flink.streaming.runtime.io.DataInputStatus;
import org.apache.flink.streaming.runtime.io.StreamInputProcessor;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.runtime.tasks.mailbox.MailboxDefaultAction;
import org.apache.flink.streaming.util.MockStreamConfig;
import org.apache.flink.streaming.util.MockStreamTaskBuilder;
import org.apache.flink.streaming.util.TestSequentialReadingStreamOperator;
import org.apache.flink.util.CloseableIterable;
import org.apache.flink.util.ExceptionUtils;
import org.apache.flink.util.FatalExitExceptionHandler;
import org.apache.flink.util.Preconditions;
import org.apache.flink.util.TestLogger;
import org.apache.flink.util.clock.SystemClock;
import org.apache.flink.util.concurrent.FutureUtils;
import org.apache.flink.util.concurrent.TestingUncaughtExceptionHandler;
import org.apache.flink.util.function.BiConsumerWithException;
import org.apache.flink.util.function.RunnableWithException;
import org.apache.flink.util.function.SupplierWithException;

import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import javax.annotation.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.StreamCorruptedException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.apache.flink.api.common.typeinfo.BasicTypeInfo.STRING_TYPE_INFO;
import static org.apache.flink.configuration.StateBackendOptions.STATE_BACKEND;
import static org.apache.flink.configuration.TaskManagerOptions.BUFFER_DEBLOAT_ENABLED;
import static org.apache.flink.configuration.TaskManagerOptions.BUFFER_DEBLOAT_PERIOD;
import static org.apache.flink.configuration.TaskManagerOptions.BUFFER_DEBLOAT_TARGET;
import static org.apache.flink.runtime.checkpoint.CheckpointFailureReason.UNKNOWN_TASK_CHECKPOINT_NOTIFICATION_FAILURE;
import static org.apache.flink.runtime.checkpoint.StateObjectCollection.singleton;
import static org.apache.flink.runtime.state.CheckpointStorageLocationReference.getDefault;
import static org.apache.flink.streaming.runtime.tasks.mailbox.TaskMailbox.MAX_PRIORITY;
import static org.apache.flink.streaming.util.StreamTaskUtil.waitTaskIsRunning;
import static org.apache.flink.util.Preconditions.checkState;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Tests for {@link StreamTask}. */
public class StreamTaskTest extends TestLogger {

    private static OneShotLatch syncLatch;

    @Rule public final Timeout timeoutPerTest = Timeout.seconds(30);

    @Test
    public void testCancellationWaitsForActiveTimers() throws Exception {
        StreamTaskWithBlockingTimer.reset();
        ResultPartitionDeploymentDescriptor descriptor =
                new ResultPartitionDeploymentDescriptor(
                        PartitionDescriptorBuilder.newBuilder().build(),
                        NettyShuffleDescriptorBuilder.newBuilder().buildLocal(),
                        1,
                        false);
        Task task =
                new TestTaskBuilder(new NettyShuffleEnvironmentBuilder().build())
                        .setInvokable(StreamTaskWithBlockingTimer.class)
                        .setResultPartitions(singletonList(descriptor))
                        .build();
        task.startTaskThread();

        StreamTaskWithBlockingTimer.timerStarted.join();
        task.cancelExecution();

        task.getTerminationFuture().join();
        // explicitly check for exceptions as they are ignored after cancellation
        StreamTaskWithBlockingTimer.timerFinished.join();
        checkState(task.getExecutionState() == ExecutionState.CANCELED);
    }

    @Test
    public void testSavepointSuspendCompleted() throws Exception {
        testSyncSavepointWithEndInput(
                StreamTask::notifyCheckpointCompleteAsync, CheckpointType.SAVEPOINT_SUSPEND, false);
    }

    @Test
    public void testSavepointTerminateCompleted() throws Exception {
        testSyncSavepointWithEndInput(
                StreamTask::notifyCheckpointCompleteAsync,
                CheckpointType.SAVEPOINT_TERMINATE,
                true);
    }

    @Test
    public void testSavepointSuspendedAborted() throws Exception {
        testSyncSavepointWithEndInput(
                (task, id) ->
                        task.abortCheckpointOnBarrier(
                                id,
                                new CheckpointException(
                                        UNKNOWN_TASK_CHECKPOINT_NOTIFICATION_FAILURE)),
                CheckpointType.SAVEPOINT_SUSPEND,
                true);
    }

    @Test
    public void testSavepointTerminateAborted() throws Exception {
        testSyncSavepointWithEndInput(
                (task, id) ->
                        task.abortCheckpointOnBarrier(
                                id,
                                new CheckpointException(
                                        UNKNOWN_TASK_CHECKPOINT_NOTIFICATION_FAILURE)),
                CheckpointType.SAVEPOINT_TERMINATE,
                true);
    }

    @Test
    public void testSavepointSuspendAbortedAsync() throws Exception {
        testSyncSavepointWithEndInput(
                (streamTask, abortCheckpointId) ->
                        streamTask.notifyCheckpointAbortAsync(abortCheckpointId, 0),
                CheckpointType.SAVEPOINT_SUSPEND,
                true);
    }

    @Test
    public void testSavepointTerminateAbortedAsync() throws Exception {
        testSyncSavepointWithEndInput(
                (streamTask, abortCheckpointId) ->
                        streamTask.notifyCheckpointAbortAsync(abortCheckpointId, 0),
                CheckpointType.SAVEPOINT_TERMINATE,
                true);
    }

    /**
     * Test for SyncSavepoint and EndInput interactions. Targets following scenarios scenarios:
     *
     * <ol>
     *   <li>Thread1: notify sync savepoint
     *   <li>Thread2: endInput
     *   <li>Thread1: confirm/abort/abortAsync
     *   <li>assert inputEnded: confirmed - no, abort/abortAsync - yes
     * </ol>
     */
    private void testSyncSavepointWithEndInput(
            BiConsumerWithException<StreamTask<?, ?>, Long, IOException> savepointResult,
            CheckpointType checkpointType,
            boolean expectEndInput)
            throws Exception {
        StreamTaskMailboxTestHarness<String> harness =
                new StreamTaskMailboxTestHarnessBuilder<>(OneInputStreamTask::new, STRING_TYPE_INFO)
                        .addInput(STRING_TYPE_INFO)
                        .setupOutputForSingletonOperatorChain(
                                new TestBoundedOneInputStreamOperator())
                        .build();

        final long checkpointId = 1L;
        CountDownLatch savepointTriggeredLatch = new CountDownLatch(1);
        CountDownLatch inputEndedLatch = new CountDownLatch(1);

        MailboxExecutor executor =
                harness.streamTask.getMailboxExecutorFactory().createExecutor(MAX_PRIORITY);
        executor.execute(
                () -> {
                    try {
                        harness.streamTask.triggerCheckpointOnBarrier(
                                new CheckpointMetaData(checkpointId, checkpointId),
                                new CheckpointOptions(checkpointType, getDefault()),
                                new CheckpointMetricsBuilder());
                    } catch (IOException e) {
                        fail(e.getMessage());
                    }
                },
                "triggerCheckpointOnBarrier");
        new Thread(
                        () -> {
                            try {
                                savepointTriggeredLatch.await();
                                harness.endInput();
                                inputEndedLatch.countDown();
                            } catch (InterruptedException e) {
                                fail(e.getMessage());
                            }
                        })
                .start();
        // this mails should be executed from the one above (from triggerCheckpointOnBarrier)
        executor.execute(savepointTriggeredLatch::countDown, "savepointTriggeredLatch");
        executor.execute(
                () -> {
                    inputEndedLatch.await();
                    savepointResult.accept(harness.streamTask, checkpointId);
                },
                "savepointResult");
        harness.processAll();

        Assert.assertEquals(expectEndInput, TestBoundedOneInputStreamOperator.isInputEnded());
    }

    @Test
    public void testCleanUpExceptionSuppressing() throws Exception {
        OneInputStreamTaskTestHarness<String, String> testHarness =
                new OneInputStreamTaskTestHarness<>(
                        OneInputStreamTask::new, STRING_TYPE_INFO, STRING_TYPE_INFO);

        testHarness.setupOutputForSingletonOperatorChain();

        StreamConfig streamConfig = testHarness.getStreamConfig();
        streamConfig.setStreamOperator(new FailingTwiceOperator());
        streamConfig.setOperatorID(new OperatorID());

        testHarness.invoke();
        testHarness.waitForTaskRunning();

        testHarness.processElement(new StreamRecord<>("Doesn't matter", 0));

        try {
            testHarness.waitForTaskCompletion();
        } catch (Exception ex) {
            // make sure the original exception is the cause and not wrapped
            if (!(ex.getCause() instanceof ExpectedTestException)) {
                throw ex;
            }
            // make sure DisposeException is the only suppressed exception
            if (ex.getCause().getSuppressed().length != 1) {
                throw ex;
            }
            if (!(ex.getCause().getSuppressed()[0]
                    instanceof FailingTwiceOperator.CloseException)) {
                throw ex;
            }
        }
    }

    private static class FailingTwiceOperator extends AbstractStreamOperator<String>
            implements OneInputStreamOperator<String, String> {
        private static final long serialVersionUID = 1L;

        @Override
        public void processElement(StreamRecord<String> element) throws Exception {
            throw new ExpectedTestException();
        }

        @Override
        public void close() throws Exception {
            throw new CloseException();
        }

        static class CloseException extends Exception {
            public CloseException() {
                super("Close Exception. This exception should be suppressed");
            }
        }
    }

    /**
     * This test checks the async exceptions handling wraps the message and cause as an
     * AsynchronousException and propagates this to the environment.
     */
    @Test
    public void streamTaskAsyncExceptionHandler_handleException_forwardsMessageProperly() {
        MockEnvironment mockEnvironment = MockEnvironment.builder().build();
        RuntimeException expectedException = new RuntimeException("RUNTIME EXCEPTION");

        final StreamTask.StreamTaskAsyncExceptionHandler asyncExceptionHandler =
                new StreamTask.StreamTaskAsyncExceptionHandler(mockEnvironment);

        mockEnvironment.setExpectedExternalFailureCause(AsynchronousException.class);
        final String expectedErrorMessage = "EXPECTED_ERROR MESSAGE";

        asyncExceptionHandler.handleAsyncException(expectedErrorMessage, expectedException);

        // expect an AsynchronousException containing the supplied error details
        Optional<? extends Throwable> actualExternalFailureCause =
                mockEnvironment.getActualExternalFailureCause();
        final Throwable actualException =
                actualExternalFailureCause.orElseThrow(
                        () -> new AssertionError("Expected exceptional completion"));

        assertThat(actualException, instanceOf(AsynchronousException.class));
        assertThat(actualException.getMessage(), is("EXPECTED_ERROR MESSAGE"));
        assertThat(actualException.getCause(), is(expectedException));
    }

    /**
     * This test checks that cancel calls that are issued before the operator is instantiated still
     * lead to proper canceling.
     */
    @Test
    public void testEarlyCanceling() throws Exception {
        final StreamConfig cfg = new StreamConfig(new Configuration());
        cfg.setOperatorID(new OperatorID(4711L, 42L));
        cfg.setStreamOperator(new SlowlyDeserializingOperator());
        cfg.setTimeCharacteristic(TimeCharacteristic.ProcessingTime);

        final TaskManagerActions taskManagerActions = spy(new NoOpTaskManagerActions());
        try (NettyShuffleEnvironment shuffleEnvironment =
                new NettyShuffleEnvironmentBuilder().build()) {
            final Task task =
                    new TestTaskBuilder(shuffleEnvironment)
                            .setInvokable(SourceStreamTask.class)
                            .setTaskConfig(cfg.getConfiguration())
                            .setTaskManagerActions(taskManagerActions)
                            .build();

            final TaskExecutionState state =
                    new TaskExecutionState(task.getExecutionId(), ExecutionState.RUNNING);

            task.startTaskThread();

            verify(taskManagerActions, timeout(2000L)).updateTaskExecutionState(eq(state));

            // send a cancel. because the operator takes a long time to deserialize, this should
            // hit the task before the operator is deserialized
            task.cancelExecution();

            task.getExecutingThread().join();

            assertFalse("Task did not cancel", task.getExecutingThread().isAlive());
            assertEquals(ExecutionState.CANCELED, task.getExecutionState());
        }
    }

    @Test
    public void testStateBackendLoadingAndClosing() throws Exception {
        Configuration taskManagerConfig = new Configuration();
        taskManagerConfig.setString(STATE_BACKEND, TestMemoryStateBackendFactory.class.getName());

        StreamConfig cfg = new StreamConfig(new Configuration());
        cfg.setStateKeySerializer(mock(TypeSerializer.class));
        cfg.setOperatorID(new OperatorID(4711L, 42L));
        TestStreamSource<Long, MockSourceFunction> streamSource =
                new TestStreamSource<>(new MockSourceFunction());
        cfg.setStreamOperator(streamSource);
        cfg.setTimeCharacteristic(TimeCharacteristic.ProcessingTime);

        try (ShuffleEnvironment shuffleEnvironment = new NettyShuffleEnvironmentBuilder().build()) {
            Task task =
                    createTask(
                            StateBackendTestSource.class,
                            shuffleEnvironment,
                            cfg,
                            taskManagerConfig);

            StateBackendTestSource.fail = false;
            task.startTaskThread();

            // wait for clean termination
            task.getExecutingThread().join();

            // ensure that the state backends and stream iterables are closed ...
            verify(TestStreamSource.operatorStateBackend).close();
            verify(TestStreamSource.keyedStateBackend).close();
            verify(TestStreamSource.rawOperatorStateInputs).close();
            verify(TestStreamSource.rawKeyedStateInputs).close();
            // ... and disposed
            verify(TestStreamSource.operatorStateBackend).dispose();
            verify(TestStreamSource.keyedStateBackend).dispose();

            assertEquals(ExecutionState.FINISHED, task.getExecutionState());
        }
    }

    @Test
    public void testStateBackendClosingOnFailure() throws Exception {
        Configuration taskManagerConfig = new Configuration();
        taskManagerConfig.setString(STATE_BACKEND, TestMemoryStateBackendFactory.class.getName());

        StreamConfig cfg = new StreamConfig(new Configuration());
        cfg.setStateKeySerializer(mock(TypeSerializer.class));
        cfg.setOperatorID(new OperatorID(4711L, 42L));
        TestStreamSource<Long, MockSourceFunction> streamSource =
                new TestStreamSource<>(new MockSourceFunction());
        cfg.setStreamOperator(streamSource);
        cfg.setTimeCharacteristic(TimeCharacteristic.ProcessingTime);

        try (NettyShuffleEnvironment shuffleEnvironment =
                new NettyShuffleEnvironmentBuilder().build()) {
            Task task =
                    createTask(
                            StateBackendTestSource.class,
                            shuffleEnvironment,
                            cfg,
                            taskManagerConfig);

            StateBackendTestSource.fail = true;
            task.startTaskThread();

            // wait for clean termination
            task.getExecutingThread().join();

            // ensure that the state backends and stream iterables are closed ...
            verify(TestStreamSource.operatorStateBackend).close();
            verify(TestStreamSource.keyedStateBackend).close();
            verify(TestStreamSource.rawOperatorStateInputs).close();
            verify(TestStreamSource.rawKeyedStateInputs).close();
            // ... and disposed
            verify(TestStreamSource.operatorStateBackend).dispose();
            verify(TestStreamSource.keyedStateBackend).dispose();

            assertEquals(ExecutionState.FAILED, task.getExecutionState());
        }
    }

    @Test
    public void testCanceleablesCanceledOnCancelTaskError() throws Exception {
        syncLatch = new OneShotLatch();

        StreamConfig cfg = new StreamConfig(new Configuration());
        try (NettyShuffleEnvironment shuffleEnvironment =
                new NettyShuffleEnvironmentBuilder().build()) {

            Task task =
                    createTask(
                            CancelFailingTask.class, shuffleEnvironment, cfg, new Configuration());

            // start the task and wait until it runs
            // execution state RUNNING is not enough, we need to wait until the stream task's run()
            // method
            // is entered
            task.startTaskThread();
            syncLatch.await();

            // cancel the execution - this should lead to smooth shutdown
            task.cancelExecution();
            task.getExecutingThread().join();

            assertEquals(ExecutionState.CANCELED, task.getExecutionState());
        }
    }

    /**
     * A task that locks for ever, fail in {@link #cancelTask()}. It can be only shut down cleanly
     * if {@link StreamTask#getCancelables()} are closed properly.
     */
    public static class CancelFailingTask
            extends StreamTask<String, AbstractStreamOperator<String>> {

        public CancelFailingTask(Environment env) throws Exception {
            super(env);
        }

        @Override
        protected void init() {}

        @Override
        protected void processInput(MailboxDefaultAction.Controller controller) throws Exception {
            final OneShotLatch latch = new OneShotLatch();
            final Object lock = new Object();

            LockHolder holder = new LockHolder(lock, latch);
            holder.start();
            try {
                // cancellation should try and cancel this
                getCancelables().registerCloseable(holder);

                // wait till the lock holder has the lock
                latch.await();

                // we are at the point where cancelling can happen
                syncLatch.trigger();

                // try to acquire the lock - this is not possible as long as the lock holder
                // thread lives
                //noinspection SynchronizationOnLocalVariableOrMethodParameter
                synchronized (lock) {
                    // nothing
                }
            } finally {
                holder.close();
            }
            controller.suspendDefaultAction();
            mailboxProcessor.suspend();
        }

        @Override
        protected void cleanup() {}

        @Override
        protected void cancelTask() throws Exception {
            throw new Exception("test exception");
        }

        /** A thread that holds a lock as long as it lives. */
        private static final class LockHolder extends Thread implements Closeable {

            private final OneShotLatch trigger;
            private final Object lock;
            private volatile boolean canceled;

            private LockHolder(Object lock, OneShotLatch trigger) {
                this.lock = lock;
                this.trigger = trigger;
            }

            @Override
            public void run() {
                synchronized (lock) {
                    while (!canceled) {
                        // signal that we grabbed the lock
                        trigger.trigger();

                        // basically freeze this thread
                        try {
                            //noinspection SleepWhileHoldingLock
                            Thread.sleep(1000000000);
                        } catch (InterruptedException ignored) {
                        }
                    }
                }
            }

            public void cancel() {
                canceled = true;
            }

            @Override
            public void close() {
                canceled = true;
                interrupt();
            }
        }
    }

    /**
     * CancelTaskException can be thrown in a down stream task, for example if an upstream task was
     * cancelled first and those two tasks were connected via {@link
     * org.apache.flink.runtime.io.network.partition.consumer.LocalInputChannel}. {@link StreamTask}
     * should be able to correctly handle such situation.
     */
    @Test
    public void testCancelTaskExceptionHandling() throws Exception {
        StreamConfig cfg = new StreamConfig(new Configuration());

        try (NettyShuffleEnvironment shuffleEnvironment =
                new NettyShuffleEnvironmentBuilder().build()) {
            Task task =
                    createTask(
                            CancelThrowingTask.class, shuffleEnvironment, cfg, new Configuration());

            task.startTaskThread();
            task.getExecutingThread().join();

            assertEquals(ExecutionState.CANCELED, task.getExecutionState());
        }
    }

    /** A task that throws {@link CancelTaskException}. */
    public static class CancelThrowingTask
            extends StreamTask<String, AbstractStreamOperator<String>> {

        public CancelThrowingTask(Environment env) throws Exception {
            super(env);
        }

        @Override
        protected void init() {}

        @Override
        protected void processInput(MailboxDefaultAction.Controller controller) {
            throw new CancelTaskException();
        }
    }

    @Test
    public void testDecliningCheckpointStreamOperator() throws Exception {
        DummyEnvironment dummyEnvironment = new DummyEnvironment();

        // mock the returned snapshots
        OperatorSnapshotFutures operatorSnapshotResult1 = mock(OperatorSnapshotFutures.class);
        OperatorSnapshotFutures operatorSnapshotResult2 = mock(OperatorSnapshotFutures.class);

        final Exception testException = new ExpectedTestException();

        RunningTask<MockStreamTask> task =
                runTask(
                        () ->
                                createMockStreamTask(
                                        dummyEnvironment,
                                        operatorChain(
                                                streamOperatorWithSnapshotException(testException),
                                                streamOperatorWithSnapshot(operatorSnapshotResult1),
                                                streamOperatorWithSnapshot(
                                                        operatorSnapshotResult2))));
        MockStreamTask streamTask = task.streamTask;

        waitTaskIsRunning(streamTask, task.invocationFuture);

        streamTask.triggerCheckpointAsync(
                new CheckpointMetaData(42L, 1L),
                CheckpointOptions.forCheckpointWithDefaultLocation());

        try {
            task.waitForTaskCompletion(false);
        } catch (Exception ex) {
            if (!ExceptionUtils.findThrowable(ex, ExpectedTestException.class).isPresent()) {
                throw ex;
            }
        }

        verify(operatorSnapshotResult1).cancel();
        verify(operatorSnapshotResult2).cancel();
    }

    /**
     * Tests that uncaught exceptions in the async part of a checkpoint operation are forwarded to
     * the uncaught exception handler. See <a
     * href="https://issues.apache.org/jira/browse/FLINK-12889">FLINK-12889</a>.
     */
    @Test
    public void testUncaughtExceptionInAsynchronousCheckpointingOperation() throws Exception {
        final RuntimeException failingCause = new RuntimeException("Test exception");
        FailingDummyEnvironment failingDummyEnvironment = new FailingDummyEnvironment(failingCause);

        // mock the returned snapshots
        OperatorSnapshotFutures operatorSnapshotResult =
                new OperatorSnapshotFutures(
                        ExceptionallyDoneFuture.of(failingCause),
                        DoneFuture.of(SnapshotResult.empty()),
                        DoneFuture.of(SnapshotResult.empty()),
                        DoneFuture.of(SnapshotResult.empty()),
                        DoneFuture.of(SnapshotResult.empty()),
                        DoneFuture.of(SnapshotResult.empty()));

        final TestingUncaughtExceptionHandler uncaughtExceptionHandler =
                new TestingUncaughtExceptionHandler();

        RunningTask<MockStreamTask> task =
                runTask(
                        () ->
                                new MockStreamTask(
                                        failingDummyEnvironment,
                                        operatorChain(
                                                streamOperatorWithSnapshot(operatorSnapshotResult)),
                                        uncaughtExceptionHandler));
        MockStreamTask streamTask = task.streamTask;

        waitTaskIsRunning(streamTask, task.invocationFuture);

        streamTask.triggerCheckpointAsync(
                new CheckpointMetaData(42L, 1L),
                CheckpointOptions.forCheckpointWithDefaultLocation());

        final Throwable uncaughtException = uncaughtExceptionHandler.waitForUncaughtException();
        assertThat(uncaughtException, is(failingCause));

        streamTask.finishInput();
        task.waitForTaskCompletion(false);
    }

    /**
     * Tests that in case of a failing AsyncCheckpointRunnable all operator snapshot results are
     * cancelled and all non partitioned state handles are discarded.
     */
    @Test
    public void testFailingAsyncCheckpointRunnable() throws Exception {

        // mock the new state operator snapshots
        OperatorSnapshotFutures operatorSnapshotResult1 = mock(OperatorSnapshotFutures.class);
        OperatorSnapshotFutures operatorSnapshotResult2 = mock(OperatorSnapshotFutures.class);
        OperatorSnapshotFutures operatorSnapshotResult3 = mock(OperatorSnapshotFutures.class);

        RunnableFuture<SnapshotResult<OperatorStateHandle>> failingFuture =
                mock(RunnableFuture.class);
        when(failingFuture.get())
                .thenThrow(new ExecutionException(new Exception("Test exception")));

        when(operatorSnapshotResult3.getOperatorStateRawFuture()).thenReturn(failingFuture);

        try (MockEnvironment mockEnvironment = new MockEnvironmentBuilder().build()) {
            RunningTask<MockStreamTask> task =
                    runTask(
                            () ->
                                    createMockStreamTask(
                                            mockEnvironment,
                                            operatorChain(
                                                    streamOperatorWithSnapshot(
                                                            operatorSnapshotResult1),
                                                    streamOperatorWithSnapshot(
                                                            operatorSnapshotResult2),
                                                    streamOperatorWithSnapshot(
                                                            operatorSnapshotResult3))));

            MockStreamTask streamTask = task.streamTask;

            waitTaskIsRunning(streamTask, task.invocationFuture);

            mockEnvironment.setExpectedExternalFailureCause(Throwable.class);
            streamTask
                    .triggerCheckpointAsync(
                            new CheckpointMetaData(42L, 1L),
                            CheckpointOptions.forCheckpointWithDefaultLocation())
                    .get();

            // wait for the completion of the async task
            ExecutorService executor = streamTask.getAsyncOperationsThreadPool();
            executor.shutdown();
            if (!executor.awaitTermination(10000L, TimeUnit.MILLISECONDS)) {
                fail(
                        "Executor did not shut down within the given timeout. This indicates that the "
                                + "checkpointing did not resume.");
            }

            assertTrue(mockEnvironment.getActualExternalFailureCause().isPresent());

            verify(operatorSnapshotResult1).cancel();
            verify(operatorSnapshotResult2).cancel();
            verify(operatorSnapshotResult3).cancel();

            streamTask.finishInput();
            task.waitForTaskCompletion(false);
        }
    }

    /**
     * FLINK-5667
     *
     * <p>Tests that a concurrent cancel operation does not discard the state handles of an
     * acknowledged checkpoint. The situation can only happen if the cancel call is executed after
     * Environment.acknowledgeCheckpoint() and before the CloseableRegistry.unregisterClosable()
     * call.
     */
    @Test
    public void testAsyncCheckpointingConcurrentCloseAfterAcknowledge() throws Exception {

        final OneShotLatch acknowledgeCheckpointLatch = new OneShotLatch();
        final OneShotLatch completeAcknowledge = new OneShotLatch();

        CheckpointResponder checkpointResponder = mock(CheckpointResponder.class);
        doAnswer(
                        new Answer() {
                            @Override
                            public Object answer(InvocationOnMock invocation) {
                                acknowledgeCheckpointLatch.trigger();

                                // block here so that we can issue the concurrent cancel call
                                while (true) {
                                    try {
                                        // wait until we successfully await (no pun intended)
                                        completeAcknowledge.await();

                                        // when await() returns normally, we break out of the loop
                                        break;
                                    } catch (InterruptedException e) {
                                        // survive interruptions that arise from thread pool
                                        // shutdown
                                        // production code cannot actually throw
                                        // InterruptedException from
                                        // checkpoint acknowledgement
                                    }
                                }

                                return null;
                            }
                        })
                .when(checkpointResponder)
                .acknowledgeCheckpoint(
                        any(JobID.class),
                        any(ExecutionAttemptID.class),
                        anyLong(),
                        any(CheckpointMetrics.class),
                        any(TaskStateSnapshot.class));

        TaskStateManager taskStateManager =
                new TaskStateManagerImpl(
                        new JobID(1L, 2L),
                        new ExecutionAttemptID(),
                        mock(TaskLocalStateStoreImpl.class),
                        mock(StateChangelogStorage.class),
                        null,
                        checkpointResponder);

        KeyedStateHandle managedKeyedStateHandle = mock(KeyedStateHandle.class);
        KeyedStateHandle rawKeyedStateHandle = mock(KeyedStateHandle.class);
        OperatorStateHandle managedOperatorStateHandle = mock(OperatorStreamStateHandle.class);
        OperatorStateHandle rawOperatorStateHandle = mock(OperatorStreamStateHandle.class);

        OperatorSnapshotFutures operatorSnapshotResult =
                new OperatorSnapshotFutures(
                        DoneFuture.of(SnapshotResult.of(managedKeyedStateHandle)),
                        DoneFuture.of(SnapshotResult.of(rawKeyedStateHandle)),
                        DoneFuture.of(SnapshotResult.of(managedOperatorStateHandle)),
                        DoneFuture.of(SnapshotResult.of(rawOperatorStateHandle)),
                        DoneFuture.of(SnapshotResult.empty()),
                        DoneFuture.of(SnapshotResult.empty()));

        try (MockEnvironment mockEnvironment =
                new MockEnvironmentBuilder()
                        .setTaskName("mock-task")
                        .setTaskStateManager(taskStateManager)
                        .build()) {

            RunningTask<MockStreamTask> task =
                    runTask(
                            () ->
                                    createMockStreamTask(
                                            mockEnvironment,
                                            operatorChain(
                                                    streamOperatorWithSnapshot(
                                                            operatorSnapshotResult))));

            MockStreamTask streamTask = task.streamTask;
            waitTaskIsRunning(streamTask, task.invocationFuture);

            final long checkpointId = 42L;
            streamTask.triggerCheckpointAsync(
                    new CheckpointMetaData(checkpointId, 1L),
                    CheckpointOptions.forCheckpointWithDefaultLocation());

            acknowledgeCheckpointLatch.await();

            ArgumentCaptor<TaskStateSnapshot> subtaskStateCaptor =
                    ArgumentCaptor.forClass(TaskStateSnapshot.class);

            // check that the checkpoint has been completed
            verify(checkpointResponder)
                    .acknowledgeCheckpoint(
                            any(JobID.class),
                            any(ExecutionAttemptID.class),
                            eq(checkpointId),
                            any(CheckpointMetrics.class),
                            subtaskStateCaptor.capture());

            TaskStateSnapshot subtaskStates = subtaskStateCaptor.getValue();
            OperatorSubtaskState subtaskState =
                    subtaskStates.getSubtaskStateMappings().iterator().next().getValue();

            // check that the subtask state contains the expected state handles
            assertEquals(singleton(managedKeyedStateHandle), subtaskState.getManagedKeyedState());
            assertEquals(singleton(rawKeyedStateHandle), subtaskState.getRawKeyedState());
            assertEquals(
                    singleton(managedOperatorStateHandle), subtaskState.getManagedOperatorState());
            assertEquals(singleton(rawOperatorStateHandle), subtaskState.getRawOperatorState());

            // check that the state handles have not been discarded
            verify(managedKeyedStateHandle, never()).discardState();
            verify(rawKeyedStateHandle, never()).discardState();
            verify(managedOperatorStateHandle, never()).discardState();
            verify(rawOperatorStateHandle, never()).discardState();

            streamTask.cancel();

            completeAcknowledge.trigger();

            // canceling the stream task after it has acknowledged the checkpoint should not discard
            // the state handles
            verify(managedKeyedStateHandle, never()).discardState();
            verify(rawKeyedStateHandle, never()).discardState();
            verify(managedOperatorStateHandle, never()).discardState();
            verify(rawOperatorStateHandle, never()).discardState();

            task.waitForTaskCompletion(true);
        }
    }

    /**
     * FLINK-5667
     *
     * <p>Tests that a concurrent cancel operation discards the state handles of a not yet
     * acknowledged checkpoint and prevents sending an acknowledge message to the
     * CheckpointCoordinator. The situation can only happen if the cancel call is executed before
     * Environment.acknowledgeCheckpoint().
     */
    @Test
    public void testAsyncCheckpointingConcurrentCloseBeforeAcknowledge() throws Exception {

        final TestingKeyedStateHandle managedKeyedStateHandle = new TestingKeyedStateHandle();
        final TestingKeyedStateHandle rawKeyedStateHandle = new TestingKeyedStateHandle();
        final TestingOperatorStateHandle managedOperatorStateHandle =
                new TestingOperatorStateHandle();
        final TestingOperatorStateHandle rawOperatorStateHandle = new TestingOperatorStateHandle();

        final BlockingRunnableFuture<SnapshotResult<KeyedStateHandle>> rawKeyedStateHandleFuture =
                new BlockingRunnableFuture<>(2, SnapshotResult.of(rawKeyedStateHandle));
        OperatorSnapshotFutures operatorSnapshotResult =
                new OperatorSnapshotFutures(
                        DoneFuture.of(SnapshotResult.of(managedKeyedStateHandle)),
                        rawKeyedStateHandleFuture,
                        DoneFuture.of(SnapshotResult.of(managedOperatorStateHandle)),
                        DoneFuture.of(SnapshotResult.of(rawOperatorStateHandle)),
                        DoneFuture.of(SnapshotResult.empty()),
                        DoneFuture.of(SnapshotResult.empty()));

        final OneInputStreamOperator<String, String> streamOperator =
                streamOperatorWithSnapshot(operatorSnapshotResult);

        final AcknowledgeDummyEnvironment mockEnvironment = new AcknowledgeDummyEnvironment();

        RunningTask<MockStreamTask> task =
                runTask(() -> createMockStreamTask(mockEnvironment, operatorChain(streamOperator)));

        waitTaskIsRunning(task.streamTask, task.invocationFuture);

        final long checkpointId = 42L;
        task.streamTask.triggerCheckpointAsync(
                new CheckpointMetaData(checkpointId, 1L),
                CheckpointOptions.forCheckpointWithDefaultLocation());

        rawKeyedStateHandleFuture.awaitRun();

        task.streamTask.cancel();

        final FutureUtils.ConjunctFuture<Void> discardFuture =
                FutureUtils.waitForAll(
                        asList(
                                managedKeyedStateHandle.getDiscardFuture(),
                                rawKeyedStateHandle.getDiscardFuture(),
                                managedOperatorStateHandle.getDiscardFuture(),
                                rawOperatorStateHandle.getDiscardFuture()));

        // make sure that all state handles have been discarded
        discardFuture.get();

        try {
            mockEnvironment.getAcknowledgeCheckpointFuture().get(10L, TimeUnit.MILLISECONDS);
            fail("The checkpoint should not get acknowledged.");
        } catch (TimeoutException expected) {
            // future should not be completed
        }

        task.waitForTaskCompletion(true);
    }

    /**
     * FLINK-5985
     *
     * <p>This test ensures that empty snapshots (no op/keyed stated whatsoever) will be reported as
     * stateless tasks. This happens by translating an empty {@link SubtaskState} into reporting
     * 'null' to #acknowledgeCheckpoint.
     */
    @Test
    public void testEmptySubtaskStateLeadsToStatelessAcknowledgment() throws Exception {

        // latch blocks until the async checkpoint thread acknowledges
        final OneShotLatch checkpointCompletedLatch = new OneShotLatch();
        final List<SubtaskState> checkpointResult = new ArrayList<>(1);

        CheckpointResponder checkpointResponder = mock(CheckpointResponder.class);
        doAnswer(
                        new Answer() {
                            @Override
                            public Object answer(InvocationOnMock invocation) throws Throwable {
                                SubtaskState subtaskState = invocation.getArgument(4);
                                checkpointResult.add(subtaskState);
                                checkpointCompletedLatch.trigger();
                                return null;
                            }
                        })
                .when(checkpointResponder)
                .acknowledgeCheckpoint(
                        any(JobID.class),
                        any(ExecutionAttemptID.class),
                        anyLong(),
                        any(CheckpointMetrics.class),
                        nullable(TaskStateSnapshot.class));

        TaskStateManager taskStateManager =
                new TaskStateManagerImpl(
                        new JobID(1L, 2L),
                        new ExecutionAttemptID(),
                        mock(TaskLocalStateStoreImpl.class),
                        mock(StateChangelogStorage.class),
                        null,
                        checkpointResponder);

        // mock the operator with empty snapshot result (all state handles are null)
        OneInputStreamOperator<String, String> statelessOperator =
                streamOperatorWithSnapshot(new OperatorSnapshotFutures());

        try (MockEnvironment mockEnvironment =
                new MockEnvironmentBuilder().setTaskStateManager(taskStateManager).build()) {

            RunningTask<MockStreamTask> task =
                    runTask(
                            () ->
                                    createMockStreamTask(
                                            mockEnvironment, operatorChain(statelessOperator)));

            waitTaskIsRunning(task.streamTask, task.invocationFuture);

            task.streamTask.triggerCheckpointAsync(
                    new CheckpointMetaData(42L, 1L),
                    CheckpointOptions.forCheckpointWithDefaultLocation());

            checkpointCompletedLatch.await(30, TimeUnit.SECONDS);

            // ensure that 'null' was acknowledged as subtask state
            Assert.assertNull(checkpointResult.get(0));

            task.streamTask.cancel();
            task.waitForTaskCompletion(true);
        }
    }

    /**
     * Tests that the StreamTask first closes all of its operators before setting its state to not
     * running (isRunning == false)
     *
     * <p>See FLINK-7430.
     */
    @Test
    public void testOperatorClosingBeforeStopRunning() throws Throwable {
        BlockingFinishStreamOperator.resetLatches();
        Configuration taskConfiguration = new Configuration();
        StreamConfig streamConfig = new StreamConfig(taskConfiguration);
        streamConfig.setStreamOperator(new BlockingFinishStreamOperator());
        streamConfig.setOperatorID(new OperatorID());

        try (MockEnvironment mockEnvironment =
                new MockEnvironmentBuilder()
                        .setTaskName("Test Task")
                        .setManagedMemorySize(32L * 1024L)
                        .setInputSplitProvider(new MockInputSplitProvider())
                        .setBufferSize(1)
                        .setTaskConfiguration(taskConfiguration)
                        .build()) {

            RunningTask<StreamTask<Void, BlockingFinishStreamOperator>> task =
                    runTask(() -> new NoOpStreamTask<>(mockEnvironment));

            BlockingFinishStreamOperator.inClose.await();

            // check that the StreamTask is not yet in isRunning == false
            assertTrue(task.streamTask.isRunning());

            // let the operator finish its close operation
            BlockingFinishStreamOperator.finishClose.trigger();

            task.waitForTaskCompletion(false);

            // now the StreamTask should no longer be running
            assertFalse(task.streamTask.isRunning());
        }
    }

    /**
     * Tests that {@link StreamTask#notifyCheckpointCompleteAsync(long)} is not relayed to closed
     * operators.
     *
     * <p>See FLINK-16383.
     */
    @Test
    public void testNotifyCheckpointOnClosedOperator() throws Throwable {
        ClosingOperator<Integer> operator = new ClosingOperator<>();
        StreamTaskMailboxTestHarnessBuilder<Integer> builder =
                new StreamTaskMailboxTestHarnessBuilder<>(
                                OneInputStreamTask::new, BasicTypeInfo.INT_TYPE_INFO)
                        .addInput(BasicTypeInfo.INT_TYPE_INFO);
        StreamTaskMailboxTestHarness<Integer> harness =
                builder.setupOutputForSingletonOperatorChain(operator).build();
        // keeps the mailbox from suspending
        harness.setAutoProcess(false);
        harness.processElement(new StreamRecord<>(1));
        harness.streamTask.runMailboxStep();

        harness.streamTask.notifyCheckpointCompleteAsync(1);
        harness.streamTask.runMailboxStep();
        assertEquals(1, ClosingOperator.notified.get());
        assertFalse(ClosingOperator.closed.get());

        // close operators directly, so that task is still fully running
        harness.streamTask.operatorChain.finishOperators(harness.streamTask.getActionExecutor());
        harness.streamTask.operatorChain.closeAllOperators();
        harness.streamTask.notifyCheckpointCompleteAsync(2);
        harness.streamTask.runMailboxStep();
        assertEquals(1, ClosingOperator.notified.get());
        assertTrue(ClosingOperator.closed.get());
    }

    @Test
    public void testFailToConfirmCheckpointCompleted() throws Exception {
        testFailToConfirmCheckpointMessage(
                streamTask -> streamTask.notifyCheckpointCompleteAsync(1L));
    }

    @Test
    public void testFailToConfirmCheckpointAborted() throws Exception {
        testFailToConfirmCheckpointMessage(
                streamTask -> streamTask.notifyCheckpointAbortAsync(1L, 0L));
    }

    private void testFailToConfirmCheckpointMessage(Consumer<StreamTask<?, ?>> consumer)
            throws Exception {
        StreamMap<Integer, Integer> streamMap =
                new StreamMap<>(new FailOnNotifyCheckpointMapper<>());
        StreamTaskMailboxTestHarnessBuilder<Integer> builder =
                new StreamTaskMailboxTestHarnessBuilder<>(
                                OneInputStreamTask::new, BasicTypeInfo.INT_TYPE_INFO)
                        .addInput(BasicTypeInfo.INT_TYPE_INFO);
        StreamTaskMailboxTestHarness<Integer> harness =
                builder.setupOutputForSingletonOperatorChain(streamMap).build();

        try {
            consumer.accept(harness.streamTask);
            harness.streamTask.runMailboxLoop();
            fail();
        } catch (ExpectedTestException expected) {
            // expected exceptionestProcessWithUnAvailableInput
        }
    }

    /**
     * Tests that checkpoints are declined if operators are (partially) closed.
     *
     * <p>See FLINK-16383.
     */
    @Test
    public void testCheckpointDeclinedOnClosedOperator() throws Throwable {
        ClosingOperator<Integer> operator = new ClosingOperator<>();
        StreamTaskMailboxTestHarnessBuilder<Integer> builder =
                new StreamTaskMailboxTestHarnessBuilder<>(
                                OneInputStreamTask::new, BasicTypeInfo.INT_TYPE_INFO)
                        .addInput(BasicTypeInfo.INT_TYPE_INFO);
        StreamTaskMailboxTestHarness<Integer> harness =
                builder.setupOutputForSingletonOperatorChain(operator).build();
        // keeps the mailbox from suspending
        harness.setAutoProcess(false);
        harness.processElement(new StreamRecord<>(1));

        harness.streamTask.operatorChain.finishOperators(harness.streamTask.getActionExecutor());
        harness.streamTask.operatorChain.closeAllOperators();
        assertTrue(ClosingOperator.closed.get());

        harness.streamTask.triggerCheckpointOnBarrier(
                new CheckpointMetaData(1, 0),
                CheckpointOptions.forCheckpointWithDefaultLocation(),
                new CheckpointMetricsBuilder());
        assertEquals(1, harness.getCheckpointResponder().getDeclineReports().size());
    }

    @Test
    public void testExecuteMailboxActionsAfterLeavingInputProcessorMailboxLoop() throws Exception {
        OneShotLatch latch = new OneShotLatch();
        try (MockEnvironment mockEnvironment = new MockEnvironmentBuilder().build()) {
            RunningTask<StreamTask<?, ?>> task =
                    runTask(
                            () ->
                                    new StreamTask<Object, StreamOperator<Object>>(
                                            mockEnvironment) {
                                        @Override
                                        protected void init() throws Exception {}

                                        @Override
                                        protected void processInput(
                                                MailboxDefaultAction.Controller controller)
                                                throws Exception {
                                            mailboxProcessor
                                                    .getMailboxExecutor(0)
                                                    .execute(latch::trigger, "trigger");
                                            controller.suspendDefaultAction();
                                            mailboxProcessor.suspend();
                                        }
                                    });
            latch.await();
            task.waitForTaskCompletion(false);
        }
    }

    /**
     * Tests that some StreamTask methods are called only in the main task's thread. Currently, the
     * main task's thread is the thread that creates the task.
     */
    @Test
    public void testThreadInvariants() throws Throwable {
        Configuration taskConfiguration = new Configuration();
        StreamConfig streamConfig = new StreamConfig(taskConfiguration);
        streamConfig.setStreamOperator(new StreamMap<>(value -> value));
        streamConfig.setOperatorID(new OperatorID());
        try (MockEnvironment mockEnvironment =
                new MockEnvironmentBuilder().setTaskConfiguration(taskConfiguration).build()) {

            ClassLoader taskClassLoader = new TestUserCodeClassLoader();

            RunningTask<ThreadInspectingTask> runningTask =
                    runTask(
                            () -> {
                                Thread.currentThread().setContextClassLoader(taskClassLoader);
                                return new ThreadInspectingTask(mockEnvironment);
                            });
            runningTask.invocationFuture.get();

            assertThat(
                    runningTask.streamTask.getTaskClassLoader(), is(sameInstance(taskClassLoader)));
        }
    }

    /**
     * This test ensures that {@link RecordWriter} is correctly closed even if we fail to construct
     * {@link OperatorChain}, for example because of user class deserialization error.
     */
    @Test
    public void testRecordWriterClosedOnStreamOperatorFactoryDeserializationError()
            throws Exception {
        Configuration taskConfiguration = new Configuration();
        StreamConfig streamConfig = new StreamConfig(taskConfiguration);
        streamConfig.setStreamOperatorFactory(new UnusedOperatorFactory());

        // Make sure that there is some output edge in the config so that some RecordWriter is
        // created
        StreamConfigChainer cfg =
                new StreamConfigChainer(new OperatorID(42, 42), streamConfig, this, 1);
        cfg.chain(
                new OperatorID(44, 44),
                new UnusedOperatorFactory(),
                StringSerializer.INSTANCE,
                StringSerializer.INSTANCE,
                false);
        cfg.finish();

        // Overwrite the serialized bytes to some garbage to induce deserialization exception
        taskConfiguration.setBytes(StreamConfig.SERIALIZEDUDF, new byte[42]);

        try (MockEnvironment mockEnvironment =
                new MockEnvironmentBuilder().setTaskConfiguration(taskConfiguration).build()) {

            mockEnvironment.addOutput(new ArrayList<>());
            StreamTask<String, TestSequentialReadingStreamOperator> streamTask =
                    new NoOpStreamTask<>(mockEnvironment);

            try {
                streamTask.invoke();
                fail("Should have failed with an exception!");
            } catch (Exception ex) {
                if (!ExceptionUtils.findThrowable(ex, StreamCorruptedException.class).isPresent()) {
                    throw ex;
                }
            }
        }

        assertTrue(
                RecordWriter.DEFAULT_OUTPUT_FLUSH_THREAD_NAME + " thread is still running",
                Thread.getAllStackTraces().keySet().stream()
                        .noneMatch(
                                thread ->
                                        thread.getName()
                                                .startsWith(
                                                        RecordWriter
                                                                .DEFAULT_OUTPUT_FLUSH_THREAD_NAME)));
    }

    @Test
    public void testProcessWithAvailableOutput() throws Exception {
        try (final MockEnvironment environment = setupEnvironment(true, true)) {
            final int numberOfProcessCalls = 10;
            final AvailabilityTestInputProcessor inputProcessor =
                    new AvailabilityTestInputProcessor(numberOfProcessCalls);
            final StreamTask task =
                    new MockStreamTaskBuilder(environment)
                            .setStreamInputProcessor(inputProcessor)
                            .build();

            task.invoke();
            assertEquals(numberOfProcessCalls, inputProcessor.currentNumProcessCalls);
        }
    }

    /**
     * In this weird construct, we are:
     *
     * <ul>
     *   <li>1. We start a thread, which will...
     *   <li>2. ... sleep for X ms, and enqueue another mail, that will...
     *   <li>3. ... sleep for Y ms, and make the output available again
     * </ul>
     *
     * <p>2nd step is to check that back pressure or idle counter is at least X. In the last 3rd
     * step, we test whether this counter was paused for the duration of processing mails.
     */
    private static class WaitingThread extends Thread {
        private final MailboxExecutor executor;
        private final RunnableWithException resumeTask;
        private final long sleepTimeInsideMail;
        private final long sleepTimeOutsideMail;
        private final TimerGauge sleepOutsideMailTimer;

        @Nullable private Exception asyncException;

        public WaitingThread(
                MailboxExecutor executor,
                RunnableWithException resumeTask,
                long sleepTimeInsideMail,
                long sleepTimeOutsideMail,
                TimerGauge sleepOutsideMailTimer) {
            this.executor = executor;
            this.resumeTask = resumeTask;
            this.sleepTimeInsideMail = sleepTimeInsideMail;
            this.sleepTimeOutsideMail = sleepTimeOutsideMail;
            this.sleepOutsideMailTimer = sleepOutsideMailTimer;
        }

        @Override
        public void run() {
            try {
                // Make sure that the Task thread actually starts measuring the backpressure before
                // we start the measured sleep. The WaitingThread is started from within the mailbox
                // so we should first wait until mailbox loop starts idling before we enter the
                // measured sleep
                while (!sleepOutsideMailTimer.isMeasuring()) {
                    Thread.sleep(1);
                }
                Thread.sleep(sleepTimeOutsideMail);
            } catch (InterruptedException e) {
                asyncException = e;
            }
            executor.submit(
                    () -> {
                        if (asyncException != null) {
                            throw asyncException;
                        }
                        Thread.sleep(sleepTimeInsideMail);
                        resumeTask.run();
                    },
                    "This task will complete the future to resume process input action.");
        }
    }

    @Test
    public void testProcessWithUnAvailableOutput() throws Exception {
        final long sleepTimeOutsideMail = 42;
        final long sleepTimeInsideMail = 44;

        @Nullable WaitingThread waitingThread = null;
        try (final MockEnvironment environment = setupEnvironment(true, false)) {
            final int numberOfProcessCalls = 10;
            final AvailabilityTestInputProcessor inputProcessor =
                    new AvailabilityTestInputProcessor(numberOfProcessCalls);
            final StreamTask task =
                    new MockStreamTaskBuilder(environment)
                            .setStreamInputProcessor(inputProcessor)
                            .build();
            final MailboxExecutor executor = task.mailboxProcessor.getMainMailboxExecutor();
            TaskIOMetricGroup ioMetricGroup =
                    task.getEnvironment().getMetricGroup().getIOMetricGroup();

            final RunnableWithException completeFutureTask =
                    () -> {
                        assertEquals(1, inputProcessor.currentNumProcessCalls);
                        assertTrue(task.mailboxProcessor.isDefaultActionUnavailable());
                        environment.getWriter(1).getAvailableFuture().complete(null);
                    };

            waitingThread =
                    new WaitingThread(
                            executor,
                            completeFutureTask,
                            sleepTimeInsideMail,
                            sleepTimeOutsideMail,
                            ioMetricGroup.getBackPressuredTimePerSecond());
            // Make sure WaitingThread is started after Task starts processing.
            executor.submit(
                    waitingThread::start,
                    "This task will submit another task to execute after processing input once.");

            long startTs = System.currentTimeMillis();

            task.invoke();
            long totalDuration = System.currentTimeMillis() - startTs;
            assertThat(
                    ioMetricGroup.getBackPressuredTimePerSecond().getCount(),
                    greaterThanOrEqualTo(sleepTimeOutsideMail));
            assertThat(
                    ioMetricGroup.getBackPressuredTimePerSecond().getCount(),
                    Matchers.lessThanOrEqualTo(totalDuration - sleepTimeInsideMail));
            assertThat(ioMetricGroup.getIdleTimeMsPerSecond().getCount(), is(0L));
            assertEquals(numberOfProcessCalls, inputProcessor.currentNumProcessCalls);
        } finally {
            if (waitingThread != null) {
                waitingThread.join();
            }
        }
    }

    @Test
    public void testProcessWithUnAvailableInput() throws Exception {
        final long sleepTimeOutsideMail = 42;
        final long sleepTimeInsideMail = 44;
        final int incomingDataSize = 10_000;

        @Nullable WaitingThread waitingThread = null;
        try (final MockEnvironment environment = setupEnvironment(true, true)) {
            final UnAvailableTestInputProcessor inputProcessor =
                    new UnAvailableTestInputProcessor();
            final StreamTask task =
                    new MockStreamTaskBuilder(environment)
                            .setStreamInputProcessor(inputProcessor)
                            .build();
            TaskIOMetricGroup ioMetricGroup =
                    task.getEnvironment().getMetricGroup().getIOMetricGroup();
            ThroughputCalculator throughputCalculator = environment.getThroughputMeter();

            final MailboxExecutor executor = task.mailboxProcessor.getMainMailboxExecutor();
            final RunnableWithException completeFutureTask =
                    () -> {
                        inputProcessor
                                .availabilityProvider
                                .getUnavailableToResetAvailable()
                                .complete(null);
                    };

            waitingThread =
                    new WaitingThread(
                            executor,
                            completeFutureTask,
                            sleepTimeInsideMail,
                            sleepTimeOutsideMail,
                            ioMetricGroup.getIdleTimeMsPerSecond());
            // Make sure WaitingThread is started after Task starts processing.
            executor.submit(
                    waitingThread::start,
                    "Start WaitingThread after Task starts processing input.");

            SystemClock clock = SystemClock.getInstance();

            long startTs = clock.relativeTimeMillis();
            throughputCalculator.incomingDataSize(incomingDataSize);
            task.invoke();
            long resultThroughput = throughputCalculator.calculateThroughput();
            long totalDuration = clock.relativeTimeMillis() - startTs;

            assertThat(
                    resultThroughput,
                    greaterThanOrEqualTo(
                            incomingDataSize * 1000 / (totalDuration - sleepTimeOutsideMail)));

            assertThat(
                    ioMetricGroup.getIdleTimeMsPerSecond().getCount(),
                    greaterThanOrEqualTo(sleepTimeOutsideMail));
            assertThat(
                    ioMetricGroup.getIdleTimeMsPerSecond().getCount(),
                    Matchers.lessThanOrEqualTo(totalDuration - sleepTimeInsideMail));
            assertThat(ioMetricGroup.getBackPressuredTimePerSecond().getCount(), is(0L));
        } finally {
            if (waitingThread != null) {
                waitingThread.join();
            }
        }
    }

    @Test
    public void testRestorePerformedOnlyOnce() throws Exception {
        // given: the operator with empty snapshot result (all state handles are null)
        OneInputStreamOperator<String, String> statelessOperator =
                streamOperatorWithSnapshot(new OperatorSnapshotFutures());
        DummyEnvironment dummyEnvironment = new DummyEnvironment();

        // when: Invoke the restore explicitly before launching the task.
        RunningTask<MockStreamTask> task =
                runTask(
                        () -> {
                            MockStreamTask mockStreamTask =
                                    createMockStreamTask(
                                            dummyEnvironment, operatorChain(statelessOperator));

                            mockStreamTask.restore();

                            return mockStreamTask;
                        });
        waitTaskIsRunning(task.streamTask, task.invocationFuture);

        task.streamTask.cancel();

        // then: 'restore' was called only once.
        assertThat(task.streamTask.restoreInvocationCount, is(1));
    }

    @Test
    public void testRestorePerformedFromInvoke() throws Exception {
        // given: the operator with empty snapshot result (all state handles are null)
        OneInputStreamOperator<String, String> statelessOperator =
                streamOperatorWithSnapshot(new OperatorSnapshotFutures());
        DummyEnvironment dummyEnvironment = new DummyEnvironment();

        // when: Launch the task.
        RunningTask<MockStreamTask> task =
                runTask(
                        () ->
                                createMockStreamTask(
                                        dummyEnvironment, operatorChain(statelessOperator)));

        waitTaskIsRunning(task.streamTask, task.invocationFuture);

        task.streamTask.cancel();

        // then: 'restore' was called even without explicit 'restore' invocation.
        assertThat(task.streamTask.restoreInvocationCount, is(1));
    }

    @Test
    public void testQuiesceOfMailboxRightBeforeSubmittingActionViaTimerService() throws Exception {
        // given: the stream task with configured handle async exception.
        AtomicBoolean submitThroughputFail = new AtomicBoolean();
        MockEnvironment mockEnvironment = new MockEnvironmentBuilder().build();

        final UnAvailableTestInputProcessor inputProcessor = new UnAvailableTestInputProcessor();
        RunningTask<StreamTask<?, ?>> task =
                runTask(
                        () ->
                                new MockStreamTaskBuilder(mockEnvironment)
                                        .setHandleAsyncException(
                                                (str, t) -> submitThroughputFail.set(true))
                                        .setStreamInputProcessor(inputProcessor)
                                        .build());

        waitTaskIsRunning(task.streamTask, task.invocationFuture);

        TimerService timerService = task.streamTask.systemTimerService;
        MailboxExecutor mainMailboxExecutor =
                task.streamTask.mailboxProcessor.getMainMailboxExecutor();

        CountDownLatch stoppingMailboxLatch = new CountDownLatch(1);
        timerService.registerTimer(
                timerService.getCurrentProcessingTime(),
                (time) -> {
                    stoppingMailboxLatch.await();
                    // The time to the start 'afterInvoke' inside of mailbox.
                    // 'afterInvoke' won't finish until this execution won't finish so it is
                    // impossible to wait on latch or something else.
                    Thread.sleep(5);
                    mainMailboxExecutor.submit(() -> {}, "test");
                });

        // when: Calling the quiesce for mailbox and finishing the timer service.
        mainMailboxExecutor
                .submit(
                        () -> {
                            stoppingMailboxLatch.countDown();
                            task.streamTask.afterInvoke();
                        },
                        "test")
                .get();

        // then: the exception handle wasn't invoked because the such situation is expected.
        assertFalse(submitThroughputFail.get());

        // Correctly shutdown the stream task to avoid hanging.
        inputProcessor.availabilityProvider.getUnavailableToResetAvailable().complete(null);
    }

    @Test
    public void testTaskAvoidHangingAfterSnapshotStateThrownException() throws Exception {
        // given: Configured SourceStreamTask with source which fails on checkpoint.
        Configuration taskManagerConfig = new Configuration();
        taskManagerConfig.setString(STATE_BACKEND, TestMemoryStateBackendFactory.class.getName());

        StreamConfig cfg = new StreamConfig(new Configuration());
        cfg.setStateKeySerializer(mock(TypeSerializer.class));
        cfg.setOperatorID(new OperatorID(4712L, 43L));

        FailedSource failedSource = new FailedSource();
        cfg.setStreamOperator(new TestStreamSource<String, FailedSource>(failedSource));
        cfg.setTimeCharacteristic(TimeCharacteristic.ProcessingTime);

        try (NettyShuffleEnvironment shuffleEnvironment =
                new NettyShuffleEnvironmentBuilder().build()) {
            Task task =
                    createTask(SourceStreamTask.class, shuffleEnvironment, cfg, taskManagerConfig);

            // when: Task starts
            task.startTaskThread();

            // wait for the task starts doing the work.
            failedSource.awaitRunning();

            // and: Checkpoint is triggered which should lead to exception in Source.
            task.triggerCheckpointBarrier(
                    42L, 1L, CheckpointOptions.forCheckpointWithDefaultLocation());

            // wait for clean termination.
            task.getExecutingThread().join();

            // then: The task doesn't hang but finished with FAILED state.
            assertEquals(ExecutionState.FAILED, task.getExecutionState());
        }
    }

    @Test
    public void testCleanUpResourcesWhenFailingDuringInit() throws Exception {
        // given: Configured SourceStreamTask with source which fails during initialization.
        StreamTaskMailboxTestHarnessBuilder<Integer> builder =
                new StreamTaskMailboxTestHarnessBuilder<>(
                                OneInputStreamTask::new, BasicTypeInfo.INT_TYPE_INFO)
                        .addInput(BasicTypeInfo.INT_TYPE_INFO);
        try {
            // when: The task initializing(restoring).
            builder.setupOutputForSingletonOperatorChain(new OpenFailingOperator<>()).build();
            fail("The task should fail during the restore");
        } catch (Exception ex) {
            // then: The task should throw exception from initialization.
            if (!ExceptionUtils.findThrowable(ex, ExpectedTestException.class).isPresent()) {
                throw ex;
            }
        }

        // then: The task should clean up all resources even when it failed on init.
        assertTrue(OpenFailingOperator.wasClosed);
    }

    @Test
    public void testRethrowExceptionFromRestoreInsideOfInvoke() throws Exception {
        // given: Configured SourceStreamTask with source which fails during initialization.
        StreamTaskMailboxTestHarnessBuilder<Integer> builder =
                new StreamTaskMailboxTestHarnessBuilder<>(
                                OneInputStreamTask::new, BasicTypeInfo.INT_TYPE_INFO)
                        .addInput(BasicTypeInfo.INT_TYPE_INFO);
        try {
            // when: The task invocation without preceded restoring.
            StreamTaskMailboxTestHarness<Integer> harness =
                    builder.setupOutputForSingletonOperatorChain(new OpenFailingOperator<>())
                            .buildUnrestored();

            harness.streamTask.invoke();

            fail("The task should fail during the restore");
        } catch (Exception ex) {
            // then: The task should rethrow exception from initialization.
            if (!ExceptionUtils.findThrowable(ex, ExpectedTestException.class).isPresent()) {
                throw ex;
            }
        }

        // and: The task should clean up all resources even when it failed on init.
        assertTrue(OpenFailingOperator.wasClosed);
    }

    @Test
    public void testCleanUpResourcesEvenWhenCancelTaskFails() throws Exception {
        // given: Configured StreamTask which fails during restoring and then inside of cancelTask.
        StreamTaskMailboxTestHarnessBuilder<Integer> builder =
                new StreamTaskMailboxTestHarnessBuilder<>(
                                (env) ->
                                        new OneInputStreamTask<String, Integer>(env) {
                                            @Override
                                            protected void cancelTask() {
                                                throw new RuntimeException("Cancel task exception");
                                            }
                                        },
                                BasicTypeInfo.INT_TYPE_INFO)
                        .addInput(BasicTypeInfo.INT_TYPE_INFO);
        try {
            // when: The task initializing(restoring).
            builder.setupOutputForSingletonOperatorChain(new OpenFailingOperator<>()).build();
            fail("The task should fail during the restore");
        } catch (Exception ex) {
            // then: The task should throw the original exception about the restore fail.
            if (!ExceptionUtils.findThrowable(ex, ExpectedTestException.class).isPresent()) {
                throw ex;
            }
        }

        // and: The task should clean up all resources even when cancelTask fails.
        assertTrue(OpenFailingOperator.wasClosed);
    }

    /**
     * This test checks the fact that throughput calculation is started automatically(just to be
     * sure that the scheduler is configured).
     */
    @Test
    public void testThroughputSchedulerStartsOnInvoke() throws Exception {
        CompletableFuture<?> finishFuture = new CompletableFuture<>();
        try (StreamTaskMailboxTestHarness<String> harness =
                new StreamTaskMailboxTestHarnessBuilder<>(OneInputStreamTask::new, STRING_TYPE_INFO)
                        .modifyStreamConfig(
                                config ->
                                        config.getConfiguration()
                                                .set(BUFFER_DEBLOAT_PERIOD, Duration.ofMillis(1)))
                        .addInput(STRING_TYPE_INFO)
                        .setupOutputForSingletonOperatorChain(
                                new TestBoundedOneInputStreamOperator())
                        .setThroughputMeter(
                                new ThroughputCalculator(SystemClock.getInstance(), 10) {
                                    @Override
                                    public long calculateThroughput() {
                                        finishFuture.complete(null);
                                        return super.calculateThroughput();
                                    }
                                })
                        .build()) {
            finishFuture.thenApply(
                    (value) -> {
                        harness.endInput();
                        return value;
                    });
            harness.streamTask.invoke();
        }
    }

    @Test
    public void testSkipRepeatCheckpointComplete() throws Exception {
        try (StreamTaskMailboxTestHarness<String> testHarness =
                new StreamTaskMailboxTestHarnessBuilder<>(
                                OneInputStreamTask::new, BasicTypeInfo.STRING_TYPE_INFO)
                        .addInput(BasicTypeInfo.STRING_TYPE_INFO, 3)
                        .modifyStreamConfig(
                                config -> {
                                    config.setCheckpointingEnabled(true);
                                    config.getConfiguration()
                                            .set(
                                                    ExecutionCheckpointingOptions
                                                            .ENABLE_CHECKPOINTS_AFTER_TASKS_FINISH,
                                                    true);
                                })
                        .setupOutputForSingletonOperatorChain(
                                new CheckpointCompleteRecordOperator())
                        .build()) {
            testHarness.streamTask.notifyCheckpointCompleteAsync(3);
            testHarness.streamTask.notifyCheckpointAbortAsync(5, 3);

            testHarness.streamTask.notifyCheckpointAbortAsync(10, 8);
            testHarness.streamTask.notifyCheckpointCompleteAsync(8);

            testHarness.processAll();

            CheckpointCompleteRecordOperator operator =
                    (CheckpointCompleteRecordOperator)
                            (AbstractStreamOperator<?>) testHarness.streamTask.getMainOperator();
            assertEquals(Arrays.asList(3L, 8L), operator.getNotifiedCheckpoint());
        }
    }

    @Test
    public void testBufferSizeRecalculationStartSuccessfully() throws Exception {
        CountDownLatch secondCalculationLatch = new CountDownLatch(2);
        int expectedThroughput = 13333;
        int inputChannels = 3;
        Consumer<StreamConfig> configuration =
                (config) -> {
                    config.getConfiguration().set(BUFFER_DEBLOAT_PERIOD, Duration.ofMillis(10));
                    config.getConfiguration().set(BUFFER_DEBLOAT_TARGET, Duration.ofSeconds(1));
                    config.getConfiguration().set(BUFFER_DEBLOAT_ENABLED, true);
                };
        SupplierWithException<StreamTask<?, ?>, Exception> testTaskFactory =
                () -> {
                    // given: Configured StreamTask with one input channel.
                    StreamTaskMailboxTestHarnessBuilder<String> builder =
                            new StreamTaskMailboxTestHarnessBuilder<>(
                                            OneInputStreamTask::new, STRING_TYPE_INFO)
                                    .modifyStreamConfig(configuration)
                                    .addInput(STRING_TYPE_INFO, inputChannels)
                                    .setupOutputForSingletonOperatorChain(
                                            new TestBoundedOneInputStreamOperator());
                    // and: The throughput meter with predictable calculation result.
                    StreamTaskMailboxTestHarness<String> harness =
                            builder.setThroughputMeter(
                                            new ThroughputCalculator(
                                                    SystemClock.getInstance(), 10) {
                                                @Override
                                                public long calculateThroughput() {
                                                    secondCalculationLatch.countDown();
                                                    return expectedThroughput;
                                                }
                                            })
                                    .build();
                    return harness.streamTask;
                };

        RunningTask<StreamTask<?, ?>> task = runTask(testTaskFactory);

        // when: The second throughput calculation happens
        secondCalculationLatch.await();

        // then: We can be sure the after the first throughput calculation the buffer size was
        // changed.
        for (InputGate inputGate : task.streamTask.getEnvironment().getAllInputGates()) {
            for (int i = 0; i < inputGate.getNumberOfInputChannels(); i++) {
                assertThat(
                        ((TestInputChannel) inputGate.getChannel(i)).getCurrentBufferSize(),
                        is(expectedThroughput / inputChannels));
            }
        }

        task.streamTask.cancel();
    }

    private MockEnvironment setupEnvironment(boolean... outputAvailabilities) {
        final Configuration configuration = new Configuration();
        new MockStreamConfig(configuration, outputAvailabilities.length);

        final List<ResultPartitionWriter> writers = new ArrayList<>(outputAvailabilities.length);
        for (int i = 0; i < outputAvailabilities.length; i++) {
            writers.add(new AvailabilityTestResultPartitionWriter(outputAvailabilities[i]));
        }

        final MockEnvironment environment =
                new MockEnvironmentBuilder().setTaskConfiguration(configuration).build();
        environment.addOutputs(writers);
        return environment;
    }

    // ------------------------------------------------------------------------
    //  Test Utilities
    // ------------------------------------------------------------------------

    private static <T> OneInputStreamOperator<T, T> streamOperatorWithSnapshot(
            OperatorSnapshotFutures operatorSnapshotResult) throws Exception {
        @SuppressWarnings("unchecked")
        OneInputStreamOperator<T, T> operator = mock(OneInputStreamOperator.class);
        when(operator.getOperatorID()).thenReturn(new OperatorID());

        when(operator.snapshotState(
                        anyLong(),
                        anyLong(),
                        any(CheckpointOptions.class),
                        any(CheckpointStreamFactory.class)))
                .thenReturn(operatorSnapshotResult);

        return operator;
    }

    private static <T> OneInputStreamOperator<T, T> streamOperatorWithSnapshotException(
            Exception exception) throws Exception {
        @SuppressWarnings("unchecked")
        OneInputStreamOperator<T, T> operator = mock(OneInputStreamOperator.class);
        when(operator.getOperatorID()).thenReturn(new OperatorID());

        when(operator.snapshotState(
                        anyLong(),
                        anyLong(),
                        any(CheckpointOptions.class),
                        any(CheckpointStreamFactory.class)))
                .thenThrow(exception);

        return operator;
    }

    private static <T> OperatorChain<T, AbstractStreamOperator<T>> operatorChain(
            OneInputStreamOperator<T, T>... streamOperators) throws Exception {
        return OperatorChainTest.setupOperatorChain(streamOperators);
    }

    private static class RunningTask<T extends StreamTask<?, ?>> {
        final T streamTask;
        final CompletableFuture<Void> invocationFuture;

        RunningTask(T streamTask, CompletableFuture<Void> invocationFuture) {
            this.streamTask = streamTask;
            this.invocationFuture = invocationFuture;
        }

        void waitForTaskCompletion(boolean cancelled) throws Exception {
            try {
                invocationFuture.get();
            } catch (Exception e) {
                if (cancelled) {
                    assertThat(e.getCause(), is(instanceOf(CancelTaskException.class)));
                } else {
                    throw e;
                }
            }
            assertThat(streamTask.isCanceled(), is(cancelled));
        }
    }

    private static <T extends StreamTask<?, ?>> RunningTask<T> runTask(
            SupplierWithException<T, Exception> taskFactory) throws Exception {
        CompletableFuture<T> taskCreationFuture = new CompletableFuture<>();
        CompletableFuture<Void> invocationFuture =
                CompletableFuture.runAsync(
                        () -> {
                            T task;
                            try {
                                task = taskFactory.get();
                                taskCreationFuture.complete(task);
                            } catch (Exception e) {
                                taskCreationFuture.completeExceptionally(e);
                                return;
                            }
                            try {
                                task.invoke();
                            } catch (RuntimeException e) {
                                throw e;
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        },
                        Executors.newSingleThreadExecutor());

        // Wait until task is created.
        return new RunningTask<>(taskCreationFuture.get(), invocationFuture);
    }

    /**
     * Operator that does nothing.
     *
     * @param <T>
     * @param <OP>
     */
    public static class NoOpStreamTask<T, OP extends StreamOperator<T>> extends StreamTask<T, OP> {

        public NoOpStreamTask(Environment environment) throws Exception {
            super(environment);
        }

        @Override
        protected void init() throws Exception {
            inputProcessor = new EmptyInputProcessor();
        }

        @Override
        protected void cleanup() throws Exception {}
    }

    /**
     * A stream input processor implementation used to control the returned input status based on
     * the total number of processing calls.
     */
    private static class AvailabilityTestInputProcessor implements StreamInputProcessor {
        private final int totalProcessCalls;
        private int currentNumProcessCalls;

        AvailabilityTestInputProcessor(int totalProcessCalls) {
            this.totalProcessCalls = totalProcessCalls;
        }

        @Override
        public DataInputStatus processInput() {
            return ++currentNumProcessCalls < totalProcessCalls
                    ? DataInputStatus.MORE_AVAILABLE
                    : DataInputStatus.END_OF_INPUT;
        }

        @Override
        public CompletableFuture<Void> prepareSnapshot(
                ChannelStateWriter channelStateWriter, final long checkpointId)
                throws CheckpointException {
            return FutureUtils.completedVoidFuture();
        }

        @Override
        public void close() throws IOException {}

        @Override
        public CompletableFuture<?> getAvailableFuture() {
            return AVAILABLE;
        }
    }

    /**
     * A stream input processor implementation with input unavailable for a specified amount of
     * time, after which processor is closing.
     */
    private static class UnAvailableTestInputProcessor implements StreamInputProcessor {
        private final AvailabilityHelper availabilityProvider = new AvailabilityHelper();

        @Override
        public DataInputStatus processInput() {
            return availabilityProvider.isAvailable()
                    ? DataInputStatus.END_OF_INPUT
                    : DataInputStatus.NOTHING_AVAILABLE;
        }

        @Override
        public CompletableFuture<Void> prepareSnapshot(
                ChannelStateWriter channelStateWriter, final long checkpointId)
                throws CheckpointException {
            return FutureUtils.completedVoidFuture();
        }

        @Override
        public void close() throws IOException {}

        @Override
        public CompletableFuture<?> getAvailableFuture() {
            return availabilityProvider.getAvailableFuture();
        }
    }

    private static class BlockingFinishStreamOperator extends AbstractStreamOperator<Void> {
        private static final long serialVersionUID = -9042150529568008847L;

        private static volatile OneShotLatch inClose;
        private static volatile OneShotLatch finishClose;

        @Override
        public void finish() throws Exception {
            checkLatches();
            inClose.trigger();
            finishClose.await();
            super.close();
        }

        private void checkLatches() {
            Preconditions.checkNotNull(inClose);
            Preconditions.checkNotNull(finishClose);
        }

        private static void resetLatches() {
            inClose = new OneShotLatch();
            finishClose = new OneShotLatch();
        }
    }

    public static Task createTask(
            Class<? extends AbstractInvokable> invokable,
            ShuffleEnvironment shuffleEnvironment,
            StreamConfig taskConfig,
            Configuration taskManagerConfig)
            throws Exception {

        return new TestTaskBuilder(shuffleEnvironment)
                .setTaskManagerConfig(taskManagerConfig)
                .setInvokable(invokable)
                .setTaskConfig(taskConfig.getConfiguration())
                .build();
    }

    // ------------------------------------------------------------------------
    //  Test operators
    // ------------------------------------------------------------------------

    private static class SlowlyDeserializingOperator
            extends StreamSource<Long, SourceFunction<Long>> {
        private static final long serialVersionUID = 1L;

        private volatile boolean canceled = false;

        public SlowlyDeserializingOperator() {
            super(new MockSourceFunction());
        }

        @Override
        public void run(
                Object lockingObject,
                Output<StreamRecord<Long>> collector,
                OperatorChain<?, ?> operatorChain)
                throws Exception {
            while (!canceled) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                }
            }
        }

        @Override
        public void cancel() {
            canceled = true;
        }

        // slow deserialization
        private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
            in.defaultReadObject();

            long delay = 500;
            long deadline = System.currentTimeMillis() + delay;
            do {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ignored) {
                }
            } while ((delay = deadline - System.currentTimeMillis()) > 0);
        }
    }

    private static class MockSourceFunction implements SourceFunction<Long> {
        private static final long serialVersionUID = 1L;

        @Override
        public void run(SourceContext<Long> ctx) {}

        @Override
        public void cancel() {}
    }

    /**
     * Mocked state backend factory which returns mocks for the operator and keyed state backends.
     */
    public static final class TestMemoryStateBackendFactory
            implements StateBackendFactory<AbstractStateBackend> {
        private static final long serialVersionUID = 1L;

        @Override
        public AbstractStateBackend createFromConfig(
                ReadableConfig config, ClassLoader classLoader) {
            return new TestSpyWrapperStateBackend(createInnerBackend(config));
        }

        protected MemoryStateBackend createInnerBackend(ReadableConfig config) {
            return new MemoryStateBackend();
        }
    }

    // ------------------------------------------------------------------------
    // ------------------------------------------------------------------------

    private static class MockStreamTask extends StreamTask<String, AbstractStreamOperator<String>> {

        private final OperatorChain<String, AbstractStreamOperator<String>> overrideOperatorChain;
        private int restoreInvocationCount = 0;

        MockStreamTask(
                Environment env,
                OperatorChain<String, AbstractStreamOperator<String>> operatorChain,
                Thread.UncaughtExceptionHandler uncaughtExceptionHandler)
                throws Exception {
            super(env, null, uncaughtExceptionHandler);
            this.overrideOperatorChain = operatorChain;
        }

        @Override
        public void executeRestore() throws Exception {
            super.executeRestore();
            restoreInvocationCount++;
        }

        @Override
        protected void init() {
            // The StreamTask initializes operatorChain first on it's own in `invoke()` method.
            // Later it calls the `init()` method before actual `run()`, so we are overriding the
            // operatorChain
            // here for test purposes.
            super.operatorChain = this.overrideOperatorChain;
            super.mainOperator = super.operatorChain.getMainOperator();
            super.inputProcessor = new EmptyInputProcessor(false);
        }

        void finishInput() {
            checkState(
                    inputProcessor != null,
                    "Tried to finishInput before MockStreamTask was started");
            ((EmptyInputProcessor) inputProcessor).finishInput();
        }
    }

    private static class EmptyInputProcessor implements StreamInputProcessor {
        private volatile boolean isFinished;

        public EmptyInputProcessor() {
            this(true);
        }

        public EmptyInputProcessor(boolean startFinished) {
            isFinished = startFinished;
        }

        @Override
        public DataInputStatus processInput() throws Exception {
            return isFinished ? DataInputStatus.END_OF_INPUT : DataInputStatus.NOTHING_AVAILABLE;
        }

        @Override
        public CompletableFuture<Void> prepareSnapshot(
                ChannelStateWriter channelStateWriter, long checkpointId)
                throws CheckpointException {
            return FutureUtils.completedVoidFuture();
        }

        @Override
        public void close() throws IOException {}

        @Override
        public CompletableFuture<?> getAvailableFuture() {
            return AVAILABLE;
        }

        public void finishInput() {
            isFinished = true;
        }
    }

    private static MockStreamTask createMockStreamTask(
            Environment env, OperatorChain<String, AbstractStreamOperator<String>> operatorChain)
            throws Exception {
        return new MockStreamTask(env, operatorChain, FatalExitExceptionHandler.INSTANCE);
    }

    /**
     * Source that instantiates the operator state backend and the keyed state backend. The created
     * state backends can be retrieved from the static fields to check if the CloseableRegistry
     * closed them correctly.
     */
    public static class StateBackendTestSource
            extends StreamTask<Long, StreamSource<Long, SourceFunction<Long>>> {

        private static volatile boolean fail;

        public StateBackendTestSource(Environment env) throws Exception {
            super(env);
        }

        @Override
        protected void init() throws Exception {}

        @Override
        protected void processInput(MailboxDefaultAction.Controller controller) throws Exception {
            if (fail) {
                throw new RuntimeException();
            }
            controller.suspendDefaultAction();
            mailboxProcessor.suspend();
        }

        @Override
        protected void cleanup() throws Exception {}

        @Override
        public StreamTaskStateInitializer createStreamTaskStateInitializer() {
            final StreamTaskStateInitializer streamTaskStateManager =
                    super.createStreamTaskStateInitializer();
            return (operatorID,
                    operatorClassName,
                    processingTimeService,
                    keyContext,
                    keySerializer,
                    closeableRegistry,
                    metricGroup,
                    fraction,
                    isUsingCustomRawKeyedState) -> {
                final StreamOperatorStateContext controller =
                        streamTaskStateManager.streamOperatorStateContext(
                                operatorID,
                                operatorClassName,
                                processingTimeService,
                                keyContext,
                                keySerializer,
                                closeableRegistry,
                                metricGroup,
                                fraction,
                                isUsingCustomRawKeyedState);

                return new StreamOperatorStateContext() {
                    @Override
                    public boolean isRestored() {
                        return controller.isRestored();
                    }

                    @Override
                    public OperatorStateBackend operatorStateBackend() {
                        return controller.operatorStateBackend();
                    }

                    @Override
                    public CheckpointableKeyedStateBackend<?> keyedStateBackend() {
                        return controller.keyedStateBackend();
                    }

                    @Override
                    public InternalTimeServiceManager<?> internalTimerServiceManager() {
                        InternalTimeServiceManager<?> timeServiceManager =
                                controller.internalTimerServiceManager();
                        return timeServiceManager != null ? spy(timeServiceManager) : null;
                    }

                    @Override
                    public CloseableIterable<StatePartitionStreamProvider>
                            rawOperatorStateInputs() {
                        return replaceWithSpy(controller.rawOperatorStateInputs());
                    }

                    @Override
                    public CloseableIterable<KeyGroupStatePartitionStreamProvider>
                            rawKeyedStateInputs() {
                        return replaceWithSpy(controller.rawKeyedStateInputs());
                    }

                    public <T extends Closeable> T replaceWithSpy(T closeable) {
                        T spyCloseable = spy(closeable);
                        if (closeableRegistry.unregisterCloseable(closeable)) {
                            try {
                                closeableRegistry.registerCloseable(spyCloseable);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }
                        return spyCloseable;
                    }
                };
            };
        }
    }

    private static class ThreadInspectingTask
            extends StreamTask<String, AbstractStreamOperator<String>> {

        private final long taskThreadId;
        private final ClassLoader taskClassLoader;

        /** Flag to wait until time trigger has been called. */
        private transient boolean hasTimerTriggered;

        ThreadInspectingTask(Environment env) throws Exception {
            super(env);
            Thread currentThread = Thread.currentThread();
            taskThreadId = currentThread.getId();
            taskClassLoader = currentThread.getContextClassLoader();
        }

        @Nullable
        ClassLoader getTaskClassLoader() {
            return taskClassLoader;
        }

        @Override
        protected void init() throws Exception {
            checkTaskThreadInfo();

            // Create a time trigger to validate that it would also be invoked in the task's thread.
            getMainOperator()
                    .getProcessingTimeService()
                    .registerTimer(
                            0,
                            new ProcessingTimeCallback() {
                                @Override
                                public void onProcessingTime(long timestamp) throws Exception {
                                    checkTaskThreadInfo();
                                    hasTimerTriggered = true;
                                }
                            });
        }

        @Override
        protected void processInput(MailboxDefaultAction.Controller controller) throws Exception {
            checkTaskThreadInfo();
            if (hasTimerTriggered) {
                controller.suspendDefaultAction();
                mailboxProcessor.suspend();
            }
        }

        @Override
        protected void cleanup() throws Exception {
            checkTaskThreadInfo();
        }

        private void checkTaskThreadInfo() {
            Thread currentThread = Thread.currentThread();
            Preconditions.checkState(
                    taskThreadId == currentThread.getId(),
                    "Task's method was called in non task thread.");
            Preconditions.checkState(
                    taskClassLoader == currentThread.getContextClassLoader(),
                    "Task's controller class loader has been changed during invocation.");
        }
    }

    /**
     * A {@link ClassLoader} that delegates everything to {@link
     * ClassLoader#getSystemClassLoader()}.
     */
    private static class TestUserCodeClassLoader extends ClassLoader {
        public TestUserCodeClassLoader() {
            super(ClassLoader.getSystemClassLoader());
        }
    }

    // ------------------------------------------------------------------------
    // ------------------------------------------------------------------------

    static class TestStreamSource<OUT, SRC extends SourceFunction<OUT>>
            extends StreamSource<OUT, SRC> {

        static AbstractKeyedStateBackend<?> keyedStateBackend;
        static OperatorStateBackend operatorStateBackend;
        static CloseableIterable<StatePartitionStreamProvider> rawOperatorStateInputs;
        static CloseableIterable<KeyGroupStatePartitionStreamProvider> rawKeyedStateInputs;

        public TestStreamSource(SRC sourceFunction) {
            super(sourceFunction);
        }

        @Override
        public void initializeState(StateInitializationContext controller) throws Exception {
            keyedStateBackend = (AbstractKeyedStateBackend<?>) getKeyedStateBackend();
            operatorStateBackend = getOperatorStateBackend();
            rawOperatorStateInputs =
                    (CloseableIterable<StatePartitionStreamProvider>)
                            controller.getRawOperatorStateInputs();
            rawKeyedStateInputs =
                    (CloseableIterable<KeyGroupStatePartitionStreamProvider>)
                            controller.getRawKeyedStateInputs();
            super.initializeState(controller);
        }
    }

    private static class TestingKeyedStateHandle implements KeyedStateHandle {

        private static final long serialVersionUID = -2473861305282291582L;

        private final transient CompletableFuture<Void> discardFuture = new CompletableFuture<>();

        public CompletableFuture<Void> getDiscardFuture() {
            return discardFuture;
        }

        @Override
        public KeyGroupRange getKeyGroupRange() {
            return KeyGroupRange.EMPTY_KEY_GROUP_RANGE;
        }

        @Override
        public TestingKeyedStateHandle getIntersection(KeyGroupRange keyGroupRange) {
            return this;
        }

        @Override
        public void registerSharedStates(SharedStateRegistry stateRegistry) {}

        @Override
        public void discardState() {
            discardFuture.complete(null);
        }

        @Override
        public long getStateSize() {
            return 0L;
        }
    }

    private static class TestingOperatorStateHandle implements OperatorStateHandle {

        private static final long serialVersionUID = 923794934187614088L;

        private final transient CompletableFuture<Void> discardFuture = new CompletableFuture<>();

        public CompletableFuture<Void> getDiscardFuture() {
            return discardFuture;
        }

        @Override
        public Map<String, StateMetaInfo> getStateNameToPartitionOffsets() {
            return Collections.emptyMap();
        }

        @Override
        public FSDataInputStream openInputStream() throws IOException {
            throw new IOException("Cannot open input streams in testing implementation.");
        }

        @Override
        public Optional<byte[]> asBytesIfInMemory() {
            return Optional.empty();
        }

        @Override
        public StreamStateHandle getDelegateStateHandle() {
            throw new UnsupportedOperationException("Not implemented.");
        }

        @Override
        public void discardState() throws Exception {
            discardFuture.complete(null);
        }

        @Override
        public long getStateSize() {
            return 0L;
        }
    }

    private static class AcknowledgeDummyEnvironment extends DummyEnvironment {

        private final CompletableFuture<Long> acknowledgeCheckpointFuture =
                new CompletableFuture<>();

        public CompletableFuture<Long> getAcknowledgeCheckpointFuture() {
            return acknowledgeCheckpointFuture;
        }

        @Override
        public void acknowledgeCheckpoint(long checkpointId, CheckpointMetrics checkpointMetrics) {
            acknowledgeCheckpointFuture.complete(checkpointId);
        }

        @Override
        public void acknowledgeCheckpoint(
                long checkpointId,
                CheckpointMetrics checkpointMetrics,
                TaskStateSnapshot subtaskState) {
            acknowledgeCheckpointFuture.complete(checkpointId);
        }
    }

    private static final class BlockingRunnableFuture<V> implements RunnableFuture<V> {

        private final CompletableFuture<V> future = new CompletableFuture<>();

        private final OneShotLatch signalRunLatch = new OneShotLatch();

        private final CountDownLatch continueRunLatch;

        private final V value;

        private BlockingRunnableFuture(int parties, V value) {
            this.continueRunLatch = new CountDownLatch(parties);
            this.value = value;
        }

        @Override
        public void run() {
            signalRunLatch.trigger();
            continueRunLatch.countDown();

            try {
                // poor man's barrier because it can happen that the async operations thread gets
                // interrupted by the mail box thread. The CyclicBarrier would in this case fail
                // all participants of the barrier, leaving the future uncompleted
                continueRunLatch.await();
            } catch (InterruptedException e) {
                ExceptionUtils.rethrow(e);
            }

            future.complete(value);
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return future.isCancelled();
        }

        @Override
        public boolean isDone() {
            return future.isDone();
        }

        @Override
        public V get() throws InterruptedException, ExecutionException {
            return future.get();
        }

        @Override
        public V get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            return future.get(timeout, unit);
        }

        void awaitRun() throws InterruptedException {
            signalRunLatch.await();
        }
    }

    private static class FailingDummyEnvironment extends DummyEnvironment {

        final RuntimeException failingCause;

        private FailingDummyEnvironment(RuntimeException failingCause) {
            this.failingCause = failingCause;
        }

        @Override
        public void declineCheckpoint(long checkpointId, CheckpointException cause) {
            throw failingCause;
        }

        @Override
        public void failExternally(Throwable cause) {
            throw failingCause;
        }
    }

    private static class UnusedOperatorFactory extends AbstractStreamOperatorFactory<String> {
        @Override
        public <T extends StreamOperator<String>> T createStreamOperator(
                StreamOperatorParameters<String> parameters) {
            throw new UnsupportedOperationException("This shouldn't be called");
        }

        @Override
        public void setChainingStrategy(ChainingStrategy strategy) {}

        @Override
        public Class<? extends StreamOperator> getStreamOperatorClass(ClassLoader classLoader) {
            throw new UnsupportedOperationException();
        }
    }

    private static class ClosingOperator<T> extends AbstractStreamOperator<T>
            implements OneInputStreamOperator<T, T> {
        static AtomicBoolean closed = new AtomicBoolean();
        static AtomicInteger notified = new AtomicInteger();

        @Override
        public void open() throws Exception {
            super.open();
            closed.set(false);
            notified.set(0);
        }

        @Override
        public void close() throws Exception {
            closed.set(true);
            super.close();
        }

        @Override
        public void notifyCheckpointComplete(long checkpointId) throws Exception {
            super.notifyCheckpointComplete(checkpointId);
            notified.incrementAndGet();
        }

        @Override
        public void processElement(StreamRecord<T> element) throws Exception {}
    }

    private static class FailOnNotifyCheckpointMapper<T>
            implements MapFunction<T, T>, CheckpointListener {
        private static final long serialVersionUID = 1L;

        @Override
        public T map(T value) throws Exception {
            return value;
        }

        @Override
        public void notifyCheckpointAborted(long checkpointId) {
            throw new ExpectedTestException();
        }

        @Override
        public void notifyCheckpointComplete(long checkpointId) {
            throw new ExpectedTestException();
        }
    }

    private static class FailedSource extends RichParallelSourceFunction<String>
            implements CheckpointedFunction {
        private static CountDownLatch runningLatch = null;

        private volatile boolean running;

        public FailedSource() {
            runningLatch = new CountDownLatch(1);
        }

        @Override
        public void open(Configuration parameters) throws Exception {
            running = true;
        }

        @Override
        public void run(SourceContext<String> ctx) throws Exception {
            runningLatch.countDown();
            while (running) {
                try {
                    Thread.sleep(Integer.MAX_VALUE);
                } catch (InterruptedException ignore) {
                    // ignore
                }
            }
        }

        @Override
        public void cancel() {
            running = false;
        }

        @Override
        public void snapshotState(FunctionSnapshotContext context) throws Exception {
            if (runningLatch.getCount() == 0) {
                throw new RuntimeException("source failed");
            }
        }

        @Override
        public void initializeState(FunctionInitializationContext context) throws Exception {}

        public void awaitRunning() throws InterruptedException {
            runningLatch.await();
        }
    }

    static class OpenFailingOperator<T> extends AbstractStreamOperator<T>
            implements OneInputStreamOperator<T, T> {
        static boolean wasClosed;

        public OpenFailingOperator() {
            wasClosed = false;
        }

        @Override
        public void open() throws Exception {
            throw new ExpectedTestException();
        }

        @Override
        public void close() throws Exception {
            wasClosed = true;
        }

        @Override
        public void processElement(StreamRecord<T> element) throws Exception {}
    }

    /**
     * A {@link StreamTask} that register a single timer that waits for a cancellation and then
     * emits some data. The assumption is that output remains available until the future returned
     * from {@link AbstractInvokable#cancel()} is completed. Public * access to allow reflection in
     * {@link Task}.
     */
    public static class StreamTaskWithBlockingTimer extends StreamTask {
        static volatile CompletableFuture<Void> timerStarted;
        static volatile CompletableFuture<Void> timerFinished;
        static volatile CompletableFuture<Void> invokableCancelled;

        public static void reset() {
            timerStarted = new CompletableFuture<>();
            timerFinished = new CompletableFuture<>();
            invokableCancelled = new CompletableFuture<>();
        }

        // public access to allow reflection in Task
        public StreamTaskWithBlockingTimer(Environment env) throws Exception {
            super(env);
            super.inputProcessor = getInputProcessor();
            getProcessingTimeServiceFactory()
                    .createProcessingTimeService(mainMailboxExecutor)
                    .registerTimer(0, unused -> onProcessingTime());
        }

        @Override
        protected void cancelTask() throws Exception {
            super.cancelTask();
            invokableCancelled.complete(null);
        }

        private void onProcessingTime() {
            try {
                timerStarted.complete(null);
                waitForCancellation();
                emit();
                timerFinished.complete(null);
            } catch (Throwable e) { // assertion is Error
                timerFinished.completeExceptionally(e);
            }
        }

        private void waitForCancellation() {
            invokableCancelled.join();
            // allow network resources to be closed mistakenly
            for (int i = 0; i < 10; i++) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    // ignore: can be interrupted by TaskCanceller/Interrupter
                }
            }
        }

        private void emit() throws IOException {
            checkState(getEnvironment().getAllWriters().length > 0);
            for (ResultPartitionWriter writer : getEnvironment().getAllWriters()) {
                assertFalse(writer.isReleased());
                assertFalse(writer.isFinished());
                writer.emitRecord(ByteBuffer.allocate(10), 0);
            }
        }

        @Override
        protected void init() {}

        private static StreamInputProcessor getInputProcessor() {
            return new StreamInputProcessor() {

                @Override
                public DataInputStatus processInput() {
                    return DataInputStatus.NOTHING_AVAILABLE;
                }

                @Override
                public CompletableFuture<Void> prepareSnapshot(
                        ChannelStateWriter channelStateWriter, long checkpointId) {
                    return CompletableFuture.completedFuture(null);
                }

                @Override
                public CompletableFuture<?> getAvailableFuture() {
                    return new CompletableFuture<>();
                }

                @Override
                public void close() {}
            };
        }
    }

    private static class CheckpointCompleteRecordOperator extends AbstractStreamOperator<Integer>
            implements OneInputStreamOperator<Integer, Integer> {

        private final List<Long> notifiedCheckpoint = new ArrayList<>();

        @Override
        public void processElement(StreamRecord<Integer> element) throws Exception {}

        @Override
        public void notifyCheckpointComplete(long checkpointId) throws Exception {
            notifiedCheckpoint.add(checkpointId);
        }

        public List<Long> getNotifiedCheckpoint() {
            return notifiedCheckpoint;
        }
    }
}
