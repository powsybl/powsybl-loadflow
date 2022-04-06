/**
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.sa;

import com.google.auto.service.AutoService;
import com.powsybl.computation.ComputationManager;
import com.powsybl.contingency.ContingenciesProvider;
import com.powsybl.iidm.network.Network;
import com.powsybl.math.matrix.MatrixFactory;
import com.powsybl.math.matrix.SparseMatrixFactory;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivityFactory;
import com.powsybl.openloadflow.graph.GraphDecrementalConnectivityFactory;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.util.PowsyblOpenLoadFlowVersion;
import com.powsybl.security.*;
import com.powsybl.security.interceptors.SecurityAnalysisInterceptor;
import com.powsybl.security.monitor.StateMonitor;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * @author Florian Dupuy <florian.dupuy at rte-france.com>
 */
@AutoService(SecurityAnalysisProvider.class)
public class OpenSecurityAnalysisProvider implements SecurityAnalysisProvider {

    private final MatrixFactory matrixFactory;

    private final GraphDecrementalConnectivityFactory<LfBus> connectivityFactory;

    public OpenSecurityAnalysisProvider(MatrixFactory matrixFactory, GraphDecrementalConnectivityFactory<LfBus> connectivityFactory) {
        this.matrixFactory = matrixFactory;
        this.connectivityFactory = connectivityFactory;
    }

    public OpenSecurityAnalysisProvider() {
        this(new SparseMatrixFactory(), new EvenShiloachGraphDecrementalConnectivityFactory<>());
    }

    @Override
    public CompletableFuture<SecurityAnalysisReport> run(Network network, String workingVariantId, LimitViolationDetector limitViolationDetector,
                                                         LimitViolationFilter limitViolationFilter, ComputationManager computationManager,
                                                         SecurityAnalysisParameters securityAnalysisParameters, ContingenciesProvider contingenciesProvider,
                                                         List<SecurityAnalysisInterceptor> interceptors, List<StateMonitor> stateMonitors) {
        Objects.requireNonNull(network);
        Objects.requireNonNull(workingVariantId);
        Objects.requireNonNull(limitViolationDetector);
        Objects.requireNonNull(limitViolationFilter);
        Objects.requireNonNull(computationManager);
        Objects.requireNonNull(securityAnalysisParameters);
        Objects.requireNonNull(contingenciesProvider);
        Objects.requireNonNull(interceptors);
        Objects.requireNonNull(stateMonitors);

        AbstractSecurityAnalysis securityAnalysis;
        if (securityAnalysisParameters.getLoadFlowParameters().isDc()) {
            securityAnalysis = new DcSecurityAnalysis(network, limitViolationDetector, limitViolationFilter, matrixFactory, connectivityFactory, stateMonitors);
        } else {
            securityAnalysis = new AcSecurityAnalysis(network, limitViolationDetector, limitViolationFilter, matrixFactory, connectivityFactory, stateMonitors);
        }

        interceptors.forEach(securityAnalysis::addInterceptor);

        return securityAnalysis.run(workingVariantId, securityAnalysisParameters, contingenciesProvider, computationManager);
    }

    @Override
    public String getName() {
        return "OpenSecurityAnalysis";
    }

    @Override
    public String getVersion() {
        return new PowsyblOpenLoadFlowVersion().toString();
    }
}
