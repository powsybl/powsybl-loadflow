/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.sensi;

import com.google.common.base.Stopwatch;
import com.powsybl.commons.PowsyblException;
import com.powsybl.commons.reporter.Reporter;
import com.powsybl.contingency.BranchContingency;
import com.powsybl.contingency.ContingencyElement;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.Switch;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.math.matrix.LUDecomposition;
import com.powsybl.math.matrix.Matrix;
import com.powsybl.math.matrix.MatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.dc.DcLoadFlowEngine;
import com.powsybl.openloadflow.dc.DcLoadFlowParameters;
import com.powsybl.openloadflow.dc.equations.*;
import com.powsybl.openloadflow.equations.Equation;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.EquationTerm;
import com.powsybl.openloadflow.equations.JacobianMatrix;
import com.powsybl.openloadflow.graph.GraphConnectivity;
import com.powsybl.openloadflow.graph.GraphConnectivityFactory;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.network.impl.LfNetworkList;
import com.powsybl.openloadflow.network.impl.Networks;
import com.powsybl.openloadflow.network.impl.PropagatedContingency;
import com.powsybl.openloadflow.network.util.ParticipatingElement;
import com.powsybl.openloadflow.network.util.PreviousValueVoltageInitializer;
import com.powsybl.openloadflow.network.util.UniformValueVoltageInitializer;
import com.powsybl.openloadflow.network.util.VoltageInitializer;
import com.powsybl.sensitivity.*;
import org.apache.commons.lang3.tuple.Pair;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.ObjDoubleConsumer;
import java.util.stream.Collectors;

import static com.powsybl.openloadflow.network.util.ParticipatingElement.normalizeParticipationFactors;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 * @author Gaël Macherel <gael.macherel@artelys.com>
 */
public class DcSensitivityAnalysis extends AbstractSensitivityAnalysis<DcVariableType, DcEquationType> {

    static final double CONNECTIVITY_LOSS_THRESHOLD = 10e-7;

    static class ComputedContingencyElement {

        private int contingencyIndex = -1; // index of the element in the rhs for +1-1
        private int localIndex = -1; // local index of the element : index of the element in the matrix used in the setAlphas method
        private double alphaForSensitivityValue = Double.NaN;
        private double alphaForFunctionReference = Double.NaN;
        private final ContingencyElement element;
        private final LfBranch lfBranch;
        private final ClosedBranchSide1DcFlowEquationTerm branchEquation;

        public ComputedContingencyElement(final ContingencyElement element, LfNetwork lfNetwork, EquationSystem<DcVariableType, DcEquationType> equationSystem) {
            this.element = element;
            lfBranch = lfNetwork.getBranchById(element.getId());
            branchEquation = equationSystem.getEquationTerm(ElementType.BRANCH, lfBranch.getNum(), ClosedBranchSide1DcFlowEquationTerm.class);
        }

        public int getContingencyIndex() {
            return contingencyIndex;
        }

        public void setContingencyIndex(final int index) {
            this.contingencyIndex = index;
        }

        public int getLocalIndex() {
            return localIndex;
        }

        public void setLocalIndex(final int index) {
            this.localIndex = index;
        }

        public double getAlphaForSensitivityValue() {
            return alphaForSensitivityValue;
        }

        public void setAlphaForSensitivityValue(final double alpha) {
            this.alphaForSensitivityValue = alpha;
        }

        public double getAlphaForFunctionReference() {
            return alphaForFunctionReference;
        }

        public void setAlphaForFunctionReference(final double alpha) {
            this.alphaForFunctionReference = alpha;
        }

        public ContingencyElement getElement() {
            return element;
        }

        public LfBranch getLfBranch() {
            return lfBranch;
        }

        public ClosedBranchSide1DcFlowEquationTerm getLfBranchEquation() {
            return branchEquation;
        }

        public static void setContingencyIndexes(Collection<ComputedContingencyElement> elements) {
            int index = 0;
            for (ComputedContingencyElement element : elements) {
                element.setContingencyIndex(index++);
            }
        }

        public static void setLocalIndexes(Collection<ComputedContingencyElement> elements) {
            int index = 0;
            for (ComputedContingencyElement element : elements) {
                element.setLocalIndex(index++);
            }
        }

    }

    static class PhaseTapChangerContingenciesIndexing {

        private final List<PropagatedContingency> contingenciesWithoutTransformers = new ArrayList<>();
        private final Map<Set<LfBranch>, Collection<PropagatedContingency>> contingenciesIndexedByPhaseTapChangers = new LinkedHashMap<>();

        public PhaseTapChangerContingenciesIndexing(Collection<PropagatedContingency> contingencies, Map<String,
                ComputedContingencyElement> contingencyElementByBranch, Collection<String> elementIdsToSkip) {
            for (PropagatedContingency contingency : contingencies) {
                Set<LfBranch> lostTransformers = contingency.getBranchIdsToOpen().stream()
                        .filter(element -> !elementIdsToSkip.contains(element))
                        .map(contingencyElementByBranch::get)
                        .map(ComputedContingencyElement::getLfBranch)
                        .filter(LfBranch::hasPhaseControlCapability)
                        .collect(Collectors.toSet());
                if (lostTransformers.isEmpty()) {
                    contingenciesWithoutTransformers.add(contingency);
                } else {
                    contingenciesIndexedByPhaseTapChangers.computeIfAbsent(lostTransformers, key -> new ArrayList<>()).add(contingency);
                }
            }
        }

        public Collection<PropagatedContingency> getContingenciesWithoutPhaseTapChangerLoss() {
            return contingenciesWithoutTransformers;
        }

        public Map<Set<LfBranch>, Collection<PropagatedContingency>> getContingenciesIndexedByPhaseTapChangers() {
            return contingenciesIndexedByPhaseTapChangers;
        }
    }

    static class ConnectivityAnalysisResult {

        private final Collection<PropagatedContingency> contingencies = new HashSet<>();

        private final Set<String> elementsToReconnect;

        private final Set<LfBus> disabledBuses;

        private final Set<LfBus> slackConnectedComponent;

        private final Set<LfBranch> partialDisabledBranches; // branches disabled because of connectivity loss.

        protected ConnectivityAnalysisResult(Set<String> elementsToReconnect, Collection<LfSensitivityFactor<DcVariableType, DcEquationType>> factors,
                                             GraphConnectivity<LfBus, LfBranch> connectivity, LfNetwork lfNetwork) {
            this.elementsToReconnect = elementsToReconnect;
            slackConnectedComponent = connectivity.getConnectedComponent(lfNetwork.getSlackBus());
            disabledBuses = connectivity.getVerticesRemovedFromMainComponent();
            partialDisabledBranches = connectivity.getEdgesRemovedFromMainComponent();
        }

        public Collection<PropagatedContingency> getContingencies() {
            return contingencies;
        }

        public Set<String> getElementsToReconnect() {
            return elementsToReconnect;
        }

        public Set<LfBus> getDisabledBuses() {
            return disabledBuses;
        }

        public Set<LfBus> getSlackConnectedComponent() {
            return slackConnectedComponent;
        }

        public Set<LfBranch> getPartialDisabledBranches() {
            return partialDisabledBranches;
        }
    }

    public DcSensitivityAnalysis(MatrixFactory matrixFactory, GraphConnectivityFactory<LfBus, LfBranch> connectivityFactory) {
        super(matrixFactory, connectivityFactory);
    }

    private JacobianMatrix<DcVariableType, DcEquationType> createJacobianMatrix(LfNetwork network, EquationSystem<DcVariableType, DcEquationType> equationSystem, VoltageInitializer voltageInitializer) {
        DcLoadFlowEngine.initStateVector(network, equationSystem, voltageInitializer);
        return new JacobianMatrix<>(equationSystem, matrixFactory);
    }

    private static DcLoadFlowParameters createDcLoadFlowParameters(LfNetworkParameters networkParameters, MatrixFactory matrixFactory,
                                                                   LoadFlowParameters lfParameters) {
        var equationSystemCreationParameters = new DcEquationSystemCreationParameters(true,
                                                                                      true,
                                                                                      lfParameters.isDcUseTransformerRatio());

        return new DcLoadFlowParameters(networkParameters,
                                        equationSystemCreationParameters,
                                        matrixFactory,
                                        lfParameters.isDistributedSlack(),
                                        lfParameters.getBalanceType(),
                                        true);
    }

    /**
     * Calculate the active power flows for pre-contingency or a post-contingency state and set the factor function reference.
     * The interesting disabled branches are only phase shifters.
     */
    protected DenseMatrix calculateActivePowerFlows(LfNetwork network, DcLoadFlowParameters parameters,
                                                    EquationSystem<DcVariableType, DcEquationType> equationSystem, JacobianMatrix<DcVariableType, DcEquationType> j,
                                                    List<LfSensitivityFactor<DcVariableType, DcEquationType>> factors, List<ParticipatingElement> participatingElements,
                                                    Collection<LfBus> disabledBuses, Collection<LfBranch> disabledBranches,
                                                    Reporter reporter) {

        List<BusState> busStates = Collections.emptyList();
        if (parameters.isDistributedSlack()) {
            busStates = ElementState.save(participatingElements.stream()
                .map(ParticipatingElement::getLfBus)
                .collect(Collectors.toSet()), BusState::save);
        }
        // the A1 variables will be set to 0 for disabledBranches, so we need to restore them at the end
        List<BranchState> branchStates = ElementState.save(disabledBranches, BranchState::save);

        double[] dx = DcLoadFlowEngine.run(network, parameters, equationSystem, j, disabledBuses, disabledBranches, reporter).getRight();

        for (LfSensitivityFactor<DcVariableType, DcEquationType> factor : factors) {
            factor.setFunctionReference(factor.getFunctionEquationTerm().eval());
        }

        if (parameters.isDistributedSlack()) {
            ElementState.restore(busStates);
        }
        ElementState.restore(branchStates);

        return new DenseMatrix(dx.length, 1, dx);
    }

    /**
     * Get the sensitivity value for pre-contingency state and calculate the sensitivity value for a post-contingency state if asked.
     * The sensitivity value is written in the SensitivityResultWriter.
     */
    private void createBranchSensitivityValue(LfSensitivityFactor<DcVariableType, DcEquationType> factor, DenseMatrix contingenciesStates,
                                              Collection<ComputedContingencyElement> contingencyElements,
                                              PropagatedContingency contingency, SensitivityResultWriter resultWriter,
                                              Set<LfBus> disabledBuses, Set<LfBranch> disabledBranches) {
        Pair<Optional<Double>, Optional<Double>> predefinedResults = getPredefinedResults(factor, disabledBuses, disabledBranches, contingency);
        Optional<Double> sensitivityValuePredefinedResult = predefinedResults.getLeft();
        Optional<Double> functionPredefinedResults = predefinedResults.getRight();
        double sensitivityValue = sensitivityValuePredefinedResult.orElseGet(factor::getBaseSensitivityValue);
        double functionValue = functionPredefinedResults.orElseGet(factor::getFunctionReference);
        EquationTerm<DcVariableType, DcEquationType> p1 = factor.getFunctionEquationTerm();

        if (!(functionPredefinedResults.isPresent() && sensitivityValuePredefinedResult.isPresent())) {
            for (ComputedContingencyElement contingencyElement : contingencyElements) {
                double contingencySensitivity = p1.calculateSensi(contingenciesStates, contingencyElement.getContingencyIndex());
                if (functionPredefinedResults.isEmpty()) {
                    functionValue += contingencyElement.getAlphaForFunctionReference() * contingencySensitivity;
                }
                if (sensitivityValuePredefinedResult.isEmpty()) {
                    sensitivityValue += contingencyElement.getAlphaForSensitivityValue() * contingencySensitivity;
                }
            }
        }

        resultWriter.writeSensitivityValue(factor.getIndex(), contingency != null ? contingency.getIndex() : -1, unscaleSensitivity(factor, sensitivityValue), unscaleFunction(factor, functionValue));
    }

    /**
     * Calculate the sensitivity value for pre-contingency state only.
     */
    protected void setBaseCaseSensitivityValues(SensitivityFactorGroupList<DcVariableType, DcEquationType> factorGroups, DenseMatrix factorsState) {
        for (SensitivityFactorGroup<DcVariableType, DcEquationType> factorGroup : factorGroups.getList()) {
            for (LfSensitivityFactor<DcVariableType, DcEquationType> factor : factorGroup.getFactors()) {
                factor.setBaseCaseSensitivityValue(factor.getFunctionEquationTerm().calculateSensi(factorsState, factorGroup.getIndex()));
            }
        }
    }

    /**
     * Compute state for sensitivity factors taking into account slack distribution.
     */
    private DenseMatrix calculateFactorStates(LfNetwork lfNetwork,
                                              EquationSystem<DcVariableType, DcEquationType> equationSystem,
                                              SensitivityFactorGroupList<DcVariableType, DcEquationType> factorGroups,
                                              JacobianMatrix<DcVariableType, DcEquationType> j,
                                              List<ParticipatingElement> participatingElements) {
        Map<LfBus, Double> slackParticipationByBus;
        if (participatingElements.isEmpty()) {
            slackParticipationByBus = Map.of(lfNetwork.getSlackBus(), -1d);
        } else {
            slackParticipationByBus = participatingElements.stream().collect(Collectors.toMap(
                ParticipatingElement::getLfBus,
                element -> -element.getFactor(),
                Double::sum));
        }

        DenseMatrix factorStates = initFactorsRhs(equationSystem, factorGroups, slackParticipationByBus);
        j.solveTransposed(factorStates); // states for the sensitivity factors
        setBaseCaseSensitivityValues(factorGroups, factorStates); // use this state to compute the base sensitivity (without +1-1)
        return factorStates;
    }

    /**
     * Calculate sensitivity values for pre-contingency state or a post-contingency state using the pre-contingency sensitivity
     * value and some flow transfer factors (alphas).
     */
    protected void calculateSensitivityValues(List<LfSensitivityFactor<DcVariableType, DcEquationType>> lfFactors, DenseMatrix factorStates,
                                              DenseMatrix contingenciesStates, DenseMatrix flowStates, Collection<ComputedContingencyElement> contingencyElements,
                                              PropagatedContingency contingency, SensitivityResultWriter resultWriter, Set<LfBus> disabledBuses,
                                              Set<LfBranch> disabledBranches) {
        if (lfFactors.isEmpty()) {
            return;
        }

        setAlphas(contingencyElements, flowStates, contingenciesStates, 0, ComputedContingencyElement::setAlphaForFunctionReference);

        lfFactors.stream().filter(factor -> factor.getStatus() == LfSensitivityFactor.Status.VALID_ONLY_FOR_FUNCTION)
                .forEach(factor -> createBranchSensitivityValue(factor, contingenciesStates, contingencyElements, contingency, resultWriter, disabledBuses, disabledBranches));

        Map<SensitivityFactorGroup<DcVariableType, DcEquationType>, List<LfSensitivityFactor<DcVariableType, DcEquationType>>> factorsByGroup = lfFactors.stream()
                .filter(factor -> factor.getStatus() == LfSensitivityFactor.Status.VALID)
                .collect(Collectors.groupingBy(LfSensitivityFactor::getGroup, LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<SensitivityFactorGroup<DcVariableType, DcEquationType>, List<LfSensitivityFactor<DcVariableType, DcEquationType>>> e : factorsByGroup.entrySet()) {
            SensitivityFactorGroup<DcVariableType, DcEquationType> factorGroup = e.getKey();
            List<LfSensitivityFactor<DcVariableType, DcEquationType>> factorsForThisGroup = e.getValue();
            setAlphas(contingencyElements, factorStates, contingenciesStates, factorGroup.getIndex(), ComputedContingencyElement::setAlphaForSensitivityValue);
            for (LfSensitivityFactor<DcVariableType, DcEquationType> factor : factorsForThisGroup) {
                createBranchSensitivityValue(factor, contingenciesStates, contingencyElements, contingency, resultWriter, disabledBuses, disabledBranches);
            }
        }
    }

    /**
     * Compute the flow transfer factors needed to calculate the post-contingency sensitivity values.
     */
    private static void setAlphas(Collection<ComputedContingencyElement> contingencyElements, DenseMatrix states,
                                  DenseMatrix contingenciesStates, int columnState, ObjDoubleConsumer<ComputedContingencyElement> setValue) {
        if (contingencyElements.size() == 1) {
            ComputedContingencyElement element = contingencyElements.iterator().next();
            LfBranch lfBranch = element.getLfBranch();
            ClosedBranchSide1DcFlowEquationTerm p1 = element.getLfBranchEquation();
            // we solve a*alpha = b
            double a = lfBranch.getPiModel().getX() - (contingenciesStates.get(p1.getPh1Var().getRow(), element.getContingencyIndex())
                    - contingenciesStates.get(p1.getPh2Var().getRow(), element.getContingencyIndex()));
            double b = states.get(p1.getPh1Var().getRow(), columnState) - states.get(p1.getPh2Var().getRow(), columnState);
            setValue.accept(element, b / a);
        } else {
            // FIXME: direct resolution if contingencyElements.size() == 2
            ComputedContingencyElement.setLocalIndexes(contingencyElements);
            DenseMatrix rhs = new DenseMatrix(contingencyElements.size(), 1);
            DenseMatrix matrix = new DenseMatrix(contingencyElements.size(), contingencyElements.size());
            for (ComputedContingencyElement element : contingencyElements) {
                LfBranch lfBranch = element.getLfBranch();
                ClosedBranchSide1DcFlowEquationTerm p1 = element.getLfBranchEquation();
                rhs.set(element.getLocalIndex(), 0, states.get(p1.getPh1Var().getRow(), columnState)
                        - states.get(p1.getPh2Var().getRow(), columnState)
                );
                for (ComputedContingencyElement element2 : contingencyElements) {
                    double value = 0d;
                    if (element.equals(element2)) {
                        value = lfBranch.getPiModel().getX();
                    }
                    value = value - (contingenciesStates.get(p1.getPh1Var().getRow(), element2.getContingencyIndex())
                            - contingenciesStates.get(p1.getPh2Var().getRow(), element2.getContingencyIndex()));
                    matrix.set(element.getLocalIndex(), element2.getLocalIndex(), value);
                }
            }
            try (LUDecomposition lu = matrix.decomposeLU()) {
                lu.solve(rhs); // rhs now contains state matrix
            }
            contingencyElements.forEach(element -> setValue.accept(element, rhs.get(element.getLocalIndex(), 0)));
        }
    }

    /**
     * Fills the right hand side with +1/-1 to model a branch contingency.
     */
    protected static void fillRhsContingency(LfNetwork lfNetwork, EquationSystem<DcVariableType, DcEquationType> equationSystem,
                                             Collection<ComputedContingencyElement> contingencyElements, Matrix rhs) {
        for (ComputedContingencyElement element : contingencyElements) {
            LfBranch lfBranch = lfNetwork.getBranchById(element.getElement().getId());
            if (lfBranch.getBus1() == null || lfBranch.getBus2() == null) {
                continue;
            }
            LfBus bus1 = lfBranch.getBus1();
            LfBus bus2 = lfBranch.getBus2();
            if (bus1.isSlack()) {
                Equation<DcVariableType, DcEquationType> p = equationSystem.getEquation(bus2.getNum(), DcEquationType.BUS_TARGET_P).orElseThrow(IllegalStateException::new);
                rhs.set(p.getColumn(), element.getContingencyIndex(), -1);
            } else if (bus2.isSlack()) {
                Equation<DcVariableType, DcEquationType> p = equationSystem.getEquation(bus1.getNum(), DcEquationType.BUS_TARGET_P).orElseThrow(IllegalStateException::new);
                rhs.set(p.getColumn(), element.getContingencyIndex(), 1);
            } else {
                Equation<DcVariableType, DcEquationType> p1 = equationSystem.getEquation(bus1.getNum(), DcEquationType.BUS_TARGET_P).orElseThrow(IllegalStateException::new);
                Equation<DcVariableType, DcEquationType> p2 = equationSystem.getEquation(bus2.getNum(), DcEquationType.BUS_TARGET_P).orElseThrow(IllegalStateException::new);
                rhs.set(p1.getColumn(), element.getContingencyIndex(), 1);
                rhs.set(p2.getColumn(), element.getContingencyIndex(), -1);
            }
        }
    }

    protected static DenseMatrix initContingencyRhs(LfNetwork lfNetwork, EquationSystem<DcVariableType, DcEquationType> equationSystem, Collection<ComputedContingencyElement> contingencyElements) {
        DenseMatrix rhs = new DenseMatrix(equationSystem.getIndex().getSortedEquationsToSolve().size(), contingencyElements.size());
        fillRhsContingency(lfNetwork, equationSystem, contingencyElements, rhs);
        return rhs;
    }

    private static DenseMatrix calculateContingenciesStates(LfNetwork lfNetwork, EquationSystem<DcVariableType, DcEquationType> equationSystem, Map<String, ComputedContingencyElement> contingencyElementByBranch, JacobianMatrix<DcVariableType, DcEquationType> j) {
        DenseMatrix contingenciesStates = initContingencyRhs(lfNetwork, equationSystem, contingencyElementByBranch.values()); // rhs with +1 -1 on contingency elements
        j.solveTransposed(contingenciesStates);
        return contingenciesStates;
    }

    private static void detectPotentialConnectivityBreak(LfNetwork lfNetwork, DenseMatrix states, List<PropagatedContingency> contingencies,
                                                         Map<String, ComputedContingencyElement> contingencyElementByBranch,
                                                         EquationSystem<DcVariableType, DcEquationType> equationSystem,
                                                         Collection<PropagatedContingency> nonLosingConnectivityContingencies,
                                                         Map<Set<ComputedContingencyElement>, List<PropagatedContingency>> contingenciesByGroupOfElementsBreakingConnectivity) {
        for (PropagatedContingency contingency : contingencies) {
            List<ComputedContingencyElement> contingencyElements = contingency.getBranchIdsToOpen().stream().map(contingencyElementByBranch::get).collect(Collectors.toList());
            Set<ComputedContingencyElement> groupOfElementsBreakingConnectivity = getGroupOfElementsBreakingConnectivity(lfNetwork, states, contingencyElements, equationSystem);
            if (groupOfElementsBreakingConnectivity.isEmpty()) { // connectivity not broken
                nonLosingConnectivityContingencies.add(contingency);
            } else {
                contingenciesByGroupOfElementsBreakingConnectivity.computeIfAbsent(groupOfElementsBreakingConnectivity, key -> new LinkedList<>()).add(contingency);
            }
        }
    }

    private static Set<ComputedContingencyElement> getGroupOfElementsBreakingConnectivity(LfNetwork lfNetwork, DenseMatrix contingenciesStates,
                                                                                          Collection<ComputedContingencyElement> contingencyElements,
                                                                                          EquationSystem<DcVariableType, DcEquationType> equationSystem) {
        // use a sensitivity-criterion to detect the loss of connectivity after a contingency
        // we consider a +1 -1 on a line, and we observe the sensitivity of these injections on the other contingency elements
        // if the sum of the sensitivities (in absolute value) is 1, it means that all the flow is going through the lines with a non-zero sensitivity
        // thus, losing these lines will lose the connectivity
        Set<ComputedContingencyElement> groupOfElementsBreakingConnectivity = new LinkedHashSet<>();
        for (ComputedContingencyElement element : contingencyElements) {
            Set<ComputedContingencyElement> responsibleElements = new LinkedHashSet<>();
            double sum = 0d;
            for (ComputedContingencyElement element2 : contingencyElements) {
                LfBranch branch = lfNetwork.getBranchById(element2.getElement().getId());
                ClosedBranchSide1DcFlowEquationTerm p = equationSystem.getEquationTerm(ElementType.BRANCH, branch.getNum(), ClosedBranchSide1DcFlowEquationTerm.class);
                double value = Math.abs(p.calculateSensi(contingenciesStates, element.getContingencyIndex()));
                if (value > CONNECTIVITY_LOSS_THRESHOLD) {
                    responsibleElements.add(element2);
                }
                sum += value;
            }
            if (sum > 1d - CONNECTIVITY_LOSS_THRESHOLD) {
                // all lines that have a non-0 sensitivity associated to "element" breaks the connectivity
                groupOfElementsBreakingConnectivity.addAll(responsibleElements);
            }
        }
        return groupOfElementsBreakingConnectivity;
    }

    private static List<ConnectivityAnalysisResult> computeConnectivityData(LfNetwork lfNetwork, SensitivityFactorHolder<DcVariableType, DcEquationType> factorHolder,
                                                                            Map<Set<ComputedContingencyElement>, List<PropagatedContingency>> contingenciesByGroupOfElementsBreakingConnectivity,
                                                                            List<PropagatedContingency> nonLosingConnectivityContingencies, SensitivityResultWriter resultWriter) {
        if (contingenciesByGroupOfElementsBreakingConnectivity.isEmpty()) {
            return Collections.emptyList();
        }

        Map<Set<ComputedContingencyElement>, ConnectivityAnalysisResult> connectivityAnalysisResults = new LinkedHashMap<>();

        GraphConnectivity<LfBus, LfBranch> connectivity = lfNetwork.getConnectivity();
        for (Map.Entry<Set<ComputedContingencyElement>, List<PropagatedContingency>> e : contingenciesByGroupOfElementsBreakingConnectivity.entrySet()) {
            Set<ComputedContingencyElement> breakingConnectivityCandidates = e.getKey();
            List<PropagatedContingency> contingencyList = e.getValue();
            connectivity.startTemporaryChanges();
            breakingConnectivityCandidates.stream()
                    .map(ComputedContingencyElement::getElement)
                    .map(ContingencyElement::getId)
                    .distinct()
                    .map(lfNetwork::getBranchById)
                    .filter(b -> b.getBus1() != null && b.getBus2() != null)
                    .forEach(connectivity::removeEdge);

            // filter the branches that really impacts connectivity
            Set<ComputedContingencyElement> breakingConnectivityElements = breakingConnectivityCandidates.stream()
                    .filter(element -> isBreakingConnectivity(connectivity, element))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (breakingConnectivityElements.isEmpty()) {
                // we did not break any connectivity
                nonLosingConnectivityContingencies.addAll(contingencyList);
            } else {
                // only compute for factors that have to be computed for this contingency lost
                List<String> contingenciesIds = contingencyList.stream().map(contingency -> contingency.getContingency().getId()).collect(Collectors.toList());

                List<LfSensitivityFactor<DcVariableType, DcEquationType>> lfFactors = factorHolder.getFactorsForContingencies(contingenciesIds);
                if (!lfFactors.isEmpty()) {
                    ConnectivityAnalysisResult connectivityAnalysisResult = connectivityAnalysisResults.computeIfAbsent(breakingConnectivityElements, k -> {
                        Set<String> elementsToReconnect = computeElementsToReconnect(connectivity, breakingConnectivityElements);
                        return new ConnectivityAnalysisResult(elementsToReconnect, lfFactors, connectivity, lfNetwork);
                    });
                    connectivityAnalysisResult.getContingencies().addAll(contingencyList);
                } else {
                    // write contingency status
                    for (PropagatedContingency propagatedContingency : contingencyList) {
                        resultWriter.writeContingencyStatus(propagatedContingency.getIndex(), SensitivityAnalysisResult.Status.SUCCESS);
                    }
                }
            }
            connectivity.undoTemporaryChanges();
        }
        return new ArrayList<>(connectivityAnalysisResults.values());
    }

    private static boolean isBreakingConnectivity(GraphConnectivity<LfBus, LfBranch> connectivity, ComputedContingencyElement element) {
        LfBranch lfBranch = element.getLfBranch();
        return connectivity.getComponentNumber(lfBranch.getBus1()) != connectivity.getComponentNumber(lfBranch.getBus2());
    }

    /**
     * Given the elements breaking the connectivity, extract the minimum number of elements which reconnect all connected components together
     */
    private static Set<String> computeElementsToReconnect(GraphConnectivity<LfBus, LfBranch> connectivity, Set<DcSensitivityAnalysis.ComputedContingencyElement> breakingConnectivityElements) {
        Set<String> elementsToReconnect = new LinkedHashSet<>();

        // We suppose we're reconnecting one by one each element breaking connectivity.
        // At each step we look if the reconnection was needed on the connectivity level by maintaining a list of grouped connected components.
        List<Set<Integer>> reconnectedCc = new ArrayList<>();
        for (DcSensitivityAnalysis.ComputedContingencyElement element : breakingConnectivityElements) {
            int cc1 = connectivity.getComponentNumber(element.getLfBranch().getBus1());
            int cc2 = connectivity.getComponentNumber(element.getLfBranch().getBus2());

            Set<Integer> recCc1 = reconnectedCc.stream().filter(s -> s.contains(cc1)).findFirst().orElseGet(() -> new HashSet<>(List.of(cc1)));
            Set<Integer> recCc2 = reconnectedCc.stream().filter(s -> s.contains(cc2)).findFirst().orElseGet(() -> Set.of(cc2));
            if (recCc1 != recCc2) {
                // cc1 and cc2 are still separated:
                // - mark the element as needed to reconnect all connected components together
                // - update the list of grouped connected components
                elementsToReconnect.add(element.getElement().getId());
                reconnectedCc.remove(recCc2);
                if (recCc1.size() == 1) {
                    // adding the new set (the list of grouped connected components is not initialized with the singleton sets)
                    reconnectedCc.add(recCc1);
                }
                recCc1.addAll(recCc2);
            }
        }

        if (reconnectedCc.size() != 1 || reconnectedCc.get(0).size() != connectivity.getNbConnectedComponents()) {
            LOGGER.error("Elements to reconnect computed do not reconnect all connected components together");
        }

        return elementsToReconnect;
    }

    /**
     * Calculate sensitivity values for a post-contingency state.
     * When a contingency involves the loss of a load or a generator, the slack distribution could changed
     * or the sensitivity factors in case of GLSK.
     */
    public void calculateContingencySensitivityValues(PropagatedContingency contingency, SensitivityFactorGroupList<DcVariableType, DcEquationType> factorGroups, DenseMatrix factorStates, DenseMatrix contingenciesStates,
                                                      DenseMatrix flowStates, Collection<ComputedContingencyElement> contingencyElements, SensitivityResultWriter resultWriter,
                                                      LfNetwork lfNetwork, DcLoadFlowParameters lfParameters, OpenLoadFlowParameters lfParametersExt, JacobianMatrix<DcVariableType, DcEquationType> j, EquationSystem<DcVariableType, DcEquationType> equationSystem,
                                                      SensitivityFactorHolder<DcVariableType, DcEquationType> factorHolder, List<ParticipatingElement> participatingElements,
                                                      Set<LfBus> disabledBuses, Set<LfBranch> disabledBranches, Reporter reporter) {
        List<LfSensitivityFactor<DcVariableType, DcEquationType>> factors = factorHolder.getFactorsForContingency(contingency.getContingency().getId());
        if (contingency.getGeneratorIdsToLose().isEmpty() && contingency.getLoadIdsToShift().isEmpty()) {
            calculateSensitivityValues(factors, factorStates, contingenciesStates, flowStates, contingencyElements,
                    contingency, resultWriter, disabledBuses, disabledBranches);
            // write contingency status
            resultWriter.writeContingencyStatus(contingency.getIndex(), SensitivityAnalysisResult.Status.SUCCESS);
        } else {
            // if we have a contingency including the loss of a DC line or a generator or a load
            // save base state for later restoration after each contingency
            NetworkState networkState = NetworkState.save(lfNetwork);
            LfContingency lfContingency = contingency.toLfContingency(lfNetwork).orElse(null);
            DenseMatrix newFactorStates = factorStates;
            List<ParticipatingElement> newParticipatingElements = participatingElements;
            boolean participatingElementsChanged = false;
            boolean rhsChanged = false;
            if (lfContingency != null) {
                lfContingency.apply(lfParameters.getBalanceType());
                participatingElementsChanged = (isDistributedSlackOnGenerators(lfParameters) && !contingency.getGeneratorIdsToLose().isEmpty())
                        || (isDistributedSlackOnLoads(lfParameters) && !contingency.getLoadIdsToShift().isEmpty());
                if (factorGroups.hasMultiVariables()) {
                    Set<LfBus> impactedBuses = lfContingency.getLoadAndGeneratorBuses();
                    rhsChanged = rescaleGlsk(factorGroups, impactedBuses);
                }
                if (participatingElementsChanged) {
                    if (isDistributedSlackOnGenerators(lfParameters)) {
                        // deep copy of participatingElements, removing the participating LfGeneratorImpl whose targetP has been set to 0
                        Set<LfGenerator> participatingGeneratorsToRemove = lfContingency.getLostGenerators();
                        newParticipatingElements = participatingElements.stream()
                                .filter(participatingElement -> !participatingGeneratorsToRemove.contains(participatingElement.getElement()))
                                .map(participatingElement -> new ParticipatingElement(participatingElement.getElement(), participatingElement.getFactor()))
                                .collect(Collectors.toList());
                        normalizeParticipationFactors(newParticipatingElements, "LfGenerators");
                    } else { // slack distribution on loads
                        newParticipatingElements = getParticipatingElements(lfNetwork.getBuses(), lfParameters.getBalanceType(), lfParametersExt);
                    }
                }
                if (participatingElementsChanged || rhsChanged) {
                    newFactorStates = calculateFactorStates(lfNetwork, equationSystem, factorGroups, j, newParticipatingElements);
                }
                // write contingency status
                resultWriter.writeContingencyStatus(contingency.getIndex(), SensitivityAnalysisResult.Status.SUCCESS);
            } else {
                // write contingency status
                resultWriter.writeContingencyStatus(contingency.getIndex(), SensitivityAnalysisResult.Status.NO_IMPACT);
            }

            DenseMatrix newFlowStates = calculateActivePowerFlows(lfNetwork, lfParameters, equationSystem, j, factors,
                    newParticipatingElements, disabledBuses, disabledBranches, reporter);

            calculateSensitivityValues(factors, newFactorStates, contingenciesStates, newFlowStates, contingencyElements,
                    contingency, resultWriter, disabledBuses, disabledBranches);

            networkState.restore();
            if (participatingElementsChanged || rhsChanged) {
                setBaseCaseSensitivityValues(factorGroups, factorStates);
            }
        }
    }

    private void calculateSensitivityValuesForContingencyList(LfNetwork lfNetwork, OpenLoadFlowParameters lfParametersExt, DcLoadFlowParameters dcLoadFlowParameters,
                                                              EquationSystem<DcVariableType, DcEquationType> equationSystem, SensitivityFactorHolder<DcVariableType, DcEquationType> validFactorHolder,
                                                              SensitivityFactorGroupList<DcVariableType, DcEquationType> factorGroups, JacobianMatrix<DcVariableType, DcEquationType> j, DenseMatrix factorState, DenseMatrix contingenciesStates, DenseMatrix flowStates,
                                                              Collection<PropagatedContingency> contingencies, Map<String, ComputedContingencyElement> contingencyElementByBranch,
                                                              Set<LfBus> disabledBuses, List<ParticipatingElement> participatingElements, Set<String> elementsToReconnect,
                                                              SensitivityResultWriter resultWriter, Reporter reporter, Set<LfBranch> partialDisabledBranches) {
        DenseMatrix modifiedFlowStates = flowStates;

        PhaseTapChangerContingenciesIndexing phaseTapChangerContingenciesIndexing = new PhaseTapChangerContingenciesIndexing(contingencies, contingencyElementByBranch, elementsToReconnect);

        // compute contingencies without loss of phase tap changer
        // first we compute the ones without loss of phase tap changers (because we reuse the load flows from the pre contingency network for all of them)
        for (PropagatedContingency contingency : phaseTapChangerContingenciesIndexing.getContingenciesWithoutPhaseTapChangerLoss()) {
            Collection<ComputedContingencyElement> contingencyElements = contingency.getBranchIdsToOpen().stream()
                    .filter(element -> !elementsToReconnect.contains(element))
                    .map(contingencyElementByBranch::get)
                    .collect(Collectors.toList());

            Set<LfBranch> disabledBranches = contingency.getBranchIdsToOpen().stream().map(lfNetwork::getBranchById).collect(Collectors.toSet());
            disabledBranches.addAll(partialDisabledBranches);

            calculateContingencySensitivityValues(contingency, factorGroups, factorState, contingenciesStates, modifiedFlowStates, contingencyElements, resultWriter,
                    lfNetwork, dcLoadFlowParameters, lfParametersExt, j, equationSystem, validFactorHolder, participatingElements,
                    disabledBuses, disabledBranches, reporter);
        }

        // then we compute the ones involving the loss of a phase tap changer (because we need to recompute the load flows)
        for (Map.Entry<Set<LfBranch>, Collection<PropagatedContingency>> e : phaseTapChangerContingenciesIndexing.getContingenciesIndexedByPhaseTapChangers().entrySet()) {
            Set<LfBranch> disabledPhaseTapChangers = e.getKey();
            Collection<PropagatedContingency> propagatedContingencies = e.getValue();
            List<String> contingenciesIds = propagatedContingencies.stream()
                    .map(c -> c.getContingency().getId())
                    .collect(Collectors.toList());
            List<LfSensitivityFactor<DcVariableType, DcEquationType>> lfFactors = validFactorHolder.getFactorsForContingencies(contingenciesIds);
            if (!lfFactors.isEmpty()) {
                modifiedFlowStates = calculateActivePowerFlows(lfNetwork, dcLoadFlowParameters, equationSystem, j, lfFactors, participatingElements, disabledBuses, disabledPhaseTapChangers, reporter);
            }
            for (PropagatedContingency contingency : propagatedContingencies) {
                Collection<ComputedContingencyElement> contingencyElements = contingency.getBranchIdsToOpen().stream()
                        .filter(element -> !elementsToReconnect.contains(element))
                        .map(contingencyElementByBranch::get)
                        .collect(Collectors.toList());

                Set<LfBranch> disabledBranches = contingency.getBranchIdsToOpen().stream().map(lfNetwork::getBranchById).collect(Collectors.toSet());
                disabledBranches.addAll(partialDisabledBranches);

                calculateContingencySensitivityValues(contingency, factorGroups, factorState, contingenciesStates, modifiedFlowStates, contingencyElements, resultWriter,
                        lfNetwork, dcLoadFlowParameters, lfParametersExt, j, equationSystem, validFactorHolder, participatingElements,
                        disabledBuses, disabledBranches, reporter);
            }
        }
    }

    private void processContingenciesBreakingConnectivity(ConnectivityAnalysisResult connectivityAnalysisResult, LfNetwork lfNetwork,
                                                          LoadFlowParameters lfParameters, OpenLoadFlowParameters lfParametersExt, DcLoadFlowParameters dcLoadFlowParameters,
                                                          EquationSystem<DcVariableType, DcEquationType> equationSystem,
                                                          SensitivityFactorHolder<DcVariableType, DcEquationType> validFactorHolder,
                                                          SensitivityFactorGroupList<DcVariableType, DcEquationType> factorGroups,
                                                          List<ParticipatingElement> participatingElements,
                                                          Map<String, ComputedContingencyElement> contingencyElementByBranch,
                                                          JacobianMatrix<DcVariableType, DcEquationType> j,
                                                          DenseMatrix flowStates, DenseMatrix factorsStates, DenseMatrix contingenciesStates,
                                                          SensitivityResultWriter resultWriter,
                                                          Reporter reporter) {
        DenseMatrix modifiedFlowStates = flowStates;

        List<String> contingenciesIds = connectivityAnalysisResult.getContingencies().stream().map(c -> c.getContingency().getId()).collect(Collectors.toList());
        List<LfSensitivityFactor<DcVariableType, DcEquationType>> lfFactorsForContingencies = validFactorHolder.getFactorsForContingencies(contingenciesIds);

        Set<LfBus> disabledBuses = connectivityAnalysisResult.getDisabledBuses();
        Set<LfBranch> partialDisabledBranches = connectivityAnalysisResult.getPartialDisabledBranches();

        // null and unused if slack bus is not distributed
        List<ParticipatingElement> participatingElementsForThisConnectivity = participatingElements;
        boolean rhsChanged = false; // true if the disabled buses change the slack distribution, or the GLSK
        DenseMatrix factorStateForThisConnectivity = factorsStates;
        if (lfParameters.isDistributedSlack()) {
            rhsChanged = participatingElements.stream().anyMatch(element -> disabledBuses.contains(element.getLfBus()));
        }
        if (factorGroups.hasMultiVariables()) {
            // some elements of the GLSK may not be in the connected component anymore, we recompute the injections
            rhsChanged |= rescaleGlsk(factorGroups, disabledBuses);
        }

        // we need to recompute the factor states because the connectivity changed
        if (rhsChanged) {
            participatingElementsForThisConnectivity = lfParameters.isDistributedSlack()
                    ? getParticipatingElements(connectivityAnalysisResult.getSlackConnectedComponent(), lfParameters.getBalanceType(), lfParametersExt) // will also be used to recompute the loadflow
                    : Collections.emptyList();

            factorStateForThisConnectivity = calculateFactorStates(lfNetwork, equationSystem, factorGroups, j, participatingElementsForThisConnectivity);
        }

        if (!lfFactorsForContingencies.isEmpty()) {
            modifiedFlowStates = calculateActivePowerFlows(lfNetwork, dcLoadFlowParameters, equationSystem, j, lfFactorsForContingencies,
                    participatingElementsForThisConnectivity, disabledBuses, Collections.emptyList(), reporter);
        }

        calculateSensitivityValuesForContingencyList(lfNetwork, lfParametersExt, dcLoadFlowParameters, equationSystem,
                validFactorHolder, factorGroups, j, factorStateForThisConnectivity, contingenciesStates, modifiedFlowStates,
                connectivityAnalysisResult.getContingencies(), contingencyElementByBranch, disabledBuses, participatingElementsForThisConnectivity,
                connectivityAnalysisResult.getElementsToReconnect(), resultWriter, reporter, partialDisabledBranches);

        if (rhsChanged) {
            setBaseCaseSensitivityValues(factorGroups, factorsStates); // we modified the rhs, we need to restore previous state
        }
    }

    private static Map<String, ComputedContingencyElement> createContingencyElementsIndexByBranchId(List<PropagatedContingency> contingencies,
                                                                                                    LfNetwork lfNetwork, EquationSystem<DcVariableType, DcEquationType> equationSystem) {
        Map<String, ComputedContingencyElement> contingencyElementByBranch =
                contingencies.stream()
                        .flatMap(contingency -> contingency.getBranchIdsToOpen().stream())
                        .map(branch -> new ComputedContingencyElement(new BranchContingency(branch), lfNetwork, equationSystem))
                        .filter(element -> element.getLfBranchEquation() != null)
                        .collect(Collectors.toMap(
                            computedContingencyElement -> computedContingencyElement.getElement().getId(),
                            computedContingencyElement -> computedContingencyElement,
                            (existing, replacement) -> existing,
                            LinkedHashMap::new
                        ));
        ComputedContingencyElement.setContingencyIndexes(contingencyElementByBranch.values());
        return contingencyElementByBranch;
    }

    public void analyse(Network network, List<PropagatedContingency> contingencies, List<SensitivityVariableSet> variableSets,
                        LoadFlowParameters lfParameters, OpenLoadFlowParameters lfParametersExt, SensitivityFactorReader factorReader,
                        SensitivityResultWriter resultWriter, Reporter reporter, Set<Switch> allSwitchesToOpen) {
        Objects.requireNonNull(network);
        Objects.requireNonNull(contingencies);
        Objects.requireNonNull(variableSets);
        Objects.requireNonNull(lfParameters);
        Objects.requireNonNull(lfParametersExt);
        Objects.requireNonNull(factorReader);
        Objects.requireNonNull(resultWriter);

        Stopwatch stopwatch = Stopwatch.createStarted();

        boolean breakers = !allSwitchesToOpen.isEmpty();

        // create the network (we only manage main connected component)
        SlackBusSelector slackBusSelector = SlackBusSelector.fromMode(lfParametersExt.getSlackBusSelectionMode(), lfParametersExt.getSlackBusesIds(), lfParametersExt.getPlausibleActivePowerLimit());
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
                .setReactivePowerRemoteControl(false)
                .setDc(true)
                .setShuntVoltageControl(false)
                .setReactiveLimits(false)
                .setHvdcAcEmulation(false)
                .setMinPlausibleTargetVoltage(lfParametersExt.getMinPlausibleTargetVoltage())
                .setMaxPlausibleTargetVoltage(lfParametersExt.getMaxPlausibleTargetVoltage());
        // create networks including all necessary switches
        try (LfNetworkList lfNetworks = Networks.load(network, lfNetworkParameters, allSwitchesToOpen, Collections.emptySet(), reporter)) {
            LfNetwork lfNetwork = lfNetworks.getLargest().orElseThrow(() -> new PowsyblException("Empty network"));
            checkContingencies(lfNetwork, contingencies);
            checkLoadFlowParameters(lfParameters);

            Map<String, SensitivityVariableSet> variableSetsById = variableSets.stream().collect(Collectors.toMap(SensitivityVariableSet::getId, Function.identity()));
            SensitivityFactorHolder<DcVariableType, DcEquationType> allFactorHolder = readAndCheckFactors(network, variableSetsById, factorReader, lfNetwork, breakers);
            List<LfSensitivityFactor<DcVariableType, DcEquationType>> allLfFactors = allFactorHolder.getAllFactors();

            allLfFactors.stream()
                    .filter(lfFactor -> (lfFactor.getFunctionType() != SensitivityFunctionType.BRANCH_ACTIVE_POWER
                            && lfFactor.getFunctionType() != SensitivityFunctionType.BRANCH_ACTIVE_POWER_1
                            && lfFactor.getFunctionType() != SensitivityFunctionType.BRANCH_ACTIVE_POWER_2)
                            || (lfFactor.getVariableType() != SensitivityVariableType.INJECTION_ACTIVE_POWER
                            && lfFactor.getVariableType() != SensitivityVariableType.TRANSFORMER_PHASE
                            && lfFactor.getVariableType() != SensitivityVariableType.HVDC_LINE_ACTIVE_POWER))
                    .findFirst()
                    .ifPresent(ignored -> {
                        throw new PowsyblException("Only variables of type TRANSFORMER_PHASE, INJECTION_ACTIVE_POWER and HVDC_LINE_ACTIVE_POWER, and functions of type BRANCH_ACTIVE_POWER_1 and BRANCH_ACTIVE_POWER_2 are yet supported in DC");
                    });

            LOGGER.info("Running DC sensitivity analysis with {} factors and {} contingencies", allLfFactors.size(), contingencies.size());

            var dcLoadFlowParameters = createDcLoadFlowParameters(lfNetworkParameters, matrixFactory, lfParameters);

            // create DC equation system for sensitivity analysis
            EquationSystem<DcVariableType, DcEquationType> equationSystem = DcEquationSystem.create(lfNetwork, dcLoadFlowParameters.getEquationSystemCreationParameters());

            // next we only work with valid factors
            var validFactorHolder = writeInvalidFactors(allFactorHolder, resultWriter);
            var validLfFactors = validFactorHolder.getAllFactors();
            LOGGER.info("{}/{} factors are valid", validLfFactors.size(), allLfFactors.size());

            // index factors by variable group to compute the minimal number of states
            SensitivityFactorGroupList<DcVariableType, DcEquationType> factorGroups = createFactorGroups(validLfFactors.stream().filter(factor -> factor.getStatus() == LfSensitivityFactor.Status.VALID).collect(Collectors.toList()));

            // compute the participation for each injection factor (+1 on the injection and then -participation factor on all
            // buses that contain elements participating to slack distribution)
            List<ParticipatingElement> participatingElements = lfParameters.isDistributedSlack()
                    ? getParticipatingElements(lfNetwork.getBuses(), lfParameters.getBalanceType(), lfParametersExt)
                    : Collections.emptyList();

            // index contingency elements by branch id
            Map<String, ComputedContingencyElement> contingencyElementByBranch = createContingencyElementsIndexByBranchId(contingencies, lfNetwork, equationSystem);

            // create jacobian matrix either using calculated voltages from pre-contingency network or nominal voltages
            VoltageInitializer voltageInitializer = lfParameters.getVoltageInitMode() == LoadFlowParameters.VoltageInitMode.PREVIOUS_VALUES
                    ? new PreviousValueVoltageInitializer()
                    : new UniformValueVoltageInitializer();

            try (JacobianMatrix<DcVariableType, DcEquationType> j = createJacobianMatrix(lfNetwork, equationSystem, voltageInitializer)) {

                // run DC load on pre-contingency network
                DenseMatrix flowStates = calculateActivePowerFlows(lfNetwork, dcLoadFlowParameters, equationSystem, j, validLfFactors, participatingElements, Collections.emptyList(), Collections.emptyList(), reporter);

                // compute the pre-contingency sensitivity values
                DenseMatrix factorsStates = calculateFactorStates(lfNetwork, equationSystem, factorGroups, j, participatingElements);

                // calculate sensitivity values for pre-contingency network
                calculateSensitivityValues(validFactorHolder.getFactorsForBaseNetwork(), factorsStates, null, flowStates,
                        Collections.emptySet(), null, resultWriter, Collections.emptySet(), Collections.emptySet());

                // compute states with +1 -1 to model the contingencies
                DenseMatrix contingenciesStates = calculateContingenciesStates(lfNetwork, equationSystem, contingencyElementByBranch, j);

                // connectivity analysis by contingency
                // we have to compute sensitivities and reference functions in a different way depending on either or not the contingency breaks connectivity
                // so, we will index contingencies by a list of branch that may break connectivity
                // for example, if in the network, loosing line L1 breaks connectivity, and loosing L2 and L3 together breaks connectivity,
                // the index would be: {L1, L2, L3}
                // a contingency involving a phase tap changer loss has to be processed separately
                List<PropagatedContingency> nonBreakingConnectivityContingencies = new ArrayList<>();
                Map<Set<ComputedContingencyElement>, List<PropagatedContingency>> contingenciesByGroupOfElementsPotentiallyBreakingConnectivity = new LinkedHashMap<>();

                // this first method based on sensitivity criteria is able to detect some contingencies that do not break
                // connectivity and other contingencies that potentially break connectivity
                detectPotentialConnectivityBreak(lfNetwork, contingenciesStates, contingencies, contingencyElementByBranch, equationSystem,
                        nonBreakingConnectivityContingencies, contingenciesByGroupOfElementsPotentiallyBreakingConnectivity);
                LOGGER.info("After sensitivity based connectivity analysis, {} contingencies do not break connectivity, {} contingencies potentially break connectivity",
                        nonBreakingConnectivityContingencies.size(), contingenciesByGroupOfElementsPotentiallyBreakingConnectivity.values().stream().mapToInt(List::size).count());

                // this second method process all contingencies that potentially break connectivity and using graph algorithms
                // find remaining contingencies that do not break connectivity
                List<ConnectivityAnalysisResult> connectivityAnalysisResults
                        = computeConnectivityData(lfNetwork, validFactorHolder, contingenciesByGroupOfElementsPotentiallyBreakingConnectivity, nonBreakingConnectivityContingencies, resultWriter);
                LOGGER.info("After graph based connectivity analysis, {} contingencies do not break connectivity, {} contingencies break connectivity",
                        nonBreakingConnectivityContingencies.size(), connectivityAnalysisResults.stream().mapToInt(results -> results.getContingencies().size()).count());

                LOGGER.info("Processing contingencies with no connectivity break");

                // process contingencies with no connectivity break
                calculateSensitivityValuesForContingencyList(lfNetwork, lfParametersExt, dcLoadFlowParameters, equationSystem, validFactorHolder, factorGroups,
                        j, factorsStates, contingenciesStates, flowStates, nonBreakingConnectivityContingencies, contingencyElementByBranch,
                        Collections.emptySet(), participatingElements, Collections.emptySet(), resultWriter, reporter, Collections.emptySet());

                LOGGER.info("Processing contingencies with connectivity break");

                // process contingencies with connectivity break
                for (ConnectivityAnalysisResult connectivityAnalysisResult : connectivityAnalysisResults) {
                    processContingenciesBreakingConnectivity(connectivityAnalysisResult, lfNetwork, lfParameters, lfParametersExt,
                            dcLoadFlowParameters, equationSystem, validFactorHolder, factorGroups, participatingElements,
                            contingencyElementByBranch, j, flowStates, factorsStates, contingenciesStates, resultWriter, reporter);
                }
            }

            stopwatch.stop();
            LOGGER.info("DC sensitivity analysis done in {} ms", stopwatch.elapsed(TimeUnit.MILLISECONDS));
        }
    }
}
