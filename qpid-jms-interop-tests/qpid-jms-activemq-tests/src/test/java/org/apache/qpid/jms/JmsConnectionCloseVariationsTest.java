/*
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
package org.apache.qpid.jms;

import jakarta.jms.Connection;

import org.apache.activemq.junit.ActiveMQTestRunner;
import org.apache.activemq.junit.Repeat;
import org.apache.qpid.jms.support.AmqpTestSupport;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * A test case for Connection close called under different circumstances.
 */
@RunWith(ActiveMQTestRunner.class)
public class JmsConnectionCloseVariationsTest extends AmqpTestSupport {

    @Test(timeout=60000)
    public void testCloseAfterBrokerStopped() throws Exception {
        doTestConnectionClosedAfterBrokerStopped();
    }

    @Repeat(repetitions = 25)
    @Test(timeout=90000)
    public void testCloseAfterBrokerStoppedRepeated() throws Exception {
        doTestConnectionClosedAfterBrokerStopped();
    }

    private void doTestConnectionClosedAfterBrokerStopped() throws Exception {
        Connection connection = createAmqpConnection();
        connection.start();
        stopPrimaryBroker();
        connection.close();
    }

    @Test(timeout=60000)
    public void testCloseBeforeBrokerStopped() throws Exception {
        doTestConnectionClosedBeforeBrokerStopped();
    }

    @Repeat(repetitions = 25)
    @Test(timeout=90000)
    public void testCloseBeforeBrokerStoppedRepeated() throws Exception {
        doTestConnectionClosedBeforeBrokerStopped();
    }

    private void doTestConnectionClosedBeforeBrokerStopped() throws Exception {
        Connection connection = createAmqpConnection();
        connection.start();
        connection.close();
        stopPrimaryBroker();
    }
}
