/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.openloadflow.ac.nr;

import java.util.Objects;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class NewtonRaphsonParameters {

    public static final int DEFAULT_MAX_ITERATIONS = 15;
    public static final double DEFAULT_MIN_REALISTIC_VOLTAGE = 0.5;
    public static final double DEFAULT_MAX_REALISTIC_VOLTAGE = 2;
    public static final StateVectorScalingMode DEFAULT_STATE_VECTOR_SCALING_MODE = StateVectorScalingMode.NONE;
    public static final boolean ALWAYS_UPDATE_NETWORK_DEFAULT_VALUE = false;

    private int maxIterations = DEFAULT_MAX_ITERATIONS;

    private double minRealisticVoltage = DEFAULT_MIN_REALISTIC_VOLTAGE;

    private double maxRealisticVoltage = DEFAULT_MAX_REALISTIC_VOLTAGE;

    private StateVectorScalingMode stateVectorScalingMode = DEFAULT_STATE_VECTOR_SCALING_MODE;

    private int lineSearchStateVectorScalingMaxIteration = LineSearchStateVectorScaling.DEFAULT_MAX_ITERATION;

    private double lineSearchStateVectorScalingStepFold = LineSearchStateVectorScaling.DEFAULT_STEP_FOLD;

    private double maxVoltageChangeStateVectorScalingMaxDv = MaxVoltageChangeStateVectorScaling.DEFAULT_MAX_DV;

    private double maxVoltageChangeStateVectorScalingMaxDphi = MaxVoltageChangeStateVectorScaling.DEFAULT_MAX_DPHI;

    private NewtonRaphsonStoppingCriteria stoppingCriteria = new DefaultNewtonRaphsonStoppingCriteria();

    private boolean alwaysUpdateNetwork = ALWAYS_UPDATE_NETWORK_DEFAULT_VALUE;

    private boolean detailedReport = false;

    public static int checkMaxIteration(int maxIteration) {
        if (maxIteration < 1) {
            throw new IllegalArgumentException("Invalid max iteration value: " + maxIteration);
        }
        return maxIteration;
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    public NewtonRaphsonParameters setMaxIterations(int maxIterations) {
        this.maxIterations = checkMaxIteration(maxIterations);
        return this;
    }

    public double getMinRealisticVoltage() {
        return minRealisticVoltage;
    }

    public NewtonRaphsonParameters setMinRealisticVoltage(double minRealisticVoltage) {
        this.minRealisticVoltage = minRealisticVoltage;
        return this;
    }

    public double getMaxRealisticVoltage() {
        return maxRealisticVoltage;
    }

    public NewtonRaphsonParameters setMaxRealisticVoltage(double maxRealisticVoltage) {
        this.maxRealisticVoltage = maxRealisticVoltage;
        return this;
    }

    public NewtonRaphsonStoppingCriteria getStoppingCriteria() {
        return stoppingCriteria;
    }

    public NewtonRaphsonParameters setStoppingCriteria(NewtonRaphsonStoppingCriteria stoppingCriteria) {
        this.stoppingCriteria = Objects.requireNonNull(stoppingCriteria);
        return this;
    }

    public StateVectorScalingMode getStateVectorScalingMode() {
        return stateVectorScalingMode;
    }

    public NewtonRaphsonParameters setStateVectorScalingMode(StateVectorScalingMode stateVectorScalingMode) {
        this.stateVectorScalingMode = Objects.requireNonNull(stateVectorScalingMode);
        return this;
    }

    public boolean isDetailedReport() {
        return detailedReport;
    }

    public NewtonRaphsonParameters setDetailedReport(boolean detailedReport) {
        this.detailedReport = detailedReport;
        return this;
    }

    public boolean isAlwaysUpdateNetwork() {
        return alwaysUpdateNetwork;
    }

    public NewtonRaphsonParameters setAlwaysUpdateNetwork(boolean alwaysUpdateNetwork) {
        this.alwaysUpdateNetwork = alwaysUpdateNetwork;
        return this;
    }

    public int getLineSearchStateVectorScalingMaxIteration() {
        return lineSearchStateVectorScalingMaxIteration;
    }

    public NewtonRaphsonParameters setLineSearchStateVectorScalingMaxIteration(int lineSearchStateVectorScalingMaxIteration) {
        this.lineSearchStateVectorScalingMaxIteration = lineSearchStateVectorScalingMaxIteration;
        return this;

    }

    public double getLineSearchStateVectorScalingStepFold() {
        return lineSearchStateVectorScalingStepFold;
    }

    public NewtonRaphsonParameters setLineSearchStateVectorScalingStepFold(double lineSearchStateVectorScalingStepFold) {
        this.lineSearchStateVectorScalingStepFold = lineSearchStateVectorScalingStepFold;
        return this;
    }

    public double getMaxVoltageChangeStateVectorScalingMaxDv() {
        return maxVoltageChangeStateVectorScalingMaxDv;
    }

    public NewtonRaphsonParameters setMaxVoltageChangeStateVectorScalingMaxDv(double maxVoltageChangeStateVectorScalingMaxDv) {
        this.maxVoltageChangeStateVectorScalingMaxDv = maxVoltageChangeStateVectorScalingMaxDv;
        return this;
    }

    public double getMaxVoltageChangeStateVectorScalingMaxDphi() {
        return maxVoltageChangeStateVectorScalingMaxDphi;
    }

    public NewtonRaphsonParameters setMaxVoltageChangeStateVectorScalingMaxDphi(double maxVoltageChangeStateVectorScalingMaxDphi) {
        this.maxVoltageChangeStateVectorScalingMaxDphi = maxVoltageChangeStateVectorScalingMaxDphi;
        return this;
    }

    @Override
    public String toString() {
        return "NewtonRaphsonParameters(" +
                "maxIterations=" + maxIterations +
                ", minRealisticVoltage=" + minRealisticVoltage +
                ", maxRealisticVoltage=" + maxRealisticVoltage +
                ", stoppingCriteria=" + stoppingCriteria.getClass().getSimpleName() +
                ", stateVectorScalingMode=" + stateVectorScalingMode +
                ", alwaysUpdateNetwork=" + alwaysUpdateNetwork +
                ", detailedNrReport=" + detailedReport +
                ", lineSearchStateVectorScalingMaxIteration=" + lineSearchStateVectorScalingMaxIteration +
                ", lineSearchStateVectorScalingStepFold=" + lineSearchStateVectorScalingStepFold +
                ", maxVoltageChangeStateVectorScalingMaxDv=" + maxVoltageChangeStateVectorScalingMaxDv +
                ", maxVoltageChangeStateVectorScalingMaxDphi=" + maxVoltageChangeStateVectorScalingMaxDphi +
                ')';
    }
}
