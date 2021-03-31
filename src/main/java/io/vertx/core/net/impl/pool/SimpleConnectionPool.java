/*
 * Copyright (c) 2011-2021 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package io.vertx.core.net.impl.pool;

import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.http.ConnectionPoolTooBusyException;
import io.vertx.core.impl.EventLoopContext;
import io.vertx.core.net.impl.clientconnection.ConnectResult;
import io.vertx.core.net.impl.clientconnection.Lease;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.BiFunction;
import java.util.function.Predicate;

public class SimpleConnectionPool<C> implements ConnectionPool<C> {

  private static final Future POOL_CLOSED = Future.failedFuture("Pool closed");

  private static final BiFunction<PoolWaiter, List<PoolConnection>, PoolConnection> strategy1 = (waiter, list) -> {
    int size = list.size();
    for (int i = 0;i < size;i++) {
      PoolConnection slot = list.get(i);
      if (slot.context() == waiter.context() && slot.capacity() > 0) {
        return slot;
      }
    }
    return null;
  };

  private static final BiFunction<PoolWaiter, List<PoolConnection>, PoolConnection> strategy2 = (waiter, list) -> {
    int size = list.size();
    for (int i = 0;i < size;i++) {
      PoolConnection slot = list.get(i);
      if (slot.capacity() > 0) {
        return slot;
      }
    }
    return null;
  };

  static class Slot<C> implements PoolConnector.Listener, PoolConnection<C> {

    private final SimpleConnectionPool<C> pool;
    private final EventLoopContext context;
    private final Promise<C> result;
    private PoolWaiter<C> initiator;
    private C connection;
    private int index;
    private int capacity;
    private int maxCapacity;
    private int weight;

    public Slot(SimpleConnectionPool<C> pool, EventLoopContext context, int index, int initialWeight) {
      this.pool = pool;
      this.context = context;
      this.connection = null;
      this.capacity = 0;
      this.index = index;
      this.weight = initialWeight;
      this.result = context.promise();
    }

    @Override
    public void onRemove() {
      pool.remove(this);
    }

    @Override
    public Context context() {
      return context;
    }

    @Override
    public C get() {
      return connection;
    }

    @Override
    public int capacity() {
      return capacity;
    }

    @Override
    public int maxCapacity() {
      return maxCapacity;
    }
  }

  private final PoolConnector<C> connector;

  private final Slot<C>[] slots;
  private int size;
  private final int maxWaiters;
  private final int maxWeight;
  private int weight;
  private boolean closed;
  private final Executor<SimpleConnectionPool<C>> sync;
  private final Waiters<C> waiters = new Waiters<>();
  private final ListImpl list = new ListImpl();
  private BiFunction<PoolWaiter<C>, List<PoolConnection<C>>, PoolConnection<C>> DEFAULT_SELECTOR;
  private BiFunction<PoolWaiter<C>, List<PoolConnection<C>>, PoolConnection<C>> FIRST_AVAILABLE_SELECTOR;

  SimpleConnectionPool(PoolConnector<C> connector, int maxSize, int maxWeight) {
    this(connector, maxSize, maxWeight, -1);
  }

  SimpleConnectionPool(PoolConnector<C> connector, int maxSize, int maxWeight, int maxWaiters) {
    this.connector = connector;
    this.slots = new Slot[maxSize];
    this.size = 0;
    this.maxWaiters = maxWaiters;
    this.weight = 0;
    this.maxWeight = maxWeight;
    this.sync = new CombinerExecutor2<>(this);
    this.DEFAULT_SELECTOR = (BiFunction) strategy1;
    this.FIRST_AVAILABLE_SELECTOR = (BiFunction) strategy2;
  }

  @Override
  public ConnectionPool<C> connectionSelector(BiFunction<PoolWaiter<C>, List<PoolConnection<C>>, PoolConnection<C>> selector) {
    this.DEFAULT_SELECTOR = selector;
    return this;
  }

  private void execute(Executor.Action<SimpleConnectionPool<C>> action) {
    sync.submit(action);
  }

  public int size() {
      return size;
  }

  public void connect(Slot<C> slot, PoolWaiter<C> waiter) {
    slot.initiator = waiter;
    connector.connect(slot.context, slot, ar -> {
      slot.initiator = null;
      if (ar.succeeded()) {
        execute(new ConnectSuccess<>(slot, ar.result(), waiter));
      } else {
        execute(new ConnectFailed<>(slot, ar.cause(), waiter));
      }
    });
  }

  private static class ConnectSuccess<C> implements Executor.Action<SimpleConnectionPool<C>> {

    private final Slot<C> slot;
    private final ConnectResult<C> result;
    private PoolWaiter<C> waiter;

    private ConnectSuccess(Slot<C> slot, ConnectResult<C> result, PoolWaiter<C> waiter) {
      this.slot = slot;
      this.result = result;
      this.waiter = waiter;
    }

    @Override
    public Runnable execute(SimpleConnectionPool<C> pool) {
      int initialWeight = slot.weight;
      slot.connection = result.connection();
      slot.maxCapacity = (int)result.concurrency();
      slot.weight = (int) result.weight();
      slot.capacity = slot.maxCapacity;
      pool.weight += (result.weight() - initialWeight);
      if (pool.closed) {
        if (waiter.done) {
          waiter = null;
        } else {
          waiter.done = true;
        }
        return () -> {
          if (waiter != null) {
            slot.context.emit(POOL_CLOSED, waiter.handler);
          }
          slot.result.complete(slot.connection);
        };
      } else {
        int c = 1;
        LeaseImpl<C>[] extra;
        int capacity;
        if (waiter.done) {
          waiter = null;
          capacity = slot.capacity;
        } else {
          waiter.done = true;
          capacity = slot.capacity - 1;
        }
        int m = Math.min(capacity, pool.waiters.size());
        if (m > 0) {
          c += m;
          extra = new LeaseImpl[m];
          for (int i = 0;i < m;i++) {
            extra[i] = new LeaseImpl<>(slot, pool.waiters.poll().handler);
          }
        } else {
          extra = null;
        }
        slot.capacity -= c;
        return () -> {
          if (waiter != null) {
            new LeaseImpl<>(slot, waiter.handler).emit();
          }
          if (extra != null) {
            for (LeaseImpl<C> lease : extra) {
              lease.emit();
            }
          }
          slot.result.complete(slot.connection);
        };
      }
    }
  }

  private static class ConnectFailed<C> extends Remove<C> {

    private final Throwable cause;
    private PoolWaiter<C> waiter;

    public ConnectFailed(Slot<C> removed, Throwable cause, PoolWaiter<C> waiter) {
      super(removed);
      this.cause = cause;
      this.waiter = waiter;
    }

    @Override
    public Runnable execute(SimpleConnectionPool<C> pool) {
      if (waiter.done) {
        waiter = null;
      } else {
        waiter.done = true;
      }
      if (pool.closed) {
        return () -> waiter.handler.handle(POOL_CLOSED);
      }
      Runnable res = super.execute(pool);
      return () -> {
        if (res != null) {
          res.run();
        }
        if (waiter != null) {
          removed.context.emit(Future.failedFuture(cause), waiter.handler);
        }
        removed.result.fail(cause);
      };
    }
  }

  private static class Remove<C> implements Executor.Action<SimpleConnectionPool<C>> {

    protected final Slot<C> removed;

    private Remove(Slot<C> removed) {
      this.removed = removed;
    }

    @Override
    public Runnable execute(SimpleConnectionPool<C> pool) {
      if (pool.closed) {
        return null;
      }
      int w = removed.weight;
      removed.capacity = 0;
      removed.maxCapacity = 0;
      removed.connection = null;
      removed.weight = 0;
      PoolWaiter<C> waiter = pool.waiters.poll();
      if (waiter != null) {
        Slot<C> slot = new Slot<>(pool, waiter.context, removed.index, waiter.weight);
        pool.weight -= w;
        pool.weight += waiter.weight;
        pool.slots[removed.index] = slot;
        return () -> {
          if (waiter.listener != null) {
            waiter.listener.onConnect(waiter);
          }
          pool.connect(slot, waiter);
        };
      } else if (pool.size > 1) {
        Slot<C> tmp = pool.slots[pool.size - 1];
        tmp.index = removed.index;
        pool.slots[removed.index] = tmp;
        pool.slots[pool.size - 1] = null;
        pool.size--;
        pool.weight -= w;
        return null;
      } else {
        pool.slots[0] = null;
        pool.size--;
        pool.weight -= w;
        return null;
      }
    }
  }

  private void remove(Slot<C> removed) {
    execute(new Remove<>(removed));
  }

  private static class Evict<C> implements Executor.Action<SimpleConnectionPool<C>> {

    private final Predicate<C> predicate;
    private final Handler<AsyncResult<List<C>>> handler;

    public Evict(Predicate<C> predicate, Handler<AsyncResult<List<C>>> handler) {
      this.predicate = predicate;
      this.handler = handler;
    }

    @Override
    public Runnable execute(SimpleConnectionPool<C> pool) {
      if (pool.closed) {
        return () -> handler.handle(POOL_CLOSED);
      }
      List<C> lst = new ArrayList<>();
      for (int i = pool.size - 1;i >= 0;i--) {
        Slot<C> slot = pool.slots[i];
        if (slot.connection != null && slot.capacity == slot.maxCapacity && predicate.test(slot.connection)) {
          lst.add(slot.connection);
          slot.capacity = 0;
          slot.maxCapacity = 0;
          slot.connection = null;
          if (i == pool.size - 1) {
            pool.slots[i] = null;
          } else {
            Slot<C> last = pool.slots[pool.size - 1];
            last.index = i;
            pool.slots[i] = last;
          }
          pool.weight -= slot.weight;
          pool.size--;
        }
      }
      return () -> handler.handle(Future.succeededFuture(lst));
    }
  }

  @Override
  public void evict(Predicate<C> predicate, Handler<AsyncResult<List<C>>> handler) {
    execute(new Evict<>(predicate, handler));
  }

  private static class Acquire<C> extends PoolWaiter<C> implements Executor.Action<SimpleConnectionPool<C>> {

    public Acquire(EventLoopContext context, PoolWaiter.Listener<C> listener, int weight, Handler<AsyncResult<Lease<C>>> handler) {
      super(listener, context, weight, handler);
    }

    @Override
    public Runnable execute(SimpleConnectionPool<C> pool) {
      if (pool.closed) {
        return () -> context.emit(POOL_CLOSED, handler);
      }

      // 1. Try reuse a existing connection with the same context
      Slot<C> slot1 = (Slot<C>) pool.DEFAULT_SELECTOR.apply(this, pool.list);
      if (slot1 != null) {
        slot1.capacity--;
        return () -> {
          new LeaseImpl<>(slot1, handler).emit();
        };
      }

      // 2. Try create connection
      if (pool.weight < pool.maxWeight) {
        pool.weight += weight;
        if (pool.size < pool.slots.length) {
          Slot<C> slot2 = new Slot<>(pool, context, pool.size, weight);
          pool.slots[pool.size++] = slot2;
          return () -> {
            if (listener != null) {
              listener.onConnect(this);
            }
            pool.connect(slot2, this);
          };
        } else {
          throw new IllegalStateException();
        }
      }

      // 3. Try use another context
      Slot<C> slot3 = (Slot<C>) pool.FIRST_AVAILABLE_SELECTOR.apply(this, pool.list);
      if (slot3 != null) {
        slot3.capacity--;
        return () -> {
          new LeaseImpl<>(slot3, handler).emit();
        };
      }

      // 4. Fall in waiters list
      if (pool.maxWaiters == -1 || pool.waiters.size() < pool.maxWaiters) {
        pool.waiters.add(this);
        return () -> {
          if (listener != null) {
            listener.onEnqueue(this);
          }
        };
      } else {
        return () -> context.emit(Future.failedFuture(new ConnectionPoolTooBusyException("Connection pool reached max wait queue size of " + pool.maxWaiters)), handler);
      }
    }
  }

  @Override
  public void acquire(EventLoopContext context, PoolWaiter.Listener<C> listener, int weight, Handler<AsyncResult<Lease<C>>> handler) {
    execute(new Acquire<>(context, listener, weight, handler));
  }

  public void acquire(EventLoopContext context, int weight, Handler<AsyncResult<Lease<C>>> handler) {
    acquire(context, PoolWaiter.NULL_LISTENER, weight, handler);
  }

  @Override
  public void cancel(PoolWaiter<C> waiter, Handler<AsyncResult<Boolean>> handler) {
    execute(new Cancel<>(waiter, handler));
  }

  private static class Cancel<C> implements Executor.Action<SimpleConnectionPool<C>>, Runnable {

    private final PoolWaiter<C> waiter;
    private final Handler<AsyncResult<Boolean>> handler;
    private boolean done;

    public Cancel(PoolWaiter<C> waiter, Handler<AsyncResult<Boolean>> handler) {
      this.waiter = waiter;
      this.handler = handler;
    }

    @Override
    public Runnable execute(SimpleConnectionPool<C> pool) {
      if (pool.closed) {
        return () -> handler.handle(POOL_CLOSED);
      }
      if (pool.waiters.remove(waiter)) {
        done = true;
      } else if (!waiter.done) {
        waiter.done = true;
        done = true;
      } else {
        done = false;
      }
      return this;
    }

    @Override
    public void run() {
      handler.handle(Future.succeededFuture(done));
    }
  }

  static class LeaseImpl<C> implements Lease<C> {

    private final Handler<AsyncResult<Lease<C>>> handler;
    private final Slot<C> slot;
    private final C connection;
    private boolean recycled;

    public LeaseImpl(Slot<C> slot, Handler<AsyncResult<Lease<C>>> handler) {
      this.handler = handler;
      this.slot = slot;
      this.connection = slot.connection;
    }

    @Override
    public C get() {
      return connection;
    }

    @Override
    public void recycle() {
      slot.pool.recycle(this);
    }

    void emit() {
      slot.context.emit(Future.succeededFuture(new LeaseImpl<>(slot, handler)), handler);
    }
  }

  private static class Recycle<C> implements Executor.Action<SimpleConnectionPool<C>> {

    private final Slot<C> slot;

    public Recycle(Slot<C> slot) {
      this.slot = slot;
    }

    @Override
    public Runnable execute(SimpleConnectionPool<C> pool) {
      if (!pool.closed && slot.connection != null) {
        if (pool.waiters.size() > 0) {
          PoolWaiter<C> waiter = pool.waiters.poll();
          return () -> new LeaseImpl<>(slot, waiter.handler).emit();
        } else {
          slot.capacity++;
        }
      }
      return null;
    }
  }

  private void recycle(LeaseImpl<C> lease) {
    if (lease.recycled) {
      throw new IllegalStateException("Attempt to recycle more than permitted");
    }
    lease.recycled = true;
    execute(new Recycle<>(lease.slot));
  }

  public int waiters() {
    return waiters.size();
  }

  public int weight() {
    return weight;
  }

  private static class Close<C> implements Executor.Action<SimpleConnectionPool<C>> {

    private final Handler<AsyncResult<List<Future<C>>>> handler;

    private Close(Handler<AsyncResult<List<Future<C>>>> handler) {
      this.handler = handler;
    }

    @Override
    public Runnable execute(SimpleConnectionPool<C> pool) {
      if (pool.closed) {
        return () -> handler.handle(POOL_CLOSED);
      }
      pool.closed = true;
      List<PoolWaiter<C>> waiters = pool.waiters.clear();
      List<Future<C>> list = new ArrayList<>();
      for (int i = 0;i < pool.size;i++) {
        Slot<C> slot = pool.slots[i];
        pool.slots[i] = null;
        if (slot.initiator != null) {
          waiters.add(slot.initiator);
          slot.initiator = null;
        }
        list.add(slot.result.future());
      }
      pool.size = 0;
      return () -> {
        waiters.forEach(w -> w.context.emit(POOL_CLOSED, w.handler));
        handler.handle(Future.succeededFuture(list));
      };
    }
  }

  @Override
  public void close(Handler<AsyncResult<List<Future<C>>>> handler) {
    execute(new Close<>(handler));
  }

  private static class Waiters<C> implements Iterable<PoolWaiter<C>> {

    private final PoolWaiter<C> head;
    private int size;

    public Waiters() {
      head = new PoolWaiter<>(null, null, 0, null);
      head.next = head.prev = head;
    }

    PoolWaiter<C> poll() {
      if (head.next == head) {
        return null;
      }
      PoolWaiter<C> node = head.next;
      remove(node);
      return node;
    }

    void add(PoolWaiter<C> node) {
      node.prev = head.prev;
      node.next = head;
      head.prev.next = node;
      head.prev = node;
      size++;
    }

    boolean remove(PoolWaiter<C> node) {
      if (node.next == null) {
        return false;
      }
      node.next.prev = node.prev;
      node.prev.next = node.next;
      size--;
      return true;
    }

    List<PoolWaiter<C>> clear() {
      List<PoolWaiter<C>> lst = new ArrayList<>(size);
      this.forEach(lst::add);
      size = 0;
      head.next = head.prev = head;
      return lst;
    }

    int size() {
      return size;
    }

    @Override
    public Iterator<PoolWaiter<C>> iterator() {
      return new Iterator<PoolWaiter<C>>() {
        PoolWaiter<C> current = head;
        @Override
        public boolean hasNext() {
          return current.next != head;
        }
        @Override
        public PoolWaiter<C> next() {
          if (current.next == head) {
            throw new NoSuchElementException();
          }
          try {
            return current.next;
          } finally {
            current = current.next;
          }
        }
      };
    }
  }

  class ListImpl extends AbstractList<PoolConnection<C>> {
    @Override
    public PoolConnection<C> get(int index) {
      return slots[index];
    }
    @Override
    public int size() {
      return size;
    }
  }
}
