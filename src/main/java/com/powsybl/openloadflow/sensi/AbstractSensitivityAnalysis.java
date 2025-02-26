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
import com.powsybl.contingency.ContingencyContext;
import com.powsybl.contingency.ContingencyContextType;
import com.powsybl.iidm.network.*;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.math.matrix.Matrix;
import com.powsybl.math.matrix.MatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.dc.DcLoadFlowParameters;
import com.powsybl.openloadflow.equations.Equation;
import com.powsybl.openloadflow.equations.InjectionDerivable;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.EquationTerm;
import com.powsybl.openloadflow.equations.Quantity;
import com.powsybl.openloadflow.graph.GraphConnectivityFactory;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.network.impl.*;
import com.powsybl.openloadflow.network.util.ActivePowerDistribution;
import com.powsybl.openloadflow.network.util.ParticipatingElement;
import com.powsybl.openloadflow.util.Derivable;
import com.powsybl.openloadflow.util.Evaluable;
import com.powsybl.openloadflow.util.PerUnit;
import com.powsybl.sensitivity.*;
import org.apache.commons.lang3.NotImplementedException;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 * @author Gael Macherel {@literal <gael.macherel at artelys.com>}
 */
abstract class AbstractSensitivityAnalysis<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> {

    protected static final Logger LOGGER = LoggerFactory.getLogger(AbstractSensitivityAnalysis.class);

    protected final MatrixFactory matrixFactory;

    protected final GraphConnectivityFactory<LfBus, LfBranch> connectivityFactory;

    protected SensitivityAnalysisParameters parameters;

    private static final String NOT_FOUND = "' not found";

    protected AbstractSensitivityAnalysis(MatrixFactory matrixFactory, GraphConnectivityFactory<LfBus, LfBranch> connectivityFactory, SensitivityAnalysisParameters parameters) {
        this.matrixFactory = Objects.requireNonNull(matrixFactory);
        this.connectivityFactory = Objects.requireNonNull(connectivityFactory);
        this.parameters = Objects.requireNonNull(parameters);
    }

    protected interface LfSensitivityFactor<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> {

        enum Status {
            VALID,
            SKIP,
            VALID_ONLY_FOR_FUNCTION,
            ZERO
        }

        int getIndex();

        String getVariableId();

        SensitivityVariableType getVariableType();

        String getFunctionId();

        LfElement getFunctionElement();

        SensitivityFunctionType getFunctionType();

        ContingencyContext getContingencyContext();

        Derivable<V> getFunctionEquationTerm();

        Double getSensitivityValuePredefinedResult();

        Double getFunctionPredefinedResult();

        void setSensitivityValuePredefinedResult(Double predefinedResult);

        void setFunctionPredefinedResult(Double predefinedResult);

        double getFunctionReference();

        void setFunctionReference(double functionReference);

        Status getStatus();

        void setStatus(Status status);

        boolean isVariableConnectedToSlackComponent(DisabledNetwork disabledNetwork);

        boolean isFunctionConnectedToSlackComponent(DisabledNetwork disabledNetwork);

        boolean isVariableInContingency(PropagatedContingency propagatedContingency);

        SensitivityFactorGroup<V, E> getGroup();

        void setGroup(SensitivityFactorGroup<V, E> group);
    }

    protected abstract static class AbstractLfSensitivityFactor<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> implements LfSensitivityFactor<V, E> {

        private final int index;

        protected final String variableId;

        private final String functionId;

        protected final LfElement functionElement;

        protected final SensitivityFunctionType functionType;

        protected final SensitivityVariableType variableType;

        protected final ContingencyContext contingencyContext;

        private Double sensitivityValuePredefinedResult = null;

        private Double functionPredefinedResult = null;

        private double functionReference = 0d;

        protected Status status = Status.VALID;

        protected SensitivityFactorGroup<V, E> group;

        protected AbstractLfSensitivityFactor(int index, String variableId, String functionId,
                                              LfElement functionElement, SensitivityFunctionType functionType,
                                              SensitivityVariableType variableType, ContingencyContext contingencyContext) {
            this.index = index;
            this.variableId = Objects.requireNonNull(variableId);
            this.functionId = Objects.requireNonNull(functionId);
            this.functionElement = functionElement;
            this.functionType = Objects.requireNonNull(functionType);
            this.variableType = Objects.requireNonNull(variableType);
            this.contingencyContext = Objects.requireNonNull(contingencyContext);
            if (functionElement == null) {
                status = Status.ZERO;
            }
        }

        @Override
        public int getIndex() {
            return index;
        }

        @Override
        public String getVariableId() {
            return variableId;
        }

        @Override
        public SensitivityVariableType getVariableType() {
            return variableType;
        }

        @Override
        public String getFunctionId() {
            return functionId;
        }

        @Override
        public LfElement getFunctionElement() {
            return functionElement;
        }

        @Override
        public SensitivityFunctionType getFunctionType() {
            return functionType;
        }

        @Override
        public ContingencyContext getContingencyContext() {
            return contingencyContext;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Derivable<V> getFunctionEquationTerm() {
            LfBranch branch;
            return (Derivable<V>) switch (functionType) {
                case BRANCH_ACTIVE_POWER_1, BRANCH_ACTIVE_POWER_3
                        -> ((LfBranch) functionElement).getP1();
                case BRANCH_ACTIVE_POWER_2 -> {
                    branch = (LfBranch) functionElement;
                    yield branch instanceof LfLegBranch ? ((LfBranch) functionElement).getP1()
                                                        : ((LfBranch) functionElement).getP2();
                }
                case BRANCH_REACTIVE_POWER_1, BRANCH_REACTIVE_POWER_3
                        -> ((LfBranch) functionElement).getQ1();
                case BRANCH_REACTIVE_POWER_2 -> {
                    branch = (LfBranch) functionElement;
                    yield branch instanceof LfLegBranch ? ((LfBranch) functionElement).getQ1()
                                                        : ((LfBranch) functionElement).getQ2();
                }
                case BRANCH_CURRENT_1, BRANCH_CURRENT_3
                        -> ((LfBranch) functionElement).getI1();
                case BRANCH_CURRENT_2 -> {
                    branch = (LfBranch) functionElement;
                    yield branch instanceof LfLegBranch ? ((LfBranch) functionElement).getI1()
                                                        : ((LfBranch) functionElement).getI2();
                }
                case BUS_REACTIVE_POWER -> {
                    Evaluable q = ((LfBus) functionElement).getQ();
                    yield q instanceof Equation ? new InjectionDerivable<>((Equation<V, ?>) q) : q;
                }
                case BUS_VOLTAGE -> ((LfBus) functionElement).getCalculatedV();
                default -> throw new UnsupportedOperationException("Function type not supported: " + functionType);
            };
        }

        @Override
        public Double getSensitivityValuePredefinedResult() {
            return sensitivityValuePredefinedResult;
        }

        @Override
        public Double getFunctionPredefinedResult() {
            return functionPredefinedResult;
        }

        @Override
        public void setSensitivityValuePredefinedResult(Double predefinedResult) {
            this.sensitivityValuePredefinedResult = predefinedResult;
        }

        @Override
        public void setFunctionPredefinedResult(Double predefinedResult) {
            this.functionPredefinedResult = predefinedResult;
        }

        @Override
        public double getFunctionReference() {
            return functionReference;
        }

        @Override
        public void setFunctionReference(double functionReference) {
            this.functionReference = functionReference;
        }

        @Override
        public Status getStatus() {
            return status;
        }

        @Override
        public void setStatus(Status status) {
            this.status = status;
        }

        protected boolean isElementConnectedToSlackComponent(LfElement element, DisabledNetwork disabledNetwork) {
            if (element instanceof LfBus) {
                return !disabledNetwork.getBuses().contains(element);
            } else if (element instanceof LfBranch) {
                return !disabledNetwork.getBranches().contains(element);
            }
            throw new PowsyblException("Cannot compute connectivity for variable element of class: " + element.getClass().getSimpleName());
        }

        @Override
        public SensitivityFactorGroup<V, E> getGroup() {
            return group;
        }

        @Override
        public void setGroup(SensitivityFactorGroup<V, E> group) {
            this.group = Objects.requireNonNull(group);
        }
    }

    protected static class SingleVariableLfSensitivityFactor<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> extends AbstractLfSensitivityFactor<V, E> {

        private final LfElement variableElement;

        protected SingleVariableLfSensitivityFactor(int index, String variableId, String functionId,
                                                    LfElement functionElement, SensitivityFunctionType functionType,
                                                    LfElement variableElement, SensitivityVariableType variableType,
                                                    ContingencyContext contingencyContext) {
            super(index, variableId, functionId, functionElement, functionType, variableType, contingencyContext);
            this.variableElement = variableElement;
            if (variableElement == null) {
                status = functionElement == null ? Status.SKIP : Status.VALID_ONLY_FOR_FUNCTION;
            }
        }

        protected LfElement getVariableElement() {
            return variableElement;
        }

        protected Equation<V, E> getVariableEquation() {
            switch (variableType) {
                case TRANSFORMER_PHASE, TRANSFORMER_PHASE_1, TRANSFORMER_PHASE_2, TRANSFORMER_PHASE_3:
                    LfBranch lfBranch = (LfBranch) variableElement;
                    return ((EquationTerm<V, E>) lfBranch.getA1()).getEquation();
                case BUS_TARGET_VOLTAGE:
                    LfBus lfBus = (LfBus) variableElement;
                    return ((EquationTerm<V, E>) lfBus.getCalculatedV()).getEquation();
                default:
                    return null;
            }
        }

        @Override
        public boolean isVariableConnectedToSlackComponent(DisabledNetwork disabledNetwork) {
            return isElementConnectedToSlackComponent(variableElement, disabledNetwork);
        }

        @Override
        public boolean isFunctionConnectedToSlackComponent(DisabledNetwork disabledNetwork) {
            return isElementConnectedToSlackComponent(functionElement, disabledNetwork);
        }

        @Override
        public boolean isVariableInContingency(PropagatedContingency contingency) {
            if (contingency != null) {
                switch (variableType) {
                    case INJECTION_ACTIVE_POWER,
                         HVDC_LINE_ACTIVE_POWER:
                        // a load, a generator, a dangling line, an LCC or a VSC converter station.
                        return contingency.getGeneratorIdsToLose().contains(variableId) || contingency.getLoadIdsToLose().containsKey(variableId);
                    case BUS_TARGET_VOLTAGE:
                        // a generator or a two windings transformer.
                        // shunt contingency not supported yet.
                        // ratio tap changer in a three windings transformer not supported yet.
                        return contingency.getGeneratorIdsToLose().contains(variableId) || contingency.getBranchIdsToOpen().containsKey(variableId);
                    case TRANSFORMER_PHASE:
                        // a phase shifter on a two windings transformer.
                        return contingency.getBranchIdsToOpen().containsKey(variableId);
                    default:
                        return false;
                }
            } else {
                return false;
            }
        }
    }

    protected static class MultiVariablesLfSensitivityFactor<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> extends AbstractLfSensitivityFactor<V, E> {

        private final Map<LfElement, Double> weightedVariableElements;

        private final Set<String> originalVariableSetIds;

        protected MultiVariablesLfSensitivityFactor(int index, String variableId, String functionId,
                                                    LfElement functionElement, SensitivityFunctionType functionType,
                                                    Map<LfElement, Double> weightedVariableElements, SensitivityVariableType variableType,
                                                    ContingencyContext contingencyContext, Set<String> originalVariableSetIds) {
            super(index, variableId, functionId, functionElement, functionType, variableType, contingencyContext);
            this.weightedVariableElements = weightedVariableElements;
            if (weightedVariableElements.isEmpty()) {
                status = functionElement == null ? Status.SKIP : Status.VALID_ONLY_FOR_FUNCTION;
            }
            this.originalVariableSetIds = originalVariableSetIds;
        }

        protected Map<LfElement, Double> getWeightedVariableElements() {
            return weightedVariableElements;
        }

        protected Collection<LfElement> getVariableElements() {
            return weightedVariableElements.keySet();
        }

        @Override
        public boolean isVariableConnectedToSlackComponent(DisabledNetwork disabledNetwork) {
            for (LfElement lfElement : getVariableElements()) {
                if (isElementConnectedToSlackComponent(lfElement, disabledNetwork)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean isFunctionConnectedToSlackComponent(DisabledNetwork disabledNetwork) {
            return isElementConnectedToSlackComponent(functionElement, disabledNetwork);
        }

        @Override
        public boolean isVariableInContingency(PropagatedContingency contingency) {
            if (contingency != null) {
                int sizeCommonIds = (int) Stream.concat(contingency.getGeneratorIdsToLose().stream(), contingency.getLoadIdsToLose().keySet().stream())
                        .distinct()
                        .filter(originalVariableSetIds::contains)
                        .count();
                return sizeCommonIds == originalVariableSetIds.size();
            } else {
                return false;
            }
        }
    }

    protected interface SensitivityFactorGroup<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> {

        List<LfSensitivityFactor<V, E>> getFactors();

        int getIndex();

        void setIndex(int index);

        void addFactor(LfSensitivityFactor<V, E> factor);

        void fillRhs(Matrix rhs, Map<LfBus, Double> participationByBus);
    }

    protected abstract static class AbstractSensitivityFactorGroup<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> implements SensitivityFactorGroup<V, E> {

        protected final List<LfSensitivityFactor<V, E>> factors = new ArrayList<>();

        protected final SensitivityVariableType variableType;

        private int index = -1;

        AbstractSensitivityFactorGroup(SensitivityVariableType variableType) {
            this.variableType = variableType;
        }

        @Override
        public List<LfSensitivityFactor<V, E>> getFactors() {
            return factors;
        }

        @Override
        public int getIndex() {
            return index;
        }

        @Override
        public void setIndex(int index) {
            this.index = index;
        }

        @Override
        public void addFactor(LfSensitivityFactor<V, E> factor) {
            factors.add(factor);
        }

        protected void addBusInjection(Matrix rhs, LfBus lfBus, double injection) {
            Equation<V, E> p = (Equation<V, E>) lfBus.getP();
            if (lfBus.isSlack() || !p.isActive()) {
                return;
            }
            int column = p.getColumn();
            rhs.add(column, getIndex(), injection);
        }

        protected void addBusReactiveInjection(Matrix rhs, LfBus lfBus, double injection) {
            Equation<V, E> q = (Equation<V, E>) lfBus.getQ();
            if (!q.isActive()) {
                return;
            }
            int column = q.getColumn();
            rhs.add(column, getIndex(), injection);
        }
    }

    private static NotImplementedException createVariableTypeNotImplementedException(SensitivityVariableType variableType) {
        return new NotImplementedException("Variable type " + variableType + " is not implemented");
    }

    protected static class SingleVariableFactorGroup<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> extends AbstractSensitivityFactorGroup<V, E> {

        private final LfElement variableElement;

        private final Equation<V, E> variableEquation;

        protected SingleVariableFactorGroup(LfElement variableElement, Equation<V, E> variableEquation, SensitivityVariableType variableType) {
            super(variableType);
            this.variableElement = Objects.requireNonNull(variableElement);
            this.variableEquation = variableEquation;
        }

        @Override
        public void fillRhs(Matrix rhs, Map<LfBus, Double> participationByBus) {
            switch (variableType) {
                case TRANSFORMER_PHASE, TRANSFORMER_PHASE_1, TRANSFORMER_PHASE_2, TRANSFORMER_PHASE_3:
                    if (variableEquation.isActive()) {
                        rhs.set(variableEquation.getColumn(), getIndex(), Math.toRadians(1d));
                    }
                    break;
                case INJECTION_ACTIVE_POWER:
                    for (Map.Entry<LfBus, Double> lfBusAndParticipationFactor : participationByBus.entrySet()) {
                        LfBus lfBus = lfBusAndParticipationFactor.getKey();
                        Double injection = lfBusAndParticipationFactor.getValue();
                        addBusInjection(rhs, lfBus, injection);
                    }
                    addBusInjection(rhs, (LfBus) variableElement, 1d);
                    break;
                case INJECTION_REACTIVE_POWER:
                    addBusReactiveInjection(rhs, (LfBus) variableElement, 1d);
                    break;
                case BUS_TARGET_VOLTAGE:
                    if (variableEquation.isActive()) {
                        rhs.set(variableEquation.getColumn(), getIndex(), 1d);
                    }
                    break;
                default:
                    throw createVariableTypeNotImplementedException(variableType);
            }
        }
    }

    protected static class MultiVariablesFactorGroup<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> extends AbstractSensitivityFactorGroup<V, E> {

        private Map<LfElement, Double> variableElements;
        private Map<LfElement, Double> mainComponentWeights;

        protected MultiVariablesFactorGroup(Map<LfElement, Double> variableElements, SensitivityVariableType variableType) {
            super(variableType);
            this.variableElements = variableElements;
            this.mainComponentWeights = variableElements;
        }

        protected Map<LfElement, Double> getVariableElements() {
            return variableElements;
        }

        @Override
        public void fillRhs(Matrix rhs, Map<LfBus, Double> participationByBus) {
            double weightSum = mainComponentWeights.values().stream().mapToDouble(Math::abs).sum();
            switch (variableType) {
                case INJECTION_ACTIVE_POWER:
                    for (Map.Entry<LfBus, Double> lfBusAndParticipationFactor : participationByBus.entrySet()) {
                        LfBus lfBus = lfBusAndParticipationFactor.getKey();
                        double injection = lfBusAndParticipationFactor.getValue();
                        addBusInjection(rhs, lfBus, injection);
                    }
                    for (Map.Entry<LfElement, Double> variableElementAndWeight : mainComponentWeights.entrySet()) {
                        LfElement variableElement = variableElementAndWeight.getKey();
                        double weight = variableElementAndWeight.getValue();
                        addBusInjection(rhs, (LfBus) variableElement, weight / weightSum);
                    }
                    break;
                case HVDC_LINE_ACTIVE_POWER:
                    assert mainComponentWeights.size() <= 2;
                    double balanceDiff = mainComponentWeights.values().stream().mapToDouble(x -> x).sum();
                    for (Map.Entry<LfBus, Double> lfBusAndParticipationFactor : participationByBus.entrySet()) {
                        LfBus lfBus = lfBusAndParticipationFactor.getKey();
                        double injection = lfBusAndParticipationFactor.getValue() * balanceDiff; // adapt the sign of the slack distribution depending on the injection
                        addBusInjection(rhs, lfBus, injection);
                    }
                    // add the injections on the side of the hvdc
                    for (Map.Entry<LfElement, Double> variableElementAndWeight : mainComponentWeights.entrySet()) {
                        LfElement variableElement = variableElementAndWeight.getKey();
                        double weight = variableElementAndWeight.getValue();
                        addBusInjection(rhs, (LfBus) variableElement, weight);
                    }
                    break;
                default:
                    throw createVariableTypeNotImplementedException(variableType);
            }
        }

        protected boolean updateConnectivityWeights(Set<LfBus> nonConnectedBuses) {
            mainComponentWeights = variableElements.entrySet().stream()
                .filter(entry -> !nonConnectedBuses.contains((LfBus) entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            return mainComponentWeights.size() != variableElements.size();
        }
    }

    protected static class SensitivityFactorGroupList<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> {

        private final List<SensitivityFactorGroup<V, E>> list;

        private final boolean multiVariables;

        protected SensitivityFactorGroupList(List<SensitivityFactorGroup<V, E>> list) {
            this.list = Objects.requireNonNull(list);
            multiVariables = list.stream().anyMatch(MultiVariablesFactorGroup.class::isInstance);
        }

        protected List<SensitivityFactorGroup<V, E>> getList() {
            return list;
        }

        protected boolean hasMultiVariables() {
            return multiVariables;
        }
    }

    protected SensitivityFactorGroupList<V, E> createFactorGroups(List<LfSensitivityFactor<V, E>> factors) {
        Map<Pair<SensitivityVariableType, String>, SensitivityFactorGroup<V, E>> groupIndexedById = new LinkedHashMap<>(factors.size());
        // index factors by variable config
        for (LfSensitivityFactor<V, E> factor : factors) {
            Pair<SensitivityVariableType, String> id = Pair.of(factor.getVariableType(), factor.getVariableId());
            if (factor instanceof SingleVariableLfSensitivityFactor) {
                SingleVariableLfSensitivityFactor<V, E> singleVarFactor = (SingleVariableLfSensitivityFactor<V, E>) factor;
                SensitivityFactorGroup<V, E> factorGroup = groupIndexedById.computeIfAbsent(id, k -> new SingleVariableFactorGroup<>(singleVarFactor.getVariableElement(), singleVarFactor.getVariableEquation(), factor.getVariableType()));
                factorGroup.addFactor(factor);
                factor.setGroup(factorGroup);
            } else if (factor instanceof MultiVariablesLfSensitivityFactor) {
                SensitivityFactorGroup<V, E> factorGroup = groupIndexedById.computeIfAbsent(id, k -> new MultiVariablesFactorGroup<>(((MultiVariablesLfSensitivityFactor<V, E>) factor).getWeightedVariableElements(), factor.getVariableType()));
                factorGroup.addFactor(factor);
                factor.setGroup(factorGroup);
            }
        }

        // assign an index to each factor group
        int index = 0;
        for (SensitivityFactorGroup<V, E> factorGroup : groupIndexedById.values()) {
            factorGroup.setIndex(index++);
        }

        return new SensitivityFactorGroupList<>(new ArrayList<>(groupIndexedById.values()));
    }

    protected List<ParticipatingElement> getParticipatingElements(Collection<LfBus> buses, LoadFlowParameters.BalanceType balanceType, OpenLoadFlowParameters openLoadFlowParameters) {
        ActivePowerDistribution.Step step = ActivePowerDistribution.getStep(balanceType, openLoadFlowParameters.isLoadPowerFactorConstant(), openLoadFlowParameters.isUseActiveLimits());
        List<ParticipatingElement> participatingElements = step.getParticipatingElements(buses, 0.0); // active power mismatch is only needed for REMAINING_MARGIN participation type. But it is not supported in sensi analysis and is checked in #checkLoadFlowParameters
        ParticipatingElement.normalizeParticipationFactors(participatingElements);
        return participatingElements;
    }

    static <V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> DenseMatrix initFactorsRhs(EquationSystem<V, E> equationSystem, SensitivityFactorGroupList<V, E> factorsGroups, Map<LfBus, Double> participationByBus) {
        // otherwise, defining the rhs matrix will result in integer overflow
        int equationCount = equationSystem.getIndex().getSortedEquationsToSolve().size();
        int factorsGroupCount = factorsGroups.getList().size();
        int maxFactorsGroups = Integer.MAX_VALUE / (equationCount * Double.BYTES);
        if (factorsGroupCount > maxFactorsGroups) {
            throw new PowsyblException("Too many factors groups " + factorsGroupCount
                    + ", maximum is " + maxFactorsGroups + " for a system with " + equationCount + " equations");
        }

        DenseMatrix rhs = new DenseMatrix(equationCount, factorsGroupCount);
        fillRhsSensitivityVariable(factorsGroups, rhs, participationByBus);
        return rhs;
    }

    protected static <V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> void fillRhsSensitivityVariable(SensitivityFactorGroupList<V, E> factorGroups, Matrix rhs, Map<LfBus, Double> participationByBus) {
        for (SensitivityFactorGroup<V, E> factorGroup : factorGroups.getList()) {
            factorGroup.fillRhs(rhs, participationByBus);
        }
    }

    protected void setPredefinedResults(Collection<LfSensitivityFactor<V, E>> lfFactors, DisabledNetwork disabledNetwork,
                                        PropagatedContingency propagatedContingency) {
        for (LfSensitivityFactor<V, E> factor : lfFactors) {
            Pair<Optional<Double>, Optional<Double>> predefinedResults = getPredefinedResults(factor, disabledNetwork, propagatedContingency);
            predefinedResults.getLeft().ifPresent(factor::setSensitivityValuePredefinedResult);
            predefinedResults.getRight().ifPresent(factor::setFunctionPredefinedResult);
        }
    }

    protected Pair<Optional<Double>, Optional<Double>> getPredefinedResults(LfSensitivityFactor<V, E> factor, DisabledNetwork disabledNetwork,
                                                                            PropagatedContingency propagatedContingency) {
        Double sensitivityValuePredefinedResult = null;
        Double functionPredefinedResult = null;
        if (factor.getStatus() == LfSensitivityFactor.Status.VALID) {
            // after a contingency, we check if the factor function and the variable are in different connected components
            // or if the variable is in contingency. Note that a branch in contingency is considered as not connected to the slack component.
            boolean variableConnected = factor.isVariableConnectedToSlackComponent(disabledNetwork) && !factor.isVariableInContingency(propagatedContingency);
            boolean functionConnectedToSlackComponent = factor.isFunctionConnectedToSlackComponent(disabledNetwork);
            if (variableConnected) {
                if (!functionConnectedToSlackComponent) {
                    // ZERO status
                    sensitivityValuePredefinedResult = 0d;
                    functionPredefinedResult = Double.NaN;
                }
            } else {
                if (functionConnectedToSlackComponent) {
                    // VALID_ONLY_FOR_FUNCTION status
                    sensitivityValuePredefinedResult = 0d;
                } else {
                    // SKIP status
                    sensitivityValuePredefinedResult = Double.NaN;
                    functionPredefinedResult = Double.NaN;
                }
            }
        } else if (factor.getStatus() == LfSensitivityFactor.Status.VALID_ONLY_FOR_FUNCTION) {
            sensitivityValuePredefinedResult = 0d;
            if (!factor.isFunctionConnectedToSlackComponent(disabledNetwork)) {
                functionPredefinedResult = Double.NaN;
            }
        } else {
            throw new IllegalStateException("Unexpected factor status: " + factor.getStatus());
        }
        return Pair.of(Optional.ofNullable(sensitivityValuePredefinedResult), Optional.ofNullable(functionPredefinedResult));
    }

    protected boolean rescaleGlsk(SensitivityFactorGroupList<V, E> factorGroups, Set<LfBus> nonConnectedBuses) {
        boolean rescaled = false;
        // compute the corresponding injection (with participation) for each factor
        for (SensitivityFactorGroup<V, E> factorGroup : factorGroups.getList()) {
            if (factorGroup instanceof MultiVariablesFactorGroup) {
                MultiVariablesFactorGroup<V, E> multiVariablesFactorGroup = (MultiVariablesFactorGroup<V, E>) factorGroup;
                rescaled |= multiVariablesFactorGroup.updateConnectivityWeights(nonConnectedBuses);
            }
        }
        return rescaled;
    }

    /**
     * Write zero or skip factors to output and send a new factor holder containing only other valid ones.
     * IMPORTANT: this is only a base case test (factor status only deal with base case). We do not output anything
     * on post contingency if factor is already invalid (skip o zero) on base case. Except for factors with specific
     * contingency context, we output the invalid status found during base case analysis.
     */
    protected SensitivityFactorHolder<V, E> writeInvalidFactors(SensitivityFactorHolder<V, E> factorHolder, SensitivityResultWriter resultWriter,
                                                                List<PropagatedContingency> contingencies) {
        Set<String> skippedVariables = new LinkedHashSet<>();
        SensitivityFactorHolder<V, E> validFactorHolder = new SensitivityFactorHolder<>();
        Map<String, Integer> contingencyIndexById = new HashMap<>();
        contingencies.stream().forEach(contingency -> contingencyIndexById.put(contingency.getContingency().getId(), contingency.getIndex()));
        for (var factor : factorHolder.getAllFactors()) {
            Optional<Double> sensitivityVariableToWrite = Optional.empty();
            if (factor.getStatus() == LfSensitivityFactor.Status.ZERO) {
                // ZERO status is for factors where variable element is in the main connected component and reference element is not.
                // Therefore, the sensitivity is known to value 0, but the reference cannot be known and is set to NaN.
                if (!filterSensitivityValue(0, factor.getVariableType(), factor.getFunctionType(), parameters)) {
                    sensitivityVariableToWrite = Optional.of(0.0);
                }
            } else if (factor.getStatus() == LfSensitivityFactor.Status.SKIP) {
                sensitivityVariableToWrite = Optional.of(Double.NaN);
                skippedVariables.add(factor.getVariableId());
            } else {
                validFactorHolder.addFactor(factor);
            }
            if (sensitivityVariableToWrite.isPresent()) {
                // directly write output for zero and invalid factors
                double value = sensitivityVariableToWrite.get();
                if (factor.getContingencyContext().getContextType() == ContingencyContextType.NONE) {
                    resultWriter.writeSensitivityValue(factor.getIndex(), -1, value, Double.NaN);
                } else if (factor.getContingencyContext().getContextType() == ContingencyContextType.SPECIFIC) {
                    resultWriter.writeSensitivityValue(factor.getIndex(), contingencyIndexById.get(factor.getContingencyContext().getContingencyId()), value, Double.NaN);
                } else if (factor.getContingencyContext().getContextType() == ContingencyContextType.ALL) {
                    resultWriter.writeSensitivityValue(factor.getIndex(), -1, value, Double.NaN);
                    contingencyIndexById.values().forEach(index -> resultWriter.writeSensitivityValue(factor.getIndex(), index, value, Double.NaN));
                }
            }
        }
        if (!skippedVariables.isEmpty() && LOGGER.isWarnEnabled()) {
            LOGGER.warn("Skipping all factors with variables: '{}', as they cannot be found in the network",
                    String.join(", ", skippedVariables));
        }
        return validFactorHolder;
    }

    protected void checkContingencies(List<PropagatedContingency> contingencies) {
        Set<String> contingenciesIds = new HashSet<>();
        for (PropagatedContingency contingency : contingencies) {
            // check ID are unique because, later contingency are indexed by their IDs
            String contingencyId = contingency.getContingency().getId();
            if (contingenciesIds.contains(contingencyId)) {
                throw new PowsyblException("Contingency '" + contingencyId + "' already exists");
            }
            contingenciesIds.add(contingencyId);
        }
    }

    protected void checkLoadFlowParameters(LoadFlowParameters lfParameters) {
        if (!lfParameters.getBalanceType().equals(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX)
                && !lfParameters.getBalanceType().equals(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD)
                && !lfParameters.getBalanceType().equals(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P)) {
            throw new UnsupportedOperationException("Unsupported balance type mode: " + lfParameters.getBalanceType());
        }
    }

    private static LfBranch checkAndGetBranchOrLeg(Network network, String branchId, SensitivityFunctionType fType, LfNetwork lfNetwork) {
        Branch<?> branch = network.getBranch(branchId);
        if (branch != null) {
            return lfNetwork.getBranchById(branchId);
        }
        DanglingLine danglingLine = network.getDanglingLine(branchId);
        if (danglingLine != null && !danglingLine.isPaired()) {
            return lfNetwork.getBranchById(branchId);
        }
        ThreeWindingsTransformer twt = network.getThreeWindingsTransformer(branchId);
        if (twt != null) {
            return lfNetwork.getBranchById(LfLegBranch.getId(branchId, getLegNumber(fType)));
        }
        TieLine line = network.getTieLine(branchId);
        if (line != null) {
            return lfNetwork.getBranchById(branchId);
        }
        throw new PowsyblException("Branch, tie line, dangling line or leg of '" + branchId + NOT_FOUND);
    }

    private static void checkBus(Network network, String busId, Map<String, Bus> busCache, boolean breakers) {
        if (busCache.isEmpty()) {
            Networks.getBuses(network, breakers).forEach(bus -> busCache.put(bus.getId(), bus));
        }
        Bus bus = busCache.get(busId);
        if (bus == null) {
            throw new PowsyblException("Bus '" + busId + NOT_FOUND);
        }
    }

    private static void checkPhaseShifter(Network network, String transformerId) {
        TwoWindingsTransformer twt = network.getTwoWindingsTransformer(transformerId);
        if (twt == null) {
            throw new PowsyblException("Two windings transformer '" + transformerId + NOT_FOUND);
        }
        if (twt.getPhaseTapChanger() == null) {
            throw new PowsyblException("Two windings transformer '" + transformerId + "' is not a phase shifter");
        }
    }

    private static void checkThreeWindingsTransformerPhaseShifter(Network network, String transformerId, SensitivityVariableType type) {
        ThreeWindingsTransformer twt = network.getThreeWindingsTransformer(transformerId);
        if (twt == null) {
            throw new PowsyblException("Three windings transformer '" + transformerId + NOT_FOUND);
        }
        ThreeWindingsTransformer.Leg l = twt.getLegs().get(getLegNumber(type) - 1);
        if (l.getPhaseTapChanger() == null) {
            throw new PowsyblException("Three windings transformer '" + transformerId + "' leg on side '" + type + "' has no phase tap changer");
        }
    }

    private static void checkRegulatingTerminal(Network network, String equipmentId) {
        Optional<Terminal> terminal = Networks.getEquipmentRegulatingTerminal(network, equipmentId);
        if (terminal.isEmpty()) {
            throw new PowsyblException("Regulating terminal for '" + equipmentId + NOT_FOUND);
        }
    }

    protected static class SensitivityFactorHolder<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> {

        private final Map<String, List<LfSensitivityFactor<V, E>>> additionalFactorsPerContingency = new LinkedHashMap<>();
        private final List<LfSensitivityFactor<V, E>> additionalFactorsNoContingency = new ArrayList<>();
        private final List<LfSensitivityFactor<V, E>> commonFactors = new ArrayList<>();

        protected List<LfSensitivityFactor<V, E>> getAllFactors() {
            List<LfSensitivityFactor<V, E>> allFactors = new ArrayList<>(commonFactors);
            allFactors.addAll(additionalFactorsNoContingency);
            allFactors.addAll(additionalFactorsPerContingency.values().stream().flatMap(List::stream).collect(Collectors.toCollection(LinkedHashSet::new)));
            return allFactors;
        }

        protected List<LfSensitivityFactor<V, E>> getFactorsForContingency(String contingencyId) {
            return Stream.concat(commonFactors.stream(), additionalFactorsPerContingency.getOrDefault(contingencyId, Collections.emptyList()).stream())
                .collect(Collectors.toList());
        }

        protected List<LfSensitivityFactor<V, E>> getFactorsForContingencies(List<String> contingenciesIds) {
            return Stream.concat(commonFactors.stream(),
                                 contingenciesIds.stream().flatMap(contingencyId -> additionalFactorsPerContingency.getOrDefault(contingencyId, Collections.emptyList()).stream()))
                    .collect(Collectors.toList());
        }

        protected List<LfSensitivityFactor<V, E>> getFactorsForBaseNetwork() {
            return Stream.concat(commonFactors.stream(), additionalFactorsNoContingency.stream())
                .collect(Collectors.toList());
        }

        protected void addFactor(LfSensitivityFactor<V, E> factor) {
            switch (factor.getContingencyContext().getContextType()) {
                case ALL:
                    commonFactors.add(factor);
                    break;
                case NONE:
                    additionalFactorsNoContingency.add(factor);
                    break;
                case SPECIFIC:
                    additionalFactorsPerContingency.computeIfAbsent(factor.getContingencyContext().getContingencyId(), k -> new ArrayList<>()).add(factor);
                    break;
            }
        }
    }

    private static PowsyblException createFunctionTypeNotSupportedException(SensitivityFunctionType functionType) {
        return new PowsyblException("Function type " + functionType + " not supported");
    }

    private static PowsyblException createVariableTypeNotSupportedWithFunctionTypeException(SensitivityVariableType variableType, SensitivityFunctionType functionType) {
        return new PowsyblException("Variable type " + variableType + " not supported with function type " + functionType);
    }

    static class InjectionVariableIdToBusIdCache {

        private final Map<String, String> variableIdToBusId = new HashMap<>();

        private static Injection<?> getInjection(Network network, String injectionId) {
            Injection<?> injection = network.getGenerator(injectionId);
            if (injection == null) {
                injection = network.getLoad(injectionId);
            }
            if (injection == null) {
                injection = network.getDanglingLine(injectionId);
                if (injection != null && network.getDanglingLine(injectionId).isPaired()) {
                    throw new PowsyblException("The dangling line " + injectionId + " is paired: it cannot be a sensitivity variable");
                }
                injection = network.getDanglingLine(injectionId);
            }
            if (injection == null) {
                injection = network.getLccConverterStation(injectionId);
            }
            if (injection == null) {
                injection = network.getVscConverterStation(injectionId);
            }
            return injection;
        }

        protected static String getInjectionBusId(Network network, String injectionId, boolean breakers) {
            // try with an injection
            Injection<?> injection = getInjection(network, injectionId);
            if (injection != null) {
                Bus bus = Networks.getBus(injection.getTerminal(), breakers);
                if (bus == null) {
                    return null;
                }
                if (injection instanceof DanglingLine dl) {
                    return LfDanglingLineBus.getId(dl);
                } else {
                    return bus.getId();
                }
            }

            // try with a configured bus
            Bus configuredBus = network.getBusBreakerView().getBus(injectionId);
            if (configuredBus != null) {
                // find a bus from bus view corresponding to this configured bus
                List<Terminal> terminals = new ArrayList<>();
                configuredBus.visitConnectedEquipments(new AbstractTerminalTopologyVisitor() {
                    @Override
                    public void visitTerminal(Terminal terminal) {
                        terminals.add(terminal);
                    }
                });
                for (Terminal terminal : terminals) {
                    Bus bus = Networks.getBus(terminal, breakers);
                    if (bus != null) {
                        return bus.getId();
                    }
                }
                return null;
            }

            // try with a busbar section
            BusbarSection busbarSection = network.getBusbarSection(injectionId);
            if (busbarSection != null) {
                Bus bus = Networks.getBus(busbarSection.getTerminal(), breakers);
                if (bus == null) {
                    return null;
                }
                return bus.getId();
            }

            throw new PowsyblException("Injection '" + injectionId + NOT_FOUND);
        }

        String getBusId(Network network, String variableId, boolean breakers) {
            return variableIdToBusId.computeIfAbsent(variableId, variableId2 -> getInjectionBusId(network, variableId2, breakers));
        }
    }

    protected SensitivityFactorHolder<V, E> readAndCheckFactors(Network network, Map<String, SensitivityVariableSet> variableSetsById,
                                                             SensitivityFactorReader factorReader, LfNetwork lfNetwork, boolean breakers) {
        final SensitivityFactorHolder<V, E> factorHolder = new SensitivityFactorHolder<>();

        final Map<String, Map<LfElement, Double>> injectionBusesByVariableId = new LinkedHashMap<>();
        final Map<String, Set<String>> originalVariableSetIdsByVariableId = new LinkedHashMap<>();
        final Map<String, Bus> busCache = new HashMap<>();
        InjectionVariableIdToBusIdCache injectionVariableIdToBusIdCache = new InjectionVariableIdToBusIdCache();
        int[] factorIndex = new int[1];
        factorReader.read((functionType, functionId, variableType, variableId, variableSet, contingencyContext) -> {
            if (variableSet) {
                if (isActivePowerFunctionType(functionType)) {
                    if (variableType == SensitivityVariableType.INJECTION_ACTIVE_POWER) {
                        LfBranch branch = checkAndGetBranchOrLeg(network, functionId, functionType, lfNetwork);
                        LfElement functionElement = branch != null && branch.getBus1() != null && branch.getBus2() != null ? branch : null;
                        Map<LfElement, Double> injectionLfBuses = injectionBusesByVariableId.get(variableId);
                        Set<String> originalVariableSetIds = originalVariableSetIdsByVariableId.get(variableId);
                        if (injectionLfBuses == null && originalVariableSetIds == null) {
                            injectionLfBuses = new LinkedHashMap<>();
                            originalVariableSetIds = new HashSet<>();
                            injectionBusesByVariableId.put(variableId, injectionLfBuses);
                            originalVariableSetIdsByVariableId.put(variableId, originalVariableSetIds);
                            SensitivityVariableSet set = variableSetsById.get(variableId);
                            if (set == null) {
                                throw new PowsyblException("Variable set '" + variableId + NOT_FOUND);
                            }
                            List<String> skippedInjection = new ArrayList<>(set.getVariables().size());
                            for (WeightedSensitivityVariable variable : set.getVariables()) {
                                String injectionBusId = injectionVariableIdToBusIdCache.getBusId(network, variable.getId(), breakers);
                                LfBus injectionLfBus = injectionBusId != null ? lfNetwork.getBusById(injectionBusId) : null;
                                if (injectionLfBus == null) {
                                    skippedInjection.add(variable.getId());
                                    continue;
                                }
                                injectionLfBuses.put(injectionLfBus, injectionLfBuses.getOrDefault(injectionLfBus, 0d) + variable.getWeight());
                                originalVariableSetIds.add(variable.getId());
                            }
                            if (!skippedInjection.isEmpty() && LOGGER.isWarnEnabled()) {
                                LOGGER.warn("Injections {} cannot be found for glsk {} and will be ignored", String.join(", ", skippedInjection), variableId);
                            }
                        }
                        factorHolder.addFactor(new MultiVariablesLfSensitivityFactor<>(factorIndex[0], variableId,
                                    functionId, functionElement, functionType,
                                    injectionLfBuses, variableType, contingencyContext, originalVariableSetIds));
                    } else {
                        throw createVariableTypeNotSupportedWithFunctionTypeException(variableType, functionType);
                    }
                } else {
                    throw createFunctionTypeNotSupportedException(functionType);
                }
            } else {
                if (isActivePowerFunctionType(functionType) && variableType == SensitivityVariableType.HVDC_LINE_ACTIVE_POWER) {
                    LfBranch branch = checkAndGetBranchOrLeg(network, functionId, functionType, lfNetwork);
                    LfElement functionElement = branch != null && branch.getBus1() != null && branch.getBus2() != null ? branch : null;

                    HvdcLine hvdcLine = network.getHvdcLine(variableId);
                    if (hvdcLine == null) {
                        throw new PowsyblException("HVDC line '" + variableId + "' cannot be found in the network.");
                    }
                    Bus bus1 = Networks.getBus(hvdcLine.getConverterStation1().getTerminal(), breakers);
                    Bus bus2 = Networks.getBus(hvdcLine.getConverterStation2().getTerminal(), breakers);

                    // corresponds to an augmentation of +1 on the active power setpoint on each side on the HVDC line
                    // => we create a multi (bi) variables factor
                    Map<LfElement, Double> injectionLfBuses = new HashMap<>(2);
                    Set<String> originalVariableSetIds = new HashSet<>(2);
                    if (bus1 != null) {
                        LfBus lfBus1 = lfNetwork.getBusById(bus1.getId());
                        if (lfBus1 != null) {
                            injectionLfBuses.put(lfBus1, HvdcConverterStations.getActivePowerSetpointMultiplier(hvdcLine.getConverterStation1()));
                            originalVariableSetIds.add(hvdcLine.getConverterStation1().getId());
                        }
                    }
                    if (bus2 != null) {
                        LfBus lfBus2 = lfNetwork.getBusById(bus2.getId());
                        if (lfBus2 != null) {
                            injectionLfBuses.put(lfBus2, HvdcConverterStations.getActivePowerSetpointMultiplier(hvdcLine.getConverterStation2()));
                            originalVariableSetIds.add(hvdcLine.getConverterStation2().getId());
                        }
                    }

                    factorHolder.addFactor(new MultiVariablesLfSensitivityFactor<>(factorIndex[0], variableId,
                            functionId, functionElement, functionType, injectionLfBuses, variableType, contingencyContext, originalVariableSetIds));
                } else {
                    LfElement functionElement;
                    LfElement variableElement;
                    if (isActivePowerFunctionType(functionType) || isCurrentFunctionType(functionType)) {
                        LfBranch branch = checkAndGetBranchOrLeg(network, functionId, functionType, lfNetwork);
                        functionElement = branch != null && branch.getBus1() != null && branch.getBus2() != null ? branch : null;
                        switch (variableType) {
                            case INJECTION_ACTIVE_POWER, INJECTION_REACTIVE_POWER:
                                String injectionBusId = injectionVariableIdToBusIdCache.getBusId(network, variableId, breakers);
                                variableElement = injectionBusId != null ? lfNetwork.getBusById(injectionBusId) : null;
                                break;
                            case TRANSFORMER_PHASE:
                                checkPhaseShifter(network, variableId);
                                LfBranch twt = lfNetwork.getBranchById(variableId);
                                variableElement = twt != null && twt.getBus1() != null && twt.getBus2() != null ? twt : null;
                                break;
                            case TRANSFORMER_PHASE_1, TRANSFORMER_PHASE_2, TRANSFORMER_PHASE_3:
                                checkThreeWindingsTransformerPhaseShifter(network, variableId, variableType);
                                LfBranch leg = lfNetwork.getBranchById(LfLegBranch.getId(variableId, getLegNumber(variableType)));
                                variableElement = leg != null && leg.getBus1() != null && leg.getBus2() != null ? leg : null;
                                break;
                            case BUS_TARGET_VOLTAGE:
                                variableElement = findBusTargetVoltageVariableElement(network, variableId, breakers, lfNetwork);
                                break;
                            default:
                                throw createVariableTypeNotSupportedWithFunctionTypeException(variableType, functionType);
                        }
                    } else if (functionType == SensitivityFunctionType.BUS_VOLTAGE) {
                        checkBus(network, functionId, busCache, breakers);
                        functionElement = lfNetwork.getBusById(functionId);
                        switch (variableType) {
                            case BUS_TARGET_VOLTAGE :
                                variableElement = findBusTargetVoltageVariableElement(network, variableId, breakers, lfNetwork);
                                break;
                            case INJECTION_REACTIVE_POWER:
                                String injectionBusId = injectionVariableIdToBusIdCache.getBusId(network, variableId, breakers);
                                variableElement = injectionBusId != null ? lfNetwork.getBusById(injectionBusId) : null;
                                break;
                            default:
                                throw createVariableTypeNotSupportedWithFunctionTypeException(variableType, functionType);
                        }
                    } else if (isReactivePowerFunctionType(functionType)) {
                        LfBranch branch = checkAndGetBranchOrLeg(network, functionId, functionType, lfNetwork);
                        functionElement = branch != null && branch.getBus1() != null && branch.getBus2() != null ? branch : null;
                        if (variableType == SensitivityVariableType.BUS_TARGET_VOLTAGE) {
                            variableElement = findBusTargetVoltageVariableElement(network, variableId, breakers, lfNetwork);
                        } else {
                            throw createVariableTypeNotSupportedWithFunctionTypeException(variableType, functionType);
                        }
                    } else if (functionType == SensitivityFunctionType.BUS_REACTIVE_POWER) {
                        String functionInjectionBusId = injectionVariableIdToBusIdCache.getBusId(network, functionId, breakers);
                        functionElement = functionInjectionBusId != null ? lfNetwork.getBusById(functionInjectionBusId) : null;
                        variableElement = switch (variableType) {
                            case BUS_TARGET_VOLTAGE -> findBusTargetVoltageVariableElement(network, variableId, breakers, lfNetwork);
                            case INJECTION_REACTIVE_POWER -> {
                                String variableInjectionBusId = injectionVariableIdToBusIdCache.getBusId(network, variableId, breakers);
                                yield variableInjectionBusId != null ? lfNetwork.getBusById(variableInjectionBusId) : null;
                            }
                            default -> throw createVariableTypeNotSupportedWithFunctionTypeException(variableType, functionType);
                        };
                    } else {
                        throw createFunctionTypeNotSupportedException(functionType);
                    }
                    factorHolder.addFactor(new SingleVariableLfSensitivityFactor<>(factorIndex[0], variableId,
                            functionId, functionElement, functionType, variableElement, variableType, contingencyContext));
                }
            }
            factorIndex[0]++;
        });
        return factorHolder;
    }

    protected static LfElement findBusTargetVoltageVariableElement(Network network, String variableId, boolean breakers,
                                                                   LfNetwork lfNetwork) {
        checkRegulatingTerminal(network, variableId);
        Terminal regulatingTerminal = Networks.getEquipmentRegulatingTerminal(network, variableId).orElseThrow(); // this cannot fail because it is checked in checkRegulatingTerminal
        Bus regulatedBus = Networks.getBus(regulatingTerminal, breakers);
        return regulatedBus != null ? lfNetwork.getBusById(regulatedBus.getId()) : null;
    }

    private static boolean isActivePowerFunctionType(SensitivityFunctionType functionType) {
        return functionType == SensitivityFunctionType.BRANCH_ACTIVE_POWER_1
                || functionType == SensitivityFunctionType.BRANCH_ACTIVE_POWER_2
                || functionType == SensitivityFunctionType.BRANCH_ACTIVE_POWER_3;
    }

    private static boolean isReactivePowerFunctionType(SensitivityFunctionType functionType) {
        return functionType == SensitivityFunctionType.BRANCH_REACTIVE_POWER_1
                || functionType == SensitivityFunctionType.BRANCH_REACTIVE_POWER_2
                || functionType == SensitivityFunctionType.BRANCH_REACTIVE_POWER_3;
    }

    private static boolean isCurrentFunctionType(SensitivityFunctionType functionType) {
        return functionType == SensitivityFunctionType.BRANCH_CURRENT_1
                || functionType == SensitivityFunctionType.BRANCH_CURRENT_2
                || functionType == SensitivityFunctionType.BRANCH_CURRENT_3;
    }

    protected Pair<Boolean, Boolean> hasBusTargetVoltage(SensitivityFactorReader factorReader, Network network) {
        // Left value if we find a BUS_TARGET_VOLTAGE factor and right value if it is linked to a transformer.
        AtomicBoolean hasBusTargetVoltage = new AtomicBoolean(false);
        AtomicBoolean hasTransformerBusTargetVoltage = new AtomicBoolean(false);
        factorReader.read((functionType, functionId, variableType, variableId, variableSet, contingencyContext) -> {
            if (variableType == SensitivityVariableType.BUS_TARGET_VOLTAGE) {
                hasBusTargetVoltage.set(true);
                Identifiable<?> equipment = network.getIdentifiable(variableId);
                if (equipment instanceof TwoWindingsTransformer || equipment instanceof ThreeWindingsTransformer) {
                    hasTransformerBusTargetVoltage.set(true);
                }
            }
        });
        return Pair.of(hasBusTargetVoltage.get(), hasTransformerBusTargetVoltage.get());
    }

    protected static boolean isDistributedSlackOnGenerators(DcLoadFlowParameters lfParameters) {
        return lfParameters.isDistributedSlack()
                && (lfParameters.getBalanceType() == LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX
                || lfParameters.getBalanceType() == LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P);
    }

    protected static boolean isDistributedSlackOnLoads(DcLoadFlowParameters lfParameters) {
        return lfParameters.isDistributedSlack()
                && (lfParameters.getBalanceType() == LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD
                || lfParameters.getBalanceType() == LoadFlowParameters.BalanceType.PROPORTIONAL_TO_CONFORM_LOAD);
    }

    /**
     * Base value for per-uniting, depending on the function type
     */
    private static <V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> double getFunctionBaseValue(LfSensitivityFactor<V, E> factor) {
        return switch (factor.getFunctionType()) {
            case BRANCH_ACTIVE_POWER_1, BRANCH_ACTIVE_POWER_2, BRANCH_ACTIVE_POWER_3,
                    BRANCH_REACTIVE_POWER_1, BRANCH_REACTIVE_POWER_2, BRANCH_REACTIVE_POWER_3, BUS_REACTIVE_POWER
                    -> PerUnit.SB;
            case BRANCH_CURRENT_1, BRANCH_CURRENT_3 -> {
                LfBranch branch = (LfBranch) factor.getFunctionElement();
                yield PerUnit.ib(branch.getBus1().getNominalV());
            }
            case BRANCH_CURRENT_2 -> {
                LfBranch branch2 = (LfBranch) factor.getFunctionElement();
                yield branch2 instanceof LfLegBranch ? PerUnit.ib(branch2.getBus1().getNominalV()) :
                                                       PerUnit.ib(branch2.getBus2().getNominalV());
            }
            case BUS_VOLTAGE -> ((LfBus) factor.getFunctionElement()).getNominalV();
            default -> throw new UnsupportedOperationException("Function type not supported: " + factor.getFunctionType());
        };
    }

    /**
     * Base value for per-uniting, depending on the variable type
     */
    private static <V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> double getVariableBaseValue(LfSensitivityFactor<V, E> factor) {
        switch (factor.getVariableType()) {
            case HVDC_LINE_ACTIVE_POWER, INJECTION_ACTIVE_POWER, INJECTION_REACTIVE_POWER:
                return PerUnit.SB;
            case TRANSFORMER_PHASE, TRANSFORMER_PHASE_1, TRANSFORMER_PHASE_2, TRANSFORMER_PHASE_3:
                return 1; //TODO: radians ?
            case BUS_TARGET_VOLTAGE:
                LfBus bus = (LfBus) ((SingleVariableLfSensitivityFactor<V, E>) factor).getVariableElement();
                return bus.getNominalV();
            default:
                throw new IllegalArgumentException("Unknown variable type " + factor.getVariableType());
        }
    }

    /**
     * Unscales sensitivity value from per-unit, according to its type.
     */
    protected static <V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> double unscaleSensitivity(LfSensitivityFactor<V, E> factor, double sensitivity) {
        return sensitivity * getFunctionBaseValue(factor) / getVariableBaseValue(factor);
    }

    /**
     * Unscales function value from per-unit, according to its type.
     */
    protected static <V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> double unscaleFunction(LfSensitivityFactor<V, E> factor, double value) {
        return value * getFunctionBaseValue(factor);
    }

    protected static int getLegNumber(SensitivityFunctionType type) {
        return type.getSide().orElseThrow(() -> new PowsyblException("Cannot convert function type " + type + " to a leg number"));
    }

    protected static int getLegNumber(SensitivityVariableType type) {
        return type.getSide().orElseThrow(() -> new PowsyblException("Cannot convert variable type " + type + " to a leg number"));
    }

    public abstract void analyse(Network network, List<PropagatedContingency> contingencies, List<SensitivityVariableSet> variableSets, SensitivityFactorReader factorReader,
                                 SensitivityResultWriter resultWriter, ReportNode reportNode, LfTopoConfig topoConfig);

    protected static boolean filterSensitivityValue(double value, SensitivityVariableType variable, SensitivityFunctionType function, SensitivityAnalysisParameters parameters) {
        switch (variable) {
            case INJECTION_ACTIVE_POWER, HVDC_LINE_ACTIVE_POWER:
                return isFlowFunction(function) && Math.abs(value) < parameters.getFlowFlowSensitivityValueThreshold();
            case INJECTION_REACTIVE_POWER:
                // We consider that the magnitude change of reactive injection to V is of the same magnitude as other usages of FlowVoltage threshold
                // (used for dV/dQ, dV/dI) and re-use this threshold
                return function == SensitivityFunctionType.BUS_VOLTAGE && Math.abs(value) < parameters.getFlowVoltageSensitivityValueThreshold();
            case TRANSFORMER_PHASE, TRANSFORMER_PHASE_1, TRANSFORMER_PHASE_2, TRANSFORMER_PHASE_3:
                return isFlowFunction(function) && Math.abs(value) < parameters.getAngleFlowSensitivityValueThreshold();
            case BUS_TARGET_VOLTAGE:
                return filterBusTargetVoltageVariable(value, function, parameters);
            default:
                return false;
        }
    }

    protected static boolean filterBusTargetVoltageVariable(double value, SensitivityFunctionType function,
                                                            SensitivityAnalysisParameters parameters) {
        return switch (function) {
            // We consider that the voltage change to current is approximately of the same magnitude as the voltage change to reactive power
            // and use the same threshold for filtering
            case BRANCH_CURRENT_1, BRANCH_CURRENT_2, BRANCH_CURRENT_3, BUS_REACTIVE_POWER -> Math.abs(value) < parameters.getFlowVoltageSensitivityValueThreshold();
            case BUS_VOLTAGE -> Math.abs(value) < parameters.getVoltageVoltageSensitivityValueThreshold();
            default -> false;
        };
    }

    protected static boolean isFlowFunction(SensitivityFunctionType function) {
        return switch (function) {
            case BRANCH_ACTIVE_POWER_1, BRANCH_ACTIVE_POWER_2, BRANCH_ACTIVE_POWER_3, BRANCH_CURRENT_1, BRANCH_CURRENT_2, BRANCH_CURRENT_3 -> true;
            default -> false;
        };
    }
}
