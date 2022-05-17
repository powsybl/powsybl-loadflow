/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.sensi;

import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.reporter.Reporter;
import com.powsybl.iidm.network.Network;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.math.matrix.MatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.ac.equations.AcEquationType;
import com.powsybl.openloadflow.ac.equations.AcVariableType;
import com.powsybl.openloadflow.ac.outerloop.AcLoadFlowContext;
import com.powsybl.openloadflow.ac.outerloop.AcLoadFlowParameters;
import com.powsybl.openloadflow.ac.outerloop.AcloadFlowEngine;
import com.powsybl.openloadflow.equations.Variable;
import com.powsybl.openloadflow.graph.GraphDecrementalConnectivityFactory;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.network.impl.Networks;
import com.powsybl.openloadflow.network.impl.PropagatedContingency;
import com.powsybl.openloadflow.network.util.ActivePowerDistribution;
import com.powsybl.openloadflow.network.util.ParticipatingElement;
import com.powsybl.openloadflow.network.util.PreviousValueVoltageInitializer;
import com.powsybl.sensitivity.SensitivityFactorReader;
import com.powsybl.sensitivity.SensitivityValueWriter;
import com.powsybl.sensitivity.SensitivityVariableSet;
import com.powsybl.sensitivity.SensitivityVariableType;
import org.apache.commons.lang3.NotImplementedException;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 * @author Gael Macherel <gael.macherel at artelys.com>
 */
public class AcSensitivityAnalysis extends AbstractSensitivityAnalysis<AcVariableType, AcEquationType> {

    public AcSensitivityAnalysis(MatrixFactory matrixFactory, GraphDecrementalConnectivityFactory<LfBus, LfBranch> connectivityFactory) {
        super(matrixFactory, connectivityFactory);
    }

    private void calculateSensitivityValues(List<LfSensitivityFactor<AcVariableType, AcEquationType>> lfFactors, List<SensitivityFactorGroup<AcVariableType, AcEquationType>> factorGroups, DenseMatrix factorsState,
                                            int contingencyIndex, SensitivityValueWriter valueWriter) {
        Set<LfSensitivityFactor<AcVariableType, AcEquationType>> lfFactorsSet = new HashSet<>(lfFactors);

        // VALID_ONLY_FOR_FUNCTION status is for factors where variable element is not in the main connected component but reference element is.
        // Therefore, the sensitivity is known to value 0 and the reference value can be computed.
        lfFactors.stream().filter(factor -> factor.getStatus() == LfSensitivityFactor.Status.VALID_ONLY_FOR_FUNCTION)
                .forEach(factor -> valueWriter.write(factor.getIndex(), contingencyIndex, 0, unscaleFunction(factor, factor.getFunctionReference())));

        for (SensitivityFactorGroup<AcVariableType, AcEquationType> factorGroup : factorGroups) {
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
                    if (factor.getFunctionElement() instanceof LfBranch &&
                            factor instanceof SingleVariableLfSensitivityFactor &&
                            ((SingleVariableLfSensitivityFactor<AcVariableType, AcEquationType>) factor).getVariableElement() instanceof LfBranch &&
                            ((SingleVariableLfSensitivityFactor<AcVariableType, AcEquationType>) factor).getVariableElement().equals(factor.getFunctionElement())) {
                        // add nabla_p eta, fr specific cases
                        // the only case currently: if we are computing the sensitivity of a phasetap change on itself
                        Variable<AcVariableType> phi1Var = factor.getFunctionEquationTerm().getVariables()
                                .stream()
                                .filter(var -> var.getElementNum() == factor.getFunctionElement().getNum() && var.getType().equals(AcVariableType.BRANCH_ALPHA1))
                                .findAny()
                                .orElseThrow(() -> new PowsyblException("No alpha_1 variable on the function branch"));
                        sensi += Math.toRadians(factor.getFunctionEquationTerm().der(phi1Var));
                    }
                }
                if (factor.getFunctionPredefinedResult() != null) {
                    ref = factor.getFunctionPredefinedResult();
                } else {
                    ref = factor.getFunctionReference();
                }

                valueWriter.write(factor.getIndex(), contingencyIndex, unscaleSensitivity(factor, sensi), unscaleFunction(factor, ref));
            }
        }
    }

    protected void setFunctionReferences(List<LfSensitivityFactor<AcVariableType, AcEquationType>> factors) {
        for (LfSensitivityFactor<AcVariableType, AcEquationType> factor : factors) {
            if (factor.getFunctionPredefinedResult() != null) {
                factor.setFunctionReference(factor.getFunctionPredefinedResult());
            } else {
                factor.setFunctionReference(factor.getFunctionEquationTerm().eval());
            }
        }
    }

    private void calculatePostContingencySensitivityValues(List<LfSensitivityFactor<AcVariableType, AcEquationType>> lfFactors, LfContingency lfContingency,
                                                           LfNetwork lfNetwork, AcLoadFlowContext context, List<SensitivityFactorGroup<AcVariableType, AcEquationType>> factorGroups,
                                                           Map<LfBus, Double> participationByBus,
                                                           LoadFlowParameters lfParameters, OpenLoadFlowParameters lfParametersExt,
                                                           int contingencyIndex, SensitivityValueWriter valueWriter,
                                                           Reporter reporter, boolean hasTransformerBusTargetVoltage, boolean hasMultiVariables) {
        if (lfParameters.isDistributedSlack() && Math.abs(lfContingency.getActivePowerLoss()) > 0) {
            ActivePowerDistribution activePowerDistribution = ActivePowerDistribution.create(lfParameters.getBalanceType(), lfParametersExt.isLoadPowerFactorConstant());
            activePowerDistribution.run(lfNetwork, lfContingency.getActivePowerLoss());
        }

        context.getParameters().setVoltageInitializer(new PreviousValueVoltageInitializer());
        new AcloadFlowEngine(context)
                .run(reporter);

        // if we have at least one bus target voltage linked to a ratio tap changer, we have to rebuild the AC equation
        // system obtained just before the transformer steps rounding.
        if (hasTransformerBusTargetVoltage) {
            for (LfBranch branch : lfNetwork.getBranches()) {
                branch.getVoltageControl().ifPresent(vc -> branch.setVoltageControlEnabled(true));
            }
        }

        Map<LfBus, Double> newSlackParticipationByBus = participationByBus;
        if (lfContingency.getDisabledBuses().isEmpty()) {
            if (lfParameters.isDistributedSlack()) {
                newSlackParticipationByBus = getParticipatingElements(lfNetwork.getBuses(), lfParameters.getBalanceType(), lfParametersExt).stream().collect(Collectors.toMap(
                        ParticipatingElement::getLfBus, element -> -element.getFactor(), Double::sum));
            } else {
                newSlackParticipationByBus = Collections.singletonMap(lfNetwork.getSlackBus(), -1d);
            }
        }

        if (hasMultiVariables && (!lfContingency.getBusesLoadShift().isEmpty() || !lfContingency.getGenerators().isEmpty())) {
            // FIXME. It does not work with a contingency that breaks connectivity and loose an isolate injection.
            Set<LfBus> affectedBuses = lfContingency.getLoadAndGeneratorBuses();
            rescaleGlsk(factorGroups, affectedBuses);
        }

        // we make the assumption that we ran a loadflow before, and thus this jacobian is the right one

        // solve system
        DenseMatrix factorsStates = initFactorsRhs(context.getEquationSystem(), factorGroups, newSlackParticipationByBus); // this is the rhs for the moment
        context.getJacobianMatrix().solveTransposed(factorsStates);
        setFunctionReferences(lfFactors);

        // calculate sensitivity values
        calculateSensitivityValues(lfFactors, factorGroups, factorsStates, contingencyIndex, valueWriter);
    }

    @Override
    public void checkContingencies(LfNetwork lfNetwork, List<PropagatedContingency> contingencies) {
        super.checkContingencies(lfNetwork, contingencies);

        for (PropagatedContingency contingency : contingencies) {
            if (!contingency.getShuntIdsToShift().isEmpty()) {
                throw new NotImplementedException("Shunt Contingencies are not yet supported in AC mode.");
            }
        }
    }

    /**
     * https://people.montefiore.uliege.be/vct/elec0029/lf.pdf / Equation 32 is transposed
     */
    public void analyse(Network network, List<PropagatedContingency> contingencies, List<SensitivityVariableSet> variableSets,
                        LoadFlowParameters lfParameters, OpenLoadFlowParameters lfParametersExt, SensitivityFactorReader factorReader,
                        SensitivityValueWriter valueWriter, Reporter reporter) {
        Objects.requireNonNull(network);
        Objects.requireNonNull(contingencies);
        Objects.requireNonNull(lfParameters);
        Objects.requireNonNull(lfParametersExt);
        Objects.requireNonNull(factorReader);
        Objects.requireNonNull(valueWriter);

        // create LF network (we only manage main connected component)
        boolean hasTransformerBusTargetVoltage = hasTransformerBusTargetVoltage(factorReader, network);
        if (hasTransformerBusTargetVoltage) {
            // if we have at least one bus target voltage linked to a ratio tap changer, we activate the transformer
            // voltage control for the AC load flow engine.
            lfParameters.setTransformerVoltageControlOn(true);
        }
        SlackBusSelector slackBusSelector = SlackBusSelector.fromMode(lfParametersExt.getSlackBusSelectionMode(), lfParametersExt.getSlackBusesIds());
        LfNetworkParameters lfNetworkParameters = new LfNetworkParameters(slackBusSelector,
                                                                          connectivityFactory,
                                                                          lfParametersExt.hasVoltageRemoteControl(),
                                                                          true,
                                                                          lfParameters.isTwtSplitShuntAdmittance(),
                                                                          false,
                                                                          lfParametersExt.getPlausibleActivePowerLimit(),
                                                                          lfParametersExt.isAddRatioToLinesWithDifferentNominalVoltageAtBothEnds(),
                                                                          true,
                                                                          lfParameters.getCountriesToBalance(),
                                                                          lfParameters.getBalanceType() == LoadFlowParameters.BalanceType.PROPORTIONAL_TO_CONFORM_LOAD,
                                                                          lfParameters.isPhaseShifterRegulationOn(),
                                                                          lfParameters.isTransformerVoltageControlOn(),
                                                                          lfParametersExt.isVoltagePerReactivePowerControl(),
                                                                          lfParametersExt.hasReactivePowerRemoteControl(),
                                                                          lfParameters.isDc(),
                                                                          lfParameters.isShuntCompensatorVoltageControlOn(),
                                                                          !lfParameters.isNoGeneratorReactiveLimits(),
                                                                          lfParameters.isHvdcAcEmulation());
        List<LfNetwork> lfNetworks = Networks.load(network, lfNetworkParameters, reporter);
        LfNetwork lfNetwork = lfNetworks.get(0);
        checkContingencies(lfNetwork, contingencies);
        checkLoadFlowParameters(lfParameters);

        Map<String, SensitivityVariableSet> variableSetsById = variableSets.stream().collect(Collectors.toMap(SensitivityVariableSet::getId, Function.identity()));
        SensitivityFactorHolder<AcVariableType, AcEquationType> allFactorHolder = readAndCheckFactors(network, variableSetsById, factorReader, lfNetwork);
        List<LfSensitivityFactor<AcVariableType, AcEquationType>> allLfFactors = allFactorHolder.getAllFactors();
        LOGGER.info("Running AC sensitivity analysis with {} factors and {} contingencies",  allLfFactors.size(), contingencies.size());

        // next we only work with valid and valid only for function factors
        var validFactorHolder = writeInvalidFactors(allFactorHolder, valueWriter);
        var validLfFactors = validFactorHolder.getAllFactors();

        // create AC engine
        AcLoadFlowParameters acParameters = OpenLoadFlowParameters.createAcParameters(network, lfParameters, lfParametersExt, matrixFactory, connectivityFactory, reporter, false, true);

        try (AcLoadFlowContext context = new AcLoadFlowContext(lfNetwork, acParameters)) {

            new AcloadFlowEngine(context)
                    .run(reporter);

            // index factors by variable group to compute a minimal number of states
            List<SensitivityFactorGroup<AcVariableType, AcEquationType>> factorGroups = createFactorGroups(validLfFactors.stream()
                    .filter(factor -> factor.getStatus() == LfSensitivityFactor.Status.VALID).collect(Collectors.toList()));

            boolean hasMultiVariables = factorGroups.stream().anyMatch(MultiVariablesFactorGroup.class::isInstance);

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
            if (hasTransformerBusTargetVoltage) {
                // switch on regulating transformers
                for (LfBranch branch : lfNetwork.getBranches()) {
                    branch.getVoltageControl().ifPresent(vc -> branch.setVoltageControlEnabled(true));
                }
            }

            // we make the assumption that we ran a loadflow before, and thus this jacobian is the right one

            // otherwise, defining the rhs matrix will result in integer overflow
            if (factorGroups.size() >= Integer.MAX_VALUE / (context.getEquationSystem().getSortedEquationsToSolve().size() * Double.BYTES)) {
                throw new PowsyblException("Too many factors!");
            }

            // initialize right hand side from valid factors
            DenseMatrix factorsStates = initFactorsRhs(context.getEquationSystem(), factorGroups, slackParticipationByBus); // this is the rhs for the moment

            // solve system
            context.getJacobianMatrix().solveTransposed(factorsStates);

            // calculate sensitivity values
            setFunctionReferences(validLfFactors);
            calculateSensitivityValues(validFactorHolder.getFactorsForBaseNetwork(), factorGroups, factorsStates, -1, valueWriter);

            NetworkState networkState = NetworkState.save(lfNetwork);

            contingencies.stream().flatMap(contingency -> contingency.toLfContingency(lfNetwork, false).stream()).forEach(lfContingency -> {

                List<LfSensitivityFactor<AcVariableType, AcEquationType>> contingencyFactors = validFactorHolder.getFactorsForContingency(lfContingency.getId());
                contingencyFactors.forEach(lfFactor -> {
                    lfFactor.setSensitivityValuePredefinedResult(null);
                    lfFactor.setFunctionPredefinedResult(null);
                });

                lfContingency.apply(lfParameters.getBalanceType());

                Map<LfBus, Double> postContingencySlackParticipationByBus;
                if (lfContingency.getDisabledBuses().isEmpty()) {
                    // contingency not breaking connectivity
                    LOGGER.info("Contingency {} without loss of connectivity", lfContingency.getId());
                    postContingencySlackParticipationByBus = slackParticipationByBus;
                    contingencyFactors.stream()
                            .filter(lfFactor -> lfFactor.getFunctionElement() instanceof LfBranch)
                            .filter(lfFactor ->  lfContingency.getDisabledBranches().contains(lfFactor.getFunctionElement()))
                            .forEach(lfFactor ->  {
                                lfFactor.setSensitivityValuePredefinedResult(0d);
                                lfFactor.setFunctionPredefinedResult(Double.NaN);
                            });
                    contingencyFactors.stream()
                            .filter(lfFactor -> lfFactor.getVariableType().equals(SensitivityVariableType.TRANSFORMER_PHASE))
                            .filter(lfFactor ->  lfContingency.getDisabledBranches().contains(lfNetwork.getBranchById(lfFactor.getVariableId())))
                            .forEach(lfFactor -> lfFactor.setSensitivityValuePredefinedResult(0d));
                } else {
                    // contingency breaking connectivity
                    LOGGER.info("Contingency {} with loss of connectivity", lfContingency.getId());
                    // we check if factors are still in the main component
                    Set<LfBus> slackConnectedComponent = lfNetwork.getBuses().stream().filter(Predicate.not(lfContingency.getDisabledBuses()::contains)).collect(Collectors.toSet());
                    setPredefinedResults(contingencyFactors, slackConnectedComponent);

                    // we recompute GLSK weights if needed
                    rescaleGlsk(factorGroups, lfContingency.getDisabledBuses());

                    // compute the participation for each injection factor (+1 on the injection and then -participation factor on all
                    // buses that contain elements participating to slack distribution)
                    if (lfParameters.isDistributedSlack()) {
                        List<ParticipatingElement> participatingElementsForThisConnectivity = getParticipatingElements(
                                slackConnectedComponent, lfParameters.getBalanceType(), lfParametersExt); // will also be used to recompute the load flow
                        postContingencySlackParticipationByBus = participatingElementsForThisConnectivity.stream().collect(Collectors.toMap(
                            ParticipatingElement::getLfBus,
                            element -> -element.getFactor(),
                            Double::sum
                        ));
                    } else {
                        postContingencySlackParticipationByBus = Collections.singletonMap(lfNetwork.getSlackBus(), -1d);
                    }
                }
                calculatePostContingencySensitivityValues(contingencyFactors, lfContingency, lfNetwork, context, factorGroups, postContingencySlackParticipationByBus,
                        lfParameters, lfParametersExt, lfContingency.getIndex(), valueWriter, reporter, hasTransformerBusTargetVoltage, hasMultiVariables);

                networkState.restore();
            });
        }
    }
}
