/**
 * Copyright (c) 2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.powsybl.openloadflow.util.LoadFlowAssert.assertActivePowerEquals;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
class MultipleSlackBusesTest {

    private Network network;

    private LoadFlow.Runner loadFlowRunner;

    private LoadFlowParameters parameters;

    private OpenLoadFlowParameters parametersExt;

    @BeforeEach
    void setUp() {
        network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());
        loadFlowRunner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
        parameters = new LoadFlowParameters()
                .setUseReactiveLimits(false)
                .setDistributedSlack(false);
        parametersExt = OpenLoadFlowParameters.create(parameters)
                .setMaxSlackBusCount(2);
    }

    static Stream<Arguments> allStoppingCriteriaTypes() {
        return Arrays.stream(NewtonRaphsonStoppingCriteriaType.values()).map(Arguments::of);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allStoppingCriteriaTypes")
    void multiSlackTest(NewtonRaphsonStoppingCriteriaType stoppingCriteria) {
        parametersExt.setNewtonRaphsonStoppingCriteriaType(stoppingCriteria);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        LoadFlowResult.ComponentResult componentResult = result.getComponentResults().get(0);
        assertEquals(3, componentResult.getIterationCount());
        List<LoadFlowResult.SlackBusResult> slackBusResults = componentResult.getSlackBusResults();
        assertEquals(2, slackBusResults.size());
        assertEquals(-0.716, slackBusResults.get(0).getActivePowerMismatch(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.716, slackBusResults.get(1).getActivePowerMismatch(), LoadFlowAssert.DELTA_POWER);

        parameters.setDistributedSlack(true);
        result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        componentResult = result.getComponentResults().get(0);
        slackBusResults = componentResult.getSlackBusResults();
        assertEquals(4, componentResult.getIterationCount());
        assertEquals(2, slackBusResults.size());
        assertEquals(-0.005, slackBusResults.get(0).getActivePowerMismatch(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.005, slackBusResults.get(1).getActivePowerMismatch(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void nonImpedantBranchTest() {
        network.getLine("NHV1_NHV2_1")
                .setR(0)
                .setX(0);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        LoadFlowResult.ComponentResult componentResult = result.getComponentResults().get(0);
        List<LoadFlowResult.SlackBusResult> slackBusResults = componentResult.getSlackBusResults();
        assertEquals(3, componentResult.getIterationCount());
        assertEquals(2, slackBusResults.size());
        assertEquals(-2.755, slackBusResults.get(0).getActivePowerMismatch(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-2.755, slackBusResults.get(1).getActivePowerMismatch(), LoadFlowAssert.DELTA_POWER);

        parameters.setDistributedSlack(true);
        result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        componentResult = result.getComponentResults().get(0);
        slackBusResults = componentResult.getSlackBusResults();
        assertEquals(4, componentResult.getIterationCount());
        assertEquals(2, slackBusResults.size());
        assertEquals(-0.005, slackBusResults.get(0).getActivePowerMismatch(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.005, slackBusResults.get(1).getActivePowerMismatch(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testSlackBusWithInjection() {
        parametersExt
                .setSlackBusSelectionMode(SlackBusSelectionMode.NAME)
                .setSlackBusesIds(List.of("VLHV2", "VLLOAD"))
                .setNewtonRaphsonConvEpsPerEq(1e-6);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        LoadFlowResult.ComponentResult componentResult = result.getComponentResults().get(0);
        List<LoadFlowResult.SlackBusResult> slackBusResults = componentResult.getSlackBusResults();

        TwoWindingsTransformer t2wtLoad = network.getTwoWindingsTransformer("NHV2_NLOAD");
        Load load = network.getLoad("LOAD");

        assertActivePowerEquals(-600.710, t2wtLoad.getTerminal2());
        assertActivePowerEquals(600.0, load.getTerminal());

        assertEquals(2, slackBusResults.size());
        assertEquals(-0.710, slackBusResults.get(0).getActivePowerMismatch(), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.710, slackBusResults.get(1).getActivePowerMismatch(), LoadFlowAssert.DELTA_POWER);
    }
}
