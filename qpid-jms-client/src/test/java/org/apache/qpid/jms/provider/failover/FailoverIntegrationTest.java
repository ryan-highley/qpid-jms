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
package org.apache.qpid.jms.provider.failover;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.Session;

import org.apache.qpid.jms.JmsConnection;
import org.apache.qpid.jms.JmsConnectionFactory;
import org.apache.qpid.jms.JmsDefaultConnectionListener;
import org.apache.qpid.jms.test.QpidJmsTestCase;
import org.apache.qpid.jms.test.testpeer.TestAmqpPeer;
import org.apache.qpid.jms.test.testpeer.describedtypes.Accepted;
import org.apache.qpid.jms.test.testpeer.matchers.sections.MessageAnnotationsSectionMatcher;
import org.apache.qpid.jms.test.testpeer.matchers.sections.MessageHeaderSectionMatcher;
import org.apache.qpid.jms.test.testpeer.matchers.sections.TransferPayloadCompositeMatcher;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FailoverIntegrationTest extends QpidJmsTestCase {

    private static final Logger LOG = LoggerFactory.getLogger(FailoverIntegrationTest.class);

    @Test(timeout = 20000)
    public void testFailoverHandlesImmediateTransportDropAfterConnect() throws Exception {
        try (TestAmqpPeer originalPeer = new TestAmqpPeer();
             TestAmqpPeer rejectingPeer = new TestAmqpPeer();
             TestAmqpPeer finalPeer = new TestAmqpPeer();) {

            final CountDownLatch originalConnected = new CountDownLatch(1);
            final CountDownLatch finalConnected = new CountDownLatch(1);

            // Create a peer to connect to, one to fail to reconnect to, and a final one to reconnect to
            final String originalURI = createPeerURI(originalPeer);
            final String rejectingURI = createPeerURI(rejectingPeer);
            final String finalURI = createPeerURI(finalPeer);

            LOG.info("Original peer is at: {}", originalURI);
            LOG.info("Rejecting peer is at: {}", rejectingURI);
            LOG.info("Final peer is at: {}", finalURI);

            // Connect to the first
            originalPeer.expectSaslAnonymousConnect();
            originalPeer.expectBegin();

            final JmsConnection connection = establishAnonymousConnecton(originalPeer, rejectingPeer, finalPeer);
            connection.addConnectionListener(new JmsDefaultConnectionListener() {
                @Override
                public void onConnectionEstablished(URI remoteURI) {
                    LOG.info("Connection Established: {}", remoteURI);
                    if (originalURI.equals(remoteURI.toString())) {
                        originalConnected.countDown();
                    }
                }

                @Override
                public void onConnectionRestored(URI remoteURI) {
                    LOG.info("Connection Restored: {}", remoteURI);
                    if (finalURI.equals(remoteURI.toString())) {
                        finalConnected.countDown();
                    }
                }
            });
            connection.start();

            assertTrue("Should connect to original peer", originalConnected.await(5, TimeUnit.SECONDS));
            assertEquals("should not yet have connected to final peer", 1L, finalConnected.getCount());

            // Set expectations on rejecting and final peer
            rejectingPeer.expectSaslHeaderThenDrop();

            finalPeer.expectSaslAnonymousConnect();
            finalPeer.expectBegin();

            // Close the original peer and wait for things to shake out.
            originalPeer.close();

            rejectingPeer.waitForAllHandlersToComplete(2000);

            assertTrue("Should connect to final peer", finalConnected.await(5, TimeUnit.SECONDS));

            //Shut it down
            finalPeer.expectClose();
            connection.close();
            finalPeer.waitForAllHandlersToComplete(1000);
        }
    }

    @Test(timeout = 20000)
    public void testFailoverHandlesTransportDropBeforeDispositionRecieived() throws Exception {
        try (TestAmqpPeer originalPeer = new TestAmqpPeer();
             TestAmqpPeer finalPeer = new TestAmqpPeer();) {

            final CountDownLatch originalConnected = new CountDownLatch(1);
            final CountDownLatch finalConnected = new CountDownLatch(1);

            // Create a peer to connect to, then one to reconnect to
            final String originalURI = createPeerURI(originalPeer);
            final String finalURI = createPeerURI(finalPeer);

            LOG.info("Original peer is at: {}", originalURI);
            LOG.info("Final peer is at: {}", finalURI);

            // Connect to the first peer
            originalPeer.expectSaslAnonymousConnect();
            originalPeer.expectBegin();

            final JmsConnection connection = establishAnonymousConnecton(originalPeer, finalPeer);
            connection.addConnectionListener(new JmsDefaultConnectionListener() {
                @Override
                public void onConnectionEstablished(URI remoteURI) {
                    LOG.info("Connection Established: {}", remoteURI);
                    if (originalURI.equals(remoteURI.toString())) {
                        originalConnected.countDown();
                    }
                }

                @Override
                public void onConnectionRestored(URI remoteURI) {
                    LOG.info("Connection Restored: {}", remoteURI);
                    if (finalURI.equals(remoteURI.toString())) {
                        finalConnected.countDown();
                    }
                }
            });
            connection.start();

            assertTrue("Should connect to original peer", originalConnected.await(5, TimeUnit.SECONDS));

            // Create session+producer, send a persistent message on auto-ack session for synchronous send
            originalPeer.expectBegin();

            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Queue queue = session.createQueue("myQueue");

            originalPeer.expectSenderAttach();

            final MessageProducer producer = session.createProducer(queue);

            MessageHeaderSectionMatcher headersMatcher = new MessageHeaderSectionMatcher(true).withDurable(equalTo(true));
            MessageAnnotationsSectionMatcher msgAnnotationsMatcher = new MessageAnnotationsSectionMatcher(true);
            TransferPayloadCompositeMatcher messageMatcher = new TransferPayloadCompositeMatcher();
            messageMatcher.setHeadersMatcher(headersMatcher);
            messageMatcher.setMessageAnnotationsMatcher(msgAnnotationsMatcher);

            final Message message = session.createTextMessage();

            final CountDownLatch senderCompleted = new CountDownLatch(1);
            final AtomicReference<Throwable> problem = new AtomicReference<Throwable>();

            // Have the peer expect the message but NOT send any disposition for it
            originalPeer.expectTransfer(messageMatcher, nullValue(), false, false, null, true);

            Thread runner = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        producer.send(message);
                    } catch (Throwable t) {
                        problem.set(t);
                        LOG.error("Problem in sending thread", t);
                    }
                    finally {
                        senderCompleted.countDown();
                    }
                }
            });
            runner.start();

            // Wait for the message to have been sent and received by peer
            originalPeer.waitForAllHandlersToComplete(3000);

            // Set the secondary peer to expect connection restoration, this time send disposition accepting the message
            finalPeer.expectSaslAnonymousConnect();
            finalPeer.expectBegin();
            finalPeer.expectBegin();
            finalPeer.expectSenderAttach();
            finalPeer.expectTransfer(messageMatcher, nullValue(), false, true, new Accepted(), true);

            assertEquals("Should not yet have connected to final peer", 1L, finalConnected.getCount());
            assertEquals("Sender thread should not yet have completed", 1L, senderCompleted.getCount());

            // Close the original peer to provoke reconnect, while send() is still outstanding
            originalPeer.close();

            assertTrue("Should connect to final peer", finalConnected.await(5, TimeUnit.SECONDS));

            boolean await = senderCompleted.await(5, TimeUnit.SECONDS);
            Throwable t = problem.get();
            assertTrue("Sender thread should have completed. Problem: " + t, await);

            //Shut it down
            finalPeer.expectClose();
            connection.close();
            finalPeer.waitForAllHandlersToComplete(1000);
        }
    }

    private JmsConnection establishAnonymousConnecton(TestAmqpPeer... peers) throws JMSException {
        if(peers.length == 0) {
            throw new IllegalArgumentException("No test peers were given, at least 1 required");
        }

        String remoteURI = "failover:(";
        boolean first = true;
        for(TestAmqpPeer peer : peers) {
            if (!first) {
                remoteURI += ",";
            }
            remoteURI += createPeerURI(peer);
            first = false;
        }
        remoteURI += ")?failover.maxReconnectAttempts=10";

        ConnectionFactory factory = new JmsConnectionFactory(remoteURI);
        Connection connection = factory.createConnection();

        return (JmsConnection) connection;
    }

    private String createPeerURI(TestAmqpPeer peer) {
        return "amqp://localhost:" + peer.getServerPort();
    }
}