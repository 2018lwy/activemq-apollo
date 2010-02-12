/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * his work for additional information regarding copyright ownership.
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
package org.apache.activemq.amqp.protocol.types;

import java.lang.Short;
import java.util.HashMap;
import org.apache.activemq.amqp.protocol.marshaller.AmqpEncodingError;
import org.apache.activemq.amqp.protocol.types.AmqpUbyte;

/**
 * Represents a defined scopes
 */
public enum AmqpScope {

    SESSION(new Short("0")),
    CONTAINER(new Short("1"));

    private static final HashMap<Short, AmqpScope> LOOKUP = new HashMap<Short, AmqpScope>(2);
    static {
        for (AmqpScope scope : AmqpScope.values()) {
            LOOKUP.put(scope.value.getValue(), scope);
        }
    }

    private final AmqpUbyte value;

    private AmqpScope(Short value) {
        this.value = new AmqpUbyte.AmqpUbyteBean(value);
    }

    public final AmqpUbyte getValue() {
        return value;
    }

    public static final AmqpScope get(AmqpUbyte value) throws AmqpEncodingError{
        AmqpScope scope= LOOKUP.get(value.getValue());
        if (scope == null) {
            //TODO perhaps this should be an IllegalArgumentException?
            throw new AmqpEncodingError("Unknown scope: " + value + " expected one of " + LOOKUP.keySet());
        }
        return scope;
    }
}