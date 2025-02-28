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
import com.powsybl.iidm.network.extensions.LineFortescue;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.sa.LimitReductionManager;
import com.powsybl.openloadflow.util.PerUnit;
import com.powsybl.security.results.BranchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class LfBranchImpl extends AbstractImpedantLfBranch {

    private static final Logger LOGGER = LoggerFactory.getLogger(LfBranchImpl.class);

    private final Ref<Branch<?>> branchRef;

    protected LfBranchImpl(LfNetwork network, LfBus bus1, LfBus bus2, PiModel piModel, Branch<?> branch, LfNetworkParameters parameters) {
        super(network, bus1, bus2, piModel, parameters);
        this.branchRef = Ref.create(branch, parameters.isCacheEnabled());
    }

    private static void createLineAsym(Line line, double zb, PiModel piModel, LfBranchImpl lfBranch) {
        var extension = line.getExtension(LineFortescue.class);
        if (extension != null) {
            boolean openPhaseA = extension.isOpenPhaseA();
            boolean openPhaseB = extension.isOpenPhaseB();
            boolean openPhaseC = extension.isOpenPhaseC();
            double rz = extension.getRz();
            double xz = extension.getXz();
            SimplePiModel piZeroComponent = new SimplePiModel()
                    .setR(rz / zb)
                    .setX(xz / zb);
            SimplePiModel piPositiveComponent = new SimplePiModel()
                    .setR(piModel.getR())
                    .setX(piModel.getX());
            SimplePiModel piNegativeComponent = new SimplePiModel()
                    .setR(piModel.getR())
                    .setX(piModel.getX());
            lfBranch.setAsymLine(new LfAsymLine(piZeroComponent, piPositiveComponent, piNegativeComponent,
                    openPhaseA, openPhaseB, openPhaseC));
        }
    }

    private static LfBranchImpl createLine(Line line, LfNetwork network, LfBus bus1, LfBus bus2, LfNetworkParameters parameters) {
        double r1;
        double r;
        double x;
        double g1;
        double b1;
        double g2;
        double b2;
        double zb;
        if (parameters.getLinePerUnitMode() == LinePerUnitMode.RATIO) {
            double nominalV2 = line.getTerminal2().getVoltageLevel().getNominalV();
            zb = PerUnit.zb(nominalV2);
            r1 = 1 / Transformers.getRatioPerUnitBase(line);
            r = line.getR() / zb;
            x = line.getX() / zb;
            g1 = line.getG1() * zb;
            g2 = line.getG2() * zb;
            b1 = line.getB1() * zb;
            b2 = line.getB2() * zb;
        } else { // IMPEDANCE
            r1 = 1;
            double zSquare = line.getR() * line.getR() + line.getX() * line.getX();
            if (zSquare == 0) {
                r = 0;
                x = 0;
                g1 = 0;
                b1 = 0;
                g2 = 0;
                b2 = 0;
                zb = 0;
            } else {
                double nominalV1 = line.getTerminal1().getVoltageLevel().getNominalV();
                double nominalV2 = line.getTerminal2().getVoltageLevel().getNominalV();
                double g = line.getR() / zSquare;
                double b = -line.getX() / zSquare;
                zb = nominalV1 * nominalV2 / PerUnit.SB;
                r = line.getR() / zb;
                x = line.getX() / zb;
                g1 = (line.getG1() * nominalV1 * nominalV1 + g * nominalV1 * (nominalV1 - nominalV2)) / PerUnit.SB;
                b1 = (line.getB1() * nominalV1 * nominalV1 + b * nominalV1 * (nominalV1 - nominalV2)) / PerUnit.SB;
                g2 = (line.getG2() * nominalV2 * nominalV2 + g * nominalV2 * (nominalV2 - nominalV1)) / PerUnit.SB;
                b2 = (line.getB2() * nominalV2 * nominalV2 + b * nominalV2 * (nominalV2 - nominalV1)) / PerUnit.SB;
            }
        }

        PiModel piModel = new SimplePiModel()
                .setR1(r1)
                .setR(r)
                .setX(x)
                .setG1(g1)
                .setG2(g2)
                .setB1(b1)
                .setB2(b2);

        LfBranchImpl lfBranch = new LfBranchImpl(network, bus1, bus2, piModel, line, parameters);
        if (parameters.isAsymmetrical()) {
            createLineAsym(line, zb, piModel, lfBranch);
        }
        return lfBranch;
    }

    private static LfBranchImpl createTransformer(TwoWindingsTransformer twt, LfNetwork network, LfBus bus1, LfBus bus2,
                                                  boolean retainPtc, boolean retainRtc, LfNetworkParameters parameters) {
        PiModel piModel = null;

        double baseRatio = Transformers.getRatioPerUnitBase(twt);
        double nominalV2 = twt.getTerminal2().getVoltageLevel().getNominalV();
        double zb = PerUnit.zb(nominalV2);

        PhaseTapChanger ptc = twt.getPhaseTapChanger();
        if (ptc != null
                && (ptc.isRegulating()
                && ptc.getRegulationMode() != PhaseTapChanger.RegulationMode.FIXED_TAP || retainPtc)) {
            // we have a phase control, whatever we also have a voltage control or not, we create a pi model array
            // based on phase taps mixed with voltage current tap
            Integer rtcPosition = Transformers.getCurrentPosition(twt.getRatioTapChanger());
            List<PiModel> models = new ArrayList<>();
            for (int ptcPosition = ptc.getLowTapPosition(); ptcPosition <= ptc.getHighTapPosition(); ptcPosition++) {
                Transformers.TapCharacteristics tapCharacteristics = Transformers.getTapCharacteristics(twt, rtcPosition, ptcPosition);
                models.add(Transformers.createPiModel(tapCharacteristics, zb, baseRatio, parameters.isTwtSplitShuntAdmittance()));
            }
            piModel = new PiModelArray(models, ptc.getLowTapPosition(), ptc.getTapPosition());
        }

        RatioTapChanger rtc = twt.getRatioTapChanger();
        if (rtc != null && (rtc.isRegulating() && rtc.hasLoadTapChangingCapabilities() || retainRtc)) {
            if (piModel == null) {
                // we have a voltage control, we create a pi model array based on voltage taps mixed with phase current
                // tap
                Integer ptcPosition = Transformers.getCurrentPosition(twt.getPhaseTapChanger());
                List<PiModel> models = new ArrayList<>();
                for (int rtcPosition = rtc.getLowTapPosition(); rtcPosition <= rtc.getHighTapPosition(); rtcPosition++) {
                    Transformers.TapCharacteristics tapCharacteristics = Transformers.getTapCharacteristics(twt, rtcPosition, ptcPosition);
                    models.add(Transformers.createPiModel(tapCharacteristics, zb, baseRatio, parameters.isTwtSplitShuntAdmittance()));
                }
                piModel = new PiModelArray(models, rtc.getLowTapPosition(), rtc.getTapPosition());
            } else {
                throw new PowsyblException("Voltage and phase control on same branch '" + twt.getId() + "' is not yet supported");
            }
        }

        if (piModel == null) {
            // we don't have any phase or voltage control, we create a simple pi model (single tap) based on phase current
            // tap and voltage current tap
            Transformers.TapCharacteristics tapCharacteristics = Transformers.getTapCharacteristics(twt);
            piModel = Transformers.createPiModel(tapCharacteristics, zb, baseRatio, parameters.isTwtSplitShuntAdmittance());
        }

        return new LfBranchImpl(network, bus1, bus2, piModel, twt, parameters);
    }

    public static LfBranchImpl create(Branch<?> branch, LfNetwork network, LfBus bus1, LfBus bus2, LfTopoConfig topoConfig,
                                      LfNetworkParameters parameters) {
        Objects.requireNonNull(branch);
        Objects.requireNonNull(network);
        Objects.requireNonNull(topoConfig);
        Objects.requireNonNull(parameters);
        LfBranchImpl lfBranch;
        if (branch instanceof Line line) {
            lfBranch = createLine(line, network, bus1, bus2, parameters);
        } else if (branch instanceof TwoWindingsTransformer twt) {
            lfBranch = createTransformer(twt, network, bus1, bus2, topoConfig.isRetainedPtc(twt.getId()),
                    topoConfig.isRetainedRtc(twt.getId()), parameters);
        } else {
            throw new PowsyblException("Unsupported type of branch for flow equations of branch: " + branch.getId());
        }
        if (bus1 != null && topoConfig.getBranchIdsOpenableSide1().contains(lfBranch.getId())) {
            lfBranch.setDisconnectionAllowedSide1(true);
        }
        if (bus2 != null && topoConfig.getBranchIdsOpenableSide2().contains(lfBranch.getId())) {
            lfBranch.setDisconnectionAllowedSide2(true);
        }
        return lfBranch;
    }

    private Branch<?> getBranch() {
        return branchRef.get();
    }

    @Override
    public String getId() {
        return getBranch().getId();
    }

    @Override
    public BranchType getBranchType() {
        return getBranch() instanceof Line ? BranchType.LINE : BranchType.TRANSFO_2;
    }

    @Override
    public boolean hasPhaseControllerCapability() {
        var branch = getBranch();
        return branch.getType() == IdentifiableType.TWO_WINDINGS_TRANSFORMER
                && ((TwoWindingsTransformer) branch).getPhaseTapChanger() != null;
    }

    @Override
    public List<BranchResult> createBranchResult(double preContingencyBranchP1, double preContingencyBranchOfContingencyP1, boolean createExtension) {
        var branch = getBranch();
        double flowTransfer = Double.NaN;
        if (!Double.isNaN(preContingencyBranchP1) && !Double.isNaN(preContingencyBranchOfContingencyP1)) {
            flowTransfer = (p1.eval() * PerUnit.SB - preContingencyBranchP1) / preContingencyBranchOfContingencyP1;
        }
        double currentScale1 = PerUnit.ib(branch.getTerminal1().getVoltageLevel().getNominalV());
        double currentScale2 = PerUnit.ib(branch.getTerminal2().getVoltageLevel().getNominalV());
        var branchResult = new BranchResult(getId(), p1.eval() * PerUnit.SB, q1.eval() * PerUnit.SB, currentScale1 * i1.eval(),
                                            p2.eval() * PerUnit.SB, q2.eval() * PerUnit.SB, currentScale2 * i2.eval(), flowTransfer);
        if (createExtension) {
            branchResult.addExtension(OlfBranchResult.class, new OlfBranchResult(piModel.getR1(), piModel.getContinuousR1(),
                    getV1() * branch.getTerminal1().getVoltageLevel().getNominalV(),
                    getV2() * branch.getTerminal2().getVoltageLevel().getNominalV(),
                    Math.toDegrees(getAngle1()),
                    Math.toDegrees(getAngle2())));
        }
        return List.of(branchResult);
    }

    @Override
    public List<LfLimit> getLimits1(final LimitType type, LimitReductionManager limitReductionManager) {
        switch (type) {
            case ACTIVE_POWER:
                return getLimits1(type, () -> getBranch().getActivePowerLimits1(), limitReductionManager);
            case APPARENT_POWER:
                return getLimits1(type, () -> getBranch().getApparentPowerLimits1(), limitReductionManager);
            case CURRENT:
                return getLimits1(type, () -> getBranch().getCurrentLimits1(), limitReductionManager);
            case VOLTAGE:
            default:
                throw new UnsupportedOperationException(String.format("Getting %s limits is not supported.", type.name()));
        }
    }

    @Override
    public List<LfLimit> getLimits2(final LimitType type, LimitReductionManager limitReductionManager) {
        switch (type) {
            case ACTIVE_POWER:
                return getLimits2(type, () -> getBranch().getActivePowerLimits2(), limitReductionManager);
            case APPARENT_POWER:
                return getLimits2(type, () -> getBranch().getApparentPowerLimits2(), limitReductionManager);
            case CURRENT:
                return getLimits2(type, () -> getBranch().getCurrentLimits2(), limitReductionManager);
            case VOLTAGE:
            default:
                throw new UnsupportedOperationException(String.format("Getting %s limits is not supported.", type.name()));
        }
    }

    /**
     * <p>Create the list of the limit reductions to use for each limit:
     * <ul>
     *  <li>the value for the permanent limit is stored at index 0, values for the temporary limits are stored from index 1;</li>
     *  <li>if no reduction is detected for a limit, the corresponding value is set to 1;</li>
     *  <li>if several LimitReductions are applicable for the same limit, the last one is used;</li>
     * </ul></p>
     * <p>This method may return an empty list when no reduction apply.</p>
     */
    @Override
    public double[] getLimitReductions(TwoSides side, LimitReductionManager limitReductionManager, LoadingLimits limits) {
        if (limits == null) {
            return new double[] {};
        }
        if (limits.getLimitType() != LimitType.CURRENT) {
            return new double[] {};
        }
        if (limitReductionManager == null || limitReductionManager.isEmpty()) {
            return new double[] {};
        }
        // Initialize the array of the reductions with 1s
        double[] limitReductions = new double[limits.getTemporaryLimits().size() + 1];
        Arrays.fill(limitReductions, 1.);
        double nominalV = branchRef.get().getTerminal(side).getVoltageLevel().getNominalV();
        for (LimitReductionManager.TerminalLimitReduction terminalLimitReduction : limitReductionManager.getTerminalLimitReductions()) {
            if (terminalLimitReduction.nominalV().contains(nominalV)) {
                if (terminalLimitReduction.isPermanent()) {
                    limitReductions[0] = terminalLimitReduction.reduction();
                }
                if (terminalLimitReduction.acceptableDuration() != null) {
                    int i = 1; // temporary limit's reductions will be stored starting from index 1
                    for (LoadingLimits.TemporaryLimit temporaryLimit : limits.getTemporaryLimits()) {
                        if (terminalLimitReduction.acceptableDuration().contains(temporaryLimit.getAcceptableDuration())) {
                            limitReductions[i] = terminalLimitReduction.reduction();
                        }
                        i++;
                    }
                }
            }
        }
        return limitReductions;
    }

    @Override
    public void updateState(LfNetworkStateUpdateParameters parameters, LfNetworkUpdateReport updateReport) {
        var branch = getBranch();

        if (isDisabled()) {
            updateFlows(Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        } else {
            updateFlows(p1.eval(), q1.eval(), p2.eval(), q2.eval());
        }

        // in case of automation system simulation we might need to update IIDM terminals connection status
        if (parameters.isSimulateAutomationSystems()) {
            boolean connectedSide1Before = branch.getTerminal1().isConnected();
            boolean connectedSide2Before = branch.getTerminal2().isConnected();
            boolean connectedSide1After = !isDisabled() && isConnectedSide1();
            boolean connectedSide2After = !isDisabled() && isConnectedSide2();

            if (connectedSide1Before && !connectedSide1After) {
                LOGGER.warn("Disconnect terminal 1 of branch '{}'", branch.getId());
                branch.getTerminal1().disconnect();
                updateReport.disconnectedBranchSide1Count++;
            }
            if (!connectedSide1Before && connectedSide1After) {
                LOGGER.warn("Connect terminal 1 of branch '{}'", branch.getId());
                branch.getTerminal1().connect();
                updateReport.connectedBranchSide1Count++;
            }
            if (connectedSide2Before && !connectedSide2After) {
                LOGGER.warn("Disconnect terminal 2 of branch '{}'", branch.getId());
                branch.getTerminal2().disconnect();
                updateReport.disconnectedBranchSide2Count++;
            }
            if (!connectedSide2Before && connectedSide2After) {
                LOGGER.warn("Connect terminal 2 of branch '{}'", branch.getId());
                branch.getTerminal2().connect();
                updateReport.connectedBranchSide2Count++;
            }
        }

        if (parameters.isPhaseShifterRegulationOn() && isPhaseController()) {
            // it means there is a regulating phase tap changer located on that branch
            updateTapPosition(((TwoWindingsTransformer) branch).getPhaseTapChanger());
        }

        if (parameters.isTransformerVoltageControlOn() && isVoltageController()
                || parameters.isTransformerReactivePowerControlOn() && isTransformerReactivePowerController()) { // it means there is a regulating ratio tap changer
            TwoWindingsTransformer twt = (TwoWindingsTransformer) branch;
            RatioTapChanger rtc = twt.getRatioTapChanger();
            double baseRatio = Transformers.getRatioPerUnitBase(twt);
            double rho = getPiModel().getR1() * twt.getRatedU1() / twt.getRatedU2() * baseRatio;
            double ptcRho = twt.getPhaseTapChanger() != null ? twt.getPhaseTapChanger().getCurrentStep().getRho() : 1;
            updateTapPosition(rtc, ptcRho, rho);
        }
    }

    @Override
    public void updateFlows(double p1, double q1, double p2, double q2) {
        var branch = getBranch();
        branch.getTerminal1().setP(p1 * PerUnit.SB)
                .setQ(q1 * PerUnit.SB);
        branch.getTerminal2().setP(p2 * PerUnit.SB)
                .setQ(q2 * PerUnit.SB);
    }
}
