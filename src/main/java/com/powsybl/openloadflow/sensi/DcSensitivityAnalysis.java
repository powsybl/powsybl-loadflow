/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.sensi;

import com.powsybl.commons.PowsyblException;
import com.powsybl.contingency.BranchContingency;
import com.powsybl.contingency.ContingencyElement;
import com.powsybl.contingency.ContingencyElementType;
import com.powsybl.iidm.network.Injection;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.TwoWindingsTransformer;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.DenseMatrix;
import com.powsybl.math.matrix.LUDecomposition;
import com.powsybl.math.matrix.Matrix;
import com.powsybl.math.matrix.MatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowParameters;
import com.powsybl.openloadflow.dc.equations.ClosedBranchSide1DcFlowEquationTerm;
import com.powsybl.openloadflow.dc.equations.DcEquationSystem;
import com.powsybl.openloadflow.dc.equations.DcEquationSystemCreationParameters;
import com.powsybl.openloadflow.equations.*;
import com.powsybl.openloadflow.graph.GraphDecrementalConnectivity;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.network.util.ActivePowerDistribution;
import com.powsybl.openloadflow.network.util.ParticipatingElement;
import com.powsybl.openloadflow.util.BusState;
import com.powsybl.openloadflow.util.Profiler;
import com.powsybl.openloadflow.util.PropagatedContingency;
import com.powsybl.sensitivity.SensitivityFactor;
import com.powsybl.sensitivity.SensitivityValue;
import com.powsybl.sensitivity.factors.BranchFlowPerInjectionIncrease;
import com.powsybl.sensitivity.factors.BranchFlowPerLinearGlsk;
import com.powsybl.sensitivity.factors.BranchFlowPerPSTAngle;
import com.powsybl.sensitivity.factors.variables.LinearGlsk;
import org.apache.commons.lang3.NotImplementedException;
import org.apache.commons.lang3.tuple.Pair;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 * @author Gaël Macherel <gael.macherel@artelys.com>
 */
public class DcSensitivityAnalysis extends AbstractSensitivityAnalysis {

    static final double CONNECTIVITY_LOSS_THRESHOLD = 10e-7;

    static class LfSensitivityFactor {
        // Wrap factors in specific class to have instant access to their branch, and their equation term
        private final SensitivityFactor factor;

        private final LfBranch functionLfBranch;

        private final String functionLfBranchId;

        private final ClosedBranchSide1DcFlowEquationTerm equationTerm;

        private Double predefinedResult = null;

        private Double functionReference;

        private Double baseCaseSensitivityValue = Double.NaN; // the sensitivity value without any +1-1 (needs to be recomputed if the stack distribution changes)

        public LfSensitivityFactor(SensitivityFactor factor, LfNetwork lfNetwork, EquationSystem equationSystem) {
            this.factor = factor;
            if (factor instanceof BranchFlowPerInjectionIncrease) {
                functionLfBranch = lfNetwork.getBranchById(((BranchFlowPerInjectionIncrease) factor).getFunction().getBranchId());
            } else if (factor instanceof BranchFlowPerPSTAngle) {
                functionLfBranch = lfNetwork.getBranchById(((BranchFlowPerPSTAngle) factor).getFunction().getBranchId());
            } else if (factor instanceof BranchFlowPerLinearGlsk) {
                functionLfBranch = lfNetwork.getBranchById(((BranchFlowPerLinearGlsk) factor).getFunction().getBranchId());
            } else {
                throw new UnsupportedOperationException("Only factors of type BranchFlow are supported");
            }
            functionLfBranchId = functionLfBranch.getId();
            equationTerm = equationSystem.getEquationTerm(SubjectType.BRANCH, functionLfBranch.getNum(), ClosedBranchSide1DcFlowEquationTerm.class);
            functionReference = 0d;
        }

        public static LfSensitivityFactor create(SensitivityFactor factor, Network network, LfNetwork lfNetwork, EquationSystem equationSystem) {
            if (factor instanceof BranchFlowPerInjectionIncrease) {
                return new LfBranchFlowPerInjectionIncrease(factor, network, lfNetwork, equationSystem);
            } else if (factor instanceof BranchFlowPerPSTAngle) {
                return new LfBranchFlowPerPSTAngle(factor, lfNetwork, equationSystem);
            }  else if (factor instanceof BranchFlowPerLinearGlsk) {
                return new LfBranchFlowPerLinearGlsk(factor, network, lfNetwork, equationSystem);
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

        public ClosedBranchSide1DcFlowEquationTerm getEquationTerm() {
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

        public boolean areVariableAndFunctionDisconnected(GraphDecrementalConnectivity<LfBus> connectivity) {
            throw new NotImplementedException("areVariableAndFunctionDisconnected should have an override");
        }

        public boolean isConnectedToComponent(Integer componentNumber, GraphDecrementalConnectivity<LfBus> connectivity) {
            throw new NotImplementedException("isConnectedToComponent should have an override");
        }
    }

    static class LfBranchFlowPerInjectionIncrease extends LfSensitivityFactor {

        private final LfBus injectionLfBus;

        LfBranchFlowPerInjectionIncrease(SensitivityFactor factor, Network network, LfNetwork lfNetwork, EquationSystem equationSystem) {
            super(factor, lfNetwork, equationSystem);
            injectionLfBus = DcSensitivityAnalysis.getInjectionLfBus(network, lfNetwork, (BranchFlowPerInjectionIncrease) factor);
        }

        @Override
        public boolean areVariableAndFunctionDisconnected(final GraphDecrementalConnectivity<LfBus> connectivity) {
            return connectivity.getComponentNumber(injectionLfBus) != connectivity.getComponentNumber(getFunctionLfBranch().getBus1())
                    || connectivity.getComponentNumber(injectionLfBus) != connectivity.getComponentNumber(getFunctionLfBranch().getBus2());
        }

        @Override
        public boolean isConnectedToComponent(Integer componentNumber, GraphDecrementalConnectivity<LfBus> connectivity) {
            return connectivity.getComponentNumber(injectionLfBus) == componentNumber;
        }

        public LfBus getInjectionLfBus() {
            return injectionLfBus;
        }
    }

    static class LfBranchFlowPerPSTAngle extends LfSensitivityFactor {

        private final LfBranch phaseTapChangerLfBranch;

        LfBranchFlowPerPSTAngle(SensitivityFactor factor, LfNetwork lfNetwork, EquationSystem equationSystem) {
            super(factor, lfNetwork, equationSystem);
            phaseTapChangerLfBranch = getPhaseTapChangerLfBranch(lfNetwork, (BranchFlowPerPSTAngle) factor);
        }

        @Override
        public boolean areVariableAndFunctionDisconnected(final GraphDecrementalConnectivity<LfBus> connectivity) {
            return connectivity.getComponentNumber(phaseTapChangerLfBranch.getBus1()) != connectivity.getComponentNumber(getFunctionLfBranch().getBus1())
                    || connectivity.getComponentNumber(phaseTapChangerLfBranch.getBus1()) != connectivity.getComponentNumber(getFunctionLfBranch().getBus2());
        }

        @Override
        public boolean isConnectedToComponent(Integer componentNumber, GraphDecrementalConnectivity<LfBus> connectivity) {
            return componentNumber == connectivity.getComponentNumber(phaseTapChangerLfBranch.getBus1());
        }

    }

    static class LfBranchFlowPerLinearGlsk extends LfSensitivityFactor {

        private final Map<LfBus, Double> injectionBuses;

        LfBranchFlowPerLinearGlsk(SensitivityFactor factor, Network network, LfNetwork lfNetwork, EquationSystem equationSystem) {
            super(factor, lfNetwork, equationSystem);
            injectionBuses = ((LinearGlsk) factor.getVariable()).getGLSKs()
                                                                .entrySet()
                                                                .stream()
                                                                .collect(Collectors.toMap(
                                                                    entry -> DcSensitivityAnalysis.getInjectionLfBus(network, lfNetwork, entry.getKey()),
                                                                    entry -> entry.getValue().doubleValue(),
                                                                    Double::sum
                                                                ));
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
        public boolean isConnectedToComponent(Integer componentNumber, GraphDecrementalConnectivity<LfBus> connectivity) {
            if (connectivity.getComponentNumber(getFunctionLfBranch().getBus1()) != componentNumber
                || connectivity.getComponentNumber(getFunctionLfBranch().getBus2()) != componentNumber) {
                return false;
            }
            for (LfBus lfBus : injectionBuses.keySet()) {
                if (connectivity.getComponentNumber(lfBus) == componentNumber) {
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
            rhs.set(a1.getColumn(), getIndex(), Math.toRadians(1d));
        }
    }

    static class InjectionFactorGroup extends SensitivityFactorGroup {

        Map<String, Double> injectionByBus;

        InjectionFactorGroup(final String id) {
            super(id);
        }

        public void setInjectionByBus(final Map<String, Double> slackParticipationByBus) {
            slackParticipationByBus.put(getId(), slackParticipationByBus.getOrDefault(getId(), 0d) + 1);
            injectionByBus = slackParticipationByBus;
        }

        @Override
        void fillRhs(LfNetwork lfNetwork, EquationSystem equationSystem, Matrix rhs) {
            for (Map.Entry<String, Double> injectionByBus : injectionByBus.entrySet()) {
                LfBus lfBus = lfNetwork.getBusById(injectionByBus.getKey());
                int column;
                if (lfBus.isSlack()) {
                    Equation p = equationSystem.getEquation(lfBus.getNum(), EquationType.BUS_PHI).orElseThrow(IllegalStateException::new);
                    column = p.getColumn();
                } else {
                    Equation p = equationSystem.getEquation(lfBus.getNum(), EquationType.BUS_P).orElseThrow(IllegalStateException::new);
                    column = p.getColumn();
                }
                rhs.set(column, getIndex(), injectionByBus.getValue() / PerUnit.SB);
            }
        }
    }

    static class LinearGlskGroup extends InjectionFactorGroup {
        // This group makes sense because we are only computing sensitivities in the main connected component
        // otherwise, we wouldn't be able to group different branches within the same group
        private final Map<LfBus, Double> glskMap;
        private Map<String, Double> glskMapInMainComponent;

        LinearGlskGroup(String id, Map<LfBus, Double> glskMap) {
            super(id);
            this.glskMap = glskMap;
            glskMapInMainComponent = glskMap.entrySet().stream().collect(Collectors.toMap(
                entry -> entry.getKey().getId(),
                Map.Entry::getValue
            ));
        }

        @Override
        public void setInjectionByBus(final Map<String, Double> participationToSlackByBus) {
            Double glskWeightSum = glskMapInMainComponent.values().stream().mapToDouble(Math::abs).sum();
            glskMapInMainComponent.forEach((busId, weight) -> participationToSlackByBus.merge(busId, weight / glskWeightSum, Double::sum));
            injectionByBus = participationToSlackByBus;
        }

        public void setGlskMapInMainComponent(final Map<String, Double> glskMapInMainComponent) {
            this.glskMapInMainComponent = glskMapInMainComponent;
        }

        public Map<LfBus, Double> getGlskMap() {
            return glskMap;
        }
    }

    static class ComputedContingencyElement {

        private int contingencyIndex = -1; // index of the element in the rhs for +1-1
        private int localIndex = -1; // local index of the element : index of the element in the matrix used in the setAlphas method
        private double alphaForSensitivityValue = Double.NaN;
        private double alphaForFunctionReference = Double.NaN;
        private final ContingencyElement element;
        private final LfBranch lfBranch;
        private final ClosedBranchSide1DcFlowEquationTerm branchEquation;

        public ComputedContingencyElement(final ContingencyElement element, LfNetwork lfNetwork, EquationSystem equationSystem) {
            this.element = element;
            lfBranch = lfNetwork.getBranchById(element.getId());
            branchEquation = equationSystem.getEquationTerm(SubjectType.BRANCH, lfBranch.getNum(), ClosedBranchSide1DcFlowEquationTerm.class);
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

    public DcSensitivityAnalysis(MatrixFactory matrixFactory) {
        super(matrixFactory);
    }

    protected DenseMatrix setReferenceActivePowerFlows(LfNetwork network, EquationSystem equationSystem, JacobianMatrix j, List<LfSensitivityFactor> factors,
                                                       LoadFlowParameters lfParameters, List<ParticipatingElement> participatingElements, GraphDecrementalConnectivity<LfBus> connectivity) {

        double[] x = equationSystem.createStateVector(new UniformValueVoltageInitializer());
        Map<LfBus, BusState> busStates = new HashMap<>();
        if (lfParameters.isDistributedSlack()) {
            double mismatch;
            if (connectivity != null) {
                mismatch = network.getActivePowerMismatchInMainComponent(connectivity);
            } else {
                mismatch = network.getActivePowerMismatch();
            }
            busStates = BusState.createBusStates(participatingElements.stream()
                    .map(ParticipatingElement::getLfBus)
                    .collect(Collectors.toSet()));
            int iteration = 0;
            ActivePowerDistribution.Step step = ActivePowerDistribution.getStep(lfParameters.getBalanceType(), false);
            while (!participatingElements.isEmpty()
                    && Math.abs(mismatch) > ActivePowerDistribution.P_RESIDUE_EPS) {
                mismatch -= step.run(participatingElements, iteration, mismatch);

                iteration++;
            }
        }

        equationSystem.updateEquations(x);

        double[] dx = equationSystem.createTargetVector();

        if (connectivity != null) {
            // set buses injections and transformers to 0 outside the main connected component
            int mainComponentNumber = connectivity.getComponentNumber(network.getSlackBus());
            Set<Integer> columnsToSetToZero = network.getBuses().stream()
                .filter(lfBus -> connectivity.getComponentNumber(lfBus) != mainComponentNumber)
                .map(lfBus -> equationSystem.getEquation(lfBus.getNum(), EquationType.BUS_P))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .map(Equation::getColumn)
                .collect(Collectors.toSet());
            for (Integer column : columnsToSetToZero) {
                dx[column] = 0;
            }
        }

        j.solveTransposed(dx);

        equationSystem.updateEquations(dx);
        equationSystem.updateNetwork(dx);

        // set all calculated voltages to NaN
        for (LfBus bus : network.getBuses()) {
            bus.setV(Double.NaN);
        }
        for (LfSensitivityFactor factor : factors) {
            factor.setFunctionReference(factor.getFunctionLfBranch().getP1());
        }

        if (lfParameters.isDistributedSlack()) {
            BusState.restoreDcBusStates(busStates);
        }

        return new DenseMatrix(dx.length, 1, dx);
    }

    protected void fillRhsSensitivityVariable(LfNetwork lfNetwork, EquationSystem equationSystem, List<SensitivityFactorGroup> factorGroups, Matrix rhs) {
        for (SensitivityFactorGroup factorGroup : factorGroups) {
            factorGroup.fillRhs(lfNetwork, equationSystem, rhs);
        }
    }

    private void setPredefinedResults(Collection<LfSensitivityFactor> lfFactors, GraphDecrementalConnectivity<LfBus> connectivity, int mainComponent) {
        for (LfSensitivityFactor factor : lfFactors) {
            // check if the factor function and variable are in different connected components
            if (factor.areVariableAndFunctionDisconnected(connectivity)) {
                factor.setPredefinedResult(0d);
            } else if (!factor.isConnectedToComponent(mainComponent, connectivity)) {
                factor.setPredefinedResult(Double.NaN); // works for sensitivity and function reference
            }
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
                    groupIndexedById.computeIfAbsent(lfBus.getId(), id -> new InjectionFactorGroup(lfBus.getId())).addFactor(factor);
                }
            } else if (factor instanceof LfBranchFlowPerPSTAngle) {
                BranchFlowPerPSTAngle pstAngleFactor = (BranchFlowPerPSTAngle) factor.getFactor();
                String phaseTapChangerHolderId = pstAngleFactor.getVariable().getPhaseTapChangerHolderId();
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

    protected void computeInjectionFactors(Map<String, Double> participationFactorByBus, List<SensitivityFactorGroup> factorGroups) {
        // compute the corresponding injection (including participation) for each factor
        for (SensitivityFactorGroup factorGroup : factorGroups) {
            if (factorGroup instanceof InjectionFactorGroup) {
                InjectionFactorGroup injectionGroup = (InjectionFactorGroup) factorGroup;
                injectionGroup.setInjectionByBus(new HashMap<>(participationFactorByBus));
            }
        }
    }

    protected void rescaleGlsk(List<SensitivityFactorGroup> factorGroups, GraphDecrementalConnectivity<LfBus> connectivity, Integer mainComponentNumber) {
        // compute the corresponding injection (with participation) for each factor
        for (SensitivityFactorGroup factorGroup : factorGroups) {
            if (!(factorGroup instanceof LinearGlskGroup)) {
                continue;
            }
            LinearGlskGroup glskGroup = (LinearGlskGroup) factorGroup;
            Map<String, Double> remainingGlskInjections = glskGroup.getGlskMap().entrySet().stream()
                    .filter(entry -> connectivity.getComponentNumber(entry.getKey()) == mainComponentNumber)
                    .collect(Collectors.toMap(entry -> entry.getKey().getId(), Map.Entry::getValue));
            glskGroup.setGlskMapInMainComponent(remainingGlskInjections);
        }
    }

    protected List<ParticipatingElement> getParticipatingElements(LfNetwork lfNetwork, LoadFlowParameters loadFlowParameters, Function<ParticipatingElement, Boolean> filter) {
        ActivePowerDistribution.Step step = ActivePowerDistribution.getStep(loadFlowParameters.getBalanceType(), false);
        List<ParticipatingElement> participatingElements =  step.getParticipatingElements(lfNetwork).stream().filter(filter::apply).collect(Collectors.toList());
        ParticipatingElement.normalizeParticipationFactors(participatingElements, "bus");
        return participatingElements;
    }

    protected List<ParticipatingElement> getParticipatingElements(LfNetwork lfNetwork, LoadFlowParameters loadFlowParameters) {
        return getParticipatingElements(lfNetwork, loadFlowParameters, element -> true);
    }

    public void checkSensitivities(Network network, LfNetwork lfNetwork, List<SensitivityFactor> factors) {
        for (SensitivityFactor<?, ?> factor : factors) {
            LfBranch monitoredBranch;
            if (factor instanceof  BranchFlowPerInjectionIncrease) {
                BranchFlowPerInjectionIncrease injectionFactor = (BranchFlowPerInjectionIncrease) factor;
                Injection<?> injection = getInjection(network, injectionFactor.getVariable().getInjectionId());
                if (injection == null) {
                    throw new PowsyblException("Injection " + injectionFactor.getVariable().getInjectionId() + " not found in the network");
                }
                if (lfNetwork.getBranchById(factor.getFunction().getId()) == null) {
                    throw new PowsyblException("Branch '" + factor.getFunction().getId() + "' not found");
                }
                monitoredBranch = lfNetwork.getBranchById(injectionFactor.getFunction().getBranchId());
            } else if (factor instanceof BranchFlowPerPSTAngle) {
                BranchFlowPerPSTAngle pstAngleFactor = (BranchFlowPerPSTAngle) factor;
                String phaseTapChangerHolderId = pstAngleFactor.getVariable().getPhaseTapChangerHolderId();
                TwoWindingsTransformer twt = network.getTwoWindingsTransformer(phaseTapChangerHolderId);
                if (twt == null) {
                    throw new PowsyblException("Phase shifter '" + phaseTapChangerHolderId + "' not found in the network");
                }
                if (lfNetwork.getBranchById(factor.getFunction().getId()) == null) {
                    throw new PowsyblException("Branch '" + factor.getFunction().getId() + "' not found");
                }
                monitoredBranch = lfNetwork.getBranchById(pstAngleFactor.getFunction().getBranchId());
            } else if (factor instanceof BranchFlowPerLinearGlsk) {
                BranchFlowPerLinearGlsk glskFactor = (BranchFlowPerLinearGlsk) factor;
                if (glskFactor.getVariable().getGLSKs().isEmpty()) {
                    throw new PowsyblException("The glsk '" + factor.getVariable().getId() + "' cannot be empty");
                }
                for (Map.Entry<String, Float> injectionEntry : glskFactor.getVariable().getGLSKs().entrySet()) {
                    Injection<?> injection = getInjection(network, injectionEntry.getKey());
                    if (injection == null) {
                        throw new PowsyblException("Injection " + injectionEntry.getKey() + " not found in the network");
                    }
                }
                if (lfNetwork.getBranchById(factor.getFunction().getId()) == null) {
                    throw new PowsyblException("Branch '" + factor.getFunction().getId() + "' not found");
                }
                monitoredBranch = lfNetwork.getBranchById(glskFactor.getFunction().getBranchId());
            } else {
                throw new PowsyblException("Only sensitivity factors of type BranchFlowPerInjectionIncrease and BranchFlowPerPSTAngle are yet supported");
            }
            if (monitoredBranch == null) {
                throw new PowsyblException("Monitored branch " + factor.getFunction().getId() + " not found in the network");
            }
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
                LOGGER.warn("Contingency " + contingency.getContingency().getId() + " has no impact.");
            }
        }
    }

    public void checkLoadFlowParameters(LoadFlowParameters lfParameters) {
        if (!lfParameters.getBalanceType().equals(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_GENERATION_P_MAX)
                && !lfParameters.getBalanceType().equals(LoadFlowParameters.BalanceType.PROPORTIONAL_TO_LOAD)) {
            throw new UnsupportedOperationException("Unsupported balance type mode: " + lfParameters.getBalanceType());
        }
    }

    private SensitivityValue createBranchSensitivityValue(LfSensitivityFactor factor, DenseMatrix contingenciesStates,
                                                          Collection<ComputedContingencyElement> contingencyElements) {
        double sensiValue;
        double flowValue;
        ClosedBranchSide1DcFlowEquationTerm p1 = factor.getEquationTerm();
        if (factor.getPredefinedResult() != null) {
            sensiValue = factor.getPredefinedResult();
            flowValue = factor.getPredefinedResult();
        } else {
            sensiValue = factor.getBaseSensitivityValue();
            flowValue = factor.getFunctionReference();
            for (ComputedContingencyElement contingencyElement : contingencyElements) {
                if (contingencyElement.getElement().getId().equals(factor.getFunctionLfBranchId())
                        || contingencyElement.getElement().getId().equals(factor.getFactor().getVariable().getId())) {
                    // the sensitivity on a removed branch is 0, the sensitivity if the variable was a removed branch is 0
                    sensiValue = 0d;
                    flowValue = 0d;
                    break;
                }
                double contingencySensitivity = p1.calculate(contingenciesStates, contingencyElement.getContingencyIndex());
                flowValue += contingencyElement.getAlphaForFunctionReference() * contingencySensitivity;
                sensiValue +=  contingencyElement.getAlphaForSensitivityValue() * contingencySensitivity;
            }
        }
        return new SensitivityValue(factor.getFactor(), sensiValue * PerUnit.SB, flowValue * PerUnit.SB, 0);
    }

    protected void setBaseCaseSensitivityValues(List<SensitivityFactorGroup> factorGroups, DenseMatrix factorsState) {
        for (SensitivityFactorGroup factorGroup : factorGroups) {
            for (LfSensitivityFactor factor : factorGroup.getFactors()) {
                factor.setBaseCaseSensitivityValue(factor.getEquationTerm().calculate(factorsState, factorGroup.getIndex()));
            }
        }
    }

    protected List<SensitivityValue> calculateSensitivityValues(List<SensitivityFactorGroup> factorGroups, DenseMatrix factorStates,
                                                                DenseMatrix contingenciesStates, DenseMatrix flowStates, Collection<ComputedContingencyElement> contingencyElements) {
        List<SensitivityValue> sensitivityValuesContingencies = new ArrayList<>(factorGroups.stream().mapToInt(group -> group.getFactors().size()).sum());
        setAlphas(contingencyElements, flowStates, contingenciesStates, 0, ComputedContingencyElement::setAlphaForFunctionReference);
        for (SensitivityFactorGroup factorGroup : factorGroups) {
            setAlphas(contingencyElements, factorStates, contingenciesStates, factorGroup.getIndex(), ComputedContingencyElement::setAlphaForSensitivityValue);
            for (LfSensitivityFactor factor : factorGroup.getFactors()) {
                sensitivityValuesContingencies.add(createBranchSensitivityValue(factor, contingenciesStates, contingencyElements));
            }
        }
        return sensitivityValuesContingencies;
    }

    private void setAlphas(Collection<ComputedContingencyElement> contingencyElements, DenseMatrix states,
                           DenseMatrix contingenciesStates, int columnState, BiConsumer<ComputedContingencyElement, Double> setValue) {
        if (contingencyElements.size() == 1) {
            ComputedContingencyElement element = contingencyElements.iterator().next();
            LfBranch lfBranch = element.getLfBranch();
            ClosedBranchSide1DcFlowEquationTerm p1 = element.getLfBranchEquation();
            // we solve a*alpha = b
            double a = lfBranch.getPiModel().getX() / PerUnit.SB - (contingenciesStates.get(p1.getVariables().get(0).getRow(), element.getContingencyIndex())
                    - contingenciesStates.get(p1.getVariables().get(1).getRow(), element.getContingencyIndex()));
            double b = states.get(p1.getVariables().get(0).getRow(), columnState) - states.get(p1.getVariables().get(1).getRow(), columnState);
            setValue.accept(element, b / a);
        } else {
            // FIXME: direct resolution if contingencyElements.size() == 2
            ComputedContingencyElement.setLocalIndexes(contingencyElements);
            DenseMatrix rhs = new DenseMatrix(contingencyElements.size(), 1);
            DenseMatrix matrix = new DenseMatrix(contingencyElements.size(), contingencyElements.size());
            for (ComputedContingencyElement element : contingencyElements) {
                LfBranch lfBranch = element.getLfBranch();
                ClosedBranchSide1DcFlowEquationTerm p1 = element.getLfBranchEquation();
                rhs.set(element.getLocalIndex(), 0, states.get(p1.getVariables().get(0).getRow(), columnState)
                        - states.get(p1.getVariables().get(1).getRow(), columnState)
                );
                for (ComputedContingencyElement element2 : contingencyElements) {
                    double value = 0d;
                    if (element.equals(element2)) {
                        value = lfBranch.getPiModel().getX() / PerUnit.SB;
                    }
                    value = value - (contingenciesStates.get(p1.getVariables().get(0).getRow(), element2.getContingencyIndex())
                            - contingenciesStates.get(p1.getVariables().get(1).getRow(), element2.getContingencyIndex()));
                    matrix.set(element.getLocalIndex(), element2.getLocalIndex(), value);
                }
            }
            LUDecomposition lu = matrix.decomposeLU();
            lu.solve(rhs); // rhs now contains state matrix
            contingencyElements.forEach(element -> setValue.accept(element, rhs.get(element.getLocalIndex(), 0)));
        }
    }

    private Set<ComputedContingencyElement> getGroupOfElementsBreakingConnectivity(LfNetwork lfNetwork, DenseMatrix contingenciesStates,
                                                                                   Collection<ComputedContingencyElement> contingencyElements,
                                                                                   EquationSystem equationSystem) {
        // use a sensitivity-criterion to detect the loss of connectivity after a contingency
        // we consider a +1 -1 on a line, and we observe the sensitivity of these injections on the other contingency elements
        // if the sum of the sensitivities (in absolute value) is 1, it means that all the flow is going through the lines with a non-zero sensitivity
        // thus, losing these lines will lose the connectivity
        Set<ComputedContingencyElement> groupOfElementsBreakingConnectivity = new HashSet<>();
        for (ComputedContingencyElement element : contingencyElements) {
            Set<ComputedContingencyElement> responsibleElements = new HashSet<>();
            double sum = 0d;
            for (ComputedContingencyElement element2 : contingencyElements) {
                LfBranch branch = lfNetwork.getBranchById(element2.getElement().getId());
                ClosedBranchSide1DcFlowEquationTerm p = equationSystem.getEquationTerm(SubjectType.BRANCH, branch.getNum(), ClosedBranchSide1DcFlowEquationTerm.class);
                double value = Math.abs(p.calculate(contingenciesStates, element.getContingencyIndex()));
                if (value > CONNECTIVITY_LOSS_THRESHOLD) {
                    responsibleElements.add(element2);
                }
                sum += value;
            }
            if (sum * PerUnit.SB > 1d - CONNECTIVITY_LOSS_THRESHOLD) {
                // all lines that have a non-0 sensitivity associated to "element" breaks the connectivity
                groupOfElementsBreakingConnectivity.addAll(responsibleElements);
            }
        }
        return groupOfElementsBreakingConnectivity;
    }

    protected void fillRhsContingency(final LfNetwork lfNetwork, final EquationSystem equationSystem,
                                      final Collection<ComputedContingencyElement> contingencyElements, final Matrix rhs) {
        for (ComputedContingencyElement element : contingencyElements) {
            LfBranch lfBranch = lfNetwork.getBranchById(element.getElement().getId());
            if (lfBranch.getBus1() == null || lfBranch.getBus2() == null) {
                continue;
            }
            LfBus bus1 = lfBranch.getBus1();
            LfBus bus2 = lfBranch.getBus2();
            if (bus1.isSlack()) {
                Equation p = equationSystem.getEquation(bus2.getNum(), EquationType.BUS_P).orElseThrow(IllegalStateException::new);
                rhs.set(p.getColumn(), element.getContingencyIndex(), -1 / PerUnit.SB);
            } else if (bus2.isSlack()) {
                Equation p = equationSystem.getEquation(bus1.getNum(), EquationType.BUS_P).orElseThrow(IllegalStateException::new);
                rhs.set(p.getColumn(), element.getContingencyIndex(), 1 / PerUnit.SB);
            } else {
                Equation p1 = equationSystem.getEquation(bus1.getNum(), EquationType.BUS_P).orElseThrow(IllegalStateException::new);
                Equation p2 = equationSystem.getEquation(bus2.getNum(), EquationType.BUS_P).orElseThrow(IllegalStateException::new);
                rhs.set(p1.getColumn(), element.getContingencyIndex(), 1 / PerUnit.SB);
                rhs.set(p2.getColumn(), element.getContingencyIndex(), -1 / PerUnit.SB);
            }
        }
    }

    protected DenseMatrix initFactorsRhs(LfNetwork lfNetwork, EquationSystem equationSystem, List<SensitivityFactorGroup> factorsGroups) {
        DenseMatrix rhs = new DenseMatrix(equationSystem.getSortedEquationsToSolve().size(), factorsGroups.size());
        fillRhsSensitivityVariable(lfNetwork, equationSystem, factorsGroups, rhs);
        return rhs;
    }

    protected DenseMatrix initContingencyRhs(LfNetwork lfNetwork, EquationSystem equationSystem, Collection<ComputedContingencyElement> contingencyElements) {
        DenseMatrix rhs = new DenseMatrix(equationSystem.getSortedEquationsToSolve().size(), contingencyElements.size());
        fillRhsContingency(lfNetwork, equationSystem, contingencyElements, rhs);
        return rhs;
    }

    private void detectConnectivityLoss(LfNetwork lfNetwork, DenseMatrix states, List<PropagatedContingency> contingencies, Map<String, ComputedContingencyElement> contingenciesElements,
                                        EquationSystem equationSystem, Collection<PropagatedContingency> nonLosingConnectivityContingencies,
                                        Map<Set<ComputedContingencyElement>, List<PropagatedContingency>> contingenciesByGroupOfElementsBreakingConnectivity) {
        for (PropagatedContingency contingency : contingencies) {
            Set<ComputedContingencyElement> groupOfElementsBreakingConnectivity = getGroupOfElementsBreakingConnectivity(lfNetwork, states,
                    contingency.getBranchIdsToOpen().stream().map(contingenciesElements::get).collect(Collectors.toList()), equationSystem);
            if (groupOfElementsBreakingConnectivity.isEmpty()) { // connectivity not broken
                nonLosingConnectivityContingencies.add(contingency);
            } else {
                contingenciesByGroupOfElementsBreakingConnectivity.computeIfAbsent(groupOfElementsBreakingConnectivity, key -> new LinkedList<>()).add(contingency);
            }
        }
    }

    private Set<String> getElementsToReconnect(GraphDecrementalConnectivity<LfBus> connectivity, Set<ComputedContingencyElement> breakingConnectivityCandidates) {
        Set<String> elementsToReconnect = new HashSet<>();

        Map<Pair<Integer, Integer>, ComputedContingencyElement> elementByConnectedComponents = new HashMap<>();
        for (ComputedContingencyElement element : breakingConnectivityCandidates) {
            int bus1Cc = connectivity.getComponentNumber(element.getLfBranch().getBus1());
            int bus2Cc = connectivity.getComponentNumber(element.getLfBranch().getBus2());

            Pair<Integer, Integer> pairOfCc = bus1Cc > bus2Cc ? Pair.of(bus2Cc, bus1Cc) : Pair.of(bus1Cc, bus2Cc);
            // we only need to reconnect one line to restore connectivity
            elementByConnectedComponents.put(pairOfCc, element);
        }

        Map<Integer, Set<Integer>> connections = new HashMap<>();
        for (int i = 0; i < connectivity.getSmallComponents().size() + 1; i++) {
            connections.put(i, Collections.singleton(i));
        }

        for (Map.Entry<Pair<Integer, Integer>, ComputedContingencyElement> elementsByCc : elementByConnectedComponents.entrySet()) {
            Integer cc1 = elementsByCc.getKey().getKey();
            Integer cc2 = elementsByCc.getKey().getValue();
            if (connections.get(cc1).contains(cc2)) {
                // cc are already connected
                continue;
            }
            elementsToReconnect.add(elementsByCc.getValue().getElement().getId());
            Set<Integer> newCc = new HashSet<>();
            newCc.addAll(connections.get(cc1));
            newCc.addAll(connections.get(cc2));
            newCc.forEach(integer -> connections.put(integer, newCc));
        }

        return elementsToReconnect;
    }

    public void cutConnectivity(LfNetwork lfNetwork, GraphDecrementalConnectivity<LfBus> connectivity, Collection<ComputedContingencyElement> elementsPotentiallyBreakingConnectivity) {
        elementsPotentiallyBreakingConnectivity.stream()
                                               .map(ComputedContingencyElement::getElement)
                                               .map(element -> lfNetwork.getBranchById(element.getId()))
                                               .forEach(lfBranch -> connectivity.cut(lfBranch.getBus1(), lfBranch.getBus2()));
    }

    public Pair<List<SensitivityValue>, Map<String, List<SensitivityValue>>> analyse(Network network, List<SensitivityFactor> factors,
                                                                                     List<PropagatedContingency> contingencies, LoadFlowParameters lfParameters,
                                                                                     OpenLoadFlowParameters lfParametersExt) {
        Objects.requireNonNull(network);
        Objects.requireNonNull(factors);
        Objects.requireNonNull(lfParametersExt);

        Profiler profiler = Profiler.create();

        // create the network (we only manage main connected component)
        List<LfNetwork> lfNetworks = LfNetwork.load(network, new LfNetworkParameters(lfParametersExt.getSlackBusSelector(), false, false, lfParameters.isTwtSplitShuntAdmittance(), false, lfParametersExt.getPlausibleActivePowerLimit()), profiler);
        LfNetwork lfNetwork = lfNetworks.get(0);
        checkContingencies(lfNetwork, contingencies);
        checkSensitivities(network, lfNetwork, factors);
        checkLoadFlowParameters(lfParameters);
        // create DC equation system for sensitivity analysis
        EquationSystem equationSystem = DcEquationSystem.create(lfNetwork, new VariableSet(),
                new DcEquationSystemCreationParameters(true, true, true, lfParametersExt.isDcUseTransformerRatio()), profiler);

        // we wrap the factor into a class that allows us to have access to their branch and EquationTerm instantly
        List<LfSensitivityFactor> lfFactors = factors.stream().map(factor -> LfSensitivityFactor.create(factor, network, lfNetwork, equationSystem)).collect(Collectors.toList());

        // index factors by variable group to compute a minimal number of states
        List<SensitivityFactorGroup> factorGroups = createFactorGroups(network, lfFactors);
        if (factorGroups.isEmpty()) {
            return Pair.of(Collections.emptyList(), Collections.emptyMap());
        }

        boolean hasGlsk = factorGroups.stream().anyMatch(group -> group instanceof LinearGlskGroup);

        // compute the participation for each injection factor (+1 on the injection and then -participation factor on all
        // buses that contain elements participating to slack distribution
        List<ParticipatingElement> participatingElements = null;
        Map<String, Double> slackParticipationByBus;
        if (lfParameters.isDistributedSlack()) {
            participatingElements = getParticipatingElements(lfNetwork, lfParameters);
            slackParticipationByBus = participatingElements.stream().collect(Collectors.toMap(
                element -> element.getLfBus().getId(),
                element -> -element.getFactor(),
                Double::sum
            ));
        } else {
            slackParticipationByBus = Collections.singletonMap(lfNetwork.getSlackBus().getId(), -1d);
        }
        computeInjectionFactors(slackParticipationByBus, factorGroups);

        // contingencies management
        Map<String, ComputedContingencyElement> contingenciesElements =
            contingencies.stream()
                             .flatMap(contingency -> contingency.getBranchIdsToOpen().stream())
                             .map(branch -> new ComputedContingencyElement(new BranchContingency(branch), lfNetwork, equationSystem))
                             .filter(element -> element.getLfBranchEquation() != null)
                             .collect(Collectors.toMap(
                                 computedContingencyElement -> computedContingencyElement.getElement().getId(),
                                 computedContingencyElement -> computedContingencyElement,
                                 (existing, replacement) -> existing
                             ));
        ComputedContingencyElement.setContingencyIndexes(contingenciesElements.values());

        // create jacobian matrix either using base network calculated voltages or nominal voltages
        VoltageInitializer voltageInitializer = lfParameters.getVoltageInitMode() == LoadFlowParameters.VoltageInitMode.PREVIOUS_VALUES ? new PreviousValueVoltageInitializer()
                : new UniformValueVoltageInitializer();
        try (JacobianMatrix j = createJacobianMatrix(equationSystem, voltageInitializer, profiler)) {

            // run DC load on pre-contingency network
            DenseMatrix flowStates = setReferenceActivePowerFlows(lfNetwork, equationSystem, j, lfFactors, lfParameters, participatingElements, null);

            // compute the pre-contingency sensitivity values + the states with +1 -1 to model the contingencies
            DenseMatrix factorsStates = initFactorsRhs(lfNetwork, equationSystem, factorGroups); // this is the rhs for the moment
            DenseMatrix contingenciesStates = initContingencyRhs(lfNetwork, equationSystem, contingenciesElements.values()); // rhs with +1 -1 on contingency elements
            j.solveTransposed(factorsStates); // states for the sensitivity factors
            j.solveTransposed(contingenciesStates); // states for the +1 -1 of the contingencies

            // sensitivity values for pre-contingency network
            setBaseCaseSensitivityValues(factorGroups, factorsStates);
            List<SensitivityValue> sensitivityValues = calculateSensitivityValues(factorGroups,
                    factorsStates, contingenciesStates, flowStates, Collections.emptyList());

            // connectivity analysis by contingency
            // we will index contingencies by a list of branch that may breaks connectivity
            // for example, if in the network, loosing line L1 breaks connectivity, and loosing L2 and L3 together breaks connectivity,
            // the index would be: {L1, L2, L3}
            // todo: There may be a better way to group the contingencies that will have the same connectivities afterwards
            Collection<PropagatedContingency> nonLosingConnectivityContingencies = new LinkedList<>();
            Map<Set<ComputedContingencyElement>, List<PropagatedContingency>> contingenciesByGroupOfElementsBreakingConnectivity = new HashMap<>();

            detectConnectivityLoss(lfNetwork, contingenciesStates, contingencies, contingenciesElements, equationSystem,
                    nonLosingConnectivityContingencies, contingenciesByGroupOfElementsBreakingConnectivity);

            Map<String, List<SensitivityValue>> contingenciesValue = new HashMap<>();
            // compute the contingencies without loss of connectivity
            for (PropagatedContingency contingency : nonLosingConnectivityContingencies) {
                contingenciesValue.put(contingency.getContingency().getId(), calculateSensitivityValues(factorGroups, factorsStates, contingenciesStates,
                        flowStates, contingency.getBranchIdsToOpen().stream().map(contingenciesElements::get).collect(Collectors.toList())));
            }

            if (contingenciesByGroupOfElementsBreakingConnectivity.isEmpty()) {
                return Pair.of(sensitivityValues, contingenciesValue);
            }

            GraphDecrementalConnectivity<LfBus> connectivity = lfNetwork.createDecrementalConnectivity();

            // compute the contingencies with loss of connectivity
            for (Map.Entry<Set<ComputedContingencyElement>, List<PropagatedContingency>> entry : contingenciesByGroupOfElementsBreakingConnectivity.entrySet()) {
                Set<ComputedContingencyElement> breakingConnectivityCandidates = entry.getKey();
                List<PropagatedContingency> contingencyList = entry.getValue();
                lfFactors.forEach(factor -> factor.setPredefinedResult(null));
                cutConnectivity(lfNetwork, connectivity, breakingConnectivityCandidates);
                int mainComponent = connectivity.getComponentNumber(lfNetwork.getSlackBus());
                setPredefinedResults(lfFactors, connectivity, mainComponent); // check if factors are still in the main component

                // some elements of the GLSK may not be in the connected component anymore, we recompute the injections
                rescaleGlsk(factorGroups, connectivity, mainComponent);

                // null and unused if slack is not distributed
                List<ParticipatingElement> participatingElementsForThisConnectivity = participatingElements;

                // we need to recompute the factor states because the connectivity changed
                if (lfParameters.isDistributedSlack() || hasGlsk) {
                    Map<String, Double> slackParticipationByBusForThisConnectivity;

                    if (lfParameters.isDistributedSlack()) {
                        participatingElementsForThisConnectivity = getParticipatingElements(lfNetwork, lfParameters, element -> connectivity.getComponentNumber(element.getLfBus()) == mainComponent); // will also be used to recompute the loadflow
                        slackParticipationByBusForThisConnectivity = participatingElementsForThisConnectivity.stream().collect(Collectors.toMap(
                            element -> element.getLfBus().getId(),
                            element -> -element.getFactor(),
                            Double::sum
                        ));
                    } else {
                        slackParticipationByBusForThisConnectivity = Collections.singletonMap(lfNetwork.getSlackBus().getId(), -1d);
                    }

                    computeInjectionFactors(slackParticipationByBusForThisConnectivity, factorGroups); // write the right injections in the factor groups
                    factorsStates.reset(); // avoid creating a new matrix to avoid buffer allocation time
                    fillRhsSensitivityVariable(lfNetwork, equationSystem, factorGroups, factorsStates);
                    j.solveTransposed(factorsStates); // get the states for the new connectivity
                    setBaseCaseSensitivityValues(factorGroups, factorsStates); // use this state to compute the base sensitivity (without +1-1)
                }

                flowStates = setReferenceActivePowerFlows(lfNetwork, equationSystem, j, lfFactors, lfParameters, participatingElementsForThisConnectivity, connectivity);

                Set<String> elementsToReconnect = getElementsToReconnect(connectivity, breakingConnectivityCandidates);

                for (PropagatedContingency contingency : contingencyList) {
                    contingenciesValue.put(contingency.getContingency().getId(), calculateSensitivityValues(factorGroups, factorsStates, contingenciesStates, flowStates,
                        contingency.getBranchIdsToOpen().stream().filter(element -> !elementsToReconnect.contains(element)).map(contingenciesElements::get).collect(Collectors.toList())
                    ));
                }

                connectivity.reset();
            }

            return Pair.of(sensitivityValues, contingenciesValue);
        }
    }
}
