/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.network.impl;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.PhaseTapChanger;
import com.powsybl.iidm.network.RatioTapChanger;
import com.powsybl.iidm.network.ThreeWindingsTransformer;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.util.Evaluable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.powsybl.openloadflow.util.EvaluableConstants.NAN;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class LfLegBranch extends AbstractLfBranch {

    private final ThreeWindingsTransformer twt;

    private final ThreeWindingsTransformer.Leg leg;

    private Evaluable p = NAN;

    private Evaluable q = NAN;

    private Evaluable i = NAN;

    protected LfLegBranch(LfNetwork network, LfBus bus1, LfBus bus0, PiModel piModel, ThreeWindingsTransformer twt, ThreeWindingsTransformer.Leg leg) {
        super(network, bus1, bus0, piModel);
        this.twt = twt;
        this.leg = leg;
    }

    public static LfLegBranch create(LfNetwork network, LfBus bus1, LfBus bus0, ThreeWindingsTransformer twt, ThreeWindingsTransformer.Leg leg,
                                     boolean twtSplitShuntAdmittance) {
        Objects.requireNonNull(bus0);
        Objects.requireNonNull(twt);
        Objects.requireNonNull(leg);

        PiModel piModel = null;

        double nominalV2 = twt.getRatedU0();
        double zb = nominalV2 * nominalV2 / PerUnit.SB;
        double baseRatio = Transformers.getRatioPerUnitBase(leg, twt);
        PhaseTapChanger ptc = leg.getPhaseTapChanger();
        if (ptc != null
                && ptc.isRegulating()
                && ptc.getRegulationMode() != PhaseTapChanger.RegulationMode.FIXED_TAP) {
            // we have a phase control, whatever we also have a voltage control or not, we create a pi model array
            // based on phase taps mixed with voltage current tap
            Integer rtcPosition = Transformers.getCurrentPosition(leg.getRatioTapChanger());
            List<PiModel> models = new ArrayList<>();
            for (int ptcPosition = ptc.getLowTapPosition(); ptcPosition <= ptc.getHighTapPosition(); ptcPosition++) {
                Transformers.TapCharacteristics tapCharacteristics = Transformers.getTapCharacteristics(twt, leg, rtcPosition, ptcPosition);
                models.add(Transformers.createPiModel(tapCharacteristics, zb, baseRatio, twtSplitShuntAdmittance));
            }
            piModel = new PiModelArray(models, ptc.getLowTapPosition(), ptc.getTapPosition());
        }

        RatioTapChanger rtc = leg.getRatioTapChanger();
        if (rtc != null && rtc.isRegulating() && rtc.hasLoadTapChangingCapabilities()) {
            if (piModel == null) {
                // we have a voltage control, we create a pi model array based on voltage taps mixed with phase current
                // tap
                Integer ptcPosition = Transformers.getCurrentPosition(leg.getPhaseTapChanger());
                List<PiModel> models = new ArrayList<>();
                for (int rtcPosition = rtc.getLowTapPosition(); rtcPosition <= rtc.getHighTapPosition(); rtcPosition++) {
                    Transformers.TapCharacteristics tapCharacteristics = Transformers.getTapCharacteristics(twt, leg, rtcPosition, ptcPosition);
                    models.add(Transformers.createPiModel(tapCharacteristics, zb, baseRatio, twtSplitShuntAdmittance));
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
            piModel = Transformers.createPiModel(tapCharacteristics, zb, baseRatio, twtSplitShuntAdmittance);
        }

        return new LfLegBranch(network, bus1, bus0, piModel, twt, leg);
    }

    private int getLegNum() {
        if (leg == twt.getLeg1()) {
            return 1;
        } else if (leg == twt.getLeg2()) {
            return 2;
        } else {
            return 3;
        }
    }

    @Override
    public String getId() {
        return twt.getId() + "_leg_" + getLegNum();
    }

    @Override
    public boolean hasPhaseControlCapability() {
        return leg.getPhaseTapChanger() != null;
    }

    @Override
    public void setP1(Evaluable p1) {
        this.p = Objects.requireNonNull(p1);
    }

    @Override
    public Evaluable getP1() {
        return p;
    }

    @Override
    public void setP2(Evaluable p2) {
        // nothing to do
    }

    @Override
    public Evaluable getP2() {
        return NAN;
    }

    @Override
    public void setQ1(Evaluable q1) {
        this.q = Objects.requireNonNull(q1);
    }

    @Override
    public Evaluable getQ1() {
        return NAN;
    }

    @Override
    public void setQ2(Evaluable q2) {
        // nothing to do
    }

    @Override
    public Evaluable getQ2() {
        return null;
    }

    @Override
    public void setI1(Evaluable i1) {
        this.i = Objects.requireNonNull(i1);
    }

    @Override
    public Evaluable getI1() {
        return i;
    }

    @Override
    public void setI2(Evaluable i2) {
        // nothing to do
    }

    @Override
    public Evaluable getI2() {
        return NAN;
    }

    @Override
    public double getPermanentLimit1() {
        return leg.getCurrentLimits() != null ? leg.getCurrentLimits().getPermanentLimit() * getBus1().getNominalV() / PerUnit.SB : Double.NaN;
    }

    @Override
    public double getPermanentLimit2() {
        return Double.NaN;
    }

    @Override
    public void updateState(boolean phaseShifterRegulationOn, boolean isTransformerVoltageControlOn) {
        leg.getTerminal().setP(p.eval() * PerUnit.SB);
        leg.getTerminal().setQ(q.eval() * PerUnit.SB);

        if (phaseShifterRegulationOn && isPhaseController() && phaseControl.getMode() == DiscretePhaseControl.Mode.OFF) {
            // it means there is a regulating phase tap changer located on that leg
            updateTapPosition(leg.getPhaseTapChanger());
        }

        if (phaseShifterRegulationOn && isPhaseControlled() && phaseControl.getControlledSide() == DiscretePhaseControl.ControlledSide.ONE) {
            // check if the target value deadband is respected
            checkTargetDeadband(p.eval());
        }

        if (isTransformerVoltageControlOn && isVoltageController()) { // it means there is a regulating ratio tap changer
            RatioTapChanger rtc = leg.getRatioTapChanger();
            double baseRatio = Transformers.getRatioPerUnitBase(leg, twt);
            double rho = getPiModel().getR1() * leg.getRatedU() / twt.getRatedU0() * baseRatio;
            double ptcRho = leg.getPhaseTapChanger() != null ? leg.getPhaseTapChanger().getCurrentStep().getRho() : 1;
            updateTapPosition(rtc, ptcRho, rho);
            checkTargetDeadband(rtc);
        }
    }
}
