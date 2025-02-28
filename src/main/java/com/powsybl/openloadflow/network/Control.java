/**
 * Copyright (c) 2023, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

import com.powsybl.openloadflow.util.PerUnit;

import java.util.List;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class Control {

    protected double targetValue;

    protected Control(double targetValue) {
        this.targetValue = targetValue;
    }

    public double getTargetValue() {
        return targetValue;
    }

    public void setTargetValue(double targetValue) {
        this.targetValue = targetValue;
    }

    private static double[] createUniformReactiveKeys(List<LfBus> controllerBuses, LfGenerator.GeneratorControlType generatorControlType) {
        double[] qKeys = new double[controllerBuses.size()];
        for (int i = 0; i < controllerBuses.size(); i++) {
            LfBus controllerBus = controllerBuses.get(i);
            qKeys[i] = controllerBus.getGenerators().stream()
                    .filter(gen -> gen.getGeneratorControlType() == generatorControlType).count();
        }
        return qKeys;
    }

    private static double[] createReactiveKeysFromMaxReactivePowerRange(List<LfBus> controllerBuses, LfGenerator.GeneratorControlType generatorControlType) {
        double[] qKeys = new double[controllerBuses.size()];
        // try to build keys from reactive power range
        for (int i = 0; i < controllerBuses.size(); i++) {
            LfBus controllerBus = controllerBuses.get(i);
            for (LfGenerator generator : controllerBus.getGenerators()) {
                double maxRangeQ = generator.getRangeQ(LfGenerator.ReactiveRangeMode.MAX);
                // if one reactive range is not plausible, we fallback to uniform keys
                if (maxRangeQ < PlausibleValues.MIN_REACTIVE_RANGE / PerUnit.SB || maxRangeQ > PlausibleValues.MAX_REACTIVE_RANGE / PerUnit.SB) {
                    return createUniformReactiveKeys(controllerBuses, generatorControlType);
                } else {
                    qKeys[i] += maxRangeQ;
                }
            }
        }
        return qKeys;
    }

    static double[] createReactiveKeys(List<LfBus> controllerBuses,
                                       LfGenerator.GeneratorControlType generatorControlType,
                                       boolean ignoreQPercent) {
        double[] qKeys = new double[controllerBuses.size()];
        for (int i = 0; i < controllerBuses.size(); i++) {
            LfBus controllerBus = controllerBuses.get(i);
            for (LfGenerator generator : controllerBus.getGenerators()) {
                double qKey = ignoreQPercent ? Double.NaN : generator.getRemoteControlReactiveKey().orElse(Double.NaN);
                if (Double.isNaN(qKey)) {
                    // in case of one missing key, we fallback to keys based on reactive power range
                    return createReactiveKeysFromMaxReactivePowerRange(controllerBuses, generatorControlType);
                } else {
                    qKeys[i] += qKey;
                }
            }
        }
        return qKeys;
    }
}
