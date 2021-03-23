package io.vertx.core.net.impl.pool;

import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.impl.EventLoopContext;
import io.vertx.core.net.impl.clientconnection.Lease;

/**
 * A waiter for a connection.
 */
public class Waiter<C> {

  final EventLoopContext context;
  final int weight;
  final Handler<AsyncResult<Lease<C>>> handler;
  Waiter<C> prev;
  Waiter<C> next;

  Waiter(EventLoopContext context, final int weight, Handler<AsyncResult<Lease<C>>> handler) {
    this.context = context;
    this.weight = weight;
    this.handler = handler;
  }
}
