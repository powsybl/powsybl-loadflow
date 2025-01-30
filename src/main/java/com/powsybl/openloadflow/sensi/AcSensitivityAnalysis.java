/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sensi;

import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.math.matrix.MatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.ac.AcLoadFlowContext;
import com.powsybl.openloadflow.ac.AcLoadFlowParameters;
import com.powsybl.openloadflow.ac.AcLoadFlowResult;
import com.powsybl.openloadflow.ac.AcloadFlowEngine;
import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.ac.solver.AcSolverStatus;
import com.powsybl.openloadflow.ac.solver.AcSolverUtil;
import com.powsybl.openloadflow.graph.GraphConnectivityFactory;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.network.impl.LfNetworkList;
import com.powsybl.openloadflow.network.impl.Networks;
import com.powsybl.openloadflow.network.impl.PropagatedContingency;
import com.powsybl.openloadflow.network.util.ActivePowerDistribution;
import com.powsybl.openloadflow.network.util.ParticipatingElement;
import com.powsybl.openloadflow.network.util.PreviousValueVoltageInitializer;
import com.powsybl.sensitivity.*;
import org.apache.commons.lang3.tuple.Pair;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 * @author Gael Macherel {@literal <gael.macherel at artelys.com>}
 */
public class AcSensitivityAnalysis extends AbstractSensitivityAnalysis<AcVariableType, AcEquationType> {

    public AcSensitivityAnalysis(MatrixFactory matrixFactory, GraphConnectivityFactory<LfBus, LfBranch> connectivityFactory, SensitivityAnalysisParameters parameters) {
        super(matrixFactory, connectivityFactory, parameters);
    }

    private void calculateSensitivityValues(List<LfSensitivityFactor<AcVariableType, AcEquationType>> lfFactors, SensitivityFactorGroupList<AcVariableType, AcEquationType> factorGroups, DenseMatrix factorsState,
                                            int contingencyIndex, SensitivityResultWriter resultWriter) {
        Set<LfSensitivityFactor<AcVariableType, AcEquationType>> lfFactorsSet = new HashSet<>(lfFactors);

        // VALID_ONLY_FOR_FUNCTION status is for factors where variable element is not in the main connected component but reference element is.
        // Therefore, the sensitivity is known to value 0 and the reference value can be computed.
        lfFactors.stream().filter(factor -> factor.getStatus() == LfSensitivityFactor.Status.VALID_ONLY_FOR_FUNCTION)
                .forEach(factor -> {
                    if (!filterSensitivityValue(0, factor.getVariableType(), factor.getFunctionType(), parameters)) {
                        resultWriter.writeSensitivityValue(factor.getIndex(), contingencyIndex, 0, unscaleFunction(factor, factor.getFunctionReference()));
                    }
                });

        for (SensitivityFactorGroup<AcVariableType, AcEquationType> factorGroup : factorGroups.getList()) {
            for (LfSensitivityFactor<AcVariableType, AcEquationType> factor : factorGroup.getFactors()) {
                if (!lfFactorsSet.contains(factor)) {
                    continue;
                }
                double sensi;
                double ref;
                if (factor.getSensitivityValuePredefinedResult() != null) {
                    sensi = factor.getSensitivityValuePredefinedResult();
                } else {
                    if (!factor.getFunctionEquationTerm().isActive()) {
                        throw new PowsyblException("Found an inactive equation for a factor that has no predefined result");
                    }
                    sensi = factor.getFunctionEquationTerm().calculateSensi(factorsState, factorGroup.getIndex());
                }
                if (factor.getFunctionPredefinedResult() != null) {
                    ref = factor.getFunctionPredefinedResult();
                } else {
                    ref = factor.getFunctionReference();
                }
                double unscaledSensi = unscaleSensitivity(factor, sensi);
                if (!filterSensitivityValue(unscaledSensi, factor.getVariableType(), factor.getFunctionType(), parameters)) {
                    resultWriter.writeSensitivityValue(factor.getIndex(), contingencyIndex, unscaledSensi, unscaleFunction(factor, ref));
                }
            }
        }
    }

    private void setFunctionReferences(List<LfSensitivityFactor<AcVariableType, AcEquationType>> factors) {
        for (LfSensitivityFactor<AcVariableType, AcEquationType> factor : factors) {
            if (factor.getFunctionPredefinedResult() != null) {
                factor.setFunctionReference(factor.getFunctionPredefinedResult());
            } else {
                factor.setFunctionReference(factor.getFunctionEquationTerm().eval());
            }
        }
    }

    private void calculatePostContingencySensitivityValues(List<LfSensitivityFactor<AcVariableType, AcEquationType>> lfFactors, LfContingency lfContingency,
                                                           LfNetwork lfNetwork, AcLoadFlowContext context, SensitivityFactorGroupList<AcVariableType, AcEquationType> factorGroups,
                                                           Map<LfBus, Double> participationByBus,
                                                           LoadFlowParameters lfParameters, OpenLoadFlowParameters lfParametersExt,
                                                           int contingencyIndex, SensitivityResultWriter resultWriter,
                                                           boolean hasTransformerBusTargetVoltage) {
        if (lfParameters.isDistributedSlack() && Math.abs(lfContingency.getActivePowerLoss()) > 0) {
            ActivePowerDistribution activePowerDistribution = ActivePowerDistribution.create(lfParameters.getBalanceType(), lfParametersExt.isLoadPowerFactorConstant(), lfParametersExt.isUseActiveLimits());
            activePowerDistribution.run(lfNetwork, lfContingency.getActivePowerLoss());
        }

        if (!runLoadFlow(context, false, false)) {
            // write contingency status
            resultWriter.writeContingencyStatus(contingencyIndex, SensitivityAnalysisResult.Status.FAILURE);
            return;
        }

        // write contingency status
        resultWriter.writeContingencyStatus(contingencyIndex, SensitivityAnalysisResult.Status.SUCCESS);

        // if we have at least one bus target voltage linked to a ratio tap changer, we have to rebuild the AC equation
        // system obtained just before the transformer steps rounding.
        if (hasTransformerBusTargetVoltage) {
            for (LfBranch branch : lfNetwork.getBranches()) {
                branch.getVoltageControl().ifPresent(vc -> branch.setVoltageControlEnabled(true));
            }
            lfNetwork.fixTransformerVoltageControls();
        }

        if (factorGroups.hasMultiVariables() && (!lfContingency.getLostLoads().isEmpty() || !lfContingency.getLostGenerators().isEmpty())) {
            // FIXME. It does not work with a contingency that breaks connectivity and lose an isolate injection.
            Set<LfBus> affectedBuses = lfContingency.getLoadAndGeneratorBuses();
            rescaleGlsk(factorGroups, affectedBuses);
        }

        // we make the assumption that we ran a loadflow before, and thus this jacobian is the right one

        // solve system
        DenseMatrix factorsStates = initFactorsRhs(context.getEquationSystem(), factorGroups, participationByBus); // this is the rhs for the moment
        context.getJacobianMatrix().solveTransposed(factorsStates);
        setFunctionReferences(lfFactors);

        // calculate sensitivity values
        calculateSensitivityValues(lfFactors, factorGroups, factorsStates, contingencyIndex, resultWriter);
    }

    private static boolean runLoadFlow(AcLoadFlowContext context,
                                       boolean throwsExceptionIfNoConvergence,
                                       boolean updateContext) {
        AcLoadFlowResult result = new AcloadFlowEngine(context)
                .run();
        if (result.isSuccess() || result.getSolverStatus() == AcSolverStatus.NO_CALCULATION) {
            if (updateContext) {
                context.setOuterLoopInitData(result.getOuterLoopInitData());
            }
            return true;
        } else {
            if (throwsExceptionIfNoConvergence) {
                throw new PowsyblException("Load flow ended with status " + result.getSolverStatus());
            } else {
                LOGGER.warn("Load flow ended with status {}", result.getSolverStatus());
                return false;
            }
        }
    }

    /**
     * https://people.montefiore.uliege.be/vct/elec0029/lf.pdf / Equation 32 is transposed
     */
    @Override
    public void analyse(Network network, List<PropagatedContingency> contingencies, List<SensitivityVariableSet> variableSets,
                        SensitivityFactorReader factorReader, SensitivityResultWriter resultWriter, ReportNode reportNode,
                        LfTopoConfig topoConfig) {
        Objects.requireNonNull(network);
        Objects.requireNonNull(contingencies);
        Objects.requireNonNull(factorReader);
        Objects.requireNonNull(resultWriter);
        Objects.requireNonNull(reportNode);

        LoadFlowParameters lfParameters = parameters.getLoadFlowParameters();
        OpenLoadFlowParameters lfParametersExt = OpenLoadFlowParameters.get(lfParameters);
        Pair<Boolean, Boolean> hasBusTargetVoltage = hasBusTargetVoltage(factorReader, network);
        boolean breakers = topoConfig.isBreaker();
        if (breakers && Boolean.TRUE.equals(hasBusTargetVoltage.getLeft())) {
            // FIXME
            // a bus voltage function works only on a bus/branch topology and a switch contingency only works on a
            // bus/breaker topology. It is not compatible and must be fixed in the API.
            throw new PowsyblException("Switch contingency is not yet supported with sensitivity function of type BUS_VOLTAGE");
        }

        // create LF network (we only manage main connected component)
        if (Boolean.TRUE.equals(hasBusTargetVoltage.getRight())) {
            // if we have at least one bus target voltage linked to a ratio tap changer, we activate the transformer
            // voltage control for the AC load flow engine.
            lfParameters.setTransformerVoltageControlOn(true);
        }
        SlackBusSelector slackBusSelector = SlackBusSelector.fromMode(lfParametersExt.getSlackBusSelectionMode(),
                                                                      lfParametersExt.getSlackBusesIds(),
                                                                      lfParametersExt.getPlausibleActivePowerLimit(),
                                                                      lfParametersExt.getMostMeshedSlackBusSelectorMaxNominalVoltagePercentile(),
                                                                      lfParametersExt.getSlackBusCountryFilter());
        if (lfParameters.isReadSlackBus()) {
            slackBusSelector = new NetworkSlackBusSelector(network, lfParametersExt.getSlackBusCountryFilter(), slackBusSelector);
        }
        LfNetworkParameters lfNetworkParameters = new LfNetworkParameters()
                .setSlackBusSelector(slackBusSelector)
                .setConnectivityFactory(connectivityFactory)
                .setGeneratorVoltageRemoteControl(lfParametersExt.isVoltageRemoteControl())
                .setMinImpedance(true)
                .setTwtSplitShuntAdmittance(lfParameters.isTwtSplitShuntAdmittance())
                .setBreakers(breakers)
                .setPlausibleActivePowerLimit(lfParametersExt.getPlausibleActivePowerLimit())
                .setComputeMainConnectedComponentOnly(true)
                .setCountriesToBalance(lfParameters.getCountriesToBalance())
                .setDistributedOnConformLoad(lfParameters.getBalanceType() == LoadFlowParameters.BalanceType.PROPORTIONAL_TO_CONFORM_LOAD)
                .setPhaseControl(lfParameters.isPhaseShifterRegulationOn())
                .setTransformerVoltageControl(lfParameters.isTransformerVoltageControlOn())
                .setVoltagePerReactivePowerControl(lfParametersExt.isVoltagePerReactivePowerControl())
                .setGeneratorReactivePowerRemoteControl(lfParametersExt.isGeneratorReactivePowerRemoteControl())
                .setTransformerReactivePowerControl(lfParametersExt.isTransformerReactivePowerControl())
                .setLoadFlowModel(lfParameters.isDc() ? LoadFlowModel.DC : LoadFlowModel.AC)
                .setShuntVoltageControl(lfParameters.isShuntCompensatorVoltageControlOn())
                .setReactiveLimits(lfParameters.isUseReactiveLimits())
                .setHvdcAcEmulation(lfParameters.isHvdcAcEmulation())
                .setMinPlausibleTargetVoltage(lfParametersExt.getMinPlausibleTargetVoltage())
                .setMaxPlausibleTargetVoltage(lfParametersExt.getMaxPlausibleTargetVoltage())
                .setMinNominalVoltageTargetVoltageCheck(lfParametersExt.getMinNominalVoltageTargetVoltageCheck())
                .setCacheEnabled(false) // force not caching as not supported in sensi analysis
                .setSimulateAutomationSystems(false)
                .setReferenceBusSelector(ReferenceBusSelector.DEFAULT_SELECTOR) // not supported yet
                .setAreaInterchangeControl(lfParametersExt.isAreaInterchangeControl())
                .setAreaInterchangeControlAreaType(lfParametersExt.getAreaInterchangeControlAreaType());

        // create networks including all necessary switches
        try (LfNetworkList lfNetworks = Networks.load(network, lfNetworkParameters, topoConfig, reportNode)) {
            LfNetwork lfNetwork = lfNetworks.getLargest().orElseThrow(() -> new PowsyblException("Empty network"));

            checkContingencies(contingencies);
            checkLoadFlowParameters(lfParameters);

            Map<String, SensitivityVariableSet> variableSetsById = variableSets.stream().collect(Collectors.toMap(SensitivityVariableSet::getId, Function.identity()));
            SensitivityFactorHolder<AcVariableType, AcEquationType> allFactorHolder = readAndCheckFactors(network, variableSetsById, factorReader, lfNetwork, breakers);
            List<LfSensitivityFactor<AcVariableType, AcEquationType>> allLfFactors = allFactorHolder.getAllFactors();
            LOGGER.info("Running AC sensitivity analysis with {} factors and {} contingencies", allLfFactors.size(), contingencies.size());

            // next we only work with valid and valid only for function factors
            var validFactorHolder = writeInvalidFactors(allFactorHolder, resultWriter, contingencies);
            var validLfFactors = validFactorHolder.getAllFactors();

            // create AC engine
            AcLoadFlowParameters acParameters = OpenLoadFlowParameters.createAcParameters(network, lfParameters, lfParametersExt, matrixFactory, connectivityFactory, breakers, true);
            acParameters.setDetailedReport(lfParametersExt.getReportedFeatures().contains(OpenLoadFlowParameters.ReportedFeatures.NEWTON_RAPHSON_SENSITIVITY_ANALYSIS));

            try (AcLoadFlowContext context = new AcLoadFlowContext(lfNetwork, acParameters)) {

                runLoadFlow(context, true, true);

                // index factors by variable group to compute a minimal number of states
                SensitivityFactorGroupList<AcVariableType, AcEquationType> factorGroups = createFactorGroups(validLfFactors.stream()
                        .filter(factor -> factor.getStatus() == LfSensitivityFactor.Status.VALID).collect(Collectors.toList()));

                // compute the participation for each injection factor (+1 on the injection and then -participation factor on all
                // buses that contain elements participating to slack distribution

                Map<LfBus, Double> slackParticipationByBus;
                if (lfParameters.isDistributedSlack()) {
                    List<ParticipatingElement> participatingElements = getParticipatingElements(lfNetwork.getBuses(), lfParameters.getBalanceType(), lfParametersExt);
                    slackParticipationByBus = participatingElements.stream().collect(Collectors.toMap(
                        ParticipatingElement::getLfBus,
                        element -> -element.getFactor(),
                        Double::sum
                    ));
                } else {
                    slackParticipationByBus = Collections.singletonMap(lfNetwork.getSlackBus(), -1d);
                }

                // if we have at least one bus target voltage linked to a ratio tap changer, we have to rebuild the AC equation
                // system obtained just before the transformer steps rounding.
                if (Boolean.TRUE.equals(hasBusTargetVoltage.getRight())) {
                    // switch on regulating transformers
                    for (LfBranch branch : lfNetwork.getBranches()) {
                        branch.getVoltageControl().ifPresent(vc -> branch.setVoltageControlEnabled(true));
                    }
                    lfNetwork.fixTransformerVoltageControls();
                }

                // we make the assumption that we ran a loadflow before, and thus this jacobian is the right one

                // initialize right hand side from valid factors
                DenseMatrix factorsStates = initFactorsRhs(context.getEquationSystem(), factorGroups, slackParticipationByBus); // this is the rhs for the moment

                // solve system
                context.getJacobianMatrix().solveTransposed(factorsStates);

                // calculate sensitivity values
                setFunctionReferences(validLfFactors);
                calculateSensitivityValues(validFactorHolder.getFactorsForBaseNetwork(), factorGroups, factorsStates, -1, resultWriter);

                NetworkState networkState = NetworkState.save(lfNetwork);

                // we always restart from base case voltages for contingency simulation
                context.getParameters().setVoltageInitializer(new PreviousValueVoltageInitializer());

                contingencies.forEach(contingency -> {
                    LOGGER.info("Simulate contingency '{}'", contingency.getContingency().getId());
                    contingency.toLfContingency(lfNetwork)
                            .ifPresentOrElse(lfContingency -> {
                                List<LfSensitivityFactor<AcVariableType, AcEquationType>> contingencyFactors = validFactorHolder.getFactorsForContingency(lfContingency.getId());
                                contingencyFactors.forEach(lfFactor -> {
                                    lfFactor.setSensitivityValuePredefinedResult(null);
                                    lfFactor.setFunctionPredefinedResult(null);
                                });

                                lfContingency.apply(lfParameters.getBalanceType());

                                setPredefinedResults(contingencyFactors, lfContingency.getDisabledNetwork(), contingency);

                                Map<LfBus, Double> postContingencySlackParticipationByBus;
                                Set<LfBus> slackConnectedComponent;
                                boolean hasChanged = false;
                                if (lfContingency.getDisabledNetwork().getBuses().isEmpty()) {
                                    // contingency not breaking connectivity
                                    LOGGER.debug("Contingency '{}' without loss of connectivity", lfContingency.getId());
                                    slackConnectedComponent = new HashSet<>(lfNetwork.getBuses());
                                } else {
                                    // contingency breaking connectivity
                                    LOGGER.debug("Contingency '{}' with loss of connectivity", lfContingency.getId());
                                    // we check if factors are still in the main component
                                    slackConnectedComponent = new HashSet<>(lfNetwork.getBuses()).stream().filter(Predicate.not(lfContingency.getDisabledNetwork().getBuses()::contains)).collect(Collectors.toSet());
                                    // we recompute GLSK weights if needed
                                    hasChanged = rescaleGlsk(factorGroups, lfContingency.getDisabledNetwork().getBuses());
                                }

                                // compute the participation for each injection factor (+1 on the injection and then -participation factor on all
                                // buses that contain elements participating to slack distribution)
                                if (lfParameters.isDistributedSlack()) {
                                    postContingencySlackParticipationByBus = getParticipatingElements(slackConnectedComponent, lfParameters.getBalanceType(), lfParametersExt).stream().collect(Collectors.toMap(
                                            ParticipatingElement::getLfBus, element -> -element.getFactor(), Double::sum));
                                } else {
                                    postContingencySlackParticipationByBus = Collections.singletonMap(lfNetwork.getSlackBus(), -1d);
                                }
                                calculatePostContingencySensitivityValues(contingencyFactors, lfContingency, lfNetwork, context, factorGroups, postContingencySlackParticipationByBus,
                                        lfParameters, lfParametersExt, lfContingency.getIndex(), resultWriter, Boolean.TRUE.equals(hasBusTargetVoltage.getRight()));

                                if (hasChanged) {
                                    rescaleGlsk(factorGroups, Collections.emptySet());
                                }
                                networkState.restore();
                            }, () -> {
                                    // it means that the contingency has no impact.
                                    // we need to force the state vector to be re-initialized from base case network state
                                    AcSolverUtil.initStateVector(lfNetwork, context.getEquationSystem(), context.getParameters().getVoltageInitializer());

                                    calculateSensitivityValues(validFactorHolder.getFactorsForContingency(contingency.getContingency().getId()), factorGroups, factorsStates, contingency.getIndex(), resultWriter);
                                    // write contingency status
                                    resultWriter.writeContingencyStatus(contingency.getIndex(), SensitivityAnalysisResult.Status.NO_IMPACT);
                                });
                });
            }
        }
    }
}
