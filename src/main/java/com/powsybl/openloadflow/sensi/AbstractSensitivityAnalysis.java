/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.sensi;

import com.powsybl.commons.PowsyblException;
import com.powsybl.contingency.ContingencyContext;
import com.powsybl.iidm.network.*;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.math.matrix.Matrix;
import com.powsybl.math.matrix.MatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.equations.Equation;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.EquationTerm;
import com.powsybl.openloadflow.equations.Quantity;
import com.powsybl.openloadflow.graph.GraphDecrementalConnectivity;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.network.impl.HvdcConverterStations;
import com.powsybl.openloadflow.network.impl.LfDanglingLineBus;
import com.powsybl.openloadflow.network.util.ActivePowerDistribution;
import com.powsybl.openloadflow.network.util.ParticipatingElement;
import com.powsybl.openloadflow.util.PropagatedContingency;
import org.apache.commons.lang3.NotImplementedException;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 * @author Gael Macherel <gael.macherel at artelys.com>
 */
public abstract class AbstractSensitivityAnalysis<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> {

    protected static final Logger LOGGER = LoggerFactory.getLogger(AbstractSensitivityAnalysis.class);

    protected final MatrixFactory matrixFactory;

    protected final Supplier<GraphDecrementalConnectivity<LfBus>> connectivityProvider;

    protected AbstractSensitivityAnalysis(MatrixFactory matrixFactory, Supplier<GraphDecrementalConnectivity<LfBus>> connectivityProvider) {
        this.matrixFactory = Objects.requireNonNull(matrixFactory);
        this.connectivityProvider = Objects.requireNonNull(connectivityProvider);
    }

    protected static Terminal getEquipmentRegulatingTerminal(Network network, String equipmentId) {
        Generator generator = network.getGenerator(equipmentId);
        if (generator != null) {
            return generator.getRegulatingTerminal();
        }
        StaticVarCompensator staticVarCompensator = network.getStaticVarCompensator(equipmentId);
        if (staticVarCompensator != null) {
            return staticVarCompensator.getRegulatingTerminal();
        }
        TwoWindingsTransformer t2wt = network.getTwoWindingsTransformer(equipmentId);
        if (t2wt != null) {
            RatioTapChanger rtc = t2wt.getRatioTapChanger();
            if (rtc != null) {
                return rtc.getRegulationTerminal();
            }
        }
        ThreeWindingsTransformer t3wt = network.getThreeWindingsTransformer(equipmentId);
        if (t3wt != null) {
            for (ThreeWindingsTransformer.Leg leg : t3wt.getLegs()) {
                RatioTapChanger rtc = leg.getRatioTapChanger();
                if (rtc != null && rtc.isRegulating()) {
                    return rtc.getRegulationTerminal();
                }
            }
        }
        ShuntCompensator shuntCompensator = network.getShuntCompensator(equipmentId);
        if (shuntCompensator != null) {
            return shuntCompensator.getRegulatingTerminal();
        }
        VscConverterStation vsc = network.getVscConverterStation(equipmentId);
        if (vsc != null) {
            return vsc.getTerminal(); // local regulation only
        }
        return null;
    }

    interface LfSensitivityFactor<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> {

        enum Status {
            VALID,
            SKIP,
            SKIP_ONLY_VARIABLE,
            ZERO
        }

        Object getContext();

        String getVariableId();

        SensitivityVariableType getVariableType();

        LfElement getFunctionElement();

        SensitivityFunctionType getFunctionType();

        ContingencyContext getContingencyContext();

        EquationTerm<V, E> getFunctionEquationTerm();

        Double getPredefinedResultSensi();

        Double getPredefinedResultRef();

        void setPredefinedResultSensi(Double predefinedResult);

        void setPredefinedResultRef(Double predefinedResult);

        double getFunctionReference();

        void setFunctionReference(double functionReference);

        double getBaseSensitivityValue();

        void setBaseCaseSensitivityValue(double baseCaseSensitivityValue);

        Status getStatus();

        void setStatus(Status status);

        boolean areVariableAndFunctionDisconnected(GraphDecrementalConnectivity<LfBus> connectivity);

        boolean isConnectedToComponent(Set<LfBus> connectedComponent);

        boolean isReferenceConnectedToComponent(Set<LfBus> connectedComponent);

        SensitivityFactorGroup<V, E> getGroup();

        void setGroup(SensitivityFactorGroup<V, E> group);
    }

    abstract static class AbstractLfSensitivityFactor<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> implements LfSensitivityFactor<V, E> {

        // Wrap factors in specific class to have instant access to their branch and their equation term
        private final Object context;

        private final String variableId;

        protected final LfElement functionElement;

        protected final SensitivityFunctionType functionType;

        protected final SensitivityVariableType variableType;

        protected final ContingencyContext contingencyContext;

        private Double predefinedResultSensi = null;

        private Double predefinedResultRef = null;

        private double functionReference = 0d;

        private double baseCaseSensitivityValue = Double.NaN; // the sensitivity value on pre contingency network, that needs to be recomputed if the stack distribution change

        protected Status status = Status.VALID;

        protected SensitivityFactorGroup<V, E> group;

        protected AbstractLfSensitivityFactor(Object context, String variableId,
                                           LfElement functionElement, SensitivityFunctionType functionType,
                                           SensitivityVariableType variableType, ContingencyContext contingencyContext) {
            this.context = context;
            this.variableId = Objects.requireNonNull(variableId);
            this.functionElement = functionElement;
            this.functionType = Objects.requireNonNull(functionType);
            this.variableType = Objects.requireNonNull(variableType);
            this.contingencyContext = Objects.requireNonNull(contingencyContext);
            if (functionElement == null) {
                status = Status.ZERO;
            }
        }

        @Override
        public Object getContext() {
            return context;
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
        public EquationTerm<V, E> getFunctionEquationTerm() {
            switch (functionType) {
                case BRANCH_ACTIVE_POWER:
                    return (EquationTerm<V, E>) ((LfBranch) functionElement).getP1();
                case BRANCH_CURRENT:
                    return (EquationTerm<V, E>) ((LfBranch) functionElement).getI1();
                case BUS_VOLTAGE:
                    return (EquationTerm<V, E>) ((LfBus) functionElement).getV();
                default:
                    throw new PowsyblException("Function type " + functionType + " is not implement.");
            }
        }

        @Override
        public Double getPredefinedResultSensi() {
            return predefinedResultSensi;
        }

        @Override
        public Double getPredefinedResultRef() {
            return predefinedResultRef;
        }

        @Override
        public void setPredefinedResultSensi(Double predefinedResult) {
            this.predefinedResultSensi = predefinedResult;
        }

        @Override
        public void setPredefinedResultRef(Double predefinedResult) {
            this.predefinedResultRef = predefinedResult;
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
        public double getBaseSensitivityValue() {
            return baseCaseSensitivityValue;
        }

        @Override
        public void setBaseCaseSensitivityValue(double baseCaseSensitivityValue) {
            this.baseCaseSensitivityValue = baseCaseSensitivityValue;
        }

        @Override
        public Status getStatus() {
            return status;
        }

        @Override
        public void setStatus(Status status) {
            this.status = status;
        }

        protected boolean areElementsDisconnected(LfElement functionElement, LfElement variableElement, GraphDecrementalConnectivity<LfBus> connectivity) {
            if (functionElement.getType() == ElementType.BUS && variableElement.getType() == ElementType.BUS) {
                return areBusAndBusDisconnected(connectivity, (LfBus) functionElement, (LfBus) variableElement);
            } else if (functionElement.getType() == ElementType.BRANCH && variableElement.getType() == ElementType.BUS) {
                return areBranchAndBusDisconnected(connectivity, (LfBranch) functionElement, (LfBus) variableElement);
            } else if (functionElement.getType() == ElementType.BRANCH && variableElement.getType() == ElementType.BRANCH) {
                return areBranchAndBranchDisconnected(connectivity, (LfBranch) functionElement, (LfBranch) variableElement);
            }
            throw new PowsyblException("Combination of function type and variable type is not implemented.");
        }

        static boolean areBranchAndBranchDisconnected(GraphDecrementalConnectivity<LfBus> connectivity, LfBranch functionBranch, LfBranch variableBranch) {
            return connectivity.getComponentNumber(variableBranch.getBus1()) != connectivity.getComponentNumber(functionBranch.getBus1())
                    || connectivity.getComponentNumber(variableBranch.getBus1()) != connectivity.getComponentNumber(functionBranch.getBus2());
        }

        static boolean areBranchAndBusDisconnected(GraphDecrementalConnectivity<LfBus> connectivity, LfBranch functionBranch, LfBus variableBus) {
            return connectivity.getComponentNumber(functionBranch.getBus1()) != connectivity.getComponentNumber(variableBus)
                    || connectivity.getComponentNumber(functionBranch.getBus2()) != connectivity.getComponentNumber(variableBus);
        }

        static boolean areBusAndBusDisconnected(GraphDecrementalConnectivity<LfBus> connectivity, LfBus functionBus, LfBus variableBus) {
            return connectivity.getComponentNumber(functionBus) != connectivity.getComponentNumber(variableBus);
        }

        protected boolean isElementConnectedToComponent(LfElement element, Set<LfBus> component) {
            if (element instanceof LfBus) {
                return component.contains(element);
            } else if (element instanceof LfBranch) {
                return component.contains(((LfBranch) element).getBus1()) && component.contains(((LfBranch) element).getBus2());
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

    static class SingleVariableLfSensitivityFactor<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> extends AbstractLfSensitivityFactor<V, E> {

        private final LfElement variableElement;

        SingleVariableLfSensitivityFactor(Object context, String variableId,
                                          LfElement functionElement, SensitivityFunctionType functionType,
                                          LfElement variableElement, SensitivityVariableType variableType,
                                          ContingencyContext contingencyContext) {
            super(context, variableId, functionElement, functionType, variableType, contingencyContext);
            this.variableElement = variableElement;
            if (variableElement == null) {
                status = functionElement == null ? Status.SKIP : Status.SKIP_ONLY_VARIABLE;
            }
        }

        public LfElement getVariableElement() {
            return variableElement;
        }

        public Equation<V, E> getVariableEquation() {
            switch (variableType) {
                case TRANSFORMER_PHASE:
                    LfBranch lfBranch = (LfBranch) variableElement;
                    return ((EquationTerm<V, E>) lfBranch.getA1()).getEquation();
                case BUS_TARGET_VOLTAGE:
                    LfBus lfBus = (LfBus) variableElement;
                    return ((EquationTerm<V, E>) lfBus.getV()).getEquation();
                default:
                    return null;
            }
        }

        @Override
        public boolean areVariableAndFunctionDisconnected(GraphDecrementalConnectivity<LfBus> connectivity) {
            return areElementsDisconnected(functionElement, variableElement, connectivity);
        }

        @Override
        public boolean isConnectedToComponent(Set<LfBus> connectedComponent) {
            return isElementConnectedToComponent(variableElement, connectedComponent);
        }

        @Override
        public boolean isReferenceConnectedToComponent(Set<LfBus> connectedComponent) {
            return isElementConnectedToComponent(functionElement, connectedComponent);
        }
    }

    static class MultiVariablesLfSensitivityFactor<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> extends AbstractLfSensitivityFactor<V, E> {

        private final Map<LfElement, Double> weightedVariableElements;

        MultiVariablesLfSensitivityFactor(Object context, String variableId,
                                          LfElement functionElement, SensitivityFunctionType functionType,
                                          Map<LfElement, Double> weightedVariableElements, SensitivityVariableType variableType,
                                          ContingencyContext contingencyContext) {
            super(context, variableId, functionElement, functionType, variableType, contingencyContext);
            this.weightedVariableElements = weightedVariableElements;
            if (weightedVariableElements.isEmpty()) {
                status = functionElement == null ? Status.SKIP : Status.SKIP_ONLY_VARIABLE;
            }
        }

        public Map<LfElement, Double> getWeightedVariableElements() {
            return weightedVariableElements;
        }

        public Collection<LfElement> getVariableElements() {
            return weightedVariableElements.keySet();
        }

        @Override
        public boolean areVariableAndFunctionDisconnected(GraphDecrementalConnectivity<LfBus> connectivity) {
            for (LfElement variableElement : getVariableElements()) {
                if (!areElementsDisconnected(functionElement, variableElement, connectivity)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean isConnectedToComponent(Set<LfBus> connectedComponent) {
            if (!isElementConnectedToComponent(functionElement, connectedComponent)) {
                return false;
            }
            for (LfElement lfElement : getVariableElements()) {
                if (isElementConnectedToComponent(lfElement, connectedComponent)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean isReferenceConnectedToComponent(Set<LfBus> connectedComponent) {
            return isElementConnectedToComponent(functionElement, connectedComponent);
        }
    }

    interface SensitivityFactorGroup<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> {

        List<LfSensitivityFactor<V, E>> getFactors();

        int getIndex();

        void setIndex(int index);

        void addFactor(LfSensitivityFactor<V, E> factor);

        void fillRhs(Matrix rhs, Map<LfBus, Double> participationByBus);
    }

    abstract static class AbstractSensitivityFactorGroup<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> implements SensitivityFactorGroup<V, E> {

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
    }

    static class SingleVariableFactorGroup<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> extends AbstractSensitivityFactorGroup<V, E> {

        private final LfElement variableElement;

        private final Equation<V, E> variableEquation;

        SingleVariableFactorGroup(LfElement variableElement, Equation<V, E> variableEquation, SensitivityVariableType variableType) {
            super(variableType);
            this.variableElement = Objects.requireNonNull(variableElement);
            this.variableEquation = variableEquation;
        }

        @Override
        public void fillRhs(Matrix rhs, Map<LfBus, Double> participationByBus) {
            switch (variableType) {
                case TRANSFORMER_PHASE:
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
                case BUS_TARGET_VOLTAGE:
                    if (variableEquation.isActive()) {
                        rhs.set(variableEquation.getColumn(), getIndex(), 1d);
                    }
                    break;
                default:
                    throw new NotImplementedException("Variable type " + variableType + " is not implemented");
            }
        }
    }

    static class MultiVariablesFactorGroup<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> extends AbstractSensitivityFactorGroup<V, E> {

        Map<LfElement, Double> variableElements;
        Map<LfElement, Double> mainComponentWeights;

        MultiVariablesFactorGroup(Map<LfElement, Double> variableElements, SensitivityVariableType variableType) {
            super(variableType);
            this.variableElements = variableElements;
            this.mainComponentWeights = variableElements;
        }

        public Map<LfElement, Double> getVariableElements() {
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
                        double injection = lfBusAndParticipationFactor.getValue() * balanceDiff; // adapt the sign of the compensation depending on the injection
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
                    throw new NotImplementedException("Variable type " + variableType + " is not implemented");
            }
        }

        boolean updateConnectivityWeights(Set<LfBus> nonConnectedBuses) {
            mainComponentWeights = variableElements.entrySet().stream()
                .filter(entry -> !nonConnectedBuses.contains((LfBus) entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            return mainComponentWeights.size() != variableElements.size();
        }
    }

    protected List<SensitivityFactorGroup<V, E>> createFactorGroups(List<LfSensitivityFactor<V, E>> factors) {
        Map<Pair<SensitivityVariableType, String>, SensitivityFactorGroup<V, E>> groupIndexedById = new LinkedHashMap<>(factors.size());
        // index factors by variable config
        for (LfSensitivityFactor<V, E> factor : factors) {
            if (factor.getStatus() == LfSensitivityFactor.Status.SKIP) {
                continue;
            }
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

        return new ArrayList<>(groupIndexedById.values());
    }

    protected List<ParticipatingElement> getParticipatingElements(Collection<LfBus> buses, LoadFlowParameters loadFlowParameters, OpenLoadFlowParameters openLoadFlowParameters) {
        ActivePowerDistribution.Step step = ActivePowerDistribution.getStep(loadFlowParameters.getBalanceType(), openLoadFlowParameters.isLoadPowerFactorConstant());
        List<ParticipatingElement> participatingElements = step.getParticipatingElements(buses);
        ParticipatingElement.normalizeParticipationFactors(participatingElements, "bus");
        return participatingElements;
    }

    protected DenseMatrix initFactorsRhs(EquationSystem<V, E> equationSystem, List<SensitivityFactorGroup<V, E>> factorsGroups, Map<LfBus, Double> participationByBus) {
        DenseMatrix rhs = new DenseMatrix(equationSystem.getSortedEquationsToSolve().size(), factorsGroups.size());
        fillRhsSensitivityVariable(factorsGroups, rhs, participationByBus);
        return rhs;
    }

    protected void fillRhsSensitivityVariable(List<SensitivityFactorGroup<V, E>> factorGroups, Matrix rhs, Map<LfBus, Double> participationByBus) {
        for (SensitivityFactorGroup<V, E> factorGroup : factorGroups) {
            factorGroup.fillRhs(rhs, participationByBus);
        }
    }

    public void cutConnectivity(LfNetwork lfNetwork, GraphDecrementalConnectivity<LfBus> connectivity, Collection<String> breakingConnectivityCandidates) {
        breakingConnectivityCandidates.stream()
            .map(lfNetwork::getBranchById)
            .forEach(lfBranch -> connectivity.cut(lfBranch.getBus1(), lfBranch.getBus2()));
    }

    protected void setPredefinedResults(Collection<LfSensitivityFactor<V, E>> lfFactors, Set<LfBus> connectedComponent,
                                        GraphDecrementalConnectivity<LfBus> connectivity) {
        for (LfSensitivityFactor<V, E> factor : lfFactors) {
            if (factor.getStatus() == LfSensitivityFactor.Status.VALID) {
                // check if the factor function and variable are in different connected components
                if (factor.areVariableAndFunctionDisconnected(connectivity)) {
                    factor.setPredefinedResultSensi(0d);
                    factor.setPredefinedResultRef(0d);
                } else if (!factor.isConnectedToComponent(connectedComponent)) {
                    // works for sensitivity and function reference
                    factor.setPredefinedResultSensi(Double.NaN);
                    factor.setPredefinedResultRef(Double.NaN);
                }
            } else if (factor.getStatus() == LfSensitivityFactor.Status.SKIP_ONLY_VARIABLE) {
                factor.setPredefinedResultSensi(0d);
                if (!factor.isReferenceConnectedToComponent(connectedComponent)) {
                    factor.setPredefinedResultSensi(Double.NaN);
                }
            }
        }
    }

    protected boolean rescaleGlsk(List<SensitivityFactorGroup<V, E>> factorGroups, Set<LfBus> nonConnectedBuses) {
        boolean rescaled = false;
        // compute the corresponding injection (with participation) for each factor
        for (SensitivityFactorGroup<V, E> factorGroup : factorGroups) {
            if (factorGroup instanceof MultiVariablesFactorGroup) {
                MultiVariablesFactorGroup<V, E> multiVariablesFactorGroup = (MultiVariablesFactorGroup<V, E>) factorGroup;
                rescaled |= multiVariablesFactorGroup.updateConnectivityWeights(nonConnectedBuses);
            }
        }
        return rescaled;
    }

    protected void writeSkippedFactors(Collection<LfSensitivityFactor<V, E>> lfFactors, SensitivityValueWriter valueWriter) {
        List<LfSensitivityFactor<V, E>> skippedFactors = lfFactors.stream().filter(factor -> factor.getStatus() == LfSensitivityFactor.Status.SKIP).collect(Collectors.toList());

        skippedFactors.forEach(factor -> valueWriter.write(factor.getContext(), null, -1, Double.NaN, Double.NaN));

        Set<String> skippedVariables = skippedFactors.stream().map(LfSensitivityFactor::getVariableId).collect(Collectors.toSet());
        if (!skippedVariables.isEmpty()) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn("Skipping all factors with variables: '{}', as they cannot be found in the network",
                        String.join(", ", skippedVariables));
            }
        }
    }

    public void checkContingencies(Network network, LfNetwork lfNetwork, List<PropagatedContingency> contingencies) {
        for (PropagatedContingency contingency : contingencies) {
            // Elements have already been checked and found in PropagatedContingency, so there is no need to
            // check them again
            Set<String> branchesToRemove = new HashSet<>(); // branches connected to one side, or switches
            for (String branchId : contingency.getBranchIdsToOpen()) {
                LfBranch lfBranch = lfNetwork.getBranchById(branchId);
                if (lfBranch == null) {
                    branchesToRemove.add(branchId); // disconnected branch
                    continue;
                }
                if (lfBranch.getBus2() == null || lfBranch.getBus1() == null) {
                    branchesToRemove.add(branchId); // branch connected only on one side
                }
            }
            contingency.getBranchIdsToOpen().removeAll(branchesToRemove);
            if (contingency.getBranchIdsToOpen().isEmpty() && contingency.getHvdcIdsToOpen().isEmpty() && contingency.getGeneratorIdsToLose().isEmpty()) {
                LOGGER.warn("Contingency {} has no impact", contingency.getContingency().getId());
            }
        }
    }

    public void checkLoadFlowParameters(LoadFlowParameters lfParameters) {
        if (!lfParameters.getBalanceType().equals(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX)
                && !lfParameters.getBalanceType().equals(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD)
                && !lfParameters.getBalanceType().equals(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P)) {
            throw new UnsupportedOperationException("Unsupported balance type mode: " + lfParameters.getBalanceType());
        }
    }

    private static Injection<?> getInjection(Network network, String injectionId) {
        Injection<?> injection = network.getGenerator(injectionId);
        if (injection == null) {
            injection = network.getLoad(injectionId);
        }
        if (injection == null) {
            injection = network.getDanglingLine(injectionId);
        }
        if (injection == null) {
            injection = network.getLccConverterStation(injectionId);
        }
        if (injection == null) {
            injection = network.getVscConverterStation(injectionId);
        }

        if (injection == null) {
            throw new PowsyblException("Injection '" + injectionId + "' not found");
        }

        return injection;
    }

    protected static String getInjectionBusId(Network network, String injectionId) {
        Injection<?> injection = getInjection(network, injectionId);
        Bus bus = injection.getTerminal().getBusView().getBus();
        if (bus == null) {
            return null;
        }
        if (injection instanceof DanglingLine) {
            return LfDanglingLineBus.getId((DanglingLine) injection);
        } else {
            return bus.getId();
        }
    }

    private static void checkBranch(Network network, String branchId) {
        Branch branch = network.getBranch(branchId);
        if (branch == null) {
            throw new PowsyblException("Branch '" + branchId + "' not found");
        }
    }

    private static void checkBus(Network network, String busId, Map<String, Bus> busCache) {
        if (busCache.isEmpty()) {
            network.getBusView()
                .getBusStream()
                .forEach(bus -> busCache.put(bus.getId(), bus));
        }
        Bus bus = busCache.get(busId);
        if (bus == null) {
            throw new PowsyblException("Bus '" + busId + "' not found");
        }
    }

    private static void checkPhaseShifter(Network network, String transformerId) {
        TwoWindingsTransformer twt = network.getTwoWindingsTransformer(transformerId);
        if (twt == null) {
            throw new PowsyblException("Two windings transformer '" + transformerId + "' not found");
        }
        if (twt.getPhaseTapChanger() == null) {
            throw new PowsyblException("Two windings transformer '" + transformerId + "' is not a phase shifter");
        }
    }

    private static void checkRegulatingTerminal(Network network, String equipmentId) {
        Terminal terminal = getEquipmentRegulatingTerminal(network, equipmentId);
        if (terminal == null) {
            throw new PowsyblException("Regulating terminal for '" + equipmentId + "' not found");
        }
    }

    static class SensitivityFactorHolder<V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> {

        private final Map<String, List<LfSensitivityFactor<V, E>>> additionalFactorsPerContingency = new LinkedHashMap<>();
        private final List<LfSensitivityFactor<V, E>> additionalFactorsNoContingency = new ArrayList<>();
        private final List<LfSensitivityFactor<V, E>> commonFactors = new ArrayList<>();

        public List<LfSensitivityFactor<V, E>> getAllFactors() {
            List<LfSensitivityFactor<V, E>> allFactors = new ArrayList<>(commonFactors);
            allFactors.addAll(additionalFactorsNoContingency);
            allFactors.addAll(additionalFactorsPerContingency.values().stream().flatMap(List::stream).collect(Collectors.toCollection(LinkedHashSet::new)));
            return allFactors;
        }

        public List<LfSensitivityFactor<V, E>> getFactorsForContingency(String contingencyId) {
            return Stream.concat(commonFactors.stream(), additionalFactorsPerContingency.getOrDefault(contingencyId, Collections.emptyList()).stream())
                .collect(Collectors.toList());
        }

        public List<LfSensitivityFactor<V, E>> getFactorsForContingencies(List<String> contingenciesIds) {
            return Stream.concat(commonFactors.stream(),
                                 contingenciesIds.stream().flatMap(contingencyId -> additionalFactorsPerContingency.getOrDefault(contingencyId, Collections.emptyList()).stream()))
                    .collect(Collectors.toList());
        }

        public List<LfSensitivityFactor<V, E>> getFactorsForBaseNetwork() {
            return Stream.concat(commonFactors.stream(), additionalFactorsNoContingency.stream())
                .collect(Collectors.toList());
        }

        public void addFactor(LfSensitivityFactor<V, E> factor) {
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

    public SensitivityFactorHolder<V, E> readAndCheckFactors(Network network, Map<String, SensitivityVariableSet> variableSetsById,
                                                       SensitivityFactorReader factorReader, LfNetwork lfNetwork) {
        final SensitivityFactorHolder<V, E> factorHolder = new SensitivityFactorHolder<>();

        final Map<String, Map<LfElement, Double>> injectionBusesByVariableId = new LinkedHashMap<>();
        final Map<String, Bus> busCache = new HashMap<>();
        factorReader.read((factorContext, functionType, functionId, variableType, variableId, variableSet, contingencyContext) -> {
            if (variableSet) {
                if (functionType == SensitivityFunctionType.BRANCH_ACTIVE_POWER) {
                    checkBranch(network, functionId);
                    LfBranch functionElement = lfNetwork.getBranchById(functionId);
                    if (variableType == SensitivityVariableType.INJECTION_ACTIVE_POWER) {
                        Map<LfElement, Double> injectionLfBuses = injectionBusesByVariableId.get(variableId);
                        if (injectionLfBuses == null) {
                            injectionLfBuses = new LinkedHashMap<>();
                            injectionBusesByVariableId.put(variableId, injectionLfBuses);
                            SensitivityVariableSet set = variableSetsById.get(variableId);
                            if (set == null) {
                                throw new PowsyblException("Variable set '" + variableId + "' not found");
                            }
                            List<String> skippedInjection = new ArrayList<>(set.getVariables().size());
                            for (WeightedSensitivityVariable variable : set.getVariables()) {
                                String injectionBusId = getInjectionBusId(network, variable.getId());
                                LfBus injectionLfBus = injectionBusId != null ? lfNetwork.getBusById(injectionBusId) : null;
                                if (injectionLfBus == null) {
                                    skippedInjection.add(variable.getId());
                                    continue;
                                }
                                injectionLfBuses.put(injectionLfBus, injectionLfBuses.getOrDefault(injectionLfBus, 0d) + variable.getWeight());
                            }
                            if (!skippedInjection.isEmpty()) {
                                if (LOGGER.isWarnEnabled()) {
                                    LOGGER.warn("Injections {} cannot be found for glsk {} and will be ignored", String.join(", ", skippedInjection), variableId);
                                }
                            }
                        }
                        factorHolder.addFactor(new MultiVariablesLfSensitivityFactor<>(factorContext, variableId,
                                    functionElement, functionType,
                                    injectionLfBuses, variableType, contingencyContext));
                    } else {
                        throw new PowsyblException("Variable type " + variableType + " not supported with function type " + functionType);
                    }
                } else {
                    throw new PowsyblException("Function type " + functionType + " not supported");
                }
            } else {
                if (functionType == SensitivityFunctionType.BRANCH_ACTIVE_POWER && variableType == SensitivityVariableType.HVDC_LINE_ACTIVE_POWER) {
                    checkBranch(network, functionId);
                    LfBranch branch = lfNetwork.getBranchById(functionId);
                    LfElement functionElement = branch != null && branch.getBus1() != null && branch.getBus2() != null ? branch : null;

                    HvdcLine hvdcLine = network.getHvdcLine(variableId);
                    if (hvdcLine == null) {
                        throw new PowsyblException("HVDC line '" + variableId + "' cannot be found in the network.");
                    }
                    LfBus bus1 = lfNetwork.getBusById(hvdcLine.getConverterStation1().getTerminal().getBusView().getBus().getId());
                    LfBus bus2 = lfNetwork.getBusById(hvdcLine.getConverterStation2().getTerminal().getBusView().getBus().getId());

                    // corresponds to an augmentation of +1 on the active power setpoint on each side on the HVDC line
                    // => we create a multi (bi) variables factor
                    Map<LfElement, Double> injectionLfBuses = new HashMap<>(2);
                    if (bus1 != null) {
                        // VSC injection follow here a load sign convention as LCC injection.
                        // FIXME: for LCC, Q changes when P changes
                        injectionLfBuses.put(bus1, (hvdcLine.getConverterStation1() instanceof VscConverterStation ? -1 : 1)
                                * HvdcConverterStations.getActivePowerSetpointMultiplier(hvdcLine.getConverterStation1()));
                    }
                    if (bus2 != null) {
                        // VSC injection follow here a load sign convention as LCC injection.
                        // FIXME: for LCC, Q changes when P changes
                        injectionLfBuses.put(bus2, (hvdcLine.getConverterStation2() instanceof VscConverterStation ? -1 : 1)
                                * HvdcConverterStations.getActivePowerSetpointMultiplier(hvdcLine.getConverterStation2()));
                    }

                    factorHolder.addFactor(new MultiVariablesLfSensitivityFactor<>(factorContext, variableId,
                            functionElement, functionType, injectionLfBuses, variableType, contingencyContext));
                } else {
                    LfElement functionElement;
                    LfElement variableElement;
                    if (functionType == SensitivityFunctionType.BRANCH_ACTIVE_POWER) {
                        checkBranch(network, functionId);
                        LfBranch branch = lfNetwork.getBranchById(functionId);
                        functionElement = branch != null && branch.getBus1() != null && branch.getBus2() != null ? branch : null;
                        if (variableType == SensitivityVariableType.INJECTION_ACTIVE_POWER) {
                            String injectionBusId = getInjectionBusId(network, variableId);
                            variableElement = injectionBusId != null ? lfNetwork.getBusById(injectionBusId) : null;
                        } else if (variableType == SensitivityVariableType.TRANSFORMER_PHASE) {
                            checkPhaseShifter(network, variableId);
                            variableElement = lfNetwork.getBranchById(variableId);
                        } else {
                            throw new PowsyblException("Variable type " + variableType + " not supported with function type " + functionType);
                        }
                    } else if (functionType == SensitivityFunctionType.BRANCH_CURRENT) {
                        checkBranch(network, functionId);
                        LfBranch branch = lfNetwork.getBranchById(functionId);
                        functionElement = branch != null && branch.getBus1() != null && branch.getBus2() != null ? branch : null;
                        if (variableType == SensitivityVariableType.TRANSFORMER_PHASE) {
                            checkPhaseShifter(network, variableId);
                            variableElement = lfNetwork.getBranchById(variableId);
                        } else {
                            throw new PowsyblException("Variable type " + variableType + " not supported with function type " + functionType);
                        }
                    } else if (functionType == SensitivityFunctionType.BUS_VOLTAGE) {
                        checkBus(network, functionId, busCache);
                        functionElement = lfNetwork.getBusById(functionId);
                        if (variableType == SensitivityVariableType.BUS_TARGET_VOLTAGE) {
                            checkRegulatingTerminal(network, variableId);
                            Terminal regulatingTerminal = getEquipmentRegulatingTerminal(network, variableId);
                            assert regulatingTerminal != null; // this cannot fail because it is checked in checkRegulatingTerminal
                            Bus regulatedBus = regulatingTerminal.getBusView().getBus();
                            variableElement = regulatedBus != null ? lfNetwork.getBusById(regulatedBus.getId()) : null;
                        } else {
                            throw new PowsyblException("Variable type " + variableType + " not supported with function type " + functionType);
                        }
                    } else {
                        throw new PowsyblException("Function type " + functionType + " not supported");
                    }
                    factorHolder.addFactor(new SingleVariableLfSensitivityFactor<>(factorContext, variableId,
                            functionElement, functionType, variableElement, variableType, contingencyContext));
                }
            }
        });
        return factorHolder;
    }

    public boolean hasTransformerBusTargetVoltage(SensitivityFactorReader factorReader, Network network) {
        AtomicBoolean hasTransformerBusTargetVoltage = new AtomicBoolean();
        factorReader.read((factorContext, functionType, functionId, variableType, variableId, variableSet, contingencyContext) -> {
            if (variableType == SensitivityVariableType.BUS_TARGET_VOLTAGE) {
                Identifiable<?> equipment = network.getIdentifiable(variableId);
                if (equipment instanceof TwoWindingsTransformer || equipment instanceof ThreeWindingsTransformer) {
                    hasTransformerBusTargetVoltage.set(true);
                }
            }
        });
        return hasTransformerBusTargetVoltage.get();
    }

    public boolean isDistributedSlackOnGenerators(LoadFlowParameters lfParameters) {
        return lfParameters.isDistributedSlack()
                && (lfParameters.getBalanceType() == LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX
                || lfParameters.getBalanceType() == LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P);
    }

    public boolean isDistributedSlackOnLoads(LoadFlowParameters lfParameters) {
        return lfParameters.isDistributedSlack()
                &&  (lfParameters.getBalanceType() == LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD
                || lfParameters.getBalanceType() == LoadFlowParameters.BalanceType.PROPORTIONAL_TO_CONFORM_LOAD);
    }

    /**
     * Base value for per-uniting, depending on the function type
     */
    private static <V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> double getFunctionBaseValue(LfSensitivityFactor<V, E> factor) {
        switch (factor.getFunctionType()) {
            case BRANCH_ACTIVE_POWER:
                return PerUnit.SB;
            case BRANCH_CURRENT:
                LfBranch branch = (LfBranch) factor.getFunctionElement();
                return PerUnit.ib(branch.getBus1().getNominalV());
            case BUS_VOLTAGE:
                LfBus bus = (LfBus) factor.getFunctionElement();
                return bus.getNominalV();
            default:
                throw new IllegalArgumentException("Unknown function type " + factor.getFunctionType());
        }
    }

    /**
     * Base value for per-uniting, depending on the variable type
     */
    private static <V extends Enum<V> & Quantity, E extends Enum<E> & Quantity> double getVariableBaseValue(LfSensitivityFactor<V, E> factor) {
        switch (factor.getVariableType()) {
            case HVDC_LINE_ACTIVE_POWER:
            case INJECTION_ACTIVE_POWER:
                return PerUnit.SB;
            case TRANSFORMER_PHASE:
                return 1; //TODO: radians ?
            case BUS_TARGET_VOLTAGE:
                LfBus bus = (LfBus) ((SingleVariableLfSensitivityFactor<V, E>) factor).getVariableElement();
                return bus.getNominalV();
            default:
                throw new IllegalArgumentException("Unknown function type " + factor.getFunctionType());
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
}
