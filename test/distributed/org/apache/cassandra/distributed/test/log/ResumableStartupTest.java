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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.distributed.test.log;

import java.io.IOException;
import java.util.concurrent.Callable;

import org.junit.Assert;
import org.junit.Test;

import harry.core.Configuration;
import harry.core.Run;
import harry.model.sut.SystemUnderTest;
import harry.visitors.GeneratingVisitor;
import harry.visitors.MutatingRowVisitor;
import harry.visitors.Visitor;
import org.apache.cassandra.config.CassandraRelevantProperties;
import org.apache.cassandra.distributed.Cluster;
import org.apache.cassandra.distributed.Constants;
import org.apache.cassandra.distributed.api.*;
import org.apache.cassandra.distributed.fuzz.HarryHelper;
import org.apache.cassandra.distributed.fuzz.InJVMTokenAwareVisitorExecutor;
import org.apache.cassandra.distributed.fuzz.InJvmSut;
import org.apache.cassandra.distributed.shared.ClusterUtils;
import org.apache.cassandra.distributed.shared.NetworkTopology;
import org.apache.cassandra.locator.InetAddressAndPort;
import org.apache.cassandra.schema.KeyspaceMetadata;
import org.apache.cassandra.tcm.ClusterMetadata;
import org.apache.cassandra.tcm.Epoch;
import org.apache.cassandra.tcm.transformations.PrepareJoin;
import org.apache.cassandra.service.StorageService;

import static org.apache.cassandra.distributed.action.GossipHelper.withProperty;
import static org.apache.cassandra.distributed.shared.ClusterUtils.getClusterMetadataVersion;
import static org.apache.cassandra.distributed.shared.ClusterUtils.getSequenceAfterCommit;

public class ResumableStartupTest extends FuzzTestBase
{
    private static int WRITES = 2000;

    @Test
    public void bootstrapWithDeferredJoinTest() throws Throwable
    {
        Configuration.ConfigurationBuilder configBuilder = HarryHelper.defaultConfiguration()
                                                                      .setPartitionDescriptorSelector(new Configuration.DefaultPDSelectorConfiguration(1, 1))
                                                                      .setClusteringDescriptorSelector(HarryHelper.defaultClusteringDescriptorSelectorConfiguration().setMaxPartitionSize(100).build());


        try (Cluster cluster = builder().withNodes(1)
                                        .withTokenSupplier(TokenSupplier.evenlyDistributedTokens(4))
                                        .withNodeIdTopology(NetworkTopology.singleDcNetworkTopology(4, "dc0", "rack0"))
                                        .createWithoutStarting())
        {
            IInvokableInstance cmsInstance = cluster.get(1);
            configBuilder.setSUT(() -> new InJvmSut(cluster));
            Run run = configBuilder.build().createRun();

            cmsInstance.config().set("auto_bootstrap", true);
            cmsInstance.startup();

            cluster.coordinator(1).execute("CREATE KEYSPACE " + run.schemaSpec.keyspace +
                                           " WITH replication = {'class': 'SimpleStrategy', 'replication_factor' : 2};",
                                           ConsistencyLevel.ALL);
            cluster.coordinator(1).execute(run.schemaSpec.compile().cql(), ConsistencyLevel.ALL);
            ClusterUtils.waitForCMSToQuiesce(cluster, cluster.get(1));

            Visitor visitor = new GeneratingVisitor(run, new InJVMTokenAwareVisitorExecutor(run, MutatingRowVisitor::new, SystemUnderTest.ConsistencyLevel.NODE_LOCAL));
            for (int i = 0; i < WRITES; i++)
                visitor.visit();

            IInstanceConfig config = cluster.newInstanceConfig()
                                            .set("auto_bootstrap", true)
                                            .set(Constants.KEY_DTEST_FULL_STARTUP, true);
            IInvokableInstance newInstance = cluster.bootstrap(config);

            withProperty(CassandraRelevantProperties.TEST_WRITE_SURVEY, true, newInstance::startup);

            // Write with ONE, replicate via pending range mechanism
            visitor = new GeneratingVisitor(run, new InJVMTokenAwareVisitorExecutor(run, MutatingRowVisitor::new, SystemUnderTest.ConsistencyLevel.ONE));
            for (int i = 0; i < WRITES; i++)
                visitor.visit();

            Epoch currentEpoch = getClusterMetadataVersion(cmsInstance);
            // Quick check that schema changes are possible with nodes in write survey mode (i.e. with ranges locked)
            cluster.coordinator(1).execute("ALTER TABLE " + run.schemaSpec.keyspace + "." + run.schemaSpec.table +
                                           " WITH comment = 'Schema alterations which do not affect placements should" +
                                           " not be restricted by in flight operations';",
                                           ConsistencyLevel.ALL);

            final String newAddress = ClusterUtils.getBroadcastAddressHostWithPortString(newInstance);
            final String keyspace = run.schemaSpec.keyspace;
            boolean newReplicaInCorrectState = cluster.get(1).callOnInstance(() -> {
                ClusterMetadata metadata = ClusterMetadata.current();
                KeyspaceMetadata ksm = metadata.schema.getKeyspaceMetadata(keyspace);
                boolean isWriteReplica = false;
                boolean isReadReplica = false;
                for (InetAddressAndPort readReplica : metadata.placements.get(ksm.params.replication).reads.byEndpoint().keySet())
                {
                    if (readReplica.getHostAddressAndPort().equals(newAddress))
                        isReadReplica = true;
                }
                for (InetAddressAndPort writeReplica : metadata.placements.get(ksm.params.replication).writes.byEndpoint().keySet())
                {
                    if (writeReplica.getHostAddressAndPort().equals(newAddress))
                        isWriteReplica = true;
                }
                return (isWriteReplica && !isReadReplica);
            });
            Assert.assertTrue("Expected new instance to be a write replica only", newReplicaInCorrectState);

            Callable<Epoch> finishedBootstrap = getSequenceAfterCommit(cmsInstance, (e, r) -> e instanceof PrepareJoin.FinishJoin && r.isSuccess());
            newInstance.runOnInstance(() -> {
                try
                {
                    StorageService.instance.joinRing();
                }
                catch (IOException e)
                {
                    throw new RuntimeException("Error joining ring", e);
                }
            });
            Epoch next = finishedBootstrap.call();
            Assert.assertEquals(String.format("Expected epoch after schema change, mid join & finish join to be %s, but was %s",
                                              next.getEpoch(), currentEpoch.getEpoch() + 3),
                                next.getEpoch(), currentEpoch.getEpoch() + 3);

            for (int i = 0; i < WRITES; i++)
                visitor.visit();

            QuiescentLocalStateChecker model = new QuiescentLocalStateChecker(run);
            model.validateAll();
        }
    }
}
