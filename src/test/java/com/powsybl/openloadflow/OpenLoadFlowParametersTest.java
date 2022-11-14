/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.powsybl.commons.config.InMemoryPlatformConfig;
import com.powsybl.commons.config.MapModuleConfig;
import com.powsybl.commons.config.PlatformConfig;
import com.powsybl.commons.config.YamlModuleConfigRepository;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.extensions.SlackTerminal;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowResult;
import com.powsybl.math.matrix.DenseMatrixFactory;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.network.impl.Networks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author Jérémy Labous <jlabous at silicom.fr>
 */
class OpenLoadFlowParametersTest {

    private InMemoryPlatformConfig platformConfig;

    private FileSystem fileSystem;

    public static final double DELTA_MISMATCH = 1E-4d;

    @BeforeEach
    public void setUp() {
        fileSystem = Jimfs.newFileSystem(Configuration.unix());
        platformConfig = new InMemoryPlatformConfig(fileSystem);

        MapModuleConfig lfModuleConfig = platformConfig.createModuleConfig("load-flow-default-parameters");
        lfModuleConfig.setStringProperty("voltageInitMode", LoadFlowParameters.VoltageInitMode.DC_VALUES.toString());
        lfModuleConfig.setStringProperty("transformerVoltageControlOn", Boolean.toString(true));
        lfModuleConfig.setStringProperty("balanceType", LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD.toString());
        lfModuleConfig.setStringProperty("dc", Boolean.toString(true));
    }

    @AfterEach
    public void tearDown() throws IOException {
        fileSystem.close();
    }

    @Test
    void testConfig() {
        MapModuleConfig olfModuleConfig = platformConfig.createModuleConfig("open-loadflow-default-parameters");
        olfModuleConfig.setStringProperty("slackBusSelectionMode", "FIRST");

        LoadFlowParameters parameters = LoadFlowParameters.load(platformConfig);

        assertEquals(LoadFlowParameters.VoltageInitMode.DC_VALUES, parameters.getVoltageInitMode());
        assertTrue(parameters.isTransformerVoltageControlOn());
        assertEquals(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD, parameters.getBalanceType());
        assertTrue(parameters.isDc());
        assertTrue(parameters.isDistributedSlack());

        OpenLoadFlowParameters olfParameters = parameters.getExtension(OpenLoadFlowParameters.class);
        assertEquals(SlackBusSelectionMode.FIRST, olfParameters.getSlackBusSelectionMode());
        assertFalse(olfParameters.isThrowsExceptionInCaseOfSlackDistributionFailure());
        assertTrue(olfParameters.hasVoltageRemoteControl());
        assertEquals(OpenLoadFlowParameters.LOW_IMPEDANCE_BRANCH_MODE_DEFAULT_VALUE, olfParameters.getLowImpedanceBranchMode());
        assertEquals(LfNetworkParameters.MIN_PLAUSIBLE_TARGET_VOLTAGE_DEFAULT_VALUE, olfParameters.getMinPlausibleTargetVoltage());
        assertEquals(LfNetworkParameters.MAX_PLAUSIBLE_TARGET_VOLTAGE_DEFAULT_VALUE, olfParameters.getMaxPlausibleTargetVoltage());
    }

    @Test
    void testDefaultOpenLoadflowConfig() {
        LoadFlowParameters parameters = LoadFlowParameters.load(platformConfig);

        assertEquals(LoadFlowParameters.VoltageInitMode.DC_VALUES, parameters.getVoltageInitMode());
        assertTrue(parameters.isTransformerVoltageControlOn());
        assertEquals(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD, parameters.getBalanceType());
        assertTrue(parameters.isDc());
        assertTrue(parameters.isDistributedSlack());

        OpenLoadFlowParameters olfParameters = parameters.getExtension(OpenLoadFlowParameters.class);
        assertEquals(OpenLoadFlowParameters.SLACK_BUS_SELECTION_DEFAULT_VALUE, olfParameters.getSlackBusSelectionMode());
        assertEquals(OpenLoadFlowParameters.VOLTAGE_REMOTE_CONTROL_DEFAULT_VALUE, olfParameters.hasVoltageRemoteControl());
        assertEquals(OpenLoadFlowParameters.LOW_IMPEDANCE_BRANCH_MODE_DEFAULT_VALUE, olfParameters.getLowImpedanceBranchMode());
        assertEquals(OpenLoadFlowParameters.THROWS_EXCEPTION_IN_CASE_OF_SLACK_DISTRIBUTION_FAILURE_DEFAULT_VALUE, olfParameters.isThrowsExceptionInCaseOfSlackDistributionFailure());
        assertEquals(OpenLoadFlowParameters.SLACK_BUS_P_MAX_MISMATCH_DEFAULT_VALUE, olfParameters.getSlackBusPMaxMismatch(), 0.0);
        assertEquals(OpenLoadFlowParameters.REACTIVE_POWER_REMOTE_CONTROL_DEFAULT_VALUE, olfParameters.hasReactivePowerRemoteControl());
        assertEquals(OpenLoadFlowParameters.DC_POWER_FACTOR_DEFAULT_VALUE, olfParameters.getDcPowerFactor());
        assertEquals(OpenLoadFlowParameters.LOW_IMPEDANCE_THRESHOLD_DEFAULT_VALUE, olfParameters.getLowImpedanceThreshold());
    }

    @Test
    void testInvalidOpenLoadflowConfig() {
        MapModuleConfig olfModuleConfig = platformConfig.createModuleConfig("open-loadflow-default-parameters");
        olfModuleConfig.setStringProperty("slackBusSelectionMode", "Invalid");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> LoadFlowParameters.load(platformConfig));
        assertEquals("No enum constant com.powsybl.openloadflow.network.SlackBusSelectionMode.Invalid", exception.getMessage());
    }

    @Test
    void testFirstSlackBusSelector() throws IOException {
        Path cfgDir = Files.createDirectory(fileSystem.getPath("config"));
        Path cfgFile = cfgDir.resolve("configFirstSlackBusSelector.yml");

        Files.copy(getClass().getResourceAsStream("/configFirstSlackBusSelector.yml"), cfgFile);
        PlatformConfig platformConfig = new PlatformConfig(new YamlModuleConfigRepository(cfgFile), cfgDir);

        LoadFlowParameters parameters = LoadFlowParameters.load(platformConfig);
        OpenLoadFlowParameters olfParameters = parameters.getExtension(OpenLoadFlowParameters.class);
        assertEquals(SlackBusSelectionMode.FIRST, olfParameters.getSlackBusSelectionMode());
    }

    @Test
    void testMostMeshedSlackBusSelector() throws IOException {
        Path cfgDir = Files.createDirectory(fileSystem.getPath("config"));
        Path cfgFile = cfgDir.resolve("configMostMeshedSlackBusSelector.yml");

        Files.copy(getClass().getResourceAsStream("/configMostMeshedSlackBusSelector.yml"), cfgFile);
        PlatformConfig platformConfig = new PlatformConfig(new YamlModuleConfigRepository(cfgFile), cfgDir);

        LoadFlowParameters parameters = LoadFlowParameters.load(platformConfig);
        OpenLoadFlowParameters olfParameters = parameters.getExtension(OpenLoadFlowParameters.class);
        assertEquals(SlackBusSelectionMode.MOST_MESHED, olfParameters.getSlackBusSelectionMode());
    }

    @Test
    void testNameSlackBusSelector() throws IOException {
        Path cfgDir = Files.createDirectory(fileSystem.getPath("config"));
        Path cfgFile = cfgDir.resolve("configNameSlackBusSelector.yml");

        Files.copy(getClass().getResourceAsStream("/configNameSlackBusSelector.yml"), cfgFile);
        PlatformConfig platformConfig = new PlatformConfig(new YamlModuleConfigRepository(cfgFile), cfgDir);

        LoadFlowParameters parameters = LoadFlowParameters.load(platformConfig);
        OpenLoadFlowParameters olfParameters = parameters.getExtension(OpenLoadFlowParameters.class);
        assertEquals(SlackBusSelectionMode.NAME, olfParameters.getSlackBusSelectionMode());
        List<LfNetwork> lfNetworks = Networks.load(EurostagTutorialExample1Factory.create(), SlackBusSelector.fromMode(olfParameters.getSlackBusSelectionMode(), olfParameters.getSlackBusesIds(), olfParameters.getPlausibleActivePowerLimit()));
        LfNetwork lfNetwork = lfNetworks.get(0);
        assertEquals("VLHV1_0", lfNetwork.getSlackBus().getId()); // fallback to automatic method.
    }

    @Test
    void testMaxIterationReached() {
        LoadFlowParameters parameters = LoadFlowParameters.load();
        parameters.setWriteSlackBus(true);
        Network network = EurostagTutorialExample1Factory.create();
        LoadFlow.Runner loadFlowRunner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));

        // Change the nominal voltage to have a target V distant enough but still plausible (in [0.8 1.2] in Pu), so that the NR diverges
        network.getVoltageLevel("VLGEN").setNominalV(100);
        network.getGenerator("GEN").setTargetV(120);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertEquals(LoadFlowResult.ComponentResult.Status.MAX_ITERATION_REACHED, result.getComponentResults().get(0).getStatus());
    }

    @Test
    void testIsWriteSlackBus() {
        LoadFlowParameters parameters = LoadFlowParameters.load();
        parameters.setWriteSlackBus(true);
        Network network = EurostagTutorialExample1Factory.create();
        LoadFlow.Runner loadFlowRunner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertEquals(network.getVoltageLevel("VLHV1").getExtension(SlackTerminal.class).getTerminal().getBusView().getBus().getId(),
                result.getComponentResults().get(0).getSlackBusId());
    }

    @Test
    void testSetSlackBusPMaxMismatch() {
        LoadFlowParameters parameters = LoadFlowParameters.load();
        Network network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());
        LoadFlow.Runner loadFlowRunner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertEquals(-0.004, result.getComponentResults().get(0).getSlackBusActivePowerMismatch(), DELTA_MISMATCH);

        parameters.getExtension(OpenLoadFlowParameters.class).setSlackBusPMaxMismatch(0.0001);
        LoadFlow.Runner loadFlowRunner2 = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
        LoadFlowResult result2 = loadFlowRunner2.run(network, parameters);
        assertEquals(-1.8703e-5, result2.getComponentResults().get(0).getSlackBusActivePowerMismatch(), DELTA_MISMATCH);
    }

    @Test
    void testPlausibleTargetVoltage() {
        LoadFlowParameters parameters = LoadFlowParameters.load();
        Network network = EurostagTutorialExample1Factory.create();
        network.getGenerator("GEN").setTargetV(30.0);
        LoadFlow.Runner loadFlowRunner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
        loadFlowRunner.run(network, parameters);
        assertTrue(Double.isNaN(network.getGenerator("GEN").getRegulatingTerminal().getBusView().getBus().getV())); // no calculation
        parameters.getExtension(OpenLoadFlowParameters.class).setMaxPlausibleTargetVoltage(1.3);
        loadFlowRunner.run(network, parameters);
        assertEquals(30.0, network.getGenerator("GEN").getRegulatingTerminal().getBusView().getBus().getV(), DELTA_MISMATCH);
    }

    @Test
    void testUpdateParameters() {
        Map<String, String> parametersMap = new HashMap<>();
        parametersMap.put("slackBusSelectionMode", "FIRST");
        parametersMap.put("voltageRemoteControl", "true");
        parametersMap.put("reactivePowerRemoteControl", "false");
        OpenLoadFlowParameters parameters = OpenLoadFlowParameters.load(parametersMap);
        assertEquals(SlackBusSelectionMode.FIRST, parameters.getSlackBusSelectionMode());
        assertTrue(parameters.hasVoltageRemoteControl());
        assertFalse(parameters.hasReactivePowerRemoteControl());
        Map<String, String> updateParametersMap = new HashMap<>();
        updateParametersMap.put("slackBusSelectionMode", "MOST_MESHED");
        updateParametersMap.put("voltageRemoteControl", "false");
        updateParametersMap.put("maxIteration", "10");
        parameters.update(updateParametersMap);
        assertEquals(SlackBusSelectionMode.MOST_MESHED, parameters.getSlackBusSelectionMode());
        assertFalse(parameters.hasVoltageRemoteControl());
        assertEquals(10, parameters.getMaxIteration());
        assertFalse(parameters.hasReactivePowerRemoteControl());
    }
}
