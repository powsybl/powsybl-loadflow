/**
 * Copyright (c) 2024, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow;

import com.powsybl.math.matrix.MatrixFactory;

import java.util.Objects;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class TargetVoltageCompatibilityCheckerParameters {

    public static final int CONTROLLED_BUS_NEIGHBORS_EXPLORATION_DEPTH_DEFAULT_VALUE = 2;

    public static final double TARGET_VOLTAGE_PLAUSIBILITY_THRESHOLD_DEFAULT_VALUE = 10;

    private MatrixFactory matrixFactory;

    private int controlledBusNeighborsExplorationDepth = CONTROLLED_BUS_NEIGHBORS_EXPLORATION_DEPTH_DEFAULT_VALUE;

    private double targetVoltagePlausibilityThreshold = TARGET_VOLTAGE_PLAUSIBILITY_THRESHOLD_DEFAULT_VALUE;

    public MatrixFactory getMatrixFactory() {
        return matrixFactory;
    }

    public void setMatrixFactory(MatrixFactory matrixFactory) {
        this.matrixFactory = Objects.requireNonNull(matrixFactory);
    }

    public TargetVoltageCompatibilityCheckerParameters(MatrixFactory matrixFactory) {
        this.matrixFactory = Objects.requireNonNull(matrixFactory);
    }

    public int getControlledBusNeighborsExplorationDepth() {
        return controlledBusNeighborsExplorationDepth;
    }

    public TargetVoltageCompatibilityCheckerParameters setControlledBusNeighborsExplorationDepth(int controlledBusNeighborsExplorationDepth) {
        this.controlledBusNeighborsExplorationDepth = controlledBusNeighborsExplorationDepth;
        return this;
    }

    public double getTargetVoltagePlausibilityThreshold() {
        return targetVoltagePlausibilityThreshold;
    }

    public TargetVoltageCompatibilityCheckerParameters setTargetVoltagePlausibilityThreshold(double targetVoltagePlausibilityThreshold) {
        this.targetVoltagePlausibilityThreshold = targetVoltagePlausibilityThreshold;
        return this;
    }

    public String toString() {
        return "TargetVoltageCompatibilityCheckerParameters(" +
                "controlledBusNeighborsExplorationDepth=" + controlledBusNeighborsExplorationDepth +
                ", targetVoltagePlausibilityThreshold=" + targetVoltagePlausibilityThreshold +
                ", matrixFactory=" + matrixFactory.getClass().getSimpleName() +
                ")";
    }
}
