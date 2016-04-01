/*
 * Copyright (c) 2015 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 *
 */
package org.openbmp;

import org.apache.commons.cli.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Configuration for program
 *
 * Will parse command line args and store results in class instance
 */
public class Config {
    private static final Logger logger = LogManager.getFormatterLogger(MySQLConsumerApp.class.getName());

    private static Config instance = null;

    private Options options = new Options();

    /// Config variables
    private String zookeeperAddress = "localhost:2181";
    private String groupId = "openbmp-mysql-consumer";
    private String clientId = null;
    private Integer expected_heartbeat_interval = 330000;
    private Integer stats_interval = 300;
    private String db_host = "localhost:3306";
    private String db_user = "openbmp";
    private String db_pw = "openbmp";
    private String db_name = "openBMP";
    private Boolean offsetLargest = Boolean.FALSE;

    //Turns this class to a singleton
    public static Config getInstance() {
        if(instance==null)
        {
            instance = new Config();
        }
        return instance;
    }

    protected Config() {
        options.addOption("zk", "zookeeper", true, "Zookeeper hostanme:port (default is localhost:2181)");
        options.addOption("g", "group.id", true, "Kafka group ID (default is openbmp-mysql-consumer)");
        options.addOption("c", "client.id", true, "Kafka client ID (default uses group.id");
        options.addOption("ol", "offset_largest", false, "Set offset to largest when offset is not known");
        options.addOption("e", "expected_heartbeat_interval", true, "Max age in minutes for collector heartbeats (default is 6 minutes)");
        options.addOption("s", "stats_interval", true, "Stats interval in seconds (default 300 seconds, 0 disables");
        options.addOption("dh", "db_host", true, "Database host (default is localhost:3306)");
        options.addOption("du", "db_user", true, "Database username (default is openbmp)");
        options.addOption("dp", "db_pw", true, "Database password (default is openbmp)");
        options.addOption("dn", "db_name", true, "Database name (default is openBMP)");
        options.addOption("h", "help", false, "Usage help");

    }

    /**
     * Parse command line args
     *
     * @param       args        Command line args to parse
     * @return      True if error, false if successfully parsed
     */
    public Boolean parse(String [] args) {
        CommandLineParser parser = new DefaultParser();

        CommandLine cmd = null;

        try {
            cmd = parser.parse(options, args);

            if (cmd.hasOption("h")) {
                help();
                System.exit(0);
            }

            if (cmd.hasOption("zk"))
                zookeeperAddress = cmd.getOptionValue("zk");

            if (cmd.hasOption("ol"))
                offsetLargest = Boolean.TRUE;

            if (cmd.hasOption("g"))
                groupId = cmd.getOptionValue("g");

            if (cmd.hasOption("e"))
                expected_heartbeat_interval = Integer.valueOf(cmd.getOptionValue("e")) * 60 * 1000 + 30000;

            if (cmd.hasOption("s"))
                stats_interval = Integer.valueOf(cmd.getOptionValue("s"));

            if (cmd.hasOption("c"))
                clientId = cmd.getOptionValue("c");
            else
                clientId = groupId;

            if (cmd.hasOption("dh"))
                db_host = cmd.getOptionValue("dh");

            if (cmd.hasOption("du"))
                db_user = cmd.getOptionValue("du");

            if (cmd.hasOption("dp"))
                db_pw = cmd.getOptionValue("dp");

            if (cmd.hasOption("dn"))
                db_name = cmd.getOptionValue("dn");

        } catch (ParseException e) {
            //e.printStackTrace();

            System.out.println("Failed to parse commandline args: " + e.getMessage());
            help();

            return true;
        }

        return false;
    }

    public void help() {
        HelpFormatter fmt = new HelpFormatter();

        StackTraceElement[] stack = Thread.currentThread ().getStackTrace ();
        StackTraceElement main = stack[stack.length - 1];
        String mainClassName = main.getClassName ();

        fmt.printHelp(80, mainClassName, "\nOPTIONS:", options, "\n");
    }

    String getZookeeperAddress() {
        return zookeeperAddress;
    }

    String getClientId() {
        return clientId;
    }

    String getGroupId() {
        return groupId;
    }

    String getDbHost() { return db_host; }

    String getDbUser() { return db_user; }

    String getDbPw() { return db_pw; }

    String getDbName() { return db_name; }

    Boolean getOffsetLargest() { return offsetLargest; }

    public Integer getHeartbeatInterval() { return expected_heartbeat_interval; }

    Integer getStatsInterval() { return stats_interval; }
}
