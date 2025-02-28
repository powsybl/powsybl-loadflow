/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network.impl;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.*;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.sa.LimitReductionManager;
import com.powsybl.openloadflow.util.PerUnit;
import com.powsybl.security.results.BranchResult;
import com.powsybl.security.results.ThreeWindingsTransformerResult;

import java.util.*;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public final class LfLegBranch extends AbstractImpedantLfBranch {

    private final Ref<ThreeWindingsTransformer> twtRef;

    private final Ref<ThreeWindingsTransformer.Leg> legRef;

    private LfLegBranch(LfNetwork network, LfBus bus1, LfBus bus0, PiModel piModel, ThreeWindingsTransformer twt, ThreeWindingsTransformer.Leg leg,
                        LfNetworkParameters parameters) {
        super(network, bus1, bus0, piModel, parameters);
        this.twtRef = Ref.create(twt, parameters.isCacheEnabled());
        this.legRef = Ref.create(leg, parameters.isCacheEnabled());
    }

    private ThreeWindingsTransformer getTwt() {
        return twtRef.get();
    }

    private ThreeWindingsTransformer.Leg getLeg() {
        return legRef.get();
    }

    public static LfLegBranch create(LfNetwork network, LfBus bus1, LfBus bus0, ThreeWindingsTransformer twt,
                                     ThreeWindingsTransformer.Leg leg, LfTopoConfig topoConfig, LfNetworkParameters parameters) {
        Objects.requireNonNull(bus0);
        Objects.requireNonNull(twt);
        Objects.requireNonNull(leg);
        Objects.requireNonNull(parameters);

        String id = LfLegBranch.getId(leg.getSide(), twt.getId());
        boolean retainPtc = topoConfig.isRetainedPtc(id);
        boolean retainRtc = topoConfig.isRetainedRtc(id);

        PiModel piModel = null;

        double zb = PerUnit.zb(twt.getRatedU0());
        double baseRatio = Transformers.getRatioPerUnitBase(leg, twt);
        PhaseTapChanger ptc = leg.getPhaseTapChanger();
        if (ptc != null
                && (ptc.isRegulating()
                && ptc.getRegulationMode() != PhaseTapChanger.RegulationMode.FIXED_TAP || retainPtc)) {
            // we have a phase control, whatever we also have a voltage control or not, we create a pi model array
            // based on phase taps mixed with voltage current tap
            Integer rtcPosition = Transformers.getCurrentPosition(leg.getRatioTapChanger());
            List<PiModel> models = new ArrayList<>();
            for (int ptcPosition = ptc.getLowTapPosition(); ptcPosition <= ptc.getHighTapPosition(); ptcPosition++) {
                Transformers.TapCharacteristics tapCharacteristics = Transformers.getTapCharacteristics(twt, leg, rtcPosition, ptcPosition);
                models.add(Transformers.createPiModel(tapCharacteristics, zb, baseRatio, parameters.isTwtSplitShuntAdmittance()));
            }
            piModel = new PiModelArray(models, ptc.getLowTapPosition(), ptc.getTapPosition());
        }

        RatioTapChanger rtc = leg.getRatioTapChanger();
        if (rtc != null && (rtc.isRegulating() && rtc.hasLoadTapChangingCapabilities() || retainRtc)) {
            if (piModel == null) {
                // we have a voltage control, we create a pi model array based on voltage taps mixed with phase current
                // tap
                Integer ptcPosition = Transformers.getCurrentPosition(leg.getPhaseTapChanger());
                List<PiModel> models = new ArrayList<>();
                for (int rtcPosition = rtc.getLowTapPosition(); rtcPosition <= rtc.getHighTapPosition(); rtcPosition++) {
                    Transformers.TapCharacteristics tapCharacteristics = Transformers.getTapCharacteristics(twt, leg, rtcPosition, ptcPosition);
                    models.add(Transformers.createPiModel(tapCharacteristics, zb, baseRatio, parameters.isTwtSplitShuntAdmittance()));
                }
                piModel = new PiModelArray(models, rtc.getLowTapPosition(), rtc.getTapPosition());
            } else {
                throw new PowsyblException("Unsupported type of branch for voltage and phase controls of branch: " + twt.getId());
            }
        }

        if (piModel == null) {
            // we don't have any phase or voltage control, we create a simple pi model (single tap) based on phase current
            // tap and voltage current tap
            Transformers.TapCharacteristics tapCharacteristics = Transformers.getTapCharacteristics(twt, leg);
            piModel = Transformers.createPiModel(tapCharacteristics, zb, baseRatio, parameters.isTwtSplitShuntAdmittance());
        }

        LfLegBranch lfBranch = new LfLegBranch(network, bus1, bus0, piModel, twt, leg, parameters);
        if (bus1 != null && topoConfig.getBranchIdsOpenableSide1().contains(lfBranch.getId())) {
            lfBranch.setDisconnectionAllowedSide1(true);
        }
        return lfBranch;
    }

    public static String getId(String twtId, int legNum) {
        return twtId + "_leg_" + legNum;
    }

    public static String getId(ThreeSides side, String transformerId) {
        return getId(transformerId, side.getNum());
    }

    @Override
    public String getId() {
        return getId(getTwt().getId(), getLeg().getSide().getNum());
    }

    @Override
    public Optional<ThreeSides> getOriginalSide() {
        return Optional.of(getLeg().getSide());
    }

    @Override
    public BranchType getBranchType() {
        var leg = getLeg();
        return switch (leg.getSide()) {
            case ONE -> BranchType.TRANSFO_3_LEG_1;
            case TWO -> BranchType.TRANSFO_3_LEG_2;
            case THREE -> BranchType.TRANSFO_3_LEG_3;
        };
    }

    @Override
    public List<String> getOriginalIds() {
        return List.of(getTwt().getId());
    }

    @Override
    public boolean hasPhaseControllerCapability() {
        return getLeg().getPhaseTapChanger() != null;
    }

    @Override
    public List<BranchResult> createBranchResult(double preContingencyBranchP1, double preContingencyBranchOfContingencyP1, boolean createExtension) {
        throw new PowsyblException("Unsupported type of branch for branch result: " + getId());
    }

    @Override
    public List<LfLimit> getLimits1(final LimitType type, LimitReductionManager limitReductionManager) {
        switch (type) {
            case ACTIVE_POWER:
                return getLimits1(type, () -> getLeg().getActivePowerLimits(), limitReductionManager);
            case APPARENT_POWER:
                return getLimits1(type, () -> getLeg().getApparentPowerLimits(), limitReductionManager);
            case CURRENT:
                return getLimits1(type, () -> getLeg().getCurrentLimits(), limitReductionManager);
            case VOLTAGE:
            default:
                throw new UnsupportedOperationException(String.format("Getting %s limits is not supported.", type.name()));
        }
    }

    @Override
    public double[] getLimitReductions(TwoSides side, LimitReductionManager limitReductionManager, LoadingLimits limits) {
        return new double[] {};
    }

    @Override
    public void updateState(LfNetworkStateUpdateParameters parameters, LfNetworkUpdateReport updateReport) {
        var twt = getTwt();
        var leg = getLeg();

        updateFlows(p1.eval(), q1.eval(), Double.NaN, Double.NaN);

        if (parameters.isPhaseShifterRegulationOn() && isPhaseController()) {
            // it means there is a regulating phase tap changer located on that leg
            updateTapPosition(leg.getPhaseTapChanger());
        }

        if (parameters.isTransformerVoltageControlOn() && isVoltageController()
                || parameters.isTransformerReactivePowerControlOn() && isTransformerReactivePowerController()) { // it means there is a regulating ratio tap changer
            RatioTapChanger rtc = leg.getRatioTapChanger();
            double baseRatio = Transformers.getRatioPerUnitBase(leg, twt);
            double rho = getPiModel().getR1() * leg.getRatedU() / twt.getRatedU0() * baseRatio;
            double ptcRho = leg.getPhaseTapChanger() != null ? leg.getPhaseTapChanger().getCurrentStep().getRho() : 1;
            updateTapPosition(rtc, ptcRho, rho);
        }
    }

    @Override
    public void updateFlows(double p1, double q1, double p2, double q2) {
        // Star bus is always on side 2.
        getLeg().getTerminal().setP(p1 * PerUnit.SB)
                .setQ(q1 * PerUnit.SB);
    }

    public static ThreeWindingsTransformerResult createThreeWindingsTransformerResult(LfNetwork network, String threeWindingsTransformerId, boolean createResultExtension) {
        LfLegBranch leg1 = (LfLegBranch) network.getBranchById(LfLegBranch.getId(threeWindingsTransformerId, 1));
        LfLegBranch leg2 = (LfLegBranch) network.getBranchById(LfLegBranch.getId(threeWindingsTransformerId, 2));
        LfLegBranch leg3 = (LfLegBranch) network.getBranchById(LfLegBranch.getId(threeWindingsTransformerId, 3));

        double i1Base = PerUnit.ib(leg1.legRef.get().getTerminal().getVoltageLevel().getNominalV());
        double i2Base = PerUnit.ib(leg2.legRef.get().getTerminal().getVoltageLevel().getNominalV());
        double i3Base = PerUnit.ib(leg3.legRef.get().getTerminal().getVoltageLevel().getNominalV());
        ThreeWindingsTransformerResult result = new ThreeWindingsTransformerResult(threeWindingsTransformerId,
                leg1.getP1().eval() * PerUnit.SB, leg1.getQ1().eval() * PerUnit.SB, leg1.getI1().eval() * i1Base,
                leg2.getP1().eval() * PerUnit.SB, leg2.getQ1().eval() * PerUnit.SB, leg2.getI1().eval() * i2Base,
                leg3.getP1().eval() * PerUnit.SB, leg3.getQ1().eval() * PerUnit.SB, leg3.getI1().eval() * i3Base);

        if (createResultExtension) {
            result.addExtension(OlfThreeWindingsTransformerResult.class, new OlfThreeWindingsTransformerResult(
                    leg1.getV1() * leg1.legRef.get().getTerminal().getVoltageLevel().getNominalV(),
                    leg2.getV1() * leg2.legRef.get().getTerminal().getVoltageLevel().getNominalV(),
                    leg3.getV1() * leg3.legRef.get().getTerminal().getVoltageLevel().getNominalV(),
                    Math.toDegrees(leg1.getAngle1()),
                    Math.toDegrees(leg2.getAngle1()),
                    Math.toDegrees(leg3.getAngle1())));
        }
        return result;
    }
}
