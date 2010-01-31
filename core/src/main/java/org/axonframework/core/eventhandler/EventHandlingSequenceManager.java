/*
 * Copyright (c) 2010. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.core.eventhandler;

import org.axonframework.core.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;

/**
 * The EventHandlingSequenceManager is responsible for delegating each incoming event to the relevant {@link
 * EventProcessingScheduler} for processing, depending on the sequencing identifier of the event.
 *
 * @author Allard Buijze
 * @since 0.3
 */
public class EventHandlingSequenceManager {

    private static final Logger logger = LoggerFactory.getLogger(EventHandlingSequenceManager.class);

    private final EventListener eventListener;
    private final Executor executor;
    private final ConcurrentMap<Object, EventProcessingScheduler> transactions =
            new ConcurrentHashMap<Object, EventProcessingScheduler>();
    private final EventSequencingPolicy eventSequencingPolicy;

    /**
     * Initialize the EventHandlingSequenceManager for the given <code>eventListener</code> using the given
     * <code>executor</code>.
     *
     * @param eventListener The event listener this instance manages
     * @param executor      The executor that processes the events
     */
    public EventHandlingSequenceManager(EventListener eventListener, Executor executor) {
        this.eventListener = eventListener;
        this.executor = executor;
        this.eventSequencingPolicy = eventListener.getEventSequencingPolicy();
    }

    /**
     * Adds an event to the relevant scheduler.
     *
     * @param event The event to schedule
     */
    public void addEvent(Event event) {
        if (eventListener.canHandle(event.getClass())) {
            final Object sequenceIdentifier = eventSequencingPolicy.getSequenceIdentifierFor(event);
            if (sequenceIdentifier == null) {
                logger.debug("Scheduling event of type [{}] for full concurrent processing",
                             event.getClass().getSimpleName());
                executor.execute(new SingleEventHandlerInvocationTask(eventListener, event));
            } else {
                logger.debug("Scheduling event of type [{}] for sequential processing in group [{}]",
                             event.getClass().getSimpleName(),
                             sequenceIdentifier.toString());
                scheduleEvent(event, sequenceIdentifier);
            }
        }
    }

    private void scheduleEvent(Event event, Object sequenceIdentifier) {
        boolean eventScheduled = false;
        while (!eventScheduled) {
            EventProcessingScheduler currentScheduler = transactions.get(sequenceIdentifier);
            if (currentScheduler == null) {
                transactions.putIfAbsent(sequenceIdentifier,
                                         newProcessingScheduler(new TransactionCleanUp(sequenceIdentifier)));
            } else {
                eventScheduled = currentScheduler.scheduleEvent(event);
                if (!eventScheduled) {
                    // we know it can be cleaned up.
                    transactions.remove(sequenceIdentifier, currentScheduler);
                }
            }
        }
    }

    /**
     * Creates a new scheduler instance for the eventListener that schedules events on the executor service for the
     * managed EventListener.
     *
     * @param shutDownCallback The callback that needs to be notified when the scheduler stops processing.
     * @return a new scheduler instance
     */
    protected EventProcessingScheduler newProcessingScheduler(TransactionCleanUp shutDownCallback) {
        logger.debug("Initializing new processing scheduler for sequence [{}]",
                     shutDownCallback.sequenceIdentifier.toString());
        return new EventProcessingScheduler(eventListener, executor, shutDownCallback);
    }

    private static class SingleEventHandlerInvocationTask implements Runnable {

        private final EventListener eventListener;
        private final Event event;

        /**
         * Configures a task to invoke a single event on an event listener
         *
         * @param eventListener The event listener to invoke the event handler on
         * @param event         the event to send to the event listener
         */
        public SingleEventHandlerInvocationTask(EventListener eventListener, Event event) {
            this.eventListener = eventListener;
            this.event = event;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void run() {
            eventListener.handle(event);
        }
    }

    private final class TransactionCleanUp implements EventProcessingScheduler.ShutdownCallback {

        private final Object sequenceIdentifier;

        private TransactionCleanUp(Object sequenceIdentifier) {
            this.sequenceIdentifier = sequenceIdentifier;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void afterShutdown(EventProcessingScheduler scheduler) {
            logger.debug("Cleaning up processing scheduler for sequence [{}]", sequenceIdentifier.toString());
            transactions.remove(sequenceIdentifier, scheduler);
        }
    }

    /**
     * Returns the event listener this instance manages events for
     *
     * @return the event listener this instance manages events for
     */
    EventListener getEventListener() {
        return eventListener;
    }
}
