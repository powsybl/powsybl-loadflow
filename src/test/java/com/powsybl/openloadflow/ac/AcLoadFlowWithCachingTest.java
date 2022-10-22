/**
 * Copyright (c) 2022, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.ac;

import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowResult;
import com.powsybl.math.matrix.DenseMatrixFactory;
import com.powsybl.openloadflow.NetworkCache;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.OpenLoadFlowProvider;
import com.powsybl.openloadflow.network.EurostagFactory;
import org.junit.jupiter.api.Test;

import static com.powsybl.openloadflow.util.LoadFlowAssert.assertActivePowerEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
class AcLoadFlowWithCachingTest {

    @Test
    void test() {
        var network = EurostagFactory.fix(EurostagTutorialExample1Factory.create());
        var load = network.getLoad("LOAD");
        var gen = network.getGenerator("GEN");
        var loadFlowRunner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
        var parameters = new LoadFlowParameters();
        OpenLoadFlowParameters.create(parameters)
                        .setNetworkCacheEnabled(true);

        var result = loadFlowRunner.run(network, parameters);
        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getComponentResults().get(0).getStatus());
        assertEquals(4, result.getComponentResults().get(0).getIterationCount());
        assertActivePowerEquals(600, load.getTerminal());
        assertActivePowerEquals(-605.559, gen.getTerminal());

        load.setP0(620);

        result = loadFlowRunner.run(network, parameters);
        assertEquals(LoadFlowResult.ComponentResult.Status.CONVERGED, result.getComponentResults().get(0).getStatus());
        assertEquals(3, result.getComponentResults().get(0).getIterationCount());
        assertActivePowerEquals(620, load.getTerminal());
        assertActivePowerEquals(-625.895, gen.getTerminal());

        // check that cache entry could be correctly garbage collected
        network = null;
        load = null;
        gen = null;
        int retry = 0;
        do {
            System.gc();
            retry++;
        } while (NetworkCache.INSTANCE.getEntryCount() > 0 && retry < 100);
        assertEquals(0, NetworkCache.INSTANCE.getEntryCount());
    }
}
