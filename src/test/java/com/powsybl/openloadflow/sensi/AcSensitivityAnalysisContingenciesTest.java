/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sensi;

import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.contingency.*;
import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.StaticVarCompensator;
import com.powsybl.iidm.network.TwoSides;
import com.powsybl.iidm.network.extensions.HvdcAngleDroopActivePowerControlAdder;
import com.powsybl.iidm.network.extensions.StandbyAutomatonAdder;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.util.LoadFlowAssert;
import com.powsybl.sensitivity.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
class AcSensitivityAnalysisContingenciesTest extends AbstractSensitivityAnalysisTest {

    @Test
    void test4BusesSensi() {
        SensitivityAnalysisParameters sensiParameters = createParameters(false, "b1_vl_0", true);
        sensiParameters.getLoadFlowParameters().setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX);

        Network network = FourBusNetworkFactory.create();
        runLf(network, sensiParameters.getLoadFlowParameters());

        List<SensitivityFactor> factors = createFactorMatrix(network.getGeneratorStream().collect(Collectors.toList()),
                                                             network.getBranchStream().collect(Collectors.toList()),
                                                             "l23");

        List<Contingency> contingencies = List.of(new Contingency("l23", new BranchContingency("l23")));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, contingencies, Collections.emptyList(), sensiParameters);

        assertEquals(15, result.getValues().size());
        assertEquals(-0.5409d, result.getBranchFlow1SensitivityValue("l23", "g4", "l14", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.4000d, result.getBranchFlow1SensitivityValue("l23", "g4", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("l23", "g4", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.2591d, result.getBranchFlow1SensitivityValue("l23", "g4", "l34", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.2591d, result.getBranchFlow1SensitivityValue("l23", "g4", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);

        assertEquals(0.1352d, result.getBranchFlow1SensitivityValue("l23", "g1", "l14", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.4000d, result.getBranchFlow1SensitivityValue("l23", "g1", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("l23", "g1", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.0648d, result.getBranchFlow1SensitivityValue("l23", "g1", "l34", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.0648d, result.getBranchFlow1SensitivityValue("l23", "g1", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);

        assertEquals(0.1352d, result.getBranchFlow1SensitivityValue("l23", "g2", "l14", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.6000d, result.getBranchFlow1SensitivityValue("l23", "g2", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("l23", "g2", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.0648d, result.getBranchFlow1SensitivityValue("l23", "g2", "l34", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.0648d, result.getBranchFlow1SensitivityValue("l23", "g2", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void test4BusesSensiAdditionalFactor() {
        SensitivityAnalysisParameters sensiParameters = createParameters(false, "b1_vl_0", true);
        sensiParameters.getLoadFlowParameters().setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX);

        Network network = FourBusNetworkFactory.create();
        runLf(network, sensiParameters.getLoadFlowParameters());

        List<SensitivityFactor> factors = createFactorMatrix(network.getGeneratorStream().collect(Collectors.toList()),
                                                             network.getBranchStream().collect(Collectors.toList()));

        List<Contingency> contingencies = List.of(new Contingency("l23", new BranchContingency("l23")));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, contingencies, Collections.emptyList(), sensiParameters);

        assertEquals(15, result.getValues("l23").size());

        assertEquals(-0.5409d, result.getBranchFlow1SensitivityValue("l23", "g4", "l14", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.4000d, result.getBranchFlow1SensitivityValue("l23", "g4", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("l23", "g4", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.2591d, result.getBranchFlow1SensitivityValue("l23", "g4", "l34", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.2591d, result.getBranchFlow1SensitivityValue("l23", "g4", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);

        assertEquals(0.1352d, result.getBranchFlow1SensitivityValue("l23", "g1", "l14", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.4000d, result.getBranchFlow1SensitivityValue("l23", "g1", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("l23", "g1", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.0648d, result.getBranchFlow1SensitivityValue("l23", "g1", "l34", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.0648d, result.getBranchFlow1SensitivityValue("l23", "g1", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);

        assertEquals(0.1352d, result.getBranchFlow1SensitivityValue("l23", "g2", "l14", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.6000d, result.getBranchFlow1SensitivityValue("l23", "g2", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("l23", "g2", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.0648d, result.getBranchFlow1SensitivityValue("l23", "g2", "l34", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.0648d, result.getBranchFlow1SensitivityValue("l23", "g2", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void test4BusesFunctionReference() {
        SensitivityAnalysisParameters sensiParameters = createParameters(false, "b1_vl_0", true);
        sensiParameters.getLoadFlowParameters().setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX);

        Network network = FourBusNetworkFactory.create();
        runLf(network, sensiParameters.getLoadFlowParameters());

        List<SensitivityFactor> factors = createFactorMatrix(network.getGeneratorStream().collect(Collectors.toList()),
                                                             network.getBranchStream().collect(Collectors.toList()));

        List<Contingency> contingencies = Collections.singletonList(new Contingency("l23", new BranchContingency("l23")));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, contingencies, Collections.emptyList(), sensiParameters);

        assertEquals(0.6761d, result.getBranchFlow1FunctionReferenceValue("l23", "l14"), LoadFlowAssert.DELTA_POWER);
        assertEquals(-1.0d, result.getBranchFlow1FunctionReferenceValue("l23", "l12"), LoadFlowAssert.DELTA_POWER);
        assertEquals(Double.NaN, result.getBranchFlow1FunctionReferenceValue("l23", "l23"), LoadFlowAssert.DELTA_POWER);
        assertEquals(-1.676d, result.getBranchFlow1FunctionReferenceValue("l23", "l34"), LoadFlowAssert.DELTA_POWER);
        assertEquals(2.324d, result.getBranchFlow1FunctionReferenceValue("l23", "l13"), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void test4busesLoosingATransfo() {
        SensitivityAnalysisParameters sensiParameters = createParameters(false, "b1_vl_0", true);
        sensiParameters.getLoadFlowParameters().setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX);

        Network network = FourBusNetworkFactory.createWithPhaseTapChangerAndGeneratorAtBus2();
        runLf(network, sensiParameters.getLoadFlowParameters());

        List<SensitivityFactor> factors = createFactorMatrix(network.getGeneratorStream().collect(Collectors.toList()),
                                                             network.getBranchStream().collect(Collectors.toList()),
                                                             "l23");

        List<Contingency> contingencies = Collections.singletonList(new Contingency("l23", new BranchContingency("l23")));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, contingencies, Collections.emptyList(), sensiParameters);

        assertEquals(15, result.getValues().size());
        assertEquals(-0.5409d, result.getBranchFlow1SensitivityValue("l23", "g4", "l14", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.400d, result.getBranchFlow1SensitivityValue("l23", "g4", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("l23", "g4", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.2591d, result.getBranchFlow1SensitivityValue("l23", "g4", "l34", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.2591d, result.getBranchFlow1SensitivityValue("l23", "g4", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);

        assertEquals(0.1352d, result.getBranchFlow1SensitivityValue("l23", "g1", "l14", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.4000d, result.getBranchFlow1SensitivityValue("l23", "g1", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("l23", "g1", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.0648d, result.getBranchFlow1SensitivityValue("l23", "g1", "l34", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.0648d, result.getBranchFlow1SensitivityValue("l23", "g1", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);

        assertEquals(0.1352d, result.getBranchFlow1SensitivityValue("l23", "g2", "l14", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.600d, result.getBranchFlow1SensitivityValue("l23", "g2", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("l23", "g2", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.0648d, result.getBranchFlow1SensitivityValue("l23", "g2", "l34", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.0648d, result.getBranchFlow1SensitivityValue("l23", "g2", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void test4busesLoosingATransfoFunctionRef() {
        SensitivityAnalysisParameters sensiParameters = createParameters(false, "b1_vl_0", true);
        sensiParameters.getLoadFlowParameters().setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX);

        Network network = FourBusNetworkFactory.createWithPhaseTapChangerAndGeneratorAtBus2();
        runLf(network, sensiParameters.getLoadFlowParameters());

        List<SensitivityFactor> factors = createFactorMatrix(network.getGeneratorStream().collect(Collectors.toList()),
                                                             network.getBranchStream().collect(Collectors.toList()),
                                                             "l23");

        List<Contingency> contingencies = Collections.singletonList(new Contingency("l23", new BranchContingency("l23")));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, contingencies, Collections.emptyList(), sensiParameters);

        assertEquals(15, result.getValues().size());
        assertEquals(0.6761d, result.getBranchFlow1FunctionReferenceValue("l23", "l14"), LoadFlowAssert.DELTA_POWER);
        assertEquals(-1.0d, result.getBranchFlow1FunctionReferenceValue("l23", "l12"), LoadFlowAssert.DELTA_POWER);
        assertEquals(Double.NaN, result.getBranchFlow1FunctionReferenceValue("l23", "l23"), LoadFlowAssert.DELTA_POWER);
        assertEquals(-1.676d, result.getBranchFlow1FunctionReferenceValue("l23", "l34"), LoadFlowAssert.DELTA_POWER);
        assertEquals(2.324d, result.getBranchFlow1FunctionReferenceValue("l23", "l13"), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void test4busesPhaseShift() {
        SensitivityAnalysisParameters sensiParameters = createParameters(false, "b1_vl_0", true);
        sensiParameters.getLoadFlowParameters().setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX);

        Network network = FourBusNetworkFactory.createWithPhaseTapChangerAndGeneratorAtBus2();
        runLf(network, sensiParameters.getLoadFlowParameters());

        List<SensitivityFactor> factors = network.getBranchStream().map(branch -> createBranchFlowPerPSTAngle(branch.getId(), "l23", "l34", TwoSides.ONE)).collect(Collectors.toList());

        List<Contingency> contingencies = Collections.singletonList(new Contingency("l34", new BranchContingency("l34")));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, contingencies, Collections.emptyList(), sensiParameters);

        assertEquals(5, result.getValues().size());
        assertEquals(0d, result.getBranchFlow1SensitivityValue("l34", "l23", "l14", SensitivityVariableType.TRANSFORMER_PHASE), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.0564d, result.getBranchFlow1SensitivityValue("l34", "l23", "l12", SensitivityVariableType.TRANSFORMER_PHASE), LoadFlowAssert.DELTA_POWER);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("l34", "l23", "l34", SensitivityVariableType.TRANSFORMER_PHASE), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.0564d, result.getBranchFlow1SensitivityValue("l34", "l23", "l13", SensitivityVariableType.TRANSFORMER_PHASE), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.0564d, result.getBranchFlow1SensitivityValue("l34", "l23", "l23", SensitivityVariableType.TRANSFORMER_PHASE), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void test4busesFunctionReferenceWithTransformer() {
        SensitivityAnalysisParameters sensiParameters = createParameters(false, "b1_vl_0", true);
        sensiParameters.getLoadFlowParameters().setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX);

        Network network = FourBusNetworkFactory.createWithPhaseTapChangerAndGeneratorAtBus2();
        runLf(network, sensiParameters.getLoadFlowParameters());

        List<SensitivityFactor> factors = network.getBranchStream().map(branch -> createBranchFlowPerPSTAngle(branch.getId(), "l23", "l34", TwoSides.ONE)).collect(Collectors.toList());

        List<Contingency> contingencies = Collections.singletonList(new Contingency("l34", new BranchContingency("l34")));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, contingencies, Collections.emptyList(), sensiParameters);

        assertEquals(5, result.getValues().size());
        assertEquals(-1.0d, result.getBranchFlow1FunctionReferenceValue("l34", "l14"), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.7319d, result.getBranchFlow1FunctionReferenceValue("l34", "l12"), LoadFlowAssert.DELTA_POWER);
        assertEquals(Double.NaN, result.getBranchFlow1FunctionReferenceValue("l34", "l34"), LoadFlowAssert.DELTA_POWER);
        assertEquals(2.268d, result.getBranchFlow1FunctionReferenceValue("l34", "l13"), LoadFlowAssert.DELTA_POWER);
        assertEquals(1.7319d, result.getBranchFlow1FunctionReferenceValue("l34", "l23"), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void test4busesFunctionReferenceWithTransformerInAdditionalFactors() {
        SensitivityAnalysisParameters sensiParameters = createParameters(false, "b1_vl_0", true);
        sensiParameters.getLoadFlowParameters().setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX);

        Network network = FourBusNetworkFactory.createWithPhaseTapChangerAndGeneratorAtBus2();
        runLf(network, sensiParameters.getLoadFlowParameters());

        List<SensitivityFactor> factors = network.getBranchStream().map(branch -> createBranchFlowPerPSTAngle(branch.getId(), "l23")).collect(Collectors.toList());

        List<Contingency> contingencies = Collections.singletonList(new Contingency("l34", new BranchContingency("l34")));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, contingencies, Collections.emptyList(), sensiParameters);

        assertEquals(5, result.getValues("l34").size());
        assertEquals(-1.0d, result.getBranchFlow1FunctionReferenceValue("l34", "l14"), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.7319d, result.getBranchFlow1FunctionReferenceValue("l34", "l12"), LoadFlowAssert.DELTA_POWER);
        assertEquals(Double.NaN, result.getBranchFlow1FunctionReferenceValue("l34", "l34"), LoadFlowAssert.DELTA_POWER);
        assertEquals(2.268d, result.getBranchFlow1FunctionReferenceValue("l34", "l13"), LoadFlowAssert.DELTA_POWER);
        assertEquals(1.7319d, result.getBranchFlow1FunctionReferenceValue("l34", "l23"), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testMultipleContingencies() {
        SensitivityAnalysisParameters sensiParameters = createParameters(false, "b1_vl_0", true);
        sensiParameters.getLoadFlowParameters().setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX);

        Network network = FourBusNetworkFactory.createWithPhaseTapChangerAndGeneratorAtBus2();
        runLf(network, sensiParameters.getLoadFlowParameters());

        List<SensitivityFactor> factors = createFactorMatrix(List.of(network.getGenerator("g2")),
                                                             network.getBranchStream().collect(Collectors.toList()));

        List<Contingency> contingencies = List.of(new Contingency("l23", new BranchContingency("l23")),
                                                  new Contingency("l34", new BranchContingency("l34")));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, contingencies, Collections.emptyList(), sensiParameters);

        assertEquals(0.1352d, result.getBranchFlow1SensitivityValue("l23", "g2", "l14", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.600d, result.getBranchFlow1SensitivityValue("l23", "g2", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("l23", "g2", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.0648d, result.getBranchFlow1SensitivityValue("l23", "g2", "l34", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.0648d, result.getBranchFlow1SensitivityValue("l23", "g2", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);

        assertEquals(0.2d, result.getBranchFlow1SensitivityValue("l34", "g2", "l14", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.4056d, result.getBranchFlow1SensitivityValue("l34", "g2", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.1944d, result.getBranchFlow1SensitivityValue("l34", "g2", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("l34", "g2", "l34", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.1944d, result.getBranchFlow1SensitivityValue("l34", "g2", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testMultipleContingenciesAdditionalFactors() {
        SensitivityAnalysisParameters sensiParameters = createParameters(false, "b1_vl_0", true);
        sensiParameters.getLoadFlowParameters().setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX);

        Network network = FourBusNetworkFactory.createWithPhaseTapChangerAndGeneratorAtBus2();
        runLf(network, sensiParameters.getLoadFlowParameters());

        List<SensitivityFactor> factors = List.of(createBranchFlowPerInjectionIncrease("l14", "g2"),
                                                  createBranchFlowPerInjectionIncrease("l23", "g2", "l23"),
                                                  createBranchFlowPerInjectionIncrease("l12", "g2", "l23"),
                                                  createBranchFlowPerInjectionIncrease("l23", "g2", "l34"),
                                                  createBranchFlowPerInjectionIncrease("l34", "g2", "l34"),
                                                  createBranchFlowPerInjectionIncrease("l12", "g2", "l34"));

        List<Contingency> contingencies = List.of(new Contingency("l23", new BranchContingency("l23")),
                                                  new Contingency("l34", new BranchContingency("l34")));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, contingencies, Collections.emptyList(), sensiParameters);

        assertEquals(4, result.getValues("l34").size());
        assertEquals(3, result.getValues("l23").size());

        assertEquals(0.1352d, result.getBranchFlow1SensitivityValue("l23", "g2", "l14", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.600d, result.getBranchFlow1SensitivityValue("l23", "g2", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("l23", "g2", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);

        assertEquals(0.2d, result.getBranchFlow1SensitivityValue("l34", "g2", "l14", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.4056d, result.getBranchFlow1SensitivityValue("l34", "g2", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.1944d, result.getBranchFlow1SensitivityValue("l34", "g2", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("l34", "g2", "l34", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testContingencyWithMultipleBranches() {
        SensitivityAnalysisParameters sensiParameters = createParameters(false, "b1_vl_0", true);
        sensiParameters.getLoadFlowParameters().setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX);

        Network network = FourBusNetworkFactory.createWithPhaseTapChangerAndGeneratorAtBus2();
        runLf(network, sensiParameters.getLoadFlowParameters());

        List<SensitivityFactor> factors = createFactorMatrix(List.of(network.getGenerator("g2")),
                                                             network.getBranchStream().collect(Collectors.toList()));

        List<Contingency> contingencies = List.of(new Contingency("l23+l34", new BranchContingency("l23"), new BranchContingency("l34")));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, contingencies, Collections.emptyList(), sensiParameters);

        assertEquals(0.2d, result.getBranchFlow1SensitivityValue("l23+l34", "g2", "l14", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.600d, result.getBranchFlow1SensitivityValue("l23+l34", "g2", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("l23+l34", "g2", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("l23+l34", "g2", "l34", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("l23+l34", "g2", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testConnectivityLossOnSingleLine() {
        Network network = ConnectedComponentNetworkFactory.createTwoComponentWithGeneratorOnOneSide();

        SensitivityAnalysisParameters sensiParameters = createParameters(false, "b1_vl_0", true);
        sensiParameters.getLoadFlowParameters().setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX);

        List<Contingency> contingencies = List.of(new Contingency("l34", new BranchContingency("l34")));

        List<SensitivityFactor> factors = createFactorMatrix(network.getGeneratorStream().collect(Collectors.toList()),
                                                             network.getBranchStream().collect(Collectors.toList()));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, contingencies, Collections.emptyList(), sensiParameters);

        assertEquals(14, result.getValues("l34").size());
        assertEquals(-0.1324d, result.getBranchFlow1SensitivityValue("l34", "g2", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.2676d, result.getBranchFlow1SensitivityValue("l34", "g2", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.1324d, result.getBranchFlow1SensitivityValue("l34", "g2", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("l34", "g2", "l34", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("l34", "g2", "l45", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("l34", "g2", "l46", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("l34", "g2", "l56", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);

        assertEquals(0.1986d, result.getBranchFlow1SensitivityValue("l34", "g3", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.4013d, result.getBranchFlow1SensitivityValue("l34", "g3", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.1986d, result.getBranchFlow1SensitivityValue("l34", "g3", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("l34", "g3", "l34", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("l34", "g3", "l45", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("l34", "g3", "l46", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("l34", "g3", "l56", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);

        assertEquals(-1.662, result.getBranchFlow1FunctionReferenceValue("l12"), LoadFlowAssert.DELTA_POWER);
        assertEquals(-1.066, result.getBranchFlow1FunctionReferenceValue("l34", "l12"), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testContingencyMultipleLinesBreaksOneContingency() {
        Network network = ConnectedComponentNetworkFactory.createTwoCcLinkedByTwoLinesWithAdditionnalGens();

        SensitivityAnalysisParameters sensiParameters = createParameters(false, "b1_vl_0", true);
        sensiParameters.getLoadFlowParameters().setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX);

        List<Contingency> contingencies = List.of(new Contingency("l24+l35", new BranchContingency("l24"), new BranchContingency("l35")));

        List<SensitivityFactor> factors = createFactorMatrix(network.getGeneratorStream().collect(Collectors.toList()),
                                                             network.getBranchStream().collect(Collectors.toList()),
                                                             "l24+l35");

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, contingencies, Collections.emptyList(), sensiParameters);

        assertEquals(24, result.getValues("l24+l35").size());
        assertEquals(-0.1331d, result.getBranchFlow1SensitivityValue("l24+l35", "g2", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.2669d, result.getBranchFlow1SensitivityValue("l24+l35", "g2", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.1331d, result.getBranchFlow1SensitivityValue("l24+l35", "g2", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("l24+l35", "g2", "l24", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("l24+l35", "g2", "l35", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("l24+l35", "g2", "l45", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("l24+l35", "g2", "l46", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("l24+l35", "g2", "l56", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);

        assertEquals(0.1997d, result.getBranchFlow1SensitivityValue("l24+l35", "g3", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.4003d, result.getBranchFlow1SensitivityValue("l24+l35", "g3", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.1997d, result.getBranchFlow1SensitivityValue("l24+l35", "g3", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("l24+l35", "g3", "l24", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("l24+l35", "g3", "l35", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("l24+l35", "g3", "l45", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("l24+l35", "g3", "l46", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("l24+l35", "g3", "l56", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);

        assertEquals(0d, result.getBranchFlow1SensitivityValue("l24+l35", "g6", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("l24+l35", "g6", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("l24+l35", "g6", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(Double.NaN, result.getBranchFlow1SensitivityValue("l24+l35", "g6", "l24", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(Double.NaN, result.getBranchFlow1SensitivityValue("l24+l35", "g6", "l35", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(Double.NaN, result.getBranchFlow1SensitivityValue("l24+l35", "g6", "l45", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(Double.NaN, result.getBranchFlow1SensitivityValue("l24+l35", "g6", "l46", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(Double.NaN, result.getBranchFlow1SensitivityValue("l24+l35", "g6", "l56", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testGlskRescale() {
        Network network = ConnectedComponentNetworkFactory.createTwoComponentWithGeneratorAndLoad();

        SensitivityAnalysisParameters sensiParameters = createParameters(false, "b1_vl_0", true);
        sensiParameters.getLoadFlowParameters().setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD);

        List<Contingency> contingencies = List.of(new Contingency("l34", new BranchContingency("l34")),
                                                  new Contingency("l12", new BranchContingency("l12")));

        List<WeightedSensitivityVariable> variables = List.of(new WeightedSensitivityVariable("g2", 0.4f),
                                                              new WeightedSensitivityVariable("g6", 0.6f));
        List<SensitivityVariableSet> variableSets = List.of(new SensitivityVariableSet("glsk", variables));

        List<SensitivityFactor> factors = network.getBranchStream()
                                                 .map(branch -> createBranchFlowPerLinearGlsk(branch.getId(), "glsk", "l34", TwoSides.ONE))
                                                 .collect(Collectors.toList());
        factors.addAll(network.getBranchStream().map(branch -> createBranchFlowPerLinearGlsk(branch.getId(), "glsk", "l12", TwoSides.ONE)).collect(Collectors.toList()));
        SensitivityAnalysisResult result = sensiRunner.run(network, factors, contingencies, variableSets, sensiParameters);

        assertEquals(7, result.getValues("l34").size());

        assertEquals(-0.5d, result.getBranchFlow1SensitivityValue("l34", "glsk", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.5d, result.getBranchFlow1SensitivityValue("l34", "glsk", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("l34", "glsk", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("l34", "glsk", "l34", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("l34", "glsk", "l45", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("l34", "glsk", "l46", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("l34", "glsk", "l56", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);

        assertEquals(7, result.getValues("l12").size());
        assertEquals(0.0, result.getBranchFlow1SensitivityValue("l12", "glsk", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.4, result.getBranchFlow1SensitivityValue("l12", "glsk", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.2, result.getBranchFlow1SensitivityValue("l12", "glsk", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("l12", "glsk", "l34", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.064, result.getBranchFlow1SensitivityValue("l12", "glsk", "l45", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.264, result.getBranchFlow1SensitivityValue("l12", "glsk", "l46", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.336, result.getBranchFlow1SensitivityValue("l12", "glsk", "l56", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testGlskRescaleAdditionalFactor() {
        Network network = ConnectedComponentNetworkFactory.createTwoComponentWithGeneratorAndLoad();

        SensitivityAnalysisParameters sensiParameters = createParameters(false, "b1_vl_0", true);
        sensiParameters.getLoadFlowParameters().setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD);

        List<Contingency> contingencies = List.of(new Contingency("l34", new BranchContingency("l34")));

        List<WeightedSensitivityVariable> variables = List.of(new WeightedSensitivityVariable("g2", 0.4f),
                                                              new WeightedSensitivityVariable("g6", 0.6f));
        List<SensitivityVariableSet> variableSets = List.of(new SensitivityVariableSet("glsk", variables));

        List<SensitivityFactor> factors = network.getBranchStream()
                                                 .map(branch -> createBranchFlowPerLinearGlsk(branch.getId(), "glsk", "l34", TwoSides.ONE))
                                                 .collect(Collectors.toList());

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, contingencies, variableSets, sensiParameters);

        assertEquals(7, result.getValues("l34").size());
        assertEquals(-0.5d, result.getBranchFlow1SensitivityValue("l34", "glsk", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.5d, result.getBranchFlow1SensitivityValue("l34", "glsk", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("l34", "glsk", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("l34", "glsk", "l34", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("l34", "glsk", "l45", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("l34", "glsk", "l46", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0d, result.getBranchFlow1SensitivityValue("l34", "glsk", "l56", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testInjectionNotFoundAdditionalFactorContingency() {
        testInjectionNotFoundAdditionalFactorContingency(false);
    }

    @Test
    void testBusVoltagePerTargetV() {
        Network network = ConnectedComponentNetworkFactory.createThreeCcLinkedByASingleBus();

        SensitivityAnalysisParameters sensiParameters = createParameters(false, "b1_vl_0", true);
        sensiParameters.getLoadFlowParameters().setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX);

        List<Contingency> contingencies = Collections.singletonList(new Contingency("l45", new BranchContingency("l45")));

        List<String> busIds = new ArrayList<>(10);
        for (int i = 1; i <= 10; i++) {
            busIds.add("b" + i);
        }
        List<SensitivityFactor> factors = busIds.stream()
                                                .map(bus -> createBusVoltagePerTargetV(bus, "g2"))
                                                .collect(Collectors.toList());

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, contingencies, Collections.emptyList(), sensiParameters);

        assertEquals(0.916d, result.getBusVoltageSensitivityValue("l45", "g2", busIds.get(0), SensitivityVariableType.BUS_TARGET_VOLTAGE), LoadFlowAssert.DELTA_V); // 0 on the slack
        assertEquals(1d, result.getBusVoltageSensitivityValue("l45", "g2", busIds.get(1), SensitivityVariableType.BUS_TARGET_VOLTAGE), LoadFlowAssert.DELTA_V); // 1 on itself
        assertEquals(0.8133d, result.getBusVoltageSensitivityValue("l45", "g2", busIds.get(2), SensitivityVariableType.BUS_TARGET_VOLTAGE), LoadFlowAssert.DELTA_V);
        assertEquals(0.512d, result.getBusVoltageSensitivityValue("l45", "g2", busIds.get(3), SensitivityVariableType.BUS_TARGET_VOLTAGE), LoadFlowAssert.DELTA_V);
        assertEquals(0d, result.getBusVoltageSensitivityValue("l45", "g2", busIds.get(4), SensitivityVariableType.BUS_TARGET_VOLTAGE), LoadFlowAssert.DELTA_V); // disconnected
        assertEquals(0d, result.getBusVoltageSensitivityValue("l45", "g2", busIds.get(5), SensitivityVariableType.BUS_TARGET_VOLTAGE), LoadFlowAssert.DELTA_V); // disconnected
        assertEquals(0d, result.getBusVoltageSensitivityValue("l45", "g2", busIds.get(6), SensitivityVariableType.BUS_TARGET_VOLTAGE), LoadFlowAssert.DELTA_V); // disconnected
        assertEquals(0.209d, result.getBusVoltageSensitivityValue("l45", "g2", busIds.get(7), SensitivityVariableType.BUS_TARGET_VOLTAGE), LoadFlowAssert.DELTA_V);
        assertEquals(0.1062d, result.getBusVoltageSensitivityValue("l45", "g2", busIds.get(8), SensitivityVariableType.BUS_TARGET_VOLTAGE), LoadFlowAssert.DELTA_V);
        assertEquals(0d, result.getBusVoltageSensitivityValue("l45", "g2", busIds.get(9), SensitivityVariableType.BUS_TARGET_VOLTAGE), LoadFlowAssert.DELTA_V); // no impact on a pv
    }

    @Test
    void testBusVoltagePerTargetVFunctionRef() {
        Network network = ConnectedComponentNetworkFactory.createThreeCcLinkedByASingleBus();

        SensitivityAnalysisParameters sensiParameters = createParameters(false, "b1_vl_0", true);
        sensiParameters.getLoadFlowParameters().setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX);

        List<Contingency> contingencies = Collections.singletonList(new Contingency("l45", new BranchContingency("l45")));

        List<String> busIds = new ArrayList<>(10);
        for (int i = 1; i <= 10; i++) {
            busIds.add("b" + i);
        }
        List<SensitivityFactor> factors = busIds.stream()
                                                .map(bus -> createBusVoltagePerTargetV(bus, "g2"))
                                                .collect(Collectors.toList());

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, contingencies, Collections.emptyList(), sensiParameters);

        assertEquals(0.993d, result.getBusVoltageFunctionReferenceValue("l45", busIds.get(0)), LoadFlowAssert.DELTA_V);
        assertEquals(1d, result.getBusVoltageFunctionReferenceValue("l45", busIds.get(1)), LoadFlowAssert.DELTA_V);
        assertEquals(0.992d, result.getBusVoltageFunctionReferenceValue("l45", busIds.get(2)), LoadFlowAssert.DELTA_V);
        assertEquals(0.988d, result.getBusVoltageFunctionReferenceValue("l45", busIds.get(3)), LoadFlowAssert.DELTA_V);
        assertEquals(Double.NaN, result.getBusVoltageFunctionReferenceValue("l45", busIds.get(4)), LoadFlowAssert.DELTA_V); // disconnected
        assertEquals(Double.NaN, result.getBusVoltageFunctionReferenceValue("l45", busIds.get(5)), LoadFlowAssert.DELTA_V); // disconnected
        assertEquals(Double.NaN, result.getBusVoltageFunctionReferenceValue("l45", busIds.get(6)), LoadFlowAssert.DELTA_V); // disconnected
        assertEquals(0.987d, result.getBusVoltageFunctionReferenceValue("l45", busIds.get(7)), LoadFlowAssert.DELTA_V);
        assertEquals(0.989d, result.getBusVoltageFunctionReferenceValue("l45", busIds.get(8)), LoadFlowAssert.DELTA_V);
        assertEquals(1d, result.getBusVoltageFunctionReferenceValue("l45", busIds.get(9)), LoadFlowAssert.DELTA_V);
    }

    @Test
    void testHvdcSensiRescale() {
        // test injection increase on loads
        Network network = HvdcNetworkFactory.createNetworkWithGenerators();

        SensitivityAnalysisParameters sensiParameters = createParameters(false, "b1_vl_0", true);
        sensiParameters.getLoadFlowParameters().getExtension(OpenLoadFlowParameters.class).setSlackBusSelectionMode(SlackBusSelectionMode.MOST_MESHED);

        Network network1 = HvdcNetworkFactory.createNetworkWithGenerators();
        network1.getLine("l25").getTerminal1().disconnect();
        network1.getLine("l25").getTerminal2().disconnect();
        runLf(network1, sensiParameters.getLoadFlowParameters());

        Network network2 = HvdcNetworkFactory.createNetworkWithGenerators();
        network2.getHvdcLine("hvdc34").setActivePowerSetpoint(network1.getHvdcLine("hvdc34").getActivePowerSetpoint() + SENSI_CHANGE);
        network2.getLine("l25").getTerminal1().disconnect();
        network2.getLine("l25").getTerminal2().disconnect();
        runLf(network2, sensiParameters.getLoadFlowParameters());

        Map<String, Double> loadFlowDiff =
                network.getLineStream().map(line -> line.getId())
                        .collect(Collectors.toMap(lineId -> lineId, line -> (network1.getLine(line).getTerminal1().getP() - network2.getLine(line).getTerminal1().getP()) / SENSI_CHANGE));

        List<SensitivityFactor> factors = SensitivityFactor.createMatrix(SensitivityFunctionType.BRANCH_ACTIVE_POWER_1, List.of("l12", "l13", "l23", "l25", "l45", "l46", "l56"),
                                                                         SensitivityVariableType.HVDC_LINE_ACTIVE_POWER, List.of("hvdc34"),
                                                                         false, ContingencyContext.all());

        sensiParameters.getLoadFlowParameters().getExtension(OpenLoadFlowParameters.class)
                       .setSlackBusSelectionMode(SlackBusSelectionMode.NAME)
                       .setSlackBusId("b1_vl_0"); // the most meshed bus selected in the loadflow

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, Collections.singletonList(new Contingency("l25", new BranchContingency("l25"))), Collections.emptyList(), sensiParameters);

        // FIXME
        // assertEquals(loadFlowDiff.get("l12"), result.getBranchFlow1SensitivityValue("l25", "hvdc34", "l12", SensitivityVariableType.HVDC_LINE_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        // assertEquals(loadFlowDiff.get("l13"), result.getBranchFlow1SensitivityValue("l25", "hvdc34", "l13", SensitivityVariableType.HVDC_LINE_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        // assertEquals(loadFlowDiff.get("l23"), result.getBranchFlow1SensitivityValue("l25", "hvdc34", "l23", SensitivityVariableType.HVDC_LINE_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.0, result.getBranchFlow1SensitivityValue("l25", "hvdc34", "l45", SensitivityVariableType.HVDC_LINE_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER); // should be loadFlowDiff.get("l45")
        assertEquals(0.0, result.getBranchFlow1SensitivityValue("l25", "hvdc34", "l46", SensitivityVariableType.HVDC_LINE_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER); // should be loadFlowDiff.get("l46")
        assertEquals(0.0, result.getBranchFlow1SensitivityValue("l25", "hvdc34", "l56", SensitivityVariableType.HVDC_LINE_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER); // should be loadFlowDiff.get("l56")
        assertEquals(0.0, result.getBranchFlow1SensitivityValue("l25", "hvdc34", "l25", SensitivityVariableType.HVDC_LINE_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testContingencyPropagationLfSwitch() {
        SensitivityAnalysisParameters sensiParameters = createParameters(false);
        sensiParameters.getLoadFlowParameters().setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX);

        Network network = NodeBreakerNetworkFactory.create3Bars();
        runLf(network, sensiParameters.getLoadFlowParameters());

        List<SensitivityFactor> factors = createFactorMatrix(network.getGeneratorStream().collect(Collectors.toList()),
                                                             network.getBranchStream().collect(Collectors.toList()));

        List<Contingency> contingencies = List.of(new Contingency("L2", new BranchContingency("L2")));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, contingencies, Collections.emptyList(), sensiParameters);

        assertEquals(200.794, result.getBranchFlow1FunctionReferenceValue("L1"), LoadFlowAssert.DELTA_POWER);
        assertEquals(200.794, result.getBranchFlow1FunctionReferenceValue("L2"), LoadFlowAssert.DELTA_POWER);
        assertEquals(200.794, result.getBranchFlow1FunctionReferenceValue("L3"), LoadFlowAssert.DELTA_POWER);

        // Propagating contingency on L2 encounters a coupler, which is not (yet) supported in sensitivity analysis
        assertEquals(301.864, result.getBranchFlow1FunctionReferenceValue("L2", "L1"), LoadFlowAssert.DELTA_POWER);
        assertEquals(Double.NaN, result.getBranchFlow1FunctionReferenceValue("L2", "L2"), LoadFlowAssert.DELTA_POWER);
        assertEquals(301.864, result.getBranchFlow1FunctionReferenceValue("L2", "L3"), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testDanglingLineContingencyDistributedSlackOnLoads() {
        Network network = BoundaryFactory.createWithLoad();

        SensitivityAnalysisParameters sensiParameters = createParameters(false, "vl3_0", true);
        sensiParameters.getLoadFlowParameters().setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD);

        List<SensitivityFactor> factors = List.of(createBranchFlowPerInjectionIncrease("l1", "g1"));

        List<Contingency> contingencies = List.of(new Contingency("dl1", new DanglingLineContingency("dl1")));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, contingencies, Collections.emptyList(), sensiParameters);

        assertEquals(1, result.getPreContingencyValues().size());
        assertEquals(0.3697, result.getBranchFlow1SensitivityValue("g1", "l1", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_SENSITIVITY_VALUE);
        assertEquals(75.272, result.getBranchFlow1FunctionReferenceValue("l1"), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.3695, result.getBranchFlow1SensitivityValue("dl1", "g1", "l1", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_SENSITIVITY_VALUE);
        assertEquals(36.794, result.getBranchFlow1FunctionReferenceValue("dl1", "l1"), LoadFlowAssert.DELTA_POWER);

        network.getDanglingLine("dl1").getTerminal().disconnect();

        LoadFlowParameters parameters = sensiParameters.getLoadFlowParameters();
        parameters.getExtension(OpenLoadFlowParameters.class).setSlackBusPMaxMismatch(0.001);
        runLf(network, parameters, ReportNode.NO_OP);

        Line l1 = network.getLine("l1");
        double initialP = l1.getTerminal1().getP();
        assertEquals(36.795, initialP, LoadFlowAssert.DELTA_POWER);

        network.getGenerator("g1").setTargetP(network.getGenerator("g1").getTargetP() + 1);

        runLf(network, sensiParameters.getLoadFlowParameters(), ReportNode.NO_OP);

        double finalP = l1.getTerminal1().getP();
        assertEquals(37.164, finalP, LoadFlowAssert.DELTA_POWER);
        assertEquals(0.3695, finalP - initialP, LoadFlowAssert.DELTA_SENSITIVITY_VALUE);
    }

    @Test
    void testDanglingLineContingencyDistributedSlackOnGenerators() {
        Network network = BoundaryFactory.createWithLoad();

        SensitivityAnalysisParameters sensiParameters = createParameters(false, "vl1_0", true);
        sensiParameters.getLoadFlowParameters().setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX);

        List<SensitivityFactor> factors = List.of(createBranchFlowPerInjectionIncrease("l1", "load3"));

        List<Contingency> contingencies = List.of(new Contingency("dl1", new DanglingLineContingency("dl1")));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, contingencies, Collections.emptyList(), sensiParameters);

        assertEquals(1, result.getPreContingencyValues().size());
        assertEquals(-0.3704, result.getBranchFlow1SensitivityValue("load3", "l1", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_SENSITIVITY_VALUE);
        assertEquals(75.336, result.getBranchFlow1FunctionReferenceValue("l1"), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.3704, result.getBranchFlow1SensitivityValue("dl1", "load3", "l1", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_SENSITIVITY_VALUE);
        assertEquals(3.0071, result.getBranchFlow1FunctionReferenceValue("dl1", "l1"), LoadFlowAssert.DELTA_POWER);

        network.getDanglingLine("dl1").getTerminal().disconnect();

        LoadFlowParameters parameters = sensiParameters.getLoadFlowParameters();
        parameters.getExtension(OpenLoadFlowParameters.class).setSlackBusPMaxMismatch(0.001);
        runLf(network, parameters, ReportNode.NO_OP);

        Line l1 = network.getLine("l1");
        double initialP = l1.getTerminal1().getP();
        assertEquals(3.0071, initialP, LoadFlowAssert.DELTA_POWER);

        network.getLoad("load3").setP0(network.getLoad("load3").getP0() + 1);
        runLf(network, sensiParameters.getLoadFlowParameters(), ReportNode.NO_OP);

        double finalP = l1.getTerminal1().getP();
        assertEquals(3.3775, finalP, LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.3704, initialP - finalP, LoadFlowAssert.DELTA_SENSITIVITY_VALUE);
    }

    @Test
    void testEurostagFactory() {
        Network network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());

        SensitivityAnalysisParameters sensiParameters = createParameters(false, "VLGEN_0", true);
        sensiParameters.getLoadFlowParameters().setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX);

        List<SensitivityFactor> factors = network.getBusBreakerView().getBusStream()
                                                 .map(bus -> createBusVoltagePerTargetV(bus.getId(), "NHV2_NLOAD"))
                                                 .collect(Collectors.toList());

        List<Contingency> contingencies = List.of(new Contingency("NHV1_NHV2_2", new BranchContingency("NHV1_NHV2_2")));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, contingencies, Collections.emptyList(), sensiParameters);

        assertEquals(4, result.getPreContingencyValues().size());
        assertEquals(0d, result.getBusVoltageSensitivityValue("NHV2_NLOAD", "NGEN", SensitivityVariableType.BUS_TARGET_VOLTAGE), LoadFlowAssert.DELTA_V);
        assertEquals(0.035205d, result.getBusVoltageSensitivityValue("NHV2_NLOAD", "NHV1", SensitivityVariableType.BUS_TARGET_VOLTAGE), LoadFlowAssert.DELTA_V);
        assertEquals(0.077194d, result.getBusVoltageSensitivityValue("NHV2_NLOAD", "NHV2", SensitivityVariableType.BUS_TARGET_VOLTAGE), LoadFlowAssert.DELTA_V);
        assertEquals(1.0d, result.getBusVoltageSensitivityValue("NHV2_NLOAD", "NLOAD", SensitivityVariableType.BUS_TARGET_VOLTAGE), LoadFlowAssert.DELTA_V);

        assertEquals(0d, result.getBusVoltageSensitivityValue("NHV1_NHV2_2", "NHV2_NLOAD", "NGEN", SensitivityVariableType.BUS_TARGET_VOLTAGE), LoadFlowAssert.DELTA_V);
        assertEquals(0.026329d, result.getBusVoltageSensitivityValue("NHV1_NHV2_2", "NHV2_NLOAD", "NHV1", SensitivityVariableType.BUS_TARGET_VOLTAGE), LoadFlowAssert.DELTA_V);
        assertEquals(0.103981d, result.getBusVoltageSensitivityValue("NHV1_NHV2_2", "NHV2_NLOAD", "NHV2", SensitivityVariableType.BUS_TARGET_VOLTAGE), LoadFlowAssert.DELTA_V);
        assertEquals(1.0d, result.getBusVoltageSensitivityValue("NHV1_NHV2_2", "NHV2_NLOAD", "NLOAD", SensitivityVariableType.BUS_TARGET_VOLTAGE), LoadFlowAssert.DELTA_V);
    }

    @Test
    void testGlskOutsideMainComponentWithContingency() {
        Network network = HvdcNetworkFactory.createLccWithBiggerComponentsAndAdditionalLine();

        SensitivityAnalysisParameters sensiParameters = createParameters(false, "vl1_0");

        List<SensitivityFactor> factors = List.of(createBranchFlowPerLinearGlsk("l12", "glsk"));

        List<SensitivityVariableSet> variableSets = List.of(new SensitivityVariableSet("glsk", List.of(new WeightedSensitivityVariable("g6", 1f),
                                                                                                       new WeightedSensitivityVariable("g3", 2f))));

        List<Contingency> contingencies = List.of(new Contingency("additionnalline_0", new BranchContingency("additionnalline_0")));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, contingencies, variableSets, sensiParameters);

        assertEquals(2, result.getValues().size());
        assertEquals(0, result.getBranchFlow1SensitivityValue("glsk", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);

        assertEquals(100.080, result.getBranchFlow1FunctionReferenceValue("l12"), LoadFlowAssert.DELTA_POWER);
        assertEquals(100.080, result.getBranchFlow1FunctionReferenceValue("additionnalline_0", "l12"), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void test4busesPhaseShiftIntensityFunctionReference() {
        Network network = FourBusNetworkFactory.createWithPhaseTapChangerAndGeneratorAtBus2();

        SensitivityAnalysisParameters sensiParameters = createParameters(false, "b1_vl_0", true);
        sensiParameters.getLoadFlowParameters().setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX);

        List<SensitivityFactor> factors = network.getBranchStream().map(branch -> createBranchIntensityPerPSTAngle(branch.getId(), "l23")).collect(Collectors.toList());

        List<Contingency> contingencies = List.of(new Contingency("l23", new BranchContingency("l23")));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, contingencies, Collections.emptyList(), sensiParameters);

        assertEquals(Double.NaN, result.getFunctionReferenceValue("l23", "l23", SensitivityFunctionType.BRANCH_CURRENT_1), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.0, result.getBranchCurrent1SensitivityValue("l23", "l23", "l12", SensitivityVariableType.TRANSFORMER_PHASE), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testLosingALineButBothEndsInMainComponent() {
        Network network = ConnectedComponentNetworkFactory.createTwoComponentWithGeneratorOnOneSide();

        SensitivityAnalysisParameters sensiParameters = createParameters(false, "b3_vl_0", false);

        List<Contingency> contingencies = List.of(new Contingency("l34+l12", new BranchContingency("l34"), new BranchContingency("l12")));

        List<SensitivityFactor> factors = createFactorMatrix(List.of(network.getGenerator("g3")),
                List.of(network.getLine("l12")),
                "l34+l12");

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, contingencies, Collections.emptyList(), sensiParameters);

        assertEquals(1, result.getValues("l34+l12").size());

        assertEquals(0.0, result.getBranchFlow1SensitivityValue("l34+l12", "g3", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(Double.NaN, result.getBranchFlow1FunctionReferenceValue("l34+l12", "l12"), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testTrivialContingencyOnGenerator() {
        Network network = ConnectedComponentNetworkFactory.createTwoCcLinkedByTwoLines();

        SensitivityAnalysisParameters sensiParameters = createParameters(false, "b1", true);
        sensiParameters.setLoadFlowParameters(new LoadFlowParameters()
                .setDc(false)
                .setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX));

        List<SensitivityFactor> factors = createFactorMatrix(Stream.of("g2").map(network::getGenerator).collect(Collectors.toList()),
                Stream.of("l12", "l13", "l23").map(network::getBranch).collect(Collectors.toList()));

        List<Contingency> contingencies = List.of(new Contingency("g6", new GeneratorContingency("g6")));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, contingencies, Collections.emptyList(), sensiParameters);

        assertEquals(3, result.getValues("g6").size());
        assertEquals(0, result.getBranchFlow1SensitivityValue("g6", "g2", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0, result.getBranchFlow1SensitivityValue("g6", "g2", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0, result.getBranchFlow1SensitivityValue("g6", "g2", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);

        assertEquals(-1.3365d, result.getBranchFlow1FunctionReferenceValue("g6", "l12"), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.3364d, result.getBranchFlow1FunctionReferenceValue("g6", "l13"), LoadFlowAssert.DELTA_POWER);
        assertEquals(1.6664, result.getBranchFlow1FunctionReferenceValue("g6", "l23"), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testContingencyOnLoad() {
        Network network = ConnectedComponentNetworkFactory.createTwoCcLinkedByTwoLines();

        SensitivityAnalysisParameters sensiParameters = createParameters(false, "b1", false);
        sensiParameters.setLoadFlowParameters(new LoadFlowParameters()
                .setDc(false)
                .setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD));

        List<SensitivityFactor> factors = createFactorMatrix(Stream.of("g2").map(network::getGenerator).collect(Collectors.toList()),
                Stream.of("l12", "l13", "l23").map(network::getBranch).collect(Collectors.toList()));

        List<Contingency> contingencies = List.of(new Contingency("d5", new LoadContingency("d5")));
        SensitivityAnalysisResult result = sensiRunner.run(network, factors, contingencies, Collections.emptyList(), sensiParameters);

        network.getLoad("d5").getTerminal().disconnect();
        SensitivityAnalysisResult result2 = sensiRunner.run(network, factors, Collections.emptyList(), Collections.emptyList(), sensiParameters);

        assertEquals(3, result.getValues("d5").size());
        assertEquals(result2.getBranchFlow1SensitivityValue("g2", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), result.getBranchFlow1SensitivityValue("d5", "g2", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(result2.getBranchFlow1SensitivityValue("g2", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), result.getBranchFlow1SensitivityValue("d5", "g2", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(result2.getBranchFlow1SensitivityValue("g2", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), result.getBranchFlow1SensitivityValue("d5", "g2", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);

        assertEquals(result2.getBranchFlow1FunctionReferenceValue(null, "l12"), result.getBranchFlow1FunctionReferenceValue("d5", "l12"), LoadFlowAssert.DELTA_POWER);
        assertEquals(result2.getBranchFlow1FunctionReferenceValue(null, "l13"), result.getBranchFlow1FunctionReferenceValue("d5", "l13"), LoadFlowAssert.DELTA_POWER);
        assertEquals(result2.getBranchFlow1FunctionReferenceValue(null, "l23"), result.getBranchFlow1FunctionReferenceValue("d5", "l23"), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testGLSK() {
        Network network = FourBusNetworkFactory.create();

        SensitivityAnalysisParameters sensiParameters = createParameters(false, "b1_vl_0", true);

        List<WeightedSensitivityVariable> variables = List.of(new WeightedSensitivityVariable("d2", 30f),
                new WeightedSensitivityVariable("g2", 10f),
                new WeightedSensitivityVariable("d3", 50f),
                new WeightedSensitivityVariable("g1", 10f));
        List<SensitivityVariableSet> variableSets = Collections.singletonList(new SensitivityVariableSet("glsk", variables));

        List<SensitivityFactor> factors = network.getBranchStream().map(branch -> createBranchFlowPerLinearGlsk(branch.getId(), "glsk")).collect(Collectors.toList());

        List<Contingency> contingencies = List.of(new Contingency("g1", new GeneratorContingency("g1")));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, contingencies, variableSets, sensiParameters);

        network.getGenerator("g1").getTerminal().disconnect();
        SensitivityAnalysisResult result2 = sensiRunner.run(network, factors, Collections.emptyList(), variableSets, sensiParameters);

        assertEquals(result2.getBranchFlow1SensitivityValue("glsk", "l14", SensitivityVariableType.INJECTION_ACTIVE_POWER), result.getBranchFlow1SensitivityValue("g1", "glsk", "l14", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(result2.getBranchFlow1SensitivityValue("glsk", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), result.getBranchFlow1SensitivityValue("g1", "glsk", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(result2.getBranchFlow1SensitivityValue("glsk", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), result.getBranchFlow1SensitivityValue("g1", "glsk", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(result2.getBranchFlow1SensitivityValue("glsk", "l34", SensitivityVariableType.INJECTION_ACTIVE_POWER), result.getBranchFlow1SensitivityValue("g1", "glsk", "l34", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(result2.getBranchFlow1SensitivityValue("glsk", "l34", SensitivityVariableType.INJECTION_ACTIVE_POWER), result.getBranchFlow1SensitivityValue("g1", "glsk", "l34", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(result2.getBranchFlow1SensitivityValue("glsk", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), result.getBranchFlow1SensitivityValue("g1", "glsk", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(result2.getBranchFlow1FunctionReferenceValue(null, "l14"), result.getBranchFlow1FunctionReferenceValue("g1", "l14"), LoadFlowAssert.DELTA_POWER);
        assertEquals(result2.getBranchFlow1FunctionReferenceValue(null, "l12"), result.getBranchFlow1FunctionReferenceValue("g1", "l12"), LoadFlowAssert.DELTA_POWER);
        assertEquals(result2.getBranchFlow1FunctionReferenceValue(null, "l23"), result.getBranchFlow1FunctionReferenceValue("g1", "l23"), LoadFlowAssert.DELTA_POWER);
        assertEquals(result2.getBranchFlow1FunctionReferenceValue(null, "l34"), result.getBranchFlow1FunctionReferenceValue("g1", "l34"), LoadFlowAssert.DELTA_POWER);
        assertEquals(result2.getBranchFlow1FunctionReferenceValue(null, "l34"), result.getBranchFlow1FunctionReferenceValue("g1", "l34"), LoadFlowAssert.DELTA_POWER);
        assertEquals(result2.getBranchFlow1FunctionReferenceValue(null, "l13"), result.getBranchFlow1FunctionReferenceValue("g1", "l13"), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testContingencyOnBranch() {
        Network network = ConnectedComponentNetworkFactory.createTwoCcLinkedByTwoLines();

        SensitivityAnalysisParameters sensiParameters = createParameters(false, "b1", true);
        sensiParameters.setLoadFlowParameters(new LoadFlowParameters()
                .setDc(false)
                .setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD));

        List<SensitivityFactor> factors = createFactorMatrix(Stream.of("g2").map(network::getGenerator).collect(Collectors.toList()),
                Stream.of("l12", "l13").map(network::getBranch).collect(Collectors.toList()));

        List<Contingency> contingencies = List.of(new Contingency("l23", new BranchContingency("l23")));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, contingencies, Collections.emptyList(), sensiParameters);
        assertEquals(SensitivityAnalysisResult.Status.SUCCESS, result.getContingencyStatus("l23"));

        network.getLine("l23").getTerminal1().disconnect();
        network.getLine("l23").getTerminal2().disconnect();
        SensitivityAnalysisResult result2 = sensiRunner.run(network, factors, Collections.emptyList(), Collections.emptyList(), sensiParameters);

        assertEquals(result2.getBranchFlow1SensitivityValue("g2", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), result.getBranchFlow1SensitivityValue("l23", "g2", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(result2.getBranchFlow1SensitivityValue("g2", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), result.getBranchFlow1SensitivityValue("l23", "g2", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(result2.getBranchFlow1FunctionReferenceValue(null, "l12"), result.getBranchFlow1FunctionReferenceValue("l23", "l12"), LoadFlowAssert.DELTA_POWER);
        assertEquals(result2.getBranchFlow1FunctionReferenceValue(null, "l13"), result.getBranchFlow1FunctionReferenceValue("l23", "l13"), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testContingencyOnHvdcLcc() {
        Network network = HvdcNetworkFactory.createTwoCcLinkedByAHvdcWithGenerators();
        network.getGeneratorStream().forEach(gen -> gen.setMaxP(gen.getMaxP() + 1));

        SensitivityAnalysisParameters sensiParameters = createParameters(false, "b1_vl_0", true);
        sensiParameters.getLoadFlowParameters().getExtension(OpenLoadFlowParameters.class).setSlackBusPMaxMismatch(0.001);

        List<SensitivityFactor> factors = createFactorMatrix(Stream.of("g1", "g2").map(network::getGenerator).collect(Collectors.toList()),
                Stream.of("l12", "l13", "l23").map(network::getBranch).collect(Collectors.toList()));

        List<Contingency> contingencies = List.of(new Contingency("hvdc34", new HvdcLineContingency("hvdc34")));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, contingencies, Collections.emptyList(), sensiParameters);

        network.getHvdcLine("hvdc34").getConverterStation1().getTerminal().disconnect();
        network.getHvdcLine("hvdc34").getConverterStation2().getTerminal().disconnect();
        SensitivityAnalysisResult result2 = sensiRunner.run(network, factors, Collections.emptyList(), Collections.emptyList(), sensiParameters);

        List<SensitivityValue> contingencyResult = result.getValues("hvdc34");
        assertEquals(6, contingencyResult.size());
        assertEquals(result2.getBranchFlow1SensitivityValue("g1", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), result.getBranchFlow1SensitivityValue("hvdc34", "g1", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(result2.getBranchFlow1SensitivityValue("g1", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), result.getBranchFlow1SensitivityValue("hvdc34", "g1", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(result2.getBranchFlow1SensitivityValue("g1", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), result.getBranchFlow1SensitivityValue("hvdc34", "g1", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(result2.getBranchFlow1SensitivityValue("g2", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), result.getBranchFlow1SensitivityValue("hvdc34", "g2", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(result2.getBranchFlow1SensitivityValue("g2", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), result.getBranchFlow1SensitivityValue("hvdc34", "g2", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(result2.getBranchFlow1SensitivityValue("g2", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), result.getBranchFlow1SensitivityValue("hvdc34", "g2", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);

        assertEquals(result2.getBranchFlow1FunctionReferenceValue(null, "l12"), result.getBranchFlow1FunctionReferenceValue("hvdc34", "l12"), LoadFlowAssert.DELTA_POWER);
        assertEquals(result2.getBranchFlow1FunctionReferenceValue(null, "l13"), result.getBranchFlow1FunctionReferenceValue("hvdc34", "l13"), LoadFlowAssert.DELTA_POWER);
        assertEquals(result2.getBranchFlow1FunctionReferenceValue(null, "l23"), result.getBranchFlow1FunctionReferenceValue("hvdc34", "l23"), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testContingencyOnHvdcVsc() {
        Network network = HvdcNetworkFactory.createTwoCcLinkedByAHvdcVscWithGenerators();

        SensitivityAnalysisParameters sensiParameters = createParameters(false, "b1_vl_0", true);
        sensiParameters.getLoadFlowParameters().getExtension(OpenLoadFlowParameters.class).setSlackBusPMaxMismatch(0.001);

        List<SensitivityFactor> factors = createFactorMatrix(Stream.of("g1", "g2").map(network::getGenerator).collect(Collectors.toList()),
                Stream.of("l12", "l13", "l23").map(network::getBranch).collect(Collectors.toList()));

        List<Contingency> contingencies = List.of(new Contingency("hvdc34", new HvdcLineContingency("hvdc34")));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, contingencies, Collections.emptyList(), sensiParameters);

        network.getHvdcLine("hvdc34").getConverterStation1().getTerminal().disconnect();
        network.getHvdcLine("hvdc34").getConverterStation2().getTerminal().disconnect();
        SensitivityAnalysisResult result2 = sensiRunner.run(network, factors, Collections.emptyList(), Collections.emptyList(), sensiParameters);

        assertEquals(6, result.getValues("hvdc34").size());
        List<SensitivityValue> contingencyResult = result.getValues("hvdc34");
        assertEquals(6, contingencyResult.size());
        assertEquals(result2.getBranchFlow1SensitivityValue("g1", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), result.getBranchFlow1SensitivityValue("hvdc34", "g1", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(result2.getBranchFlow1SensitivityValue("g1", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), result.getBranchFlow1SensitivityValue("hvdc34", "g1", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(result2.getBranchFlow1SensitivityValue("g1", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), result.getBranchFlow1SensitivityValue("hvdc34", "g1", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(result2.getBranchFlow1SensitivityValue("g2", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), result.getBranchFlow1SensitivityValue("hvdc34", "g2", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(result2.getBranchFlow1SensitivityValue("g2", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), result.getBranchFlow1SensitivityValue("hvdc34", "g2", "l13", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(result2.getBranchFlow1SensitivityValue("g2", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), result.getBranchFlow1SensitivityValue("hvdc34", "g2", "l23", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);

        assertEquals(result2.getBranchFlow1FunctionReferenceValue(null, "l12"), result.getBranchFlow1FunctionReferenceValue("hvdc34", "l12"), LoadFlowAssert.DELTA_POWER);
        assertEquals(result2.getBranchFlow1FunctionReferenceValue(null, "l13"), result.getBranchFlow1FunctionReferenceValue("hvdc34", "l13"), LoadFlowAssert.DELTA_POWER);
        assertEquals(result2.getBranchFlow1FunctionReferenceValue(null, "l23"), result.getBranchFlow1FunctionReferenceValue("hvdc34", "l23"), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testContingencyOnHvdcVsc2() {
        Network network = HvdcNetworkFactory.createWithHvdcInAcEmulation();
        network.getHvdcLine("hvdc34").newExtension(HvdcAngleDroopActivePowerControlAdder.class)
                .withDroop(180)
                .withP0(0.f)
                .withEnabled(true)
                .add();

        SensitivityAnalysisParameters sensiParameters = createParameters(false, "b1_vl_0", true);
        sensiParameters.getLoadFlowParameters().getExtension(OpenLoadFlowParameters.class).setSlackBusPMaxMismatch(0.001);

        List<SensitivityFactor> factors = createFactorMatrix(Stream.of("g1", "g5").map(network::getGenerator).collect(Collectors.toList()),
                Stream.of("l12", "l25", "l56").map(network::getBranch).collect(Collectors.toList()));

        List<Contingency> contingencies = List.of(new Contingency("hvdc34", new HvdcLineContingency("hvdc34")));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, contingencies, Collections.emptyList(), sensiParameters);

        network.getHvdcLine("hvdc34").getConverterStation1().getTerminal().disconnect();
        network.getHvdcLine("hvdc34").getConverterStation2().getTerminal().disconnect();
        SensitivityAnalysisResult result2 = sensiRunner.run(network, factors, Collections.emptyList(), Collections.emptyList(), sensiParameters);

        assertEquals(6, result.getValues("hvdc34").size());
        List<SensitivityValue> contingencyResult = result.getValues("hvdc34");
        assertEquals(6, contingencyResult.size());
        assertEquals(result2.getBranchFlow1SensitivityValue("g1", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), result.getBranchFlow1SensitivityValue("hvdc34", "g1", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(result2.getBranchFlow1SensitivityValue("g1", "l25", SensitivityVariableType.INJECTION_ACTIVE_POWER), result.getBranchFlow1SensitivityValue("hvdc34", "g1", "l25", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(result2.getBranchFlow1SensitivityValue("g1", "l56", SensitivityVariableType.INJECTION_ACTIVE_POWER), result.getBranchFlow1SensitivityValue("hvdc34", "g1", "l56", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(result2.getBranchFlow1SensitivityValue("g5", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), result.getBranchFlow1SensitivityValue("hvdc34", "g5", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(result2.getBranchFlow1SensitivityValue("g5", "l25", SensitivityVariableType.INJECTION_ACTIVE_POWER), result.getBranchFlow1SensitivityValue("hvdc34", "g5", "l25", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(result2.getBranchFlow1SensitivityValue("g5", "l56", SensitivityVariableType.INJECTION_ACTIVE_POWER), result.getBranchFlow1SensitivityValue("hvdc34", "g5", "l56", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);

        assertEquals(result2.getBranchFlow1FunctionReferenceValue(null, "l12"), result.getBranchFlow1FunctionReferenceValue("hvdc34", "l12"), LoadFlowAssert.DELTA_POWER);
        assertEquals(result2.getBranchFlow1FunctionReferenceValue(null, "l25"), result.getBranchFlow1FunctionReferenceValue("hvdc34", "l25"), LoadFlowAssert.DELTA_POWER);
        assertEquals(result2.getBranchFlow1FunctionReferenceValue(null, "l56"), result.getBranchFlow1FunctionReferenceValue("hvdc34", "l56"), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testVoltageSensitivityConnectivityLoss() {
        Network network = ConnectedComponentNetworkFactory.createTwoComponentWithGeneratorOnOneSide();

        SensitivityAnalysisParameters sensiParameters = createParameters(false, "b1_vl_0", true);
        sensiParameters.getLoadFlowParameters().setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX);

        List<Contingency> contingencies = List.of(new Contingency("l34", new BranchContingency("l34")), new Contingency("l13+l23", new BranchContingency("l13"), new BranchContingency("l23")));

        List<SensitivityFactor> factors = List.of(createBusVoltagePerTargetV("b4", "g3"),
                                                  createBusVoltagePerTargetV("b1", "g3"),
                                                  createBusVoltagePerTargetV("b4", "g3"));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, contingencies, Collections.emptyList(), sensiParameters);

        assertEquals(9, result.getValues().size());

        assertEquals(0.0, result.getBusVoltageSensitivityValue("l34", "g3", "b4", SensitivityVariableType.BUS_TARGET_VOLTAGE));
        assertEquals(Double.NaN, result.getBusVoltageFunctionReferenceValue("l34", "b4"));
        assertEquals(0.995, result.getBusVoltageFunctionReferenceValue("l34", "b1"), LoadFlowAssert.DELTA_V);
        // Contingency "l13+l23" leads to a component with only b1 and b2, slack bus is relocated on b3.
        assertEquals(0.0, result.getBusVoltageSensitivityValue("l13+l23", "g3", "b1", SensitivityVariableType.BUS_TARGET_VOLTAGE));
        assertEquals(Double.NaN, result.getBusVoltageFunctionReferenceValue("l13+l23", "b1"), LoadFlowAssert.DELTA_V);
        assertEquals(0.860, result.getBusVoltageFunctionReferenceValue("l13+l23", "b4"), LoadFlowAssert.DELTA_V);
        assertEquals(1.900, result.getBusVoltageSensitivityValue("l13+l23", "g3", "b4", SensitivityVariableType.BUS_TARGET_VOLTAGE), LoadFlowAssert.DELTA_V);
    }

    @Test
    void testContingencyWithDisconnectedBranch() {
        Network network = ConnectedComponentNetworkFactory.createTwoCcLinkedByTwoLines();

        SensitivityAnalysisParameters sensiParameters = createParameters(false, "b1_vl_0", true);
        sensiParameters.getLoadFlowParameters().setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD);

        List<Contingency> contingencies = List.of(new Contingency("l45", new BranchContingency("l45")));

        List<SensitivityFactor> factors = List.of(createBranchFlowPerInjectionIncrease("l46", "g2"));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, contingencies, Collections.emptyList(), sensiParameters);

        // different sensitivity for (g2, l46) on base case and after contingency l45
        assertEquals(0.0667d, result.getBranchFlow1SensitivityValue("g2", "l46", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.1452d, result.getBranchFlow1SensitivityValue("l45", "g2", "l46", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);

        // we open l45 at both sides
        Line l45 = network.getLine("l45");
        l45.getTerminal1().disconnect();
        l45.getTerminal2().disconnect();

        result = sensiRunner.run(network, factors, contingencies, Collections.emptyList(), sensiParameters);

        // we now have as expected the sensitivity for (g2, l46) on base case and after contingency l45
        // because l45 is already open on base case
        assertEquals(0.1452d, result.getBranchFlow1SensitivityValue("g2", "l46", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.1452d, result.getBranchFlow1SensitivityValue("l45", "g2", "l46", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(SensitivityAnalysisResult.Status.NO_IMPACT, result.getContingencyStatus("l45"));
    }

    @Test
    void testSwitchContingency() {
        Network network = NodeBreakerNetworkFactory.create();

        SensitivityAnalysisParameters sensiParameters = createParameters(false, "VL1_0");

        List<SensitivityFactor> factors = List.of(createBranchFlowPerInjectionIncrease("L1", "LD"));

        List<Contingency> contingencies = List.of(new Contingency("C", new SwitchContingency("C")));

        List<SensitivityVariableSet> variableSets = Collections.emptyList();

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, contingencies, variableSets, sensiParameters);
        assertEquals(-0.506, result.getBranchFlow1SensitivityValue("LD", "L1", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-1.000, result.getBranchFlow1SensitivityValue("C", "LD", "L1", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(301.884, result.getBranchFlow1FunctionReferenceValue("L1"), LoadFlowAssert.DELTA_POWER);
        assertEquals(3.912, result.getBranchFlow1FunctionReferenceValue("C", "L1"), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testSwitchContingency2() {
        Network network = NodeBreakerNetworkFactory.create();

        SensitivityAnalysisParameters sensiParameters = createParameters(false, "VL1_0");

        List<SensitivityFactor> factors = List.of(createBusVoltagePerTargetV("BBS3", "G"));

        List<Contingency> contingencies = List.of(new Contingency("C", new SwitchContingency("C")));

        List<SensitivityVariableSet> variableSets = Collections.emptyList();

        CompletionException e = assertThrows(CompletionException.class, () -> sensiRunner.run(network, factors, contingencies, variableSets, sensiParameters));
        assertTrue(e.getCause() instanceof PowsyblException);
        assertEquals("Switch contingency is not yet supported with sensitivity function of type BUS_VOLTAGE", e.getCause().getMessage());
    }

    @Test
    void testNoImpactContingencyAfterNormalContingency() {
        Network network = ConnectedComponentNetworkFactory.createTwoCcLinkedByTwoLines();
        // we open l45 at both sides
        Line l13 = network.getLine("l13");
        l13.getTerminal1().disconnect();
        l13.getTerminal2().disconnect();

        SensitivityAnalysisParameters sensiParameters = createParameters(false, "b1_vl_0", true);
        sensiParameters.getLoadFlowParameters().setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD);

        List<Contingency> contingencies = List.of(new Contingency("lines", List.of(new BranchContingency("l46"), new BranchContingency("l56"))),
                                                  new Contingency("l13", new BranchContingency("l13")));

        ContingencyContext contingencyContext = new ContingencyContext("l13", ContingencyContextType.SPECIFIC);
        SensitivityFactor factor = new SensitivityFactor(SensitivityFunctionType.BRANCH_ACTIVE_POWER_1, "l46",
                                                         SensitivityVariableType.INJECTION_ACTIVE_POWER,
                                                         "d1", false,
                                                         contingencyContext);
        List<SensitivityFactor> factors = List.of(factor);

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, contingencies, Collections.emptyList(), sensiParameters);
        assertEquals(0.0735, result.getBranchFlow1SensitivityValue("l13", "d1", "l46", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(SensitivityAnalysisResult.Status.NO_IMPACT, result.getContingencyStatus("l13"));
    }

    @Test
    void testMaxIterationReachedAfterContingency() {
        Network network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());
        network.getLine("NHV1_NHV2_1").setX(1000);
        List<Contingency> contingencies = List.of(new Contingency("NHV1_NHV2_2", List.of(new BranchContingency("NHV1_NHV2_2"))));
        List<SensitivityFactor> factors = List.of(createBranchFlowPerInjectionIncrease("NHV1_NHV2_1", "LOAD"));
        SensitivityAnalysisParameters parameters = new SensitivityAnalysisParameters();
        parameters.getLoadFlowParameters().setDistributedSlack(false);
        SensitivityAnalysisResult result = sensiRunner.run(network, factors, contingencies, Collections.emptyList(), parameters);
        assertEquals(SensitivityAnalysisResult.Status.FAILURE, result.getContingencyStatus("NHV1_NHV2_2"));
    }

    @Test
    void testPredefinedResults() {
        // Load and generator in contingency
        Network network = FourBusNetworkFactory.create();
        SensitivityAnalysisParameters sensiParameters = createParameters(false, "b1_vl_0", true);
        List<SensitivityFactor> factors = List.of(createBranchFlowPerInjectionIncrease("l14", "g1"),
                createBranchFlowPerInjectionIncrease("l14", "d2"));
        List<Contingency> contingencies = List.of(new Contingency("g1", new GeneratorContingency("g1")), new Contingency("d2", new LoadContingency("d2")));
        SensitivityAnalysisResult result = sensiRunner.run(network, factors, contingencies, Collections.emptyList(), sensiParameters);
        assertEquals(0, result.getBranchFlow1SensitivityValue("g1", "g1", "l14", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0, result.getBranchFlow1SensitivityValue("d2", "d2", "l14", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testPredefinedResults2() {
        // LCC line in contingency
        Network network = HvdcNetworkFactory.createNetworkWithGenerators();
        SensitivityAnalysisParameters sensiParameters = createParameters(false, "b1_vl_0", true);
        sensiParameters.getLoadFlowParameters().getExtension(OpenLoadFlowParameters.class).setSlackBusPMaxMismatch(0.001);
        List<SensitivityFactor> factors = SensitivityFactor.createMatrix(SensitivityFunctionType.BRANCH_ACTIVE_POWER_1, List.of("l12"),
                SensitivityVariableType.HVDC_LINE_ACTIVE_POWER, List.of("hvdc34"),
                false, ContingencyContext.all());
        List<Contingency> contingencies = List.of(new Contingency("hvdc34", new HvdcLineContingency("hvdc34")));
        SensitivityAnalysisResult result = sensiRunner.run(network, factors, contingencies, Collections.emptyList(), sensiParameters);
        assertEquals(0, result.getBranchFlow1SensitivityValue("hvdc34", "hvdc34", "l12", SensitivityVariableType.HVDC_LINE_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        // VSC line in contingency
        Network network2 = HvdcNetworkFactory.createNetworkWithGenerators2();
        SensitivityAnalysisResult result2 = sensiRunner.run(network2, factors, contingencies, Collections.emptyList(), sensiParameters);
        assertEquals(0, result2.getBranchFlow1SensitivityValue("hvdc34", "hvdc34", "l12", SensitivityVariableType.HVDC_LINE_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testPredefinedResults3() {
        Network network = ConnectedComponentNetworkFactory.createTwoComponentWithGeneratorOnOneSide();
        SensitivityAnalysisParameters sensiParameters = createParameters(false, "b1_vl_0", true);
        sensiParameters.getLoadFlowParameters().setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX);
        List<Contingency> contingencies = List.of(new Contingency("g2", new GeneratorContingency("g2")));
        List<SensitivityFactor> factors = List.of(createBusVoltagePerTargetV("b4", "g2", "g2"));
        SensitivityAnalysisResult result = sensiRunner.run(network, factors, contingencies, Collections.emptyList(), sensiParameters);
        assertEquals(0, result.getBusVoltageSensitivityValue("g2", "g2", "b4", SensitivityVariableType.BUS_TARGET_VOLTAGE), LoadFlowAssert.DELTA_V);
    }

    @Test
    void testPredefinedResults4() {
        SensitivityAnalysisParameters sensiParameters = createParameters(false, "b1_vl_0", true);
        sensiParameters.getLoadFlowParameters().setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX);
        Network network = FourBusNetworkFactory.createWithPhaseTapChangerAndGeneratorAtBus2();
        List<SensitivityFactor> factors = List.of(createBranchFlowPerPSTAngle("l12", "l23", "l23"),
                createBranchFlowPerPSTAngle("l23", "l23", "l23"));
        List<Contingency> contingencies = Collections.singletonList(new Contingency("l23", new BranchContingency("l23")));
        SensitivityAnalysisResult result = sensiRunner.run(network, factors, contingencies, Collections.emptyList(), sensiParameters);
        assertEquals(0, result.getBranchFlow1SensitivityValue("l23", "l23", "l12", SensitivityVariableType.TRANSFORMER_PHASE), LoadFlowAssert.DELTA_POWER);
        assertEquals(Double.NaN, result.getBranchFlow1SensitivityValue("l23", "l23", "l23", SensitivityVariableType.TRANSFORMER_PHASE), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testPredefinedResults5() {
        Network network = HvdcNetworkFactory.createNetworkWithGenerators();
        network.getLine("l25").getTerminal1().disconnect();
        network.getLine("l25").getTerminal2().disconnect();
        SensitivityAnalysisParameters sensiParameters = createParameters(true, "b1_vl_0", true);
        // with contingency context all
        List<Contingency> contingencies = Collections.singletonList(new Contingency("l12", new BranchContingency("l12")));
        List<SensitivityFactor> factors = List.of(createBranchFlowPerInjectionIncrease("l46", "g1")); // all contingency context.
        SensitivityAnalysisResult result = sensiRunner.run(network, factors, contingencies, Collections.emptyList(), sensiParameters);
        assertEquals(0.0, result.getBranchFlow1SensitivityValue("g1", "l46", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(Double.NaN, result.getBranchFlow1FunctionReferenceValue("l46"), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.0, result.getBranchFlow1SensitivityValue("l12", "g1", "l46", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(Double.NaN, result.getBranchFlow1FunctionReferenceValue("l12", "l46"), LoadFlowAssert.DELTA_POWER);
        // with contingency context specific (no pre contingency state asked...)
        factors = List.of(createBranchFlowPerInjectionIncrease("l46", "g1", "l12")); // specific contingency context.
        result = sensiRunner.run(network, factors, contingencies, Collections.emptyList(), sensiParameters);
        assertEquals(0.0, result.getBranchFlow1SensitivityValue("l12", "g1", "l46", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(Double.NaN, result.getBranchFlow1FunctionReferenceValue("l12", "l46"), LoadFlowAssert.DELTA_POWER);
        assertTrue(result.getValues(null).isEmpty()); // nothing because invalid factor in pre contingency state.
    }

    @Test
    void testRestoreAfterContingencyOnHvdc() {
        Network network = HvdcNetworkFactory.createWithHvdcInAcEmulation();
        network.getHvdcLine("hvdc34").newExtension(HvdcAngleDroopActivePowerControlAdder.class)
                .withDroop(180)
                .withP0(0.f)
                .withEnabled(true)
                .add();

        SensitivityAnalysisParameters sensiParameters = createParameters(false, "b1_vl_0", true);
        sensiParameters.getLoadFlowParameters().getExtension(OpenLoadFlowParameters.class).setSlackBusPMaxMismatch(0.001);

        List<SensitivityFactor> factors = createFactorMatrix(Stream.of("g1", "g5").map(network::getGenerator).collect(Collectors.toList()),
                Stream.of("l12", "l25", "l56").map(network::getBranch).collect(Collectors.toList()));

        List<Contingency> contingencies = List.of(new Contingency("hvdc34", new HvdcLineContingency("hvdc34")),
                                                  new Contingency("l45", new BranchContingency("l45")));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, contingencies, Collections.emptyList(), sensiParameters);

        assertEquals(0.269, result.getBranchFlow1SensitivityValue("l45", "g1", "l12", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.356, result.getBranchFlow1SensitivityValue("l45", "g1", "l25", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
        assertEquals(-0.144, result.getBranchFlow1SensitivityValue("l45", "g1", "l56", SensitivityVariableType.INJECTION_ACTIVE_POWER), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testStaticVarCompensatorContingency() {
        Network network = VoltageControlNetworkFactory.createWithStaticVarCompensator();
        network.getStaticVarCompensator("svc1").setVoltageSetpoint(385).setRegulationMode(StaticVarCompensator.RegulationMode.VOLTAGE);
        List<Contingency> contingencies = List.of(new Contingency("svc1", new StaticVarCompensatorContingency("svc1")));
        SensitivityAnalysisParameters parameters = new SensitivityAnalysisParameters();
        List<SensitivityFactor> factors = List.of(createBusVoltagePerTargetV("b2", "g1"), createBusVoltagePerTargetV("b2", "g1", "svc1"));
        SensitivityAnalysisResult result = sensiRunner.run(network, factors, contingencies, Collections.emptyList(), parameters);
        assertEquals(385.0, result.getBusVoltageFunctionReferenceValue("b2"), LoadFlowAssert.DELTA_V);
        assertEquals(388.582, result.getBusVoltageFunctionReferenceValue("svc1", "b2"), LoadFlowAssert.DELTA_V);
    }

    @Test
    void testSaWithShuntContingency() {
        Network network = VoltageControlNetworkFactory.createWithShuntSharedRemoteControl();
        network.getShuntCompensatorStream().forEach(shuntCompensator -> {
            shuntCompensator.setSectionCount(10).setVoltageRegulatorOn(false);
        });
        List<Contingency> contingencies = List.of(new Contingency("SHUNT2", new ShuntCompensatorContingency("SHUNT2")));
        SensitivityAnalysisParameters parameters = new SensitivityAnalysisParameters();
        List<SensitivityFactor> factors = List.of(createBusVoltagePerTargetV("b4", "g1"), createBusVoltagePerTargetV("b4", "g1", "SHUNT2"));
        SensitivityAnalysisResult result = sensiRunner.run(network, factors, contingencies, Collections.emptyList(), parameters);
        assertEquals(401.082, result.getBusVoltageFunctionReferenceValue("b4"), LoadFlowAssert.DELTA_V);
        assertEquals(404.218, result.getBusVoltageFunctionReferenceValue("SHUNT2", "b4"), LoadFlowAssert.DELTA_V);
    }

    @Test
    void testStaticVarCompensatorContingency2() {
        Network network = VoltageControlNetworkFactory.createWithStaticVarCompensator();
        StaticVarCompensator svc1 = network.getStaticVarCompensator("svc1");
        svc1.setVoltageSetpoint(385).setRegulationMode(StaticVarCompensator.RegulationMode.VOLTAGE);
        svc1.newExtension(StandbyAutomatonAdder.class)
                .withHighVoltageThreshold(400)
                .withLowVoltageThreshold(380)
                .withLowVoltageSetpoint(385)
                .withHighVoltageSetpoint(395)
                .withB0(-0.001f)
                .withStandbyStatus(true)
                .add();
        List<Contingency> contingencies = List.of(new Contingency("svc1", new StaticVarCompensatorContingency("svc1")));
        SensitivityAnalysisParameters parameters = new SensitivityAnalysisParameters();
        List<SensitivityFactor> factors = List.of(createBusVoltagePerTargetV("b2", "g1"), createBusVoltagePerTargetV("b2", "g1", "svc1"));
        SensitivityAnalysisResult result = sensiRunner.run(network, factors, contingencies, Collections.emptyList(), parameters);
        assertEquals(387.415, result.getBusVoltageFunctionReferenceValue("b2"), LoadFlowAssert.DELTA_V);
        assertEquals(388.582, result.getBusVoltageFunctionReferenceValue("svc1", "b2"), LoadFlowAssert.DELTA_V);
    }

    @Test
    void testBusContingency() {
        Network network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());
        // add another generator producing epsilon MW with voltage regulation enabled at NLOAD bus,
        // otherwise we end up with no bus voltage regulated in 'NHV1' and 'NGEN' post-contingency states
        network.getVoltageLevel("VLLOAD").newGenerator()
                .setId("GEN2")
                .setConnectableBus("NLOAD")
                .setBus("NLOAD")
                .setMinP(0.0)
                .setMaxP(1.0)
                .setTargetP(1e-6)
                .setTargetV(147.58)
                .setVoltageRegulatorOn(true)
                .add();
        SensitivityAnalysisParameters sensiParameters = createParameters(false, "NGEN", true);
        sensiParameters.getLoadFlowParameters().setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX);
        List<SensitivityFactor> factors = List.of(createBranchFlowPerInjectionIncrease("NHV1_NHV2_1", "LOAD"),
                                                  createBranchFlowPerInjectionIncrease("NHV1_NHV2_2", "LOAD"),
                                                  createBranchFlowPerInjectionIncrease("NHV2_NLOAD", "LOAD"),
                                                  createBranchFlowPerInjectionIncrease("NGEN_NHV1", "LOAD"));
        List<Contingency> contingencies = network.getBusBreakerView().getBusStream()
                .map(bus -> new Contingency(bus.getId(), new BusContingency(bus.getId())))
                .toList();
        SensitivityAnalysisResult result = sensiRunner.run(network, factors, contingencies, Collections.emptyList(), sensiParameters);
        assertEquals(302.444, result.getBranchFlow1FunctionReferenceValue("NHV1_NHV2_1"), LoadFlowAssert.DELTA_POWER);
        assertEquals(302.444, result.getBranchFlow1FunctionReferenceValue("NHV1_NHV2_2"), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.019, result.getBranchFlow1FunctionReferenceValue("NLOAD", "NHV1_NHV2_1"), LoadFlowAssert.DELTA_POWER);
        // Contingency 'NHV1' leads to an isolated slack bus:  slack bus is relocated.
        assertEquals(SensitivityAnalysisResult.Status.SUCCESS, result.getContingencyStatus("NHV1"));
        assertEquals(600.784, result.getBranchFlow1FunctionReferenceValue("NHV1", "NHV2_NLOAD"), LoadFlowAssert.DELTA_POWER);
        // Contingency 'NGEN' leads to the loss of a slack bus: slack bus is relocated.
        assertEquals(SensitivityAnalysisResult.Status.SUCCESS, result.getContingencyStatus("NGEN"));
        assertEquals(302.304, result.getBranchFlow1FunctionReferenceValue("NGEN", "NHV1_NHV2_1"), LoadFlowAssert.DELTA_POWER);
        assertEquals(Double.NaN, result.getBranchFlow1FunctionReferenceValue("NGEN", "NGEN_NHV1"), LoadFlowAssert.DELTA_POWER);
    }
}
