/*
 * Copyright (c) 2015 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 *
 */
package org.openbmp;

import kafka.consumer.ConsumerConfig;
import kafka.consumer.KafkaStream;
import kafka.javaapi.consumer.ConsumerConnector;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


/**
 * MySQL OpenBMP consumer
 *      Consumes the openbmp.parsed.* topic streams and stores the data into MySQL.
 */
public class MySQLConsumerApp
{
    // TODO: Will change the below to match partitions, but for now only one thread per topic is used.
    private final Integer THREADS_PER_TOPIC = 1;

    private final Integer RETRY_DELAY = 1500;               // In milliseconds

    private static final Logger logger = LogManager.getFormatterLogger(MySQLConsumerApp.class.getName());
    private ConsumerConnector consumer;
    private ExecutorService executor;
    private final Config cfg;
    private static List<MySQLConsumer> _threads;

    /**
     * routerConMap is a persistent map of collectors and routers. It's a hash of hashes.
     *      routerConMap[collectorHashId][RouterIp] = reference count of connections
     */
    private Map<String,Map<String, Integer>> routerConMap;

    public MySQLConsumerApp(Config cfg) {

        this.cfg = cfg;
        consumer = null;
        _threads = new ArrayList<MySQLConsumer>();
        routerConMap = new ConcurrentHashMap<String, Map<String, Integer>>();

        Boolean reconnect = true;

        logger.debug("Connecting to kafka/zookeeper: %s", cfg.getZookeeperAddress());

        // Connect to kafka/zookeeper - retry if needed
        while (reconnect) {
            try {
                consumer = kafka.consumer.Consumer.createJavaConsumerConnector(
                        createConsumerConfig(cfg.getZookeeperAddress(), cfg.getClientId(), cfg.getGroupId(),
                                             cfg.getOffsetLargest()));

                reconnect = false;
                logger.debug("Connected to kafka/zookeeper: %s", cfg.getZookeeperAddress());

            } catch (org.I0Itec.zkclient.exception.ZkTimeoutException ex) {
                logger.warn("Timeout connecting to zookeeper, will retry.");
                try {
                    Thread.sleep(RETRY_DELAY);
                } catch (InterruptedException iex) {
                    reconnect = false;
                }

            } catch (org.I0Itec.zkclient.exception.ZkException zex) {
                System.out.println("Failed connection, check hostname/ip: " + zex.getMessage());
                logger.error("Failed connection, check hostanme/ip or zookeeper server: %s",
                        zex.getMessage());
                reconnect = false;
            }
        }
    }

    Boolean isConnected() {
        return consumer != null ? true : false;
    }

    public void shutdown() {
        logger.debug("Shutting down MySQL consumer app");

        if (consumer != null) consumer.shutdown();

        if (executor != null) executor.shutdown();
        try {
            if (!executor.awaitTermination(5000, TimeUnit.MILLISECONDS)) {
                logger.warn("Timed out waiting for consumer threads to shut down, exiting uncleanly");
            }
        } catch (InterruptedException e) {
            logger.warn("Interrupted during shutdown, exiting uncleanly");
        }
    }

    public void run() {
        Map<String, Integer> topicCountMap = new HashMap<String, Integer>();

        // Add topics and number of threads to use per topic
        topicCountMap.put("openbmp.parsed.collector", new Integer(THREADS_PER_TOPIC));
        topicCountMap.put("openbmp.parsed.router", new Integer(THREADS_PER_TOPIC));
        topicCountMap.put("openbmp.parsed.peer", new Integer(THREADS_PER_TOPIC));
        topicCountMap.put("openbmp.parsed.base_attribute", new Integer(THREADS_PER_TOPIC));
        topicCountMap.put("openbmp.parsed.unicast_prefix", new Integer(THREADS_PER_TOPIC));
        topicCountMap.put("openbmp.parsed.bmp_stat", new Integer(THREADS_PER_TOPIC));
        topicCountMap.put("openbmp.parsed.ls_node", new Integer(THREADS_PER_TOPIC));
        topicCountMap.put("openbmp.parsed.ls_link", new Integer(THREADS_PER_TOPIC));
        topicCountMap.put("openbmp.parsed.ls_prefix", new Integer(THREADS_PER_TOPIC));

        logger.info("Creating/attaching %d topics and getting offsets. This can take a while, please wait...", topicCountMap.size());
        Map<String, List<KafkaStream<byte[], byte[]>>> consumerMap = consumer.createMessageStreams(topicCountMap);

        // Init thread pool
        executor = Executors.newFixedThreadPool(topicCountMap.size() * THREADS_PER_TOPIC);

        logger.info("Starting %d consumer threads", THREADS_PER_TOPIC * topicCountMap.size());

        // Start threads to service the topicss
        int threadNumber = 0;
        for (Map.Entry<String,Integer> topic : topicCountMap.entrySet()) {
            List<KafkaStream<byte[], byte[]>> streams = consumerMap.get(topic.getKey());

            for (final KafkaStream stream : streams) {
                _threads.add(threadNumber, new MySQLConsumer(stream, threadNumber, cfg, topic.getKey(), routerConMap));

                executor.submit(_threads.get(threadNumber));
                threadNumber++;
            }
        }
    }

    private static ConsumerConfig createConsumerConfig(String zk_addr, String clientId, String groupId,
                                                       Boolean offsetLargest) {
        Properties props = new Properties();
        props.put("zookeeper.connect", zk_addr);
        props.put("group.id", groupId);
        props.put("client.id", clientId != null ? groupId : clientId);
        props.put("zookeeper.session.timeout.ms", "500");
        props.put("zookeeper.sync.time.ms", "200");
        props.put("auto.commit.interval.ms", "1000");
        //props.put("consumer.timeout.ms", "100");            // Non-blocking for stream.it.hasNext()

        if (offsetLargest == Boolean.FALSE) {
            logger.info("Using smallest for Kafka offset reset");
            props.put("auto.offset.reset", "smallest");
        } else {
            logger.info("Using largest for Kafka offset reset");
            props.put("auto.offset.reset", "largest");
        }

        return new ConsumerConfig(props);
    }

    public static void main(String[] args) {
        Config cfg = Config.getInstance();

        cfg.parse(args);

        // Validate DB connection
        try {
            Connection con = DriverManager.getConnection(
                    "jdbc:mariadb://" + cfg.getDbHost() + "/" + cfg.getDbName() +
                            "?tcpKeepAlive=1&socketTimeout=1000&useCompression=true&autoReconnect=true&allowMultiQueries=true",
                    cfg.getDbUser(), cfg.getDbPw());
            con.close();

        } catch (SQLException e) {
            e.printStackTrace();
            System.exit(2);
        }

        MySQLConsumerApp mysqlApp = new MySQLConsumerApp(cfg);

        if (mysqlApp.isConnected()) {
            mysqlApp.run();

            try {
                while (true) {
                    if (cfg.getStatsInterval() > 0) {
                        Thread.sleep(cfg.getStatsInterval() * 1000);

                        /*
                         * Print stats for each thread
                         */
                        for (int i = 0; i < _threads.size(); i++ ) {
                            logger.info("STAT: thread %d read: %10d queue: %10d topics: %s",
                                        i, _threads.get(i).getMessageCount(),
                                        _threads.get(i).getQueueSize(),
                                        _threads.get(i).getTopics());
                        }

                    } else {
                        Thread.sleep(15000);
                    }
                }
            } catch (InterruptedException ie) {

            }
            mysqlApp.shutdown();
        }
    }
}
