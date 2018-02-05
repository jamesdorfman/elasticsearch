/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
/*

/*
 * ELASTICSEARCH CONFIDENTIAL
 * __________________
 *
 *  [2017] Elasticsearch Incorporated. All Rights Reserved.
 *
 * NOTICE:  All information contained herein is, and remains
 * the property of Elasticsearch Incorporated and its suppliers,
 * if any.  The intellectual and technical concepts contained
 * herein are proprietary to Elasticsearch Incorporated
 * and its suppliers and may be covered by U.S. and Foreign Patents,
 * patents in process, and are protected by trade secret or copyright law.
 * Dissemination of this information or reproduction of this material
 * is strictly forbidden unless prior written permission is obtained
 * from Elasticsearch Incorporated.
 */
package org.elasticsearch.xpack.ccr;

import org.elasticsearch.action.admin.indices.stats.ShardStats;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.analysis.common.CommonAnalysisPlugin;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.common.CheckedRunnable;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.translog.Translog;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.discovery.TestZenDiscovery;
import org.elasticsearch.xpack.core.XPackSettings;
import org.elasticsearch.xpack.ccr.action.FollowExistingIndexAction;
import org.elasticsearch.xpack.ccr.action.ShardChangesAction;
import org.elasticsearch.xpack.ccr.action.ShardFollowTask;
import org.elasticsearch.xpack.ccr.action.UnfollowIndexAction;
import org.elasticsearch.persistent.PersistentTasksCustomMetaData;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@ESIntegTestCase.ClusterScope(scope = ESIntegTestCase.Scope.TEST, transportClientRatio = 0)
public class ShardChangesIT extends ESIntegTestCase {

    @Override
    protected Settings nodeSettings(int nodeOrdinal)  {
        Settings.Builder newSettings = Settings.builder();
        newSettings.put(super.nodeSettings(nodeOrdinal));
        newSettings.put(XPackSettings.SECURITY_ENABLED.getKey(), false);
        newSettings.put(XPackSettings.MONITORING_ENABLED.getKey(), false);
        newSettings.put(XPackSettings.WATCHER_ENABLED.getKey(), false);
        newSettings.put(XPackSettings.MACHINE_LEARNING_ENABLED.getKey(), false);
        newSettings.put(XPackSettings.LOGSTASH_ENABLED.getKey(), false);
        return newSettings.build();
    }

    @Override
    protected Collection<Class<? extends Plugin>> getMockPlugins() {
        return Arrays.asList(TestSeedPlugin.class, TestZenDiscovery.TestPlugin.class);
    }

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return Arrays.asList(LocalStateCcr.class, CommonAnalysisPlugin.class);
    }

    @Override
    protected boolean ignoreExternalCluster() {
        return true;
    }

    @Override
    protected Collection<Class<? extends Plugin>> transportClientPlugins() {
        return nodePlugins();
    }

    // this emulates what the CCR persistent task will do for pulling
    public void testGetOperationsBasedOnGlobalSequenceId() throws Exception {
        client().admin().indices().prepareCreate("index")
                .setSettings(Settings.builder().put("index.number_of_shards", 1))
                .get();

        client().prepareIndex("index", "doc", "1").setSource("{}", XContentType.JSON).get();
        client().prepareIndex("index", "doc", "2").setSource("{}", XContentType.JSON).get();
        client().prepareIndex("index", "doc", "3").setSource("{}", XContentType.JSON).get();

        ShardStats shardStats = client().admin().indices().prepareStats("index").get().getIndex("index").getShards()[0];
        long globalCheckPoint = shardStats.getSeqNoStats().getGlobalCheckpoint();
        assertThat(globalCheckPoint, equalTo(2L));

        ShardChangesAction.Request request = new ShardChangesAction.Request(shardStats.getShardRouting().shardId());
        request.setMinSeqNo(0L);
        request.setMaxSeqNo(globalCheckPoint);
        ShardChangesAction.Response response = client().execute(ShardChangesAction.INSTANCE, request).get();
        assertThat(response.getOperations().length, equalTo(3));
        Translog.Index operation = (Translog.Index) response.getOperations()[0];
        assertThat(operation.seqNo(), equalTo(0L));
        assertThat(operation.id(), equalTo("1"));

        operation = (Translog.Index) response.getOperations()[1];
        assertThat(operation.seqNo(), equalTo(1L));
        assertThat(operation.id(), equalTo("2"));

        operation = (Translog.Index) response.getOperations()[2];
        assertThat(operation.seqNo(), equalTo(2L));
        assertThat(operation.id(), equalTo("3"));

        client().prepareIndex("index", "doc", "3").setSource("{}", XContentType.JSON).get();
        client().prepareIndex("index", "doc", "4").setSource("{}", XContentType.JSON).get();
        client().prepareIndex("index", "doc", "5").setSource("{}", XContentType.JSON).get();

        shardStats = client().admin().indices().prepareStats("index").get().getIndex("index").getShards()[0];
        globalCheckPoint = shardStats.getSeqNoStats().getGlobalCheckpoint();
        assertThat(globalCheckPoint, equalTo(5L));

        request = new ShardChangesAction.Request(shardStats.getShardRouting().shardId());
        request.setMinSeqNo(3L);
        request.setMaxSeqNo(globalCheckPoint);
        response = client().execute(ShardChangesAction.INSTANCE, request).get();
        assertThat(response.getOperations().length, equalTo(3));
        operation = (Translog.Index) response.getOperations()[0];
        assertThat(operation.seqNo(), equalTo(3L));
        assertThat(operation.id(), equalTo("3"));

        operation = (Translog.Index) response.getOperations()[1];
        assertThat(operation.seqNo(), equalTo(4L));
        assertThat(operation.id(), equalTo("4"));

        operation = (Translog.Index) response.getOperations()[2];
        assertThat(operation.seqNo(), equalTo(5L));
        assertThat(operation.id(), equalTo("5"));
    }

    public void testFollowIndex() throws Exception {
        final int numberOfPrimaryShards = randomIntBetween(1, 3);

        final String leaderIndexSettings = getIndexSettings(numberOfPrimaryShards, Collections.emptyMap());
        assertAcked(client().admin().indices().prepareCreate("index1").setSource(leaderIndexSettings, XContentType.JSON));

        final String followerIndexSettings =
                getIndexSettings(numberOfPrimaryShards, Collections.singletonMap(CcrSettings.CCR_FOLLOWING_INDEX_SETTING.getKey(), "true"));
        assertAcked(client().admin().indices().prepareCreate("index2").setSource(followerIndexSettings, XContentType.JSON));

        ensureGreen("index1", "index2");

        final FollowExistingIndexAction.Request followRequest = new FollowExistingIndexAction.Request();
        followRequest.setLeaderIndex("index1");
        followRequest.setFollowIndex("index2");
        client().execute(FollowExistingIndexAction.INSTANCE, followRequest).get();

        final int firstBatchNumDocs = randomIntBetween(2, 64);
        for (int i = 0; i < firstBatchNumDocs; i++) {
            final String source = String.format(Locale.ROOT, "{\"f\":%d}", i);
            client().prepareIndex("index1", "doc", Integer.toString(i)).setSource(source, XContentType.JSON).get();
        }

        final Map<ShardId, Long> firstBatchNumDocsPerShard = new HashMap<>();
        final ShardStats[] firstBatchShardStats = client().admin().indices().prepareStats("index1").get().getIndex("index1").getShards();
        for (final ShardStats shardStats : firstBatchShardStats) {
            if (shardStats.getShardRouting().primary()) {
                long value = shardStats.getStats().getIndexing().getTotal().getIndexCount() - 1;
                firstBatchNumDocsPerShard.put(shardStats.getShardRouting().shardId(), value);
            }
        }

        assertBusy(assertTask(numberOfPrimaryShards, firstBatchNumDocsPerShard));

        for (int i = 0; i < firstBatchNumDocs; i++) {
            assertBusy(assertExpectedDocumentRunnable(i));
        }

        final int secondBatchNumDocs = randomIntBetween(2, 64);
        for (int i = firstBatchNumDocs; i < firstBatchNumDocs + secondBatchNumDocs; i++) {
            final String source = String.format(Locale.ROOT, "{\"f\":%d}", i);
            client().prepareIndex("index1", "doc", Integer.toString(i)).setSource(source, XContentType.JSON).get();
        }

        final Map<ShardId, Long> secondBatchNumDocsPerShard = new HashMap<>();
        final ShardStats[] secondBatchShardStats = client().admin().indices().prepareStats("index1").get().getIndex("index1").getShards();
        for (final ShardStats shardStats : secondBatchShardStats) {
            if (shardStats.getShardRouting().primary()) {
                final long value = shardStats.getStats().getIndexing().getTotal().getIndexCount() - 1;
                secondBatchNumDocsPerShard.put(shardStats.getShardRouting().shardId(), value);
            }
        }

        assertBusy(assertTask(numberOfPrimaryShards, secondBatchNumDocsPerShard));

        for (int i = firstBatchNumDocs; i < firstBatchNumDocs + secondBatchNumDocs; i++) {
            assertBusy(assertExpectedDocumentRunnable(i));
        }

        final UnfollowIndexAction.Request unfollowRequest = new UnfollowIndexAction.Request();
        unfollowRequest.setFollowIndex("index2");
        client().execute(UnfollowIndexAction.INSTANCE, unfollowRequest).get();

        assertBusy(() -> {
            final ClusterState clusterState = client().admin().cluster().prepareState().get().getState();
            final PersistentTasksCustomMetaData tasks = clusterState.getMetaData().custom(PersistentTasksCustomMetaData.TYPE);
            assertThat(tasks.tasks().size(), equalTo(0));
        });
    }

    private CheckedRunnable<Exception> assertTask(final int numberOfPrimaryShards, final Map<ShardId, Long> numDocsPerShard) {
        return () -> {
            final ClusterState clusterState = client().admin().cluster().prepareState().get().getState();
            final PersistentTasksCustomMetaData tasks = clusterState.getMetaData().custom(PersistentTasksCustomMetaData.TYPE);
            assertThat(tasks.tasks().size(), equalTo(numberOfPrimaryShards));

            for (PersistentTasksCustomMetaData.PersistentTask<?> task : tasks.tasks()) {
                final ShardFollowTask shardFollowTask = (ShardFollowTask) task.getParams();
                final ShardFollowTask.Status status = (ShardFollowTask.Status) task.getStatus();
                assertThat(status, notNullValue());
                assertThat(
                        status.getProcessedGlobalCheckpoint(),
                        equalTo(numDocsPerShard.get(shardFollowTask.getLeaderShardId())));
            }
        };
    }

    private CheckedRunnable<Exception> assertExpectedDocumentRunnable(final int value) {
        return () -> {
            final GetResponse getResponse = client().prepareGet("index2", "doc", Integer.toString(value)).get();
            assertTrue(getResponse.isExists());
            assertTrue((getResponse.getSource().containsKey("f")));
            assertThat(getResponse.getSource().get("f"), equalTo(value));
        };
    }

    private String getIndexSettings(final int numberOfPrimaryShards, final Map<String, String> additionalIndexSettings) throws IOException {
        final String settings;
        try (XContentBuilder builder = jsonBuilder()) {
            builder.startObject();
            {
                builder.startObject("settings");
                {
                    builder.field("index.number_of_shards", numberOfPrimaryShards);
                    for (final Map.Entry<String, String> additionalSetting : additionalIndexSettings.entrySet()) {
                        builder.field(additionalSetting.getKey(), additionalSetting.getValue());
                    }
                }
                builder.endObject();
                builder.startObject("mappings");
                {
                    builder.startObject("doc");
                    {
                        builder.startObject("properties");
                        {
                            builder.startObject("f");
                            {
                                builder.field("type", "integer");
                            }
                            builder.endObject();
                        }
                        builder.endObject();
                    }
                    builder.endObject();
                }
                builder.endObject();
            }
            builder.endObject();
            settings = builder.string();
        }
        return settings;
    }

}
