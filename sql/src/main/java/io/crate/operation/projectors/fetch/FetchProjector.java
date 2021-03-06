/*
 * Licensed to Crate under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.  Crate licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial
 * agreement.
 */

package io.crate.operation.projectors.fetch;

import com.carrotsearch.hppc.IntContainer;
import com.carrotsearch.hppc.IntObjectHashMap;
import com.carrotsearch.hppc.IntObjectMap;
import com.carrotsearch.hppc.IntSet;
import com.carrotsearch.hppc.cursors.IntCursor;
import com.carrotsearch.hppc.cursors.IntObjectCursor;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import io.crate.analyze.symbol.Symbol;
import io.crate.core.collections.Bucket;
import io.crate.core.collections.Row;
import io.crate.metadata.Functions;
import io.crate.operation.Input;
import io.crate.operation.InputRow;
import io.crate.operation.fetch.FetchRowInputSymbolVisitor;
import io.crate.operation.projectors.AbstractProjector;
import io.crate.operation.projectors.Requirement;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.util.concurrent.AbstractRunnable;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class FetchProjector extends AbstractProjector {


    private final FetchProjectorContext context;
    private final FetchOperation fetchOperation;

    enum Stage {
        INIT,
        COLLECT,
        FETCH,
        FINALIZE
    }

    private final AtomicReference<Stage> stage = new AtomicReference<>(Stage.INIT);
    private final Object failureLock = new Object();

    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final FetchRowInputSymbolVisitor.Context collectRowContext;

    // TODO: add an estimate to the constructor
    private final ArrayList<Object[]> inputValues = new ArrayList<>();
    private final Executor resultExecutor;

    private final Row outputRow;
    private final AtomicInteger remainingRequests = new AtomicInteger(0);

    private static final ESLogger LOGGER = Loggers.getLogger(FetchProjector.class);

    /**
     * An array backed row, which returns the inner array upon materialize
     */
    public static class ArrayBackedRow implements Row {

        private Object[] cells;

        @Override
        public int size() {
            return cells.length;
        }

        @Override
        public Object get(int index) {
            assert cells != null;
            return cells[index];
        }

        @Override
        public Object[] materialize() {
            return cells;
        }
    }

    public FetchProjector(FetchOperation fetchOperation,
                          Executor resultExecutor,
                          Functions functions,
                          List<Symbol> outputSymbols,
                          FetchProjectorContext fetchProjectorContext) {
        this.fetchOperation = fetchOperation;
        this.context = fetchProjectorContext;
        this.resultExecutor = resultExecutor;

        FetchRowInputSymbolVisitor rowInputSymbolVisitor = new FetchRowInputSymbolVisitor(functions);
        this.collectRowContext = new FetchRowInputSymbolVisitor.Context(fetchProjectorContext.tableToFetchSource);

        List<Input<?>> inputs = new ArrayList<>(outputSymbols.size());
        for (Symbol symbol : outputSymbols) {
            inputs.add(rowInputSymbolVisitor.process(symbol, collectRowContext));
        }
        outputRow = new InputRow(inputs);
    }

    private boolean nextStage(Stage from, Stage to) {
        synchronized (failureLock) {
            if (failIfNeeded()) return true;
            Stage was = stage.getAndSet(to);
            assert was == from : "wrong state switch " + from + "/" + to + " was " + was;
        }
        return false;
    }

    @Override
    public void prepare() {
        assert stage.get() == Stage.INIT;
        nextStage(Stage.INIT, Stage.COLLECT);
    }

    @Override
    public boolean setNextRow(Row row) {
        Object[] cells = row.materialize();
        collectRowContext.inputRow().cells = cells;
        for (int i : collectRowContext.docIdPositions()) {
            context.require((long) cells[i]);
        }
        inputValues.add(cells);
        return true;
    }

    private void sendRequests() {
        synchronized (failureLock) {
            remainingRequests.set(context.nodeToReaderIds.size());
        }
        boolean anyRequestSent = false;
        for (Map.Entry<String, IntSet> entry : context.nodeToReaderIds.entrySet()) {

            IntObjectHashMap<IntContainer> toFetch = generateToFetch(entry);
            final String nodeId = entry.getKey();
            ListenableFuture<IntObjectMap<? extends Bucket>> future = fetchOperation.fetch(nodeId, toFetch);
            anyRequestSent = true;

            Futures.addCallback(future, new FutureCallback<IntObjectMap<? extends Bucket>>() {
                @Override
                public void onSuccess(@Nullable IntObjectMap<? extends Bucket> result) {
                    if (result != null) {
                        for (IntObjectCursor<? extends Bucket> cursor : result) {
                            ReaderBucket readerBucket = context.getReaderBucket(cursor.key);
                            readerBucket.fetched(cursor.value);
                        }
                    }
                    if (remainingRequests.decrementAndGet() == 0) {
                        resultExecutor.execute(new AbstractRunnable() {
                            @Override
                            public void onFailure(Throwable t) {
                                fail(t);
                            }

                            @Override
                            protected void doRun() throws Exception {
                                fetchFinished();
                            }
                        });
                    }
                }

                @Override
                public void onFailure(@Nonnull Throwable t) {
                    LOGGER.error("NodeFetchRequest failed on node {}", t, nodeId);
                    remainingRequests.decrementAndGet();
                    fail(t);
                }
            });
        }
        if (!anyRequestSent) {
            fetchFinished();
        }
    }

    private IntObjectHashMap<IntContainer> generateToFetch(Map.Entry<String, IntSet> entry) {
        IntObjectHashMap<IntContainer> toFetch = new IntObjectHashMap<>(entry.getValue().size());
        for (IntCursor readerIdCursor : entry.getValue()) {
            ReaderBucket readerBucket = context.readerBucket(readerIdCursor.value);
            if (readerBucket != null && readerBucket.fetchRequired() && readerBucket.docs.size() > 0) {
                toFetch.put(readerIdCursor.value, readerBucket.docs.keys());
            }
        }
        return toFetch;
    }

    private void fetchFinished() {
        if (nextStage(Stage.FETCH, Stage.FINALIZE)) {
            return;
        }
        final ArrayBackedRow inputRow = collectRowContext.inputRow();
        final ArrayBackedRow[] fetchRows = collectRowContext.fetchRows();
        final ArrayBackedRow[] partitionRows = collectRowContext.partitionRows();
        final int[] docIdPositions = collectRowContext.docIdPositions();

        for (Object[] cells : inputValues) {
            inputRow.cells = cells;
            for (int i = 0; i < docIdPositions.length; i++) {
                long doc = (long) cells[docIdPositions[i]];
                int readerId = (int) (doc >> 32);
                int docId = (int) (long) doc;
                ReaderBucket readerBucket = context.getReaderBucket(readerId);
                assert readerBucket != null;
                setPartitionRow(partitionRows, i, readerBucket);
                fetchRows[i].cells = readerBucket.get(docId);
                assert !readerBucket.fetchRequired() || fetchRows[i].cells != null;
            }
            downstream.setNextRow(outputRow);
        }
        finishDownstream();
    }

    private void setPartitionRow(ArrayBackedRow[] partitionRows, int i, ReaderBucket readerBucket) {
        // TODO: could be improved by handling non partitioned requests differently
        if (partitionRows != null && partitionRows[i] != null) {
            assert readerBucket.partitionValues != null;
            partitionRows[i].cells = readerBucket.partitionValues;
        }
    }

    @Override
    public void finish() {
        if (nextStage(Stage.COLLECT, Stage.FETCH)) {
            return;
        }
        sendRequests();
    }

    private boolean failIfNeeded() {
        Throwable t = failure.get();
        if (t != null) {
            downstream.fail(t);
            return true;
        }
        return false;
    }

    private void finishDownstream() {
        if (failIfNeeded()) {
            return;
        }
        downstream.finish();
    }

    @Override
    public void fail(Throwable throwable) {
        synchronized (failureLock) {
            boolean first = failure.compareAndSet(null, throwable);
            switch (stage.get()) {
                case INIT:
                    throw new IllegalStateException("Shouldn't call fail on projection if projection hasn't been prepared");
                case COLLECT:
                    if (first) {
                        sendRequests();
                        return;
                    }
                case FETCH:
                    if (remainingRequests.get() > 0) return;
            }
        }
        downstream.fail(throwable);
    }

    @Override
    public void kill(Throwable throwable) {
        downstream.kill(throwable);
    }

    @Override
    public Set<Requirement> requirements() {
        return downstream.requirements();
    }
}
