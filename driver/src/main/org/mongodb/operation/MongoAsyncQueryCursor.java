/*
 * Copyright (c) 2008-2014 MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.mongodb.operation;

import org.mongodb.AsyncBlock;
import org.mongodb.Decoder;
import org.mongodb.Document;
import org.mongodb.Encoder;
import org.mongodb.MongoAsyncCursor;
import org.mongodb.MongoException;
import org.mongodb.MongoNamespace;
import org.mongodb.ServerCursor;
import org.mongodb.connection.Connection;
import org.mongodb.connection.SingleResultCallback;
import org.mongodb.diagnostics.Loggers;
import org.mongodb.diagnostics.logging.Logger;
import org.mongodb.protocol.GetMoreDiscardProtocol;
import org.mongodb.protocol.GetMoreProtocol;
import org.mongodb.protocol.GetMoreReceiveProtocol;
import org.mongodb.protocol.QueryProtocol;
import org.mongodb.protocol.QueryResult;
import org.mongodb.session.ServerConnectionProvider;

// TODO: kill cursor on early breakout
// TODO: Report errors in callback
class MongoAsyncQueryCursor<T> implements MongoAsyncCursor<T> {
    private static final Logger LOGGER = Loggers.getLogger("operation");

    private final MongoNamespace namespace;
    private final Find find;
    private final Encoder<Document> queryEncoder;
    private final Decoder<T> decoder;
    private ServerConnectionProvider serverConnectionProvider;
    private Connection exhaustConnection;
    private long numFetchedSoFar;
    private ServerCursor cursor;
    private AsyncBlock<? super T> block;

    public MongoAsyncQueryCursor(final MongoNamespace namespace, final Find find, final Encoder<Document> queryEncoder,
                                 final Decoder<T> decoder, final ServerConnectionProvider serverConnectionProvider) {
        this.namespace = namespace;
        this.find = find;
        this.queryEncoder = queryEncoder;
        this.decoder = decoder;
        this.serverConnectionProvider = serverConnectionProvider;
    }

    @Override
    public void start(final AsyncBlock<? super T> aBlock) {
        this.block = aBlock;
        serverConnectionProvider.getConnectionAsync().register(new SingleResultCallback<Connection>() {
            @Override
            public void onResult(final Connection connection, final MongoException e) {
                if (e != null) {
                    close(0);
                } else {
                    if (isExhaust()) {
                        exhaustConnection = connection;
                    }
                    new QueryProtocol<T>(namespace, find, queryEncoder, decoder)
                    .executeAsync(connection, serverConnectionProvider.getServerDescription())
                    .register(new QueryResultSingleResultCallback(connection));
                }
            }
        });
    }

    private void close(final int responseTo) {
        if (isExhaust()) {
            handleExhaustCleanup(responseTo);
        } else {
            block.done();
        }
    }

    private boolean isExhaust() {
        return find.getOptions().getFlags().contains(QueryFlag.Exhaust);
    }

    private void handleExhaustCleanup(final int responseTo) {
        new GetMoreDiscardProtocol(cursor != null ? cursor.getId() : 0, responseTo, exhaustConnection)
        .executeAsync(exhaustConnection, serverConnectionProvider.getServerDescription())
        .register(new SingleResultCallback<Void>() {
            @Override
            public void onResult(final Void result, final MongoException e) {
                block.done();
            }
        });
    }

    private class QueryResultSingleResultCallback implements SingleResultCallback<QueryResult<T>> {
        private final Connection connection;

        public QueryResultSingleResultCallback(final Connection connection) {
            this.connection = connection;
        }

        @Override
        public void onResult(final QueryResult<T> result, final MongoException e) {
            if (!isExhaust()) {
                connection.close();
            }
            if (e != null) {
                close(0);
                return;
            }

            cursor = result.getCursor();

            boolean breakEarly = false;

            try {
                for (final T cur : result.getResults()) {
                    numFetchedSoFar++;
                    if (find.getLimit() > 0 && numFetchedSoFar > find.getLimit()) {
                        breakEarly = true;
                        break;
                    }
                    block.apply(cur);

                }
            } catch (Exception e1) {
                breakEarly = true;
                LOGGER.error(e1.getMessage(), e1);
            }

            if (result.getCursor() == null || breakEarly) {
                close(result.getRequestId());
            } else {
                // get more results
                if (find.getOptions().getFlags().contains(QueryFlag.Exhaust)) {
                    new GetMoreReceiveProtocol<T>(decoder, result.getRequestId())
                    .executeAsync(exhaustConnection, serverConnectionProvider.getServerDescription())
                    .register(this);
                } else {
                    serverConnectionProvider.getConnectionAsync().register(new SingleResultCallback<Connection>() {
                        @Override
                        public void onResult(final Connection connection, final MongoException e) {
                            if (e != null) {
                                close(0);
                            } else {
                                new GetMoreProtocol<T>(namespace,
                                                       new GetMore(result.getCursor(), find.getLimit(), find.getBatchSize(),
                                                                   numFetchedSoFar),
                                                       decoder)
                                .executeAsync(connection, serverConnectionProvider.getServerDescription())
                                .register(new QueryResultSingleResultCallback(connection));
                            }
                        }
                    });
                }
            }
        }
    }
}