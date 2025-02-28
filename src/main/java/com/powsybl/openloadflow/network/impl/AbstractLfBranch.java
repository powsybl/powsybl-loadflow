/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network.impl;

import com.powsybl.iidm.network.*;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.sa.LimitReductionManager;
import com.powsybl.openloadflow.util.Evaluable;
import com.powsybl.openloadflow.util.PerUnit;
import net.jafama.FastMath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Supplier;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public abstract class AbstractLfBranch extends AbstractElement implements LfBranch {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractLfBranch.class);

    protected final LfBus bus1;

    protected final LfBus bus2;

    private List<LfLimit> currentLimits1;
    private List<LfLimit> activePowerLimits1;
    private List<LfLimit> apparentPowerLimits1;

    private List<LfLimit> currentLimits2;
    private List<LfLimit> activePowerLimits2;
    private List<LfLimit> apparentPowerLimits2;

    protected final PiModel piModel;

    protected TransformerPhaseControl phaseControl;

    protected boolean phaseControlEnabled = false;

    protected TransformerVoltageControl voltageControl;

    protected boolean voltageControlEnabled = false;

    protected TransformerReactivePowerControl transformerReactivePowerControl;

    static class ZeroImpedanceContext {

        boolean spanningTreeEdge = false;

        boolean zeroImpedance = false;
    }

    protected final Map<LoadFlowModel, ZeroImpedanceContext> zeroImpedanceContextByModel = new EnumMap<>(LoadFlowModel.class);

    protected Evaluable a1;

    private GeneratorReactivePowerControl generatorReactivePowerControl;

    protected LfAsymLine asymLine;

    protected AbstractLfBranch(LfNetwork network, LfBus bus1, LfBus bus2, PiModel piModel, LfNetworkParameters parameters) {
        super(network);
        this.bus1 = bus1;
        this.bus2 = bus2;
        this.piModel = Objects.requireNonNull(piModel);
        this.piModel.setBranch(this);
        for (LoadFlowModel loadFlowModel : LoadFlowModel.values()) {
            zeroImpedanceContextByModel.put(loadFlowModel, new ZeroImpedanceContext());
        }
        if (!parameters.isMinImpedance()) {
            for (LoadFlowModel loadFlowModel : LoadFlowModel.values()) {
                zeroImpedanceContextByModel.get(loadFlowModel).zeroImpedance = isZeroImpedanceBranch(piModel, loadFlowModel, parameters.getLowImpedanceThreshold());
            }
        }
    }

    @Override
    public Optional<ThreeSides> getOriginalSide() {
        return Optional.empty();
    }

    /**
     * Create the list of LfLimits from a LoadingLimits and a list of reductions.
     * The resulting list will contain the permanent limit
     */
    protected static List<LfLimit> createSortedLimitsList(LoadingLimits loadingLimits, LfBus bus, double[] limitReductions) {
        List<LfLimit> sortedLimits = new ArrayList<>(3);
        if (loadingLimits != null) {
            double toPerUnit = getScaleForLimitType(loadingLimits.getLimitType(), bus);

            int i = 0;
            for (LoadingLimits.TemporaryLimit temporaryLimit : loadingLimits.getTemporaryLimits()) {
                if (temporaryLimit.getAcceptableDuration() != 0) {
                    // it is not useful to add a limit with acceptable duration equal to zero as the only value plausible
                    // for this limit is infinity.
                    // https://javadoc.io/doc/com.powsybl/powsybl-core/latest/com/powsybl/iidm/network/CurrentLimits.html
                    double reduction = limitReductions.length == 0 ? 1d : limitReductions[i + 1]; // Temporary limit's reductions are stored starting from index 1 in `limitReductions`
                    double originalValuePerUnit = temporaryLimit.getValue() * toPerUnit;
                    sortedLimits.add(0, LfLimit.createTemporaryLimit(temporaryLimit.getName(), temporaryLimit.getAcceptableDuration(),
                            originalValuePerUnit, reduction));
                }
                i++;
            }
            double reduction = limitReductions.length == 0 ? 1d : limitReductions[0];
            sortedLimits.add(LfLimit.createPermanentLimit(loadingLimits.getPermanentLimit() * toPerUnit, reduction));
        }
        if (sortedLimits.size() > 1) {
            // we only make that fix if there is more than a permanent limit attached to the branch.
            for (int i = sortedLimits.size() - 1; i > 0; i--) {
                // From the permanent limit to the most serious temporary limit.
                sortedLimits.get(i).setAcceptableDuration(sortedLimits.get(i - 1).getAcceptableDuration());
            }
            sortedLimits.get(0).setAcceptableDuration(0);
        }
        return sortedLimits;
    }

    @Override
    public ElementType getType() {
        return ElementType.BRANCH;
    }

    @Override
    public LfBus getBus1() {
        return bus1;
    }

    @Override
    public LfBus getBus2() {
        return bus2;
    }

    private List<LfLimit> getLimits1(LimitType type) {
        switch (type) {
            case ACTIVE_POWER -> {
                return activePowerLimits1;
            }
            case APPARENT_POWER -> {
                return apparentPowerLimits1;
            }
            case CURRENT -> {
                return currentLimits1;
            }
            default -> throw new UnsupportedOperationException(String.format("Getting limits of type %s is not supported.", type));
        }
    }

    private void setLimits1(LimitType type, List<LfLimit> limits) {
        switch (type) {
            case ACTIVE_POWER -> activePowerLimits1 = limits;
            case APPARENT_POWER -> apparentPowerLimits1 = limits;
            case CURRENT -> currentLimits1 = limits;
            default -> throw new UnsupportedOperationException(String.format("Getting limits of type %s is not supported.", type));
        }
    }

    private List<LfLimit> getLimits2(LimitType type) {
        switch (type) {
            case ACTIVE_POWER -> {
                return activePowerLimits2;
            }
            case APPARENT_POWER -> {
                return apparentPowerLimits2;
            }
            case CURRENT -> {
                return currentLimits2;
            }
            default -> throw new UnsupportedOperationException(String.format("Getting limits of type %s is not supported.", type));
        }
    }

    private void setLimits2(LimitType type, List<LfLimit> limits) {
        switch (type) {
            case ACTIVE_POWER -> activePowerLimits2 = limits;
            case APPARENT_POWER -> apparentPowerLimits2 = limits;
            case CURRENT -> currentLimits2 = limits;
            default -> throw new UnsupportedOperationException(String.format("Getting limits of type %s is not supported.", type));
        }
    }

    public <T extends LoadingLimits> List<LfLimit> getLimits1(LimitType type, Supplier<Optional<T>> loadingLimitsSupplier, LimitReductionManager limitReductionManager) {
        var limits = getLimits1(type);
        if (limits == null) {
            // It is possible to apply the reductions here since the only supported ContingencyContext for LimitReduction is ALL.
            var loadingLimits = loadingLimitsSupplier.get().orElse(null);
            limits = createSortedLimitsList(loadingLimits, bus1,
                    getLimitReductions(TwoSides.ONE, limitReductionManager, loadingLimits));
            setLimits1(type, limits);
        }
        return limits;
    }

    public <T extends LoadingLimits> List<LfLimit> getLimits2(LimitType type, Supplier<Optional<T>> loadingLimitsSupplier, LimitReductionManager limitReductionManager) {
        var limits = getLimits2(type);
        if (limits == null) {
            // It is possible to apply the reductions here since the only supported ContingencyContext for LimitReduction is ALL.
            var loadingLimits = loadingLimitsSupplier.get().orElse(null);
            limits = createSortedLimitsList(loadingLimits, bus2,
                    getLimitReductions(TwoSides.TWO, limitReductionManager, loadingLimits));
            setLimits2(type, limits);
        }
        return limits;
    }

    @Override
    public PiModel getPiModel() {
        return piModel;
    }

    @Override
    public Optional<TransformerPhaseControl> getPhaseControl() {
        return Optional.ofNullable(phaseControl);
    }

    @Override
    public void setPhaseControl(TransformerPhaseControl phaseControl) {
        this.phaseControl = phaseControl;
    }

    @Override
    public boolean isPhaseController() {
        return phaseControl != null && phaseControl.getControllerBranch() == this;
    }

    @Override
    public boolean isPhaseControlled() {
        return phaseControl != null && phaseControl.getControlledBranch() == this;
    }

    @Override
    public boolean isPhaseControlEnabled() {
        return phaseControlEnabled;
    }

    @Override
    public void setPhaseControlEnabled(boolean phaseControlEnabled) {
        if (this.phaseControlEnabled != phaseControlEnabled) {
            this.phaseControlEnabled = phaseControlEnabled;
            for (LfNetworkListener listener : network.getListeners()) {
                listener.onTransformerPhaseControlChange(this, phaseControlEnabled);
            }
        }
    }

    protected void updateTapPosition(PhaseTapChanger ptc) {
        int tapPosition = Transformers.findTapPosition(ptc, Math.toDegrees(getPiModel().getA1()));
        ptc.setTapPosition(tapPosition);
    }

    protected void updateTapPosition(RatioTapChanger rtc, double ptcRho, double rho) {
        Transformers.findTapPosition(rtc, ptcRho, rho).ifPresent(rtc::setTapPosition);
    }

    protected static double getScaleForLimitType(LimitType type, LfBus bus) {
        switch (type) {
            case ACTIVE_POWER,
                 APPARENT_POWER:
                return 1.0 / PerUnit.SB;
            case CURRENT:
                return 1.0 / PerUnit.ib(bus.getNominalV());
            case VOLTAGE:
            default:
                throw new UnsupportedOperationException(String.format("Getting scale for limit type %s is not supported.", type));
        }
    }

    @Override
    public Optional<TransformerVoltageControl> getVoltageControl() {
        return Optional.ofNullable(voltageControl);
    }

    @Override
    public boolean isVoltageController() {
        return voltageControl != null;
    }

    @Override
    public void setVoltageControl(TransformerVoltageControl transformerVoltageControl) {
        this.voltageControl = transformerVoltageControl;
    }

    @Override
    public boolean isVoltageControlEnabled() {
        return voltageControlEnabled;
    }

    public void setVoltageControlEnabled(boolean voltageControlEnabled) {
        if (this.voltageControlEnabled != voltageControlEnabled) {
            this.voltageControlEnabled = voltageControlEnabled;
            for (LfNetworkListener listener : network.getListeners()) {
                listener.onTransformerVoltageControlChange(this, voltageControlEnabled);
            }
        }
    }

    @Override
    public Optional<TransformerReactivePowerControl> getTransformerReactivePowerControl() {
        return Optional.ofNullable(transformerReactivePowerControl);
    }

    @Override
    public void setTransformerReactivePowerControl(TransformerReactivePowerControl transformerReactivePowerControl) {
        this.transformerReactivePowerControl = transformerReactivePowerControl;
    }

    @Override
    public boolean isTransformerReactivePowerController() {
        return transformerReactivePowerControl != null && transformerReactivePowerControl.getControllerBranch() == this;
    }

    @Override
    public boolean isTransformerReactivePowerControlled() {
        return transformerReactivePowerControl != null && transformerReactivePowerControl.getControlledBranch() == this;
    }

    public double computeApparentPower1() {
        double p = getP1().eval();
        double q = getQ1().eval();
        return FastMath.sqrt(p * p + q * q);
    }

    @Override
    public double computeApparentPower2() {
        double p = getP2().eval();
        double q = getQ2().eval();
        return FastMath.sqrt(p * p + q * q);
    }

    @Override
    public boolean isZeroImpedance(LoadFlowModel loadFlowModel) {
        return zeroImpedanceContextByModel.get(loadFlowModel).zeroImpedance;
    }

    @Override
    public void setSpanningTreeEdge(LoadFlowModel loadFlowModel, boolean spanningTreeEdge) {
        ZeroImpedanceContext context = zeroImpedanceContextByModel.get(loadFlowModel);
        if (spanningTreeEdge != context.spanningTreeEdge) {
            context.spanningTreeEdge = spanningTreeEdge;
            for (LfNetworkListener listener : network.getListeners()) {
                listener.onZeroImpedanceNetworkSpanningTreeChange(this, loadFlowModel, spanningTreeEdge);
            }
        }
    }

    @Override
    public boolean isSpanningTreeEdge(LoadFlowModel loadFlowModel) {
        network.updateZeroImpedanceCache(loadFlowModel);
        return zeroImpedanceContextByModel.get(loadFlowModel).spanningTreeEdge;
    }

    @Override
    public Evaluable getA1() {
        return a1;
    }

    @Override
    public void setA1(Evaluable a1) {
        this.a1 = a1;
    }

    public Optional<GeneratorReactivePowerControl> getGeneratorReactivePowerControl() {
        return Optional.ofNullable(generatorReactivePowerControl);
    }

    @Override
    public void setGeneratorReactivePowerControl(GeneratorReactivePowerControl pGeneratorReactivePowerControl) {
        this.generatorReactivePowerControl = Objects.requireNonNull(pGeneratorReactivePowerControl);
    }

    @Override
    public boolean isConnectedAtBothSides() {
        return isConnectedSide1() && isConnectedSide2();
    }

    @Override
    public void setMinZ(double lowImpedanceThreshold) {
        for (LoadFlowModel loadFlowModel : List.of(LoadFlowModel.AC, LoadFlowModel.DC)) {
            if (piModel.setMinZ(lowImpedanceThreshold, loadFlowModel) ||
                    LoadFlowModel.DC.equals(loadFlowModel) && isZeroImpedance(loadFlowModel)) {
                // Note: For DC load flow model, the min impedance has already been set by AC load flow model but
                //       the zero impedance field must still be updated.
                LOGGER.trace("Branch {} has a low impedance in {}, set to min {}", getId(), loadFlowModel, lowImpedanceThreshold);
                zeroImpedanceContextByModel.get(loadFlowModel).zeroImpedance = false;
            }
        }
    }

    private static boolean isZeroImpedanceBranch(PiModel piModel, LoadFlowModel loadFlowModel, double lowImpedanceThreshold) {
        if (loadFlowModel == LoadFlowModel.DC) {
            return FastMath.abs(piModel.getX()) < lowImpedanceThreshold;
        } else {
            return piModel.getZ() < lowImpedanceThreshold;
        }
    }

    private void updateZeroImpedanceNetworks(boolean disabled, LoadFlowModel loadFlowModel) {
        if (isZeroImpedance(loadFlowModel)) {
            LfZeroImpedanceNetwork zn1 = bus1.getZeroImpedanceNetwork(loadFlowModel);
            LfZeroImpedanceNetwork zn2 = bus2.getZeroImpedanceNetwork(loadFlowModel);
            if (zn1 != null && zn2 != null) {
                if (disabled) {
                    if (zn1 == zn2) {
                        // zero impedance network split (maybe)
                        zn1.removeBranchAndTryToSplit(this);
                    } else {
                        throw new IllegalStateException("Should not happen");
                    }
                } else {
                    if (zn1 != zn2) {
                        // zero impedance network merge
                        LfZeroImpedanceNetwork.addBranchAndMerge(zn1, zn2, this);
                    } else {
                        // we need to add the branch again to zero impedance graph and update spanning tree as
                        // this branch might become part of spanning tree (and was not before because disabled)
                        zn1.addBranch(this);
                    }
                }
            }
        }
    }

    @Override
    public void setDisabled(boolean disabled) {
        if (disabled != this.disabled) {
            this.disabled = disabled;
            notifyDisable();
            if (bus1 != null && bus2 != null) {
                updateZeroImpedanceNetworks(disabled, LoadFlowModel.AC);
                updateZeroImpedanceNetworks(disabled, LoadFlowModel.DC);
            }
        }
    }

    @Override
    public LfAsymLine getAsymLine() {
        return asymLine;
    }

    @Override
    public void setAsymLine(LfAsymLine asymLine) {
        this.asymLine = asymLine;
    }

    @Override
    public boolean isAsymmetric() {
        if (asymLine != null) {
            return asymLine.getAdmittanceMatrix().isCoupled();
        }
        return false;
    }
}
