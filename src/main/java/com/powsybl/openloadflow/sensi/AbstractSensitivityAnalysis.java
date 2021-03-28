/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.sensi;

import com.powsybl.commons.PowsyblException;
import com.powsybl.contingency.ContingencyElement;
import com.powsybl.contingency.ContingencyElementType;
import com.powsybl.iidm.network.*;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.math.matrix.Matrix;
import com.powsybl.math.matrix.MatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.equations.*;
import com.powsybl.openloadflow.graph.GraphDecrementalConnectivity;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.PerUnit;
import com.powsybl.openloadflow.network.util.ActivePowerDistribution;
import com.powsybl.openloadflow.network.util.ParticipatingElement;
import com.powsybl.openloadflow.util.PropagatedContingency;
import com.powsybl.sensitivity.SensitivityFactor;
import com.powsybl.sensitivity.SensitivityFunction;
import com.powsybl.sensitivity.SensitivityVariable;
import com.powsybl.sensitivity.factors.BranchFlowPerInjectionIncrease;
import com.powsybl.sensitivity.factors.BranchFlowPerLinearGlsk;
import com.powsybl.sensitivity.factors.BranchFlowPerPSTAngle;
import com.powsybl.sensitivity.factors.BranchIntensityPerPSTAngle;
import com.powsybl.sensitivity.factors.functions.BranchFlow;
import com.powsybl.sensitivity.factors.functions.BranchIntensity;
import com.powsybl.sensitivity.factors.variables.InjectionIncrease;
import com.powsybl.sensitivity.factors.variables.LinearGlsk;
import com.powsybl.sensitivity.factors.variables.PhaseTapChangerAngle;
import org.apache.commons.lang3.NotImplementedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 * @author Gael Macherel <gael.macherel at artelys.com>
 */
public abstract class AbstractSensitivityAnalysis {

    protected static final Logger LOGGER = LoggerFactory.getLogger(AbstractSensitivityAnalysis.class);

    protected final MatrixFactory matrixFactory;

    protected final Supplier<GraphDecrementalConnectivity<LfBus>> connectivityProvider;

    protected AbstractSensitivityAnalysis(MatrixFactory matrixFactory, Supplier<GraphDecrementalConnectivity<LfBus>> connectivityProvider) {
        this.matrixFactory = Objects.requireNonNull(matrixFactory);
        this.connectivityProvider = Objects.requireNonNull(connectivityProvider);
    }

    protected static Injection<?> getInjection(Network network, String injectionId) {
        return getInjection(network, injectionId, true);
    }

    protected static Injection<?> getInjection(Network network, String injectionId, boolean failIfAbsent) {
        Injection<?> injection = network.getGenerator(injectionId);
        if (injection == null) {
            injection = network.getLoad(injectionId);
        }
        if (injection == null) {
            injection = network.getLccConverterStation(injectionId);
        }
        if (injection == null) {
            injection = network.getVscConverterStation(injectionId);
        }

        if (failIfAbsent && injection == null) {
            throw new PowsyblException("Injection '" + injectionId + "' not found");
        }

        return injection;
    }

    protected static LfBus getInjectionLfBus(Network network, LfNetwork lfNetwork, BranchFlowPerInjectionIncrease injectionFactor) {
        return getInjectionLfBus(network, lfNetwork, injectionFactor.getVariable().getInjectionId());
    }

    protected static LfBus getInjectionLfBus(Network network, LfNetwork lfNetwork, String injectionId) {
        Injection<?> injection = getInjection(network, injectionId, false);
        if (injection == null) {
            return null;
        }
        Bus bus = injection.getTerminal().getBusView().getBus();
        return lfNetwork.getBusById(bus.getId());
    }

    protected static LfBranch getPhaseTapChangerLfBranch(LfNetwork lfNetwork, PhaseTapChangerAngle pstVariable) {
        return lfNetwork.getBranchById(pstVariable.getPhaseTapChangerHolderId());
    }

    protected JacobianMatrix createJacobianMatrix(EquationSystem equationSystem, VoltageInitializer voltageInitializer) {
        double[] x = equationSystem.createStateVector(voltageInitializer);
        equationSystem.updateEquations(x);
        return new JacobianMatrix(equationSystem, matrixFactory);
    }

    static class LfSensitivityFactor {

        enum Status {
            VALID,
            SKIP,
            ZERO
        }
        // Wrap factors in specific class to have instant access to their branch and their equation term
        private final SensitivityFactor factor;

        private final LfBranch functionLfBranch;

        private final String functionLfBranchId;

        private final EquationTerm equationTerm;

        private Double predefinedResult = null;

        private Double functionReference = 0d;

        private Double baseCaseSensitivityValue = Double.NaN; // the sensitivity value on pre contingency network, that needs to be recomputed if the stack distribution change

        private Status status = Status.VALID;

        public LfSensitivityFactor(SensitivityFactor factor, LfNetwork lfNetwork) {
            this.factor = factor;
            if (factor instanceof BranchFlowPerInjectionIncrease) {
                BranchFlow branchFlow = ((BranchFlowPerInjectionIncrease) factor).getFunction();
                functionLfBranch = lfNetwork.getBranchById(branchFlow.getBranchId());
                equationTerm = functionLfBranch != null ? (EquationTerm) functionLfBranch.getP1() : null;
            } else if (factor instanceof BranchFlowPerPSTAngle) {
                BranchFlow branchFlow = ((BranchFlowPerPSTAngle) factor).getFunction();
                functionLfBranch = lfNetwork.getBranchById(branchFlow.getBranchId());
                equationTerm = functionLfBranch != null ? (EquationTerm) functionLfBranch.getP1() : null;
            } else if (factor instanceof BranchIntensityPerPSTAngle) {
                BranchIntensity branchIntensity = ((BranchIntensityPerPSTAngle) factor).getFunction();
                functionLfBranch = lfNetwork.getBranchById(branchIntensity.getBranchId());
                equationTerm = functionLfBranch != null ? (EquationTerm) functionLfBranch.getI1() : null;
            } else if (factor instanceof BranchFlowPerLinearGlsk) {
                BranchFlow branchFlow = ((BranchFlowPerLinearGlsk) factor).getFunction();
                functionLfBranch = lfNetwork.getBranchById(branchFlow.getBranchId());
                equationTerm = functionLfBranch != null ? (EquationTerm) functionLfBranch.getP1() : null;
            } else {
                throw new UnsupportedOperationException("Only factors of type BranchFlow are supported");
            }
            if (functionLfBranch == null) {
                status = Status.ZERO;
                functionLfBranchId = null;
            } else {
                functionLfBranchId = functionLfBranch.getId();
            }
        }

        public static LfSensitivityFactor create(SensitivityFactor factor, Network network, LfNetwork lfNetwork) {
            if (factor instanceof BranchFlowPerInjectionIncrease) {
                return new LfBranchFlowPerInjectionIncrease(factor, network, lfNetwork);
            } else if (factor instanceof BranchFlowPerPSTAngle) {
                return new LfBranchFlowPerPSTAngle(factor, lfNetwork);
            } else if (factor instanceof BranchIntensityPerPSTAngle) {
                return new LfBranchIntensityPerPSTAngle(factor, lfNetwork);
            }  else if (factor instanceof BranchFlowPerLinearGlsk) {
                return new LfBranchFlowPerLinearGlsk(factor, network, lfNetwork);
            } else {
                throw new UnsupportedOperationException("Factor type '" + factor.getClass().getSimpleName() + "' not yet supported");
            }
        }

        public SensitivityFactor getFactor() {
            return factor;
        }

        public LfBranch getFunctionLfBranch() {
            return functionLfBranch;
        }

        public String getFunctionLfBranchId() {
            return functionLfBranchId;
        }

        public EquationTerm getEquationTerm() {
            return equationTerm;
        }

        public Double getPredefinedResult() {
            return predefinedResult;
        }

        public void setPredefinedResult(Double predefinedResult) {
            this.predefinedResult = predefinedResult;
        }

        public Double getFunctionReference() {
            return functionReference;
        }

        public void setFunctionReference(Double functionReference) {
            this.functionReference = functionReference;
        }

        public Double getBaseSensitivityValue() {
            return baseCaseSensitivityValue;
        }

        public void setBaseCaseSensitivityValue(Double baseCaseSensitivityValue) {
            this.baseCaseSensitivityValue = baseCaseSensitivityValue;
        }

        public Status getStatus() {
            return status;
        }

        public void setStatus(Status status) {
            this.status = status;
        }

        public boolean areVariableAndFunctionDisconnected(GraphDecrementalConnectivity<LfBus> connectivity) {
            throw new NotImplementedException("areVariableAndFunctionDisconnected should have an override");
        }

        public boolean isConnectedToComponent(Set<LfBus> connectedComponent) {
            throw new NotImplementedException("isConnectedToComponent should have an override");
        }
    }

    static class LfBranchFlowPerInjectionIncrease extends LfSensitivityFactor {

        private final LfBus injectionLfBus;

        LfBranchFlowPerInjectionIncrease(SensitivityFactor factor, Network network, LfNetwork lfNetwork) {
            super(factor, lfNetwork);
            injectionLfBus = AbstractSensitivityAnalysis.getInjectionLfBus(network, lfNetwork, (BranchFlowPerInjectionIncrease) factor);
            if (injectionLfBus == null) {
                setStatus(Status.SKIP);
            }
        }

        @Override
        public boolean areVariableAndFunctionDisconnected(final GraphDecrementalConnectivity<LfBus> connectivity) {
            return connectivity.getComponentNumber(injectionLfBus) != connectivity.getComponentNumber(getFunctionLfBranch().getBus1())
                || connectivity.getComponentNumber(injectionLfBus) != connectivity.getComponentNumber(getFunctionLfBranch().getBus2());
        }

        @Override
        public boolean isConnectedToComponent(Set<LfBus> connectedComponent) {
            return connectedComponent.contains(injectionLfBus);
        }

        public LfBus getInjectionLfBus() {
            return injectionLfBus;
        }
    }

    private static class LfBranchPerPSTAngle extends LfSensitivityFactor {

        private final LfBranch phaseTapChangerLfBranch;

        LfBranchPerPSTAngle(SensitivityFactor factor, LfNetwork lfNetwork) {
            super(factor, lfNetwork);
            phaseTapChangerLfBranch = getPhaseTapChangerLfBranch(lfNetwork, (PhaseTapChangerAngle) factor.getVariable());
            if (phaseTapChangerLfBranch == null) {
                setStatus(Status.SKIP);
            }
        }

        @Override
        public boolean areVariableAndFunctionDisconnected(final GraphDecrementalConnectivity<LfBus> connectivity) {
            return connectivity.getComponentNumber(phaseTapChangerLfBranch.getBus1()) != connectivity.getComponentNumber(getFunctionLfBranch().getBus1())
                || connectivity.getComponentNumber(phaseTapChangerLfBranch.getBus1()) != connectivity.getComponentNumber(getFunctionLfBranch().getBus2());
        }

        @Override
        public boolean isConnectedToComponent(Set<LfBus> connectedComponent) {
            return connectedComponent.contains(phaseTapChangerLfBranch.getBus1());
        }

    }

    static class LfBranchFlowPerPSTAngle extends LfBranchPerPSTAngle {
        LfBranchFlowPerPSTAngle(SensitivityFactor factor, LfNetwork lfNetwork) {
            super(factor, lfNetwork);
        }
    }

    static class LfBranchIntensityPerPSTAngle extends LfBranchPerPSTAngle {
        LfBranchIntensityPerPSTAngle(SensitivityFactor factor, LfNetwork lfNetwork) {
            super(factor, lfNetwork);
        }
    }

    static class LfBranchFlowPerLinearGlsk extends LfSensitivityFactor {

        private final Map<LfBus, Double> injectionBuses;

        LfBranchFlowPerLinearGlsk(SensitivityFactor factor, Network network, LfNetwork lfNetwork) {
            super(factor, lfNetwork);
            injectionBuses = new HashMap<>();
            Map<String, Float> glsk = ((LinearGlsk) factor.getVariable()).getGLSKs();
            Collection<String> skippedInjection = new ArrayList<>(glsk.size());
            for (String injectionId : glsk.keySet()) {
                LfBus lfBus = AbstractSensitivityAnalysis.getInjectionLfBus(network, lfNetwork, injectionId);
                if (lfBus == null) {
                    skippedInjection.add(injectionId);
                    continue;
                }
                injectionBuses.put(lfBus, injectionBuses.getOrDefault(lfBus, 0d) + glsk.get(injectionId));
            }

            if (injectionBuses.isEmpty()) {
                setStatus(Status.SKIP);
            } else if (!skippedInjection.isEmpty()) {
                LOGGER.warn("Injections {} cannot be found for glsk {} and will be ignored", String.join(", ", skippedInjection), factor.getVariable().getId());
            }
        }

        @Override
        public boolean areVariableAndFunctionDisconnected(final GraphDecrementalConnectivity<LfBus> connectivity) {
            for (LfBus lfBus : injectionBuses.keySet()) {
                if (connectivity.getComponentNumber(lfBus) == connectivity.getComponentNumber(getFunctionLfBranch().getBus1())
                    && connectivity.getComponentNumber(lfBus) == connectivity.getComponentNumber(getFunctionLfBranch().getBus2())) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean isConnectedToComponent(Set<LfBus> connectedComponent) {
            if (!connectedComponent.contains(getFunctionLfBranch().getBus1())
                || !connectedComponent.contains(getFunctionLfBranch().getBus2())) {
                return false;
            }
            for (LfBus lfBus : injectionBuses.keySet()) {
                if (connectedComponent.contains(lfBus)) {
                    return true;
                }
            }
            return false;
        }

        public Map<LfBus, Double> getInjectionBuses() {
            return injectionBuses;
        }
    }

    static class SensitivityFactorGroup {

        private final String id;

        private final List<LfSensitivityFactor> factors = new ArrayList<>();

        private int index = -1;

        SensitivityFactorGroup(String id) {
            this.id = Objects.requireNonNull(id);
        }

        String getId() {
            return id;
        }

        List<LfSensitivityFactor> getFactors() {
            return factors;
        }

        int getIndex() {
            return index;
        }

        void setIndex(int index) {
            this.index = index;
        }

        void addFactor(LfSensitivityFactor factor) {
            factors.add(factor);
        }

        void fillRhs(LfNetwork lfNetwork, EquationSystem equationSystem, Matrix rhs) {
            throw new NotImplementedException("fillRhs method must be implemented in subclasses");
        }
    }

    static class PhaseTapChangerFactorGroup extends SensitivityFactorGroup {

        PhaseTapChangerFactorGroup(final String id) {
            super(id);
        }

        @Override
        void fillRhs(LfNetwork lfNetwork, EquationSystem equationSystem, Matrix rhs) {
            LfBranch lfBranch = lfNetwork.getBranchById(getId());
            Equation a1 = equationSystem.getEquation(lfBranch.getNum(), EquationType.BRANCH_ALPHA1).orElseThrow(IllegalStateException::new);
            if (!a1.isActive()) {
                return;
            }
            rhs.set(a1.getColumn(), getIndex(), Math.toRadians(1d));
        }
    }

    abstract static class AbstractInjectionFactorGroup extends SensitivityFactorGroup {

        Map<LfBus, Double> participationByBus;

        AbstractInjectionFactorGroup(final String id) {
            super(id);
        }

        public void setParticipationByBus(final Map<LfBus, Double> participationByBus) {
            this.participationByBus = participationByBus;
        }

        @Override
        void fillRhs(LfNetwork lfNetwork, EquationSystem equationSystem, Matrix rhs) {
            for (Map.Entry<LfBus, Double> lfBusAndParticipationFactor : participationByBus.entrySet()) {
                LfBus lfBus = lfBusAndParticipationFactor.getKey();
                Equation p = (Equation) lfBus.getP();
                Double participationFactor = lfBusAndParticipationFactor.getValue();
                if (lfBus.isSlack() || !p.isActive()) {
                    continue;
                }
                int column = p.getColumn();
                rhs.set(column, getIndex(), participationFactor / PerUnit.SB);
            }
        }
    }

    static class SingleInjectionFactorGroup extends AbstractInjectionFactorGroup {
        private LfBus lfBus;

        SingleInjectionFactorGroup(final LfBus lfBus) {
            super(lfBus.getId());
            this.lfBus = lfBus;
        }

        @Override
        void fillRhs(LfNetwork lfNetwork, EquationSystem equationSystem, Matrix rhs) {
            super.fillRhs(lfNetwork, equationSystem, rhs);
            if (!lfBus.isSlack() && ((Equation) lfBus.getP()).isActive()) {
                rhs.add(((Equation) lfBus.getP()).getColumn(), getIndex(), 1d / PerUnit.SB);
            }
        }
    }

    static class LinearGlskGroup extends AbstractInjectionFactorGroup {
        // This group makes sense because we are only computing sensitivities in the main connected component
        // otherwise, we wouldn't be able to group different branches within the same group
        private final Map<LfBus, Double> glskMap;
        private Map<LfBus, Double> glskMapInMainComponent;

        LinearGlskGroup(String id, Map<LfBus, Double> glskMap) {
            super(id);
            this.glskMap = glskMap;
            this.glskMapInMainComponent = glskMap;
        }

        @Override
        void fillRhs(LfNetwork lfNetwork, EquationSystem equationSystem, Matrix rhs) {
            super.fillRhs(lfNetwork, equationSystem, rhs);
            Double glskWeightSum = glskMapInMainComponent.values().stream().mapToDouble(Math::abs).sum();
            glskMapInMainComponent.forEach((bus, weight) -> {
                Equation p = (Equation) bus.getP();
                if (bus.isSlack() || !p.isActive()) {
                    return;
                }
                rhs.add(p.getColumn(), getIndex(), weight / glskWeightSum / PerUnit.SB);
            });
        }

        public void setGlskMapInMainComponent(final Map<LfBus, Double> glskMapInMainComponent) {
            this.glskMapInMainComponent = glskMapInMainComponent;
        }

        public Map<LfBus, Double> getGlskMap() {
            return glskMap;
        }
    }

    protected List<SensitivityFactorGroup> createFactorGroups(Network network, List<LfSensitivityFactor> factors) {
        Map<String, SensitivityFactorGroup> groupIndexedById = new HashMap<>(factors.size());
        // index factors by variable config
        for (LfSensitivityFactor factor : factors) {
            if (factor instanceof LfBranchFlowPerInjectionIncrease) {
                LfBus lfBus = ((LfBranchFlowPerInjectionIncrease) factor).getInjectionLfBus();
                // skip disconnected injections
                if (lfBus != null) {
                    groupIndexedById.computeIfAbsent(lfBus.getId(), id -> new SingleInjectionFactorGroup(lfBus)).addFactor(factor);
                }
            } else if (factor instanceof LfBranchPerPSTAngle) {
                PhaseTapChangerAngle pstAngleVariable = (PhaseTapChangerAngle) factor.getFactor().getVariable();
                String phaseTapChangerHolderId = pstAngleVariable.getPhaseTapChangerHolderId();
                TwoWindingsTransformer twt = network.getTwoWindingsTransformer(phaseTapChangerHolderId);
                if (twt == null) {
                    throw new PowsyblException("Phase shifter '" + phaseTapChangerHolderId + "' not found");
                }
                groupIndexedById.computeIfAbsent(phaseTapChangerHolderId, k -> new PhaseTapChangerFactorGroup(phaseTapChangerHolderId)).addFactor(factor);
            } else if (factor instanceof LfBranchFlowPerLinearGlsk) {
                LfBranchFlowPerLinearGlsk lfFactor = (LfBranchFlowPerLinearGlsk) factor;
                LinearGlsk glsk = (LinearGlsk) factor.getFactor().getVariable();
                String glskId = glsk.getId();
                groupIndexedById.computeIfAbsent(glskId, id -> new LinearGlskGroup(glskId, lfFactor.getInjectionBuses())).addFactor(factor);
            } else {
                throw new UnsupportedOperationException("Factor type '" + factor.getFactor().getClass().getSimpleName() + "' not yet supported");
            }
        }

        // assign an index to each factor group
        int index = 0;
        for (SensitivityFactorGroup factorGroup : groupIndexedById.values()) {
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

    protected void computeInjectionFactors(Map<LfBus, Double> participationFactorByBus, List<SensitivityFactorGroup> factorGroups) {
        // compute the corresponding injection (including participation) for each factor
        for (SensitivityFactorGroup factorGroup : factorGroups) {
            if (factorGroup instanceof AbstractInjectionFactorGroup) {
                AbstractInjectionFactorGroup injectionGroup = (AbstractInjectionFactorGroup) factorGroup;
                injectionGroup.setParticipationByBus(participationFactorByBus);
            }
        }
    }

    protected DenseMatrix initFactorsRhs(LfNetwork lfNetwork, EquationSystem equationSystem, List<SensitivityFactorGroup> factorsGroups) {
        DenseMatrix rhs = new DenseMatrix(equationSystem.getSortedEquationsToSolve().size(), factorsGroups.size());
        fillRhsSensitivityVariable(lfNetwork, equationSystem, factorsGroups, rhs);
        return rhs;
    }

    protected void fillRhsSensitivityVariable(LfNetwork lfNetwork, EquationSystem equationSystem, List<SensitivityFactorGroup> factorGroups, Matrix rhs) {
        for (SensitivityFactorGroup factorGroup : factorGroups) {
            factorGroup.fillRhs(lfNetwork, equationSystem, rhs);
        }
    }

    public void cutConnectivity(LfNetwork lfNetwork, GraphDecrementalConnectivity<LfBus> connectivity, Collection<String> breakingConnectivityCandidates) {
        breakingConnectivityCandidates.stream()
            .map(lfNetwork::getBranchById)
            .forEach(lfBranch -> connectivity.cut(lfBranch.getBus1(), lfBranch.getBus2()));
    }

    protected void setPredefinedResults(Collection<LfSensitivityFactor> lfFactors, Set<LfBus> connectedComponent,
                                        GraphDecrementalConnectivity<LfBus> connectivity) {
        for (LfSensitivityFactor factor : lfFactors) {
            // check if the factor function and variable are in different connected components
            if (factor.areVariableAndFunctionDisconnected(connectivity)) {
                factor.setPredefinedResult(0d);
            } else if (!factor.isConnectedToComponent(connectedComponent)) {
                factor.setPredefinedResult(Double.NaN); // works for sensitivity and function reference
            }
        }
    }

    protected void rescaleGlsk(List<SensitivityFactorGroup> factorGroups, Set<LfBus> nonConnectedBuses) {
        // compute the corresponding injection (with participation) for each factor
        for (SensitivityFactorGroup factorGroup : factorGroups) {
            if (!(factorGroup instanceof LinearGlskGroup)) {
                continue;
            }
            LinearGlskGroup glskGroup = (LinearGlskGroup) factorGroup;
            Map<LfBus, Double> remainingGlskInjections = glskGroup.getGlskMap().entrySet().stream()
                .filter(entry -> !nonConnectedBuses.contains(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            glskGroup.setGlskMapInMainComponent(remainingGlskInjections);
        }
    }

    protected void warnSkippedFactors(Collection<LfSensitivityFactor> lfFactors) {
        List<LfSensitivityFactor> skippedFactors = lfFactors.stream().filter(factor -> factor.getStatus().equals(LfSensitivityFactor.Status.SKIP)).collect(Collectors.toList());
        Set<String> skippedVariables = skippedFactors.stream().map(factor -> factor.getFactor().getVariable().getId()).collect(Collectors.toSet());
        if (!skippedVariables.isEmpty()) {
            LOGGER.warn("Skipping all factors with variables: '{}', as they cannot be found in the network", String.join(", ", skippedVariables));
        }
    }

    private void checkInjectionIncrease(InjectionIncrease injection, Network network) {
        getInjection(network, injection.getInjectionId()); // will crash if injection is not found
    }

    private void checkLinearGlsk(LinearGlsk glsk, Network network) {
        glsk.getGLSKs().keySet().forEach(injection -> getInjection(network, injection));
    }

    private void checkPhaseTapChangerAngle(PhaseTapChangerAngle angle, Network network) {
        TwoWindingsTransformer twt = network.getTwoWindingsTransformer(angle.getPhaseTapChangerHolderId());
        if (twt == null) {
            throw new PowsyblException("Two windings transformer '" + angle.getPhaseTapChangerHolderId() + "' not found");
        }
    }

    private void checkVariable(SensitivityVariable variable, Network network) {
        if (variable instanceof InjectionIncrease) {
            checkInjectionIncrease((InjectionIncrease) variable, network);
        } else if (variable instanceof LinearGlsk) {
            checkLinearGlsk((LinearGlsk) variable, network);
        } else if (variable instanceof PhaseTapChangerAngle) {
            checkPhaseTapChangerAngle((PhaseTapChangerAngle) variable, network);
        } else {
            throw new PowsyblException("Variable of type " + variable.getClass().getSimpleName() + " is not recognized.");
        }
    }

    private void checkBranchFlow(BranchFlow branchFlow, Network network) {
        Branch branch = network.getBranch(branchFlow.getBranchId());
        if (branch == null) {
            throw new PowsyblException("Branch '" + branchFlow.getBranchId() + "' not found");
        }
    }

    private void checkBranchIntensity(BranchIntensity branchIntensity, Network network) {
        Branch branch = network.getBranch(branchIntensity.getBranchId());
        if (branch == null) {
            throw new PowsyblException("Branch '" + branchIntensity.getBranchId() + "' not found");
        }
    }

    private void checkFunction(SensitivityFunction function, Network network) {
        if (function instanceof BranchFlow) {
            checkBranchFlow((BranchFlow) function, network);
        } else if (function instanceof BranchIntensity) {
            checkBranchIntensity((BranchIntensity) function, network);
        } else {
            throw new PowsyblException("Function of type " + function.getClass().getSimpleName() + " is not recognized.");
        }
    }

    public void checkSensitivities(Network network, List<SensitivityFactor> factors) {
        for (SensitivityFactor<?, ?> factor : factors) {
            checkVariable(factor.getVariable(), network);
            checkFunction(factor.getFunction(), network);
        }
    }

    public void checkContingencies(LfNetwork lfNetwork, List<PropagatedContingency> contingencies) {
        for (PropagatedContingency contingency : contingencies) {
            for (ContingencyElement contingencyElement : contingency.getContingency().getElements()) {
                if (!contingencyElement.getType().equals(ContingencyElementType.BRANCH)) {
                    throw new UnsupportedOperationException("Only contingencies on a branch are yet supported");
                }
                LfBranch lfBranch = lfNetwork.getBranchById(contingencyElement.getId());
                if (lfBranch == null) {
                    throw new PowsyblException("The contingency on the branch " + contingencyElement.getId() + " not found in the network");
                }

            }
            Set<String> branchesToRemove = new HashSet<>(); // branches connected to one side, or switches
            for (String branchId : contingency.getBranchIdsToOpen()) {
                LfBranch lfBranch = lfNetwork.getBranchById(branchId);
                if (lfBranch == null) {
                    branchesToRemove.add(branchId); // this is certainly a switch
                    continue;
                }
                if (lfBranch.getBus2() == null || lfBranch.getBus1() == null) {
                    branchesToRemove.add(branchId); // contains the branches that are connected only on one side
                }
            }
            contingency.getBranchIdsToOpen().removeAll(branchesToRemove);
            if (contingency.getBranchIdsToOpen().isEmpty()) {
                LOGGER.warn("Contingency {} has no impact", contingency.getContingency().getId());
            }
        }
    }

    public void checkLoadFlowParameters(LoadFlowParameters lfParameters) {
        if (!lfParameters.getBalanceType().equals(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX)
            && !lfParameters.getBalanceType().equals(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD)) {
            throw new UnsupportedOperationException("Unsupported balance type mode: " + lfParameters.getBalanceType());
        }
    }
}
