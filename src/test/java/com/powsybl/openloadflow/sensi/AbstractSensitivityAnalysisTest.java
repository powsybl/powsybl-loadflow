/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.sensi;

import com.powsybl.commons.AbstractConverterTest;
import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.reporter.Reporter;
import com.powsybl.computation.local.LocalComputationManager;
import com.powsybl.contingency.BranchContingency;
import com.powsybl.contingency.Contingency;
import com.powsybl.contingency.ContingencyContext;
import com.powsybl.iidm.network.Branch;
import com.powsybl.iidm.network.Injection;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.VariantManagerConstants;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowResult;
import com.powsybl.math.matrix.DenseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.OpenLoadFlowProvider;
import com.powsybl.openloadflow.network.HvdcNetworkFactory;
import com.powsybl.openloadflow.network.SlackBusSelectionMode;
import com.powsybl.openloadflow.util.LoadFlowAssert;
import com.powsybl.sensitivity.*;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public abstract class AbstractSensitivityAnalysisTest extends AbstractConverterTest {

    protected static final double SENSI_CHANGE = 10e-4;

    protected final DenseMatrixFactory matrixFactory = new DenseMatrixFactory();

    protected final OpenSensitivityAnalysisProvider sensiProvider = new OpenSensitivityAnalysisProvider(matrixFactory);

    protected final SensitivityAnalysis.Runner sensiRunner = new SensitivityAnalysis.Runner(sensiProvider);

    protected static SensitivityAnalysisParameters createParameters(boolean dc, String slackBusId, boolean distributedSlack) {
        SensitivityAnalysisParameters sensiParameters = new SensitivityAnalysisParameters();
        LoadFlowParameters lfParameters = sensiParameters.getLoadFlowParameters();
        lfParameters.setDc(dc);
        lfParameters.setDistributedSlack(distributedSlack);
        OpenLoadFlowParameters lfParametersExt = new OpenLoadFlowParameters()
                .setSlackBusSelectionMode(SlackBusSelectionMode.NAME)
                .setSlackBusId(slackBusId);
        lfParameters.addExtension(OpenLoadFlowParameters.class, lfParametersExt);
        return sensiParameters;
    }

    protected static SensitivityAnalysisParameters createParameters(boolean dc, String slackBusId) {
        return createParameters(dc, slackBusId, false);
    }

    protected static SensitivityAnalysisParameters createParameters(boolean dc) {
        SensitivityAnalysisParameters sensiParameters = new SensitivityAnalysisParameters();
        LoadFlowParameters lfParameters = sensiParameters.getLoadFlowParameters();
        lfParameters.setDc(dc);
        lfParameters.setDistributedSlack(true);
        OpenLoadFlowParameters lfParametersExt = new OpenLoadFlowParameters()
                .setSlackBusSelectionMode(SlackBusSelectionMode.MOST_MESHED);
        lfParameters.addExtension(OpenLoadFlowParameters.class, lfParametersExt);
        return sensiParameters;
    }

    protected static <T extends Injection<T>> List<SensitivityFactor> createFactorMatrix(List<T> injections, List<Branch> branches, String contingencyId) {
        Objects.requireNonNull(injections);
        Objects.requireNonNull(branches);
        return injections.stream().flatMap(injection -> branches.stream().map(branch -> createBranchFlowPerInjectionIncrease(branch.getId(), injection.getId(), contingencyId))).collect(Collectors.toList());
    }

    protected static <T extends Injection<T>> List<SensitivityFactor> createFactorMatrix(List<T> injections, List<Branch> branches) {
        return createFactorMatrix(injections, branches, null);
    }

    protected static SensitivityFactor createBranchFlowPerInjectionIncrease(String functionId, String variableId, String contingencyId) {
        return new SensitivityFactor(SensitivityFunctionType.BRANCH_ACTIVE_POWER, functionId, SensitivityVariableType.INJECTION_ACTIVE_POWER, variableId, false, Objects.isNull(contingencyId) ? ContingencyContext.all() : ContingencyContext.specificContingency(contingencyId));
    }

    protected static SensitivityFactor createBranchFlowPerInjectionIncrease(String functionId, String variableId) {
        return createBranchFlowPerInjectionIncrease(functionId, variableId, null);
    }

    protected static SensitivityFactor createBranchFlowPerLinearGlsk(String functionId, String variableId, String contingencyId) {
        return createBranchFlowPerLinearGlsk(functionId, variableId, Objects.isNull(contingencyId) ? ContingencyContext.all() : ContingencyContext.specificContingency(contingencyId));
    }

    protected static SensitivityFactor createBranchFlowPerLinearGlsk(String functionId, String variableId, ContingencyContext contingencyContext) {
        return new SensitivityFactor(SensitivityFunctionType.BRANCH_ACTIVE_POWER, functionId, SensitivityVariableType.INJECTION_ACTIVE_POWER, variableId, true, contingencyContext);
    }

    protected static SensitivityFactor createBranchFlowPerLinearGlsk(String functionId, String variableId) {
        return createBranchFlowPerLinearGlsk(functionId, variableId, (String) null);
    }

    protected static SensitivityFactor createBranchFlowPerPSTAngle(String functionId, String variableId, String contingencyId) {
        return new SensitivityFactor(SensitivityFunctionType.BRANCH_ACTIVE_POWER, functionId, SensitivityVariableType.TRANSFORMER_PHASE, variableId, false, Objects.isNull(contingencyId) ? ContingencyContext.all() : ContingencyContext.specificContingency(contingencyId));
    }

    protected static SensitivityFactor createBranchFlowPerPSTAngle(String functionId, String variableId) {
        return createBranchFlowPerPSTAngle(functionId, variableId, null);
    }

    protected static SensitivityFactor createBranchIntensityPerPSTAngle(String functionId, String variableId) {
        return new SensitivityFactor(SensitivityFunctionType.BRANCH_CURRENT, functionId, SensitivityVariableType.TRANSFORMER_PHASE, variableId, false, ContingencyContext.all());
    }

    protected static SensitivityFactor createBusVoltagePerTargetV(String functionId, String variableId, String contingencyId) {
        return new SensitivityFactor(SensitivityFunctionType.BUS_VOLTAGE, functionId, SensitivityVariableType.BUS_TARGET_VOLTAGE, variableId, false, Objects.isNull(contingencyId) ? ContingencyContext.all() : ContingencyContext.specificContingency(contingencyId));
    }

    protected static SensitivityFactor createBusVoltagePerTargetV(String functionId, String variableId) {
        return createBusVoltagePerTargetV(functionId, variableId, null);
    }

    protected static SensitivityFactor createHvdcInjection(String functionId, String variableId) {
        return new SensitivityFactor(SensitivityFunctionType.BRANCH_ACTIVE_POWER, functionId, SensitivityVariableType.HVDC_LINE_ACTIVE_POWER, variableId, false, ContingencyContext.all());
    }

    protected void runAcLf(Network network) {
        runAcLf(network, Reporter.NO_OP);
    }

    protected void runAcLf(Network network, Reporter reporter) {
        LoadFlowResult result = new OpenLoadFlowProvider(matrixFactory)
                .run(network, LocalComputationManager.getDefault(), VariantManagerConstants.INITIAL_VARIANT_ID, new LoadFlowParameters(), reporter)
                .join();
        if (!result.isOk()) {
            throw new PowsyblException("AC LF diverged");
        }
    }

    protected void runDcLf(Network network) {
        runDcLf(network, Reporter.NO_OP);
    }

    protected void runDcLf(Network network, Reporter reporter) {
        LoadFlowParameters parameters =  new LoadFlowParameters().setDc(true);
        LoadFlowResult result = new OpenLoadFlowProvider(matrixFactory)
                .run(network, LocalComputationManager.getDefault(), VariantManagerConstants.INITIAL_VARIANT_ID, parameters, reporter)
                .join();
        if (!result.isOk()) {
            throw new PowsyblException("DC LF failed");
        }
    }

    protected void runLf(Network network, LoadFlowParameters loadFlowParameters) {
        runLf(network, loadFlowParameters, Reporter.NO_OP);
    }

    protected void runLf(Network network, LoadFlowParameters loadFlowParameters, Reporter reporter) {
        LoadFlowResult result = new OpenLoadFlowProvider(matrixFactory)
                .run(network, LocalComputationManager.getDefault(), VariantManagerConstants.INITIAL_VARIANT_ID, loadFlowParameters, reporter)
                .join();
        if (!result.isOk()) {
            throw new PowsyblException("LF failed");
        }
    }

    protected void testInjectionNotFound(boolean dc) {
        Network network = EurostagTutorialExample1Factory.create();
        runAcLf(network);

        SensitivityAnalysisParameters sensiParameters = createParameters(dc, "VLLOAD_0");

        List<SensitivityFactor> factors = Collections.singletonList(createBranchFlowPerInjectionIncrease("NHV1_NHV2_1", "a"));

        List<Contingency> contingencies = Collections.emptyList();
        List<SensitivityVariableSet> variableSets = Collections.emptyList();

        CompletionException e = assertThrows(CompletionException.class, () -> sensiRunner.run(network, factors, contingencies, variableSets, sensiParameters));
        assertTrue(e.getCause() instanceof PowsyblException);
        assertEquals("Injection 'a' not found", e.getCause().getMessage());
    }

    protected void testInjectionNotFoundAdditionalFactor(boolean dc) {
        Network network = EurostagTutorialExample1Factory.create();

        SensitivityAnalysisParameters sensiParameters = createParameters(dc, "VLLOAD_0");

        List<SensitivityFactor> factors = Collections.singletonList(createBranchFlowPerInjectionIncrease("NHV1_NHV2_1", "a"));

        List<Contingency> contingencies = Collections.emptyList();
        List<SensitivityVariableSet> variableSets = Collections.emptyList();

        CompletionException e = assertThrows(CompletionException.class, () -> sensiRunner.run(network, factors, contingencies, variableSets, sensiParameters));
        assertTrue(e.getCause() instanceof PowsyblException);
        assertEquals("Injection 'a' not found", e.getCause().getMessage());
    }

    protected void testInjectionNotFoundAdditionalFactorContingency(boolean dc) {
        Network network = EurostagTutorialExample1Factory.create();

        SensitivityAnalysisParameters sensiParameters = createParameters(dc, "VLLOAD_0");

        List<SensitivityFactor> factors = List.of(createBranchFlowPerInjectionIncrease("NHV1_NHV2_1", "a"));

        List<Contingency> contingencies = List.of(new Contingency("a", new BranchContingency("NHV1_NHV2_2")));

        List<SensitivityVariableSet> variableSets = Collections.emptyList();

        CompletionException e = assertThrows(CompletionException.class, () -> sensiRunner.run(network, factors, contingencies, variableSets, sensiParameters));
        assertTrue(e.getCause() instanceof PowsyblException);
        assertEquals("Injection 'a' not found", e.getCause().getMessage());
    }

    protected void testGlskInjectionNotFound(boolean dc) {
        Network network = EurostagTutorialExample1Factory.create();
        runAcLf(network);

        SensitivityAnalysisParameters sensiParameters = createParameters(dc, "VLLOAD_0");

        List<SensitivityFactor> factors = Collections.singletonList(createBranchFlowPerLinearGlsk("NHV1_NHV2_1", "glsk"));

        List<Contingency> contingencies = Collections.emptyList();

        List<SensitivityVariableSet> variableSets = Collections.singletonList(new SensitivityVariableSet("glsk", List.of(new WeightedSensitivityVariable("a", 10f))));

        CompletionException e = assertThrows(CompletionException.class, () -> sensiRunner.run(network, factors, contingencies, variableSets, sensiParameters));
        assertTrue(e.getCause() instanceof PowsyblException);
        assertEquals("Injection 'a' not found", e.getCause().getMessage());
    }

    protected void testHvdcInjectionNotFound(boolean dc) {
        SensitivityAnalysisParameters sensiParameters = createParameters(dc, "b1_vl_0", true);

        Network network = HvdcNetworkFactory.createTwoCcLinkedByAHvdcWithGenerators();

        List<SensitivityFactor> factors = List.of(createHvdcInjection("l12", "nop"));

        List<Contingency> contingencies = Collections.emptyList();
        List<SensitivityVariableSet> variableSets = Collections.emptyList();

        CompletionException e = assertThrows(CompletionException.class, () -> sensiRunner.run(network, factors, contingencies, variableSets, sensiParameters));
        assertTrue(e.getCause() instanceof PowsyblException);
        assertEquals("HVDC line 'nop' cannot be found in the network.", e.getCause().getMessage());
    }

    protected void testBranchNotFound(boolean dc) {
        Network network = EurostagTutorialExample1Factory.create();
        runAcLf(network);

        SensitivityAnalysisParameters sensiParameters = createParameters(dc, "VLLOAD_0");

        List<SensitivityFactor> factors = List.of(createBranchFlowPerInjectionIncrease("b", "GEN"));

        List<Contingency> contingencies = Collections.emptyList();
        List<SensitivityVariableSet> variableSets = Collections.emptyList();

        CompletionException e = assertThrows(CompletionException.class, () -> sensiRunner.run(network, factors, contingencies, variableSets, sensiParameters));
        assertTrue(e.getCause() instanceof PowsyblException);
        assertEquals("Branch 'b' not found", e.getCause().getMessage());
    }

    protected void testEmptyFactors(boolean dc) {
        Network network = EurostagTutorialExample1Factory.create();
        runAcLf(network);

        SensitivityAnalysisParameters sensiParameters = createParameters(dc, "VLLOAD_0");

        List<SensitivityFactor> factors = Collections.emptyList();

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, Collections.emptyList(), Collections.emptyList(), sensiParameters);

        assertTrue(result.getValues().isEmpty());
    }

    protected void testBranchFunctionOutsideMainComponent(boolean dc) {
        Network network = HvdcNetworkFactory.createLccWithBiggerComponents();

        SensitivityAnalysisParameters sensiParameters = createParameters(dc, "vl1_0");

        List<SensitivityFactor> factors = List.of(createBranchFlowPerInjectionIncrease("l56", "g1"));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, Collections.emptyList(), Collections.emptyList(), sensiParameters);

        assertEquals(1, result.getValues().size());
        assertEquals(0d, result.getValues().iterator().next().getValue());
    }

    protected void testInjectionOutsideMainComponent(boolean dc) {
        Network network = HvdcNetworkFactory.createLccWithBiggerComponents();

        SensitivityAnalysisParameters sensiParameters = createParameters(dc, "vl1_0");

        List<SensitivityFactor> factors = List.of(createBranchFlowPerInjectionIncrease("l12", "g3"));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, Collections.emptyList(), Collections.emptyList(), sensiParameters);

        assertEquals(1, result.getValues().size());
        assertEquals(0f, result.getSensitivityValue("g3", "l12"), LoadFlowAssert.DELTA_POWER);
    }

    protected void testPhaseShifterOutsideMainComponent(boolean dc) {
        Network network = HvdcNetworkFactory.createLccWithBiggerComponents();

        SensitivityAnalysisParameters sensiParameters = createParameters(dc, "vl1_0");

        List<SensitivityFactor> factors = List.of(createBranchFlowPerPSTAngle("l12", "l45"));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, Collections.emptyList(), Collections.emptyList(), sensiParameters);

        assertEquals(1, result.getValues().size());
        assertEquals(0d, result.getSensitivityValue("l45", "l12"), LoadFlowAssert.DELTA_POWER);
        if (dc) {
            assertEquals(100.050, result.getFunctionReferenceValue("l12"), LoadFlowAssert.DELTA_POWER);
        } else {
            assertEquals(100.131, result.getFunctionReferenceValue("l12"), LoadFlowAssert.DELTA_POWER);
        }
    }

    protected void testGlskOutsideMainComponent(boolean dc) {
        Network network = HvdcNetworkFactory.createLccWithBiggerComponents();

        SensitivityAnalysisParameters sensiParameters = createParameters(dc, "vl1_0");

        List<SensitivityFactor> factors = List.of(createBranchFlowPerLinearGlsk("l12", "glsk"));

        List<SensitivityVariableSet> variableSets = Collections.singletonList(new SensitivityVariableSet("glsk", List.of(new WeightedSensitivityVariable("g6", 1f),
                                                                                                                         new WeightedSensitivityVariable("g3", 2f))));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, Collections.emptyList(), variableSets, sensiParameters);

        assertEquals(1, result.getValues().size());
        assertEquals(0, result.getSensitivityValue("glsk", "l12"), LoadFlowAssert.DELTA_POWER);
        if (dc) {
            assertEquals(100.050, result.getFunctionReferenceValue("l12"), LoadFlowAssert.DELTA_POWER);
        } else {
            assertEquals(100.131, result.getFunctionReferenceValue("l12"), LoadFlowAssert.DELTA_POWER);
        }
    }

    protected void testGlskAndLineOutsideMainComponent(boolean dc) {
        Network network = HvdcNetworkFactory.createLccWithBiggerComponents();

        SensitivityAnalysisParameters sensiParameters = createParameters(dc, "vl1_0");

        List<SensitivityFactor> factors = List.of(createBranchFlowPerLinearGlsk("l56",  "glsk"));

        List<SensitivityVariableSet> variableSets = List.of(new SensitivityVariableSet("glsk", List.of(new WeightedSensitivityVariable("g6", 1f),
                                                                                                       new WeightedSensitivityVariable("g3", 2f))));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, Collections.emptyList(), variableSets, sensiParameters);

        assertEquals(1, result.getValues().size());
        assertEquals(Double.NaN, result.getSensitivityValue("glsk", "l56"), LoadFlowAssert.DELTA_POWER);
        assertEquals(Double.NaN, result.getFunctionReferenceValue("l56"), LoadFlowAssert.DELTA_POWER);
    }

    protected void testGlskPartiallyOutsideMainComponent(boolean dc) {
        Network network = HvdcNetworkFactory.createLccWithBiggerComponents();

        SensitivityAnalysisParameters sensiParameters = createParameters(dc, "vl1_0");

        List<SensitivityFactor> factors = List.of(createBranchFlowPerLinearGlsk("l12", "glsk"));

        List<SensitivityVariableSet> variableSets = List.of(new SensitivityVariableSet("glsk", List.of(new WeightedSensitivityVariable("ld2", 1f),
                                                                                                       new WeightedSensitivityVariable("g3", 2f))));

        SensitivityAnalysisResult result = sensiRunner.run(network, factors, Collections.emptyList(), variableSets, sensiParameters);

        assertEquals(1, result.getValues().size());

        List<SensitivityFactor> factorsInjection = List.of(createBranchFlowPerInjectionIncrease("l12", "ld2"));

        SensitivityAnalysisResult resultInjection = sensiRunner.run(network, factorsInjection, Collections.emptyList(), Collections.emptyList(), sensiParameters);

        assertEquals(resultInjection.getValues().iterator().next().getValue(), result.getValues().iterator().next().getValue(), LoadFlowAssert.DELTA_POWER);
    }
}
