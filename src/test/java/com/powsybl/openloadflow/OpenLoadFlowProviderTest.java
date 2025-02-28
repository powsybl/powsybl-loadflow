/*
 * Copyright (c) 2019-2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow;

import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowProvider;
import com.powsybl.math.matrix.DenseMatrixFactory;
import com.powsybl.openloadflow.ac.VoltageMagnitudeInitializer;
import com.powsybl.openloadflow.ac.AcLoadFlowParameters;
import com.powsybl.openloadflow.dc.DcLoadFlowParameters;
import com.powsybl.openloadflow.dc.DcValueVoltageInitializer;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import com.powsybl.openloadflow.network.EurostagFactory;
import com.powsybl.openloadflow.network.FirstSlackBusSelector;
import com.powsybl.openloadflow.network.LfNetworkParameters;
import com.powsybl.openloadflow.network.SlackBusSelectionMode;
import com.powsybl.openloadflow.network.util.PreviousValueVoltageInitializer;
import com.powsybl.openloadflow.network.util.UniformValueVoltageInitializer;
import com.powsybl.openloadflow.network.util.VoltageInitializer;
import com.powsybl.openloadflow.util.ProviderConstants;
import com.powsybl.tools.PowsyblCoreVersion;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
class OpenLoadFlowProviderTest {

    @Test
    void test() {
        LoadFlowProvider loadFlowProvider = new OpenLoadFlowProvider(new DenseMatrixFactory());
        assertEquals(ProviderConstants.NAME, loadFlowProvider.getName());
        assertEquals(new PowsyblCoreVersion().getMavenProjectVersion(), loadFlowProvider.getVersion());
    }

    @Test
    void testDcParameters() {
        Network network = Mockito.mock(Network.class);
        DcLoadFlowParameters dcParameters = OpenLoadFlowParameters.createDcParameters(network, new LoadFlowParameters().setReadSlackBus(true), new OpenLoadFlowParameters(), new DenseMatrixFactory(), new EvenShiloachGraphDecrementalConnectivityFactory<>(), true);
        assertEquals("DcLoadFlowParameters(networkParameters=LfNetworkParameters(slackBusSelector=NetworkSlackBusSelector, connectivityFactory=EvenShiloachGraphDecrementalConnectivityFactory, generatorVoltageRemoteControl=false, minImpedance=false, twtSplitShuntAdmittance=false, breakers=false, plausibleActivePowerLimit=5000.0, computeMainConnectedComponentOnly=true, countriesToBalance=[], distributedOnConformLoad=false, phaseControl=false, transformerVoltageControl=false, voltagePerReactivePowerControl=false, generatorReactivePowerRemoteControl=false, transformerReactivePowerControl=false, loadFlowModel=DC, reactiveLimits=false, hvdcAcEmulation=true, minPlausibleTargetVoltage=0.8, maxPlausibleTargetVoltage=1.2, loaderPostProcessorSelection=[], reactiveRangeCheckMode=MAX, lowImpedanceThreshold=1.0E-8, svcVoltageMonitoring=false, maxSlackBusCount=1, debugDir=null, secondaryVoltageControl=false, cacheEnabled=false, asymmetrical=false, minNominalVoltageTargetVoltageCheck=20.0, linePerUnitMode=IMPEDANCE, useLoadModel=false, simulateAutomationSystems=false, referenceBusSelector=ReferenceBusFirstSlackSelector, voltageTargetPriorities=[GENERATOR, TRANSFORMER, SHUNT], fictitiousGeneratorVoltageControlCheckMode=FORCED, areaInterchangeControl=false, areaInterchangeControlAreaType=ControlArea, forceTargetQInReactiveLimits=false, disableInconsistentVoltageControls=false, ignoreQPercent=false), equationSystemCreationParameters=DcEquationSystemCreationParameters(updateFlows=true, forcePhaseControlOffAndAddAngle1Var=true, useTransformerRatio=true, dcApproximationType=IGNORE_R), matrixFactory=DenseMatrixFactory, distributedSlack=true, balanceType=PROPORTIONAL_TO_GENERATION_P_MAX, setVToNan=true, maxOuterLoopIterations=20)",
                dcParameters.toString());
    }

    @Test
    void testAcParameters() {
        Network network = Mockito.mock(Network.class);
        AcLoadFlowParameters acParameters = OpenLoadFlowParameters.createAcParameters(network, new LoadFlowParameters().setReadSlackBus(true), new OpenLoadFlowParameters(), new DenseMatrixFactory(), new EvenShiloachGraphDecrementalConnectivityFactory<>(), false, false);
        assertEquals("AcLoadFlowParameters(networkParameters=LfNetworkParameters(slackBusSelector=NetworkSlackBusSelector, connectivityFactory=EvenShiloachGraphDecrementalConnectivityFactory, generatorVoltageRemoteControl=true, minImpedance=false, twtSplitShuntAdmittance=false, breakers=false, plausibleActivePowerLimit=5000.0, computeMainConnectedComponentOnly=true, countriesToBalance=[], distributedOnConformLoad=false, phaseControl=false, transformerVoltageControl=false, voltagePerReactivePowerControl=false, generatorReactivePowerRemoteControl=false, transformerReactivePowerControl=false, loadFlowModel=AC, reactiveLimits=true, hvdcAcEmulation=true, minPlausibleTargetVoltage=0.8, maxPlausibleTargetVoltage=1.2, loaderPostProcessorSelection=[], reactiveRangeCheckMode=MAX, lowImpedanceThreshold=1.0E-8, svcVoltageMonitoring=true, maxSlackBusCount=1, debugDir=null, secondaryVoltageControl=false, cacheEnabled=false, asymmetrical=false, minNominalVoltageTargetVoltageCheck=20.0, linePerUnitMode=IMPEDANCE, useLoadModel=false, simulateAutomationSystems=false, referenceBusSelector=ReferenceBusFirstSlackSelector, voltageTargetPriorities=[GENERATOR, TRANSFORMER, SHUNT], fictitiousGeneratorVoltageControlCheckMode=FORCED, areaInterchangeControl=false, areaInterchangeControlAreaType=ControlArea, forceTargetQInReactiveLimits=false, disableInconsistentVoltageControls=false, ignoreQPercent=false), equationSystemCreationParameters=AcEquationSystemCreationParameters(forceA1Var=false), acSolverParameters=NewtonRaphsonParameters(maxIterations=15, stoppingCriteria=DefaultNewtonRaphsonStoppingCriteria, stateVectorScalingMode=NONE, alwaysUpdateNetwork=false, lineSearchStateVectorScalingMaxIteration=10, lineSearchStateVectorScalingStepFold=1.3333333333333333, maxVoltageChangeStateVectorScalingMaxDv=0.1, maxVoltageChangeStateVectorScalingMaxDphi=0.17453292519943295), outerLoops=[DistributedSlackOuterLoop, MonitoringVoltageOuterLoop, ReactiveLimitsOuterLoop], maxOuterLoopIterations=20, matrixFactory=DenseMatrixFactory, voltageInitializer=UniformValueVoltageInitializer, asymmetrical=false, slackDistributionFailureBehavior=LEAVE_ON_SLACK_BUS, solverFactory=NewtonRaphsonFactory, detailedReport=false, voltageRemoteControlRobustMode=true, minRealisticVoltage=0.5, maxRealisticVoltage=2.0)",
                     acParameters.toString());
    }

    private static VoltageInitializer getExtendedVoltageInitializer(Network network, LoadFlowParameters parameters, OpenLoadFlowParameters parametersExt) {
        LfNetworkParameters networkParameters = OpenLoadFlowParameters.getNetworkParameters(parameters, parametersExt, new FirstSlackBusSelector(), new EvenShiloachGraphDecrementalConnectivityFactory<>(), false);
        return OpenLoadFlowParameters.getExtendedVoltageInitializer(parameters, parametersExt, networkParameters, new DenseMatrixFactory());
    }

    @Test
    void testGetExtendedVoltageInitializer() {
        Network network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());
        LoadFlowParameters parameters = new LoadFlowParameters();
        OpenLoadFlowParameters parametersExt = new OpenLoadFlowParameters();
        assertTrue(getExtendedVoltageInitializer(network, parameters, parametersExt) instanceof UniformValueVoltageInitializer);
        parameters.setVoltageInitMode(LoadFlowParameters.VoltageInitMode.PREVIOUS_VALUES);
        assertTrue(getExtendedVoltageInitializer(network, parameters, parametersExt) instanceof PreviousValueVoltageInitializer);
        parameters.setVoltageInitMode(LoadFlowParameters.VoltageInitMode.DC_VALUES);
        assertTrue(getExtendedVoltageInitializer(network, parameters, parametersExt) instanceof DcValueVoltageInitializer);
        parametersExt.setVoltageInitModeOverride(OpenLoadFlowParameters.VoltageInitModeOverride.VOLTAGE_MAGNITUDE);
        assertTrue(getExtendedVoltageInitializer(network, parameters, parametersExt) instanceof VoltageMagnitudeInitializer);
        parametersExt.setVoltageInitModeOverride(OpenLoadFlowParameters.VoltageInitModeOverride.FULL_VOLTAGE);
        assertTrue(getExtendedVoltageInitializer(network, parameters, parametersExt) instanceof FullVoltageInitializer);
    }

    @Test
    void specificParametersTest() {
        OpenLoadFlowProvider provider = new OpenLoadFlowProvider();
        assertEquals(75, provider.getSpecificParameters().size());
        LoadFlowParameters parameters = new LoadFlowParameters();

        provider.loadSpecificParameters(Collections.emptyMap())
                .ifPresent(parametersExt -> parameters.addExtension((Class) parametersExt.getClass(), parametersExt));
        assertEquals(SlackBusSelectionMode.MOST_MESHED, parameters.getExtension(OpenLoadFlowParameters.class).getSlackBusSelectionMode());

        provider.loadSpecificParameters(Map.of(OpenLoadFlowParameters.SLACK_BUS_SELECTION_MODE_PARAM_NAME, SlackBusSelectionMode.FIRST.name()))
                .ifPresent(parametersExt -> parameters.addExtension((Class) parametersExt.getClass(), parametersExt));
        assertEquals(SlackBusSelectionMode.FIRST, parameters.getExtension(OpenLoadFlowParameters.class).getSlackBusSelectionMode());
        Map<String, String> updateParametersMap = new HashMap<>();
        updateParametersMap.put("slackBusSelectionMode", "MOST_MESHED");
        updateParametersMap.put("voltageRemoteControl", "false");
        provider.updateSpecificParameters(parameters.getExtension(OpenLoadFlowParameters.class), updateParametersMap);
        assertEquals(SlackBusSelectionMode.MOST_MESHED, parameters.getExtension(OpenLoadFlowParameters.class).getSlackBusSelectionMode());
        assertFalse(parameters.getExtension(OpenLoadFlowParameters.class).isVoltageRemoteControl());
    }

    @Test
    void testCreateMapFromSpecificParameters() {
        OpenLoadFlowParameters parametersExt = new OpenLoadFlowParameters();
        OpenLoadFlowProvider provider = new OpenLoadFlowProvider();
        Map<String, String> map = provider.createMapFromSpecificParameters(parametersExt);
        assertEquals(75, map.size());
        assertEquals(provider.getSpecificParameters().size(), map.size());
    }

    @Test
    void testSpecificParametersClass() {
        assertSame(OpenLoadFlowParameters.class, new OpenLoadFlowProvider(new DenseMatrixFactory()).getSpecificParametersClass().orElseThrow());
    }
}
