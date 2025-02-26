/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * Copyright (c) 2023, Coreso SA (https://www.coreso.eu/) and TSCNET Services GmbH (https://www.tscnet.eu/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac;

import com.powsybl.commons.report.ReportNode;
import com.powsybl.computation.local.LocalComputationManager;
import com.powsybl.iidm.network.*;
import com.powsybl.iidm.network.extensions.ActivePowerControl;
import com.powsybl.iidm.network.extensions.ReferencePriorities;
import com.powsybl.iidm.network.extensions.ReferencePriority;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowResult;
import com.powsybl.math.matrix.DenseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.OpenLoadFlowProvider;
import com.powsybl.openloadflow.network.DistributedSlackNetworkFactory;
import com.powsybl.openloadflow.network.EurostagFactory;
import com.powsybl.openloadflow.network.ReferenceBusSelectionMode;
import com.powsybl.openloadflow.network.SlackBusSelectionMode;
import com.powsybl.openloadflow.util.LoadFlowAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.concurrent.CompletionException;

import static com.powsybl.openloadflow.util.LoadFlowAssert.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 * @author Caio Luke {@literal <caio.luke at artelys.com>}
 */
class DistributedSlackOnGenerationTest {

    private Network network;
    private Generator g1;
    private Generator g2;
    private Generator g3;
    private Generator g4;
    private LoadFlow.Runner loadFlowRunner;
    private LoadFlowParameters parameters;
    private OpenLoadFlowParameters parametersExt;
    private ReportNode reportNode;

    @BeforeEach
    void setUp() {
        network = DistributedSlackNetworkFactory.create();
        g1 = network.getGenerator("g1");
        g2 = network.getGenerator("g2");
        g3 = network.getGenerator("g3");
        g4 = network.getGenerator("g4");
        loadFlowRunner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
        // Note that in core, default balance type is proportional to generation Pmax
        parameters = new LoadFlowParameters().setUseReactiveLimits(false)
                .setDistributedSlack(true);
        parametersExt = OpenLoadFlowParameters.create(parameters)
                .setSlackBusSelectionMode(SlackBusSelectionMode.MOST_MESHED)
                .setSlackDistributionFailureBehavior(OpenLoadFlowParameters.SlackDistributionFailureBehavior.THROW);
        reportNode = ReportNode.newRootReportNode().withMessageTemplate("test", "test").build();
    }

    @Test
    void test() {
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertEquals(1, result.getComponentResults().size());
        assertEquals(120, result.getComponentResults().get(0).getDistributedActivePower(), LoadFlowAssert.DELTA_POWER);
        assertActivePowerEquals(-115, g1.getTerminal());
        assertActivePowerEquals(-245, g2.getTerminal());
        assertActivePowerEquals(-105, g3.getTerminal());
        assertActivePowerEquals(-135, g4.getTerminal());
        assertReactivePowerEquals(159.746, g1.getTerminal());
        Line l14 = network.getLine("l14");
        Line l24 = network.getLine("l24");
        Line l34 = network.getLine("l34");
        assertActivePowerEquals(115, l14.getTerminal1());
        assertActivePowerEquals(-115, l14.getTerminal2());
        assertActivePowerEquals(245, l24.getTerminal1());
        assertActivePowerEquals(-245, l24.getTerminal2());
        assertActivePowerEquals(240, l34.getTerminal1());
        assertActivePowerEquals(-240, l34.getTerminal2());
    }

    @Test
    void testProportionalToP() {
        // decrease g1 max limit power, so that distributed slack algo reach the g1 max
        g1.setMaxP(105);
        parameters.setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);

        assertTrue(result.isFullyConverged());
        assertActivePowerEquals(-105, g1.getTerminal());
        assertActivePowerEquals(-260.526, g2.getTerminal());
        assertActivePowerEquals(-117.236, g3.getTerminal());
        assertActivePowerEquals(-117.236, g4.getTerminal());
    }

    @Test
    void testProportionalToPWithTargetLimit() {
        // decrease g1 max limit power, so that distributed slack algo reach the g1 max
        g1.setMaxP(105);
        g1.getExtension(ActivePowerControl.class).setMaxTargetP(103);
        parameters.setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);

        assertTrue(result.isFullyConverged());
        assertActivePowerEquals(-103, g1.getTerminal());
        assertActivePowerEquals(-261.579, g2.getTerminal());
        assertActivePowerEquals(-117.711, g3.getTerminal());
        assertActivePowerEquals(-117.711, g4.getTerminal());

        // now compensation down
        Load l1 = network.getLoad("l1");
        l1.setP0(400);  // was 600
        result = loadFlowRunner.run(network, parameters);

        assertTrue(result.isFullyConverged());
        assertActivePowerEquals(-83.333, g1.getTerminal());
        assertActivePowerEquals(-166.667, g2.getTerminal());
        assertActivePowerEquals(-75.000, g3.getTerminal());
        assertActivePowerEquals(-75.000, g4.getTerminal());

        // With a minTargetP for g1
        g1.getExtension(ActivePowerControl.class).setMinTargetP(85);

        result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertActivePowerEquals(-85, g1.getTerminal());
        assertActivePowerEquals(-165.790, g2.getTerminal());
        assertActivePowerEquals(-74.605, g3.getTerminal());
        assertActivePowerEquals(-74.605, g4.getTerminal());
    }

    @Test
    void testProportionalToPMaxWithTargetLimit() {
        g1.setMaxP(150);
        parameters.setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX);
        //Check that maxTargetP has no influence on the computed participation factor but only on the limit hitting
        g1.getExtension(ActivePowerControl.class).setMaxTargetP(120);

        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertActivePowerEquals(-111.613, g1.getTerminal());
        assertActivePowerEquals(-246.452, g2.getTerminal());
        assertActivePowerEquals(-105.484, g3.getTerminal());
        assertActivePowerEquals(-136.451, g4.getTerminal());

        g1.getExtension(ActivePowerControl.class).setDroop(2.0); //Changing droop to change corresponding participation factor
        result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertActivePowerEquals(-120.0, g1.getTerminal()); // This time the limit is hit
        assertActivePowerEquals(-242.857, g2.getTerminal());
        assertActivePowerEquals(-104.285, g3.getTerminal());
        assertActivePowerEquals(-132.857, g4.getTerminal());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testProportionalToParticipationFactor() {
        // decrease g1 max limit power, so that distributed slack algo reach the g1 max
        g1.setMaxP(100);

        // set participationFactor
        // g1 NaN participationFactor should be discarded
        g1.getExtension(ActivePowerControl.class).setParticipationFactor(Double.NaN);
        g2.getExtension(ActivePowerControl.class).setParticipationFactor(3.0);
        g3.getExtension(ActivePowerControl.class).setParticipationFactor(1.0);
        g4.getExtension(ActivePowerControl.class).setParticipationFactor(-4.0); // Should be discarded

        parameters.setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_PARTICIPATION_FACTOR);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);

        assertTrue(result.isFullyConverged());
        assertActivePowerEquals(-100, g1.getTerminal());
        assertActivePowerEquals(-290, g2.getTerminal());
        assertActivePowerEquals(-120, g3.getTerminal());
        assertActivePowerEquals(-90, g4.getTerminal());
    }

    @Test
    void testProportionalToRemainingMarginUp() {
        // decrease g1 max limit power, so that distributed slack algo reach the g1 max
        g1.setMaxP(105);

        parameters.setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_REMAINING_MARGIN);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);

        assertTrue(result.isFullyConverged());
        assertActivePowerEquals(-102.667, g1.getTerminal());
        assertActivePowerEquals(-253.333, g2.getTerminal());
        assertActivePowerEquals(-122.0, g3.getTerminal());
        assertActivePowerEquals(-122.0, g4.getTerminal());
    }

    @Test
    void testProportionalToRemainingMarginDown() {
        // Decrease load P0, so that active mismatch is negative
        network.getLoad("l1").setP0(400);

        parameters.setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_REMAINING_MARGIN);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);

        assertTrue(result.isFullyConverged());
        assertActivePowerEquals(-71.428, g1.getTerminal());
        assertActivePowerEquals(-171.428, g2.getTerminal());
        assertActivePowerEquals(-78.571, g3.getTerminal());
        assertActivePowerEquals(-78.571, g4.getTerminal());
    }

    @Test
    void testProportionalToRemainingMarginPmaxBelowTargetP() {
        // decrease g1 max limit power below target P
        g1.setMaxP(90);

        parameters.setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_REMAINING_MARGIN);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);

        assertTrue(result.isFullyConverged());
        assertActivePowerEquals(-100.0, g1.getTerminal());
        assertActivePowerEquals(-254.545, g2.getTerminal());
        assertActivePowerEquals(-122.727, g3.getTerminal());
        assertActivePowerEquals(-122.727, g4.getTerminal());
    }

    @Test
    void maxTest() {
        // decrease g1 max limit power, so that distributed slack algo reach the g1 max
        g1.setMaxP(105);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertActivePowerEquals(-105, g1.getTerminal());
        assertActivePowerEquals(-249.285, g2.getTerminal());
        assertActivePowerEquals(-106.428, g3.getTerminal());
        assertActivePowerEquals(-139.285, g4.getTerminal());
    }

    @Test
    void minTest() {
        // increase g1 min limit power and global load so that distributed slack algo reach the g1 min
        g1.setMinP(95);
        network.getLoad("l1").setP0(400);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertActivePowerEquals(-95, g1.getTerminal());
        assertActivePowerEquals(-167.857, g2.getTerminal());
        assertActivePowerEquals(-79.285, g3.getTerminal());
        assertActivePowerEquals(-57.857, g4.getTerminal());
    }

    @Test
    void maxTestActivePowerLimitDisabled() {
        parametersExt.setUseActiveLimits(false);
        // decrease g1 max limit power, so that distributed slack algo reach the g1 max
        // Because we disabled active power limits, g1 will exceed max
        g1.setMaxP(105);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertActivePowerEquals(-108.372, g1.getTerminal());
        assertActivePowerEquals(-247.840, g2.getTerminal());
        assertActivePowerEquals(-105.946, g3.getTerminal());
        assertActivePowerEquals(-137.840, g4.getTerminal());
        assertEquals(120, result.getComponentResults().get(0).getDistributedActivePower(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void minTestActivePowerLimitDisabled() {
        parametersExt.setUseActiveLimits(false);
        // increase g1 min limit power and global load so that distributed slack algo reach the g1 min
        // Because we disabled active power limits, g1 will exceed min
        g1.setMinP(95);
        network.getLoad("l1").setP0(400);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertActivePowerEquals(-90, g1.getTerminal());
        assertActivePowerEquals(-170, g2.getTerminal());
        assertActivePowerEquals(-80, g3.getTerminal());
        assertActivePowerEquals(-60, g4.getTerminal());
    }

    @Test
    void targetBelowMinAndActivePowerLimitDisabled() {
        parametersExt.setUseActiveLimits(false);
        g1.setMinP(100); // was 0
        g1.setTargetP(80);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertActivePowerEquals(-97.5, g1.getTerminal()); // allowed to participate even though targetP < minP
        assertActivePowerEquals(-252.5, g2.getTerminal());
        assertActivePowerEquals(-107.5, g3.getTerminal());
        assertActivePowerEquals(-142.5, g4.getTerminal());
        assertEquals(140, result.getComponentResults().get(0).getDistributedActivePower(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void targetAboveMaxAndActivePowerLimitDisabled() {
        parametersExt.setUseActiveLimits(false);
        g1.setTargetP(240); // max is 200
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertActivePowerEquals(-237.5, g1.getTerminal()); // allowed to participate even though targetP > maxP
        assertActivePowerEquals(-192.5, g2.getTerminal());
        assertActivePowerEquals(-87.5, g3.getTerminal());
        assertActivePowerEquals(-82.5, g4.getTerminal());
        assertEquals(-20.0, result.getComponentResults().get(0).getDistributedActivePower(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void targetBelowPositiveMinTest() {
        // g1 targetP below positive minP (e.g. unit starting up / ramping)
        g1.setMinP(100);
        g1.setTargetP(80);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertActivePowerEquals(-80.0, g1.getTerminal()); // stays at targetP
        assertActivePowerEquals(-260.0, g2.getTerminal());
        assertActivePowerEquals(-110.0, g3.getTerminal());
        assertActivePowerEquals(-150.0, g4.getTerminal());
        assertEquals(140, result.getComponentResults().get(0).getDistributedActivePower(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void targetBelowZeroMinTest() {
        // g1 targetP below zero minP (e.g. unit modelled lumped with station supply and not producing but consuming a little bit)
        g1.setMinP(0);
        g1.setTargetP(-20);
        network.getLoad("l1").setP0(500);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertActivePowerEquals(20.0, g1.getTerminal()); // stays at targetP
        assertActivePowerEquals(-260.0, g2.getTerminal());
        assertActivePowerEquals(-110.0, g3.getTerminal());
        assertActivePowerEquals(-150.0, g4.getTerminal());
        assertEquals(140, result.getComponentResults().get(0).getDistributedActivePower(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void targetBelowNegativeMinTest() {
        // g1 targetP below negative minP (e.g. generator pumping more than tech limit)
        g1.setMinP(-100);
        g1.setTargetP(-120);
        network.getLoad("l1").setP0(400);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertActivePowerEquals(120.0, g1.getTerminal()); // stays at targetP
        assertActivePowerEquals(-260.0, g2.getTerminal());
        assertActivePowerEquals(-110.0, g3.getTerminal());
        assertActivePowerEquals(-150.0, g4.getTerminal());
        assertEquals(140, result.getComponentResults().get(0).getDistributedActivePower(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    @SuppressWarnings("unchecked")
    void zeroParticipatingGeneratorsThrowTest() {
        g1.getExtension(ActivePowerControl.class).setDroop(2);
        g2.getExtension(ActivePowerControl.class).setDroop(-3);
        g3.getExtension(ActivePowerControl.class).setDroop(0);
        g4.getExtension(ActivePowerControl.class).setDroop(0);
        CompletionException thrown = assertThrows(CompletionException.class, () -> loadFlowRunner.run(network, parameters));
        assertTrue(thrown.getCause().getMessage().startsWith("Failed to distribute slack bus active power mismatch, "));
    }

    @Test
    void notEnoughActivePowerThrowTest() {
        network.getLoad("l1").setP0(1000);
        CompletionException thrown = assertThrows(CompletionException.class, () -> loadFlowRunner.run(network, parameters));
        assertTrue(thrown.getCause().getMessage().startsWith("Failed to distribute slack bus active power mismatch, "));
    }

    @Test
    void notEnoughActivePowerFailTest() {
        network.getLoad("l1").setP0(1000);
        parametersExt.setSlackDistributionFailureBehavior(OpenLoadFlowParameters.SlackDistributionFailureBehavior.FAIL);
        LoadFlowResult result = loadFlowRunner.run(network, network.getVariantManager().getWorkingVariantId(), LocalComputationManager.getDefault(), parameters, reportNode);
        LoadFlowResult.ComponentResult componentResult = result.getComponentResults().get(0);
        assertFalse(result.isFullyConverged());
        assertEquals(LoadFlowResult.ComponentResult.Status.FAILED, componentResult.getStatus());
        assertEquals("Outer loop failed: Failed to distribute slack bus active power mismatch, 200.00 MW remains", componentResult.getStatusText());
        assertEquals(0, componentResult.getDistributedActivePower(), 1e-4);
        assertEquals(520, componentResult.getSlackBusResults().get(0).getActivePowerMismatch(), 1e-4);
        assertReportContains("Failed to distribute slack bus active power mismatch, [-+]?\\d*\\.\\d* MW remains", reportNode);
    }

    @Test
    void notEnoughActivePowerLeaveOnSlackBusTest() {
        network.getLoad("l1").setP0(1000);
        parametersExt.setSlackDistributionFailureBehavior(OpenLoadFlowParameters.SlackDistributionFailureBehavior.LEAVE_ON_SLACK_BUS);
        LoadFlowResult result = loadFlowRunner.run(network, network.getVariantManager().getWorkingVariantId(), LocalComputationManager.getDefault(), parameters, reportNode);
        LoadFlowResult.ComponentResult componentResult = result.getComponentResults().get(0);
        assertTrue(result.isFullyConverged());
        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, componentResult.getStatus());
        assertEquals(320, componentResult.getDistributedActivePower(), 1e-4);
        assertEquals(200, componentResult.getSlackBusResults().get(0).getActivePowerMismatch(), 1e-4);
        assertReportContains("Failed to distribute slack bus active power mismatch, [-+]?\\d*\\.\\d* MW remains", reportNode);
    }

    @Test
    void notEnoughActivePowerDistributeReferenceGeneratorTest() {
        network.getLoad("l1").setP0(1000);
        ReferencePriority.set(g1, 1);
        g1.setMaxP(200.);
        parametersExt
                .setReferenceBusSelectionMode(ReferenceBusSelectionMode.GENERATOR_REFERENCE_PRIORITY)
                .setSlackDistributionFailureBehavior(OpenLoadFlowParameters.SlackDistributionFailureBehavior.DISTRIBUTE_ON_REFERENCE_GENERATOR);
        LoadFlowResult result = loadFlowRunner.run(network, network.getVariantManager().getWorkingVariantId(), LocalComputationManager.getDefault(), parameters, reportNode);
        LoadFlowResult.ComponentResult componentResult = result.getComponentResults().get(0);
        assertTrue(result.isFullyConverged());
        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, componentResult.getStatus());
        // DistributedActivePower: 520MW, breakdown:
        // - 320MW by all 4 generators hitting maxP limit
        // - 200MW by distributing on reference generator g1
        assertEquals(520., componentResult.getDistributedActivePower(), 1e-3);
        assertEquals(0., componentResult.getSlackBusResults().get(0).getActivePowerMismatch(), 1e-3);
        assertAngleEquals(0., g1.getTerminal().getBusView().getBus());
        // can exceed maxP (200MW)
        assertActivePowerEquals(-400., g1.getTerminal());
        assertReportContains("Slack bus active power \\([-+]?\\d*\\.\\d* MW\\) distributed in 3 distribution iteration\\(s\\)", reportNode);
    }

    @Test
    void notEnoughActivePowerDistributeNoReferenceGeneratorTest() {
        network.getLoad("l1").setP0(1000);
        ReferencePriority.set(g1, 1);
        g1.setMaxP(200.);
        // We request to distribute on reference generator, but ReferenceBusSelectionMode is FIRST_SLACK.
        // FIRST_SLACK mode does not select a reference generator, therefore internally we switch to FAIL mode.
        parametersExt
                .setReferenceBusSelectionMode(ReferenceBusSelectionMode.FIRST_SLACK)
                .setSlackDistributionFailureBehavior(OpenLoadFlowParameters.SlackDistributionFailureBehavior.DISTRIBUTE_ON_REFERENCE_GENERATOR);
        LoadFlowResult result = loadFlowRunner.run(network, network.getVariantManager().getWorkingVariantId(), LocalComputationManager.getDefault(), parameters, reportNode);
        LoadFlowResult.ComponentResult componentResult = result.getComponentResults().get(0);
        assertTrue(result.isFailed());
        assertEquals(LoadFlowResult.ComponentResult.Status.FAILED, componentResult.getStatus());
        assertEquals("Outer loop failed: Failed to distribute slack bus active power mismatch, 200.00 MW remains", componentResult.getStatusText());
        assertEquals(520., componentResult.getSlackBusResults().get(0).getActivePowerMismatch(), 1e-3);
        assertReportContains("Failed to distribute slack bus active power mismatch, [-+]?\\d*\\.\\d* MW remains", reportNode);
    }

    @Test
    void generatorWithNegativeTargetP() {
        network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());
        network.getGenerator("GEN").setMaxP(1000);
        network.getGenerator("GEN").setTargetP(-607);
        network.getLoad("LOAD").setP0(-600);
        network.getLoad("LOAD").setQ0(-200);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertActivePowerEquals(595.328, network.getGenerator("GEN").getTerminal());
    }

    @Test
    void generatorWithMaxPEqualsToMinP() {
        network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());
        network.getGenerator("GEN").setMaxP(1000);
        network.getGenerator("GEN").setMinP(1000);
        network.getGenerator("GEN").setTargetP(1000);
        assertThrows(CompletionException.class, () -> loadFlowRunner.run(network, parameters),
                "Failed to distribute slack bus active power mismatch, -393.367476483181 MW remains");
    }

    @Test
    void nonParticipatingBus() {
        //B1 and B2 are located in germany the rest is in france
        Substation b1s = network.getSubstation("b1_s");
        b1s.setCountry(Country.GE);
        Substation b2s = network.getSubstation("b2_s");
        b2s.setCountry(Country.GE);

        //Only substation located in france are used
        parameters.setCountriesToBalance(EnumSet.of(Country.FR));
        LoadFlowResult result = loadFlowRunner.run(network, parameters);

        assertTrue(result.isFullyConverged());
        assertActivePowerEquals(-100, g1.getTerminal());
        assertActivePowerEquals(-200, g2.getTerminal());
        assertActivePowerEquals(-150, g3.getTerminal());
        assertActivePowerEquals(-150, g4.getTerminal());
    }

    @Test
    void generatorWithTargetPLowerThanMinP() {
        network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());
        network.getGenerator("GEN").setMaxP(1000);
        network.getGenerator("GEN").setMinP(200);
        network.getGenerator("GEN").setTargetP(100);
        network.getVoltageLevel("VLGEN").newGenerator()
                .setId("g1")
                .setBus("NGEN")
                .setConnectableBus("NGEN")
                .setEnergySource(EnergySource.THERMAL)
                .setMinP(0)
                .setMaxP(0)
                .setTargetP(0)
                .setTargetV(24.5)
                .setVoltageRegulatorOn(true)
                .add();
        assertThrows(CompletionException.class, () -> loadFlowRunner.run(network, parameters),
                "Failed to distribute slack bus active power mismatch, 504.9476825313616 MW remains");
    }

    @Test
    @SuppressWarnings("unchecked")
    void notParticipatingTest() {
        g1.getExtension(ActivePowerControl.class).setParticipate(false);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertActivePowerEquals(-100, g1.getTerminal());
        assertActivePowerEquals(-251.428, g2.getTerminal());
        assertActivePowerEquals(-107.142, g3.getTerminal());
        assertActivePowerEquals(-141.428, g4.getTerminal());
    }

    @Test
    void batteryTest() {
        network = DistributedSlackNetworkFactory.createWithBattery();
        g1 = network.getGenerator("g1");
        g2 = network.getGenerator("g2");
        g3 = network.getGenerator("g3");
        g4 = network.getGenerator("g4");
        Battery bat1 = network.getBattery("bat1");
        Battery bat2 = network.getBattery("bat2");
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertEquals(1, result.getComponentResults().size());
        assertEquals(123, result.getComponentResults().get(0).getDistributedActivePower(), LoadFlowAssert.DELTA_POWER);
        assertActivePowerEquals(-115.122, g1.getTerminal());
        assertActivePowerEquals(-245.368, g2.getTerminal());
        assertActivePowerEquals(-105.123, g3.getTerminal());
        assertActivePowerEquals(-135.369, g4.getTerminal());
        assertActivePowerEquals(-2, bat1.getTerminal());
        assertActivePowerEquals(2.983, bat2.getTerminal());
    }

    @Test
    @SuppressWarnings("unchecked")
    void batteryTestProportionalToParticipationFactor() {
        network = DistributedSlackNetworkFactory.createWithBattery();
        g1 = network.getGenerator("g1");
        g2 = network.getGenerator("g2");
        g3 = network.getGenerator("g3");
        g4 = network.getGenerator("g4");
        Battery bat1 = network.getBattery("bat1");
        Battery bat2 = network.getBattery("bat2");
        g1.getExtension(ActivePowerControl.class).setParticipationFactor(Double.NaN);
        g2.getExtension(ActivePowerControl.class).setParticipationFactor(3.0);
        g3.getExtension(ActivePowerControl.class).setParticipationFactor(1.0);
        g4.getExtension(ActivePowerControl.class).setParticipationFactor(-4.0); // Should be discarded
        parameters.setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_PARTICIPATION_FACTOR);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertEquals(1, result.getComponentResults().size());
        assertEquals(123, result.getComponentResults().get(0).getDistributedActivePower(), LoadFlowAssert.DELTA_POWER);
        assertActivePowerEquals(-100, g1.getTerminal());
        assertActivePowerEquals(-288.5, g2.getTerminal());
        assertActivePowerEquals(-119.5, g3.getTerminal());
        assertActivePowerEquals(-90, g4.getTerminal());
        assertActivePowerEquals(-2, bat1.getTerminal());
        assertActivePowerEquals(0, bat2.getTerminal());
    }

    @Test
    void testDistributedActivePower() {
        parameters.setUseReactiveLimits(true).getExtension(OpenLoadFlowParameters.class).setSlackBusPMaxMismatch(0.0001);
        network = DistributedSlackNetworkFactory.createWithLossesAndPvPqTypeSwitch();
        g1 = network.getGenerator("g1");
        g2 = network.getGenerator("g2");
        g3 = network.getGenerator("g3");
        g4 = network.getGenerator("g4");
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        // we were getting 132.47279 when computing distributedActivePower as initial NR slack - final NR slack, while difference targetP - P was only 120.1961
        var expectedDistributedActivePower = -network.getGeneratorStream().mapToDouble(g -> g.getTargetP() + g.getTerminal().getP()).sum();
        assertEquals(120.1961, expectedDistributedActivePower, LoadFlowAssert.DELTA_POWER);
        assertEquals(expectedDistributedActivePower, result.getComponentResults().get(0).getDistributedActivePower(), LoadFlowAssert.DELTA_POWER);
        assertActivePowerEquals(-115.024, g1.getTerminal());
        assertActivePowerEquals(-245.073, g2.getTerminal());
        assertActivePowerEquals(-105.024, g3.getTerminal());
        assertActivePowerEquals(-135.073, g4.getTerminal());
    }

    @Test
    void testDistributedActivePowerSlackDistributionDisabled() {
        parameters.setUseReactiveLimits(true).setDistributedSlack(false);
        network = DistributedSlackNetworkFactory.createWithLossesAndPvPqTypeSwitch();
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        // we were getting 12.307 when computing distributedActivePower as initial NR slack - final NR slack, expecting zero here
        assertEquals(0.0, result.getComponentResults().get(0).getDistributedActivePower(), LoadFlowAssert.DELTA_POWER);
    }

    @Test
    void testSlackMismatchChangingSign() {
        parameters.setUseReactiveLimits(true).getExtension(OpenLoadFlowParameters.class).setSlackBusPMaxMismatch(0.0001);
        network = DistributedSlackNetworkFactory.createWithLossesAndPvPqTypeSwitch();
        g1 = network.getGenerator("g1");
        g2 = network.getGenerator("g2");
        g3 = network.getGenerator("g3");
        g4 = network.getGenerator("g4");

        parameters.setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_PARTICIPATION_FACTOR);
        for (var g : network.getGenerators()) {
            ActivePowerControl<Generator> ext = g.getExtension(ActivePowerControl.class);
            ext.setParticipationFactor(1.0);
        }

        g1.setMaxP(110.0);
        g3.setMaxP(110.0);
        g4.setMaxP(110.0);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());

        var expectedDistributedActivePower = -network.getGeneratorStream().mapToDouble(g -> g.getTargetP() + g.getTerminal().getP()).sum();
        assertEquals(120.1976, expectedDistributedActivePower, LoadFlowAssert.DELTA_POWER);
        assertEquals(expectedDistributedActivePower, result.getComponentResults().get(0).getDistributedActivePower(), LoadFlowAssert.DELTA_POWER);

        // All generators have the same participation factor, and should increase generation by 120.1976 MW
        // generator | targetP | maxP
        // ----------|---------|-------
        //   g1      |  100    |  110  --> expected to hit limit 110MW with 10MW distributed
        //   g2      |  200    |  300  --> expected to pick up the remaining slack 70.1976 MW
        //   g3      |   90    |  110  --> expected to hit limit 110MW with 20MW distributed
        //   g4      |   90    |  110  --> expected to hit limit 110MW with 20MW distributed
        assertActivePowerEquals(-110.000, g1.getTerminal());
        assertActivePowerEquals(-270.1976, g2.getTerminal());
        assertActivePowerEquals(-110.000, g3.getTerminal());
        assertActivePowerEquals(-110.000, g4.getTerminal());
    }

    @Test
    void testSlackMismatchChangingSignReferenceGenerator() {
        parameters.setUseReactiveLimits(true).getExtension(OpenLoadFlowParameters.class).setSlackBusPMaxMismatch(0.0001);
        network = DistributedSlackNetworkFactory.createWithLossesAndPvPqTypeSwitch();
        g1 = network.getGenerator("g1");
        g2 = network.getGenerator("g2");
        g3 = network.getGenerator("g3");
        g4 = network.getGenerator("g4");

        parameters.setBalanceType(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_PARTICIPATION_FACTOR);
        parametersExt
                .setReferenceBusSelectionMode(ReferenceBusSelectionMode.GENERATOR_REFERENCE_PRIORITY)
                .setSlackDistributionFailureBehavior(OpenLoadFlowParameters.SlackDistributionFailureBehavior.DISTRIBUTE_ON_REFERENCE_GENERATOR);
        for (var g : network.getGenerators()) {
            ActivePowerControl<Generator> ext = g.getExtension(ActivePowerControl.class);
            if (g.getId().equals("g1")) {
                ext.setParticipationFactor(1.0);
            } else {
                ext.setParticipationFactor(0.0);
            }
        }
        ReferencePriorities.delete(network);
        ReferencePriority.set(g2, 1);

        g1.setMaxP(110.0);
        g3.setMaxP(110.0);
        g4.setMaxP(110.0);
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());

        var expectedDistributedActivePower = -network.getGeneratorStream().mapToDouble(g -> g.getTargetP() + g.getTerminal().getP()).sum();
        assertEquals(120.2021, expectedDistributedActivePower, LoadFlowAssert.DELTA_POWER);
        assertEquals(expectedDistributedActivePower, result.getComponentResults().get(0).getDistributedActivePower(), LoadFlowAssert.DELTA_POWER);

        // Only g1 gets slack "normally" distributed, and g2 being reference generator picks up the remaining slack
        // generator | targetP | Participation Factor | Reference
        // ----------|---------|----------------------|-----------
        //   g1      |  100    |         1.0          |           --> expected to hit limit 110MW with 10MW distributed
        //   g2      |  200    |          -           |     X     --> expected to pick up the remaining slack 110.2021 MW
        //   g3      |   90    |          -           |           --> unchanged
        //   g4      |   90    |          -           |           --> unchanged
        assertActivePowerEquals(-110.000, g1.getTerminal());
        assertActivePowerEquals(-310.2021, g2.getTerminal());
        assertActivePowerEquals(-90.000, g3.getTerminal());
        assertActivePowerEquals(-90.000, g4.getTerminal());
    }

    @Test
    void testEpsilonDistribution() {
        parametersExt.setSlackBusPMaxMismatch(0.1);
        network = DistributedSlackNetworkFactory.createWithEpsilonDistribution();
        LoadFlowResult result = loadFlowRunner.run(network, parameters);
        assertTrue(result.isFullyConverged());
        assertEquals(0.0, result.getComponentResults().get(0).getSlackBusResults().get(0).getActivePowerMismatch(), LoadFlowAssert.DELTA_POWER);
        assertEquals(0.15, result.getComponentResults().get(0).getDistributedActivePower(), LoadFlowAssert.DELTA_POWER);
    }
}
