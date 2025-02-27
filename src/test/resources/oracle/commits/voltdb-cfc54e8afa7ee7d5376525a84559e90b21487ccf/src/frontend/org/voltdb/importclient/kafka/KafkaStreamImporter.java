/* This file is part of VoltDB.
 * Copyright (C) 2008-2015 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.voltdb.importclient.kafka;

import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import kafka.api.ConsumerMetadataRequest;
import kafka.api.FetchRequest;
import kafka.api.FetchRequestBuilder;
import kafka.api.PartitionOffsetRequestInfo;
import kafka.cluster.Broker;
import kafka.common.ErrorMapping;
import kafka.common.OffsetAndMetadata;
import kafka.common.TopicAndPartition;
import kafka.javaapi.ConsumerMetadataResponse;
import kafka.javaapi.FetchResponse;
import kafka.javaapi.OffsetCommitRequest;
import kafka.javaapi.OffsetCommitResponse;
import kafka.javaapi.OffsetFetchRequest;
import kafka.javaapi.OffsetFetchResponse;
import kafka.javaapi.OffsetResponse;
import kafka.javaapi.PartitionMetadata;
import kafka.javaapi.TopicMetadata;
import kafka.javaapi.TopicMetadataRequest;
import kafka.javaapi.consumer.SimpleConsumer;
import kafka.message.MessageAndOffset;
import kafka.network.BlockingChannel;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.voltdb.VoltDB;
import org.voltdb.client.ClientResponse;
import org.voltdb.client.ProcedureCallback;
import org.voltdb.importclient.ImportBaseException;
import org.voltdb.importer.CSVInvocation;
import org.voltdb.importer.ImportHandlerProxy;
import org.voltdb.importer.ImporterChannelAssignment;
import org.voltdb.importer.VersionedOperationMode;

/**
 * Based on SimpleConsumer Implement a BundleActivator interface and extend ImportHandlerProxy.
 * For each partition for a topic a thread is launched to pull data and submit transactions.
 *
 * @author akhanzode
 */
public class KafkaStreamImporter extends ImportHandlerProxy implements BundleActivator {

    private final static PartitionOffsetRequestInfo LATEST_OFFSET =
            new PartitionOffsetRequestInfo(kafka.api.OffsetRequest.LatestTime(), 1);
    private final static PartitionOffsetRequestInfo EARLIEST_OFFSET =
            new PartitionOffsetRequestInfo(kafka.api.OffsetRequest.EarliestTime(), 1);

    //Properties for the importer
    private Properties m_properties;
    //Group id
    private String m_groupId;
    //Procedure to be invoked with params.
    private String m_procedure;
    //backpressure sleep milli seconds 100ms by default.
    private int m_backpressureSleepMs = 200;

    //List of topics form comma seperated list.
    private List<String> m_topicList;
    //List of brokers.
    private final List<HostAndPort> m_brokerList = new ArrayList<HostAndPort>();
    //kafka properties which has defaults use 2m row limit.
    private int m_fetchSize = (2*1024*1024);
    private int m_consumerSocketTimeout = 30000; //In milliseconds

    private static final String GROUP_ID = "voltdb";
    private static final String CLIENT_ID = "voltdb-importer";
    private static final int KAFKA_DEFAULT_BROKER_PORT = 9092;
    //readyForData is waiting for this released by shutdown
    private final Semaphore m_done = new Semaphore(0);
    private boolean m_stopping = false;

    //topic partition metadata
    private final Map<String, List<TopicMetadata>> m_topicPartitionMetaData = new HashMap<String, List<TopicMetadata>>();
    //Topic partitions
    private final Map<String, List<Integer>> m_topicPartitions = new HashMap<String, List<Integer>>();
    //topic partition leader
    private final Map<String, HostAndPort> m_topicPartitionLeader = new HashMap<String, HostAndPort>();
    private final Map<String, TopicPartitionFetcher> m_fetchers = new HashMap<String, TopicPartitionFetcher>();

    private ExecutorService m_es = null;

    private static final Pattern legalTopicNamesPattern = Pattern.compile("[a-zA-Z0-9\\._\\-]+");
    private static final int topicMaxNameLength = 255;

    //Simple Host and Port abstraction....dont want to use our big stuff here orgi bundle import nastiness.
    public static class HostAndPort {

        private final String m_host;
        private final int m_port;
        private final String m_connectionString;

        public HostAndPort(String h, int p) {
            m_host = h;
            m_port = p;
            m_connectionString = m_host + ":" + m_port;
        }

        public static HostAndPort fromString(String hap) {
            String s[] = hap.split(":");
            int p = KAFKA_DEFAULT_BROKER_PORT;
            if (s.length > 1 && s[1] != null && s[1].length() > 0) {
                p = Integer.parseInt(s[1].trim());
            }
            return new HostAndPort(s[0].trim(), p);
        }

        public String getHost() {
            return m_host;
        }

        public int getPort() {
            return m_port;
        }

        @Override
        public String toString() {
            return m_host + ":" + m_port;
        }

        @Override
        public int hashCode() {
            return m_connectionString.hashCode();
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof HostAndPort)) {
                return false;
            }
            if (this.getClass() != o.getClass()) {
                return false;
            }
            HostAndPort hap = (HostAndPort )o;
            if (hap == this) {
                return true;
            }
            return (hap.getHost().equals(getHost()) && hap.getPort() == getPort());
        }
    }

    // Register ImportHandlerProxy service.
    @Override
    public void start(BundleContext context) throws Exception {
        context.registerService(this.getClass().getName(), this, null);
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        //Do any bundle related cleanup.
    }

    @Override
    public boolean isRunEveryWhere() {
        //This is not a run everywhere importer only allocated resources are polled and consumed.
        return false;
    }

    //This is called to get all available resources. So called once during startup or catalog update.
    private Set<URI> buildTopicLeaderMetadata(SimpleConsumer consumer) {

        //For all topics connect and get metadata.
        Set<URI> availableResources = new TreeSet<URI>();
        for (String topic : m_topicList) {
            TopicMetadataRequest req = new TopicMetadataRequest(singletonList(topic));
            kafka.javaapi.TopicMetadataResponse resp = null;
            try {
                resp = consumer.send(req);
            } catch (Exception ex) {
                //Only called once.
                error(ex, "Failed to send topic metadata request for topic " + topic);
                continue;
            }

            List<TopicMetadata> metaData = resp.topicsMetadata();
            if (metaData == null) {
                //called once.
                error("Failed to get topic metadata for topic " + topic);
                continue;
            }
            m_topicPartitionMetaData.put(topic, metaData);
            List<Integer> partitions = m_topicPartitions.get(topic);
            if (partitions == null) {
                partitions = new ArrayList<Integer>();
                m_topicPartitions.put(topic, partitions);
            }
            for (TopicMetadata item : metaData) {
                for (PartitionMetadata part : item.partitionsMetadata()) {
                    partitions.add(part.partitionId());
                    URI uri = URI.create("kafka:/" + topic + "/partition/" + part.partitionId());
                    availableResources.add(uri);
                    String leaderKey = topic + "-" + part.partitionId();
                    Broker leader = part.leader();
                    m_topicPartitionLeader.put(leaderKey, new HostAndPort(leader.host(), leader.port()));
                }
            }
        }

        info("Available Channels are: " + availableResources);
        //Create an executor serice with Queue.
        m_es = Executors.newFixedThreadPool(availableResources.size() + 1,
                getThreadFactory("KafkaImporter", "KafkaImporterTopicFetcher", ImportHandlerProxy.MEDIUM_STACK_SIZE));
        return availableResources;
    }

    @Override
    public Set<URI> getAllResponsibleResources() {
        SimpleConsumer simpleConsumer = null;
        Set<URI> availableResources = new TreeSet<URI>();
        try {
            simpleConsumer = new SimpleConsumer(m_brokerList.get(0).getHost(), m_brokerList.get(0).getPort(), m_consumerSocketTimeout, m_fetchSize, CLIENT_ID);
            //Build all available topic URIs
            availableResources = buildTopicLeaderMetadata(simpleConsumer);
        } catch (Exception ex) {
            VoltDB.crashLocalVoltDB("Failed to get available resources for kafka importer", true, ex);
        } finally {
            closeConsumer(simpleConsumer);
        }
        return availableResources;
    }

    @Override
    public void stop() {
        m_stopping = true;
        //Stop all the fetchers.
        for (TopicPartitionFetcher fetcher : m_fetchers.values()) {
            fetcher.shutdown();
        }
        if (m_es != null) {
            //Now wait for fetchers to break out.
            m_es.shutdown();
            try {
                m_es.awaitTermination(365, TimeUnit.DAYS);
            } catch (InterruptedException ex) {
                //Should never come here.
                ex.printStackTrace();
            }
        }
        m_fetchers.clear();
        m_done.release();
    }

    /**
     * Return a name for VoltDB to log with friendly name.
     *
     * @return name of the importer.
     */
    @Override
    public String getName() {
        return "KafkaImporter82";
    }

    /**
     * This is called with the properties that are supplied in the deployment.xml Do any initialization here.
     *
     * @param p
     */
    @Override
    public void configure(Properties p) {
        m_properties = (Properties) p.clone();
        m_procedure = m_properties.getProperty("procedure", "").trim();
        if (m_procedure.isEmpty()) {
            throw new RuntimeException("Missing procedure.");
        }
        //pipe seperated list of topics.
        String topics = m_properties.getProperty("topics", "").trim();
        if (topics.isEmpty()) {
            throw new RuntimeException("Missing topic(s).");
        }
        m_topicList = Arrays.asList(topics.split("\\s*,\\s*"));
        if (m_topicList == null || m_topicList.isEmpty()) {
            throw new RuntimeException("Missing topic(s).");
        }
        for (String topic : m_topicList) {
            if (topic.contains("..") || topic.contains(".")) {
                throw new RuntimeException("topic name cannot be \".\" or \"..\"");
            }
            if (topic.length() > topicMaxNameLength) {
                throw new RuntimeException("topic name is illegal, can't be longer than "
                        + topicMaxNameLength + " characters");
            }
            if (!legalTopicNamesPattern.matcher(topic).matches()) {
                throw new RuntimeException("topic name " + topic + " is illegal, contains a character other than ASCII alphanumerics, '.', '_' and '-'");
            }
        }

       String brokers = m_properties.getProperty("brokers", "").trim();
        if (brokers.isEmpty()) {
            throw new RuntimeException("Missing kafka broker");
        }
        List<String> brokerList = Arrays.asList(brokers.split("\\s*,\\s*"));
        if (brokerList == null || brokerList.isEmpty()) {
            throw new RuntimeException("Missing kafka broker");
        }
        for (String broker : brokerList) {
            HostAndPort hap = HostAndPort.fromString(broker);
            m_brokerList.add(hap);
        }
        if (m_brokerList.isEmpty()) {
            throw new RuntimeException("Missing or misconfigured kafka broker list. See brokers property");
        }
        m_groupId = m_properties.getProperty("groupid", GROUP_ID).trim();
        //These are defaults picked up from kafka we save them so that they are passed around.
        m_fetchSize = Integer.parseInt(m_properties.getProperty("fetch.message.max.bytes", "65536"));
        m_consumerSocketTimeout = Integer.parseInt(m_properties.getProperty("socket.timeout.ms", "30000"));
        m_backpressureSleepMs = Integer.parseInt(m_properties.getProperty("backpressure.sleep.ms", "50"));
    }

    //Per topic per partition that we are responsible for.
    private class TopicPartitionFetcher implements Runnable {

        //URL for this fetcher.
        private final URI m_url;
        //Leafer for fetching data
        private HostAndPort m_leader;
        //coordinator for offset management.
        private HostAndPort m_coordinator;
        private boolean m_shutdown = false;
        private volatile boolean m_hasBackPressure = false;
        private final int m_fetchSize;
        //Available brokers.
        private final List<HostAndPort> m_brokers;
        private final int m_consumerSocketTimeout;
        //Start with invalid so consumer will fetch it.
        private final AtomicLong m_currentOffset = new AtomicLong(-1);
        private final SortedSet<Long> m_pendingOffsets = Collections.synchronizedSortedSet(new TreeSet<Long>());
        private final SortedSet<Long> m_seenOffset = Collections.synchronizedSortedSet(new TreeSet<Long>());
        private final AtomicReference<SimpleConsumer> m_offsetManager = new AtomicReference<SimpleConsumer>();
        private SimpleConsumer m_consumer = null;
        private final TopicAndPartition m_topicAndPartition;

        public TopicPartitionFetcher(List<HostAndPort> brokers, URI uri, String topic, int partition, HostAndPort leader, int fetchSize, int consumerSocketTimeout) {
            m_url = uri;
            m_brokers = brokers;
            m_leader = leader;
            m_coordinator = leader;
            m_fetchSize = fetchSize;
            m_consumerSocketTimeout = consumerSocketTimeout;
            m_topicAndPartition = new TopicAndPartition(topic, partition);
        }

        public final URI getUrl() {
            return m_url;
        }

        public void hasBackPressure(boolean flag) {
            m_hasBackPressure = flag;
        }

        //Find leader for the topic+partition.
        private PartitionMetadata findLeader() {
            PartitionMetadata returnMetaData = null;
            loop:
            for (HostAndPort broker : m_brokers) {
                SimpleConsumer consumer = null;
                try {
                    consumer = new SimpleConsumer(broker.getHost(), broker.getPort(), m_consumerSocketTimeout, m_fetchSize, "findLeader");

                    List<String> topics = singletonList(m_topicAndPartition.topic());
                    TopicMetadataRequest req = new TopicMetadataRequest(topics);
                    kafka.javaapi.TopicMetadataResponse resp = consumer.send(req);

                    List<TopicMetadata> metaData = resp.topicsMetadata();
                    for (TopicMetadata item : metaData) {
                        for (PartitionMetadata part : item.partitionsMetadata()) {
                            if (part.partitionId() == m_topicAndPartition.partition()) {
                                returnMetaData = part;
                                break loop;
                            }
                        }
                    }
                } catch (Exception e) {
                    error(e, "Error in finding leader for %s", m_topicAndPartition);
                } finally {
                    closeConsumer(consumer);
                }
            }
            if (returnMetaData == null) {
                error(null, "Failed to find Leader for %s", m_topicAndPartition);
            }
            return returnMetaData;
        }

        //Find leader for this topic partition.
        private HostAndPort findNewLeader() {
            for (int i = 0; i < 3; i++) {
                boolean shouldSleep = false;
                PartitionMetadata metadata = findLeader();
                if (metadata == null) {
                    shouldSleep = true;
                } else if (metadata.leader() == null) {
                    shouldSleep = true;
                } else if (m_leader.getHost().equalsIgnoreCase(metadata.leader().host()) && i == 0) {
                    // first time through if the leader hasn't changed give ZooKeeper a second to recover
                    // second time, assume the broker did recover before failover, or it was a non-Broker issue
                    shouldSleep = true;
                } else {
                    return new HostAndPort(metadata.leader().host(), metadata.leader().port());
                }
                if (shouldSleep) {
                    backoffSleep(i+1);
                }
            }
            //Unable to find return null for recheck.
            error(null, "Failed to find new leader for %s", m_topicAndPartition);
            return null;
        }

        //Just set shutdown flag fetcher timeout will then exit the thread.
        public void shutdown() {
            m_shutdown = true;
        }

        public void getOffsetCoordinator() {
            KafkaStreamImporterException probeException = null;
            int correlationId = 0;

            for (int attempts = 0; attempts < 3; ++attempts) {
                for (HostAndPort hp: m_brokerList) {
                    BlockingChannel channel = null;
                    try {
                        channel = new BlockingChannel(hp.getHost(), hp.getPort(),
                                BlockingChannel.UseDefaultBufferSize(), BlockingChannel.UseDefaultBufferSize(), m_consumerSocketTimeout);
                        channel.connect();
                        channel.send(new ConsumerMetadataRequest(m_groupId, ConsumerMetadataRequest.CurrentVersion(), correlationId++, CLIENT_ID));
                        ConsumerMetadataResponse metadataResponse = ConsumerMetadataResponse.readFrom(channel.receive().buffer());
                        if (metadataResponse.errorCode() == ErrorMapping.NoError()) {
                            Broker offsetManager = metadataResponse.coordinator();
                            m_coordinator = new HostAndPort(offsetManager.host(), offsetManager.port());
                            SimpleConsumer consumer = m_offsetManager.getAndSet(
                                    new SimpleConsumer(
                                            m_coordinator.getHost(),
                                            m_coordinator.getPort(),
                                            m_consumerSocketTimeout,
                                            m_fetchSize, CLIENT_ID
                                            ));
                            info("Offset Coordinator for " + m_topicAndPartition + " is " + offsetManager);
                            closeConsumer(consumer);
                            probeException = null;
                            consumer = null;
                            break;
                        }
                        probeException = new KafkaStreamImporterException("Failed to get Offset Coordinator for %s",
                                ErrorMapping.exceptionFor(metadataResponse.errorCode()), m_topicAndPartition
                                );
                    } catch (Exception e) {
                        probeException = new KafkaStreamImporterException(
                                "Failed to get Offset Coordinator for %s", e, m_topicAndPartition
                                );
                    } finally {
                        if (channel != null) {
                            channel.disconnect();
                        }
                    }
                }
                if (probeException != null) {
                    error(probeException, "Failed to query all brokers for the offeset coordinator for " + m_topicAndPartition);
                }
                backoffSleep(attempts+1);
            }
        }

        private OffsetResponse getTopicOffset(PartitionOffsetRequestInfo pori) {
            final int partition = m_topicAndPartition.partition();
            final String topic = m_topicAndPartition.topic();

            kafka.javaapi.OffsetRequest earlyRq = new kafka.javaapi.OffsetRequest(
                    singletonMap(m_topicAndPartition, pori),
                    kafka.api.OffsetRequest.CurrentVersion(), CLIENT_ID
                    );
            OffsetResponse response = null;
            Throwable fault = null;

            for (int attempts = 0; attempts < 3; ++attempts) try {
                response = m_consumer.getOffsetsBefore(earlyRq);
                if (response.hasError()) {
                    short code = response.errorCode(topic, partition);
                    fault = ErrorMapping.exceptionFor(code);
                    resetLeader();
                } else {
                    return response;
                }
            } catch (Exception e) {
                if (e instanceof IOException) {
                    resetLeader();
                }
                fault = e;
            }
            if (fault != null) {
                error(fault, "unable to fetch earliest offset for " + m_topicAndPartition);
                response = null;
            }
            return response;
        }

        private OffsetFetchResponse getClientTopicOffset() {
            final short version = 1;
            final OffsetFetchRequest rq = new OffsetFetchRequest(
                    m_groupId, singletonList(m_topicAndPartition), version, 1, CLIENT_ID
                    );
            OffsetFetchResponse rsp = null;
            Throwable fault = null;

            for (int attempts = 0; attempts < 3; ++attempts) try {
                rsp = m_offsetManager.get().fetchOffsets(rq);
                short code = rsp.offsets().get(m_topicAndPartition).error();
                if (code != ErrorMapping.NoError()) {
                    fault = ErrorMapping.exceptionFor(code);
                    if (code == ErrorMapping.NotCoordinatorForConsumerCode()) {
                        getOffsetCoordinator();
                    } else if (code == ErrorMapping.UnknownTopicOrPartitionCode()) {
                        fault = null;
                        break;
                    }
                } else {
                    fault = null;
                    break;
                }
            } catch (Exception e) {
                if (e instanceof IOException) {
                    getOffsetCoordinator();
                }
                fault = e;
            }
            if (fault != null) {
                error(fault, "unable to fetch earliest offset for " + m_topicAndPartition);
                rsp = null;
            }
            return rsp;
        }

        public long getLastOffset() {

            final int partition = m_topicAndPartition.partition();
            final String topic = m_topicAndPartition.topic();

            OffsetResponse response = getTopicOffset(EARLIEST_OFFSET);
            if (response == null) return -1L;

            long earliest = response.offsets(topic, partition)[0];

            response = getTopicOffset(LATEST_OFFSET);
            if (response == null) return -1L;

            long latest = response.offsets(topic, partition)[0];
            if (latest == earliest) return latest;

            OffsetFetchResponse ofr = getClientTopicOffset();
            if (ofr == null) return earliest;

            long current = ofr.offsets().get(m_topicAndPartition).offset();
            if (current < earliest) return earliest;

            if (current < latest) return current;

            return latest;
        }

        //Callback for each invocation we have submitted.
        private class TopicPartitionInvocationCallback implements ProcedureCallback {

            private final long m_offset;
            private final long m_nextOffset;
            private final AtomicLong m_cbcnt;

            public TopicPartitionInvocationCallback(long offset, long noffset, AtomicLong cbcnt) {
                m_offset = offset;
                m_nextOffset = noffset;
                m_cbcnt = cbcnt;
            }

            @Override
            public void clientCallback(ClientResponse response) throws Exception {
                //We should never get here with no pending offsets.
                assert(!m_pendingOffsets.isEmpty());
                m_cbcnt.incrementAndGet();
                m_pendingOffsets.remove(m_offset);
                //This is what we commit to
                m_seenOffset.add(m_nextOffset);
            }

        }

        //Sleep with backoff.
        private int backoffSleep(int fetchFailedCount) {
            try {
                Thread.sleep(1000 * fetchFailedCount++);
                if (fetchFailedCount > 10) fetchFailedCount = 1;
            } catch (InterruptedException ie) {
            }
            return fetchFailedCount;
        }

        private void resetLeader() {
            HostAndPort leaderBroker = m_leader;

            closeConsumer(m_consumer);
            m_consumer = null;
            leaderBroker = findNewLeader();
            if (leaderBroker == null) {
                //point to original leader which will fail and we fall back again here.
                error(null, "Fetch Failed to find leader continue with old leader: %s", m_leader);
                leaderBroker = m_leader;
            } else {
                if (!leaderBroker.equals(m_leader)) {
                    info("Fetch Found new leader for " + m_topicAndPartition + " New Leader: " + leaderBroker);
                    m_leader = leaderBroker;
                }
            }
            m_consumer = new SimpleConsumer(
                    leaderBroker.getHost(), leaderBroker.getPort(),
                    m_consumerSocketTimeout, m_fetchSize, CLIENT_ID
                    );
        }

        @Override
        public void run() {
            info("Starting partition fetcher for " + m_topicAndPartition);
            long submitCount = 0;
            AtomicLong cbcnt = new AtomicLong(0);
            try {
                //Startwith the starting leader.
                resetLeader();

                int sleepCounter = 1;
                while (!m_shutdown) {
                    //If we dont know the offset get it backoff if we fail.
                    if (m_currentOffset.get() < 0) {
                        getOffsetCoordinator();
                        long lastOffset = getLastOffset();
                        m_currentOffset.set(lastOffset);
                        if (m_currentOffset.get() < 0) {
                            sleepCounter = backoffSleep(sleepCounter);
                            info("Latest offset not found for " + m_topicAndPartition + " using earliest offset.");
                            //No latest time available so get earliest known for this consumer group.
                            // m_currentOffset.set(getLastOffset(kafka.api.OffsetRequest.EarliestTime()));
                            continue;
                        }
                    }
                    long currentFetchCount = 0;
                    //Build fetch request of we have a valid offset and not too many are pending.
                    FetchRequest req = new FetchRequestBuilder().clientId(CLIENT_ID)
                            .addFetch(m_topicAndPartition.topic(),
                                    m_topicAndPartition.partition(), m_currentOffset.get(), m_fetchSize)
                            .build();
                    FetchResponse fetchResponse = null;
                    try {
                        fetchResponse = m_consumer.fetch(req);
                        if (fetchResponse == null) {
                            sleepCounter = backoffSleep(sleepCounter);
                            continue;
                        }
                    } catch (Exception ex) {
                        error(ex, "Failed to fetch from %s", m_topicAndPartition);
                        //See if its network error and find new leader for this partition.
                        if (ex instanceof IOException) {
                            resetLeader();
                            //find leader in resetLeader would sleep and backoff
                            continue;
                        }
                        sleepCounter = backoffSleep(sleepCounter);
                        continue;
                    }

                    if (fetchResponse.hasError()) {
                        // Something went wrong!
                        short code = fetchResponse.errorCode(m_topicAndPartition.topic(), m_topicAndPartition.partition());
                        warn(ErrorMapping.exceptionFor(code), "Failed to fetch messages for %s", m_topicAndPartition);
                        sleepCounter = backoffSleep(sleepCounter);
                        if (code == ErrorMapping.OffsetOutOfRangeCode()) {
                            // We asked for an invalid offset. For simple case ask for the last element to reset
                            info("Invalid offset requested for " + m_topicAndPartition);
                            getOffsetCoordinator();
                            m_currentOffset.set(-1L);
                            continue;
                        }
                        resetLeader();
                        continue;
                    }
                    sleepCounter = 1;
                    for (MessageAndOffset messageAndOffset : fetchResponse.messageSet(m_topicAndPartition.topic(), m_topicAndPartition.partition())) {
                        //You may be catchin up so dont sleep.
                        currentFetchCount++;
                        long currentOffset = messageAndOffset.offset();
                        //if currentOffset is less means we have already pushed it and also check pending queue.
                        if (currentOffset < m_currentOffset.get()) {
                            continue;
                        }
                        ByteBuffer payload = messageAndOffset.message().payload();

                        String line = new String(payload.array(),payload.arrayOffset(),payload.limit(),StandardCharsets.UTF_8);
                        CSVInvocation invocation = new CSVInvocation(m_procedure, line);
                        TopicPartitionInvocationCallback cb = new TopicPartitionInvocationCallback(currentOffset, messageAndOffset.nextOffset(), cbcnt);
                        m_pendingOffsets.add(currentOffset);
                        if (!callProcedure(cb, invocation)) {
                            if (isDebugEnabled()) {
                                debug("Failed to process Invocation possibly bad data: " + line);
                            }
                            synchronized(m_seenOffset) {
                                //Make this failed offset known to seen offsets so committer can push ahead.
                                m_seenOffset.add(messageAndOffset.nextOffset());
                            }
                            m_pendingOffsets.remove(currentOffset);
                        }
                        submitCount++;
                        m_currentOffset.set(messageAndOffset.nextOffset());
                        if (m_shutdown) {
                            break;
                        }
                    }
                    if (m_shutdown) {
                        break;
                    }

                    //wait to fetch more if we are in backpressure or read nothing last time.
                    if (currentFetchCount == 0 || m_hasBackPressure) {
                        try {
                            Thread.sleep(m_backpressureSleepMs);
                        } catch (InterruptedException ie) {
                        }
                    }
                    commitOffset();
                }
                //Drain will make sure there is nothing in pending.
                info("Partition fecher stopped for " + m_topicAndPartition
                        + " Last commit point is: " + m_currentOffset.get()
                        + " Callback Rcvd: " + cbcnt.get()
                        + " Submitted: " + submitCount);
            } catch (Exception ex) {
                error("Failed to start topic partition fetcher for " + m_topicAndPartition, ex);
            } finally {
                commitOffset();
                closeConsumer(m_consumer);
                m_consumer = null;
                closeConsumer(m_offsetManager.getAndSet(null));
            }
        }

        public boolean commitOffset() {

            if (m_seenOffset.isEmpty())
                return false;
            long offset = m_seenOffset.last();
            final int correlationId = m_topicAndPartition.partition();
            final short version = 1;

            OffsetAndMetadata offsetMetdata = new OffsetAndMetadata(offset, "commitRequest", ErrorMapping.NoError());
            Map<TopicAndPartition, OffsetAndMetadata> reqMap = new HashMap<TopicAndPartition, OffsetAndMetadata>();
            reqMap.put(m_topicAndPartition, offsetMetdata);
            OffsetCommitRequest offsetCommitRequest = new OffsetCommitRequest(m_groupId, reqMap, correlationId, CLIENT_ID, version);
            OffsetCommitResponse offsetCommitResponse = null;
            try {
                SimpleConsumer consumer = m_offsetManager.get();
                if (consumer == null) {
                    getOffsetCoordinator();
                    consumer = m_offsetManager.get();
                }
                if (consumer != null) {
                    offsetCommitResponse = consumer.commitOffsets(offsetCommitRequest);
                    final short code = ((Short) offsetCommitResponse.errors().get(m_topicAndPartition));
                    if (code == ErrorMapping.NotCoordinatorForConsumerCode()) {
                        info("Not coordinator for committing offset for " + m_topicAndPartition + " Updating coordinator.");
                        getOffsetCoordinator();
                        consumer = m_offsetManager.get();
                        if (consumer != null) {
                            offsetCommitResponse = consumer.commitOffsets(offsetCommitRequest);
                        }
                    }
                    if ((Short)offsetCommitResponse.errors().get(m_topicAndPartition) == ErrorMapping.NoError()){
                        info("[STEBUG] " + "Committed offset " + offset + " for " + m_topicAndPartition);
                    }
                } else {
                    error("Commit Offset Failed to get offset coordinator for " + m_topicAndPartition);
                    return false;
                }
            } catch (Exception e) {
                error(e, "Failed to commit Offset for " + m_topicAndPartition);
                return false;
            }
            final short code = ((Short) offsetCommitResponse.errors().get(m_topicAndPartition));
            if (code != ErrorMapping.NoError()) {
                final String msg = "Commit Offset Failed to commit for " + m_topicAndPartition + " Code: %d";
                error(null, msg, code);
                return false;
            }
            synchronized(m_seenOffset) {
                m_seenOffset.clear();
            }
            return true;
        }

    }

    public void closeConsumer(SimpleConsumer consumer) {
        try {
            if (consumer != null) {
                consumer.close();
            }
        } catch (Exception e) {
            error("Failed to close consumer connection.", e);
        }
    }

    @Override
    public void hasBackPressure(boolean flag) {
        if (m_stopping) return;
        for (TopicPartitionFetcher fetcher : m_fetchers.values()) {
            fetcher.hasBackPressure(flag);
        }
    }

    //On getting this event kick off ready
    @Override
    public void onChange(ImporterChannelAssignment assignment) {
        if (m_stopping) {
            info("Importer is stopping, ignoring the change notification.");
            return;
        }
        if (m_es == null) {
            //Create executor with sufficient threads.
            VoltDB.crashLocalVoltDB("buildTopicLeaderMetadata must be called before getting an onChange", false, null);
        }

        //For addeed create fetchers...make sure existing fetchers are not there.
        for (URI nuri : assignment.getAdded()) {
            Map<String, List<Integer>> topicMap = new HashMap<String, List<Integer>>();
            for (String topic : m_topicList) {
                topicMap.put(topic, singletonList(0));
            }
            for (String topic : m_topicList) {
                List<Integer> topicPartitions = m_topicPartitions.get(topic);
                if (topicPartitions == null) {
                    //I got a change for added partition that I am not aware of die die.
                    VoltDB.crashLocalVoltDB("Unknown kafka topic added for this node", false, null);
                }
                for (int partition : topicPartitions) {
                    String leaderKey = topic + "-" + partition;
                    URI assignedKey = URI.create("kafka:/" + topic + "/partition/" + partition);
                    //The fetcher must not have existed.
                    if (!m_fetchers.containsKey(nuri.toString()) && nuri.equals(assignedKey)) {
                        info("Channel " + assignedKey + " mastership is assigned to this node.");
                        HostAndPort hap = m_topicPartitionLeader.get(leaderKey);
                        TopicPartitionFetcher fetcher = new TopicPartitionFetcher(m_brokerList, assignedKey, topic, partition,
                                hap, m_fetchSize, m_consumerSocketTimeout);
                        m_fetchers.put(assignedKey.toString(), fetcher);
                        m_es.submit(fetcher);
                        info("KafkaImporter is fetching for resource: " + nuri);
                    }
                }
            }
        }

        //For removed shutdown the fetchers if all are removed the importer will be closed/shutdown?
        for (URI r : assignment.getRemoved()) {
            TopicPartitionFetcher fetcher = m_fetchers.get(r.toString());
            if (fetcher != null) {
                fetcher.shutdown();
                info("KafkaImporter is NOT fetching for resource: " + r);
                m_fetchers.remove(r.toString());
            }
        }
    }

    // TODO: implement
    @Override
    public void onClusterStateChange(VersionedOperationMode mode) {
        info("cluster state change notification: " + mode);
    }

    /**
     * This is called when server is ready to accept any transactions.
     */
    @Override
    public void readyForData() {
        try {
            info("Configured and ready with properties: " + m_properties);
            //We wait for shutdown task to release.
            m_done.acquire();
        } catch (Exception ex) {
            error("Kafka Importer finished with exeception ", ex);
        }
    }

    public class KafkaStreamImporterException extends ImportBaseException
    {
        private static final long serialVersionUID = 7668280657393399984L;

        public KafkaStreamImporterException() {
        }

        public KafkaStreamImporterException(String format, Object... args) {
            super(format, args);
        }

        public KafkaStreamImporterException(Throwable cause) {
            super(cause);
        }

        public KafkaStreamImporterException(String format, Throwable cause,
                Object... args) {
            super(format, cause, args);
        }

    }

}
