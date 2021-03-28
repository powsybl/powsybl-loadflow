/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.sensi;

import com.google.auto.service.AutoService;
import com.powsybl.computation.ComputationManager;
import com.powsybl.contingency.Contingency;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.MatrixFactory;
import com.powsybl.math.matrix.SparseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.graph.EvenShiloachGraphDecrementalConnectivity;
import com.powsybl.openloadflow.graph.GraphDecrementalConnectivity;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.util.PropagatedContingency;
import com.powsybl.sensitivity.*;
import com.powsybl.tools.PowsyblCoreVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
@AutoService(SensitivityAnalysisProvider.class)
public class OpenSensitivityAnalysisProvider implements SensitivityAnalysisProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenSensitivityAnalysisProvider.class);

    private static final String NAME = "OpenSensitivityAnalysis";

    private final DcSensitivityAnalysis dcSensitivityAnalysis;

    private final AcSensitivityAnalysis acSensitivityAnalysis;

    public OpenSensitivityAnalysisProvider() {
        this(new SparseMatrixFactory());
    }

    public OpenSensitivityAnalysisProvider(MatrixFactory matrixFactory) {
        this(matrixFactory, EvenShiloachGraphDecrementalConnectivity::new);
    }

    public OpenSensitivityAnalysisProvider(MatrixFactory matrixFactory, Supplier<GraphDecrementalConnectivity<LfBus>> connectivityProvider) {
        dcSensitivityAnalysis = new DcSensitivityAnalysis(matrixFactory, connectivityProvider);
        acSensitivityAnalysis = new AcSensitivityAnalysis(matrixFactory, connectivityProvider);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getVersion() {
        return new PowsyblCoreVersion().getMavenProjectVersion();
    }

    private static OpenSensitivityAnalysisParameters getSensitivityAnalysisParametersExtension(SensitivityAnalysisParameters sensitivityAnalysisParameters) {
        OpenSensitivityAnalysisParameters sensiParametersExt = sensitivityAnalysisParameters.getExtension(OpenSensitivityAnalysisParameters.class);
        if (sensiParametersExt == null) {
            sensiParametersExt = new OpenSensitivityAnalysisParameters();
        }
        return sensiParametersExt;
    }

    private static OpenLoadFlowParameters getLoadFlowParametersExtension(LoadFlowParameters lfParameters) {
        OpenLoadFlowParameters lfParametersExt = lfParameters.getExtension(OpenLoadFlowParameters.class);
        if (lfParametersExt == null) {
            lfParametersExt = new OpenLoadFlowParameters();
        }
        return lfParametersExt;
    }

    @Override
    public CompletableFuture<SensitivityAnalysisResult> run(Network network, String workingStateId,
                                                            SensitivityFactorsProvider sensitivityFactorsProvider,
                                                            List<Contingency> contingencies,
                                                            SensitivityAnalysisParameters sensitivityAnalysisParameters,
                                                            ComputationManager computationManager) {
        return CompletableFuture.supplyAsync(() -> {
            SensitivityValueWriterAdapter writer = new SensitivityValueWriterAdapter();
            run(network, workingStateId, sensitivityFactorsProvider, contingencies, sensitivityAnalysisParameters, writer);
            boolean ok = true;
            Map<String, String> metrics = new HashMap<>();
            String logs = "";
            return new SensitivityAnalysisResult(ok, metrics, logs, writer.getSensitivityValues(), writer.getSensitivityValuesByContingency());
        });
    }

    public void run(Network network, String workingStateId, SensitivityFactorsProvider sensitivityFactorsProvider,
                    List<Contingency> contingencies, SensitivityAnalysisParameters sensitivityAnalysisParameters,
                    SensitivityValueWriter writer) {
        network.getVariantManager().setWorkingVariant(workingStateId);

        List<SensitivityFactor> factors = sensitivityFactorsProvider.getCommonFactors(network);
        // FIXME additional factors are not yet supported
        if (!sensitivityFactorsProvider.getAdditionalFactors(network).isEmpty()) {
            throw new UnsupportedOperationException("Factors specific to base case not yet supported");
        }
        for (Contingency contingency : contingencies) {
            if (!sensitivityFactorsProvider.getAdditionalFactors(network, contingency.getId()).isEmpty()) {
                throw new UnsupportedOperationException("Factors specific to one contingency not yet supported");
            }
        }

        List<PropagatedContingency> propagatedContingencies = PropagatedContingency.create(network, contingencies, new HashSet<>());

        LoadFlowParameters lfParameters = sensitivityAnalysisParameters.getLoadFlowParameters();
        OpenLoadFlowParameters lfParametersExt = getLoadFlowParametersExtension(lfParameters);

        LOGGER.info("Running {} sensitivity analysis with {} factors and {} contingencies", lfParameters.isDc() ? "DC" : "AC",
                factors.size(), contingencies.size());

        if (lfParameters.isDc()) {
            dcSensitivityAnalysis.analyse(network, factors, propagatedContingencies, lfParameters, lfParametersExt, writer);
        } else {
            acSensitivityAnalysis.analyse(network, factors, propagatedContingencies, lfParameters, lfParametersExt, writer);
        }
    }
}
