/**
 * Copyright (c) 2019, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.network;

import net.jafama.FastMath;
import org.apache.commons.lang3.Range;
import org.apache.commons.math3.complex.Complex;

import java.util.Objects;
import java.util.Optional;

/**
 * @author Geoffroy Jamgotchian {@literal <geoffroy.jamgotchian at rte-france.com>}
 */
public class SimplePiModel implements PiModel {

    private double r = 0;
    private double x = 0;
    private double g1 = 0;
    private double b1 = 0;
    private double g2 = 0;
    private double b2 = 0;
    private double r1 = 1;
    private double a1 = 0;

    private static final String NO_TAP_POSITION_ERROR = "No tap position change in simple Pi model implementation";

    @Override
    public double getR() {
        return r;
    }

    @Override
    public SimplePiModel setR(double r) {
        this.r = r;
        return this;
    }

    @Override
    public double getX() {
        return x;
    }

    @Override
    public SimplePiModel setX(double x) {
        this.x = x;
        return this;
    }

    @Override
    public double getZ() {
        return FastMath.hypot(r, x);
    }

    @Override
    public double getY() {
        return 1 / getZ();
    }

    @Override
    public double getKsi() {
        return FastMath.atan2(r, x);
    }

    @Override
    public double getG1() {
        return g1;
    }

    public SimplePiModel setG1(double g1) {
        this.g1 = g1;
        return this;
    }

    @Override
    public double getB1() {
        return b1;
    }

    public SimplePiModel setB1(double b1) {
        this.b1 = b1;
        return this;
    }

    @Override
    public double getG2() {
        return g2;
    }

    public SimplePiModel setG2(double g2) {
        this.g2 = g2;
        return this;
    }

    @Override
    public double getB2() {
        return b2;
    }

    public SimplePiModel setB2(double b2) {
        this.b2 = b2;
        return this;
    }

    @Override
    public double getR1() {
        return r1;
    }

    @Override
    public double getMinR1() {
        return getR1();
    }

    @Override
    public double getMaxR1() {
        return getR1();
    }

    @Override
    public double getContinuousR1() {
        return getR1();
    }

    public SimplePiModel setR1(double r1) {
        this.r1 = r1;
        return this;
    }

    @Override
    public double getA1() {
        return a1;
    }

    @Override
    public SimplePiModel setA1(double a1) {
        this.a1 = a1;
        return this;
    }

    @Override
    public void roundA1ToClosestTap() {
        throw new IllegalStateException("A1 rounding is not supported in simple Pi model implementation");
    }

    @Override
    public void roundR1ToClosestTap() {
        throw new IllegalStateException("R1 rounding is not supported in simple Pi model implementation");
    }

    @Override
    public boolean shiftOneTapPositionToChangeA1(Direction direction) {
        throw new IllegalStateException(NO_TAP_POSITION_ERROR);
    }

    @Override
    public Optional<Direction> updateTapPositionToReachNewR1(double deltaR1, int maxTapShift, AllowedDirection allowedDirection) {
        throw new IllegalStateException(NO_TAP_POSITION_ERROR);
    }

    @Override
    public Optional<Direction> updateTapPositionToExceedNewA1(double deltaA1, int maxTapShift, AllowedDirection allowedDirection) {
        throw new IllegalStateException(NO_TAP_POSITION_ERROR);
    }

    @Override
    public Optional<Direction> updateTapPositionToReachNewA1(double deltaA1, int maxTapShift, AllowedDirection allowedDirection) {
        throw new IllegalStateException(NO_TAP_POSITION_ERROR);
    }

    @Override
    public Optional<Complex> getCutZ(double minZ, LoadFlowModel loadFlowModel) {
        Objects.requireNonNull(loadFlowModel);
        if (loadFlowModel == LoadFlowModel.DC) {
            if (FastMath.abs(this.x) < minZ) {
                return Optional.of(Complex.valueOf(0, minZ));
            }
        } else {
            double z = getZ();
            if (z < minZ) {
                double ksi = getKsi();
                double newR = minZ * FastMath.sin(ksi);
                double newX = minZ * FastMath.cos(ksi);
                return Optional.of(Complex.valueOf(newR, newX));
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean setMinZ(double minZ, LoadFlowModel loadFlowModel) {
        return getCutZ(minZ, loadFlowModel).map(z -> {
            SimplePiModel.this.r = z.getReal();
            SimplePiModel.this.x = z.getImaginary();
            return true;
        }).orElse(false);
    }

    @Override
    public void setBranch(LfBranch branch) {
        // nothing to set
    }

    @Override
    public int getTapPosition() {
        throw new IllegalStateException(NO_TAP_POSITION_ERROR);
    }

    @Override
    public PiModel setTapPosition(int tapPosition) {
        throw new IllegalStateException(NO_TAP_POSITION_ERROR);
    }

    @Override
    public Range<Integer> getTapPositionRange() {
        throw new IllegalStateException(NO_TAP_POSITION_ERROR);
    }
}
