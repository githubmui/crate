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

package io.crate.operation;

import com.google.common.util.concurrent.SettableFuture;
import io.crate.core.collections.Bucket;
import io.crate.core.collections.CollectionBucket;
import io.crate.core.collections.Row;
import io.crate.executor.QueryResult;
import io.crate.executor.TaskResult;
import io.crate.operation.projectors.Requirement;
import io.crate.operation.projectors.Requirements;
import io.crate.operation.projectors.RowReceiver;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ClientPagingReceiver implements RowReceiver {

    private final int fetchSize;
    private final SettableFuture<TaskResult> resultFuture;

    private int numRows = 0;
    private RowUpstream rowUpstream;
    private List<Object[]> rows = new ArrayList<>();
    private FetchCallback callback;
    private Throwable killed;

    public ClientPagingReceiver(int fetchSize, SettableFuture<TaskResult> resultFuture) {
        this.fetchSize = fetchSize;
        this.resultFuture = resultFuture;
    }

    @Override
    public boolean setNextRow(Row row) {
        if (killed != null) {
            return false;
        }

        numRows++;
        rows.add(row.materialize());
        if (numRows % fetchSize == 0) {
            emitResult(false);
            // TODO: could continue buffering until 1 other bucket is full
            rowUpstream.pause();
        }
        return true;
    }

    private void emitResult(boolean isLast) {
        if (!resultFuture.isDone()) {
            resultFuture.set(new QueryResult(new CollectionBucket(rows)));
        } else {
            callback.onResult(new CollectionBucket(rows), isLast);
            callback = null;
        }
        rows = new ArrayList<>(); // buckets are lazy, must re-initialize to not overwrite the rows of a previously emitted bucket
    }


    public void fetch(FetchCallback callback) {
        assert this.callback == null : "there may only be one active callback";
        this.callback = callback;
        rowUpstream.resume(false);
    }

    @Override
    public void finish() {
        emitResult(true);
    }

    @Override
    public void fail(Throwable throwable) {
        if (!resultFuture.isDone()) {
            resultFuture.setException(throwable);
        } else {
            callback.onError(throwable);
            callback = null;
        }
    }

    @Override
    public void kill(Throwable throwable) {
        killed = throwable;
    }

    @Override
    public void prepare() {
    }

    @Override
    public void setUpstream(RowUpstream rowUpstream) {
        this.rowUpstream = rowUpstream;
    }

    @Override
    public Set<Requirement> requirements() {
        return Requirements.NO_REQUIREMENTS;
    }

    public interface FetchCallback {
        void onResult(Bucket rows, boolean isLast);

        void onError(Throwable t);
    }
}
