/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.brooklyn.ambari;

import brooklyn.entity.basic.BasicStartableImpl;
import brooklyn.entity.basic.SoftwareProcess;
import brooklyn.entity.group.DynamicCluster;
import brooklyn.entity.proxying.EntitySpec;
import brooklyn.entity.software.ssh.SshCommandSensor;
import brooklyn.event.SensorEvent;
import brooklyn.event.SensorEventListener;
import brooklyn.location.Location;
import brooklyn.util.config.ConfigBag;
import brooklyn.util.time.Duration;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.apache.brooklyn.ambari.agent.AmbariAgent;
import org.apache.brooklyn.ambari.server.AmbariServer;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static brooklyn.event.basic.DependentConfiguration.attributeWhenReady;

public class AmbariClusterImpl extends BasicStartableImpl implements AmbariCluster {

    //TODO is there an issue with rebind here?  On rebind should be populated from somewhere else?
    private final ConcurrentHashMap<String, Boolean> registeredHosts = new ConcurrentHashMap<String, Boolean>();

    @Override
    public void init() {
        super.init();

        setDisplayName("Ambari Cluster");
        Integer initialSize = getConfig(INITIAL_SIZE);
        //TODO need to do something better with security groups here
        Object securityGroup = getConfig(SECURITY_GROUP);
        ImmutableMap<String, Object> serverProvisioningProperties = ImmutableMap.<String, Object>of(
                "inboundPorts", ImmutableList.of(8080, 22),
                "securityGroups", securityGroup,
                "minRam", 4096);

        SshCommandSensor<String> hostnameSensor = new SshCommandSensor<String>(ConfigBag.newInstance()
                .configure(SshCommandSensor.SENSOR_PERIOD, Duration.seconds(1))
                .configure(SshCommandSensor.SENSOR_NAME, "fqdn")
                .configure(SshCommandSensor.SENSOR_COMMAND, "hostname -s"
                ));
        AmbariServer val = addChild(getConfig(SERVER_SPEC)
                        .configure(SoftwareProcess.PROVISIONING_PROPERTIES, serverProvisioningProperties)
                        .displayName("Ambari Server")
                        .addInitializer(hostnameSensor)
        );
        setAttribute(AMBARI_SERVER, val);


        ImmutableMap<String, Object> agentProvisioningProperties = ImmutableMap.<String, Object>of(
                "minRam", 4096,
                "osFamily", "ubuntu",
                "osVersionRegex", "12.*",
                "securityGroups", securityGroup);

        EntitySpec<AmbariAgent> agentEntitySpec = EntitySpec.create(AmbariAgent.class)
                .configure(AmbariAgent.AMBARI_SERVER_FQDN, attributeWhenReady(getAttribute(AMBARI_SERVER), AmbariServer.HOSTNAME))
                        //TODO shouldn't use default os
                .addInitializer(hostnameSensor)
                .configure(SoftwareProcess.PROVISIONING_PROPERTIES, agentProvisioningProperties);

        setAttribute(AMBARI_AGENT, addChild(EntitySpec.create(DynamicCluster.class)
                        //TODO should probably change option to hadoop cluster size
                        .configure(DynamicCluster.INITIAL_SIZE, initialSize - 1)
                        .configure(DynamicCluster.MEMBER_SPEC, agentEntitySpec)
                        .displayName("All Nodes")
        ));
    }

    @Override
    public void start(Collection<? extends Location> locations) {
        super.start(locations);

        subscribe(getAttribute(AMBARI_SERVER), AmbariServer.REGISTERED_HOSTS, registeredHostsEventListener);

        setAttribute(SERVICE_UP, Boolean.TRUE);
    }

    final SensorEventListener<List<String>> registeredHostsEventListener = new SensorEventListener<List<String>>() {
        @Override
        public void onEvent(SensorEvent<List<String>> event) {
            for (String host : event.getValue()) {
                registeredHosts.putIfAbsent(host, true);
            }
        }
    };

    private void createCluster() {
        getAttribute(AMBARI_SERVER).installHDP("Cluster1", "mybp", ImmutableList.<String>copyOf(registeredHosts.keySet()), ImmutableList.<String>of("ZOOKEEPER", "HDFS"));
    }
}
