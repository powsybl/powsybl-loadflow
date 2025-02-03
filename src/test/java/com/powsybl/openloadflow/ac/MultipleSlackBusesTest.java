/**
 * Copyright (c) 2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac;

import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Generator;
import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.Load;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.TwoWindingsTransformer;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowResult;
import com.powsybl.math.matrix.DenseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.OpenLoadFlowProvider;
import com.powsybl.openloadflow.ac.solver.NewtonRaphsonStoppingCriteriaType;
import com.powsybl.openloadflow.network.EurostagFactory;
import com.powsybl.openloadflow.network.SlackBusSelectionMode;
import com.powsybl.openloadflow.util.LoadFlowAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.powsybl.openloadflow.util.LoadFlowAssert.assertActivePowerEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
class MultipleSlackBusesTest {

    private Network network;

    private LoadFlow.Runner loadFlowRunner;

    private LoadFlowParameters parameters;

    private OpenLoadFlowParameters parametersExt;
    Bus genBus;
    Load load;
    Generator generator;
    private Line line1;
    private Line line2;
    private TwoWindingsTransformer loadT2wt;

    @BeforeEach
    void setUp() {
        network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());
        loadFlowRunner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
        parameters = new LoadFlowParameters()
                .setUseReactiveLimits(false)
                .setDistributedSlack(false);
        parametersExt = OpenLoadFlowParameters.create(parameters)
                .setMaxSlackBusCount(2);
        genBus = network.getBusBreakerView().getBus("NGEN");
        load = network.getLoad("LOAD");
        generator = network.getGenerator("GEN");
        line1 = network.getLine("NHV1_NHV2_1");
        line2 = network.getLine("NHV1_NHV2_2");
        loadT2wt = network.getTwoWindingsTransformer("NHV2_NLOAD");
    }

    static Stream<Arguments> allModelAndStoppingCriteriaTypes() {
        Stream<Arguments> acStream = Arrays.stream(NewtonRaphsonStoppingCriteriaType.values()).map(a -> Arguments.of(true, a));
        Stream<Arguments> dcStream = Stream.of(Arguments.of(false, NewtonRaphsonStoppingCriteriaType.UNIFORM_CRITERIA));
        return Stream.concat(acStream, dcStream);
    }

    static Stream<Arguments> allModelAndSwitchingTypes() {
        return Stream.of(
                Arguments.of(true, true),
                Arguments.of(true, false),
                Arguments.of(false, true),
                Arguments.of(false, false)
        );
    }

    static Stream<Arguments> allModelTypesAndNbSlackbuses() {
        return Stream.concat(IntStream.range(1, 5).mapToObj(i -> Arguments.of(true, i)),
                IntStream.range(1, 5).mapToObj(i -> Arguments.of(false, i)));
    }

    static Stream<Arguments> allModelTypes() {
        return Stream.of(Arguments.of(true), Arguments.of(false));
    }

    @ParameterizedTest(name = "ac : {0}, NR stopping crit : {1}")
    @MethodSource("allModelAndStoppingCriteriaTypes")
    void multiSlackTest(boolean ac, NewtonRaphsonStoppingCriteriaType stoppingCriteria) {
        parameters.setDc(!ac);
        parametersExt.setNewtonRaphsonStoppingCriteriaType(stoppingCriteria);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        LoadFlowResult.ComponentResult componentResult = result.getComponentResults().get(0);
        int expectedIterationCount = ac ? 3 : 0;
        assertEquals(expectedIterationCount, componentResult.getIterationCount());

        List<LoadFlowResult.SlackBusResult> slackBusResults = componentResult.getSlackBusResults();
        double expectedSlackBusMismatch = ac ? -0.7164 : -3.5;
        assertSlackBusResults(slackBusResults, expectedSlackBusMismatch, 2);

        if (ac) {
            assertActivePowerValues(302.807, 302.807, 600.868);
        } else {
            assertActivePowerValues(301.75, 301.75, 600);
        }

        parameters.setDistributedSlack(true);
        result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        componentResult = result.getComponentResults().get(0);
        slackBusResults = componentResult.getSlackBusResults();
        expectedIterationCount = ac ? 4 : 0;
        assertEquals(expectedIterationCount, componentResult.getIterationCount());
        expectedSlackBusMismatch = ac ? -0.005 : 0;
        assertSlackBusResults(slackBusResults, expectedSlackBusMismatch, 2);
    }

    @ParameterizedTest(name = "ac : {0}")
    @MethodSource("allModelTypes")
    void nonImpedantBranchTest(boolean ac) {
        parameters.setDc(!ac);
        network.getLine("NHV1_NHV2_1")
                .setR(0)
                .setX(0);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        LoadFlowResult.ComponentResult componentResult = result.getComponentResults().get(0);
        int expectedIterationCount = ac ? 3 : 0;
        assertEquals(expectedIterationCount, componentResult.getIterationCount());
        List<LoadFlowResult.SlackBusResult> slackBusResults = componentResult.getSlackBusResults();
        double expectedSlackBusMismatch = ac ? -2.755 : -3.5;
        assertSlackBusResults(slackBusResults, expectedSlackBusMismatch, 2);

        if (ac) {
            assertActivePowerValues(603.567, 0.0, 600.812);
        } else {
            assertActivePowerValues(603.5, 0.0, 600);
        }

        parameters.setDistributedSlack(true);
        result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        componentResult = result.getComponentResults().get(0);
        slackBusResults = componentResult.getSlackBusResults();
        expectedIterationCount = ac ? 4 : 0;
        assertEquals(expectedIterationCount, componentResult.getIterationCount());
        expectedSlackBusMismatch = ac ? -0.005 : 0;
        assertSlackBusResults(slackBusResults, expectedSlackBusMismatch, 2);
    }

    @ParameterizedTest(name = "ac : {0}, nbSlackbuses : {1}")
    @MethodSource("allModelTypesAndNbSlackbuses")
    void differentNbSlackbusesTest(boolean ac, int nbSlackbuses) {
        network = IeeeCdfNetworkFactory.create14();
        parameters.setDc(!ac).setReadSlackBus(false).setDistributedSlack(true);
        parametersExt.setMaxSlackBusCount(nbSlackbuses) // Testing from 1 to 4 slack buses and expecting same global mismatch
                .setSlackBusPMaxMismatch(0.001)
                .setPlausibleActivePowerLimit(10000); // IEEE14 Network has generators with maxP = 9999 we want to keep for distribution
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        double distributedActivePower = result.getComponentResults().get(0).getDistributedActivePower();
        assertEquals(ac ? -0.006 : -13.4, distributedActivePower, 0.001);
        assertActivePowerEquals(ac ? -39.996 : -33.300, network.getGenerator("B2-G").getTerminal());
        assertActivePowerEquals(ac ? 156.886 : 153.453, network.getLine("L1-2-1").getTerminal1());
        assertActivePowerEquals(ac ? 56.131 : 54.768, network.getLine("L2-4-1").getTerminal1());
    }

    @ParameterizedTest(name = "ac : {0}, switchSlacks : {1}")
    @MethodSource("allModelAndSwitchingTypes")
    void loadOnSlackBusTest(boolean ac, boolean switchSlacks) {
        parameters.setDc(!ac);
        parametersExt.setSlackBusSelectionMode(SlackBusSelectionMode.NAME);
        if (switchSlacks) { //switching slack buses order (expecting the same result)
            parametersExt.setSlackBusesIds(List.of("VLHV2", "VLLOAD"));
        } else {
            parametersExt.setSlackBusesIds(List.of("VLLOAD", "VLHV2"));
        }

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        LoadFlowResult.ComponentResult componentResult = result.getComponentResults().get(0);
        int expectedIterationCount = ac ? 3 : 0;
        assertEquals(expectedIterationCount, componentResult.getIterationCount());

        List<LoadFlowResult.SlackBusResult> slackBusResults = componentResult.getSlackBusResults();
        assertEquals(switchSlacks ? List.of("VLHV2_0", "VLLOAD_0") : List.of("VLLOAD_0", "VLHV2_0"),
                slackBusResults.stream().map(LoadFlowResult.SlackBusResult::getId).toList());
        double expectedSlackBusMismatch = ac ? -0.711 : -3.5;
        assertSlackBusResults(slackBusResults, expectedSlackBusMismatch, 2);

        if (ac) {
            assertActivePowerValues(303.165, 303.165, 601.58);
        } else {
            assertActivePowerValues(303.5, 303.5, 603.5);
        }
    }

    void assertActivePowerValues(double line1P1, double line2P1, double loadT2wtP1) {
        assertActivePowerEquals(line1P1, line1.getTerminal1());
        assertActivePowerEquals(line2P1, line2.getTerminal1());
        assertActivePowerEquals(loadT2wtP1, loadT2wt.getTerminal1());
        assertActivePowerEquals(600, load.getTerminal());
        assertActivePowerEquals(-607, generator.getTerminal());
    }

    void assertSlackBusResults(List<LoadFlowResult.SlackBusResult> slackBusResults, double expectedMismatch, int slackBusCount) {
        assertEquals(slackBusCount, slackBusResults.size());
        for (LoadFlowResult.SlackBusResult slackBusResult : slackBusResults) {
            assertEquals(expectedMismatch, slackBusResult.getActivePowerMismatch(), LoadFlowAssert.DELTA_POWER);
        }
    }
}
