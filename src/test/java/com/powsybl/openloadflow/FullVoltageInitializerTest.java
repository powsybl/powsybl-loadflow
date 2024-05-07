/**
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow;

import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.DenseMatrixFactory;
import com.powsybl.math.matrix.MatrixFactory;
import com.powsybl.openloadflow.ac.VoltageMagnitudeInitializer;
import com.powsybl.openloadflow.dc.DcValueVoltageInitializer;
import com.powsybl.openloadflow.dc.equations.DcApproximationType;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.network.impl.LfNetworkLoaderImpl;
import org.junit.jupiter.api.Test;

import static com.powsybl.openloadflow.ac.VoltageMagnitudeInitializerTest.assertBusVoltage;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
class FullVoltageInitializerTest {

    @Test
    void testEsgTuto1() {
        Network network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());
        SlackBusSelector slackBusSelector = new FirstSlackBusSelector();
        LfNetwork lfNetwork = LfNetwork.load(network, new LfNetworkLoaderImpl(), slackBusSelector).get(0);
        MatrixFactory matrixFactory = new DenseMatrixFactory();
        FullVoltageInitializer initializer = new FullVoltageInitializer(new VoltageMagnitudeInitializer(false, matrixFactory, LfNetworkParameters.LOW_IMPEDANCE_THRESHOLD_DEFAULT_VALUE),
                                                                        new DcValueVoltageInitializer(new LfNetworkParameters().setSlackBusSelector(slackBusSelector),
                                                                                                      false,
                                                                                                      LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX,
                                                                                                      true,
                                                                                                      DcApproximationType.IGNORE_R,
                                                                                                      matrixFactory,
                                                                                                      0));
        initializer.prepare(lfNetwork);
        assertBusVoltage(lfNetwork, initializer, "VLGEN_0", 1.020833, 0);
        assertBusVoltage(lfNetwork, initializer, "VLHV1_0", 1.074561, -0.043833);
        assertBusVoltage(lfNetwork, initializer, "VLHV2_0", 1.074561, -0.112393);
        assertBusVoltage(lfNetwork, initializer, "VLLOAD_0", 1.075994, -0.220241);
    }
}
