/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.broker;

import javax.jms.JMSException;

import org.apache.activemq.command.Message;
import org.apache.activemq.filter.BooleanExpression;
import org.apache.activemq.filter.MessageEvaluationContext;
import org.apache.activemq.flow.IFlowSink;
import org.apache.activemq.flow.ISourceController;
import org.apache.activemq.queue.ExclusivePersistentQueue;
import org.apache.activemq.queue.Subscription;

public class DurableSubscription implements BrokerSubscription, DeliveryTarget {

    private final ExclusivePersistentQueue<Long, MessageDelivery> queue;
    private final VirtualHost host;
    private final Destination destination;
    private Subscription<MessageDelivery> connectedSub;
    boolean started = false;
    BooleanExpression selector;

    DurableSubscription(VirtualHost host, Destination destination, BooleanExpression selector, ExclusivePersistentQueue<Long, MessageDelivery> queue) {
        this.host = host;
        this.queue = queue;
        this.destination = destination;
        this.selector = selector;
        this.host.getRouter().bind(destination, this);
    }
    

    /* (non-Javadoc)
     * @see org.apache.activemq.broker.DeliveryTarget#deliver(org.apache.activemq.broker.MessageDelivery, org.apache.activemq.flow.ISourceController)
     */
    public void deliver(MessageDelivery message, ISourceController<?> source) {
        queue.add(message, source);
    }

    public synchronized void connect(final Subscription<MessageDelivery> subscription) throws UserAlreadyConnectedException {
        if (this.connectedSub == null) {
            this.connectedSub = subscription;
            queue.addSubscription(connectedSub);
        } else if (connectedSub != subscription) {
            throw new UserAlreadyConnectedException();
        }
    }

    public synchronized void disconnect(final Subscription<MessageDelivery> subscription) {
        if (connectedSub != null && connectedSub == subscription) {
            queue.removeSubscription(connectedSub);
            connectedSub = null;
        }
    }

    public boolean matches(MessageDelivery message) {
        if (selector == null) {
            return true;
        }

        Message msg = message.asType(Message.class);
        if (msg == null) {
            return false;
        }

        MessageEvaluationContext selectorContext = new MessageEvaluationContext();
        selectorContext.setMessageReference(msg);
        selectorContext.setDestination(destination.asActiveMQDestination());
        try {
            return (selector.matches(selectorContext));
        } catch (JMSException e) {
            e.printStackTrace();
            return false;
        }
    }

    public IFlowSink<MessageDelivery> getSink() {
        return queue;
    }

    public boolean hasSelector() {
        return selector != null;
    }
}
