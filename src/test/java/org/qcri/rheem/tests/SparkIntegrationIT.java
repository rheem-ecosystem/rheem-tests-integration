package org.qcri.rheem.tests;

import org.junit.Assert;
import org.junit.Test;
import org.qcri.rheem.basic.operators.CollectionSource;
import org.qcri.rheem.basic.operators.FilterOperator;
import org.qcri.rheem.basic.operators.LocalCallbackSink;
import org.qcri.rheem.core.api.Job;
import org.qcri.rheem.core.api.RheemContext;
import org.qcri.rheem.core.api.exception.RheemException;
import org.qcri.rheem.core.function.ExecutionContext;
import org.qcri.rheem.core.function.PredicateDescriptor;
import org.qcri.rheem.core.plan.rheemplan.RheemPlan;
import org.qcri.rheem.core.types.DataSetType;
import org.qcri.rheem.core.util.RheemCollections;
import org.qcri.rheem.spark.platform.SparkPlatform;
import org.qcri.rheem.spark.plugin.SparkBasicPlugin;
import org.qcri.rheem.tests.platform.MyMadeUpPlatform;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Test the Spark integration with Rheem.
 */
public class SparkIntegrationIT {

    @Test
    public void testReadAndWrite() throws URISyntaxException, IOException {
        // Build a Rheem plan.
        List<String> collector = new LinkedList<>();
        RheemPlan rheemPlan = RheemPlans.readWrite(RheemPlans.FILE_SOME_LINES_TXT, collector);

        // Instantiate Rheem and activate the Spark backend.
        RheemContext rheemContext = new RheemContext().with(new SparkBasicPlugin());

        // Have Rheem execute the plan.
        rheemContext.execute(rheemPlan);

        // Verify the plan result.
        final List<String> lines = Files.lines(Paths.get(RheemPlans.FILE_SOME_LINES_TXT)).collect(Collectors.toList());
        Assert.assertEquals(lines, collector);
    }

    @Test
    public void testReadAndTransformAndWrite() throws URISyntaxException {
        // Build a Rheem plan.
        final RheemPlan rheemPlan = RheemPlans.readTransformWrite(RheemPlans.FILE_SOME_LINES_TXT);

        // Instantiate Rheem and activate the Spark backend.
        RheemContext rheemContext = new RheemContext().with(new SparkBasicPlugin());

        // Have Rheem execute the plan.
        rheemContext.execute(rheemPlan);
    }

    @Test(expected = RheemException.class)
    public void testReadAndTransformAndWriteWithIllegalConfiguration1() throws URISyntaxException {
        // Build a Rheem plan.
        final RheemPlan rheemPlan = RheemPlans.readTransformWrite(RheemPlans.FILE_SOME_LINES_TXT);
        // ILLEGAL: This platform is not registered, so this operator will find no implementation.
        rheemPlan.getSinks().forEach(sink -> sink.addTargetPlatform(MyMadeUpPlatform.getInstance()));

        // Instantiate Rheem and activate the Spark backend.
        RheemContext rheemContext = new RheemContext().with(new SparkBasicPlugin());


        // Have Rheem execute the plan.
        rheemContext.execute(rheemPlan);

        // Have Rheem execute the plan.
        rheemContext.execute(rheemPlan);
    }

    @Test(expected = RheemException.class)
    public void testReadAndTransformAndWriteWithIllegalConfiguration2() throws URISyntaxException {
        // Build a Rheem plan.
        final RheemPlan rheemPlan = RheemPlans.readTransformWrite(RheemPlans.FILE_SOME_LINES_TXT);

        RheemContext rheemContext = new RheemContext();
        // ILLEGAL: This dummy platform is not sufficient to execute the plan.
        rheemContext.register(MyMadeUpPlatform.getInstance());

        // Have Rheem execute the plan.
        rheemContext.execute(rheemPlan);
    }

    @Test(expected = RheemException.class)
    public void testReadAndTransformAndWriteWithIllegalConfiguration3() throws URISyntaxException {
        // Build a Rheem plan.
        final RheemPlan rheemPlan = RheemPlans.readTransformWrite(RheemPlans.FILE_SOME_LINES_TXT);

        // Instantiate Rheem and activate the Spark backend.
        RheemContext rheemContext = new RheemContext().with(new SparkBasicPlugin());

        // Have Rheem execute the plan.
        final Job job = rheemContext.createJob(null, rheemPlan);
        // ILLEGAL: We blacklist the Spark platform, although we need it.
        job.getConfiguration().getPlatformProvider().addToBlacklist(SparkPlatform.getInstance());
        job.getConfiguration().getPlatformProvider().addToWhitelist(MyMadeUpPlatform.getInstance());
        job.execute();
    }

    @Test
    public void testMultiSourceAndMultiSink() throws URISyntaxException {
        // Define some input data.
        final List<String> collection1 = Arrays.asList("This is source 1.", "This is source 1, too.");
        final List<String> collection2 = Arrays.asList("This is source 2.", "This is source 2, too.");
        List<String> collector1 = new LinkedList<>();
        List<String> collector2 = new LinkedList<>();
        final RheemPlan rheemPlan = RheemPlans.multiSourceMultiSink(collection1, collection2, collector1, collector2);

        // Instantiate Rheem and activate the Spark backend.
        RheemContext rheemContext = new RheemContext().with(new SparkBasicPlugin());

        // Have Rheem execute the plan.
        rheemContext.execute(rheemPlan);

        // Check the results in both sinks.
        List<String> expectedOutcome1 = Stream.concat(collection1.stream(), collection2.stream())
                .map(String::toUpperCase)
                .collect(Collectors.toList());
        List<String> expectedOutcome2 = Stream.concat(collection1.stream(), collection2.stream())
                .collect(Collectors.toList());
        Collections.sort(expectedOutcome1);
        Collections.sort(expectedOutcome2);
        Collections.sort(collector1);
        Collections.sort(collector2);
        Assert.assertEquals(expectedOutcome1, collector1);
        Assert.assertEquals(expectedOutcome2, collector2);
    }

    @Test
    public void testMultiSourceAndHoleAndMultiSink() throws URISyntaxException {
        // Define some input data.
        final List<String> collection1 = Arrays.asList("This is source 1.", "This is source 1, too.");
        final List<String> collection2 = Arrays.asList("This is source 2.", "This is source 2, too.");
        List<String> collector1 = new LinkedList<>();
        List<String> collector2 = new LinkedList<>();
        final RheemPlan rheemPlan = RheemPlans.multiSourceHoleMultiSink(collection1, collection2, collector1, collector2);

        // Instantiate Rheem and activate the Spark backend.
        RheemContext rheemContext = new RheemContext().with(new SparkBasicPlugin());

        // Have Rheem execute the plan.
        rheemContext.execute(rheemPlan);

        // Check the results in both sinks.
        List<String> expectedOutcome = Stream.concat(collection1.stream(), collection2.stream())
                .flatMap(string -> Arrays.asList(string.toLowerCase(), string.toUpperCase()).stream())
                .collect(Collectors.toList());
        Collections.sort(expectedOutcome);
        Collections.sort(collector1);
        Collections.sort(collector2);
        Assert.assertEquals(expectedOutcome, collector1);
        Assert.assertEquals(expectedOutcome, collector2);
    }

    @Test
    public void testGlobalMaterializedGroup() throws URISyntaxException {
        // Build the RheemPlan.
        List<Iterable<Integer>> collector = new LinkedList<>();
        RheemPlan rheemPlan = RheemPlans.globalMaterializedGroup(collector, 1, 2, 3);

        // Instantiate Rheem and activate the Java backend.
        RheemContext rheemContext = new RheemContext().with(new SparkBasicPlugin());

        rheemContext.execute(rheemPlan);

        Assert.assertEquals(1, collector.size());
        Assert.assertEquals(RheemCollections.asSet(1, 2, 3), RheemCollections.asCollection(collector.get(0), HashSet::new));
    }

    @Test
    public void testIntersect() throws URISyntaxException {
        // Build the RheemPlan.
        List<Integer> collector = new LinkedList<>();
        RheemPlan rheemPlan = RheemPlans.intersectSquares(collector, 0, 1, 2, 3, 3, -1, -1, -2, -3, -3, -4);

        // Instantiate Rheem and activate the Java backend.
        RheemContext rheemContext = new RheemContext().with(new SparkBasicPlugin());

        rheemContext.execute(rheemPlan);

        Assert.assertEquals(RheemCollections.asSet(1, 4, 9), RheemCollections.asSet(collector));
    }

    @Test
    public void testZipWithId() throws URISyntaxException {
        // Build the RheemPlan.
        List<Long> collector = new LinkedList<>();
        RheemPlan rheemPlan = RheemPlans.zipWithId(collector, 0, 10, 20, 30, 30);

        // Instantiate Rheem and activate the Java backend.
        RheemContext rheemContext = new RheemContext().with(new SparkBasicPlugin());

        rheemContext.execute(rheemPlan);

        Assert.assertEquals(1, collector.size());
        Assert.assertEquals(Long.valueOf(5L), collector.get(0));
    }

    @Test
    public void testDiverseScenario1() throws URISyntaxException {
        // Build the RheemPlan.
        RheemPlan rheemPlan = RheemPlans.diverseScenario1(RheemPlans.FILE_SOME_LINES_TXT);

        // Instantiate Rheem and activate the Spark backend.
        RheemContext rheemContext = new RheemContext().with(new SparkBasicPlugin());

        rheemContext.execute(rheemPlan);
    }

    @Test
    public void testDiverseScenario2() throws URISyntaxException {
        // Build the RheemPlan.
        RheemPlan rheemPlan = RheemPlans.diverseScenario2(RheemPlans.FILE_SOME_LINES_TXT, RheemPlans.FILE_OTHER_LINES_TXT);

        // Instantiate Rheem and activate the Spark backend.
        RheemContext rheemContext = new RheemContext().with(new SparkBasicPlugin());

        rheemContext.execute(rheemPlan);
    }

    @Test
    public void testDiverseScenario3() throws URISyntaxException {
        // Build the RheemPlan.
        RheemPlan rheemPlan = RheemPlans.diverseScenario3(RheemPlans.FILE_SOME_LINES_TXT, RheemPlans.FILE_OTHER_LINES_TXT);

        // Instantiate Rheem and activate the Spark backend.
        RheemContext rheemContext = new RheemContext().with(new SparkBasicPlugin());

        rheemContext.execute(rheemPlan);
    }

    @Test
    public void testDiverseScenario4() throws URISyntaxException {
        // Build the RheemPlan.
        RheemPlan rheemPlan = RheemPlans.diverseScenario4(RheemPlans.FILE_SOME_LINES_TXT, RheemPlans.FILE_OTHER_LINES_TXT);

        // Instantiate Rheem and activate the Java backend.
        RheemContext rheemContext = new RheemContext().with(new SparkBasicPlugin());

        rheemContext.execute(rheemPlan);
    }

    @Test
    public void testSimpleLoop() throws URISyntaxException {
        // Build the RheemPlan.
        final List<Integer> collector = new LinkedList<>();
        RheemPlan rheemPlan = RheemPlans.simpleLoop(3, collector, 0, 1, 2);

        // Instantiate Rheem and activate the Java backend.
        RheemContext rheemContext = new RheemContext().with(new SparkBasicPlugin());

        rheemContext.execute(rheemPlan);
        System.out.println(collector);
    }

    @Test
    public void testSample() throws URISyntaxException {
        // Build the RheemPlan.
        final List<Integer> collector = new LinkedList<>();
        RheemPlan rheemPlan = RheemPlans.simpleSample(collector, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9);

        // Instantiate Rheem and activate the Java backend.
        RheemContext rheemContext = new RheemContext().with(new SparkBasicPlugin());

        rheemContext.execute(rheemPlan);
        System.out.println(collector);
    }

    @Test
    public void testBroadcasts() {
        Collection<Integer> broadcastedValues = Arrays.asList(1, 2, 3, 4);
        Collection<Integer> mainValues = Arrays.asList(2, 4, 6, 2);
        List<Integer> collectedValues = new ArrayList<>();
        List<Integer> expectedValues = Arrays.asList(2, 2, 4);

        final DataSetType<Integer> integerDataSetType = DataSetType.createDefault(Integer.class);
        CollectionSource<Integer> broadcastSource = new CollectionSource<>(broadcastedValues, integerDataSetType);
        CollectionSource<Integer> mainSource = new CollectionSource<>(mainValues, integerDataSetType);
        FilterOperator<Integer> semijoin = new FilterOperator<>(integerDataSetType, new SemijoinFunction());
        final LocalCallbackSink<Integer> collectingSink =
                LocalCallbackSink.createCollectingSink(collectedValues, integerDataSetType);

        mainSource.connectTo(0, semijoin, 0);
        broadcastSource.broadcastTo(0, semijoin, "allowed values");
        semijoin.connectTo(0, collectingSink, 0);

        RheemPlan rheemPlan = new RheemPlan(collectingSink);

        // Instantiate Rheem and activate the Java backend.
        RheemContext rheemContext = new RheemContext().with(new SparkBasicPlugin());

        rheemContext.execute(rheemPlan);

        Collections.sort(collectedValues);
        Assert.assertEquals(expectedValues, collectedValues);
    }

    private static class SemijoinFunction implements PredicateDescriptor.ExtendedSerializablePredicate<Integer> {

        private Set<Integer> allowedInts;

        @Override
        public void open(ExecutionContext ctx) {
            this.allowedInts = new HashSet<>(ctx.getBroadcast("allowed values"));
        }

        @Override
        public boolean test(Integer integer) {
            return this.allowedInts.contains(integer);
        }
    }
}
