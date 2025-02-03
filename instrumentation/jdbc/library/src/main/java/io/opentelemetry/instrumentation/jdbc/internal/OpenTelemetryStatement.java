/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

// Includes work from:
/*
 * Copyright 2017-2021 The OpenTracing Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package io.opentelemetry.instrumentation.jdbc.internal;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter;
import io.opentelemetry.instrumentation.jdbc.internal.dbinfo.DbInfo;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * This class is internal and is hence not for public use. Its APIs are unstable and can change at
 * any time.
 */
public class OpenTelemetryStatement<S extends Statement> implements Statement {

  protected final S delegate;
  protected final OpenTelemetryConnection connection;
  protected final DbInfo dbInfo;
  protected final String query;
  protected final Instrumenter<DbRequest, Void> instrumenter;

  private final List<String> batchCommands = new ArrayList<>();
  protected long batchSize;

  OpenTelemetryStatement(
      S delegate,
      OpenTelemetryConnection connection,
      DbInfo dbInfo,
      Instrumenter<DbRequest, Void> instrumenter) {
    this(delegate, connection, dbInfo, null, instrumenter);
  }

  OpenTelemetryStatement(
      S delegate,
      OpenTelemetryConnection connection,
      DbInfo dbInfo,
      String query,
      Instrumenter<DbRequest, Void> instrumenter) {
    this.delegate = delegate;
    this.connection = connection;
    this.dbInfo = dbInfo;
    this.query = query;
    this.instrumenter = instrumenter;
  }

  @Override
  public ResultSet executeQuery(String sql) throws SQLException {
    return wrapCall(sql, () -> delegate.executeQuery(sql));
  }

  @Override
  public int executeUpdate(String sql) throws SQLException {
    return wrapCall(sql, () -> delegate.executeUpdate(sql));
  }

  @Override
  public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
    return wrapCall(sql, () -> delegate.executeUpdate(sql, autoGeneratedKeys));
  }

  @Override
  public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
    return wrapCall(sql, () -> delegate.executeUpdate(sql, columnIndexes));
  }

  @Override
  public int executeUpdate(String sql, String[] columnNames) throws SQLException {
    return wrapCall(sql, () -> delegate.executeUpdate(sql, columnNames));
  }

  @Override
  public boolean execute(String sql) throws SQLException {
    return wrapCall(sql, () -> delegate.execute(sql));
  }

  @Override
  public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
    return wrapCall(sql, () -> delegate.execute(sql, autoGeneratedKeys));
  }

  @Override
  public boolean execute(String sql, int[] columnIndexes) throws SQLException {
    return wrapCall(sql, () -> delegate.execute(sql, columnIndexes));
  }

  @Override
  public boolean execute(String sql, String[] columnNames) throws SQLException {
    return wrapCall(sql, () -> delegate.execute(sql, columnNames));
  }

  @Override
  public int[] executeBatch() throws SQLException {
    return wrapBatchCall(delegate::executeBatch);
  }

  @Override
  public void close() throws SQLException {
    delegate.close();
  }

  @Override
  public int getMaxFieldSize() throws SQLException {
    return delegate.getMaxFieldSize();
  }

  @Override
  public void setMaxFieldSize(int max) throws SQLException {
    delegate.setMaxFieldSize(max);
  }

  @Override
  public int getMaxRows() throws SQLException {
    return delegate.getMaxRows();
  }

  @Override
  public void setMaxRows(int max) throws SQLException {
    delegate.setMaxRows(max);
  }

  @Override
  public void setEscapeProcessing(boolean enable) throws SQLException {
    delegate.setEscapeProcessing(enable);
  }

  @Override
  public int getQueryTimeout() throws SQLException {
    return delegate.getQueryTimeout();
  }

  @Override
  public void setQueryTimeout(int seconds) throws SQLException {
    delegate.setQueryTimeout(seconds);
  }

  @Override
  public void cancel() throws SQLException {
    delegate.cancel();
  }

  @Override
  public SQLWarning getWarnings() throws SQLException {
    return delegate.getWarnings();
  }

  @Override
  public void clearWarnings() throws SQLException {
    delegate.clearWarnings();
  }

  @Override
  public void setCursorName(String name) throws SQLException {
    delegate.setCursorName(name);
  }

  @Override
  public ResultSet getResultSet() throws SQLException {
    return delegate.getResultSet();
  }

  @Override
  public int getUpdateCount() throws SQLException {
    return delegate.getUpdateCount();
  }

  @Override
  public boolean getMoreResults() throws SQLException {
    return delegate.getMoreResults();
  }

  @SuppressWarnings("UngroupedOverloads")
  @Override
  public boolean getMoreResults(int current) throws SQLException {
    return delegate.getMoreResults(current);
  }

  @Override
  public int getFetchDirection() throws SQLException {
    return delegate.getFetchDirection();
  }

  @Override
  public void setFetchDirection(int direction) throws SQLException {
    delegate.setFetchDirection(direction);
  }

  @Override
  public int getFetchSize() throws SQLException {
    return delegate.getFetchSize();
  }

  @Override
  public void setFetchSize(int rows) throws SQLException {
    delegate.setFetchSize(rows);
  }

  @Override
  public int getResultSetConcurrency() throws SQLException {
    return delegate.getResultSetConcurrency();
  }

  @Override
  public int getResultSetType() throws SQLException {
    return delegate.getResultSetType();
  }

  @Override
  public void addBatch(String sql) throws SQLException {
    delegate.addBatch(sql);
    batchCommands.add(sql);
    batchSize++;
  }

  @Override
  public void clearBatch() throws SQLException {
    delegate.clearBatch();
    batchCommands.clear();
    batchSize = 0;
  }

  @Override
  public Connection getConnection() {
    return connection;
  }

  @Override
  public ResultSet getGeneratedKeys() throws SQLException {
    return delegate.getGeneratedKeys();
  }

  @Override
  public int getResultSetHoldability() throws SQLException {
    return delegate.getResultSetHoldability();
  }

  @Override
  public boolean isClosed() throws SQLException {
    return delegate.isClosed();
  }

  @Override
  public boolean isPoolable() throws SQLException {
    return delegate.isPoolable();
  }

  @Override
  public void setPoolable(boolean poolable) throws SQLException {
    delegate.setPoolable(poolable);
  }

  @Override
  public void closeOnCompletion() throws SQLException {
    delegate.closeOnCompletion();
  }

  @Override
  public boolean isCloseOnCompletion() throws SQLException {
    return delegate.isCloseOnCompletion();
  }

  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    return delegate.unwrap(iface);
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) throws SQLException {
    return delegate.isWrapperFor(iface);
  }

  protected <T, E extends Exception> T wrapCall(String sql, ThrowingSupplier<T, E> callable)
      throws E {
    DbRequest request = DbRequest.create(dbInfo, sql);
    return wrapCall(request, callable);
  }

  protected <T, E extends Exception> T wrapCall(DbRequest request, ThrowingSupplier<T, E> callable)
      throws E {
    Context parentContext = Context.current();

    if (!this.instrumenter.shouldStart(parentContext, request)) {
      return callable.call();
    }

    Context context = this.instrumenter.start(parentContext, request);
    T result;
    try (Scope ignored = context.makeCurrent()) {
      result = callable.call();
    } catch (Throwable t) {
      this.instrumenter.end(context, request, null, t);
      throw t;
    }
    this.instrumenter.end(context, request, null, null);
    return result;
  }

  private <T, E extends Exception> T wrapBatchCall(ThrowingSupplier<T, E> callable) throws E {
    DbRequest request = DbRequest.create(dbInfo, batchCommands, batchSize);
    return wrapCall(request, callable);
  }
}
