/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.ac.outerloop;

import com.powsybl.commons.report.ReportNode;
import com.powsybl.openloadflow.ac.AcOuterLoopContext;
import com.powsybl.openloadflow.ac.outerloop.tap.GeneratorVoltageControlManager;
import com.powsybl.openloadflow.ac.outerloop.tap.TransformerRatioManager;
import com.powsybl.openloadflow.lf.outerloop.OuterLoopResult;
import com.powsybl.openloadflow.lf.outerloop.OuterLoopStatus;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;
import com.powsybl.openloadflow.network.LfNetwork;
import com.powsybl.openloadflow.network.TransformerVoltageControl;
import com.powsybl.openloadflow.network.VoltageControl;

/**
 * @author Anne Tilloy {@literal <anne.tilloy at rte-france.com>}
 */
public class TransformerVoltageControlOuterLoop extends AbstractTransformerVoltageControlOuterLoop {

    public static final String NAME = "TransformerVoltageControl";

    private enum Step {
        INITIAL,
        CONTROL,
        COMPLETE
    }

    private final double maxControlledNominalVoltageOverride;

    private static final class ContextData {

        private TransformerRatioManager transformerRatioManager;

        private GeneratorVoltageControlManager generatorVoltageControlManager;

        private Step step = Step.INITIAL;
    }

    private final boolean useInitialTapPosition;

    public TransformerVoltageControlOuterLoop(boolean useInitialTapPosition, double maxControlledNominalVoltageOverride) {
        this.useInitialTapPosition = useInitialTapPosition;
        this.maxControlledNominalVoltageOverride = maxControlledNominalVoltageOverride;
    }

    @Override
    public void initialize(AcOuterLoopContext context) {
        ContextData contextData = new ContextData();
        context.setData(contextData);

        // All transformer voltage control are disabled for the first equation system resolution.
        for (LfBranch controllerBranch : context.getNetwork().<LfBranch>getControllerElements(VoltageControl.Type.TRANSFORMER)) {
            controllerBranch.setVoltageControlEnabled(false);
        }

        contextData.generatorVoltageControlManager = new GeneratorVoltageControlManager(context.getNetwork(), maxControlledNominalVoltageOverride);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public OuterLoopResult check(AcOuterLoopContext context, ReportNode reportNode) {

        var contextData = (ContextData) context.getData();

        return switch (contextData.step) {
            case INITIAL -> initStep(context.getNetwork(), contextData);
            case CONTROL -> controlStep(context.getNetwork(), contextData);
            case COMPLETE -> new OuterLoopResult(this, OuterLoopStatus.STABLE);
        };

    }

    /**
     * At first outer loop iteration, the voltage control of generators that controlled under the max controlled nominal
     * voltage are disabled (automatic detection or parameter).
     * The transformer voltage controls are enabled and their continuous ratio is computed.
     * @return a stable status if all taps are already tuned for tension control, unstable otherwise
     */
    private OuterLoopResult initStep(LfNetwork network, ContextData contextData) {
        boolean needRun = false;
        for (LfBranch branch : network.<LfBranch>getControllerElements(VoltageControl.Type.TRANSFORMER)) {
            if (branch.getVoltageControl().isPresent()) {
                TransformerVoltageControl voltageControl = branch.getVoltageControl().orElseThrow();
                double targetV = voltageControl.getTargetValue();
                double v = voltageControl.getControlledBus().getV();
                double diffV = targetV - v;
                double halfTargetDeadband = getHalfTargetDeadband(voltageControl);
                if (Math.abs(diffV) > halfTargetDeadband && branch.isConnectedAtBothSides()) {
                    branch.setVoltageControlEnabled(true);
                    needRun = true;
                }
            }
        }

        contextData.transformerRatioManager = new TransformerRatioManager(network, useInitialTapPosition);

        if (!needRun) {
            contextData.step = Step.COMPLETE;
            return new OuterLoopResult(this, OuterLoopStatus.STABLE);
        }
        contextData.generatorVoltageControlManager.disableGeneratorVoltageControlsUnderMaxControlledNominalVoltage(network);

        network.fixTransformerVoltageControls();

        contextData.step = Step.CONTROL;
        return new OuterLoopResult(this, OuterLoopStatus.UNSTABLE);
    }

    /**
     * During control step, transformers with ratio outside of range are rounded. We iterate until all transformers
     * with continuous ratio are within their operating range, rounded then and switch them to COMPLETE.
     */
    private OuterLoopResult controlStep(LfNetwork network, ContextData contextData) {
        boolean outOfBoundTap = false;

        for (LfBranch controllerBranch : network.<LfBranch>getControllerElements(VoltageControl.Type.TRANSFORMER)) {
            if (contextData.transformerRatioManager.roundR1ToExtremeTapPosition(controllerBranch)) {
                outOfBoundTap = true;
            }
        }

        if (!outOfBoundTap) {
            updateContinuousRatio(network, contextData);

            roundVoltageRatios(network);
            contextData.generatorVoltageControlManager.enableGeneratorVoltageControlsUnderMaxControlledNominalVoltage();

            contextData.step = Step.COMPLETE;
        }
        // In any case, the loop must run again.
        return new OuterLoopResult(this, OuterLoopStatus.UNSTABLE);
    }

    private void updateContinuousRatio(LfNetwork network, ContextData contextData) {
        for (LfBus bus : network.getControlledBuses(VoltageControl.Type.TRANSFORMER)) {
            bus.getTransformerVoltageControl()
                    .filter(voltageControl -> voltageControl.getMergeStatus() == VoltageControl.MergeStatus.MAIN)
                    .ifPresent(voltageControl ->
                        voltageControl.getMergedControllerElements().stream()
                                .filter(b -> !b.isDisabled())
                                .filter(LfBranch::isVoltageControlEnabled)
                                .forEach(b -> contextData.transformerRatioManager.updateContinuousRatio(b)));
        }
    }

    @Override
    public boolean canFixUnrealisticSate() {
        // Irrealistic voltage can happen before tap discretization
        return true;
    }
}
