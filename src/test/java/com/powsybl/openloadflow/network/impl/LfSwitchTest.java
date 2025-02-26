/*
 * Copyright (c) 2021-2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network.impl;

import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.LimitType;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.DenseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.ac.AcLoadFlowParameters;
import com.powsybl.openloadflow.ac.equations.*;
import com.powsybl.openloadflow.ac.equations.vector.AcVectorEngine;
import com.powsybl.openloadflow.equations.EquationTerm;
import com.powsybl.openloadflow.equations.VariableSet;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.LfNetworkParameters;
import com.powsybl.openloadflow.network.NodeBreakerNetworkFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * @author Anne Tilloy {@literal <anne.tilloy at rte-france.com>}
 */
class LfSwitchTest {

    private Network network;

    private LfNetwork lfNetwork;

    private LfSwitch lfSwitch;

    private AcLoadFlowParameters acLoadFlowParameters;

    private AcVectorEngine acVectorEnginee;

    @BeforeEach
    void setUp() {
        network = NodeBreakerNetworkFactory.create();
        acLoadFlowParameters = OpenLoadFlowParameters.createAcParameters(network, new LoadFlowParameters(),
                new OpenLoadFlowParameters(), new DenseMatrixFactory(), new EvenShiloachGraphDecrementalConnectivityFactory<>(), true, false);
        List<LfNetwork> lfNetworks = Networks.load(network, acLoadFlowParameters.getNetworkParameters(), ReportNode.NO_OP);
        assertEquals(1, lfNetworks.size());
        lfNetwork = lfNetworks.get(0);
        lfSwitch = (LfSwitch) lfNetwork.getBranchById("B3");
        acVectorEnginee = new AcVectorEngine(lfNetwork, null);
    }

    @Test
    void getterTest() {
        assertEquals("B3", lfSwitch.getId());
        assertFalse(lfSwitch.hasPhaseControllerCapability());
        assertEquals(Double.NaN, lfSwitch.getP1().eval());
        assertEquals(Double.NaN, lfSwitch.getP2().eval());
        assertEquals(Double.NaN, lfSwitch.getI1().eval());
        assertEquals(Double.NaN, lfSwitch.getI2().eval());
        assertEquals(Collections.emptyList(), lfSwitch.getLimits1(LimitType.CURRENT, null));
        assertEquals(Collections.emptyList(), lfSwitch.getLimits2(LimitType.CURRENT, null));
    }

    @Test
    void setterTest() {
        lfSwitch.getPiModel().setX(LfNetworkParameters.LOW_IMPEDANCE_THRESHOLD_DEFAULT_VALUE);

        VariableSet<AcVariableType> variableSet = new VariableSet<>();
        EquationTerm<AcVariableType, AcEquationType> p1 = new ClosedBranchSide1ActiveFlowEquationTerm(lfSwitch, lfSwitch.getBus1(), lfSwitch.getBus2(), variableSet, false, false, acVectorEnginee);
        EquationTerm<AcVariableType, AcEquationType> p2 = new ClosedBranchSide2ActiveFlowEquationTerm(lfSwitch, lfSwitch.getBus1(), lfSwitch.getBus2(), variableSet, false, false, acVectorEnginee);
        lfSwitch.setP1(p1);
        assertEquals(Double.NaN, lfSwitch.getP1().eval());
        lfSwitch.setP2(p2);
        assertEquals(Double.NaN, lfSwitch.getP2().eval());

        EquationTerm<AcVariableType, AcEquationType> i1 = new ClosedBranchSide1CurrentMagnitudeEquationTerm(lfSwitch, lfSwitch.getBus1(), lfSwitch.getBus2(), variableSet, false, false, acVectorEnginee);
        EquationTerm<AcVariableType, AcEquationType> i2 = new ClosedBranchSide2CurrentMagnitudeEquationTerm(lfSwitch, lfSwitch.getBus1(), lfSwitch.getBus2(), variableSet, false, false, acVectorEnginee);
        lfSwitch.setI1(i1);
        assertEquals(Double.NaN, lfSwitch.getP1().eval());
        lfSwitch.setI2(i2);
        assertEquals(Double.NaN, lfSwitch.getP2().eval());
    }

}
