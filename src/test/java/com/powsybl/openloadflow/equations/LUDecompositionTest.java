/**
 * Copyright (c) 2024, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.openloadflow.equations;

import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.math.matrix.DenseMatrixFactory;
import com.powsybl.math.matrix.LUDecomposition;
import com.powsybl.math.matrix.MatrixException;
import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.network.FirstSlackBusSelector;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.impl.Networks;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Didier Vidal {@literal <didier.vidal_externe at rte-france.com>}
 */
class LUDecompositionTest {

    class LUDecompositionMockIncrementalFailure implements LUDecomposition {
        private final LUDecomposition delegate;
        private final SpyMatrixFactory spy;

        public LUDecompositionMockIncrementalFailure(LUDecomposition delegate, SpyMatrixFactory spy) {
            this.delegate = delegate;
            this.spy = spy;
        }

        @Override
        public void update() {
            delegate.update();
        }

        @Override
        public void solve(double[] b) {
            delegate.solve(b);
        }

        @Override
        public void solveTransposed(double[] b) {
            delegate.solveTransposed(b);
        }

        @Override
        public void solve(DenseMatrix b) {
            delegate.solve(b);
        }

        @Override
        public void solveTransposed(DenseMatrix b) {
            delegate.solveTransposed(b);
        }

        @Override
        public void close() {
            delegate.close();
        }

        @Override
        public void update(boolean allowIncrementalUpdate) {
            if (allowIncrementalUpdate || spy.alwaysFail) {
                spy.exceptionThrown = true;
                throw new MatrixException("Sorry incremental update failed");
            } else {
                delegate.update(allowIncrementalUpdate);
            }
        }
    }

    class SpyMatrixFactory extends DenseMatrixFactory {
        boolean exceptionThrown = false;
        boolean alwaysFail = false;

        @Override
        public DenseMatrix create(int rowCount, int columnCount, int estimatedValueCount) {
            return new DenseMatrix(rowCount, columnCount) {
                @Override
                public LUDecomposition decomposeLU() {
                    return new LUDecompositionMockIncrementalFailure(super.decomposeLU(), SpyMatrixFactory.this);
                }
            };
        }
    }

    @Test
    void testIncrementalLURobustification() {

        // The condition that cause an incremental LU decomposition to fail is hard to reproduce on small models.
        // It has been seen in Security Analysis, on large models, when a contingence moves an AC emulation HVDC link above PMax
        //
        // This test uses a mocked DenseMatrix to reproduce the condition and check that the solve succeds

        SpyMatrixFactory spyMatrixFactory = new SpyMatrixFactory();

        List<LfNetwork> lfNetworks = Networks.load(EurostagTutorialExample1Factory.create(), new FirstSlackBusSelector());
        LfNetwork network = lfNetworks.get(0);

        LfBus bus = network.getBus(0);

        EquationSystem<AcVariableType, AcEquationType> equationSystem = new EquationSystem<>();
        equationSystem.createEquation(bus.getNum(), AcEquationType.BUS_TARGET_V).addTerm(equationSystem.getVariable(bus.getNum(), AcVariableType.BUS_V).createTerm())
                .setActive(true);

        double[] values = new double[] {0.1};

        try (JacobianMatrix j = new JacobianMatrix(equationSystem, spyMatrixFactory)) {
            // First update
            j.solve(values);
            assertFalse(spyMatrixFactory.exceptionThrown);
            // second update that triggers incremental update
            j.updateStatus(JacobianMatrix.Status.VALUES_INVALID);
            j.solve(values);
            // An eception should be thrown but the solve should continue
            assertTrue(spyMatrixFactory.exceptionThrown);

            // Force always fail
            spyMatrixFactory.alwaysFail = true;
            j.updateStatus(JacobianMatrix.Status.VALUES_INVALID);
            assertThrows(MatrixException.class, () -> j.solve(values));

            // Force always fail in non incremental case
            spyMatrixFactory.alwaysFail = true;
            j.updateStatus(JacobianMatrix.Status.VALUES_AND_ZEROS_INVALID);
            assertThrows(MatrixException.class, () -> j.solve(values));
        }

    }
}
