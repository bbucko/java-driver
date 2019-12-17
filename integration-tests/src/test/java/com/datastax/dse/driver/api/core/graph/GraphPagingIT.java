/*
 * Copyright DataStax, Inc.
 *
 * This software can be used solely with DataStax Enterprise. Please consult the license at
 * http://www.datastax.com/terms/datastax-dse-driver-license-terms
 */
package com.datastax.dse.driver.api.core.graph;

import static com.datastax.dse.driver.api.core.cql.continuous.ContinuousPagingITBase.Options;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

import com.codahale.metrics.Timer;
import com.datastax.dse.driver.DseNodeMetrics;
import com.datastax.dse.driver.DseSessionMetric;
import com.datastax.dse.driver.api.core.DseSession;
import com.datastax.dse.driver.api.core.config.DseDriverOption;
import com.datastax.dse.driver.api.core.cql.continuous.ContinuousPagingITBase;
import com.datastax.dse.driver.api.testinfra.session.DseSessionRule;
import com.datastax.dse.driver.internal.core.graph.MultiPageGraphResultSet;
import com.datastax.oss.driver.api.core.DriverTimeoutException;
import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.datastax.oss.driver.api.core.config.DriverExecutionProfile;
import com.datastax.oss.driver.api.core.cql.ExecutionInfo;
import com.datastax.oss.driver.api.core.metadata.Node;
import com.datastax.oss.driver.api.core.metrics.Metrics;
import com.datastax.oss.driver.api.testinfra.DseRequirement;
import com.datastax.oss.driver.api.testinfra.ccm.CustomCcmRule;
import com.datastax.oss.driver.api.testinfra.session.SessionUtils;
import com.datastax.oss.driver.internal.core.util.CountingIterator;
import com.tngtech.java.junit.dataprovider.DataProviderRunner;
import com.tngtech.java.junit.dataprovider.UseDataProvider;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;

@DseRequirement(min = "6.8.0", description = "Graph paging requires DSE 6.8+")
@RunWith(DataProviderRunner.class)
public class GraphPagingIT {

  private static final CustomCcmRule CCM_RULE = GraphTestSupport.GRAPH_CCM_RULE_BUILDER.build();

  private static final DseSessionRule SESSION_RULE =
      GraphTestSupport.getCoreGraphSessionBuilder(CCM_RULE)
          .withConfigLoader(
              SessionUtils.configLoaderBuilder()
                  .withStringList(
                      DefaultDriverOption.METRICS_SESSION_ENABLED,
                      Collections.singletonList(DseSessionMetric.GRAPH_REQUESTS.getPath()))
                  .withStringList(
                      DefaultDriverOption.METRICS_NODE_ENABLED,
                      Collections.singletonList(DseNodeMetrics.GRAPH_MESSAGES.getPath()))
                  .build())
          .build();

  @ClassRule
  public static final TestRule CHAIN = RuleChain.outerRule(CCM_RULE).around(SESSION_RULE);

  @BeforeClass
  public static void setupSchema() {
    SESSION_RULE
        .session()
        .execute(
            ScriptGraphStatement.newInstance(
                    "schema.vertexLabel('person')"
                        + ".partitionBy('pk', Int)"
                        + ".clusterBy('cc', Int)"
                        + ".property('name', Text)"
                        + ".create();")
                .setGraphName(SESSION_RULE.getGraphName()));
    for (int i = 1; i <= 100; i++) {
      SESSION_RULE
          .session()
          .execute(
              ScriptGraphStatement.newInstance(
                      String.format(
                          "g.addV('person').property('pk',0).property('cc',%d).property('name', '%s');",
                          i, "user" + i))
                  .setGraphName(SESSION_RULE.getGraphName()));
    }
  }

  @UseDataProvider(location = ContinuousPagingITBase.class, value = "pagingOptions")
  @Test
  public void synchronous_paging_with_options(Options options) {
    // given
    DriverExecutionProfile profile = enableGraphPaging(options, PagingEnabledOptions.ENABLED);

    if (options.sizeInBytes) {
      // Page sizes in bytes are not supported with graph queries
      return;
    }

    // when
    GraphResultSet result =
        SESSION_RULE
            .session()
            .execute(
                ScriptGraphStatement.newInstance("g.V().hasLabel('person').values('name')")
                    .setGraphName(SESSION_RULE.getGraphName())
                    .setTraversalSource("g")
                    .setExecutionProfile(profile));

    // then
    List<GraphNode> nodes = result.all();

    assertThat(((CountingIterator) result.iterator()).remaining()).isZero();
    assertThat(nodes).hasSize(options.expectedRows);
    for (int i = 1; i <= nodes.size(); i++) {
      GraphNode node = nodes.get(i - 1);
      assertThat(node.asString()).isEqualTo("user" + i);
    }
    assertThat(result.getRequestExecutionInfo()).isNotNull();
    assertThat(result.getRequestExecutionInfo().getCoordinator().getEndPoint().resolve())
        .isEqualTo(firstCcmNode());
    assertIfMultiPage(result, options.expectedPages);
    validateMetrics(SESSION_RULE.session());
  }

  @UseDataProvider(location = ContinuousPagingITBase.class, value = "pagingOptions")
  @Test
  public void synchronous_paging_with_options_when_auto(Options options) {
    // given
    DriverExecutionProfile profile = enableGraphPaging(options, PagingEnabledOptions.AUTO);

    if (options.sizeInBytes) {
      // Page sizes in bytes are not supported with graph queries
      return;
    }

    // when
    GraphResultSet result =
        SESSION_RULE
            .session()
            .execute(
                ScriptGraphStatement.newInstance("g.V().hasLabel('person').values('name')")
                    .setGraphName(SESSION_RULE.getGraphName())
                    .setTraversalSource("g")
                    .setExecutionProfile(profile));

    // then
    List<GraphNode> nodes = result.all();

    assertThat(((CountingIterator) result.iterator()).remaining()).isZero();
    assertThat(nodes).hasSize(options.expectedRows);
    for (int i = 1; i <= nodes.size(); i++) {
      GraphNode node = nodes.get(i - 1);
      assertThat(node.asString()).isEqualTo("user" + i);
    }
    assertThat(result.getRequestExecutionInfo()).isNotNull();
    assertThat(result.getRequestExecutionInfo().getCoordinator().getEndPoint().resolve())
        .isEqualTo(firstCcmNode());

    assertIfMultiPage(result, options.expectedPages);
    validateMetrics(SESSION_RULE.session());
  }

  private void assertIfMultiPage(GraphResultSet result, int expectedPages) {
    if (result instanceof MultiPageGraphResultSet) {
      assertThat(((MultiPageGraphResultSet) result).getRequestExecutionInfos())
          .hasSize(expectedPages);
      assertThat(result.getRequestExecutionInfo())
          .isSameAs(
              ((MultiPageGraphResultSet) result).getRequestExecutionInfos().get(expectedPages - 1));
    }
  }

  @UseDataProvider(location = ContinuousPagingITBase.class, value = "pagingOptions")
  @Test
  public void synchronous_options_with_paging_disabled_should_fallback_to_single_page(
      Options options) {
    // given
    DriverExecutionProfile profile = enableGraphPaging(options, PagingEnabledOptions.DISABLED);

    if (options.sizeInBytes) {
      // Page sizes in bytes are not supported with graph queries
      return;
    }

    // when
    GraphResultSet result =
        SESSION_RULE
            .session()
            .execute(
                ScriptGraphStatement.newInstance("g.V().hasLabel('person').values('name')")
                    .setGraphName(SESSION_RULE.getGraphName())
                    .setTraversalSource("g")
                    .setExecutionProfile(profile));

    // then
    List<GraphNode> nodes = result.all();

    assertThat(((CountingIterator) result.iterator()).remaining()).isZero();
    assertThat(nodes).hasSize(100);
    for (int i = 1; i <= nodes.size(); i++) {
      GraphNode node = nodes.get(i - 1);
      assertThat(node.asString()).isEqualTo("user" + i);
    }
    assertThat(result.getRequestExecutionInfo()).isNotNull();
    assertThat(result.getRequestExecutionInfo().getCoordinator().getEndPoint().resolve())
        .isEqualTo(firstCcmNode());
    validateMetrics(SESSION_RULE.session());
  }

  @UseDataProvider(location = ContinuousPagingITBase.class, value = "pagingOptions")
  @Test
  public void asynchronous_paging_with_options(Options options)
      throws ExecutionException, InterruptedException {
    // given
    DriverExecutionProfile profile = enableGraphPaging(options, PagingEnabledOptions.ENABLED);

    if (options.sizeInBytes) {
      // Page sizes in bytes are not supported with graph queries
      return;
    }

    // when
    CompletionStage<AsyncGraphResultSet> result =
        SESSION_RULE
            .session()
            .executeAsync(
                ScriptGraphStatement.newInstance("g.V().hasLabel('person').values('name')")
                    .setGraphName(SESSION_RULE.getGraphName())
                    .setTraversalSource("g")
                    .setExecutionProfile(profile));

    // then
    checkAsyncResult(result, options, 0, 1, new ArrayList<>());
    validateMetrics(SESSION_RULE.session());
  }

  @UseDataProvider(location = ContinuousPagingITBase.class, value = "pagingOptions")
  @Test
  public void asynchronous_paging_with_options_when_auto(Options options)
      throws ExecutionException, InterruptedException {
    // given
    DriverExecutionProfile profile = enableGraphPaging(options, PagingEnabledOptions.AUTO);

    if (options.sizeInBytes) {
      // Page sizes in bytes are not supported with graph queries
      return;
    }

    // when
    CompletionStage<AsyncGraphResultSet> result =
        SESSION_RULE
            .session()
            .executeAsync(
                ScriptGraphStatement.newInstance("g.V().hasLabel('person').values('name')")
                    .setGraphName(SESSION_RULE.getGraphName())
                    .setTraversalSource("g")
                    .setExecutionProfile(profile));

    // then
    checkAsyncResult(result, options, 0, 1, new ArrayList<>());
    validateMetrics(SESSION_RULE.session());
  }

  @UseDataProvider(location = ContinuousPagingITBase.class, value = "pagingOptions")
  @Test
  public void asynchronous_options_with_paging_disabled_should_fallback_to_single_page(
      Options options) throws ExecutionException, InterruptedException {
    // given
    DriverExecutionProfile profile = enableGraphPaging(options, PagingEnabledOptions.DISABLED);

    if (options.sizeInBytes) {
      // Page sizes in bytes are not supported with graph queries
      return;
    }

    // when
    CompletionStage<AsyncGraphResultSet> result =
        SESSION_RULE
            .session()
            .executeAsync(
                ScriptGraphStatement.newInstance("g.V().hasLabel('person').values('name')")
                    .setGraphName(SESSION_RULE.getGraphName())
                    .setTraversalSource("g")
                    .setExecutionProfile(profile));

    // then
    AsyncGraphResultSet asyncGraphResultSet = result.toCompletableFuture().get();
    for (int i = 1; i <= 100; i++, asyncGraphResultSet.remaining()) {
      GraphNode node = asyncGraphResultSet.one();
      assertThat(node.asString()).isEqualTo("user" + i);
    }
    assertThat(asyncGraphResultSet.remaining()).isEqualTo(0);
    validateMetrics(SESSION_RULE.session());
  }

  private void checkAsyncResult(
      CompletionStage<AsyncGraphResultSet> future,
      Options options,
      int rowsFetched,
      int pageNumber,
      List<ExecutionInfo> graphExecutionInfos)
      throws ExecutionException, InterruptedException {
    AsyncGraphResultSet result = future.toCompletableFuture().get();
    int remaining = result.remaining();
    rowsFetched += remaining;
    assertThat(remaining).isLessThanOrEqualTo(options.pageSize);

    if (options.expectedRows == rowsFetched) {
      assertThat(result.hasMorePages()).isFalse();
    } else {
      assertThat(result.hasMorePages()).isTrue();
    }

    int first = (pageNumber - 1) * options.pageSize + 1;
    int last = (pageNumber - 1) * options.pageSize + remaining;

    for (int i = first; i <= last; i++, remaining--) {
      GraphNode node = result.one();
      assertThat(node.asString()).isEqualTo("user" + i);
      assertThat(result.remaining()).isEqualTo(remaining - 1);
    }

    assertThat(result.remaining()).isZero();
    assertThat(result.getRequestExecutionInfo()).isNotNull();
    assertThat(result.getRequestExecutionInfo().getCoordinator().getEndPoint().resolve())
        .isEqualTo(firstCcmNode());

    graphExecutionInfos.add(result.getRequestExecutionInfo());

    assertThat(graphExecutionInfos).hasSize(pageNumber);
    assertThat(result.getRequestExecutionInfo()).isSameAs(graphExecutionInfos.get(pageNumber - 1));
    if (pageNumber == options.expectedPages) {
      assertThat(result.hasMorePages()).isFalse();
      assertThat(options.expectedRows).isEqualTo(rowsFetched);
      assertThat(options.expectedPages).isEqualTo(pageNumber);
    } else {
      assertThat(result.hasMorePages()).isTrue();
      checkAsyncResult(
          result.fetchNextPage(), options, rowsFetched, pageNumber + 1, graphExecutionInfos);
    }
  }

  @Test
  public void should_cancel_result_set() {
    // given
    DriverExecutionProfile profile =
        enableGraphPaging()
            .withInt(DseDriverOption.GRAPH_CONTINUOUS_PAGING_MAX_ENQUEUED_PAGES, 1)
            .withInt(DseDriverOption.GRAPH_CONTINUOUS_PAGING_PAGE_SIZE, 10);

    // when
    GraphStatement statement =
        ScriptGraphStatement.newInstance("g.V().hasLabel('person').values('name')")
            .setGraphName(SESSION_RULE.getGraphName())
            .setTraversalSource("g")
            .setExecutionProfile(profile);
    MultiPageGraphResultSet results =
        (MultiPageGraphResultSet) SESSION_RULE.session().execute(statement);

    assertThat(((MultiPageGraphResultSet.RowIterator) results.iterator()).isCancelled()).isFalse();
    assertThat(((CountingIterator) results.iterator()).remaining()).isEqualTo(10);
    results.cancel();

    assertThat(((MultiPageGraphResultSet.RowIterator) results.iterator()).isCancelled()).isTrue();
    assertThat(((CountingIterator) results.iterator()).remaining()).isEqualTo(10);
    for (int i = 0; i < 10; i++) {
      results.one();
    }
  }

  @Test
  public void should_trigger_global_timeout_sync_from_config() {
    // given
    Duration timeout = Duration.ofMillis(100);
    DriverExecutionProfile profile =
        enableGraphPaging().withDuration(DseDriverOption.GRAPH_TIMEOUT, timeout);

    // when
    try {
      CCM_RULE.getCcmBridge().pause(1);
      try {
        SESSION_RULE
            .session()
            .execute(
                ScriptGraphStatement.newInstance("g.V().hasLabel('person').values('name')")
                    .setGraphName(SESSION_RULE.getGraphName())
                    .setTraversalSource("g")
                    .setExecutionProfile(profile));
        fail("Expecting DriverTimeoutException");
      } catch (DriverTimeoutException e) {
        assertThat(e).hasMessage("Query timed out after " + timeout);
      }
    } finally {
      CCM_RULE.getCcmBridge().resume(1);
    }
  }

  @Test
  public void should_trigger_global_timeout_sync_from_statement() {
    // given
    Duration timeout = Duration.ofMillis(100);

    // when
    try {
      CCM_RULE.getCcmBridge().pause(1);
      try {
        SESSION_RULE
            .session()
            .execute(
                ScriptGraphStatement.newInstance("g.V().hasLabel('person').values('name')")
                    .setGraphName(SESSION_RULE.getGraphName())
                    .setTraversalSource("g")
                    .setTimeout(timeout));
        fail("Expecting DriverTimeoutException");
      } catch (DriverTimeoutException e) {
        assertThat(e).hasMessage("Query timed out after " + timeout);
      }
    } finally {
      CCM_RULE.getCcmBridge().resume(1);
    }
  }

  @Test
  public void should_trigger_global_timeout_async() throws InterruptedException {
    // given
    Duration timeout = Duration.ofMillis(100);
    DriverExecutionProfile profile =
        enableGraphPaging().withDuration(DseDriverOption.GRAPH_TIMEOUT, timeout);

    // when
    try {
      CCM_RULE.getCcmBridge().pause(1);
      CompletionStage<AsyncGraphResultSet> result =
          SESSION_RULE
              .session()
              .executeAsync(
                  ScriptGraphStatement.newInstance("g.V().hasLabel('person').values('name')")
                      .setGraphName(SESSION_RULE.getGraphName())
                      .setTraversalSource("g")
                      .setExecutionProfile(profile));
      result.toCompletableFuture().get();
      fail("Expecting DriverTimeoutException");
    } catch (ExecutionException e) {
      assertThat(e.getCause()).hasMessage("Query timed out after " + timeout);
    } finally {
      CCM_RULE.getCcmBridge().resume(1);
    }
  }

  @Test
  public void should_trigger_global_timeout_async_after_first_page() throws InterruptedException {
    // given
    Duration timeout = Duration.ofSeconds(1);
    DriverExecutionProfile profile =
        enableGraphPaging()
            .withDuration(DseDriverOption.GRAPH_TIMEOUT, timeout)
            .withInt(DseDriverOption.GRAPH_CONTINUOUS_PAGING_MAX_ENQUEUED_PAGES, 1)
            .withInt(DseDriverOption.GRAPH_CONTINUOUS_PAGING_PAGE_SIZE, 10);

    // when
    try {
      CompletionStage<AsyncGraphResultSet> firstPageFuture =
          SESSION_RULE
              .session()
              .executeAsync(
                  ScriptGraphStatement.newInstance("g.V().hasLabel('person').values('name')")
                      .setGraphName(SESSION_RULE.getGraphName())
                      .setTraversalSource("g")
                      .setExecutionProfile(profile));
      AsyncGraphResultSet firstPage = firstPageFuture.toCompletableFuture().get();
      CCM_RULE.getCcmBridge().pause(1);
      CompletionStage<AsyncGraphResultSet> secondPageFuture = firstPage.fetchNextPage();
      secondPageFuture.toCompletableFuture().get();
      fail("Expecting DriverTimeoutException");
    } catch (ExecutionException e) {
      assertThat(e.getCause()).hasMessage("Query timed out after " + timeout);
    } finally {
      CCM_RULE.getCcmBridge().resume(1);
    }
  }

  private DriverExecutionProfile enableGraphPaging() {
    return SESSION_RULE
        .session()
        .getContext()
        .getConfig()
        .getDefaultProfile()
        .withString(DseDriverOption.GRAPH_PAGING_ENABLED, PagingEnabledOptions.ENABLED.name());
  }

  private DriverExecutionProfile enableGraphPaging(
      Options options, PagingEnabledOptions pagingEnabledOptions) {
    return SESSION_RULE
        .session()
        .getContext()
        .getConfig()
        .getDefaultProfile()
        .withInt(DseDriverOption.GRAPH_CONTINUOUS_PAGING_PAGE_SIZE, options.pageSize)
        .withInt(DseDriverOption.GRAPH_CONTINUOUS_PAGING_MAX_PAGES, options.maxPages)
        .withInt(
            DseDriverOption.GRAPH_CONTINUOUS_PAGING_MAX_PAGES_PER_SECOND, options.maxPagesPerSecond)
        .withString(DseDriverOption.GRAPH_PAGING_ENABLED, pagingEnabledOptions.name());
  }

  private SocketAddress firstCcmNode() {
    return CCM_RULE.getContactPoints().iterator().next().resolve();
  }

  private void validateMetrics(DseSession session) {
    Node node = session.getMetadata().getNodes().values().iterator().next();
    assertThat(session.getMetrics()).isPresent();
    Metrics metrics = session.getMetrics().get();
    assertThat(metrics.getNodeMetric(node, DseNodeMetrics.GRAPH_MESSAGES)).isPresent();
    Timer messages = (Timer) metrics.getNodeMetric(node, DseNodeMetrics.GRAPH_MESSAGES).get();
    assertThat(messages.getCount()).isGreaterThan(0);
    assertThat(messages.getMeanRate()).isGreaterThan(0);
    assertThat(metrics.getSessionMetric(DseSessionMetric.GRAPH_REQUESTS)).isPresent();
    Timer requests = (Timer) metrics.getSessionMetric(DseSessionMetric.GRAPH_REQUESTS).get();
    assertThat(requests.getCount()).isGreaterThan(0);
    assertThat(requests.getMeanRate()).isGreaterThan(0);
  }
}