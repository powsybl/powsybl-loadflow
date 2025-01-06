/**
 * Copyright (c) 2024, Artelys (http://www.artelys.com/)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.solver;

import com.google.auto.service.AutoService;
import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.parameters.Parameter;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.loadflow.LoadFlowResult;
import com.powsybl.math.matrix.DenseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.OpenLoadFlowProvider;
import com.powsybl.openloadflow.ac.AcLoadFlowParameters;
import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.EquationVector;
import com.powsybl.openloadflow.equations.JacobianMatrix;
import com.powsybl.openloadflow.equations.TargetVector;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import com.powsybl.openloadflow.network.FourBusNetworkFactory;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.util.UniformValueVoltageInitializer;
import com.powsybl.openloadflow.network.util.VoltageInitializer;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Damien Jeandemange {@literal <damien.jeandemange at artelys.com>}
 */
class AcSolverFactoryTest {

    public record AcSolverMockParameters(int maxIterations) implements AcSolverParameters {

        @Override
        public String toString() {
            return "AcSolverMockParameters(" +
                    "maxIterations=" + maxIterations +
                    ')';
        }
    }

    public static class AcSolverMock implements AcSolver {
        private static final Logger LOGGER = LoggerFactory.getLogger(AcSolverMock.class);
        private final LfNetwork network;
        private final EquationSystem<AcVariableType, AcEquationType> equationSystem;
        private final AcSolverMockParameters parameters;

        public AcSolverMock(LfNetwork network, EquationSystem<AcVariableType, AcEquationType> equationSystem, AcSolverMockParameters parameters) {
            this.network = network;
            this.equationSystem = equationSystem;
            this.parameters = parameters;
        }

        @Override
        public String getName() {
            return "AC Solver Mock";
        }

        @Override
        public AcSolverResult run(VoltageInitializer voltageInitializer, ReportNode reportNode, boolean canCheckUnrealistic) {
            AcSolverUtil.initStateVector(network, equationSystem, new UniformValueVoltageInitializer());
            LOGGER.info("I am a not so advanced solver only able to return flat 1 p.u. /_ 0.0, in max iterations, leaving 34 MW slack mismatch.");
            return new AcSolverResult(AcSolverStatus.CONVERGED, parameters.maxIterations(), 0.34);
        }
    }

    @AutoService(AcSolverFactory.class)
    public static class AcSolverFactoryMock implements AcSolverFactory {

        public static final String NAME = "AC_SOLVER_MOCK";

        @Override
        public String getName() {
            return NAME;
        }

        @Override
        public AcSolverParameters createParameters(LoadFlowParameters parameters) {
            return new AcSolverMockParameters(12);
        }

        @Override
        public AcSolver create(LfNetwork network, AcLoadFlowParameters parameters, EquationSystem<AcVariableType, AcEquationType> equationSystem, JacobianMatrix<AcVariableType, AcEquationType> j, TargetVector<AcVariableType, AcEquationType> targetVector, EquationVector<AcVariableType, AcEquationType> equationVector) {
            return new AcSolverMock(network, equationSystem, (AcSolverMockParameters) parameters.getAcSolverParameters());
        }
    }

    @Test
    void testAcSolverTypeParam() {
        OpenLoadFlowProvider provider = new OpenLoadFlowProvider();
        Parameter acSolverType = provider.getSpecificParameters().stream().filter(p -> p.getName().equals(OpenLoadFlowParameters.AC_SOLVER_TYPE_PARAM_NAME)).findFirst().orElseThrow();
        assertEquals("NEWTON_RAPHSON", acSolverType.getDefaultValue());
        assertEquals(List.of("AC_SOLVER_MOCK", "NEWTON_KRYLOV", "NEWTON_RAPHSON"), acSolverType.getPossibleValues());
    }

    @Test
    void testMockAcSolverType() {
        Network network = FourBusNetworkFactory.createBaseNetwork();
        LoadFlow.Runner loadFlowRunner = new LoadFlow.Runner(new OpenLoadFlowProvider(new DenseMatrixFactory()));
        LoadFlowParameters parameters = new LoadFlowParameters().setDistributedSlack(false);
        OpenLoadFlowParameters parametersExt = OpenLoadFlowParameters.create(parameters);
        parametersExt.setAcSolverType(AcSolverFactoryMock.NAME);

        AcLoadFlowParameters acLoadFlowParameters = OpenLoadFlowParameters.createAcParameters(
                network,
                parameters,
                parametersExt,
                new DenseMatrixFactory(),
                new EvenShiloachGraphDecrementalConnectivityFactory<>()
        );
        assertEquals("AcSolverMockParameters(maxIterations=12)", acLoadFlowParameters.getAcSolverParameters().toString());

        LoadFlowResult result = loadFlowRunner.run(network, parametersExt.getExtendable());
        assertTrue(result.isFullyConverged());
        assertEquals(12, result.getComponentResults().get(0).getIterationCount());
        assertEquals(34, result.getComponentResults().get(0).getSlackBusResults().get(0).getActivePowerMismatch());
    }

    @Test
    void testNotFoundAcSolver() {
        LoadFlowParameters parameters = new LoadFlowParameters();
        OpenLoadFlowParameters parametersExt = OpenLoadFlowParameters.create(parameters);
        Throwable t = assertThrows(PowsyblException.class, () -> parametersExt.setAcSolverType("notExists"));
        assertEquals("AC Solver 'notExists' not found", t.getMessage());
    }
}
