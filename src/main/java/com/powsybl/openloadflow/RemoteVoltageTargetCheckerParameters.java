/**
 * Copyright (c) 2024, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow;

import com.powsybl.math.matrix.MatrixFactory;
import com.powsybl.openloadflow.network.util.VoltageInitializer;

import java.util.Objects;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class RemoteVoltageTargetCheckerParameters {

    public static final int CONTROLLED_BUS_NEIGHBORS_EXPLORATION_DEPTH_DEFAULT_VALUE = 2;

    public static final double TARGET_VOLTAGE_PLAUSIBILITY_THRESHOLD_DEFAULT_VALUE = 10;

    public static final double CONTROLLER_BUS_ACCEPTABLE_VOLTAGE_SHIFT_DEFAULT_VALUE = 0.8;

    private MatrixFactory matrixFactory;

    private VoltageInitializer voltageInitializer;

    private int controlledBusNeighborsExplorationDepth = CONTROLLED_BUS_NEIGHBORS_EXPLORATION_DEPTH_DEFAULT_VALUE;

    private double targetVoltagePlausibilityIndicatorThreshold = TARGET_VOLTAGE_PLAUSIBILITY_THRESHOLD_DEFAULT_VALUE;

    private double controllerBusAcceptableVoltageDrop = CONTROLLER_BUS_ACCEPTABLE_VOLTAGE_SHIFT_DEFAULT_VALUE; // shift from 1 pu

    public RemoteVoltageTargetCheckerParameters(MatrixFactory matrixFactory, VoltageInitializer voltageInitializer) {
        this.matrixFactory = Objects.requireNonNull(matrixFactory);
        this.voltageInitializer = Objects.requireNonNull(voltageInitializer);
    }

    public MatrixFactory getMatrixFactory() {
        return matrixFactory;
    }

    public void setMatrixFactory(MatrixFactory matrixFactory) {
        this.matrixFactory = Objects.requireNonNull(matrixFactory);
    }

    public VoltageInitializer getVoltageInitializer() {
        return voltageInitializer;
    }

    public void setVoltageInitializer(VoltageInitializer voltageInitializer) {
        this.voltageInitializer = Objects.requireNonNull(voltageInitializer);
    }

    public int getControlledBusNeighborsExplorationDepth() {
        return controlledBusNeighborsExplorationDepth;
    }

    public RemoteVoltageTargetCheckerParameters setControlledBusNeighborsExplorationDepth(int controlledBusNeighborsExplorationDepth) {
        this.controlledBusNeighborsExplorationDepth = controlledBusNeighborsExplorationDepth;
        return this;
    }

    public double getTargetVoltagePlausibilityIndicatorThreshold() {
        return targetVoltagePlausibilityIndicatorThreshold;
    }

    public RemoteVoltageTargetCheckerParameters setTargetVoltagePlausibilityIndicatorThreshold(double targetVoltagePlausibilityIndicatorThreshold) {
        this.targetVoltagePlausibilityIndicatorThreshold = targetVoltagePlausibilityIndicatorThreshold;
        return this;
    }

    public double getControllerBusAcceptableVoltageDrop() {
        return controllerBusAcceptableVoltageDrop;
    }

    public RemoteVoltageTargetCheckerParameters setControllerBusAcceptableVoltageDrop(double controllerBusAcceptableVoltageDrop) {
        this.controllerBusAcceptableVoltageDrop = controllerBusAcceptableVoltageDrop;
        return this;
    }

    public String toString() {
        return "TargetVoltageCompatibilityCheckerParameters(" +
                "controlledBusNeighborsExplorationDepth=" + controlledBusNeighborsExplorationDepth +
                ", targetVoltagePlausibilityIndicatorThreshold=" + targetVoltagePlausibilityIndicatorThreshold +
                ", controllerBusAcceptableVoltageDrop=" + controllerBusAcceptableVoltageDrop +
                ", matrixFactory=" + matrixFactory.getClass().getSimpleName() +
                ")";
    }
}
