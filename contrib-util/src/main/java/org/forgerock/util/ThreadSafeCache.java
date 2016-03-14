/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2014-2016 ForgeRock AS.
 */

package org.forgerock.util;

import static org.forgerock.util.Reject.checkNotNull;
import static org.forgerock.util.promise.Promises.newResultPromise;
import static org.forgerock.util.time.Duration.duration;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.forgerock.util.promise.Promise;
import org.forgerock.util.promise.ResultHandler;
import org.forgerock.util.time.Duration;

/**
 * ThreadSafeCache is a thread-safe write-through cache.
 * <p>
 * Instead of storing directly the value in the backing Map, it requires the
 * consumer to provide a value factory (a Callable). A new FutureTask
 * encapsulate the callable, is executed and is placed inside a
 * ConcurrentHashMap if absent.
 * <p>
 * The final behavior is that, even if two concurrent Threads are borrowing an
 * object from the cache, given that they provide an equivalent value factory,
 * the first one will compute the value while the other will get the result from
 * the Future (and will wait until the result is computed or a timeout occurs).
 * <p>
 * By default, cache duration is set to 1 minute and there is no maximum timeout.
 *
 * @param <K>
 *            Type of the key
 * @param <V>
 *            Type of the value
 */
public class ThreadSafeCache<K, V> {

    private static final Duration DEFAULT_TIMEOUT = duration(1L, TimeUnit.MINUTES);
    private static final Function<Exception, Duration, Exception> ON_EXCEPTION_NO_TIMEOUT =
            new Function<Exception, Duration, Exception>() {
                @Override
                public Duration apply(Exception e) throws Exception {
                    return Duration.ZERO;
                }
            };

    private final ScheduledExecutorService executorService;
    private final ConcurrentMap<K, Future<V>> cache = new ConcurrentHashMap<>();
    private AsyncFunction<V, Duration, Exception> defaultTimeoutFunction;
    private Duration maxTimeout;

    /**
     * Build a new {@link ThreadSafeCache} using the given scheduled executor.
     *
     * @param executorService
     *            scheduled executor for registering expiration callbacks.
     */
    public ThreadSafeCache(final ScheduledExecutorService executorService) {
        this.executorService = executorService;
        setDefaultTimeout(DEFAULT_TIMEOUT);
    }

    /**
     * Sets the default cache entry expiration delay, if none provided in the
     * caller. Notice that this will impact only new cache entries.
     *
     * @param defaultTimeout
     *            new cache entry timeout
     */
    public void setDefaultTimeout(final Duration defaultTimeout) {
        setDefaultTimeoutFunction(new AsyncFunction<V, Duration, Exception>() {
            @Override
            public Promise<Duration, Exception> apply(V value) {
                return newResultPromise(defaultTimeout);
            }
        });
    }

    /**
     * Sets the function that will be applied on each entry to compute the timeout, if none provided in the
     * caller. Notice that this will impact only new cache entries.
     *
     * @param timeoutFunction
     *            the function that will compute the cache entry timeout (must not be {@literal null})
     */
    public void setDefaultTimeoutFunction(AsyncFunction<V, Duration, Exception> timeoutFunction) {
        this.defaultTimeoutFunction = checkNotNull(timeoutFunction);
    }

    private Future<V> createIfAbsent(final K key,
                                     final Callable<V> callable,
                                     final AsyncFunction<V, Duration, Exception> timeoutFunction)
            throws InterruptedException, ExecutionException {
        // See the javadoc of the class for the intent of the Future and FutureTask.
        Future<V> future = cache.get(key);
        if (future == null) {
            // First call: no value cached for that key
            final FutureTask<V> futureTask = new FutureTask<>(callable);
            future = cache.putIfAbsent(key, futureTask);
            if (future == null) {
                // after the double check, it seems we are still the first to want to cache that value.
                future = futureTask;

                // Compute the value
                futureTask.run();

                scheduleEviction(key, futureTask.get(), timeoutFunction);
            }
        }
        return future;
    }

    private void scheduleEviction(final K key,
                                  final V value,
                                  final AsyncFunction<V, Duration, Exception> timeoutFunction)
            throws ExecutionException, InterruptedException {
        newResultPromise(value)
                .thenAsync(timeoutFunction)
                .thenCatch(ON_EXCEPTION_NO_TIMEOUT)
                .thenCatchRuntimeException(ON_EXCEPTION_NO_TIMEOUT)
                .thenOnResult(new ResultHandler<Duration>() {
                    @Override
                    public void handleResult(Duration timeout) {
                        if (timeout == null || timeout.isZero()) {
                            // Fast path : no need to schedule, evict it now
                            evict(key);
                            return;
                        } else {
                            // Cap the timeout if requested
                            if (maxTimeout != null) {
                                timeout = timeout.compareTo(maxTimeout) < 0 ? timeout : maxTimeout;
                            }

                            if (!timeout.isUnlimited()) {
                                // Schedule the eviction
                                executorService.schedule(new Expiration(key),
                                                         timeout.getValue(),
                                                         timeout.getUnit());
                            }
                        }
                    }
                });
    }

    /**
     * Borrow (and create before hand if absent) a cache entry. If another
     * Thread has created (or the creation is undergoing) the value, this
     * methods waits indefinitely for the value to be available.
     *
     * @param key
     *            entry key
     * @param callable
     *            cached value factory
     * @return the cached value
     * @throws InterruptedException
     *             if the current thread was interrupted while waiting
     * @throws ExecutionException
     *             if the cached value computation threw an exception
     */
    public V getValue(final K key, final Callable<V> callable) throws InterruptedException,
                                                                      ExecutionException {
        return getValue(key, callable, defaultTimeoutFunction);
    }

    /**
     * Borrow (and create before hand if absent) a cache entry. If another
     * Thread has created (or the creation is undergoing) the value, this
     * methods waits indefinitely for the value to be available.
     *
     * @param key
     *            entry key
     * @param callable
     *            cached value factory
     * @param expire
     *            function to override the global cache's timeout
     * @return the cached value
     * @throws InterruptedException
     *             if the current thread was interrupted while waiting
     * @throws ExecutionException
     *             if the cached value computation threw an exception
     */
    public V getValue(final K key,
                      final Callable<V> callable,
                      final AsyncFunction<V, Duration, Exception> expire) throws InterruptedException,
                                                                                 ExecutionException {
        try {
            return createIfAbsent(key, callable, expire).get();
        } catch (InterruptedException | RuntimeException | ExecutionException e) {
            evict(key);
            throw e;
        }
    }

    /**
     * Clean-up the cache entries.
     */
    public void clear() {
        cache.clear();
    }

    /**
     * Returns the number of cached values.
     * @return the number of cached values
     */
    public int size() {
        return cache.size();
    }

    private Future<V> evict(K key) {
        return cache.remove(key);
    }

    /**
     * Gets the maximum timeout (can be {@literal null}).
     * @return the maximum timeout
     */
    public Duration getMaxTimeout() {
        return maxTimeout;
    }

    /**
     * Sets the maximum timeout. If the timeout returned by the {@literal timeoutFunction} is greater than this
     * specified maximum timeout, then the maximum timeout is used instead of the returned one to cache the entry.
     * @param maxTimeout the maximum timeout to use.
     */
    public void setMaxTimeout(Duration maxTimeout) {
        this.maxTimeout = maxTimeout;
    }

    /**
     * Registered in the executor, this callable simply removes the cache entry
     * after a specified amount of time.
     */
    private class Expiration implements Runnable {
        private final K key;

        public Expiration(final K key) {
            this.key = key;
        }

        @Override
        public void run() {
            evict(key);
        }
    }
}
