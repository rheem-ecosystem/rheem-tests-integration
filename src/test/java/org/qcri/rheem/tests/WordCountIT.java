package org.qcri.rheem.tests;

import org.junit.Assert;
import org.junit.Test;
import org.qcri.rheem.basic.data.Tuple2;
import org.qcri.rheem.basic.operators.*;
import org.qcri.rheem.core.api.Job;
import org.qcri.rheem.core.api.RheemContext;
import org.qcri.rheem.core.function.FlatMapDescriptor;
import org.qcri.rheem.core.function.ReduceDescriptor;
import org.qcri.rheem.core.function.TransformationDescriptor;
import org.qcri.rheem.core.optimizer.costs.DefaultLoadEstimator;
import org.qcri.rheem.core.optimizer.costs.LoadEstimator;
import org.qcri.rheem.core.optimizer.costs.LoadProfileToTimeConverter;
import org.qcri.rheem.core.optimizer.costs.LoadToTimeConverter;
import org.qcri.rheem.core.plan.rheemplan.RheemPlan;
import org.qcri.rheem.core.platform.Platform;
import org.qcri.rheem.core.types.DataSetType;
import org.qcri.rheem.core.types.DataUnitType;
import org.qcri.rheem.core.util.Counter;
import org.qcri.rheem.java.JavaPlatform;
import org.qcri.rheem.java.operators.JavaLocalCallbackSink;
import org.qcri.rheem.java.operators.JavaReduceByOperator;
import org.qcri.rheem.spark.operators.SparkFlatMapOperator;
import org.qcri.rheem.spark.operators.SparkMapOperator;
import org.qcri.rheem.spark.operators.SparkTextFileSource;
import org.qcri.rheem.spark.platform.SparkPlatform;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Word count integration test. Besides going through different {@link Platform} combinations, each test addresses a different
 * way of specifying the target {@link Platform}s.
 */
public class WordCountIT {

    @Test
    public void testOnJava() throws URISyntaxException, IOException {
        // Assignment mode: RheemContext.

        // Instantiate Rheem and activate the backend.
        RheemContext rheemContext = new RheemContext();
        rheemContext.register(JavaPlatform.getInstance());
        rheemContext.getConfiguration().getLoadProfileToTimeConverterProvider().set(
                JavaPlatform.getInstance(),
                LoadProfileToTimeConverter.createDefault(
                        LoadToTimeConverter.createLinearCoverter(1d / (2700 * 1000)),
                        LoadToTimeConverter.createLinearCoverter(2.2 / 1024 / 1024),
                        LoadToTimeConverter.createLinearCoverter(1d / 1024 / 1024),
                        (cpuEstimate, diskEstimate, networkEstimate) -> cpuEstimate.plus(diskEstimate).plus(networkEstimate)
                )
        );

        TextFileSource textFileSource = new TextFileSource(RheemPlans.FILE_SOME_LINES_TXT.toString());

        // for each line (input) output an iterator of the words
        FlatMapOperator<String, String> flatMapOperator = new FlatMapOperator<>(
                new FlatMapDescriptor<>(line -> Arrays.asList((String[]) line.split(" ")),
                        DataUnitType.createBasic(String.class),
                        DataUnitType.createBasic(String.class)
                ), DataSetType.createDefault(String.class),
                DataSetType.createDefault(String.class)
        );
        flatMapOperator.getFunctionDescriptor().setLoadEstimators(
                new DefaultLoadEstimator(1, 1, 0.9d, (inCards, outCards) -> inCards[0] * 670),
                LoadEstimator.createFallback(1, 1)
        );


        // for each word transform it to lowercase and output a key-value pair (word, 1)
        MapOperator<String, Tuple2<String, Integer>> mapOperator = new MapOperator<>(
                new TransformationDescriptor<>(word -> new Tuple2<String, Integer>(word.toLowerCase(), 1),
                        DataUnitType.<String>createBasic(String.class),
                        DataUnitType.<Tuple2<String, Integer>>createBasicUnchecked(Tuple2.class)
                ), DataSetType.createDefault(String.class),
                DataSetType.createDefaultUnchecked(Tuple2.class)
        );
        mapOperator.getFunctionDescriptor().setLoadEstimators(
                new DefaultLoadEstimator(1, 1, 0.9d, (inCards, outCards) -> inCards[0] * 245),
                LoadEstimator.createFallback(1, 1)
        );


        // groupby the key (word) and add up the values (frequency)
        ReduceByOperator<Tuple2<String, Integer>, String> reduceByOperator = new ReduceByOperator<>(
                new TransformationDescriptor<>(pair -> pair.field0,
                        DataUnitType.createBasic(Tuple2.class),
                        DataUnitType.createBasic(String.class)), new ReduceDescriptor<>(
                ((a, b) -> {
                    a.field1 += b.field1;
                    return a;
                }), DataUnitType.createGroupedUnchecked(Tuple2.class),
                DataUnitType.createBasicUnchecked(Tuple2.class)
        ), DataSetType.createDefaultUnchecked(Tuple2.class)
        );
        reduceByOperator.getKeyDescriptor().setLoadEstimators(
                new DefaultLoadEstimator(1, 1, 0.9d, (inCards, outCards) -> inCards[0] * 50),
                LoadEstimator.createFallback(1, 1)
        );
        reduceByOperator.getReduceDescriptor().setLoadEstimators(
                new DefaultLoadEstimator(1, 1, 0.9d, (inCards, outCards) -> inCards[0] * 350 + 500000),
                LoadEstimator.createFallback(1, 1)
        );


        // write results to a sink
        List<Tuple2> results = new ArrayList<>();
        LocalCallbackSink<Tuple2> sink = LocalCallbackSink.createCollectingSink(results, DataSetType.createDefault(Tuple2.class));

        // Build Rheem plan by connecting operators
        textFileSource.connectTo(0, flatMapOperator, 0);
        flatMapOperator.connectTo(0, mapOperator, 0);
        mapOperator.connectTo(0, reduceByOperator, 0);
        reduceByOperator.connectTo(0, sink, 0);

        // Have Rheem execute the plan.
        rheemContext.execute(new RheemPlan(sink));

        // Verify the plan result.
        Counter<String> counter = new Counter<>();
        List<Tuple2> correctResults = new ArrayList<>();
        final List<String> lines = Files.lines(Paths.get(RheemPlans.FILE_SOME_LINES_TXT)).collect(Collectors.toList());
        lines.stream()
                .flatMap(line -> Arrays.stream(line.split("\\s+")))
                .map(String::toLowerCase)
                .forEach(counter::increment);

        for (Map.Entry<String, Integer> countEntry : counter) {
            correctResults.add(new Tuple2<>(countEntry.getKey(), countEntry.getValue()));
        }
        Assert.assertTrue(results.size() == correctResults.size() && results.containsAll(correctResults) && correctResults.containsAll(results));
    }

    @Test
    public void testOnSpark() throws URISyntaxException, IOException {
        // Assignment mode: Job.

        TextFileSource textFileSource = new TextFileSource(RheemPlans.FILE_SOME_LINES_TXT.toString());

        // for each line (input) output an iterator of the words
        FlatMapOperator<String, String> flatMapOperator = new FlatMapOperator<>(
                new FlatMapDescriptor<>(line -> Arrays.asList((String[]) line.split(" ")),
                        DataUnitType.createBasic(String.class),
                        DataUnitType.createBasic(String.class)
                ), DataSetType.createDefault(String.class),
                DataSetType.createDefault(String.class)
        );


        // for each word transform it to lowercase and output a key-value pair (word, 1)
        MapOperator<String, Tuple2<String, Integer>> mapOperator = new MapOperator<>(
                new TransformationDescriptor<>(word -> new Tuple2<String, Integer>(word.toLowerCase(), 1),
                        DataUnitType.createBasic(String.class),
                        DataUnitType.createBasic(Tuple2.class)
                ), DataSetType.createDefault(String.class),
                DataSetType.createDefaultUnchecked(Tuple2.class)
        );


        // groupby the key (word) and add up the values (frequency)
        ReduceByOperator<Tuple2<String, Integer>, String> reduceByOperator = new ReduceByOperator<>(
                new TransformationDescriptor<>(pair -> pair.field0,
                        DataUnitType.createBasic(Tuple2.class),
                        DataUnitType.createBasic(String.class)), new ReduceDescriptor<>(
                ((a, b) -> {
                    a.field1 += b.field1;
                    return a;
                }), DataUnitType.createGroupedUnchecked(Tuple2.class),
                DataUnitType.createBasicUnchecked(Tuple2.class)
        ), DataSetType.createDefaultUnchecked(Tuple2.class)
        );


        // write results to a sink
        List<Tuple2> results = new ArrayList<>();
        LocalCallbackSink<Tuple2> sink = LocalCallbackSink.createCollectingSink(results, DataSetType.createDefault(Tuple2.class));

        // Build Rheem plan by connecting operators
        textFileSource.connectTo(0, flatMapOperator, 0);
        flatMapOperator.connectTo(0, mapOperator, 0);
        mapOperator.connectTo(0, reduceByOperator, 0);
        reduceByOperator.connectTo(0, sink, 0);
        RheemPlan rheemPlan = new RheemPlan(sink);

        // Have Rheem execute the plan.
        RheemContext rheemContext = new RheemContext();
        final Job job = rheemContext.createJob(rheemPlan);
        job.getConfiguration().getPlatformProvider().addToWhitelist(SparkPlatform.getInstance());
        job.execute();

        // Verify the plan result.
        Counter<String> counter = new Counter<>();
        List<Tuple2> correctResults = new ArrayList<>();
        final List<String> lines = Files.lines(Paths.get(RheemPlans.FILE_SOME_LINES_TXT)).collect(Collectors.toList());
        lines.stream()
                .flatMap(line -> Arrays.stream(line.split("\\s+")))
                .map(String::toLowerCase)
                .forEach(counter::increment);

        for (Map.Entry<String, Integer> countEntry : counter) {
            correctResults.add(new Tuple2<>(countEntry.getKey(), countEntry.getValue()));
        }
        Assert.assertTrue(results.size() == correctResults.size() && results.containsAll(correctResults) && correctResults.containsAll(results));
    }

    @Test
    public void testOnSparkToJava() throws URISyntaxException, IOException {
        // Assignment mode: ExecutionOperators.

        // Instantiate Rheem and activate the backend.
        RheemContext rheemContext = new RheemContext();
        rheemContext.register(SparkPlatform.getInstance());
        rheemContext.register(JavaPlatform.getInstance());

        TextFileSource textFileSource = new SparkTextFileSource(RheemPlans.FILE_SOME_LINES_TXT.toString());

        // for each line (input) output an iterator of the words
        FlatMapOperator<String, String> flatMapOperator = new SparkFlatMapOperator<>(
                DataSetType.createDefault(String.class),
                DataSetType.createDefault(String.class),
                new FlatMapDescriptor<>(line -> Arrays.asList(line.split(" ")),
                        DataUnitType.createBasic(String.class),
                        DataUnitType.createBasic(String.class)
                ));


        // for each word transform it to lowercase and output a key-value pair (word, 1)
        MapOperator<String, Tuple2<String, Integer>> mapOperator = new SparkMapOperator<>(
                DataSetType.createDefault(String.class),
                DataSetType.createDefaultUnchecked(Tuple2.class),
                new TransformationDescriptor<>(word -> new Tuple2<>(word.toLowerCase(), 1),
                        DataUnitType.createBasic(String.class),
                        DataUnitType.createBasic(Tuple2.class)
                ));


        // groupby the key (word) and add up the values (frequency)
        ReduceByOperator<Tuple2<String, Integer>, String> reduceByOperator = new JavaReduceByOperator<>(
                DataSetType.createDefaultUnchecked(Tuple2.class),
                new TransformationDescriptor<>(pair -> pair.field0,
                        DataUnitType.createBasic(Tuple2.class),
                        DataUnitType.createBasic(String.class)),
                new ReduceDescriptor<>(
                        ((a, b) -> {
                            a.field1 += b.field1;
                            return a;
                        }), DataUnitType.createGroupedUnchecked(Tuple2.class),
                        DataUnitType.createBasicUnchecked(Tuple2.class)
                )
        );


        // write results to a sink
        List<Tuple2> results = new ArrayList<>();
        LocalCallbackSink<Tuple2> sink = new JavaLocalCallbackSink<>(results::add, DataSetType.createDefault(Tuple2.class));

        // Build Rheem plan by connecting operators
        textFileSource.connectTo(0, flatMapOperator, 0);
        flatMapOperator.connectTo(0, mapOperator, 0);
        mapOperator.connectTo(0, reduceByOperator, 0);
        reduceByOperator.connectTo(0, sink, 0);
        RheemPlan rheemPlan = new RheemPlan(sink);

        // Have Rheem execute the plan.
        rheemContext.execute(rheemPlan);

        // Verify the plan result.
        Counter<String> counter = new Counter<>();
        List<Tuple2> correctResults = new ArrayList<>();
        final List<String> lines = Files.lines(Paths.get(RheemPlans.FILE_SOME_LINES_TXT)).collect(Collectors.toList());
        lines.stream()
                .flatMap(line -> Arrays.stream(line.split("\\s+")))
                .map(String::toLowerCase)
                .forEach(counter::increment);

        for (Map.Entry<String, Integer> countEntry : counter) {
            correctResults.add(new Tuple2<>(countEntry.getKey(), countEntry.getValue()));
        }
        Assert.assertTrue(results.size() == correctResults.size() && results.containsAll(correctResults) && correctResults.containsAll(results));
    }

    @Test
    public void testOnJavaToSpark() throws URISyntaxException, IOException {
        // Assignment mode: Constraints.

        TextFileSource textFileSource = new TextFileSource(RheemPlans.FILE_SOME_LINES_TXT.toString());
        textFileSource.addTargetPlatform(JavaPlatform.getInstance());

        // for each line (input) output an iterator of the words
        FlatMapOperator<String, String> flatMapOperator = new FlatMapOperator<>(
                new FlatMapDescriptor<>(line -> Arrays.asList((String[]) line.split(" ")),
                        DataUnitType.createBasic(String.class),
                        DataUnitType.createBasic(String.class)
                ), DataSetType.createDefault(String.class),
                DataSetType.createDefault(String.class)
        );
        flatMapOperator.addTargetPlatform(JavaPlatform.getInstance());


        // for each word transform it to lowercase and output a key-value pair (word, 1)
        MapOperator<String, Tuple2<String, Integer>> mapOperator = new MapOperator<>(
                new TransformationDescriptor<>(word -> new Tuple2<String, Integer>(word.toLowerCase(), 1),
                        DataUnitType.createBasic(String.class),
                        DataUnitType.createBasic(Tuple2.class)
                ), DataSetType.createDefault(String.class),
                DataSetType.createDefaultUnchecked(Tuple2.class)
        );
        mapOperator.addTargetPlatform(SparkPlatform.getInstance());


        // groupby the key (word) and add up the values (frequency)
        ReduceByOperator<Tuple2<String, Integer>, String> reduceByOperator = new ReduceByOperator<>(
                new TransformationDescriptor<>(pair -> pair.field0,
                        DataUnitType.createBasic(Tuple2.class),
                        DataUnitType.createBasic(String.class)), new ReduceDescriptor<>(
                ((a, b) -> {
                    a.field1 += b.field1;
                    return a;
                }), DataUnitType.createGroupedUnchecked(Tuple2.class),
                DataUnitType.createBasicUnchecked(Tuple2.class)
        ), DataSetType.createDefaultUnchecked(Tuple2.class)
        );
        reduceByOperator.addTargetPlatform(SparkPlatform.getInstance());


        // write results to a sink
        List<Tuple2> results = new ArrayList<>();
        LocalCallbackSink<Tuple2> sink = LocalCallbackSink.createCollectingSink(results, DataSetType.createDefault(Tuple2.class));
        sink.addTargetPlatform(SparkPlatform.getInstance());

        // Build Rheem plan by connecting operators
        textFileSource.connectTo(0, flatMapOperator, 0);
        flatMapOperator.connectTo(0, mapOperator, 0);
        mapOperator.connectTo(0, reduceByOperator, 0);
        reduceByOperator.connectTo(0, sink, 0);
        RheemPlan rheemPlan = new RheemPlan(sink);

        // Have Rheem execute the plan.
        RheemContext rheemContext = new RheemContext();
        rheemContext.register(JavaPlatform.getInstance());
        rheemContext.register(SparkPlatform.getInstance());
        rheemContext.execute(rheemPlan);

        // Verify the plan result.
        Counter<String> counter = new Counter<>();
        List<Tuple2> correctResults = new ArrayList<>();
        final List<String> lines = Files.lines(Paths.get(RheemPlans.FILE_SOME_LINES_TXT)).collect(Collectors.toList());
        lines.stream()
                .flatMap(line -> Arrays.stream(line.split("\\s+")))
                .map(String::toLowerCase)
                .forEach(counter::increment);

        for (Map.Entry<String, Integer> countEntry : counter) {
            correctResults.add(new Tuple2<>(countEntry.getKey(), countEntry.getValue()));
        }
        Assert.assertTrue(results.size() == correctResults.size() && results.containsAll(correctResults) && correctResults.containsAll(results));
    }

    @Test
    public void testOnJavaAndSpark() throws URISyntaxException, IOException {
        // Assignment mode: none.

        TextFileSource textFileSource = new TextFileSource(RheemPlans.FILE_SOME_LINES_TXT.toString());
        textFileSource.addTargetPlatform(JavaPlatform.getInstance());
        textFileSource.addTargetPlatform(SparkPlatform.getInstance());

        // for each line (input) output an iterator of the words
        FlatMapOperator<String, String> flatMapOperator = new FlatMapOperator<>(
                new FlatMapDescriptor<>(line -> Arrays.asList((String[]) line.split(" ")),
                        DataUnitType.createBasic(String.class),
                        DataUnitType.createBasic(String.class)
                ), DataSetType.createDefault(String.class),
                DataSetType.createDefault(String.class)
        );


        // for each word transform it to lowercase and output a key-value pair (word, 1)
        MapOperator<String, Tuple2<String, Integer>> mapOperator = new MapOperator<>(
                new TransformationDescriptor<>(word -> new Tuple2<String, Integer>(word.toLowerCase(), 1),
                        DataUnitType.createBasic(String.class),
                        DataUnitType.createBasic(Tuple2.class)
                ), DataSetType.createDefault(String.class),
                DataSetType.createDefaultUnchecked(Tuple2.class)
        );


        // groupby the key (word) and add up the values (frequency)
        ReduceByOperator<Tuple2<String, Integer>, String> reduceByOperator = new ReduceByOperator<>(
                new TransformationDescriptor<>(pair -> pair.field0,
                        DataUnitType.createBasic(Tuple2.class),
                        DataUnitType.createBasic(String.class)), new ReduceDescriptor<>(
                ((a, b) -> {
                    a.field1 += b.field1;
                    return a;
                }), DataUnitType.createGroupedUnchecked(Tuple2.class),
                DataUnitType.createBasicUnchecked(Tuple2.class)
        ), DataSetType.createDefaultUnchecked(Tuple2.class)
        );


        // write results to a sink
        List<Tuple2> results = new ArrayList<>();
        LocalCallbackSink<Tuple2> sink = LocalCallbackSink.createCollectingSink(results, DataSetType.createDefault(Tuple2.class));

        // Build Rheem plan by connecting operators
        textFileSource.connectTo(0, flatMapOperator, 0);
        flatMapOperator.connectTo(0, mapOperator, 0);
        mapOperator.connectTo(0, reduceByOperator, 0);
        reduceByOperator.connectTo(0, sink, 0);
        RheemPlan rheemPlan = new RheemPlan(sink);

        // Have Rheem execute the plan.
        RheemContext rheemContext = new RheemContext();
        rheemContext.register(JavaPlatform.getInstance());
        rheemContext.register(SparkPlatform.getInstance());
        rheemContext.execute(rheemPlan);

        // Verify the plan result.
        Counter<String> counter = new Counter<>();
        List<Tuple2> correctResults = new ArrayList<>();
        final List<String> lines = Files.lines(Paths.get(RheemPlans.FILE_SOME_LINES_TXT)).collect(Collectors.toList());
        lines.stream()
                .flatMap(line -> Arrays.stream(line.split("\\s+")))
                .map(String::toLowerCase)
                .forEach(counter::increment);

        for (Map.Entry<String, Integer> countEntry : counter) {
            correctResults.add(new Tuple2<>(countEntry.getKey(), countEntry.getValue()));
        }
        Assert.assertTrue(results.size() == correctResults.size() && results.containsAll(correctResults) && correctResults.containsAll(results));
    }


}
