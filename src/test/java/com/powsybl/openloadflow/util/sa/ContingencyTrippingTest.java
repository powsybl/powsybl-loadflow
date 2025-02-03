/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.util.sa;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.*;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.iidm.network.test.FictitiousSwitchFactory;
import com.powsybl.openloadflow.network.EurostagFactory;
import com.powsybl.openloadflow.network.NodeBreakerNetworkFactory;
import com.powsybl.openloadflow.network.impl.ContingencyTripping;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Florian Dupuy {@literal <florian.dupuy at rte-france.com>}
 */
class ContingencyTrippingTest {

    @Test
    void testLineTripping() {
        Network network = NodeBreakerNetworkFactory.create();

        Set<Switch> switchesToOpen = new HashSet<>();
        Set<Terminal> terminalsToDisconnect = new HashSet<>();
        ContingencyTripping.createBranchTripping(network, network.getBranch("L1")).traverse(switchesToOpen, terminalsToDisconnect);
        checkSwitches(switchesToOpen, "C");
        checkTerminalIds(terminalsToDisconnect, "BBS1", "L1");

        switchesToOpen.clear();
        terminalsToDisconnect.clear();
        ContingencyTripping.createBranchTripping(network, network.getBranch("L2")).traverse(switchesToOpen, terminalsToDisconnect);
        checkSwitches(switchesToOpen);
        checkTerminalIds(terminalsToDisconnect, "L2");
    }

    @Test
    void testBusbarSectionTripping() {
        Network network = NodeBreakerNetworkFactory.create();

        Set<Switch> switchesToOpen = new HashSet<>();
        Set<Terminal> terminalsToDisconnect = new HashSet<>();

        ContingencyTripping.createBusbarSectionMinimalTripping(network, network.getBusbarSection("BBS3"))
                .traverse(switchesToOpen, terminalsToDisconnect);
        checkSwitches(switchesToOpen, "B3", "B4");
        checkTerminalIds(terminalsToDisconnect, "LD", "BBS3");
    }

    @Test
    void testUnconnectedVoltageLevel() {
        Network network = NodeBreakerNetworkFactory.create();
        Exception unknownVl = assertThrows(PowsyblException.class,
            () -> ContingencyTripping.createBranchTripping(network, network.getBranch("L1"), "VL3"));
        assertEquals("VoltageLevel 'VL3' not connected to branch 'L1'", unknownVl.getMessage());
    }

    @Test
    void testVoltageLevelFilter() {
        Network network = NodeBreakerNetworkFactory.create();

        ContingencyTripping trippingTaskVl1 = ContingencyTripping.createBranchTripping(network, network.getBranch("L1"), "VL1");
        Set<Switch> switchesToOpen = new HashSet<>();
        Set<Terminal> terminalsToDisconnect = new HashSet<>();
        trippingTaskVl1.traverse(switchesToOpen, terminalsToDisconnect);
        checkSwitches(switchesToOpen, "C");
        checkTerminalIds(terminalsToDisconnect, "BBS1", "L1");

        ContingencyTripping trippingTaskVl2 = ContingencyTripping.createBranchTripping(network, network.getBranch("L1"), "VL2");
        switchesToOpen.clear();
        terminalsToDisconnect.clear();
        trippingTaskVl2.traverse(switchesToOpen, terminalsToDisconnect);
        checkSwitches(switchesToOpen);
        checkTerminalIds(terminalsToDisconnect, "L1");
    }

    @Test
    void testDisconnectorBeforeMultipleSwitches() {
        Network network = FictitiousSwitchFactory.create();

        Set<Switch> switchesToOpen = new HashSet<>();
        Set<Terminal> terminalsToDisconnect = new HashSet<>();
        ContingencyTripping.createBranchTripping(network, network.getBranch("CJ")).traverse(switchesToOpen, terminalsToDisconnect);
        checkSwitches(switchesToOpen);
        checkTerminalIds(terminalsToDisconnect, "D", "CI", "CJ");
    }

    @Test
    void testSwitchBeforeOpenedDisconnector() {
        Network network = FictitiousSwitchFactory.create();

        // Close switches to traverse the voltage level further
        network.getSwitch("BB").setOpen(false);
        network.getSwitch("AZ").setOpen(false);

        // First without opening disconnector
        ContingencyTripping lbt1 = ContingencyTripping.createBranchTripping(network, network.getBranch("CJ"));
        Set<Switch> switchesToOpen = new HashSet<>();
        Set<Terminal> terminalsToDisconnect = new HashSet<>();
        lbt1.traverse(switchesToOpen, terminalsToDisconnect);
        checkSwitches(switchesToOpen, "BL", "BJ");
        checkTerminalIds(terminalsToDisconnect, "D", "CE", "CF", "CG", "CH", "CI", "P", "CJ");

        // Then with the opened disconnector
        network.getSwitch("AH").setOpen(true);
        switchesToOpen.clear();
        terminalsToDisconnect.clear();
        lbt1.traverse(switchesToOpen, terminalsToDisconnect);
        checkSwitches(switchesToOpen, "BL");
        checkTerminalIds(terminalsToDisconnect, "D", "CE", "CF", "CG", "CH", "CI", "P", "CJ");
    }

    @Test
    void testOpenedSwitches() {
        // Testing disconnector and breaker opened just after contingency
        Network network = FictitiousSwitchFactory.create();
        network.getSwitch("L").setOpen(true); // breaker at C side of line CJ
        network.getSwitch("BF").setOpen(true);  // disconnector at N side of line CJ

        Set<Switch> switchesToOpen = new HashSet<>();
        Set<Terminal> terminalsToDisconnect = new HashSet<>();
        ContingencyTripping.createBranchTripping(network, network.getBranch("CJ")).traverse(switchesToOpen, terminalsToDisconnect);
        checkSwitches(switchesToOpen);
        checkTerminalIds(terminalsToDisconnect, "CJ");
    }

    @Test
    void testEndNodeAfterSwitch() {
        Network network = FictitiousSwitchFactory.create();

        // Close switches to traverse the voltage level until encountering generator/loads
        network.getSwitch("BB").setOpen(false); // BB fictitious breaker
        network.getSwitch("AZ").setOpen(false); // AZ disconnector to BBS1
        network.getSwitch("AV").setOpen(false); // AV disconnector to generator CD

        // Adding breakers between two loads to simulate the case of a branching of two switches at an end node
        network.getVoltageLevel("N").getNodeBreakerView().newSwitch()
            .setId("ZW")
            .setName("ZX")
            .setKind(SwitchKind.BREAKER)
            .setRetained(false)
            .setOpen(true)
            .setFictitious(true)
            .setNode1(22)
            .setNode2(16)
            .add();
        network.getVoltageLevel("N").getNodeBreakerView().newSwitch()
            .setId("ZY")
            .setName("ZZ")
            .setKind(SwitchKind.BREAKER)
            .setRetained(false)
            .setOpen(false)
            .setFictitious(true)
            .setNode1(20)
            .setNode2(18)
            .add();

        ContingencyTripping lbt1 = ContingencyTripping.createBranchTripping(network, network.getBranch("CJ"));
        Set<Switch> switchesToOpen = new HashSet<>();
        Set<Terminal> terminalsToDisconnect = new HashSet<>();
        lbt1.traverse(switchesToOpen, terminalsToDisconnect);
        checkSwitches(switchesToOpen, "BJ", "BL", "BV", "BX");
        checkTerminalIds(terminalsToDisconnect, "D", "CD", "CE", "CH", "CI", "P", "O", "CJ");

        // Adding an internal connection and open the ZW switch
        network.getSwitch("ZY").setOpen(true);
        network.getVoltageLevel("N").getNodeBreakerView().newInternalConnection()
            .setNode1(20)
            .setNode2(18)
            .add();
        switchesToOpen.clear();
        terminalsToDisconnect.clear();
        lbt1.traverse(switchesToOpen, terminalsToDisconnect);
        checkSwitches(switchesToOpen, "BJ", "BL", "BV", "BX");
        checkTerminalIds(terminalsToDisconnect, "D", "CD", "CE", "CH", "CI", "P", "O", "CJ");
    }

    @Test
    void testInternalConnection() {
        Network network = FictitiousSwitchFactory.create();

        // Adding internal connections
        network.getVoltageLevel("N").getNodeBreakerView().newInternalConnection()
            .setNode1(3)
            .setNode2(9)
            .add();
        network.getVoltageLevel("C").getNodeBreakerView().newInternalConnection()
            .setNode1(2)
            .setNode2(3)
            .add();

        Set<Switch> switchesToOpen = new HashSet<>();
        Set<Terminal> terminalsToDisconnect = new HashSet<>();
        ContingencyTripping.createBranchTripping(network, network.getBranch("CJ")).traverse(switchesToOpen, terminalsToDisconnect);
        checkSwitches(switchesToOpen, "BL");
        checkTerminalIds(terminalsToDisconnect, "D", "CE", "CI", "CJ");
    }

    @Test
    void testInternalConnectionEndingAtSwitches() {
        Network network = FictitiousSwitchFactory.create();

        network.getSwitch("L").setFictitious(false);
        network.getSwitch("BB").setFictitious(false);

        VoltageLevel.NodeBreakerView c = network.getVoltageLevel("C").getNodeBreakerView();
        c.newInternalConnection().setNode1(4).setNode2(5).add();
        c.newInternalConnection().setNode1(5).setNode2(6).add();
        c.newBreaker().setId("ZZ").setNode1(6).setNode2(0).add();

        Set<Switch> switchesToOpen = new HashSet<>();
        Set<Terminal> terminalsToDisconnect = new HashSet<>();
        ContingencyTripping.createBranchTripping(network, network.getBranch("CJ")).traverse(switchesToOpen, terminalsToDisconnect);
        assertTrue(switchesToOpen.isEmpty());
        checkTerminalIds(terminalsToDisconnect, "CJ");

        c.newInternalConnection().setNode1(5).setNode2(7).add();
        Switch b = c.newBreaker().setId("ZY").setNode1(7).setNode2(0).add();

        terminalsToDisconnect.clear();
        ContingencyTripping.createBranchTripping(network, network.getBranch("CJ")).traverse(switchesToOpen, terminalsToDisconnect);
        assertTrue(switchesToOpen.isEmpty());
        checkTerminalIds(terminalsToDisconnect, "CJ");

        b.setFictitious(true);
        terminalsToDisconnect.clear();
        ContingencyTripping.createBranchTripping(network, network.getBranch("CJ")).traverse(switchesToOpen, terminalsToDisconnect);
        checkSwitches(switchesToOpen, "L", "ZZ");
        checkTerminalIds(terminalsToDisconnect, "D", "CI", "CJ");
    }

    @Test
    void testStopAtStartEdges() {
        Network network = FictitiousSwitchFactory.create();

        // Adding edges after CJ line
        network.getVoltageLevel("N").getNodeBreakerView().newBreaker()
            .setId("ZY")
            .setName("ZZ")
            .setRetained(false)
            .setOpen(false)
            .setFictitious(false)
            .setNode1(5)
            .setNode2(9)
            .add();
        network.getVoltageLevel("C").getNodeBreakerView().newInternalConnection()
            .setNode1(1)
            .setNode2(4)
            .add();

        ContingencyTripping lbt1 = ContingencyTripping.createBranchTripping(network, network.getBranch("CJ"));
        Set<Switch> switchesToOpen = new HashSet<>();
        Set<Terminal> terminalsToDisconnect = new HashSet<>();
        lbt1.traverse(switchesToOpen, terminalsToDisconnect);
        checkSwitches(switchesToOpen, "ZY");
        checkTerminalIds(terminalsToDisconnect, "D", "CI", "CJ");

        network.getSwitch("ZY").setOpen(true);
        switchesToOpen.clear();
        terminalsToDisconnect.clear();
        lbt1.traverse(switchesToOpen, terminalsToDisconnect);
        checkSwitches(switchesToOpen);
        checkTerminalIds(terminalsToDisconnect, "D", "CI", "CJ");
    }

    @Test
    void testEurostagNetwork() {
        Network network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());

        Set<Switch> switchesToOpen = new HashSet<>();
        Set<Terminal> terminalsToDisconnect = new HashSet<>();

        ContingencyTripping trippingTask = ContingencyTripping.createBranchTripping(network, network.getBranch("NHV1_NHV2_1"));
        trippingTask.traverse(switchesToOpen, terminalsToDisconnect);

        assertTrue(switchesToOpen.isEmpty());
        assertEquals(2, terminalsToDisconnect.size());
        checkTerminalStrings(terminalsToDisconnect, "BusTerminal[NHV1]", "BusTerminal[NHV2]");

        Line line = network.getLine("NHV1_NHV2_1");
        line.getTerminal1().disconnect();

        terminalsToDisconnect.clear();
        trippingTask.traverse(switchesToOpen, terminalsToDisconnect);

        assertTrue(switchesToOpen.isEmpty());
        assertEquals(1, terminalsToDisconnect.size());
        checkTerminalStrings(terminalsToDisconnect, "BusTerminal[NHV2]");
    }

    private static void checkSwitches(Set<Switch> switches, String... sId) {
        assertEquals(new HashSet<>(Arrays.asList(sId)), switches.stream().map(Switch::getId).collect(Collectors.toSet()));
    }

    private static void checkTerminalIds(Set<Terminal> terminals, String... tId) {
        assertEquals(new HashSet<>(Arrays.asList(tId)), terminals.stream().map(t -> t.getConnectable().getId()).collect(Collectors.toSet()));
    }

    private static void checkTerminalStrings(Set<Terminal> terminals, String... strings) {
        assertEquals(new HashSet<>(Arrays.asList(strings)), terminals.stream().map(Object::toString).collect(Collectors.toSet()));
    }

}
