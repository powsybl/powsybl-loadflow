/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sensi;

import com.powsybl.commons.PowsyblException;
import com.powsybl.contingency.BranchContingency;
import com.powsybl.contingency.Contingency;
import com.powsybl.contingency.ContingencyContext;
import com.powsybl.contingency.DanglingLineContingency;
import com.powsybl.iidm.network.*;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.iidm.network.test.PhaseShifterTestCaseFactory;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.dc.equations.DcEquationType;
import com.powsybl.openloadflow.dc.equations.DcVariableType;
import com.powsybl.openloadflow.dc.fastdc.AbstractComputedElement;
import com.powsybl.openloadflow.dc.fastdc.ComputedContingencyElement;
import com.powsybl.openloadflow.equations.Equation;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.EquationSystemIndex;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.network.impl.PropagatedContingency;
import com.powsybl.openloadflow.network.impl.PropagatedContingencyCreationParameters;
import com.powsybl.openloadflow.util.LoadFlowAssert;
import com.powsybl.sensitivity.*;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
class DcSensitivityAnalysisTest extends AbstractSensitivityAnalysisTest {

    @Test
    void testEsgTuto() {
        Network network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());
        runAcLf(network);

        SensitivityAnalysisParameters sensiParameters = createParameters(true, "VLLOAD_0");

        List<SensitivityFactor> factors = createFactorMatrix(network.getGeneratorStream().collect(Collectors.toList()),
                                                             network.getLineStream().collect(Collectors.toList()));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, Collections.emptyList(), Collections.emptyList(), sensiParameters);

        assertEquals(2, result.getValues().size());
        assertEquals(0.5d, result.getBranchFlow1SensitivityValue("GEN", "NHV1_NHV2_1", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.5d, result.getBranchFlow1SensitivityValue("GEN", "NHV1_NHV2_2", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void test4buses() {
        Network network = FourBusNetworkFactory.create();
        runDcLf(network);

        Map<String, Double> functionReferenceByLine = new HashMap<>();
        for (Line line : network.getLines()) {
            functionReferenceByLine.put(line.getId(), line.getTerminal1().getP());
        }

        SensitivityAnalysisParameters sensiParameters = createParameters(true, "b3_vl_0");

        List<SensitivityFactor> factors = createFactorMatrix(network.getGeneratorStream().collect(Collectors.toList()),
                                                             network.getBranchStream().collect(Collectors.toList()), TwoSides.ONE);

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, Collections.emptyList(), Collections.emptyList(), sensiParameters);

        assertEquals(15, result.getValues().size());

        //Check sensitivity values for side one
        assertEquals(0.25d, result.getBranchFlow1SensitivityValue("g1", "l14", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.25d, result.getBranchFlow1SensitivityValue("g1", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.25d, result.getBranchFlow1SensitivityValue("g1", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.25d, result.getBranchFlow1SensitivityValue("g1", "l34", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.5d, result.getBranchFlow1SensitivityValue("g1", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);

        assertEquals(0.125d, result.getBranchFlow1SensitivityValue("g2", "l14", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.375d, result.getBranchFlow1SensitivityValue("g2", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.625d, result.getBranchFlow1SensitivityValue("g2", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.125d, result.getBranchFlow1SensitivityValue("g2", "l34", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.25d, result.getBranchFlow1SensitivityValue("g2", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);

        assertEquals(-0.375d, result.getBranchFlow1SensitivityValue("g4", "l14", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.125d, result.getBranchFlow1SensitivityValue("g4", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.125d, result.getBranchFlow1SensitivityValue("g4", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.625d, result.getBranchFlow1SensitivityValue("g4", "l34", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.25d, result.getBranchFlow1SensitivityValue("g4", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);

        for (Line line : network.getLines()) {
            assertEquals(functionReferenceByLine.get(line.getId()), result.getBranchFlow1FunctionReferenceValue(line.getId()), LoadFlowAssert.DELTA_POWER);
        }
    }

    @Test
    void test4busesSide2() {
        Network network = FourBusNetworkFactory.create();
        runDcLf(network);

        Map<String, Double> functionReferenceByLine = new HashMap<>();
        for (Line line : network.getLines()) {
            functionReferenceByLine.put(line.getId(), line.getTerminal2().getP());
        }

        SensitivityAnalysisParameters sensiParameters = createParameters(true, "b3_vl_0");

        List<SensitivityFactor> factors = createFactorMatrix(network.getGeneratorStream().collect(Collectors.toList()),
                network.getBranchStream().collect(Collectors.toList()), TwoSides.TWO);

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, Collections.emptyList(), Collections.emptyList(), sensiParameters);

        assertEquals(15, result.getValues().size());

        //Check sensitivity values for side two
        assertEquals(-0.25d, result.getBranchFlow2SensitivityValue("g1", "l14", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.25d, result.getBranchFlow2SensitivityValue("g1", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.25d, result.getBranchFlow2SensitivityValue("g1", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.25d, result.getBranchFlow2SensitivityValue("g1", "l34", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.5d, result.getBranchFlow2SensitivityValue("g1", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);

        assertEquals(-0.125d, result.getBranchFlow2SensitivityValue("g2", "l14", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.375d, result.getBranchFlow2SensitivityValue("g2", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.625d, result.getBranchFlow2SensitivityValue("g2", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.125d, result.getBranchFlow2SensitivityValue("g2", "l34", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.25d, result.getBranchFlow2SensitivityValue("g2", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);

        assertEquals(0.375d, result.getBranchFlow2SensitivityValue("g4", "l14", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.125d, result.getBranchFlow2SensitivityValue("g4", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.125d, result.getBranchFlow2SensitivityValue("g4", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.625d, result.getBranchFlow2SensitivityValue("g4", "l34", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.25d, result.getBranchFlow2SensitivityValue("g4", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);

        for (Line line : network.getLines()) {
            assertEquals(functionReferenceByLine.get(line.getId()), result.getBranchFlow2FunctionReferenceValue(line.getId()), LoadFlowAssert.DELTA_POWER);
        }
    }

    @Test
    void testGeneratorInjection4busesDistributed() {
        // The factors are generators injections
        Network network = FourBusNetworkFactory.create();
        runDcLf(network);

        SensitivityAnalysisParameters sensiParameters = createParameters(true, "b3_vl_0", true);
        sensiParameters.getLoadFlowParameters().setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX);

        List<SensitivityFactor> factors = createFactorMatrix(network.getGeneratorStream().collect(Collectors.toList()),
                                                             network.getBranchStream().collect(Collectors.toList()));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, Collections.emptyList(), Collections.emptyList(), sensiParameters);

        assertEquals(0.175, result.getBranchFlow1SensitivityValue("g1", "l14", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.275, result.getBranchFlow1SensitivityValue("g1", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.125, result.getBranchFlow1SensitivityValue("g1", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.0245, result.getBranchFlow1SensitivityValue("g1", "l34", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.150, result.getBranchFlow1SensitivityValue("g1", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);

        assertEquals(0.050, result.getBranchFlow1SensitivityValue("g2", "l14", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.350, result.getBranchFlow1SensitivityValue("g2", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.250, result.getBranchFlow1SensitivityValue("g2", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.150, result.getBranchFlow1SensitivityValue("g2", "l34", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.100, result.getBranchFlow1SensitivityValue("g2", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);

        assertEquals(-0.450, result.getBranchFlow1SensitivityValue("g4", "l14", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.150, result.getBranchFlow1SensitivityValue("g4", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.250, result.getBranchFlow1SensitivityValue("g4", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.350, result.getBranchFlow1SensitivityValue("g4", "l34", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.100, result.getBranchFlow1SensitivityValue("g4", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testGeneratorInjection4busesDistributed2() {
        // The factors are generators injections
        Network network = FourBusNetworkFactory.create();
        runDcLf(network);

        SensitivityAnalysisParameters sensiParameters = createParameters(true, "b3_vl_0", true);
        sensiParameters.getLoadFlowParameters().setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P);

        List<SensitivityFactor> factors = createFactorMatrix(network.getGeneratorStream().collect(Collectors.toList()),
                                                             network.getBranchStream().collect(Collectors.toList()));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, Collections.emptyList(), Collections.emptyList(), sensiParameters);

        assertEquals(15, result.getValues().size());
        assertEquals(0.175d, result.getBranchFlow1SensitivityValue("g1", "l14", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.275d, result.getBranchFlow1SensitivityValue("g1", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.125d, result.getBranchFlow1SensitivityValue("g1", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.025d, result.getBranchFlow1SensitivityValue("g1", "l34", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.15d, result.getBranchFlow1SensitivityValue("g1", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);

        assertEquals(0.05d, result.getBranchFlow1SensitivityValue("g2", "l14", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.35d, result.getBranchFlow1SensitivityValue("g2", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.25d, result.getBranchFlow1SensitivityValue("g2", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.15d, result.getBranchFlow1SensitivityValue("g2", "l34", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.1d, result.getBranchFlow1SensitivityValue("g2", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);

        assertEquals(-0.450d, result.getBranchFlow1SensitivityValue("g4", "l14", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.15d, result.getBranchFlow1SensitivityValue("g4", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.25d, result.getBranchFlow1SensitivityValue("g4", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.35d, result.getBranchFlow1SensitivityValue("g4", "l34", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.1d, result.getBranchFlow1SensitivityValue("g4", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testLoadInjection4busesDistributed() {
        // test injection increase on loads
        Network network = FourBusNetworkFactory.create();
        runDcLf(network);

        SensitivityAnalysisParameters sensiParameters = createParameters(true, "b1_vl_0", true);
        sensiParameters.getLoadFlowParameters().setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX);

        List<SensitivityFactor> factors = createFactorMatrix(network.getLoadStream().collect(Collectors.toList()),
                                                             network.getBranchStream().collect(Collectors.toList()));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, Collections.emptyList(), Collections.emptyList(), sensiParameters);

        assertEquals(10, result.getValues().size());
        assertEquals(0.05d, result.getBranchFlow1SensitivityValue("d2", "l14", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.35d, result.getBranchFlow1SensitivityValue("d2", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.25d, result.getBranchFlow1SensitivityValue("d2", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.15d, result.getBranchFlow1SensitivityValue("d2", "l34", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.1d, result.getBranchFlow1SensitivityValue("d2", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);

        assertEquals(-0.075d, result.getBranchFlow1SensitivityValue("d3", "l14", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.025d, result.getBranchFlow1SensitivityValue("d3", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.375d, result.getBranchFlow1SensitivityValue("d3", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.275d, result.getBranchFlow1SensitivityValue("d3", "l34", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.35d, result.getBranchFlow1SensitivityValue("d3", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testSeveralGeneratorsConnectedToTheSameBus() {
        // test injection increase on loads
        Network network = FourBusNetworkFactory.createWithTwoGeneratorsAtBus2();
        runDcLf(network);

        SensitivityAnalysisParameters sensiParameters = createParameters(true, "b1_vl_0", true);
        sensiParameters.getLoadFlowParameters().setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX);

        List<SensitivityFactor> factors = createFactorMatrix(network.getLoadStream().collect(Collectors.toList()),
                                                             network.getBranchStream().collect(Collectors.toList()));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, Collections.emptyList(), Collections.emptyList(), sensiParameters);

        assertEquals(10, result.getValues().size());
        assertEquals(0.045d, result.getBranchFlow1SensitivityValue("d2", "l14", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.318d, result.getBranchFlow1SensitivityValue("d2", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.227d, result.getBranchFlow1SensitivityValue("d2", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.136d, result.getBranchFlow1SensitivityValue("d2", "l34", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.09d, result.getBranchFlow1SensitivityValue("d2", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testGeneratorInjection4busesDistributedOnLoad() {
        // test injection increase on loads
        Network network = FourBusNetworkFactory.create();
        runDcLf(network);

        SensitivityAnalysisParameters sensiParameters = createParameters(true, "b3_vl_0", true);
        sensiParameters.getLoadFlowParameters().setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD);

        List<SensitivityFactor> factors = createFactorMatrix(network.getGeneratorStream().collect(Collectors.toList()),
                                                             network.getBranchStream().collect(Collectors.toList()));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, Collections.emptyList(), Collections.emptyList(), sensiParameters);

        assertEquals(15, result.getValues().size());
        assertEquals(0.225d, result.getBranchFlow1SensitivityValue("g1", "l14", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.325d, result.getBranchFlow1SensitivityValue("g1", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.125d, result.getBranchFlow1SensitivityValue("g1", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.225d, result.getBranchFlow1SensitivityValue("g1", "l34", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.45d, result.getBranchFlow1SensitivityValue("g1", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);

        assertEquals(0.1d, result.getBranchFlow1SensitivityValue("g2", "l14", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.3d, result.getBranchFlow1SensitivityValue("g2", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.5d, result.getBranchFlow1SensitivityValue("g2", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.1d, result.getBranchFlow1SensitivityValue("g2", "l34", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.2d, result.getBranchFlow1SensitivityValue("g2", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);

        assertEquals(-0.4d, result.getBranchFlow1SensitivityValue("g4", "l14", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.2d, result.getBranchFlow1SensitivityValue("g4", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("g4", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.6d, result.getBranchFlow1SensitivityValue("g4", "l34", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.2d, result.getBranchFlow1SensitivityValue("g4", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testLoadInjection4busesDistributedOnLoad() {
        // test injection increase on loads
        Network network = FourBusNetworkFactory.create();
        runDcLf(network);
        SensitivityAnalysisParameters sensiParameters = createParameters(true, "b1_vl_0", true);
        sensiParameters.getLoadFlowParameters().setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD);
        List<SensitivityFactor> factors = createFactorMatrix(network.getLoadStream().collect(Collectors.toList()),
                network.getBranchStream().collect(Collectors.toList()));
        SensitivityAnalysisResult result = sensiRunner.run(network, factors, Collections.emptyList(), Collections.emptyList(), sensiParameters);

        assertEquals(10, result.getValues().size());
        assertEquals(0.1d, result.getBranchFlow1SensitivityValue("d2", "l14", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.3d, result.getBranchFlow1SensitivityValue("d2", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.5d, result.getBranchFlow1SensitivityValue("d2", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.1d, result.getBranchFlow1SensitivityValue("d2", "l34", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.2d, result.getBranchFlow1SensitivityValue("d2", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);

        assertEquals(-0.025d, result.getBranchFlow1SensitivityValue("d3", "l14", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.075d, result.getBranchFlow1SensitivityValue("d3", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.125d, result.getBranchFlow1SensitivityValue("d3", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.025d, result.getBranchFlow1SensitivityValue("d3", "l34", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.05d, result.getBranchFlow1SensitivityValue("d3", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void test4busesDistributedPartialFactors() {
        // test that the sensitivity computation does not make assumption about the presence of all factors
        Network network = FourBusNetworkFactory.create();
        runDcLf(network);

        SensitivityAnalysisParameters sensiParameters = createParameters(true, "b3_vl_0", true);
        sensiParameters.getLoadFlowParameters().setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX);

        List<SensitivityFactor> factors = createFactorMatrix(network.getGeneratorStream().filter(gen -> gen.getId().equals("g1")).collect(Collectors.toList()),
                                                             network.getBranchStream().collect(Collectors.toList()));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, Collections.emptyList(), Collections.emptyList(), sensiParameters);

        assertEquals(5, result.getValues().size());
        assertEquals(0.175d, result.getBranchFlow1SensitivityValue("g1", "l14", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.275d, result.getBranchFlow1SensitivityValue("g1", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.125d, result.getBranchFlow1SensitivityValue("g1", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.025d, result.getBranchFlow1SensitivityValue("g1", "l34", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.15d, result.getBranchFlow1SensitivityValue("g1", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testLoadInjectionWithoutGenerator() {
        // test injection increase on loads
        Network network = FourBusNetworkFactory.createBaseNetwork();
        runDcLf(network);

        SensitivityAnalysisParameters sensiParameters = createParameters(true, "b1_vl_0", true);
        sensiParameters.getLoadFlowParameters().setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX);

        List<SensitivityFactor> factors = createFactorMatrix(network.getLoadStream().collect(Collectors.toList()),
                                                             network.getBranchStream().collect(Collectors.toList()));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, Collections.emptyList(), Collections.emptyList(), sensiParameters);

        assertEquals(10, result.getValues().size());
        assertEquals(0.083d, result.getBranchFlow1SensitivityValue("d2", "l14", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.583d, result.getBranchFlow1SensitivityValue("d2", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.417d, result.getBranchFlow1SensitivityValue("d2", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.25d, result.getBranchFlow1SensitivityValue("d2", "l34", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.167d, result.getBranchFlow1SensitivityValue("d2", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);

        assertEquals(-0.0416d, result.getBranchFlow1SensitivityValue("d3", "l14", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.2083d, result.getBranchFlow1SensitivityValue("d3", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.2083d, result.getBranchFlow1SensitivityValue("d3", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.375d, result.getBranchFlow1SensitivityValue("d3", "l34", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.416d, result.getBranchFlow1SensitivityValue("d3", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testLoadInjectionOnSlackBusDistributed() {
        Network network = FourBusNetworkFactory.create();
        runDcLf(network);

        SensitivityAnalysisParameters sensiParameters = createParameters(true, "b1_vl_0", true);
        sensiParameters.getLoadFlowParameters().setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX);

        List<SensitivityFactor> factors = createFactorMatrix(network.getGeneratorStream().collect(Collectors.toList()),
                                                             network.getBranchStream().collect(Collectors.toList()));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, Collections.emptyList(), Collections.emptyList(), sensiParameters);

        assertEquals(15, result.getValues().size());
        assertEquals(0.175d, result.getBranchFlow1SensitivityValue("g1", "l14", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.275d, result.getBranchFlow1SensitivityValue("g1", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.125d, result.getBranchFlow1SensitivityValue("g1", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.025d, result.getBranchFlow1SensitivityValue("g1", "l34", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.15d, result.getBranchFlow1SensitivityValue("g1", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);

        assertEquals(0.05d, result.getBranchFlow1SensitivityValue("g2", "l14", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.35d, result.getBranchFlow1SensitivityValue("g2", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.25d, result.getBranchFlow1SensitivityValue("g2", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.15d, result.getBranchFlow1SensitivityValue("g2", "l34", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.1d, result.getBranchFlow1SensitivityValue("g2", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);

        assertEquals(-0.450d, result.getBranchFlow1SensitivityValue("g4", "l14", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.15d, result.getBranchFlow1SensitivityValue("g4", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.25d, result.getBranchFlow1SensitivityValue("g4", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.35d, result.getBranchFlow1SensitivityValue("g4", "l34", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.1d, result.getBranchFlow1SensitivityValue("g4", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testGLSK() {
        Network network = FourBusNetworkFactory.create();
        runDcLf(network);

        SensitivityAnalysisParameters sensiParameters = createParameters(true, "b1_vl_0", false);

        List<WeightedSensitivityVariable> variables = List.of(new WeightedSensitivityVariable("d2", 30f),
                                                              new WeightedSensitivityVariable("g2", 10f),
                                                              new WeightedSensitivityVariable("d3", 50f),
                                                              new WeightedSensitivityVariable("g1", 10f));
        List<SensitivityVariableSet> variableSets = Collections.singletonList(new SensitivityVariableSet("glsk", variables));

        List<SensitivityFactor> factors = network.getBranchStream().map(branch -> createBranchFlowPerLinearGlsk(branch.getId(), "glsk")).collect(Collectors.toList());

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, Collections.emptyList(), variableSets, sensiParameters);

        assertEquals(5, result.getValues().size());
        assertEquals(-7d / 40d, result.getBranchFlow1SensitivityValue("glsk", "l14", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-3d / 8d, result.getBranchFlow1SensitivityValue("glsk", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(1d / 40d, result.getBranchFlow1SensitivityValue("glsk", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(7d / 40d, result.getBranchFlow1SensitivityValue("glsk", "l34", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-7d / 20d, result.getBranchFlow1SensitivityValue("glsk", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testGlskOnSlackBusDistributed() {
        Network network = FourBusNetworkFactory.create();
        runDcLf(network);

        SensitivityAnalysisParameters sensiParameters = createParameters(true, "b1_vl_0", true);
        sensiParameters.getLoadFlowParameters().setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX);

        List<WeightedSensitivityVariable> variables = List.of(new WeightedSensitivityVariable("d2", 30f),
                                                              new WeightedSensitivityVariable("g2", 10f),
                                                              new WeightedSensitivityVariable("d3", 50f),
                                                              new WeightedSensitivityVariable("g1", 10f));
        List<SensitivityVariableSet> variableSets = Collections.singletonList(new SensitivityVariableSet("glsk", variables));

        List<SensitivityFactor> factors = network.getBranchStream().map(branch -> createBranchFlowPerLinearGlsk(branch.getId(), "glsk")).collect(Collectors.toList());

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, Collections.emptyList(), variableSets, sensiParameters);

        assertEquals(5, result.getValues().size());
        assertEquals(0d, result.getBranchFlow1SensitivityValue("glsk", "l14", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-1d / 10d, result.getBranchFlow1SensitivityValue("glsk", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-1d / 10d, result.getBranchFlow1SensitivityValue("glsk", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(1d / 5d, result.getBranchFlow1SensitivityValue("glsk", "l34", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-1d / 5d, result.getBranchFlow1SensitivityValue("glsk", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testLoadInjectionOnSlackBus() {
        // test injection increase on loads
        Network network = FourBusNetworkFactory.createBaseNetwork();
        runDcLf(network);

        SensitivityAnalysisParameters sensiParameters = createParameters(true, "b2_vl_0", false);

        List<SensitivityFactor> factors = createFactorMatrix(network.getLoadStream().collect(Collectors.toList()),
                                                             network.getBranchStream().collect(Collectors.toList()));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, Collections.emptyList(), Collections.emptyList(), sensiParameters);

        assertEquals(0.0d, result.getBranchFlow1SensitivityValue("d2", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testVscInjection() {
        // test injection increase on loads
        Network network = HvdcNetworkFactory.createVsc();
        runDcLf(network);

        SensitivityAnalysisParameters sensiParameters = createParameters(true, "vl1_0", false);

        List<SensitivityFactor> factors = network.getBranchStream()
                .map(branch -> createBranchFlowPerInjectionIncrease(branch.getId(), network.getVscConverterStation("cs2").getId()))
                .collect(Collectors.toList());

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, Collections.emptyList(), Collections.emptyList(), sensiParameters);

        assertEquals(-1d, result.getBranchFlow1SensitivityValue("cs2", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testLccInjection() {
        // test injection increase on loads
        Network network = HvdcNetworkFactory.createLcc();
        runDcLf(network);

        SensitivityAnalysisParameters sensiParameters = createParameters(true, "vl1_0", false);

        List<SensitivityFactor> factors = network.getBranchStream()
                .map(branch -> createBranchFlowPerInjectionIncrease(branch.getId(), network.getLccConverterStation("cs2").getId()))
                .collect(Collectors.toList());

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, Collections.emptyList(), Collections.emptyList(), sensiParameters);

        assertEquals(-1d, result.getBranchFlow1SensitivityValue("cs2", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testHvdcSensi() {
        SensitivityAnalysisParameters sensiParameters = createParameters(true, List.of("b1_vl_0", "b4_vl_0"), false);

        // test injection increase on loads
        Network network = HvdcNetworkFactory.createTwoCcLinkedByAHvdcWithGenerators();
        runLf(network, sensiParameters.getLoadFlowParameters());

        Network network1 = HvdcNetworkFactory.createTwoCcLinkedByAHvdcWithGenerators();
        network1.getHvdcLine("hvdc34").setActivePowerSetpoint(network1.getHvdcLine("hvdc34").getActivePowerSetpoint() + SENSI_CHANGE);
        runLf(network1, sensiParameters.getLoadFlowParameters());
        Map<String, Double> loadFlowDiff = network.getLineStream().map(Identifiable::getId)
            .collect(Collectors.toMap(
                lineId -> lineId,
                line -> (network1.getLine(line).getTerminal1().getP() - network.getLine(line).getTerminal1().getP()) / SENSI_CHANGE
            ));

        List<SensitivityFactor> factors = SensitivityFactor.createMatrix(SensitivityFunctionType.BRANCH_ACTIVE_POWER_1, List.of("l12", "l13", "l23"),
                                                                         SensitivityVariableType.HVDC_LINE_ACTIVE_POWER, List.of("hvdc34"),
                                                                         false, ContingencyContext.all());

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, Collections.emptyList(), Collections.emptyList(), sensiParameters);

        assertEquals(loadFlowDiff.get("l12"), result.getBranchFlow1SensitivityValue("hvdc34", "l12", SensitivityVariableType.HVDC_LINE_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(loadFlowDiff.get("l13"), result.getBranchFlow1SensitivityValue("hvdc34", "l13", SensitivityVariableType.HVDC_LINE_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(loadFlowDiff.get("l23"), result.getBranchFlow1SensitivityValue("hvdc34", "l23", SensitivityVariableType.HVDC_LINE_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testHvdcSensiWithBothSides() {
        SensitivityAnalysisParameters sensiParameters = createParameters(true, "b1_vl_0", false);

        // test injection increase on loads
        Network network = HvdcNetworkFactory.createNetworkWithGenerators();
        runLf(network, sensiParameters.getLoadFlowParameters());

        Network network1 = HvdcNetworkFactory.createNetworkWithGenerators();
        network1.getHvdcLine("hvdc34").setActivePowerSetpoint(network1.getHvdcLine("hvdc34").getActivePowerSetpoint() + SENSI_CHANGE);
        runLf(network1, sensiParameters.getLoadFlowParameters());
        Map<String, Double> loadFlowDiff = network.getLineStream().map(Identifiable::getId)
            .collect(Collectors.toMap(
                lineId -> lineId,
                line -> (network1.getLine(line).getTerminal1().getP() - network.getLine(line).getTerminal1().getP()) / SENSI_CHANGE
            ));

        List<SensitivityFactor> factors = SensitivityFactor.createMatrix(SensitivityFunctionType.BRANCH_ACTIVE_POWER_1, List.of("l12", "l13", "l23", "l25", "l45", "l46", "l56"),
                                                                         SensitivityVariableType.HVDC_LINE_ACTIVE_POWER, List.of("hvdc34"),
                                                                         false, ContingencyContext.all());

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, Collections.emptyList(), Collections.emptyList(), sensiParameters);

        assertEquals(loadFlowDiff.get("l12"), result.getBranchFlow1SensitivityValue("hvdc34", "l12", SensitivityVariableType.HVDC_LINE_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(loadFlowDiff.get("l13"), result.getBranchFlow1SensitivityValue("hvdc34", "l13", SensitivityVariableType.HVDC_LINE_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(loadFlowDiff.get("l23"), result.getBranchFlow1SensitivityValue("hvdc34", "l23", SensitivityVariableType.HVDC_LINE_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(loadFlowDiff.get("l25"), result.getBranchFlow1SensitivityValue("hvdc34", "l25", SensitivityVariableType.HVDC_LINE_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(loadFlowDiff.get("l45"), result.getBranchFlow1SensitivityValue("hvdc34", "l45", SensitivityVariableType.HVDC_LINE_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(loadFlowDiff.get("l46"), result.getBranchFlow1SensitivityValue("hvdc34", "l46", SensitivityVariableType.HVDC_LINE_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(loadFlowDiff.get("l56"), result.getBranchFlow1SensitivityValue("hvdc34", "l56", SensitivityVariableType.HVDC_LINE_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testHvdcSensiWithBothSidesDistributed() {
        SensitivityAnalysisParameters sensiParameters = createParameters(true, "b1_vl_0", true);

        // test injection increase on loads
        Network network = HvdcNetworkFactory.createNetworkWithGenerators();
        runLf(network, sensiParameters.getLoadFlowParameters());

        Network network1 = HvdcNetworkFactory.createNetworkWithGenerators();
        network1.getHvdcLine("hvdc34").setActivePowerSetpoint(network1.getHvdcLine("hvdc34").getActivePowerSetpoint() + SENSI_CHANGE);
        runLf(network1, sensiParameters.getLoadFlowParameters());
        Map<String, Double> loadFlowDiff = network.getLineStream().map(Identifiable::getId)
            .collect(Collectors.toMap(
                lineId -> lineId,
                line -> (network1.getLine(line).getTerminal1().getP() - network.getLine(line).getTerminal1().getP()) / SENSI_CHANGE
            ));

        List<SensitivityFactor> factors = SensitivityFactor.createMatrix(SensitivityFunctionType.BRANCH_ACTIVE_POWER_1, List.of("l12", "l13", "l23", "l25", "l45", "l46", "l56"),
                                                                         SensitivityVariableType.HVDC_LINE_ACTIVE_POWER, List.of("hvdc34"),
                                                                         false, ContingencyContext.all());

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, Collections.emptyList(), Collections.emptyList(), sensiParameters);

        assertEquals(loadFlowDiff.get("l12"), result.getBranchFlow1SensitivityValue("hvdc34", "l12", SensitivityVariableType.HVDC_LINE_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(loadFlowDiff.get("l13"), result.getBranchFlow1SensitivityValue("hvdc34", "l13", SensitivityVariableType.HVDC_LINE_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(loadFlowDiff.get("l23"), result.getBranchFlow1SensitivityValue("hvdc34", "l23", SensitivityVariableType.HVDC_LINE_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(loadFlowDiff.get("l25"), result.getBranchFlow1SensitivityValue("hvdc34", "l25", SensitivityVariableType.HVDC_LINE_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(loadFlowDiff.get("l45"), result.getBranchFlow1SensitivityValue("hvdc34", "l45", SensitivityVariableType.HVDC_LINE_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(loadFlowDiff.get("l46"), result.getBranchFlow1SensitivityValue("hvdc34", "l46", SensitivityVariableType.HVDC_LINE_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(loadFlowDiff.get("l56"), result.getBranchFlow1SensitivityValue("hvdc34", "l56", SensitivityVariableType.HVDC_LINE_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testHvdcSensiVsc() {
        SensitivityAnalysisParameters sensiParameters = createParameters(true, List.of("b1_vl_0", "b4_vl_0"), true);

        // test injection increase on loads
        Network network = HvdcNetworkFactory.createTwoCcLinkedByAHvdcVscWithGenerators();
        runLf(network, sensiParameters.getLoadFlowParameters());

        Network network1 = HvdcNetworkFactory.createTwoCcLinkedByAHvdcVscWithGenerators();
        network1.getHvdcLine("hvdc34").setActivePowerSetpoint(network1.getHvdcLine("hvdc34").getActivePowerSetpoint() + SENSI_CHANGE);
        runLf(network1, sensiParameters.getLoadFlowParameters());
        Map<String, Double> loadFlowDiff = network.getLineStream().map(Identifiable::getId)
            .collect(Collectors.toMap(
                lineId -> lineId,
                line -> (network1.getLine(line).getTerminal1().getP() - network.getLine(line).getTerminal1().getP()) / SENSI_CHANGE
            ));

        List<SensitivityFactor> factors = SensitivityFactor.createMatrix(SensitivityFunctionType.BRANCH_ACTIVE_POWER_1, List.of("l12", "l13", "l23"),
                                                                         SensitivityVariableType.HVDC_LINE_ACTIVE_POWER, List.of("hvdc34"),
                                                                         false, ContingencyContext.all());

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, Collections.emptyList(), Collections.emptyList(), sensiParameters);

        assertEquals(loadFlowDiff.get("l12"), result.getBranchFlow1SensitivityValue("hvdc34", "l12", SensitivityVariableType.HVDC_LINE_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(loadFlowDiff.get("l13"), result.getBranchFlow1SensitivityValue("hvdc34", "l13", SensitivityVariableType.HVDC_LINE_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(loadFlowDiff.get("l23"), result.getBranchFlow1SensitivityValue("hvdc34", "l23", SensitivityVariableType.HVDC_LINE_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testHvdcSensiVsc2() {
        SensitivityAnalysisParameters sensiParameters = createParameters(true, List.of("b1_vl_0", "b4_vl_0"), true);

        // test injection increase on loads
        Network network = HvdcNetworkFactory.createTwoCcLinkedByAHvdcVscWithGenerators();
        network.getHvdcLine("hvdc34").setConvertersMode(HvdcLine.ConvertersMode.SIDE_1_RECTIFIER_SIDE_2_INVERTER);
        network.getGeneratorStream().forEach(gen -> gen.setMaxP(2 * gen.getMaxP()));
        runLf(network, sensiParameters.getLoadFlowParameters());

        Network network1 = HvdcNetworkFactory.createTwoCcLinkedByAHvdcVscWithGenerators();
        network1.getHvdcLine("hvdc34").setConvertersMode(HvdcLine.ConvertersMode.SIDE_1_RECTIFIER_SIDE_2_INVERTER);
        network1.getHvdcLine("hvdc34").setActivePowerSetpoint(network1.getHvdcLine("hvdc34").getActivePowerSetpoint() + SENSI_CHANGE);
        network1.getGeneratorStream().forEach(gen -> gen.setMaxP(2 * gen.getMaxP()));
        runLf(network1, sensiParameters.getLoadFlowParameters());
        Map<String, Double> loadFlowDiff = network.getLineStream().map(Identifiable::getId)
            .collect(Collectors.toMap(
                lineId -> lineId,
                line -> (network1.getLine(line).getTerminal1().getP() - network.getLine(line).getTerminal1().getP()) / SENSI_CHANGE
            ));

        List<SensitivityFactor> factors = SensitivityFactor.createMatrix(SensitivityFunctionType.BRANCH_ACTIVE_POWER_1, List.of("l12", "l13", "l23"),
                SensitivityVariableType.HVDC_LINE_ACTIVE_POWER, List.of("hvdc34"),
                false, ContingencyContext.all());

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, Collections.emptyList(), Collections.emptyList(), sensiParameters);

        assertEquals(loadFlowDiff.get("l12"), result.getBranchFlow1SensitivityValue("hvdc34", "l12", SensitivityVariableType.HVDC_LINE_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(loadFlowDiff.get("l13"), result.getBranchFlow1SensitivityValue("hvdc34", "l13", SensitivityVariableType.HVDC_LINE_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(loadFlowDiff.get("l23"), result.getBranchFlow1SensitivityValue("hvdc34", "l23", SensitivityVariableType.HVDC_LINE_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testHvdcInjectionNotFound() {
        testHvdcInjectionNotFound(true);
    }

    @Test
    void testHvdcSensiDisconnected() {
        Network network = HvdcNetworkFactory.createTwoCcLinkedByAHvdcWithGenerators();
        HvdcLine hvdc34 = network.getHvdcLine("hvdc34");

        SensitivityAnalysisParameters sensiParameters = createParameters(true, List.of("b1_vl_0", "b4_vl_0"), false);

        SensitivityFactor factor = new SensitivityFactor(
                SensitivityFunctionType.BRANCH_ACTIVE_POWER_1, "l12",
                SensitivityVariableType.HVDC_LINE_ACTIVE_POWER, "hvdc34",
                false, ContingencyContext.all());

        // disconnected both sides
        hvdc34.getConverterStation1().getTerminal().disconnect();
        hvdc34.getConverterStation2().getTerminal().disconnect();
        SensitivityAnalysisResult result = sensiRunner.run(network, List.of(factor), Collections.emptyList(), Collections.emptyList(), sensiParameters);
        assertEquals(0, result.getBranchFlow1SensitivityValue("hvdc34", "l12", SensitivityVariableType.HVDC_LINE_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);

        // disconnected other side
        hvdc34.getConverterStation1().getTerminal().connect();
        result = sensiRunner.run(network, List.of(factor), Collections.emptyList(), Collections.emptyList(), sensiParameters);
        assertEquals(-0.325, result.getBranchFlow1SensitivityValue("hvdc34", "l12", SensitivityVariableType.HVDC_LINE_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);

        // disconnected network side
        hvdc34.getConverterStation1().getTerminal().disconnect();
        hvdc34.getConverterStation2().getTerminal().connect();
        result = sensiRunner.run(network, List.of(factor), Collections.emptyList(), Collections.emptyList(), sensiParameters);
        assertEquals(0, result.getBranchFlow1SensitivityValue("hvdc34", "l12", SensitivityVariableType.HVDC_LINE_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testBalanceTypeNotSupported() {
        // test injection increase on loads
        Network network = FourBusNetworkFactory.create();
        runDcLf(network);

        SensitivityAnalysisParameters sensiParameters = createParameters(true, "b3_vl_0", true);
        sensiParameters.getLoadFlowParameters().setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_CONFORM_LOAD);

        List<SensitivityFactor> factors = createFactorMatrix(network.getLoadStream().collect(Collectors.toList()),
                                                             network.getBranchStream().collect(Collectors.toList()));

        List<Contingency> contingencies = Collections.emptyList();
        List<SensitivityVariableSet> variableSets = Collections.emptyList();

        CompletionException exception = assertThrows(CompletionException.class, () -> sensiRunner.run(network, factors, contingencies, variableSets, sensiParameters));
        assertTrue(exception.getCause() instanceof UnsupportedOperationException);
        assertEquals("Unsupported balance type mode: PROPORTIONAL_TO_CONFORM_LOAD", exception.getCause().getMessage());
    }

    @Test
    void testPhaseShifter() {
        Network network = PhaseShifterTestCaseFactory.create();
        runAcLf(network);

        SensitivityAnalysisParameters sensiParameters = createParameters(true, "VL2_0");

        List<SensitivityFactor> factors = List.of(createBranchFlowPerPSTAngle("L1", "PS1"));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, Collections.emptyList(), Collections.emptyList(), sensiParameters);

        assertEquals(-6.3d, result.getBranchFlow1SensitivityValue("PS1", "L1", SensitivityVariableType.TRANSFORMER_PHASE), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testAdditionalFactors() {
        // test injection increase on loads
        Network network = FourBusNetworkFactory.create();
        runDcLf(network);

        Map<String, Double> functionReferenceByLine = new HashMap<>();
        for (Line line : network.getLines()) {
            functionReferenceByLine.put(line.getId(), line.getTerminal1().getP());
        }

        SensitivityAnalysisParameters sensiParameters = createParameters(true, "b3_vl_0", true);
        sensiParameters.getLoadFlowParameters().setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD);

        List<SensitivityFactor> factors = List.of(createBranchFlowPerInjectionIncrease("l12", "g1"),
                                                  createBranchFlowPerInjectionIncrease("l13", "g2"));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, Collections.emptyList(), Collections.emptyList(), sensiParameters);

        assertEquals(2, result.getValues().size());
        assertEquals(0.325d, result.getBranchFlow1SensitivityValue("g1", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.2d, result.getBranchFlow1SensitivityValue("g2", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);

        assertEquals(functionReferenceByLine.get("l12"), result.getBranchFlow1FunctionReferenceValue("l12"), LoadFlowAssert.DELTA_POWER);
        assertEquals(functionReferenceByLine.get("l13"), result.getBranchFlow1FunctionReferenceValue("l13"), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testInjectionNotFoundAdditionalFactor() {
        testInjectionNotFoundAdditionalFactor(true);
    }

    @Test
    void testIntensityCrash() {
        Network network = FourBusNetworkFactory.createWithPhaseTapChangerAndGeneratorAtBus2();

        SensitivityAnalysisParameters sensiParameters = createParameters(true, "b1_vl_0", true);
        sensiParameters.getLoadFlowParameters().setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX);

        List<SensitivityFactor> factors = network.getBranchStream().map(branch -> createBranchIntensityPerPSTAngle(branch.getId(), "l23")).collect(Collectors.toList());

        List<Contingency> contingencies = Collections.emptyList();
        List<SensitivityVariableSet> variableSets = Collections.emptyList();

        CompletionException e = assertThrows(CompletionException.class, () -> sensiRunner.run(network, factors, contingencies, variableSets, sensiParameters));
        assertTrue(e.getCause() instanceof PowsyblException);
        assertEquals("Only variables of type TRANSFORMER_PHASE, TRANSFORMER_PHASE_1, TRANSFORMER_PHASE_2, TRANSFORMER_PHASE_3, INJECTION_ACTIVE_POWER and HVDC_LINE_ACTIVE_POWER, and functions of type BRANCH_ACTIVE_POWER_1, BRANCH_ACTIVE_POWER_2 and BRANCH_ACTIVE_POWER_3 are yet supported in DC", e.getCause().getMessage());
    }

    @Test
    void testBranchFunctionOutsideMainComponent() {
        testBranchFunctionOutsideMainComponent(true);
    }

    @Test
    void testInjectionOutsideMainComponent() {
        testInjectionOutsideMainComponent(true);
    }

    @Test
    void testPhaseShifterOutsideMainComponent() {
        testPhaseShifterOutsideMainComponent(true);
    }

    @Test
    void testGlskOutsideMainComponent() {
        testGlskOutsideMainComponent(true);
    }

    @Test
    void testGlskAndLineOutsideMainComponent() {
        testGlskAndLineOutsideMainComponent(true);
    }

    @Test
    void testGlskPartiallyOutsideMainComponent() {
        testGlskPartiallyOutsideMainComponent(true);
    }

    @Test
    void testInjectionNotFound() {
        testInjectionNotFound(true);
    }

    @Test
    void testBranchNotFound() {
        testBranchNotFound(true);
    }

    @Test
    void testEmptyFactors() {
        testEmptyFactors(true);
    }

    @Test
    void testGlskNotFound() {
        testGlskInjectionNotFound(true);
    }

    @Test
    void testDanglingLineSensi() {
        Network network = BoundaryFactory.createWithLoad();
        runAcLf(network);

        SensitivityAnalysisParameters sensiParameters = createParameters(true, "vl1_0");

        List<SensitivityFactor> factors = List.of(createBranchFlowPerInjectionIncrease("l1", "dl1"),
                createBranchFlowPerInjectionIncrease("dl1", "load3"));

        // dangling line is connected
        SensitivityAnalysisResult result = sensiRunner.run(network, factors, Collections.emptyList(), Collections.emptyList(), sensiParameters);
        assertEquals(-0.812d, result.getBranchFlow1SensitivityValue("dl1", "l1", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(91.0, result.getBranchFlow1FunctionReferenceValue("dl1"), LoadFlowAssert.DELTA_POWER);

        // dangling line is connected on base case but will be disconnected by a contingency => 0
        List<Contingency> contingencies = List.of(new Contingency("c", new DanglingLineContingency("dl1")));
        result = sensiRunner.run(network, factors, contingencies, Collections.emptyList(), sensiParameters);
        assertEquals(-0.812d, result.getBranchFlow1SensitivityValue("dl1", "l1", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("c", "dl1", "l1", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);

        // dangling line is disconnected on base case => 0
        network.getDanglingLine("dl1").getTerminal().disconnect();
        result = sensiRunner.run(network, factors, Collections.emptyList(), Collections.emptyList(), sensiParameters);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("dl1", "l1", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testContingencyOnOpenLine() {
        Network network = NodeBreakerNetworkFactory.create();
        List<Contingency> contingencies = List.of(new Contingency("c1", new BranchContingency("L1")));

        List<PropagatedContingency> propagatedContingencies = PropagatedContingency.createList(network, contingencies, new LfTopoConfig(), new PropagatedContingencyCreationParameters());
        assertEquals(1, propagatedContingencies.size());
    }

    @Test
    void testOpenMonitoredBranch() {
        Network network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());
        runDcLf(network);
        network.getLine("NHV1_NHV2_1").getTerminal2().disconnect();

        SensitivityAnalysisParameters sensiParameters = createParameters(true, "VLLOAD_0");

        List<WeightedSensitivityVariable> variables = List.of(new WeightedSensitivityVariable("LOAD", 10f));
        List<SensitivityVariableSet> variableSets = List.of(new SensitivityVariableSet("glsk", variables));

        List<SensitivityFactor> factors = List.of(createBranchFlowPerLinearGlsk("NHV1_NHV2_1", "glsk"));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, Collections.emptyList(), variableSets, sensiParameters);

        assertEquals(0., result.getBranchFlow1SensitivityValue("glsk", "NHV1_NHV2_1", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(Double.NaN, result.getBranchFlow1FunctionReferenceValue("NHV1_NHV2_1"), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testOpenMonitoredBranch2() {
        Network network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());
        runDcLf(network);
        network.getLine("NHV1_NHV2_1").getTerminal1().disconnect();

        SensitivityAnalysisParameters sensiParameters = createParameters(true, "VLLOAD_0");

        List<WeightedSensitivityVariable> variables = List.of(new WeightedSensitivityVariable("LOAD", 10f));
        List<SensitivityVariableSet> variableSets = List.of(new SensitivityVariableSet("glsk", variables));

        List<SensitivityFactor> factors = List.of(createBranchFlowPerLinearGlsk("NHV1_NHV2_1", "glsk"));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, Collections.emptyList(), variableSets, sensiParameters);

        assertEquals(0., result.getBranchFlow1SensitivityValue("glsk", "NHV1_NHV2_1", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(Double.NaN, result.getBranchFlow1FunctionReferenceValue("NHV1_NHV2_1"), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void nonImpedantBranchTest() {
        Network network = PhaseShifterTestCaseFactory.create();
        network.getLine("L2").setX(0).setR(0);

        SensitivityAnalysisParameters sensiParameters = createParameters(true);

        List<SensitivityVariableSet> variableSets = List.of(new SensitivityVariableSet("glsk", List.of(new WeightedSensitivityVariable("LD2", 10f))));

        List<SensitivityFactor> factors = List.of(createBranchFlowPerLinearGlsk("L2", "glsk"));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, Collections.emptyList(), variableSets, sensiParameters);

        assertEquals(-0.6666666, result.getBranchFlow1SensitivityValue("glsk", "L2", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(66.6666, result.getBranchFlow1FunctionReferenceValue("L2"), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testConfiguredBusFactor() {
        Network network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());
        runAcLf(network);

        SensitivityAnalysisParameters sensiParameters = createParameters(true, "VLLOAD_0");

        List<SensitivityFactor> factors = List.of(createBranchFlowPerInjectionIncrease("NHV1_NHV2_1", "GEN"),
                                                  new SensitivityFactor(SensitivityFunctionType.BRANCH_ACTIVE_POWER_1,
                                                                        "NHV1_NHV2_1",
                                                                        SensitivityVariableType.INJECTION_ACTIVE_POWER,
                                                                        "NGEN",
                                                                        false,
                                                                        ContingencyContext.all()));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, Collections.emptyList(), Collections.emptyList(), sensiParameters);

        assertEquals(2, result.getValues().size());
        assertEquals(result.getBranchFlow1SensitivityValue("GEN", "NHV1_NHV2_1", SensitivityVariableType.INJECTION_ACTIVE_POWER),
                     result.getBranchFlow1SensitivityValue("NGEN", "NHV1_NHV2_1", SensitivityVariableType.INJECTION_ACTIVE_POWER),
                     LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testConfiguredBusInvalidFactor() {
        Network network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());
        network.getVoltageLevel("VLGEN").getBusBreakerView().newBus()
                .setId("X")
                .add();
        runAcLf(network);

        SensitivityAnalysisParameters sensiParameters = createParameters(true, "VLLOAD_0");

        List<SensitivityFactor> factors = List.of(new SensitivityFactor(SensitivityFunctionType.BRANCH_ACTIVE_POWER_1,
                                                                        "NHV1_NHV2_1",
                                                                        SensitivityVariableType.INJECTION_ACTIVE_POWER,
                                                                        "X",
                                                                        false,
                                                                        ContingencyContext.all()));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, Collections.emptyList(), Collections.emptyList(), sensiParameters);

        assertEquals(1, result.getValues().size());
        assertEquals(0, result.getBranchFlow1SensitivityValue("X", "NHV1_NHV2_1", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testBusbarSectionFactor() {
        Network network = NodeBreakerNetworkFactory.create();
        runAcLf(network);

        SensitivityAnalysisParameters sensiParameters = createParameters(true, "VL2_0");

        List<SensitivityFactor> factors = List.of(createBranchFlowPerInjectionIncrease("L1", "G"),
                                                  new SensitivityFactor(SensitivityFunctionType.BRANCH_ACTIVE_POWER_1,
                                                                        "L1",
                                                                        SensitivityVariableType.INJECTION_ACTIVE_POWER,
                                                                        "BBS2",
                                                                        false,
                                                                        ContingencyContext.all()));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, Collections.emptyList(), Collections.emptyList(), sensiParameters);

        assertEquals(2, result.getValues().size());
        assertEquals(result.getBranchFlow1SensitivityValue("G", "L1", SensitivityVariableType.INJECTION_ACTIVE_POWER),
                     result.getBranchFlow1SensitivityValue("BBS2", "L1", SensitivityVariableType.INJECTION_ACTIVE_POWER),
                     LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testBusbarSectionInvalidFactor() {
        Network network = NodeBreakerNetworkFactory.create();
        network.getVoltageLevel("VL1").getNodeBreakerView().newBusbarSection()
                .setId("X")
                .setNode(100)
                .add();
        runAcLf(network);

        SensitivityAnalysisParameters sensiParameters = createParameters(true, "VL2_0");

        List<SensitivityFactor> factors = List.of(new SensitivityFactor(SensitivityFunctionType.BRANCH_ACTIVE_POWER_1,
                                                                        "L1",
                                                                        SensitivityVariableType.INJECTION_ACTIVE_POWER,
                                                                        "X",
                                                                        false,
                                                                        ContingencyContext.all()));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, Collections.emptyList(), Collections.emptyList(), sensiParameters);

        assertEquals(1, result.getValues().size());
        assertEquals(0, result.getBranchFlow1SensitivityValue("X", "L1", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testWithTieLines() {
        SensitivityAnalysisParameters sensiParameters = createParameters(true, "b1_vl_0", true);
        sensiParameters.getLoadFlowParameters().setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX);
        Network network = BoundaryFactory.createWithTieLine();
        List<SensitivityFactor> factors = network.getTieLineStream().map(line -> createBranchFlowPerInjectionIncrease(line.getId(), "h1")).collect(Collectors.toList());
        List<Contingency> contingencies = Collections.emptyList();
        List<SensitivityVariableSet> variableSets = Collections.emptyList();
        CompletionException e = assertThrows(CompletionException.class, () -> sensiRunner.run(network, factors, contingencies, variableSets, sensiParameters));
        assertTrue(e.getCause() instanceof PowsyblException);
        assertEquals("The dangling line h1 is paired: it cannot be a sensitivity variable", e.getCause().getMessage());
    }

    @Test
    void testWithTieLines2() {
        SensitivityAnalysisParameters sensiParameters = createParameters(true, "b1_vl_0", true);
        sensiParameters.getLoadFlowParameters().setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX);
        Network network = BoundaryFactory.createWithTieLine();
        List<SensitivityFactor> factors = network.getDanglingLineStream().map(line -> createBranchFlowPerInjectionIncrease(line.getId(), "g1")).collect(Collectors.toList());
        List<Contingency> contingencies = Collections.emptyList();
        List<SensitivityVariableSet> variableSets = Collections.emptyList();
        CompletionException e = assertThrows(CompletionException.class, () -> sensiRunner.run(network, factors, contingencies, variableSets, sensiParameters));
        assertTrue(e.getCause() instanceof PowsyblException);
        assertEquals("Branch, tie line, dangling line or leg of 'h1' not found", e.getCause().getMessage());
    }

    @Test
    void testTooManyFactorsAndContingencies() {
        EquationSystem<DcVariableType, DcEquationType> equationSystem = Mockito.mock(EquationSystem.class);
        EquationSystemIndex<DcVariableType, DcEquationType> equationSystemIndex = Mockito.mock(EquationSystemIndex.class);
        Mockito.when(equationSystem.getIndex()).thenReturn(equationSystemIndex);
        List<Equation<DcVariableType, DcEquationType>> equations = Mockito.mock(List.class);
        Mockito.when(equations.size()).thenReturn(100000);
        Mockito.when(equationSystemIndex.getSortedEquationsToSolve()).thenReturn(equations);
        AbstractSensitivityAnalysis.SensitivityFactorGroupList<DcVariableType, DcEquationType> factorsGroups = Mockito.mock(AbstractSensitivityAnalysis.SensitivityFactorGroupList.class);
        List<AbstractSensitivityAnalysis.SensitivityFactorGroup<DcVariableType, DcEquationType>> factorGroupList = Mockito.mock(List.class);
        Mockito.when(factorGroupList.size()).thenReturn(3333333);
        Mockito.when(factorsGroups.getList()).thenReturn(factorGroupList);
        Map<LfBus, Double> participationByBus = Collections.emptyMap();
        PowsyblException e = assertThrows(PowsyblException.class, () -> AbstractSensitivityAnalysis.initFactorsRhs(equationSystem, factorsGroups, participationByBus));
        assertEquals("Too many factors groups 3333333, maximum is 2684 for a system with 100000 equations", e.getMessage());

        List<ComputedContingencyElement> contingencyElements = Mockito.mock(List.class);
        Mockito.when(contingencyElements.size()).thenReturn(999999);
        e = assertThrows(PowsyblException.class, () -> AbstractComputedElement.initRhs(equationSystem, contingencyElements));
        assertEquals("Too many elements 999999, maximum is 2684 for a system with 100000 equations", e.getMessage());
    }

    @Test
    void testThreeWindingsTransformerAsFunction() {
        SensitivityAnalysisParameters sensiParameters = createParameters(true, "b1_vl_0", true);
        sensiParameters.getLoadFlowParameters().setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX);
        Network network = VoltageControlNetworkFactory.createNetworkWithT3wt();

        SensitivityFactor factorActivePower1Twt = createTransformerLegFlowPerInjectionIncrease("T3wT", "LOAD_3", ThreeSides.ONE);
        SensitivityFactor factorActivePower2Twt = createTransformerLegFlowPerInjectionIncrease("T3wT", "LOAD_3", ThreeSides.TWO);
        SensitivityFactor factorActivePower3Twt = createTransformerLegFlowPerInjectionIncrease("T3wT", "LOAD_3", ThreeSides.THREE);

        List<SensitivityFactor> factors = List.of(factorActivePower1Twt, factorActivePower2Twt, factorActivePower3Twt);
        SensitivityAnalysisResult result = sensiRunner.run(network, factors, Collections.emptyList(), Collections.emptyList(), sensiParameters);
        assertEquals(3, result.getValues().size());

        assertEquals(10.0, result.getBranchFlow1FunctionReferenceValue("T3wT"), LoadFlowAssert.DELTA_POWER);
        assertEquals(-5.0, result.getBranchFlow2FunctionReferenceValue("T3wT"), LoadFlowAssert.DELTA_POWER);
        assertEquals(-5.0, result.getBranchFlow3FunctionReferenceValue("T3wT"), LoadFlowAssert.DELTA_POWER);

        assertEquals(-1.0, result.getBranchFlow1SensitivityValue("LOAD_3", "T3wT", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(1.0, result.getBranchFlow2SensitivityValue("LOAD_3", "T3wT", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.0, result.getBranchFlow3SensitivityValue("LOAD_3", "T3wT", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testThreeWindingsTransformerAsVariable() {
        SensitivityAnalysisParameters sensiParameters = createParameters(true, "b1_vl_0", true);

        sensiParameters.getLoadFlowParameters()
                .setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX)
                .getExtension(OpenLoadFlowParameters.class)
                    .setSlackBusPMaxMismatch(0.001)
                    .setNewtonRaphsonConvEpsPerEq(0.0001);
        sensiParameters.setAngleFlowSensitivityValueThreshold(0.1);
        Network network = PhaseControlFactory.createNetworkWithT3wt();

        //Add phase tap changer to leg1 and leg3 of the twt for testing purpose
        ThreeWindingsTransformer twt = network.getThreeWindingsTransformer("PS1");
        twt.getLeg1().newPhaseTapChanger()
                .setTapPosition(1)
                .setRegulationTerminal(twt.getLeg1().getTerminal())
                .setRegulationMode(PhaseTapChanger.RegulationMode.FIXED_TAP)
                .setRegulationValue(200)
                .beginStep()
                .setAlpha(-5.0)
                .endStep()
                .beginStep()
                .setAlpha(0.0)
                .endStep()
                .beginStep()
                .setAlpha(5)
                .endStep()
                .add();
        twt.getLeg3().newPhaseTapChanger()
                .setTapPosition(1)
                .setRegulationTerminal(twt.getLeg3().getTerminal())
                .setRegulationMode(PhaseTapChanger.RegulationMode.FIXED_TAP)
                .setRegulationValue(200)
                .beginStep()
                .setAlpha(-5.0)
                .endStep()
                .beginStep()
                .setAlpha(0.0)
                .endStep()
                .beginStep()
                .setAlpha(5)
                .endStep()
                .add();

        SensitivityFactor factorPhase1 = createBranchFlowPerTransformerLegPSTAngle("L1", "PS1", ThreeSides.ONE);
        SensitivityFactor factorPhase2 = createBranchFlowPerTransformerLegPSTAngle("L1", "PS1", ThreeSides.TWO);
        SensitivityFactor factorPhase3 = createBranchFlowPerTransformerLegPSTAngle("L1", "PS1", ThreeSides.THREE);
        List<SensitivityFactor> factors = List.of(factorPhase1, factorPhase2, factorPhase3);
        SensitivityAnalysisResult result = sensiRunner.run(network, factors, Collections.emptyList(), Collections.emptyList(), sensiParameters);
        assertEquals(2, result.getValues().size());

        // Numerically computing sensi of L1 active power flow with respect to PS1 transformer phase 1
        loadFlowRunner.run(network, sensiParameters.getLoadFlowParameters());
        double p0 = network.getBranch("L1").getTerminal1().getP();
        network.getThreeWindingsTransformer("PS1").getLeg1().getPhaseTapChanger().setTapPosition(2); // A phase shift of 5.0 is applied
        loadFlowRunner.run(network, sensiParameters.getLoadFlowParameters());
        double p1 = network.getBranch("L1").getTerminal1().getP();
        double sensiL1 = (p1 - p0) / 5.0;

        assertEquals(-5.245, sensiL1, LoadFlowAssert.DELTA_POWER);
        assertEquals(-5.245, result.getBranchFlow1SensitivityValue("PS1", "L1", SensitivityVariableType.TRANSFORMER_PHASE_1), LoadFlowAssert.DELTA_POWER);
        assertEquals(5.245, result.getBranchFlow1SensitivityValue("PS1", "L1", SensitivityVariableType.TRANSFORMER_PHASE_2), LoadFlowAssert.DELTA_POWER);
        //Sensitivity value at phase 3 is filtered because it is 0
    }
}
