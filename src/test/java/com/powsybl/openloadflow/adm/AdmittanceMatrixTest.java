/**
 * Copyright (c) 2024, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.adm;

import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.math.matrix.DenseMatrixFactory;
import com.powsybl.openloadflow.equations.VariableSet;
import com.powsybl.openloadflow.network.FirstSlackBusSelector;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.impl.LfNetworkLoaderImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
class AdmittanceMatrixTest {

    @Test
    void test() {
        Network network = EurostagTutorialExample1Factory.create();
        LfNetwork lfNetwork = LfNetwork.load(network, new LfNetworkLoaderImpl(), new FirstSlackBusSelector()).get(0);
        var ySystem = AdmittanceEquationSystem.create(lfNetwork, new VariableSet<>());
        try (var y = AdmittanceMatrix.create(ySystem, new DenseMatrixFactory())) {
            var yRef = new DenseMatrix(8, 8, new double[] {
                3.4570637119113563, -144.0028305895698, -3.2842105263157895, 136.80268906009132, 0.0, 0.0, 0.0, 0.0,
                144.0028305895698, 3.4570637119113563, -136.80268906009132, -3.2842105263157895, 0.0, 0.0, 0.0, 0.0,
                -3.2842105263157895, 136.80268906009132, 11.010710382513661, -216.20298481473702, -7.890710382513662, 86.79781420765028, 0.0, 0.0,
                -136.80268906009132, -3.2842105263157895, 216.20298481473702, 11.010710382513661, -86.79781420765028, -7.890710382513662, 0.0, 0.0,
                0.0, 0.0, -7.890710382513662, 86.79781420765028, 8.540588654886903, -141.94049103976127, -0.649012633744856, 55.6258682851227,
                0.0, 0.0, -86.79781420765028, -7.890710382513662, 141.94049103976127, 8.540588654886903, -55.6258682851227, -0.649012633744856,
                0.0, 0.0, 0.0, 0.0, -0.649012633744856, 55.6258682851227, 0.6481481481481483, -55.55177456269486,
                0.0, 0.0, 0.0, 0.0, -55.6258682851227, -0.649012633744856, 55.55177456269486, 0.6481481481481483
            }).transpose();
            assertEquals(yRef, y.getMatrix());

            LfBus ngen = lfNetwork.getBusById("VLGEN_0");
            LfBus nhv1 = lfNetwork.getBusById("VLHV1_0");
            LfBus nhv2 = lfNetwork.getBusById("VLHV2_0");
            LfBus nload = lfNetwork.getBusById("VLLOAD_0");

            assertEquals(136.842, 1.0 / y.getZ(ngen, nhv1).abs(), 1e-3);
            assertEquals(87.155, 1.0 / y.getZ(nhv1, nhv2).abs(), 1e-3);
            assertEquals(55.629, 1.0 / y.getZ(nhv2, nload).abs(), 1e-3);
            assertEquals(28.582, 1.0 / y.getZ(ngen, nload).abs(), 1e-3);
        }
    }
}
