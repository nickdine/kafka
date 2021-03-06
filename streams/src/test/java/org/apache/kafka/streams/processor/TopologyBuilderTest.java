/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kafka.streams.processor;

import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.errors.StreamsException;
import org.apache.kafka.streams.errors.TopologyBuilderException;
import org.apache.kafka.streams.processor.TopologyBuilder.TopicsInfo;
import org.apache.kafka.streams.processor.internals.InternalTopicConfig;
import org.apache.kafka.streams.processor.internals.InternalTopicManager;
import org.apache.kafka.streams.processor.internals.ProcessorNode;
import org.apache.kafka.streams.processor.internals.ProcessorStateManager;
import org.apache.kafka.streams.processor.internals.ProcessorTopology;
import org.apache.kafka.streams.state.Stores;
import org.apache.kafka.streams.processor.internals.StreamPartitionAssignor;
import org.apache.kafka.streams.state.internals.RocksDBWindowStoreSupplier;
import org.apache.kafka.test.MockProcessorSupplier;
import org.apache.kafka.test.MockStateStoreSupplier;
import org.apache.kafka.test.ProcessorTopologyTestDriver;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

import static org.apache.kafka.common.utils.Utils.mkList;
import static org.apache.kafka.common.utils.Utils.mkSet;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TopologyBuilderTest {

    @Test(expected = TopologyBuilderException.class)
    public void testAddSourceWithSameName() {
        final TopologyBuilder builder = new TopologyBuilder();

        builder.addSource("source", "topic-1");
        builder.addSource("source", "topic-2");
    }

    @Test(expected = TopologyBuilderException.class)
    public void testAddSourceWithSameTopic() {
        final TopologyBuilder builder = new TopologyBuilder();

        builder.addSource("source", "topic-1");
        builder.addSource("source-2", "topic-1");
    }

    @Test(expected = TopologyBuilderException.class)
    public void testAddProcessorWithSameName() {
        final TopologyBuilder builder = new TopologyBuilder();

        builder.addSource("source", "topic-1");
        builder.addProcessor("processor", new MockProcessorSupplier(), "source");
        builder.addProcessor("processor", new MockProcessorSupplier(), "source");
    }

    @Test(expected = TopologyBuilderException.class)
    public void testAddProcessorWithWrongParent() {
        final TopologyBuilder builder = new TopologyBuilder();

        builder.addProcessor("processor", new MockProcessorSupplier(), "source");
    }

    @Test(expected = TopologyBuilderException.class)
    public void testAddProcessorWithSelfParent() {
        final TopologyBuilder builder = new TopologyBuilder();

        builder.addProcessor("processor", new MockProcessorSupplier(), "processor");
    }

    @Test(expected = TopologyBuilderException.class)
    public void testAddSinkWithSameName() {
        final TopologyBuilder builder = new TopologyBuilder();

        builder.addSource("source", "topic-1");
        builder.addSink("sink", "topic-2", "source");
        builder.addSink("sink", "topic-3", "source");
    }

    @Test(expected = TopologyBuilderException.class)
    public void testAddSinkWithWrongParent() {
        final TopologyBuilder builder = new TopologyBuilder();

        builder.addSink("sink", "topic-2", "source");
    }

    @Test(expected = TopologyBuilderException.class)
    public void testAddSinkWithSelfParent() {
        final TopologyBuilder builder = new TopologyBuilder();

        builder.addSink("sink", "topic-2", "sink");
    }

    @Test
    public void testAddSinkConnectedWithParent() {
        final TopologyBuilder builder = new TopologyBuilder();

        builder.addSource("source", "source-topic");
        builder.addSink("sink", "dest-topic", "source");

        Map<Integer, Set<String>> nodeGroups = builder.nodeGroups();
        Set<String> nodeGroup = nodeGroups.get(0);

        assertTrue(nodeGroup.contains("sink"));
        assertTrue(nodeGroup.contains("source"));

    }

    @Test
    public void testAddSinkConnectedWithMultipleParent() {
        final TopologyBuilder builder = new TopologyBuilder();

        builder.addSource("source", "source-topic");
        builder.addSource("sourceII", "source-topicII");
        builder.addSink("sink", "dest-topic", "source", "sourceII");

        Map<Integer, Set<String>> nodeGroups = builder.nodeGroups();
        Set<String> nodeGroup = nodeGroups.get(0);

        assertTrue(nodeGroup.contains("sink"));
        assertTrue(nodeGroup.contains("source"));
        assertTrue(nodeGroup.contains("sourceII"));

    }

    @Test
    public void testSourceTopics() {
        final TopologyBuilder builder = new TopologyBuilder();
        builder.setApplicationId("X");
        builder.addSource("source-1", "topic-1");
        builder.addSource("source-2", "topic-2");
        builder.addSource("source-3", "topic-3");
        builder.addInternalTopic("topic-3");

        Set<String> expected = new HashSet<>();
        expected.add("topic-1");
        expected.add("topic-2");
        expected.add("X-topic-3");

        assertEquals(expected, builder.sourceTopics());
    }

    @Test
    public void testPatternSourceTopic() {
        final TopologyBuilder builder = new TopologyBuilder();
        Pattern expectedPattern = Pattern.compile("topic-\\d");
        builder.addSource("source-1", expectedPattern);
        assertEquals(expectedPattern.pattern(), builder.sourceTopicPattern().pattern());
    }

    @Test
    public void testAddMoreThanOnePatternSourceNode() {
        final TopologyBuilder builder = new TopologyBuilder();
        Pattern expectedPattern = Pattern.compile("topics[A-Z]|.*-\\d");
        builder.addSource("source-1", Pattern.compile("topics[A-Z]"));
        builder.addSource("source-2", Pattern.compile(".*-\\d"));
        assertEquals(expectedPattern.pattern(), builder.sourceTopicPattern().pattern());
    }

    @Test
    public void testSubscribeTopicNameAndPattern() {
        final TopologyBuilder builder = new TopologyBuilder();
        Pattern expectedPattern = Pattern.compile("topic-foo|topic-bar|.*-\\d");
        builder.addSource("source-1", "topic-foo", "topic-bar");
        builder.addSource("source-2", Pattern.compile(".*-\\d"));
        assertEquals(expectedPattern.pattern(), builder.sourceTopicPattern().pattern());
    }

    @Test(expected = TopologyBuilderException.class)
    public void testPatternMatchesAlreadyProvidedTopicSource() {
        final TopologyBuilder builder = new TopologyBuilder();
        builder.addSource("source-1", "foo");
        builder.addSource("source-2", Pattern.compile("f.*"));
    }

    @Test(expected = TopologyBuilderException.class)
    public void testNamedTopicMatchesAlreadyProvidedPattern() {
        final TopologyBuilder builder = new TopologyBuilder();
        builder.addSource("source-1", Pattern.compile("f.*"));
        builder.addSource("source-2", "foo");
    }

    @Test(expected = TopologyBuilderException.class)
    public void testAddStateStoreWithNonExistingProcessor() {
        final TopologyBuilder builder = new TopologyBuilder();

        builder.addStateStore(new MockStateStoreSupplier("store", false), "no-such-processsor");
    }

    @Test(expected = TopologyBuilderException.class)
    public void testAddStateStoreWithSource() {
        final TopologyBuilder builder = new TopologyBuilder();

        builder.addSource("source-1", "topic-1");
        builder.addStateStore(new MockStateStoreSupplier("store", false), "source-1");
    }

    @Test(expected = TopologyBuilderException.class)
    public void testAddStateStoreWithSink() {
        final TopologyBuilder builder = new TopologyBuilder();

        builder.addSink("sink-1", "topic-1");
        builder.addStateStore(new MockStateStoreSupplier("store", false), "sink-1");
    }

    @Test(expected = TopologyBuilderException.class)
    public void testAddStateStoreWithDuplicates() {
        final TopologyBuilder builder = new TopologyBuilder();

        builder.addStateStore(new MockStateStoreSupplier("store", false));
        builder.addStateStore(new MockStateStoreSupplier("store", false));
    }

    @Test
    public void testAddStateStore() {
        final TopologyBuilder builder = new TopologyBuilder();

        StateStoreSupplier supplier = new MockStateStoreSupplier("store-1", false);
        builder.addStateStore(supplier);
        builder.setApplicationId("X");
        builder.addSource("source-1", "topic-1");
        builder.addProcessor("processor-1", new MockProcessorSupplier(), "source-1");

        assertEquals(0, builder.build(null).stateStores().size());

        builder.connectProcessorAndStateStores("processor-1", "store-1");

        List<StateStore> suppliers = builder.build(null).stateStores();
        assertEquals(1, suppliers.size());
        assertEquals(supplier.name(), suppliers.get(0).name());
    }

    @Test
    public void testTopicGroups() {
        final TopologyBuilder builder = new TopologyBuilder();
        builder.setApplicationId("X");
        builder.addInternalTopic("topic-1x");
        builder.addSource("source-1", "topic-1", "topic-1x");
        builder.addSource("source-2", "topic-2");
        builder.addSource("source-3", "topic-3");
        builder.addSource("source-4", "topic-4");
        builder.addSource("source-5", "topic-5");

        builder.addProcessor("processor-1", new MockProcessorSupplier(), "source-1");

        builder.addProcessor("processor-2", new MockProcessorSupplier(), "source-2", "processor-1");
        builder.copartitionSources(mkList("source-1", "source-2"));

        builder.addProcessor("processor-3", new MockProcessorSupplier(), "source-3", "source-4");

        Map<Integer, TopicsInfo> topicGroups = builder.topicGroups();

        Map<Integer, TopicsInfo> expectedTopicGroups = new HashMap<>();
        expectedTopicGroups.put(0, new TopicsInfo(Collections.<String>emptySet(), mkSet("topic-1", "X-topic-1x", "topic-2"), Collections.<String, InternalTopicConfig>emptyMap(), Collections.<String, InternalTopicConfig>emptyMap()));
        expectedTopicGroups.put(1, new TopicsInfo(Collections.<String>emptySet(), mkSet("topic-3", "topic-4"), Collections.<String, InternalTopicConfig>emptyMap(), Collections.<String, InternalTopicConfig>emptyMap()));
        expectedTopicGroups.put(2, new TopicsInfo(Collections.<String>emptySet(), mkSet("topic-5"), Collections.<String, InternalTopicConfig>emptyMap(), Collections.<String, InternalTopicConfig>emptyMap()));

        assertEquals(3, topicGroups.size());
        assertEquals(expectedTopicGroups, topicGroups);

        Collection<Set<String>> copartitionGroups = builder.copartitionGroups();

        assertEquals(mkSet(mkSet("topic-1", "X-topic-1x", "topic-2")), new HashSet<>(copartitionGroups));
    }

    @Test
    public void testTopicGroupsByStateStore() {
        final TopologyBuilder builder = new TopologyBuilder();
        builder.setApplicationId("X");
        builder.addSource("source-1", "topic-1", "topic-1x");
        builder.addSource("source-2", "topic-2");
        builder.addSource("source-3", "topic-3");
        builder.addSource("source-4", "topic-4");
        builder.addSource("source-5", "topic-5");

        builder.addProcessor("processor-1", new MockProcessorSupplier(), "source-1");
        builder.addProcessor("processor-2", new MockProcessorSupplier(), "source-2");
        builder.addStateStore(new MockStateStoreSupplier("store-1", false), "processor-1", "processor-2");

        builder.addProcessor("processor-3", new MockProcessorSupplier(), "source-3");
        builder.addProcessor("processor-4", new MockProcessorSupplier(), "source-4");
        builder.addStateStore(new MockStateStoreSupplier("store-2", false), "processor-3", "processor-4");

        builder.addProcessor("processor-5", new MockProcessorSupplier(), "source-5");
        StateStoreSupplier supplier = new MockStateStoreSupplier("store-3", false);
        builder.addStateStore(supplier);
        builder.connectProcessorAndStateStores("processor-5", "store-3");

        Map<Integer, TopicsInfo> topicGroups = builder.topicGroups();

        Map<Integer, TopicsInfo> expectedTopicGroups = new HashMap<>();
        final String store1 = ProcessorStateManager.storeChangelogTopic("X", "store-1");
        final String store2 = ProcessorStateManager.storeChangelogTopic("X", "store-2");
        final String store3 = ProcessorStateManager.storeChangelogTopic("X", "store-3");
        expectedTopicGroups.put(0, new TopicsInfo(Collections.<String>emptySet(), mkSet("topic-1", "topic-1x", "topic-2"),
                                                  Collections.<String, InternalTopicConfig>emptyMap(),
                                                  Collections.singletonMap(store1,
                                                                           new InternalTopicConfig(
                                                                                   store1,
                                                                                   Collections.singleton(InternalTopicConfig.CleanupPolicy.compact),
                                                                                   Collections.<String, String>emptyMap()))));
        expectedTopicGroups.put(1, new TopicsInfo(Collections.<String>emptySet(), mkSet("topic-3", "topic-4"),
                                                  Collections.<String, InternalTopicConfig>emptyMap(),
                                                  Collections.singletonMap(store2,
                                                                           new InternalTopicConfig(
                                                                                   store2,
                                                                                   Collections.singleton(InternalTopicConfig.CleanupPolicy.compact),
                                                                                   Collections.<String, String>emptyMap()))));
        expectedTopicGroups.put(2, new TopicsInfo(Collections.<String>emptySet(), mkSet("topic-5"),
                                                  Collections.<String, InternalTopicConfig>emptyMap(),
                                                  Collections.singletonMap(store3,
                                                                           new InternalTopicConfig(
                                                                                   store3,
                                                                                   Collections.singleton(InternalTopicConfig.CleanupPolicy.compact),
                                                                                   Collections.<String, String>emptyMap()))));



        assertEquals(3, topicGroups.size());
        assertEquals(expectedTopicGroups, topicGroups);
    }

    @Test
    public void testBuild() {
        final TopologyBuilder builder = new TopologyBuilder();

        builder.addSource("source-1", "topic-1", "topic-1x");
        builder.addSource("source-2", "topic-2");
        builder.addSource("source-3", "topic-3");
        builder.addSource("source-4", "topic-4");
        builder.addSource("source-5", "topic-5");

        builder.addProcessor("processor-1", new MockProcessorSupplier(), "source-1");
        builder.addProcessor("processor-2", new MockProcessorSupplier(), "source-2", "processor-1");
        builder.addProcessor("processor-3", new MockProcessorSupplier(), "source-3", "source-4");

        builder.setApplicationId("X");
        ProcessorTopology topology0 = builder.build(0);
        ProcessorTopology topology1 = builder.build(1);
        ProcessorTopology topology2 = builder.build(2);

        assertEquals(mkSet("source-1", "source-2", "processor-1", "processor-2"), nodeNames(topology0.processors()));
        assertEquals(mkSet("source-3", "source-4", "processor-3"), nodeNames(topology1.processors()));
        assertEquals(mkSet("source-5"), nodeNames(topology2.processors()));
    }

    @Test(expected = NullPointerException.class)
    public void shouldNotAllowNullNameWhenAddingSink() throws Exception {
        final TopologyBuilder builder = new TopologyBuilder();
        builder.addSink(null, "topic");
    }

    @Test(expected = NullPointerException.class)
    public void shouldNotAllowNullTopicWhenAddingSink() throws Exception {
        final TopologyBuilder builder = new TopologyBuilder();
        builder.addSink("name", null);
    }

    @Test(expected = NullPointerException.class)
    public void shouldNotAllowNullNameWhenAddingProcessor() throws Exception {
        final TopologyBuilder builder = new TopologyBuilder();
        builder.addProcessor(null, new ProcessorSupplier() {
            @Override
            public Processor get() {
                return null;
            }
        });
    }

    @Test(expected = NullPointerException.class)
    public void shouldNotAllowNullProcessorSupplier() throws Exception {
        final TopologyBuilder builder = new TopologyBuilder();
        builder.addProcessor("name", null);
    }

    @Test(expected = NullPointerException.class)
    public void shouldNotAllowNullNameWhenAddingSource() throws Exception {
        final TopologyBuilder builder = new TopologyBuilder();
        builder.addSource(null, Pattern.compile(".*"));
    }

    @Test(expected = NullPointerException.class)
    public void shouldNotAllowNullProcessorNameWhenConnectingProcessorAndStateStores() throws Exception {
        final TopologyBuilder builder = new TopologyBuilder();
        builder.connectProcessorAndStateStores(null, "store");
    }

    @Test(expected = NullPointerException.class)
    public void shouldNotAddNullInternalTopic() throws Exception {
        final TopologyBuilder builder = new TopologyBuilder();
        builder.addInternalTopic(null);
    }

    @Test(expected = NullPointerException.class)
    public void shouldNotSetApplicationIdToNull() throws Exception {
        final TopologyBuilder builder = new TopologyBuilder();
        builder.setApplicationId(null);
    }

    @Test(expected = NullPointerException.class)
    public void shouldNotAddNullStateStoreSupplier() throws Exception {
        final TopologyBuilder builder = new TopologyBuilder();
        builder.addStateStore(null);
    }

    private Set<String> nodeNames(Collection<ProcessorNode> nodes) {
        Set<String> nodeNames = new HashSet<>();
        for (ProcessorNode node : nodes) {
            nodeNames.add(node.name());
        }
        return nodeNames;
    }

    @Test
    public void shouldAssociateStateStoreNameWhenStateStoreSupplierIsInternal() throws Exception {
        final TopologyBuilder builder = new TopologyBuilder();
        builder.addSource("source", "topic");
        builder.addProcessor("processor", new MockProcessorSupplier(), "source");
        builder.addStateStore(new MockStateStoreSupplier("store", false), "processor");
        final Map<String, Set<String>> stateStoreNameToSourceTopic = builder.stateStoreNameToSourceTopics();
        assertEquals(1, stateStoreNameToSourceTopic.size());
        assertEquals(Collections.singleton("topic"), stateStoreNameToSourceTopic.get("store"));
    }

    @Test
    public void shouldAssociateStateStoreNameWhenStateStoreSupplierIsExternal() throws Exception {
        final TopologyBuilder builder = new TopologyBuilder();
        builder.addSource("source", "topic");
        builder.addProcessor("processor", new MockProcessorSupplier(), "source");
        builder.addStateStore(new MockStateStoreSupplier("store", false), "processor");
        final Map<String, Set<String>> stateStoreNameToSourceTopic = builder.stateStoreNameToSourceTopics();
        assertEquals(1, stateStoreNameToSourceTopic.size());
        assertEquals(Collections.singleton("topic"), stateStoreNameToSourceTopic.get("store"));
    }

    @Test
    public void shouldCorrectlyMapStateStoreToInternalTopics() throws Exception {
        final TopologyBuilder builder = new TopologyBuilder();
        builder.setApplicationId("appId");
        builder.addInternalTopic("internal-topic");
        builder.addSource("source", "internal-topic");
        builder.addProcessor("processor", new MockProcessorSupplier(), "source");
        builder.addStateStore(new MockStateStoreSupplier("store", false), "processor");
        final Map<String, Set<String>> stateStoreNameToSourceTopic = builder.stateStoreNameToSourceTopics();
        assertEquals(1, stateStoreNameToSourceTopic.size());
        assertEquals(Collections.singleton("appId-internal-topic"), stateStoreNameToSourceTopic.get("store"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldAddInternalTopicConfigWithCompactAndDeleteSetForWindowStores() throws Exception {
        final TopologyBuilder builder = new TopologyBuilder();
        builder.setApplicationId("appId");
        builder.addSource("source", "topic");
        builder.addProcessor("processor", new MockProcessorSupplier(), "source");
        builder.addStateStore(new RocksDBWindowStoreSupplier("store", 30000, 3, false, null, null, 10000, true, Collections.<String, String>emptyMap(), false), "processor");
        final Map<Integer, TopicsInfo> topicGroups = builder.topicGroups();
        final TopicsInfo topicsInfo = topicGroups.values().iterator().next();
        final InternalTopicConfig topicConfig = topicsInfo.stateChangelogTopics.get("appId-store-changelog");
        final Properties properties = topicConfig.toProperties(0);
        final List<String> policies = Arrays.asList(properties.getProperty(InternalTopicManager.CLEANUP_POLICY_PROP).split(","));
        assertEquals("appId-store-changelog", topicConfig.name());
        assertTrue(policies.contains("compact"));
        assertTrue(policies.contains("delete"));
        assertEquals(2, policies.size());
        assertEquals("30000", properties.getProperty(InternalTopicManager.RETENTION_MS));
        assertEquals(2, properties.size());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldAddInternalTopicConfigWithCompactForNonWindowStores() throws Exception {
        final TopologyBuilder builder = new TopologyBuilder();
        builder.setApplicationId("appId");
        builder.addSource("source", "topic");
        builder.addProcessor("processor", new MockProcessorSupplier(), "source");
        builder.addStateStore(new MockStateStoreSupplier("name", true), "processor");
        final Map<Integer, TopicsInfo> topicGroups = builder.topicGroups();
        final TopicsInfo topicsInfo = topicGroups.values().iterator().next();
        final InternalTopicConfig topicConfig = topicsInfo.stateChangelogTopics.get("appId-name-changelog");
        final Properties properties = topicConfig.toProperties(0);
        assertEquals("appId-name-changelog", topicConfig.name());
        assertEquals("compact", properties.getProperty(InternalTopicManager.CLEANUP_POLICY_PROP));
        assertEquals(1, properties.size());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldAddInternalTopicConfigWithCleanupPolicyDeleteForInternalTopics() throws Exception {
        final TopologyBuilder builder = new TopologyBuilder();
        builder.setApplicationId("appId");
        builder.addInternalTopic("foo");
        builder.addSource("source", "foo");
        final TopicsInfo topicsInfo = builder.topicGroups().values().iterator().next();
        final InternalTopicConfig topicConfig = topicsInfo.repartitionSourceTopics.get("appId-foo");
        final Properties properties = topicConfig.toProperties(0);
        assertEquals("appId-foo", topicConfig.name());
        assertEquals("delete", properties.getProperty(InternalTopicManager.CLEANUP_POLICY_PROP));
        assertEquals(1, properties.size());
    }


    @Test(expected = TopologyBuilderException.class)
    public void shouldThroughOnUnassignedStateStoreAccess() {
        final String sourceNodeName = "source";
        final String goodNodeName = "goodGuy";
        final String badNodeName = "badGuy";

        final Properties config = new Properties();
        config.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "host:1");
        config.put(StreamsConfig.APPLICATION_ID_CONFIG, "appId");
        final StreamsConfig streamsConfig = new StreamsConfig(config);

        try {
            final TopologyBuilder builder = new TopologyBuilder();
            builder
                .addSource(sourceNodeName, "topic")
                .addProcessor(goodNodeName, new LocalMockProcessorSupplier(), sourceNodeName)
                .addStateStore(
                    Stores.create(LocalMockProcessorSupplier.STORE_NAME).withStringKeys().withStringValues().inMemory().build(),
                    goodNodeName)
                .addProcessor(badNodeName, new LocalMockProcessorSupplier(), sourceNodeName);

            final ProcessorTopologyTestDriver driver = new ProcessorTopologyTestDriver(streamsConfig, builder, LocalMockProcessorSupplier.STORE_NAME);
            driver.process("topic", null, null);
        } catch (final StreamsException e) {
            final Throwable cause = e.getCause();
            if (cause != null
                && cause instanceof TopologyBuilderException
                && cause.getMessage().equals("Invalid topology building: Processor " + badNodeName + " has no access to StateStore " + LocalMockProcessorSupplier.STORE_NAME)) {
                throw (TopologyBuilderException) cause;
            } else {
                throw new RuntimeException("Did expect different exception. Did catch:", e);
            }
        }
    }

    private static class LocalMockProcessorSupplier implements ProcessorSupplier {
        final static String STORE_NAME = "store";

        @Override
        public Processor get() {
            return new Processor() {
                @Override
                public void init(ProcessorContext context) {
                    context.getStateStore(STORE_NAME);
                }

                @Override
                public void process(Object key, Object value) {
                }

                @Override
                public void punctuate(long timestamp) {
                }

                @Override
                public void close() {
                }
            };
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldSetCorrectSourceNodesWithRegexUpdatedTopics() throws Exception {
        final TopologyBuilder builder = new TopologyBuilder();
        builder.addSource("source-1", "topic-foo");
        builder.addSource("source-2", Pattern.compile("topic-[A-C]"));
        builder.addSource("source-3", Pattern.compile("topic-\\d"));

        StreamPartitionAssignor.SubscriptionUpdates subscriptionUpdates = new StreamPartitionAssignor.SubscriptionUpdates();
        Field updatedTopicsField  = subscriptionUpdates.getClass().getDeclaredField("updatedTopicSubscriptions");
        updatedTopicsField.setAccessible(true);

        Set<String> updatedTopics = (Set<String>) updatedTopicsField.get(subscriptionUpdates);

        updatedTopics.add("topic-B");
        updatedTopics.add("topic-3");
        updatedTopics.add("topic-A");

        builder.updateSubscriptions(subscriptionUpdates, null);
        builder.setApplicationId("test-id");

        Map<Integer, TopicsInfo> topicGroups = builder.topicGroups();
        assertTrue(topicGroups.get(0).sourceTopics.contains("topic-foo"));
        assertTrue(topicGroups.get(1).sourceTopics.contains("topic-A"));
        assertTrue(topicGroups.get(1).sourceTopics.contains("topic-B"));
        assertTrue(topicGroups.get(2).sourceTopics.contains("topic-3"));

    }
}
