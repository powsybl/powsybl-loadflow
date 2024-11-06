/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.sensi;

import com.google.common.base.Stopwatch;
import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.math.matrix.DenseMatrixFactory;
import com.powsybl.math.matrix.MatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.dc.DcLoadFlowContext;
import com.powsybl.openloadflow.dc.DcLoadFlowEngine;
import com.powsybl.openloadflow.dc.DcLoadFlowParameters;
import com.powsybl.openloadflow.dc.equations.DcEquationSystemCreationParameters;
import com.powsybl.openloadflow.dc.equations.DcEquationType;
import com.powsybl.openloadflow.dc.equations.DcVariableType;
import com.powsybl.openloadflow.graph.GraphConnectivityFactory;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.network.impl.LfNetworkList;
import com.powsybl.openloadflow.network.impl.Networks;
import com.powsybl.openloadflow.network.impl.PropagatedContingency;
import com.powsybl.openloadflow.network.util.ParticipatingElement;
import com.powsybl.openloadflow.network.util.PreviousValueVoltageInitializer;
import com.powsybl.openloadflow.network.util.UniformValueVoltageInitializer;
import com.powsybl.openloadflow.network.util.VoltageInitializer;
import com.powsybl.openloadflow.util.Derivable;
import com.powsybl.sensitivity.*;
import org.apache.commons.lang3.tuple.Pair;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.powsybl.openloadflow.dc.DcLoadFlowEngine.run;
import static com.powsybl.openloadflow.network.impl.PropagatedContingency.cleanContingencies;
import static com.powsybl.openloadflow.network.util.ParticipatingElement.normalizeParticipationFactors;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 * @author Gaël Macherel {@literal <gael.macherel@artelys.com>}
 */
public class DcSensitivityAnalysis extends AbstractSensitivityAnalysis<DcVariableType, DcEquationType> {
    private static final double FUNCTION_REFERENCE_ZER0_THRESHOLD = 1e-13;

    public DcSensitivityAnalysis(MatrixFactory matrixFactory, GraphConnectivityFactory<LfBus, LfBranch> connectivityFactory, SensitivityAnalysisParameters parameters) {
        super(matrixFactory, connectivityFactory, parameters);
    }

    private static DcLoadFlowParameters createDcLoadFlowParameters(LfNetworkParameters networkParameters, MatrixFactory matrixFactory,
                                                                   LoadFlowParameters lfParameters, OpenLoadFlowParameters parametersExt) {
        var equationSystemCreationParameters = new DcEquationSystemCreationParameters()
                .setUpdateFlows(true)
                .setForcePhaseControlOffAndAddAngle1Var(true)
                .setUseTransformerRatio(lfParameters.isDcUseTransformerRatio())
                .setDcApproximationType(parametersExt.getDcApproximationType());

        return new DcLoadFlowParameters()
                .setNetworkParameters(networkParameters)
                .setEquationSystemCreationParameters(equationSystemCreationParameters)
                .setMatrixFactory(matrixFactory)
                .setDistributedSlack(lfParameters.isDistributedSlack())
                .setBalanceType(lfParameters.getBalanceType())
                .setSetVToNan(true)
                .setMaxOuterLoopIterations(parametersExt.getMaxOuterLoopIterations());
    }

    /**
     * Calculate the active power flows for pre-contingency or a post-contingency state.
     * The interesting disabled branches are only phase shifters.
     */
    private DenseMatrix calculateFlowStates(DcLoadFlowContext loadFlowContext, List<ParticipatingElement> participatingElements,
                                            DisabledNetwork disabledNetwork, ReportNode reportNode) {
        List<BusState> busStates = Collections.emptyList();
        DcLoadFlowParameters parameters = loadFlowContext.getParameters();
        if (parameters.isDistributedSlack()) {
            busStates = ElementState.save(participatingElements.stream()
                    .map(ParticipatingElement::getLfBus)
                    .collect(Collectors.toSet()), BusState::save);
        }

        double[] dx = run(loadFlowContext, disabledNetwork, reportNode);

        if (parameters.isDistributedSlack()) {
            ElementState.restore(busStates);
        }

        return new DenseMatrix(dx.length, 1, dx);
    }

    /**
     * Calculate flow and sensitivity values from pre-contingency states or post-contingency states.
     * Write the flow and sensitivity values for a LfSensitivityFactor in the SensitivityResultWriter.
     */
    private void createBranchSensitivityValue(LfSensitivityFactor<DcVariableType, DcEquationType> factor, SensitivityFactorGroup<DcVariableType, DcEquationType> factorGroup,
                                              DenseMatrix flowStates, DenseMatrix factorStates, PropagatedContingency contingency, SensitivityResultWriter resultWriter,
                                              DisabledNetwork disabledNetwork) {
        Pair<Optional<Double>, Optional<Double>> predefinedResults = getPredefinedResults(factor, disabledNetwork, contingency);
        Optional<Double> sensitivityValuePredefinedResult = predefinedResults.getLeft();
        Optional<Double> functionPredefinedResults = predefinedResults.getRight();
        double sensitivityValue = sensitivityValuePredefinedResult.orElse(0d);
        double functionValue = functionPredefinedResults.orElse(0d);
        Derivable<DcVariableType> p1 = factor.getFunctionEquationTerm();

        if (functionPredefinedResults.isEmpty()) {
            functionValue = p1.calculateSensi(flowStates, 0);
        }

        if (sensitivityValuePredefinedResult.isEmpty()) {
            sensitivityValue = p1.calculateSensi(factorStates, factorGroup.getIndex());
        }

        functionValue = fixZeroFunctionReference(contingency, functionValue);

        double unscaledSensi = unscaleSensitivity(factor, sensitivityValue);
        if (!filterSensitivityValue(unscaledSensi, factor.getVariableType(), factor.getFunctionType(), parameters)) {
            resultWriter.writeSensitivityValue(factor.getIndex(), contingency != null ? contingency.getIndex() : -1, unscaledSensi, unscaleFunction(factor, functionValue));
        }
    }

    /**
     * Post contingency reference flow, that should be strictly zero, for numeric reason and because it is computed
     * from shifting pre-contingency non-zero flow, cannot end up to a strict zero: very small values are converted to zero.
     */
    private static double fixZeroFunctionReference(PropagatedContingency contingency, double functionValue) {
        if (contingency != null) {
            return Math.abs(functionValue) < FUNCTION_REFERENCE_ZER0_THRESHOLD ? 0 : functionValue;
        }
        return functionValue;
    }

    /**
     * Compute state for sensitivity factors taking into account slack distribution.
     */
    private DenseMatrix calculateFactorStates(DcLoadFlowContext loadFlowContext,
                                              SensitivityFactorGroupList<DcVariableType, DcEquationType> factorGroups,
                                              List<ParticipatingElement> participatingElements) {
        Map<LfBus, Double> slackParticipationByBus;
        if (participatingElements.isEmpty()) {
            slackParticipationByBus = Map.of(loadFlowContext.getNetwork().getSlackBus(), -1d);
        } else {
            slackParticipationByBus = participatingElements.stream().collect(Collectors.toMap(
                ParticipatingElement::getLfBus,
                element -> -element.getFactor(),
                Double::sum));
        }

        DenseMatrix factorStates = initFactorsRhs(loadFlowContext.getEquationSystem(), factorGroups, slackParticipationByBus);
        loadFlowContext.getJacobianMatrix().solveTransposed(factorStates); // states for the sensitivity factors
        return factorStates;
    }

    /**
     * Create branch flow and sensitivity values from a pre-contingency state or a post-contingency state.
     */
    private void calculateSensitivityValues(List<LfSensitivityFactor<DcVariableType, DcEquationType>> lfFactors, DenseMatrix factorStates, DenseMatrix flowStates,
                                            PropagatedContingency contingency, SensitivityResultWriter resultWriter, DisabledNetwork disabledNetwork) {
        if (lfFactors.isEmpty()) {
            return;
        }

        lfFactors.stream().filter(factor -> factor.getStatus() == LfSensitivityFactor.Status.VALID_ONLY_FOR_FUNCTION)
                .forEach(factor -> createBranchSensitivityValue(factor, null, flowStates, factorStates, contingency, resultWriter, disabledNetwork));

        Map<SensitivityFactorGroup<DcVariableType, DcEquationType>, List<LfSensitivityFactor<DcVariableType, DcEquationType>>> factorsByGroup = lfFactors.stream()
                .filter(factor -> factor.getStatus() == LfSensitivityFactor.Status.VALID)
                .collect(Collectors.groupingBy(LfSensitivityFactor::getGroup, LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<SensitivityFactorGroup<DcVariableType, DcEquationType>, List<LfSensitivityFactor<DcVariableType, DcEquationType>>> e : factorsByGroup.entrySet()) {
            SensitivityFactorGroup<DcVariableType, DcEquationType> factorGroup = e.getKey();
            List<LfSensitivityFactor<DcVariableType, DcEquationType>> factorsForThisGroup = e.getValue();
            for (LfSensitivityFactor<DcVariableType, DcEquationType> factor : factorsForThisGroup) {
                createBranchSensitivityValue(factor, factorGroup, flowStates, factorStates, contingency, resultWriter, disabledNetwork);
            }
        }
    }

    /**
     * Calculate sensitivity values for a contingency.
     * In case of connectivity break, a pre-computation has been done in {@link #processContingenciesBreakingConnectivity}
     * to get a first version of the new participating elements, that can be overridden in this method, and to indicate
     * if the factorsStates should be overridden or not in this method.
     * If connectivity, a generator, a load or a phase tap changer is lost due to the contingency,
     * the flowStates are overridden.
     */
    private void calculateSensitivityValuesForAContingency(DcLoadFlowContext loadFlowContext, OpenLoadFlowParameters lfParametersExt, SensitivityFactorHolder<DcVariableType, DcEquationType> validFactorHolder,
                                                           SensitivityFactorGroupList<DcVariableType, DcEquationType> factorGroups, DenseMatrix factorStates, DenseMatrix contingenciesStates, DenseMatrix flowStates,
                                                           PropagatedContingency contingency, Map<String, ComputedContingencyElement> contingencyElementByBranch,
                                                           Set<LfBus> disabledBuses, List<ParticipatingElement> participatingElements, Set<String> elementsToReconnect,
                                                           SensitivityResultWriter resultWriter, ReportNode reportNode, Set<LfBranch> partialDisabledBranches, boolean rhsChangedAfterConnectivityBreak) {
        List<LfSensitivityFactor<DcVariableType, DcEquationType>> factors = validFactorHolder.getFactorsForContingency(contingency.getContingency().getId());
        List<ComputedContingencyElement> contingencyElements = contingency.getBranchIdsToOpen().keySet().stream()
                .filter(element -> !elementsToReconnect.contains(element))
                .map(contingencyElementByBranch::get)
                .collect(Collectors.toList());

        var lfNetwork = loadFlowContext.getNetwork();
        Set<LfBranch> disabledBranches = contingency.getBranchIdsToOpen().keySet().stream().map(lfNetwork::getBranchById).collect(Collectors.toSet());
        disabledBranches.addAll(partialDisabledBranches);
        DisabledNetwork disabledNetwork = new DisabledNetwork(disabledBuses, disabledBranches);
        DenseMatrix newFactorStates = factorStates;

        WoodburyEngine engine = new WoodburyEngine(loadFlowContext.getParameters().getEquationSystemCreationParameters(), contingencyElements, contingenciesStates);

        if (contingency.getGeneratorIdsToLose().isEmpty() && contingency.getLoadIdsToLose().isEmpty()) {
            DenseMatrix newFlowStates = flowStates;
            // we need to recompute the factor states because the connectivity changed
            if (rhsChangedAfterConnectivityBreak) {
                newFactorStates = calculateFactorStates(loadFlowContext, factorGroups, participatingElements);
            }

            // get the lost phase tap changers for this contingency
            Set<LfBranch> lostPhaseControllers = contingency.getBranchIdsToOpen().keySet().stream()
                    .filter(element -> !elementsToReconnect.contains(element))
                    .map(contingencyElementByBranch::get)
                    .map(ComputedContingencyElement::getLfBranch)
                    .filter(LfBranch::hasPhaseControllerCapability)
                    .collect(Collectors.toSet());

            // if a phase tap changer is lost or if the connectivity have changed, we must recompute load flows
            if (!disabledBuses.isEmpty() || !lostPhaseControllers.isEmpty()) {
                newFlowStates = calculateFlowStates(loadFlowContext, participatingElements, disabledNetwork, reportNode);
            }

            DenseMatrix postContingencyFlowStates = engine.run(newFlowStates);
            DenseMatrix postContingencyFactorStates = engine.run(newFactorStates);
            calculateSensitivityValues(factors, postContingencyFactorStates, postContingencyFlowStates, contingency, resultWriter, disabledNetwork);
            // write contingency status
            if (contingency.hasNoImpact()) {
                resultWriter.writeContingencyStatus(contingency.getIndex(), SensitivityAnalysisResult.Status.NO_IMPACT);
            } else {
                resultWriter.writeContingencyStatus(contingency.getIndex(), SensitivityAnalysisResult.Status.SUCCESS);
            }
        } else {
            // if we have a contingency including the loss of a DC line or a generator or a load
            // save base state for later restoration after each contingency
            DcLoadFlowParameters lfParameters = loadFlowContext.getParameters();
            NetworkState networkState = NetworkState.save(lfNetwork);
            List<ParticipatingElement> newParticipatingElements = participatingElements;
            boolean participatingElementsChanged = false;
            boolean rhsChangedAfterGlskRescaling = false;
            LfContingency lfContingency = contingency.toLfContingency(lfNetwork).orElse(null);
            if (lfContingency != null) {
                lfContingency.apply(lfParameters.getBalanceType());
                if (isDistributedSlackOnGenerators(lfParameters) && !contingency.getGeneratorIdsToLose().isEmpty()) {
                    // deep copy of participatingElements, removing the participating LfGeneratorImpl whose targetP has been set to 0
                    Set<LfGenerator> participatingGeneratorsToRemove = lfContingency.getLostGenerators();
                    newParticipatingElements = participatingElements.stream()
                            .filter(participatingElement -> !participatingGeneratorsToRemove.contains(participatingElement.getElement()))
                            .map(participatingElement -> new ParticipatingElement(participatingElement.getElement(), participatingElement.getFactor()))
                            .collect(Collectors.toList());
                    normalizeParticipationFactors(newParticipatingElements);
                    participatingElementsChanged = true;
                } else if (isDistributedSlackOnLoads(lfParameters) && !contingency.getLoadIdsToLose().isEmpty()) {
                    newParticipatingElements = getParticipatingElements(lfNetwork.getBuses(), lfParameters.getBalanceType(), lfParametersExt);
                    participatingElementsChanged = true;
                }
                if (factorGroups.hasMultiVariables()) {
                    Set<LfBus> impactedBuses = lfContingency.getLoadAndGeneratorBuses();
                    rhsChangedAfterGlskRescaling = rescaleGlsk(factorGroups, impactedBuses);
                }
                // write contingency status
                resultWriter.writeContingencyStatus(contingency.getIndex(), SensitivityAnalysisResult.Status.SUCCESS);
            } else {
                // write contingency status
                resultWriter.writeContingencyStatus(contingency.getIndex(), SensitivityAnalysisResult.Status.NO_IMPACT);
            }

            // we need to recompute the factor states because the rhs or the participating elements have changed
            if (participatingElementsChanged || rhsChangedAfterGlskRescaling || rhsChangedAfterConnectivityBreak) {
                newFactorStates = calculateFactorStates(loadFlowContext, factorGroups, newParticipatingElements);
            }

            DenseMatrix newFlowStates = calculateFlowStates(loadFlowContext, newParticipatingElements, disabledNetwork, reportNode);

            DenseMatrix postContingencyFlowStates = engine.run(newFlowStates);
            DenseMatrix postContingencyFactorStates = engine.run(newFactorStates);
            calculateSensitivityValues(factors, postContingencyFactorStates, postContingencyFlowStates, contingency, resultWriter, disabledNetwork);

            networkState.restore();
        }
    }

    /**
     * Calculate sensitivity values for a contingency breaking connectivity.
     * It determines if the right hand side has been changed due to the contingency, e.g. when the slack distribution is
     * impacted by the disabled buses. If so, factorsStates will be overridden in {@link #calculateSensitivityValuesForAContingency}.
     */
    private void processContingenciesBreakingConnectivity(ConnectivityBreakAnalysis.ConnectivityAnalysisResult connectivityAnalysisResult, DcLoadFlowContext loadFlowContext,
                                                          LoadFlowParameters lfParameters, OpenLoadFlowParameters lfParametersExt,
                                                          SensitivityFactorHolder<DcVariableType, DcEquationType> validFactorHolder,
                                                          SensitivityFactorGroupList<DcVariableType, DcEquationType> factorGroups,
                                                          List<ParticipatingElement> participatingElements,
                                                          Map<String, ComputedContingencyElement> contingencyElementByBranch,
                                                          DenseMatrix flowStates, DenseMatrix factorsStates, DenseMatrix contingenciesStates,
                                                          SensitivityResultWriter resultWriter,
                                                          ReportNode reportNode) {

        PropagatedContingency contingency = connectivityAnalysisResult.getPropagatedContingency();
        Set<LfBus> disabledBuses = connectivityAnalysisResult.getDisabledBuses();
        Set<LfBranch> partialDisabledBranches = connectivityAnalysisResult.getPartialDisabledBranches();

        // as we are processing a contingency with connectivity break, we have to reset active power flow of a hvdc line
        // if one bus of the line is lost.
        for (LfHvdc hvdc : loadFlowContext.getNetwork().getHvdcs()) {
            if (Networks.isIsolatedBusForHvdc(hvdc.getBus1(), disabledBuses) ^ Networks.isIsolatedBusForHvdc(hvdc.getBus2(), disabledBuses)) {
                contingency.getGeneratorIdsToLose().add(hvdc.getConverterStation1().getId());
                contingency.getGeneratorIdsToLose().add(hvdc.getConverterStation2().getId());
            }
        }

        List<ParticipatingElement> participatingElementsForThisConnectivity = participatingElements;
        boolean rhsChanged = false; // true if the disabled buses change the slack distribution, or the GLSK
        if (lfParameters.isDistributedSlack()) {
            rhsChanged = participatingElements.stream().anyMatch(element -> disabledBuses.contains(element.getLfBus()));
        }
        if (factorGroups.hasMultiVariables()) {
            // some elements of the GLSK may not be in the connected component anymore, we recompute the injections
            rhsChanged |= rescaleGlsk(factorGroups, disabledBuses);
        }

        // we need to recompute the participating elements because the connectivity changed
        if (rhsChanged) {
            participatingElementsForThisConnectivity = lfParameters.isDistributedSlack()
                    ? getParticipatingElements(connectivityAnalysisResult.getSlackConnectedComponent(), lfParameters.getBalanceType(), lfParametersExt) // will also be used to recompute the loadflow
                    : Collections.emptyList();
        }

        calculateSensitivityValuesForAContingency(loadFlowContext, lfParametersExt,
                validFactorHolder, factorGroups, factorsStates, contingenciesStates, flowStates,
                contingency, contingencyElementByBranch, disabledBuses, participatingElementsForThisConnectivity,
                connectivityAnalysisResult.getElementsToReconnect(), resultWriter, reportNode, partialDisabledBranches, rhsChanged);
    }

    @Override
    public void analyse(Network network, List<PropagatedContingency> contingencies, List<SensitivityVariableSet> variableSets,
                        SensitivityFactorReader factorReader, SensitivityResultWriter resultWriter, ReportNode reportNode,
                        LfTopoConfig topoConfig) {
        Objects.requireNonNull(network);
        Objects.requireNonNull(contingencies);
        Objects.requireNonNull(variableSets);
        Objects.requireNonNull(factorReader);
        Objects.requireNonNull(resultWriter);

        LoadFlowParameters lfParameters = parameters.getLoadFlowParameters();
        OpenLoadFlowParameters lfParametersExt = OpenLoadFlowParameters.get(lfParameters);

        Stopwatch stopwatch = Stopwatch.createStarted();

        boolean breakers = topoConfig.isBreaker();

        // create the network (we only manage main connected component)
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
                .setGeneratorVoltageRemoteControl(false)
                .setMinImpedance(true)
                .setTwtSplitShuntAdmittance(lfParameters.isTwtSplitShuntAdmittance())
                .setBreakers(breakers)
                .setPlausibleActivePowerLimit(lfParametersExt.getPlausibleActivePowerLimit())
                .setComputeMainConnectedComponentOnly(true)
                .setCountriesToBalance(lfParameters.getCountriesToBalance())
                .setDistributedOnConformLoad(lfParameters.getBalanceType() == LoadFlowParameters.BalanceType.PROPORTIONAL_TO_CONFORM_LOAD)
                .setPhaseControl(false)
                .setTransformerVoltageControl(false)
                .setVoltagePerReactivePowerControl(false)
                .setGeneratorReactivePowerRemoteControl(false)
                .setTransformerReactivePowerControl(false)
                .setLoadFlowModel(LoadFlowModel.DC)
                .setShuntVoltageControl(false)
                .setReactiveLimits(false)
                .setHvdcAcEmulation(false) // still not supported
                .setCacheEnabled(false) // force not caching as not supported in sensi analysis
                .setReferenceBusSelector(ReferenceBusSelector.DEFAULT_SELECTOR); // not supported yet
        // create networks including all necessary switches
        try (LfNetworkList lfNetworks = Networks.load(network, lfNetworkParameters, topoConfig, reportNode)) {
            LfNetwork lfNetwork = lfNetworks.getLargest().orElseThrow(() -> new PowsyblException("Empty network"));

            checkContingencies(contingencies);
            cleanContingencies(lfNetwork, contingencies);
            checkLoadFlowParameters(lfParameters);

            Map<String, SensitivityVariableSet> variableSetsById = variableSets.stream().collect(Collectors.toMap(SensitivityVariableSet::getId, Function.identity()));
            SensitivityFactorHolder<DcVariableType, DcEquationType> allFactorHolder = readAndCheckFactors(network, variableSetsById, factorReader, lfNetwork, breakers);
            List<LfSensitivityFactor<DcVariableType, DcEquationType>> allLfFactors = allFactorHolder.getAllFactors();

            allLfFactors.stream()
                    .filter(lfFactor -> lfFactor.getFunctionType() != SensitivityFunctionType.BRANCH_ACTIVE_POWER_1
                                && lfFactor.getFunctionType() != SensitivityFunctionType.BRANCH_ACTIVE_POWER_2
                            || lfFactor.getVariableType() != SensitivityVariableType.INJECTION_ACTIVE_POWER
                                && lfFactor.getVariableType() != SensitivityVariableType.TRANSFORMER_PHASE
                                && lfFactor.getVariableType() != SensitivityVariableType.HVDC_LINE_ACTIVE_POWER)
                    .findFirst()
                    .ifPresent(ignored -> {
                        throw new PowsyblException("Only variables of type TRANSFORMER_PHASE, INJECTION_ACTIVE_POWER and HVDC_LINE_ACTIVE_POWER, and functions of type BRANCH_ACTIVE_POWER_1 and BRANCH_ACTIVE_POWER_2 are yet supported in DC");
                    });

            LOGGER.info("Running DC sensitivity analysis with {} factors and {} contingencies", allLfFactors.size(), contingencies.size());

            var dcLoadFlowParameters = createDcLoadFlowParameters(lfNetworkParameters, matrixFactory, lfParameters, lfParametersExt);

            // next we only work with valid factors
            var validFactorHolder = writeInvalidFactors(allFactorHolder, resultWriter, contingencies);
            var validLfFactors = validFactorHolder.getAllFactors();
            LOGGER.info("{}/{} factors are valid", validLfFactors.size(), allLfFactors.size());

            try (DcLoadFlowContext loadFlowContext = new DcLoadFlowContext(lfNetwork, dcLoadFlowParameters, false)) {

                // create jacobian matrix either using calculated voltages from pre-contingency network or nominal voltages
                VoltageInitializer voltageInitializer = lfParameters.getVoltageInitMode() == LoadFlowParameters.VoltageInitMode.PREVIOUS_VALUES
                        ? new PreviousValueVoltageInitializer()
                        : new UniformValueVoltageInitializer();

                DcLoadFlowEngine.initStateVector(lfNetwork, loadFlowContext.getEquationSystem(), voltageInitializer);

                // index factors by variable group to compute the minimal number of states
                SensitivityFactorGroupList<DcVariableType, DcEquationType> factorGroups = createFactorGroups(validLfFactors.stream().filter(factor -> factor.getStatus() == LfSensitivityFactor.Status.VALID).collect(Collectors.toList()));

                // compute the participation for each injection factor (+1 on the injection and then -participation factor on all
                // buses that contain elements participating to slack distribution)
                List<ParticipatingElement> participatingElements = lfParameters.isDistributedSlack()
                        ? getParticipatingElements(lfNetwork.getBuses(), lfParameters.getBalanceType(), lfParametersExt)
                        : Collections.emptyList();

                // run DC load on pre-contingency network
                DenseMatrix baseFlowStates = calculateFlowStates(loadFlowContext, participatingElements, new DisabledNetwork(), reportNode);
                DenseMatrix flowStates = (DenseMatrix) baseFlowStates.copy(new DenseMatrixFactory());
                // compute the pre-contingency factor states
                DenseMatrix baseFactorStates = calculateFactorStates(loadFlowContext, factorGroups, participatingElements);
                DenseMatrix factorStates = (DenseMatrix) baseFactorStates.copy(new DenseMatrixFactory());

                // calculate sensitivity values for pre-contingency network
                calculateSensitivityValues(validFactorHolder.getFactorsForBaseNetwork(), factorStates, flowStates, null, resultWriter, new DisabledNetwork());

                // filter contingencies without factors
                List<PropagatedContingency> contingenciesWithFactors = new ArrayList<>();
                contingencies.forEach(contingency -> {
                    List<AbstractSensitivityAnalysis.LfSensitivityFactor<DcVariableType, DcEquationType>> lfFactors = validFactorHolder.getFactorsForContingencies(List.of(contingency.getContingency().getId()));
                    if (!lfFactors.isEmpty()) {
                        contingenciesWithFactors.add(contingency);
                    } else {
                        resultWriter.writeContingencyStatus(contingency.getIndex(), SensitivityAnalysisResult.Status.SUCCESS);
                    }
                });

                // compute states with +1 -1 to model the contingencies and run connectivity analysis
                ConnectivityBreakAnalysis.ConnectivityBreakAnalysisResults connectivityBreakAnalysisResults = ConnectivityBreakAnalysis.run(loadFlowContext, contingenciesWithFactors);

                LOGGER.info("Processing contingencies with no connectivity break");

                // process contingencies with no connectivity break
                for (PropagatedContingency contingency : connectivityBreakAnalysisResults.nonBreakingConnectivityContingencies()) {
                    matrixShallowCopy(baseFlowStates, flowStates);
                    matrixShallowCopy(baseFactorStates, factorStates);

                    calculateSensitivityValuesForAContingency(loadFlowContext, lfParametersExt, validFactorHolder, factorGroups,
                            factorStates, connectivityBreakAnalysisResults.contingenciesStates(), flowStates, contingency,
                            connectivityBreakAnalysisResults.contingencyElementByBranch(), Collections.emptySet(), participatingElements, Collections.emptySet(), resultWriter, reportNode, Collections.emptySet(), false);
                }

                LOGGER.info("Processing contingencies with connectivity break");

                // process contingencies with connectivity break
                for (ConnectivityBreakAnalysis.ConnectivityAnalysisResult connectivityAnalysisResult : connectivityBreakAnalysisResults.connectivityAnalysisResults()) {
                    matrixShallowCopy(baseFlowStates, flowStates);
                    matrixShallowCopy(baseFactorStates, factorStates);

                    processContingenciesBreakingConnectivity(connectivityAnalysisResult, loadFlowContext, lfParameters, lfParametersExt,
                            validFactorHolder, factorGroups, participatingElements, connectivityBreakAnalysisResults.contingencyElementByBranch(),
                            flowStates, factorStates, connectivityBreakAnalysisResults.contingenciesStates(), resultWriter, reportNode);
                }
            }

            stopwatch.stop();
            LOGGER.info("DC sensitivity analysis done in {} ms", stopwatch.elapsed(TimeUnit.MILLISECONDS));
        }
    }

    /**
     * Copy all the values that are in an originalMatrix and paste it in the copyMatrix (without allocating new memory spaces)
     * The dimensions of both matrices must be the same
     */
    // TODO : implement this method for DenseMatrix in powsybl-core ?
    private static void matrixShallowCopy(DenseMatrix originalMatrix, DenseMatrix copyMatrix) {
        if (originalMatrix.getRowCount() == copyMatrix.getRowCount() && originalMatrix.getColumnCount() == copyMatrix.getColumnCount()) {
            for (int columnIndex = 0; columnIndex < originalMatrix.getColumnCount(); columnIndex++) {
                for (int rowIndex = 0; rowIndex < originalMatrix.getRowCount(); rowIndex++) {
                    copyMatrix.set(rowIndex, columnIndex, originalMatrix.get(rowIndex, columnIndex));
                }
            }
        }
    }

}
