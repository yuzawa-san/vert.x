package io.vertx.core.net.impl.pool;

import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.impl.EventLoopContext;
import io.vertx.core.net.impl.clientconnection.Lease;

/**
 * A waiter for a connection.
 */
public class Waiter<C> {

  static final Listener NULL_LISTENER = new Listener() {
  };

  public interface Listener<C> {

    default void onEnqueue(Waiter<C> waiter) {
    }

    default void onConnect(Waiter<C> waiter) {
    }
  }

  final Waiter.Listener<C> listener;
  final EventLoopContext context;
  final int weight;
  final Handler<AsyncResult<Lease<C>>> handler;
  Waiter<C> prev;
  Waiter<C> next;
  boolean done;

  Waiter(Waiter.Listener<C> listener, EventLoopContext context, final int weight, Handler<AsyncResult<Lease<C>>> handler) {
    this.listener = listener;
    this.context = context;
    this.weight = weight;
    this.handler = handler;
  }
}
