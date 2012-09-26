/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package example.wildcard;

import example.util.Util;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.command.ActiveMQTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jms.*;
import java.util.Scanner;

/**
 * @author <a href="http://www.christianposta.com/blog">Christian Posta</a>
 */
public class Client {
    private static final Logger LOG = LoggerFactory.getLogger(Client.class);
    private static final Boolean NON_TRANSACTED = false;
    private static final String BROKER_HOST = "tcp://localhost:%d";
    private static final int BROKER_PORT = Util.getBrokerPort();
    private static final String BROKER_URL = String.format(BROKER_HOST, BROKER_PORT);

    public static void main(String[] args) {

        ActiveMQConnectionFactory connectionFactory = new ActiveMQConnectionFactory("admin", "password", BROKER_URL);
        Connection connection = null;

        try {
            Topic senderTopic = new ActiveMQTopic(System.getProperty("topicName"));

            connection = connectionFactory.createConnection("admin", "password");

            Session senderSession = connection.createSession(NON_TRANSACTED, Session.AUTO_ACKNOWLEDGE);
            MessageProducer sender = senderSession.createProducer(senderTopic);

            Session receiverSession = connection.createSession(NON_TRANSACTED, Session.AUTO_ACKNOWLEDGE);

            String policyType = System.getProperty("wildcard", ".*");
            String receiverTopicName = senderTopic.getTopicName() + policyType;
            Topic receiverTopic = receiverSession.createTopic(receiverTopicName);

            MessageConsumer receiver = receiverSession.createConsumer(receiverTopic);
            receiver.setMessageListener(new MessageListener() {
                public void onMessage(Message message) {
                    try {
                        if (message instanceof TextMessage) {
                            String text = ((TextMessage) message).getText();
                            LOG.info("We received a new message: " + text);
                        }
                    } catch (JMSException e) {
                        LOG.error("Could not read the receiver's topic because of a JMSException", e);
                    }
                }
            });

            connection.start();
            System.out.println("Listening on '" + receiverTopicName + "'");
            System.out.println("Enter a message to send: ");

            Scanner inputReader = new Scanner(System.in);

            while (true) {
                String line = inputReader.nextLine();
                if (line == null) {
                    LOG.info("Done!");
                    break;
                } else if (line.length() > 0) {
                    try {
                        TextMessage message = senderSession.createTextMessage();
                        message.setText(line);
                        LOG.info("Sending a message: " + message.getText());
                        sender.send(message);
                    } catch (JMSException e) {
                        LOG.error("Exception during publishing a message: ", e);
                    }
                }
            }

            receiver.close();
            receiverSession.close();
            sender.close();
            senderSession.close();

        } catch (Exception e) {
            LOG.error("Caught exception!", e);
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (JMSException e) {
                    LOG.error("When trying to close connection: ", e);
                }
            }
        }

    }
}
