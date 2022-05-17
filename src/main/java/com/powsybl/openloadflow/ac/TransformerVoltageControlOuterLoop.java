/**
 * Copyright (c) 2020, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.ac;

import com.powsybl.commons.reporter.Reporter;
import com.powsybl.openloadflow.ac.outerloop.OuterLoopContext;
import com.powsybl.openloadflow.ac.outerloop.OuterLoopStatus;
import com.powsybl.openloadflow.network.LfBranch;
import com.powsybl.openloadflow.network.LfBus;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Anne Tilloy <anne.tilloy at rte-france.com>
 */
public class TransformerVoltageControlOuterLoop extends AbstractTransformerVoltageControlOuterLoop {

    private static final class ContextData {

        private double maxControlledNominalVoltage = Double.MIN_VALUE;

        private final List<LfBus> busesWithVoltageControlDisabled = new ArrayList<>();

        private double getMaxControlledNominalVoltage() {
            return maxControlledNominalVoltage;
        }

        private void setMaxControlledNominalVoltage(double maxControlledNominalVoltage) {
            this.maxControlledNominalVoltage = maxControlledNominalVoltage;
        }

        private List<LfBus> getBusesWithVoltageControlDisabled() {
            return busesWithVoltageControlDisabled;
        }
    }

    @Override
    public void initialize(OuterLoopContext context) {
        context.setData(new ContextData());

        for (LfBranch controllerBranch : getControllerBranches(context.getNetwork())) {
            controllerBranch.setVoltageControlEnabled(false);
        }

        // All transformer voltage control are disabled for the first equation system resolution.
        double[] maxControlledNominalVoltage = new double[1];
        maxControlledNominalVoltage[0] = Double.MIN_VALUE;
        for (LfBus bus : context.getNetwork().getBuses()) {
            if (!bus.isDisabled() && bus.isTransformerVoltageControlled()) {
                maxControlledNominalVoltage[0] = Math.max(maxControlledNominalVoltage[0], bus.getNominalV());
            }
        }
        ((ContextData) context.getData()).setMaxControlledNominalVoltage(maxControlledNominalVoltage[0]);
    }

    @Override
    public String getType() {
        return "Transformer voltage control";
    }

    @Override
    public OuterLoopStatus check(OuterLoopContext context, Reporter reporter) {
        OuterLoopStatus status = OuterLoopStatus.STABLE;

        var contextData = (ContextData) context.getData();

        double maxControlledNominalVoltage = contextData.getMaxControlledNominalVoltage();

        // At first outer loop iteration, the voltage control of generators that controlled at nominal voltage of
        // the set controlledNominalVoltages are disabled.
        // The transformer voltage controls are enabled.
        if (context.getIteration() == 0) {
            for (LfBus bus : context.getNetwork().getBuses()) {
                if (!bus.isDisabled() && bus.isVoltageControlled() && bus.getNominalV() <= maxControlledNominalVoltage) {
                    var voltageControl = bus.getVoltageControl().orElseThrow();
                    voltageControl.getControllerBuses().forEach(controllerBus -> {
                        if (controllerBus.isVoltageControlEnabled()) {
                            controllerBus.setGenerationTargetQ(controllerBus.getQ().eval());
                            controllerBus.setVoltageControlEnabled(false);
                            contextData.getBusesWithVoltageControlDisabled().add(controllerBus);
                        }
                    });
                    status = OuterLoopStatus.UNSTABLE;
                }
            }
            for (LfBranch branch : getControllerBranches(context.getNetwork())) {
                branch.setVoltageControlEnabled(true);
                status = OuterLoopStatus.UNSTABLE;
            }
            checkControl(context.getNetwork());
        }

        // At second outer loop iteration, the transformers are rounded. The generator voltage controls that were
        // disabled previously are enabled.
        if (context.getIteration() == 1) {
            status = roundVoltageRatios(context);
            for (LfBus controllerBus : contextData.getBusesWithVoltageControlDisabled()) {
                controllerBus.setGenerationTargetQ(0);
                controllerBus.setVoltageControlEnabled(true);
                status = OuterLoopStatus.UNSTABLE;
            }
        }

        return status;
    }
}
